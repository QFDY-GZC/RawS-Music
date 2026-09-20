package com.rawsmusic.module.player

import android.os.Process
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the blocking FFmpeg decode loop and its decoder-thread cleanup rules. */
internal class FfmpegDecoderLoopCoordinator(
    private val tag: String,
    private val decoderChunkWriter: FfmpegDecoderChunkWriter,
    private val gaplessAuditRegistry: DecoderGaplessAuditRegistry,
    private val isPlaying: () -> Boolean,
    private val setPlaying: (Boolean) -> Unit,
    private val isReleased: () -> Boolean,
    private val isStillCurrentPlayback: (String, Int) -> Boolean,
    private val consumePendingSeek: () -> PendingDecoderSeek.Request?,
    private val setPositionMs: (Long) -> Unit,
    private val canStartDecoderSeek: (serial: Long) -> Boolean,
    private val pausedSeekCommitGate: PausedSeekCommitGate,
    private val seekOutputBarrier: SeekOutputBarrier,
    private val activeDecoderHandle: () -> Long,
    private val activeRingBuffer: () -> RingBuffer?,
    private val activeStopToken: () -> DecoderStopToken,
    private val markDecoderDone: () -> Unit,
    private val setState: (FfmpegAudioPlayer.State) -> Unit,
    private val onPlaybackError: (String) -> Unit,
    private val decoderHandleTransferred: AtomicBoolean,
    private val clearDecoderHandleIfMatches: (Long) -> Unit,
    private val onFirstDecode: (sourcePath: String, generation: Int, decodedBytes: Int, elapsedMs: Double) -> Unit = { _, _, _, _ -> },
    private val onSeekCommitted: (serial: Long, targetMs: Long) -> Boolean = { _, _ -> true },
    private val onSeekFailed: (serial: Long, targetMs: Long, reason: String) -> Unit = { _, _, _ -> },
    private val onDecoderEof: (sourcePath: String, generation: Int, totalDecodedBytes: Long) -> Unit = { _, _, _ -> },
    private val onDecodeFailed: (sourcePath: String, generation: Int, result: Int) -> Unit = { _, _, _ -> },
) {
    fun run(
        handle: Long,
        ringBuffer: RingBuffer,
        generation: Int,
        sourcePath: String,
        stopToken: DecoderStopToken,
        decodeChunkSize: Int = 16384,
    ) {
        val ringBufferStartup = System.nanoTime()
        val onlineEntry = MusicSourceResolvedStreamRegistry.lookup(sourcePath)
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        }.onFailure {
            AppLogger.w(tag, "Unable to raise FFmpeg decoder thread priority", it)
        }
        val decodeBuffer = ByteArray(decodeChunkSize)
        val decoderSampleRate = runCatching { PlaybackDecoderBridge.getDecoderSampleRate(handle) }.getOrDefault(0)
        val decoderChannels = runCatching { PlaybackDecoderBridge.getDecoderChannels(handle) }.getOrDefault(0)
        val decoderBits = runCatching { PlaybackDecoderBridge.getDecoderBitsPerSample(handle) }.getOrDefault(0)
        val decoderBytesPerSample = when {
            decoderBits <= 1 -> 1
            decoderBits <= 16 -> 2
            else -> 4
        }
        val decoderFrameSize = (decoderChannels * decoderBytesPerSample).coerceAtLeast(1)
        gaplessAuditRegistry.ensure(
            decoderSerial = handle,
            sourcePath = sourcePath,
            outputSampleRate = decoderSampleRate,
            outputFrameSize = decoderFrameSize,
        )

        AppLogger.i(
            tag,
            "Decoder thread started, handle=$handle, decodeChunkSize=$decodeChunkSize, " +
                "isPlaying=${isPlaying()}",
        )
        onlineEntry?.let {
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} DECODER_THREAD_START " +
                    "generation=${it.generation} handle=0x${handle.toString(16)} " +
                    "chunkBytes=$decodeChunkSize",
            )
        }

        var decodeCallCount = 0
        var totalDecodedBytes = 0L
        var totalWrittenToRingBuffer = 0L
        var reachedEofForThisDecoder = false
        try {
            while (isPlaying() && !isReleased() && !stopToken.isStopRequested) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(tag, "Decoder thread: song changed, exiting")
                    break
                }

                val seekRequest = consumePendingSeek()
                if (seekRequest != null) {
                    val seekSerial = seekRequest.serial
                    val seekTarget = seekRequest.targetMs
                    if (!canStartDecoderSeek(seekSerial)) {
                        seekOutputBarrier.cancel(seekSerial)
                        AppLogger.w(
                            tag,
                            ">>> DECODER seek rejected by native barrier: " +
                                "seekTarget=$seekTarget serial=$seekSerial",
                        )
                        continue
                    }
                    gaplessAuditRegistry.invalidate(handle, "seek_serial_$seekSerial")
                    ringBuffer.clear()
                    val seekOk = PlaybackDecoderBridge.seekDecoder(handle, seekTarget)
                    if (!seekOk) {
                        seekOutputBarrier.cancel(seekSerial)
                        onSeekFailed(seekSerial, seekTarget, "ffmpeg_seek_failed")
                        AppLogger.w(
                            tag,
                            ">>> DECODER seek failed: seekTarget=$seekTarget serial=$seekSerial",
                        )
                        continue
                    }
                    ringBuffer.clear()
                    if (!onSeekCommitted(seekSerial, seekTarget)) {
                        seekOutputBarrier.cancel(seekSerial)
                        AppLogger.w(
                            tag,
                            ">>> DECODER seek commit rejected as stale: " +
                                "seekTarget=$seekTarget serial=$seekSerial",
                        )
                        continue
                    }
                    setPositionMs(seekTarget)
                    pausedSeekCommitGate.markCommitted(seekSerial)
                    seekOutputBarrier.markCommitted(seekSerial)
                    AppLogger.w(
                        tag,
                        ">>> DECODER seek done: seekTarget=$seekTarget, " +
                            "serial=$seekSerial barrier=${seekOutputBarrier.describe()}",
                    )
                    continue
                }

                if (stopToken.isStopRequested) {
                    AppLogger.w(
                        tag,
                        "Decoder thread: stop requested before decodeChunk, " +
                            "exiting safely token=${stopToken.label}",
                    )
                    break
                }

                val ringBufferAvailable = ringBuffer.available()
                if (ringBuffer.isClosed()) {
                    AppLogger.w(
                        tag,
                        "Decoder thread: RingBuffer already closed, " +
                            "decodeCalls=$decodeCallCount totalDecoded=$totalDecodedBytes " +
                            "totalWrittenToRb=$totalWrittenToRingBuffer",
                    )
                    break
                }

                val decoded = PlaybackDecoderBridge.decodeChunk(
                    handle,
                    decodeBuffer,
                    0,
                    decodeBuffer.size,
                )
                decodeCallCount++
                if (decodeCallCount == 1) {
                    val firstMs = (System.nanoTime() - ringBufferStartup) / 1_000_000.0
                    AppLogger.w(
                        tag,
                        "Decoder thread: FIRST decodeChunk returned $decoded bytes " +
                            "(started ${"%.1f".format(firstMs)}ms ago, rbAvail=$ringBufferAvailable)",
                    )
                    onFirstDecode(sourcePath, generation, decoded, firstMs)
                    onlineEntry?.let {
                        AppLogger.i(
                            tag,
                            "${OnlinePlaybackDiagnostics.PREFIX} FIRST_DECODE " +
                                "generation=${it.generation} result=$decoded " +
                                "elapsedMs=${"%.1f".format(firstMs)} " +
                                "rbAvailable=$ringBufferAvailable",
                        )
                    }
                }
                if (decodeCallCount == 1 || decodeCallCount % 5000 == 0) {
                    AppLogger.d(
                        tag,
                        "Decoder thread: decodeChunk #$decodeCallCount returned $decoded, " +
                            "rb.available=$ringBufferAvailable, rb.isClosed=${ringBuffer.isClosed()}",
                    )
                }

                when {
                    decoded > 0 -> {
                        gaplessAuditRegistry.recordDecodedBytes(handle, decoded)
                        val chunk = decoderChunkWriter.write(
                            decodeBuffer = decodeBuffer,
                            decoded = decoded,
                            decodeCallCount = decodeCallCount,
                            ringBuffer = ringBuffer,
                        )
                        totalDecodedBytes += decoded
                        totalWrittenToRingBuffer += chunk.written.coerceAtLeast(0)
                        if (chunk.written < 0) {
                            AppLogger.w(
                                tag,
                                "Decoder thread: ring buffer closed " +
                                    "(decodeCalls=$decodeCallCount totalDecoded=$totalDecodedBytes " +
                                    "totalWritten=$totalWrittenToRingBuffer)",
                            )
                            break
                        }
                    }

                    decoded == -1 -> {
                        reachedEofForThisDecoder = true
                        val ownsActiveDecoder = DecoderCompletionOwnership.ownsActiveDecoder(
                            activeHandle = activeDecoderHandle(),
                            loopHandle = handle,
                            sameRingBuffer = activeRingBuffer() === ringBuffer,
                            sameStopToken = activeStopToken() === stopToken,
                            stopRequested = stopToken.isStopRequested,
                        )
                        AppLogger.i(
                            tag,
                            "Decoder thread: EOF reached (activeOwner=$ownsActiveDecoder, " +
                                "decodeCalls=$decodeCallCount totalDecoded=$totalDecodedBytes " +
                                "totalWrittenToRb=$totalWrittenToRingBuffer " +
                                "rb.available=${ringBuffer.available()})",
                        )
                        if (ownsActiveDecoder) {
                            markDecoderDone()
                        }
                        val gaplessAudit = gaplessAuditRegistry.finish(handle)
                        onDecoderEof(sourcePath, generation, totalDecodedBytes)
                        if (gaplessAudit != null) {
                            AppLogger.i(
                                tag,
                                "Decoder gapless EOF audit: verification=${gaplessAudit.verification} " +
                                    "decodedFrames=${gaplessAudit.decodedOutputFrames} " +
                                    "expectedAudible=${gaplessAudit.expectedAudibleOutputFrames}",
                            )
                        }
                        ringBuffer.markEOF()
                        break
                    }

                    else -> {
                        AppLogger.e(
                            tag,
                            "Decoder thread: decode error: $decoded " +
                                "(decodeCalls=$decodeCallCount totalDecoded=$totalDecodedBytes " +
                                "totalWrittenToRb=$totalWrittenToRingBuffer)",
                        )
                        gaplessAuditRegistry.invalidate(handle, "decode_error_$decoded")
                        onDecodeFailed(sourcePath, generation, decoded)
                        onlineEntry?.let {
                            AppLogger.e(
                                tag,
                                "${OnlinePlaybackDiagnostics.PREFIX} DECODE_FAIL " +
                                    "generation=${it.generation} result=$decoded calls=$decodeCallCount " +
                                    "decodedBytes=$totalDecodedBytes",
                            )
                        }
                        ringBuffer.close()
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            gaplessAuditRegistry.invalidate(handle, "interrupted")
            AppLogger.w(tag, "Decoder thread interrupted")
        } catch (error: Exception) {
            gaplessAuditRegistry.invalidate(handle, "fatal_${error.javaClass.simpleName}")
            AppLogger.e(tag, "Decoder thread fatal error", error)
            runCatching {
                // This decoder may be the retired owner of the previous track.  During a
                // rapid replacement (especially across formats) FFmpeg can surface an
                // exception only after the new playback generation has already started.
                // Never let that stale thread clear the process-wide playing flag or move
                // the replacement session to ERROR.  Its local ring/handle still need
                // normal cleanup in finally.
                val ownsCurrentPlayback = isStillCurrentPlayback(sourcePath, generation)
                ringBuffer.close()
                if (ownsCurrentPlayback) {
                    setPlaying(false)
                    setState(FfmpegAudioPlayer.State.ERROR)
                    onPlaybackError("解码线程异常: ${error.message}")
                } else {
                    AppLogger.w(
                        tag,
                        "Decoder thread fatal error belongs to stale generation; " +
                            "replacement playback state left untouched source=$sourcePath gen=$generation",
                    )
                }
            }.onFailure { notifyError ->
                AppLogger.e(tag, "Error notifying decoder failure", notifyError)
            }
        } finally {
            finishDecoder(
                handle = handle,
                ringBuffer = ringBuffer,
                stopToken = stopToken,
                reachedEofForThisDecoder = reachedEofForThisDecoder,
            )
        }
    }

    private fun finishDecoder(
        handle: Long,
        ringBuffer: RingBuffer,
        stopToken: DecoderStopToken,
        reachedEofForThisDecoder: Boolean,
    ) {
        if (stopToken.isStopRequested) {
            if (stopToken.shouldCloseRetiredHandleInOwnerThread) {
                AppLogger.w(
                    tag,
                    "Decoder thread ended (stop requested token=${stopToken.label} " +
                        "reason=${stopToken.reason}), closing retired handle=$handle in owner thread",
                )
                gaplessAuditRegistry.discard(handle, "stop_owner_close")
                runCatching { PlaybackDecoderBridge.closeDecoder(handle) }
                    .onFailure { error -> AppLogger.e(tag, "Error closing retired decoder in owner thread", error) }
                clearDecoderHandleIfMatches(handle)
            } else {
                gaplessAuditRegistry.discard(handle, "stop_without_owner_close")
                AppLogger.w(
                    tag,
                    "Decoder thread ended (stop requested token=${stopToken.label} " +
                        "reason=${stopToken.reason}), NOT closing handle=$handle",
                )
            }
        } else if (reachedEofForThisDecoder && activeDecoderHandle() == handle) {
            AppLogger.i(tag, "Decoder thread ended (active EOF), keeping handle=$handle for potential seek")
        } else if (decoderHandleTransferred.getAndSet(false)) {
            AppLogger.w(tag, "Decoder thread ended but handle transferred to new thread, NOT closing handle=$handle")
        } else {
            AppLogger.i(tag, "Decoder thread ended (error/interrupt), closing handle=$handle")
            gaplessAuditRegistry.discard(handle, "thread_error_or_interrupt")
            runCatching { PlaybackDecoderBridge.closeDecoder(handle) }
                .onFailure { error -> AppLogger.e(tag, "Error closing decoder in thread finally", error) }
            clearDecoderHandleIfMatches(handle)
        }
        AppLogger.i(tag, "Decoder thread cleanup done")
    }
}
