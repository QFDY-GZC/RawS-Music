package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class StereoWidenModule : DspModule {
    override val id: Int = MODULE_ID
    override val name: String = "StereoWide"

    companion object {
        const val MODULE_ID = 2
        private const val FACTOR_SMOOTH = 0.003f
        private const val SIDE_HP_FREQ = 420f       // 保护低频/人声，只展开中高频空间线索
        private const val ALLPASS_FREQ = 2400f      // 高频极轻微去相关中心频率
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

    // === 高频 side 轻微 all-pass 去相关 ===
    private var apX1 = 0f
    private var apY1 = 0f
    private var apA = 0f

    // === 立体声联动压限器 ===
    private var limGain = 1.0f
    private var limAttack = 0.0f
    private var limRelease = 0.0f
    private val limThreshold = 0.95f  // 阈值放宽，不压瞬态

    private fun updateCoeffs() {
        val dt = 1.0f / currentSampleRate.toFloat()

        // Side 高通：保护低频和人声基音，主要展开中高频空间线索
        val rcSide = 1.0f / (2.0f * Math.PI.toFloat() * SIDE_HP_FREQ)
        sideHpAlpha = rcSide / (rcSide + dt)

        val t = kotlin.math.tan(Math.PI.toFloat() * ALLPASS_FREQ / currentSampleRate.toFloat())
        apA = (t - 1.0f) / (t + 1.0f)

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
            apX1 = 0f
            apY1 = 0f
            limGain = 1.0f
        }
    }

    private fun processAllPass(x: Float): Float {
        val y = apA * x + apX1 - apA * apY1
        apX1 = x
        apY1 = y
        return y
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
            // 2. Side 通道：自然展宽
            //    低频/人声基音保护，中高频轻微去相关，避免空洞和金属感。
            // ==========================================
            val sideHp = sideHpAlpha * (sideHpY1 + side - sideHpX1)
            sideHpX1 = side
            sideHpY1 = sideHp
            val sideLp = side - sideHp

            val amount = sqrt(smoothedFactor.coerceIn(0f, 1f))
            val decorSideHp = processAllPass(sideHp)
            val decorMix = amount * 0.08f
            val widenedHp = sideHp * (1.0f - decorMix) + decorSideHp * decorMix
            val highFreqGain = 1.0f + amount * 1.05f
            val lowFreqGain = 1.0f
            var outSide = sideLp * lowFreqGain + widenedHp * highFreqGain

            val sideLimit = (abs(mid) + 0.12f) * (1.20f + amount * 0.38f)
            val sideAbs = abs(outSide)
            if (sideAbs > sideLimit) {
                val soft = sideLimit + (sideAbs - sideLimit) * 0.35f
                outSide *= soft / (sideAbs + 1e-12f)
            }

            // ==========================================
            // 3. 重组并做 dry/wet 混合，避免中心声像突变
            // ==========================================
            val outMid = mid
            val wetL = outMid + outSide
            val wetR = outMid - outSide
            val wetMix = 0.62f + amount * 0.22f
            var outL = L * (1.0f - wetMix) + wetL * wetMix
            var outR = R * (1.0f - wetMix) + wetR * wetMix

            val compensateGain = 1.0f / (1.0f + amount * 0.025f)
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
        apX1 = 0f
        apY1 = 0f
        limGain = 1.0f
    }
}
