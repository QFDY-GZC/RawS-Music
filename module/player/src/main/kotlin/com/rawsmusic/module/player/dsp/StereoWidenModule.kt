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
        private const val MID_HP_FREQ = 2500f       // Mid 高通截止频率（人声临场感）
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

    // === Side 通道滤波器 ===
    private var sideHpX1 = 0f
    private var sideHpY1 = 0f
    private var sideHpAlpha = 0f

    // === Mid 通道高频滤波器 (人声临场感增强) ===
    private var midHpX1 = 0f
    private var midHpY1 = 0f
    private var midHpAlpha = 0f

    // === 立体声联动压限器 ===
    private var limGain = 1.0f
    private var limAttack = 0.0f
    private var limRelease = 0.0f
    private val limThreshold = 0.85f

    private fun updateCoeffs() {
        val dt = 1.0f / currentSampleRate.toFloat()

        // Side 高通：600 Hz，提取侧边高频
        val rcSide = 1.0f / (2.0f * Math.PI.toFloat() * SIDE_HP_FREQ)
        sideHpAlpha = rcSide / (rcSide + dt)

        // Mid 高通：2500 Hz，提取人声临场感/齿音频段
        val rcMid = 1.0f / (2.0f * Math.PI.toFloat() * MID_HP_FREQ)
        midHpAlpha = rcMid / (rcMid + dt)

        // 压限器参数
        limAttack = (1.0f - exp(-1.0 / (0.0001 * currentSampleRate)).toFloat())
        limRelease = (1.0f - exp(-1.0 / (0.150 * currentSampleRate)).toFloat())
    }

    override fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        if (!enabled) {
            factor = 0f
            smoothedFactor = 0f
            sideHpX1 = 0f
            sideHpY1 = 0f
            midHpX1 = 0f
            midHpY1 = 0f
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

            // 1. M/S 变换
            val mid = (L + R) * 0.5f
            val side = (L - R) * 0.5f

            // ==========================================
            // 2. Mid 通道：人声临场感增强
            // ==========================================
            val midHp = midHpAlpha * (midHpY1 + mid - midHpX1)
            midHpX1 = mid
            midHpY1 = midHp
            val midLp = mid - midHp // Mid 的低频部分（底鼓、贝斯，绝不碰）

            // 增强中高频 Mid，让人声靠前。最大增强 1.5 倍
            val midPresenceGain = 1.0f + smoothedFactor * 0.5f
            val outMid = midLp + midHp * midPresenceGain

            // ==========================================
            // 3. Side 通道：立体声展宽
            // ==========================================
            val sideHp = sideHpAlpha * (sideHpY1 + side - sideHpX1)
            sideHpX1 = side
            sideHpY1 = sideHp
            val sideLp = side - sideHp

            // 高频强力展宽 (3.0倍) 再衰减 -3dB (0.707)
            val highFreqExpandGain = (1.0f + smoothedFactor * 3.0f) * 0.707f
            // 低频微弱展宽 (0.3倍)
            val lowFreqExpandGain = 1.0f + smoothedFactor * 0.3f

            val outSide = sideLp * lowFreqExpandGain + sideHp * highFreqExpandGain

            // ==========================================
            // 4. 重组 L/R
            // ==========================================
            var outL = outMid + outSide
            var outR = outMid - outSide

            // ==========================================
            // 5. 立体声联动压限器 (死守防爆音底线)
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
        midHpX1 = 0f
        midHpY1 = 0f
        limGain = 1.0f
    }
}
