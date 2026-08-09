package com.rawsmusic.module.player

import android.os.Process
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the blocking FFmpeg decode loop and its decoder-thread cleanup rules. */
internal class FfmpegDecoderLoopCoordinator(
    private val tag: String,
    private val decoderChunkWriter: FfmpegDecoderChunkWriter,
    private val isPlaying: () -> Boolean,
    private val setPlaying: (Boolean) -> Unit,
    private val isReleased: () -> Boolean,
    private val isStillCurrentPlayback: (String, Int) -> Boolean,
    private val consumePendingSeek: () -> Long?,
    private val pendingSeekSerial: () -> Long,
    private val setPositionMs: (Long) -> Unit,
    private val markAudioTrackFlush: () -> Unit,
    private val flushNativePcmBuffer: (String) -> Unit,
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

                val seekTarget = consumePendingSeek()
                if (seekTarget != null) {
                    val seekSerial = pendingSeekSerial()
                    ringBuffer.clear()
                    val seekOk = FFmpegBridge.seekDecoder(handle, seekTarget)
                    if (seekSerial != pendingSeekSerial()) {
                        AppLogger.w(
                            tag,
                            ">>> DECODER seek superseded: seekTarget=$seekTarget " +
                                "serial=$seekSerial latest=${pendingSeekSerial()}",
                        )
                        continue
                    }
                    if (!seekOk) {
                        seekOutputBarrier.cancel(seekSerial)
                        AppLogger.w(
                            tag,
                            ">>> DECODER seek failed: seekTarget=$seekTarget serial=$seekSerial",
                        )
                        continue
                    }
                    ringBuffer.clear()
                    setPositionMs(seekTarget)
                    pausedSeekCommitGate.markCommitted(seekSerial)
                    seekOutputBarrier.markCommitted(seekSerial)
                    markAudioTrackFlush()
                    flushNativePcmBuffer("decoder_pending_seek")
                    AppLogger.w(
                        tag,
                        ">>> DECODER seek done: seekTarget=$seekTarget, " +
                            "barrier=${seekOutputBarrier.describe()}",
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

                val decoded = FFmpegBridge.decodeChunk(
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
            AppLogger.w(tag, "Decoder thread interrupted")
        } catch (error: Exception) {
            AppLogger.e(tag, "Decoder thread fatal error", error)
            runCatching {
                setPlaying(false)
                ringBuffer.close()
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(FfmpegAudioPlayer.State.ERROR)
                    onPlaybackError("解码线程异常: ${error.message}")
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
                runCatching { FFmpegBridge.closeDecoder(handle) }
                    .onFailure { error -> AppLogger.e(tag, "Error closing retired decoder in owner thread", error) }
                clearDecoderHandleIfMatches(handle)
            } else {
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
            runCatching { FFmpegBridge.closeDecoder(handle) }
                .onFailure { error -> AppLogger.e(tag, "Error closing decoder in thread finally", error) }
            clearDecoderHandleIfMatches(handle)
        }
        AppLogger.i(tag, "Decoder thread cleanup done")
    }
}
