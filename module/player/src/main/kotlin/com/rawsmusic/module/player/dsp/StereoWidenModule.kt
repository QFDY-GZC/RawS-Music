package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class StereoWidenModule : DspModule {
    override val id: Int = MODULE_ID
    override val name: String = "StereoWide"

    companion object {
        const val MODULE_ID = 2
        private const val FACTOR_SMOOTH = 0.003f
        private const val SIDE_HP_FREQ = 600f       // Side 高通截止频率
    }

    private var _isEnabled = AppPreferences.Equalizer.virtualizer > 0
    override val isEnabled: Boolean get() = _isEnabled

    var factor: Float = AppPreferences.Equalizer.virtualizer / 1000f
        set(value) {
            field = value.coerceIn(0f, 1f)
            _isEnabled = field > 0.01f
            AppPreferences.Equalizer.virtualizer = (field * 1000f).toInt().coerceIn(0, 1000)
        }

    private var smoothedFactor = 0f
    private var currentSampleRate = 44100

    // === Side 通道滤波器 (仅高频展宽，避免低频膨胀) ===
    private var sideHpX1 = 0f
    private var sideHpY1 = 0f
    private var sideHpAlpha = 0f

    // === 立体声联动压限器 ===
    private var limGain = 1.0f
    private var limAttack = 0.0f
    private var limRelease = 0.0f
    private val limThreshold = 0.95f  // 阈值放宽，不压瞬态

    private fun updateCoeffs() {
        val dt = 1.0f / currentSampleRate.toFloat()

        // Side 高通：600 Hz，只展宽中高频，低频保持稳定
        val rcSide = 1.0f / (2.0f * Math.PI.toFloat() * SIDE_HP_FREQ)
        sideHpAlpha = rcSide / (rcSide + dt)

        // 压限器：attack 1ms（保留瞬态），release 200ms（平滑恢复）
        limAttack = (1.0f - exp(-1.0 / (0.001 * currentSampleRate)).toFloat())
        limRelease = (1.0f - exp(-1.0 / (0.200 * currentSampleRate)).toFloat())
    }

    override fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        if (!enabled) {
            factor = 0f
            smoothedFactor = 0f
            sideHpX1 = 0f
            sideHpY1 = 0f
            limGain = 1.0f
        }
    }

    override fun process(buffer: ByteArray, byteCount: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        if (channels != 2 || factor <= 0.01f) return
        if (bitsPerSample != 16) return

        if (currentSampleRate != sampleRate) {
            currentSampleRate = sampleRate
            updateCoeffs()
        }

        val shortCount = byteCount / 2
        val shortBuffer = ByteBuffer.wrap(buffer, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val samples = ShortArray(shortCount)
        shortBuffer.get(samples)

        for (i in 0 until shortCount step 2) {
            smoothedFactor += (factor - smoothedFactor) * FACTOR_SMOOTH

            val L = samples[i].toFloat() / 32768f
            val R = samples[i + 1].toFloat() / 32768f

            // 1. M/S 变换（mid 原样通过，只放大 side）
            val mid = (L + R) * 0.5f
            val side = (L - R) * 0.5f

            // ==========================================
            // 2. Side 通道：频率依赖展宽
            //    高频：展宽因子 1.0x（max factor 时 side 高频 = 1.414x ≈ +3dB）
            //    低频：微弱展宽 0.1x（max factor 时 side 低频 = 1.1x ≈ +0.8dB）
            //    纯 M/S (side *= 1+width) 基础上加了频率分段更精细
            // ==========================================
            val sideHp = sideHpAlpha * (sideHpY1 + side - sideHpX1)
            sideHpX1 = side
            sideHpY1 = sideHp
            val sideLp = side - sideHp

            val highFreqGain = (1.0f + smoothedFactor * 1.0f) * 0.707f  // max 1.414x
            val lowFreqGain = 1.0f + smoothedFactor * 0.1f              // max 1.1x
            val outSide = sideLp * lowFreqGain + sideHp * highFreqGain

            // ==========================================
            // 3. 重组 L/R（mid 不变，side 展宽）
            // ==========================================
            var outL = mid + outSide
            var outR = mid - outSide

            // ==========================================
            // 4. 输出增益补偿：side 展宽会增加总能量，
            //    按展宽比例反向衰减，防止后续环节削波
            // ==========================================
            val compensateGain = 1.0f / (1.0f + smoothedFactor * 0.3f)
            outL *= compensateGain
            outR *= compensateGain

            // ==========================================
            // 5. 立体声联动压限器（安全网）
            //    阈值 0.95，attack 1ms，release 200ms
            //    不压瞬态，不泵浦，只截极端峰值
            // ==========================================
            val maxAbs = max(abs(outL), abs(outR))
            val targetGain = if (maxAbs > limThreshold) {
                limThreshold / maxAbs
            } else {
                1.0f
            }

            if (targetGain < limGain) {
                limGain += (targetGain - limGain) * limAttack
            } else {
                limGain += (targetGain - limGain) * limRelease
            }

            outL *= limGain
            outR *= limGain

            outL = max(-1f, min(1f, outL))
            outR = max(-1f, min(1f, outR))

            samples[i] = (outL * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            samples[i + 1] = (outR * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        shortBuffer.position(0)
        shortBuffer.put(samples)
    }

    override fun reset() {
        factor = 0f
        _isEnabled = false
        smoothedFactor = 0f
        sideHpX1 = 0f
        sideHpY1 = 0f
        limGain = 1.0f
    }
}
