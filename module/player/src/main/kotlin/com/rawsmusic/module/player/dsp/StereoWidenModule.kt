package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin fallback for the native continuous full-band stereo expander.
 *
 * The public amount is 0f..1f and maps linearly to a Side multiplier of
 * 1x..3x. A linked stereo peak controller follows the matrix so both channels
 * always receive the same gain and the image does not shift on peaks.
 */
class StereoWidenModule : DspModule {
    override val id: Int = MODULE_ID
    override val name: String = "StereoWidth"

    companion object {
        const val MODULE_ID = 2

        private const val PEAK_CEILING = 0.999f
        private const val AMOUNT_IDLE_THRESHOLD = 1.0e-5f
        private const val GAIN_IDLE_THRESHOLD = 1.0e-5f
        private const val LIMITER_ATTACK_SECONDS = 128f / 48000f
        private const val LIMITER_RELEASE_SECONDS = 8192f / 48000f
    }

    private var enabled = AppPreferences.Equalizer.virtualizer > 0
    override val isEnabled: Boolean
        get() = enabled ||
            currentAmount > 5.0e-4f ||
            limiterGain < 1f - GAIN_IDLE_THRESHOLD

    var factor: Float = AppPreferences.Equalizer.virtualizer / 1000f
        set(value) {
            field = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
            enabled = field > AMOUNT_IDLE_THRESHOLD || isEnabled
            AppPreferences.Equalizer.virtualizer =
                (field * 1000f).toInt().coerceIn(0, 1000)
        }

    private var currentSampleRate = 44100
    private var currentAmount = 0f
    private var limiterTargetGain = 1f
    private var limiterGain = 1f

    private var amountSmoothing = 0f
    private var limiterAttackStep = 0f
    private var limiterReleaseStep = 0f

    private var sampleScratch = ShortArray(0)

    init {
        updateCoefficients()
    }

    private fun exponentialCoefficient(milliseconds: Float): Float {
        val safeRate = currentSampleRate.coerceAtLeast(8000).toFloat()
        val seconds = milliseconds.coerceAtLeast(0.05f) * 0.001f
        return 1f - exp(-1.0 / (seconds.toDouble() * safeRate)).toFloat()
    }

    private fun updateCoefficients() {
        val safeRate = currentSampleRate.coerceAtLeast(8000).toFloat()
        amountSmoothing = exponentialCoefficient(8f)
        limiterAttackStep = (1f / (safeRate * LIMITER_ATTACK_SECONDS))
            .coerceIn(1.0e-6f, 1f)
        limiterReleaseStep = (1f / (safeRate * LIMITER_RELEASE_SECONDS))
            .coerceIn(1.0e-7f, 1f)
    }

    private fun resetState(resetAmount: Boolean) {
        limiterTargetGain = 1f
        limiterGain = 1f
        if (resetAmount) currentAmount = 0f
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            factor = 0f
        }
    }

    override fun process(
        buffer: ByteArray,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int
    ) {
        if (channels != 2 || bitsPerSample != 16 || byteCount <= 0) return
        if (!enabled &&
            factor <= AMOUNT_IDLE_THRESHOLD &&
            currentAmount <= 5.0e-4f &&
            limiterGain >= 1f - GAIN_IDLE_THRESHOLD
        ) {
            return
        }

        if (currentSampleRate != sampleRate && sampleRate > 0) {
            currentSampleRate = sampleRate
            updateCoefficients()
            resetState(resetAmount = true)
        }

        val shortCount = byteCount / 2
        if (shortCount < 2) return
        if (sampleScratch.size < shortCount) {
            sampleScratch = ShortArray(shortCount)
        }

        val shortBuffer = ByteBuffer.wrap(buffer, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        shortBuffer.get(sampleScratch, 0, shortCount)

        var index = 0
        while (index + 1 < shortCount) {
            currentAmount += (factor - currentAmount) * amountSmoothing
            currentAmount = currentAmount.coerceIn(0f, 1f)

            val left = sampleScratch[index] / 32768f
            val right = sampleScratch[index + 1] / 32768f

            val difference = left - right
            val expandedLeft = left + currentAmount * difference
            val expandedRight = right - currentAmount * difference

            val peak = max(abs(expandedLeft), abs(expandedRight))
            val immediateSafeGain = if (peak > PEAK_CEILING) {
                PEAK_CEILING / peak
            } else {
                1f
            }

            if (immediateSafeGain < limiterTargetGain) {
                limiterTargetGain = immediateSafeGain
            } else {
                limiterTargetGain = min(1f, limiterTargetGain + limiterReleaseStep)
            }

            if (limiterTargetGain < limiterGain) {
                limiterGain = max(limiterTargetGain, limiterGain - limiterAttackStep)
            } else if (limiterTargetGain > limiterGain) {
                limiterGain = min(limiterTargetGain, limiterGain + limiterReleaseStep)
            }

            val appliedGain = min(limiterGain, immediateSafeGain)
            val outputLeft = (expandedLeft * appliedGain).takeIf(Float::isFinite) ?: 0f
            val outputRight = (expandedRight * appliedGain).takeIf(Float::isFinite) ?: 0f

            sampleScratch[index] = if (outputLeft < 0f) {
                (outputLeft * 32768f).toInt()
            } else {
                (outputLeft * 32767f).toInt()
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            sampleScratch[index + 1] = if (outputRight < 0f) {
                (outputRight * 32768f).toInt()
            } else {
                (outputRight * 32767f).toInt()
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            index += 2
        }

        shortBuffer.position(0)
        shortBuffer.put(sampleScratch, 0, shortCount)

        if (factor <= AMOUNT_IDLE_THRESHOLD &&
            currentAmount <= 5.0e-4f &&
            limiterTargetGain >= 1f - GAIN_IDLE_THRESHOLD &&
            limiterGain >= 1f - GAIN_IDLE_THRESHOLD
        ) {
            currentAmount = 0f
            limiterTargetGain = 1f
            limiterGain = 1f
            enabled = false
        }
    }

    override fun reset() {
        factor = 0f
        enabled = false
        resetState(resetAmount = true)
    }
}
