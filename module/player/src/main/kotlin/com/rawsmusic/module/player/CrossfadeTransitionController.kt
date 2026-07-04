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
    private var nextReadBuffer = ByteArray(0)
    private var nextMixBuffer = ByteArray(0)

    val mixedBytesSoFar: Long
        get() = bytesMixed

    fun reset(reason: String) {
        if (active || bytesMixed != 0L || totalFrames != 0L) {
            AppLogger.d(tag, "Crossfade: reset reason=$reason bytesMixed=$bytesMixed totalFrames=$totalFrames")
        }
        active = false
        totalFrames = 0L
        bytesMixed = 0L
    }

    fun start(durationMs: Int, sampleRate: Int, bufferSize: Int, remainingMs: Long): Boolean {
        if (durationMs <= 0 || sampleRate <= 0) return false
        totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        bytesMixed = 0L
        ensureBuffers(bufferSize)
        active = true
        AppLogger.d(tag, "Crossfade: START remaining=${remainingMs}ms totalFrames=$totalFrames")
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
        ensureBuffers(currentRead.coerceAtLeast(frameSize).coerceAtMost(currentBuf.size))

        val decodeLimit = currentRead.coerceAtMost(nextReadBuffer.size)
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
        val mixNextBuffer = if (outputIsFloat && next.bitsPerSample > 16) {
            mixNextLen = PcmSampleConverter.s32ToFloatPcm(nextReadBuffer, nextRead, nextMixBuffer)
            nextMixBuffer
        } else if (outputIsPacked24 && next.bitsPerSample > 16) {
            mixNextLen = PcmSampleConverter.s32ToS24PackedPcm(nextReadBuffer, nextRead, nextMixBuffer)
            nextMixBuffer
        } else {
            nextReadBuffer
        }

        val alignedCurrent = PcmFrameAligner.alignDown(currentRead, frameSize)
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
