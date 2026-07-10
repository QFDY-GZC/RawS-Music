package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Owns the small, mutable state needed while an AudioTrack crossfade is active.
 *
 * The main playback loop still decides when to switch decoder ownership. This
 * helper only tracks crossfade progress, decodes the next chunk, converts it to
 * the current output container when needed, and mixes it into the current buffer.
 */
internal class CrossfadeTransitionController(private val tag: String) {
    data class MixResult(
        val nextRead: Int,
        val mixedBytes: Int,
        val progressBeforeMix: Float,
        val completed: Boolean
    )

    @Volatile
    var active: Boolean = false
        private set

    private var totalFrames: Long = 0L
    private var bytesMixed: Long = 0L
    @Volatile
    private var activeTargetPath: String? = null
    private var nextReadBuffer = ByteArray(0)
    private var nextMixBuffer = ByteArray(0)

    val mixedBytesSoFar: Long
        get() = bytesMixed

    val targetPath: String?
        get() = activeTargetPath

    fun reset(reason: String) {
        if (active || bytesMixed != 0L || totalFrames != 0L) {
            AppLogger.d(tag, "Crossfade: reset reason=$reason bytesMixed=$bytesMixed totalFrames=$totalFrames")
        }
        active = false
        totalFrames = 0L
        bytesMixed = 0L
        activeTargetPath = null
    }

    fun start(targetPath: String, durationMs: Int, sampleRate: Int, bufferSize: Int, remainingMs: Long): Boolean {
        if (durationMs <= 0 || sampleRate <= 0) return false
        totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        bytesMixed = 0L
        ensureBuffers(bufferSize)
        activeTargetPath = targetPath
        active = true
        AppLogger.d(tag, "Crossfade: START target=$targetPath remaining=${remainingMs}ms totalFrames=$totalFrames")
        return true
    }

    fun elapsedMs(bytesPerMs: Double): Long {
        return if (bytesPerMs > 0.0) (bytesMixed.toDouble() / bytesPerMs).toLong() else 0L
    }

    fun mixNextIntoCurrent(
        currentBuf: ByteArray,
        currentRead: Int,
        next: GaplessNextDecoder.Prepared,
        frameSize: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        bitsPerSample: Int
    ): MixResult {
        if (!active || currentRead <= 0) {
            return MixResult(nextRead = 0, mixedBytes = 0, progressBeforeMix = 0f, completed = false)
        }
        val alignedCurrent = PcmFrameAligner.alignDown(currentRead, frameSize)
        val outputBytesPerSample = when {
            outputIsFloat -> 4
            outputIsPacked24 -> 3
            bitsPerSample <= 16 -> 2
            else -> 4
        }
        val channels = (frameSize / outputBytesPerSample).coerceAtLeast(1)
        val currentFrames = (alignedCurrent / frameSize).coerceAtLeast(0)
        // FFmpegBridge outputs 24/32-bit PCM as S32LE.  When the Android output
        // container is packed24/S16, the next decoder therefore needs more source
        // bytes than the current output buffer length.  Under-reading here causes
        // partial-frame mixes and Direct-mode motor-like noise.
        val nextBytesPerSample = if (next.bitsPerSample <= 16) 2 else 4
        val desiredNextRead = (currentFrames * channels * nextBytesPerSample).coerceAtLeast(0)
        ensureBuffers(maxOf(alignedCurrent, desiredNextRead).coerceAtLeast(frameSize).coerceAtMost(currentBuf.size * 2))

        val decodeLimit = desiredNextRead.coerceAtMost(nextReadBuffer.size)
        val nextRead = try {
            FFmpegBridge.decodeChunk(next.handle, nextReadBuffer, 0, decodeLimit)
        } catch (t: Throwable) {
            AppLogger.e(tag, "Crossfade: next decoder read failed path=${next.path}", t)
            -1
        }
        if (nextRead <= 0) {
            AppLogger.w(tag, "Crossfade: next decoder returned $nextRead path=${next.path}; continuing current buffer without mix")
            return MixResult(
                nextRead = nextRead,
                mixedBytes = 0,
                progressBeforeMix = progress(frameSize),
                completed = false
            )
        }

        val totalBytes = totalBytes(frameSize)
        val progressBefore = if (totalBytes > 0L) {
            (bytesMixed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val gainOut = PcmCrossfadeMixer.gainOut(progressBefore)
        val gainIn = PcmCrossfadeMixer.gainIn(progressBefore)

        var mixNextLen = nextRead
        val mixNextBuffer = when {
            outputIsFloat && next.bitsPerSample > 16 -> {
                mixNextLen = PcmSampleConverter.s32ToFloatPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            outputIsFloat && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToFloatPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            outputIsPacked24 && next.bitsPerSample > 16 -> {
                mixNextLen = PcmSampleConverter.s32ToS24PackedPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            outputIsPacked24 && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToS24PackedPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            bitsPerSample <= 16 && next.bitsPerSample > 16 -> {
                mixNextLen = PcmSampleConverter.s32ToS16Pcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            bitsPerSample > 16 && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToS32Pcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            else -> nextReadBuffer
        }

        val alignedNext = PcmFrameAligner.alignDown(mixNextLen, frameSize)
        val mixLen = minOf(alignedCurrent, alignedNext)
        if (mixLen > 0) {
            PcmCrossfadeMixer.mixInPlace(
                currentBuf = currentBuf,
                currentLen = mixLen,
                nextBuf = mixNextBuffer,
                nextLen = mixLen,
                gainOut = gainOut,
                gainIn = gainIn,
                outputIsFloat = outputIsFloat,
                bitsPerSample = bitsPerSample,
                outputIsPacked24 = outputIsPacked24
            )
        }
        bytesMixed += alignedCurrent.coerceAtLeast(0)
        return MixResult(
            nextRead = nextRead,
            mixedBytes = mixLen,
            progressBeforeMix = progressBefore,
            completed = isComplete(frameSize)
        )
    }

    private fun ensureBuffers(size: Int) {
        val safeSize = size.coerceAtLeast(1)
        if (nextReadBuffer.size < safeSize) nextReadBuffer = ByteArray(safeSize)
        if (nextMixBuffer.size < safeSize) nextMixBuffer = ByteArray(safeSize)
    }

    private fun totalBytes(frameSize: Int): Long {
        return if (frameSize > 0) totalFrames * frameSize.toLong() else 0L
    }

    private fun progress(frameSize: Int): Float {
        val total = totalBytes(frameSize)
        return if (total > 0L) (bytesMixed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    }

    private fun isComplete(frameSize: Int): Boolean {
        val total = totalBytes(frameSize)
        return total > 0L && bytesMixed >= total
    }
}
