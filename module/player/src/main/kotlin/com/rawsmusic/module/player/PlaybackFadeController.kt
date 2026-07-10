package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Applies short playback fade envelopes directly on the PCM buffer that is about
 * to be written.  The owner decides when to arm a fade; this class only mutates
 * samples and tracks completion.
 */
internal class PlaybackFadeController(private val tag: String) {
    private enum class Direction { IN, OUT }

    @Volatile
    private var direction: Direction? = null
    @Volatile
    private var durationMs: Int = 0
    @Volatile
    private var processedFrames: Long = 0L
    @Volatile
    private var reason: String = ""

    val isActive: Boolean
        get() = direction != null

    fun startFadeIn(durationMs: Int, reason: String) {
        start(Direction.IN, durationMs, reason)
    }

    fun startFadeOut(durationMs: Int, reason: String) {
        start(Direction.OUT, durationMs, reason)
    }

    fun clear(reason: String) {
        if (direction != null) {
            AppLogger.d(tag, "PlaybackFade: clear active=$direction oldReason=${this.reason} reason=$reason")
        }
        direction = null
        durationMs = 0
        processedFrames = 0L
        this.reason = ""
    }

    fun processInPlace(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean
    ) {
        val activeDirection = direction ?: return
        if (length <= 0 || sampleRate <= 0 || frameSize <= 0) return
        val totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        val frames = length / frameSize
        if (frames <= 0) return

        for (frame in 0 until frames) {
            val progress = ((processedFrames + frame).toDouble() / totalFrames.toDouble()).coerceIn(0.0, 1.0)
            val gain = when (activeDirection) {
                Direction.IN -> progress.toFloat()
                Direction.OUT -> (1.0 - progress).toFloat()
            }.coerceIn(0f, 1f)
            applyGainToFrame(
                buffer = buffer,
                frameOffset = offset + frame * frameSize,
                frameSize = frameSize,
                bitsPerSample = bitsPerSample,
                outputIsFloat = outputIsFloat,
                outputIsPacked24 = outputIsPacked24,
                gain = gain
            )
        }

        processedFrames += frames.toLong()
        if (processedFrames >= totalFrames) {
            if (activeDirection == Direction.OUT) {
                silenceTail(
                    buffer = buffer,
                    offset = offset,
                    length = length,
                    frameSize = frameSize,
                    bitsPerSample = bitsPerSample,
                    outputIsFloat = outputIsFloat,
                    outputIsPacked24 = outputIsPacked24
                )
            }
            AppLogger.d(tag, "PlaybackFade: complete direction=$activeDirection reason=$reason durationMs=$durationMs")
            clear("complete")
        }
    }

    private fun start(newDirection: Direction, requestedDurationMs: Int, newReason: String) {
        val safeMs = requestedDurationMs.coerceAtLeast(1)
        direction = newDirection
        durationMs = safeMs
        processedFrames = 0L
        reason = newReason
        AppLogger.d(tag, "PlaybackFade: start direction=$newDirection durationMs=$safeMs reason=$newReason")
    }

    private fun applyGainToFrame(
        buffer: ByteArray,
        frameOffset: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        gain: Float
    ) {
        when {
            outputIsFloat -> applyFloatGain(buffer, frameOffset, frameSize, gain)
            outputIsPacked24 -> applyS24PackedGain(buffer, frameOffset, frameSize, gain)
            bitsPerSample <= 16 -> applyS16Gain(buffer, frameOffset, frameSize, gain)
            else -> applyS32Gain(buffer, frameOffset, frameSize, gain)
        }
    }

    private fun applyFloatGain(buffer: ByteArray, frameOffset: Int, frameSize: Int, gain: Float) {
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var sampleOffset = frameOffset
        val end = frameOffset + frameSize
        while (sampleOffset + 4 <= end) {
            bb.putFloat(sampleOffset, bb.getFloat(sampleOffset) * gain)
            sampleOffset += 4
        }
    }

    private fun applyS32Gain(buffer: ByteArray, frameOffset: Int, frameSize: Int, gain: Float) {
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var sampleOffset = frameOffset
        val end = frameOffset + frameSize
        while (sampleOffset + 4 <= end) {
            val mixed = (bb.getInt(sampleOffset).toDouble() * gain.toDouble())
                .roundToIntSafely(Int.MIN_VALUE, Int.MAX_VALUE)
            bb.putInt(sampleOffset, mixed)
            sampleOffset += 4
        }
    }

    private fun applyS16Gain(buffer: ByteArray, frameOffset: Int, frameSize: Int, gain: Float) {
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var sampleOffset = frameOffset
        val end = frameOffset + frameSize
        while (sampleOffset + 2 <= end) {
            val mixed = (bb.getShort(sampleOffset).toDouble() * gain.toDouble())
                .roundToIntSafely(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(sampleOffset, mixed.toShort())
            sampleOffset += 2
        }
    }

    private fun applyS24PackedGain(buffer: ByteArray, frameOffset: Int, frameSize: Int, gain: Float) {
        var sampleOffset = frameOffset
        val end = frameOffset + frameSize
        while (sampleOffset + 3 <= end) {
            val mixed = (readS24LE(buffer, sampleOffset).toDouble() * gain.toDouble())
                .roundToIntSafely(-8_388_608, 8_388_607)
            writeS24LE(buffer, sampleOffset, mixed)
            sampleOffset += 3
        }
    }

    private fun silenceTail(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean
    ) {
        val frameCount = length / frameSize
        val silenceStart = offset + frameCount * frameSize
        if (silenceStart < offset + length) return
        // The frame loop already wrote the tail with near-zero gain.  Keep this
        // hook explicit for formats whose last frame lands exactly on completion.
        if (frameCount <= 0) return
        val lastFrame = offset + (frameCount - 1) * frameSize
        applyGainToFrame(buffer, lastFrame, frameSize, bitsPerSample, outputIsFloat, outputIsPacked24, 0f)
    }

    private fun readS24LE(buf: ByteArray, offset: Int): Int {
        var value = (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16)
        if ((value and 0x00800000) != 0) value = value or -0x01000000
        return value
    }

    private fun writeS24LE(buf: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(-8_388_608, 8_388_607)
        buf[offset] = (v and 0xff).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xff).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xff).toByte()
    }

    private fun Double.roundToIntSafely(min: Int, max: Int): Int =
        coerceIn(min.toDouble(), max.toDouble()).roundToInt()
}
