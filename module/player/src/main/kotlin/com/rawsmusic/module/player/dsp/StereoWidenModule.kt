package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin fallback for the native continuous full-band stereo expander.
 *
 * Amount 0f is an exact bypass. The Mid component remains unchanged and only
 * Side is multiplied from 1x to 3x. Peak management is deliberately left to
 * the global limiter later in the DSP chain.
 */
class StereoWidenModule : DspModule {
    override val id: Int = MODULE_ID
    override val name: String = "StereoWidth"

    companion object {
        const val MODULE_ID = 2
    }

    override val isEnabled: Boolean
        get() = factor > 0f

    var factor: Float = AppPreferences.Equalizer.virtualizer / 1000f
        set(value) {
            field = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            AppPreferences.Equalizer.virtualizer =
                (field * 1000f).toInt().coerceIn(0, 1000)
        }

    private var sampleScratch = ShortArray(0)

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) factor = 0f
    }

    override fun process(
        buffer: ByteArray,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int
    ) {
        if (!isEnabled || channels != 2 || bitsPerSample != 16 || byteCount <= 0) return

        val shortCount = byteCount / 2
        if (shortCount < 2) return
        if (sampleScratch.size < shortCount) sampleScratch = ShortArray(shortCount)

        val shortBuffer = ByteBuffer.wrap(buffer, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        shortBuffer.get(sampleScratch, 0, shortCount)

        val width = 1f + 2f * factor
        var index = 0
        while (index + 1 < shortCount) {
            val left = sampleScratch[index] / 32768f
            val right = sampleScratch[index + 1] / 32768f
            val mid = (left + right) * 0.5f
            val outputLeft = mid + (left - mid) * width
            val outputRight = mid + (right - mid) * width

            sampleScratch[index] = floatToPcm16(outputLeft)
            sampleScratch[index + 1] = floatToPcm16(outputRight)
            index += 2
        }

        shortBuffer.position(0)
        shortBuffer.put(sampleScratch, 0, shortCount)
    }

    override fun reset() {
        factor = 0f
    }

    private fun floatToPcm16(value: Float): Short {
        val finite = value.takeIf(Float::isFinite) ?: 0f
        val scaled = if (finite < 0f) finite * 32768f else finite * 32767f
        return scaled.toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
