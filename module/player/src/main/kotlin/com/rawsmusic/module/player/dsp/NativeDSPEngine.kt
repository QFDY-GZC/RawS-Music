package com.rawsmusic.module.player.dsp

import android.util.Log

class NativeDSPEngine {
    private var nativeHandle: Long = 0
    var sampleRate: Int = 44100
        private set

    companion object {
        private const val TAG = "NativeDSPEngine"
        private const val MAX_PEQ_FILTERS = 40
        init {
            try {
                System.loadLibrary("rawsmusic_dsp")
                Log.d(TAG, "rawsmusic_dsp library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load rawsmusic_dsp", e)
            }
        }
    }

    fun init(sampleRate: Int, channels: Int) {
        if (nativeHandle != 0L) release()
        this.sampleRate = sampleRate
        nativeHandle = nativeCreate(sampleRate, channels)
        Log.d(TAG, "init: sampleRate=$sampleRate, channels=$channels, handle=$nativeHandle")
    }

    fun setStereoWiden(factor: Float) {
        if (nativeHandle == 0L) return

        val safeFactor = if (factor.isFinite()) {
            factor.coerceIn(0f, 1f)
        } else {
            0f
        }

        nativeSetStereoWiden(nativeHandle, safeFactor)
    }

    fun process(buffer: ShortArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcess(nativeHandle, buffer, length, channels)
    }

    fun processFloat(buffer: FloatArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcessFloat(nativeHandle, buffer, length, channels)
    }

    fun hasActiveEffects(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeHasActiveEffects(nativeHandle)
    }

    // ==========================================
    // 参量均衡器 API
    // ==========================================

    /** 启用/禁用参量均衡器 */
    fun setPEQEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPEQEnabled(nativeHandle, enabled)
    }

    /**
     * 设置PEQ滤波器
     * @param index 滤波器索引 (0-39)
     * @param type 滤波器类型 (0=Peak, 1=LowShelf, 2=HighShelf, 3=LowPass, 4=HighPass, 5=BandPass, 6=Notch, 7=PeakAnalog)
     * @param frequency 中心/截止频率 (Hz)
     * @param gainDB 增益 (dB)，建议 -24..24
     * @param Q 品质因数，建议 0.05..24
     * @param enabled 是否启用
     */
    fun setPEQFilter(
        index: Int,
        type: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    ) {
        if (nativeHandle == 0L) return
        if (index !in 0 until MAX_PEQ_FILTERS) return

        val safeType = type.coerceIn(0, 7)

        val safeFrequency = if (frequency.isFinite()) {
            frequency.coerceIn(20f, 20000f)
        } else {
            1000f
        }

        val safeGainDB = if (gainDB.isFinite()) {
            gainDB.coerceIn(-24f, 24f)
        } else {
            0f
        }

        val safeQ = if (Q.isFinite()) {
            Q.coerceIn(0.05f, 24f)
        } else {
            1.414f
        }

        nativeSetPEQFilter(
            nativeHandle,
            index,
            safeType,
            safeFrequency,
            safeGainDB,
            safeQ,
            enabled
        )
    }

    /** 移除指定索引的滤波器 */
    fun removePEQFilter(index: Int) {
        if (nativeHandle == 0L) return
        if (index !in 0 until MAX_PEQ_FILTERS) return

        nativeRemovePEQFilter(nativeHandle, index)
    }

    /** 清除所有滤波器 */
    fun clearPEQFilters() {
        if (nativeHandle == 0L) return
        nativeClearPEQFilters(nativeHandle)
    }

    /** 设置前置放大器增益 (dB)，范围 -12 到 12 */
    fun setPreamp(gainDB: Float) {
        if (nativeHandle == 0L) return

        val safeGain = if (gainDB.isFinite()) {
            gainDB.coerceIn(-12f, 12f)
        } else {
            0f
        }

        nativeSetPreamp(nativeHandle, safeGain)
    }

    // ==========================================
    // 互馈 (Crossfeed) API
    // ==========================================

    /** 启用/禁用互馈 */
    fun setCrossfeedEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetCrossfeedEnabled(nativeHandle, enabled)
    }

    /**
     * 设置互馈参数
     * @param lowCutFreq 高通截止频率 (Hz)，50-1000
     * @param highCutFreq 低通截止频率 (Hz)，500-8000
     * @param attenuationDB 衰减量 (dB)，0-15
     */
    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) {
        if (nativeHandle == 0L) return
        nativeSetCrossfeedParams(nativeHandle, lowCutFreq, highCutFreq, attenuationDB)
    }

    /**
     * 计算频率响应曲线
     * @param frequencies 输入频率数组 (Hz)
     * @param magnitudes 输出增益数组 (dB)
     * @param numPoints 采样点数
     */
    fun calcPEQResponse(frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int) {
        if (nativeHandle == 0L) return

        val safePoints = numPoints
            .coerceAtMost(frequencies.size)
            .coerceAtMost(magnitudes.size)
            .coerceAtLeast(0)

        if (safePoints <= 0) return

        nativeCalcPEQResponse(nativeHandle, frequencies, magnitudes, safePoints)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

    fun isInitialized(): Boolean = nativeHandle != 0L

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetStereoWiden(handle: Long, factor: Float)
    private external fun nativeProcess(handle: Long, buffer: ShortArray, length: Int, channels: Int): Int
    private external fun nativeProcessFloat(handle: Long, buffer: FloatArray, length: Int, channels: Int): Int
    private external fun nativeHasActiveEffects(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)

    // PEQ JNI 方法
    private external fun nativeSetPEQEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetPEQFilter(handle: Long, index: Int, type: Int, frequency: Float, gainDB: Float, Q: Float, enabled: Boolean)
    private external fun nativeRemovePEQFilter(handle: Long, index: Int)
    private external fun nativeClearPEQFilters(handle: Long)
    private external fun nativeCalcPEQResponse(handle: Long, frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int)
    private external fun nativeSetPreamp(handle: Long, gainDB: Float)

    // Crossfeed JNI 方法
    private external fun nativeSetCrossfeedEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCrossfeedParams(handle: Long, lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float)

    // ==========================================
    // 压限器 (Compressor) API
    // ==========================================

    /** 启用/禁用压限器 */
    fun setCompressorEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetCompressorEnabled(nativeHandle, enabled)
    }

    /**
     * 设置压限器参数
     * @param thresholdDB 阈值 (dB)，范围 -60 ~ 0
     * @param ratio 压缩比，范围 1 ~ 20
     * @param attackMs 启动时间 (ms)，范围 0.1 ~ 100
     * @param releaseMs 释放时间 (ms)，范围 10 ~ 1000
     * @param makeupGainDB 补偿增益 (dB)，范围 0 ~ 24
     */
    fun setCompressorParams(thresholdDB: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupGainDB: Float) {
        if (nativeHandle == 0L) return
        nativeSetCompressorParams(nativeHandle, thresholdDB, ratio, attackMs, releaseMs, makeupGainDB)
    }

    /**
     * 设置压限器拐点宽度
     * @param kneeWidthDB 拐点宽度 (dB)，范围 0 ~ 30，0为硬拐点
     */
    fun setCompressorKneeWidth(kneeWidthDB: Float) {
        if (nativeHandle == 0L) return
        nativeSetCompressorKneeWidth(nativeHandle, kneeWidthDB)
    }

    /**
     * 设置压限器检测模式
     * @param mode 0=Peak, 1=RMS
     */
    fun setCompressorDetectionMode(mode: Int) {
        if (nativeHandle == 0L) return
        nativeSetCompressorDetectionMode(nativeHandle, mode)
    }

    /**
     * 获取当前增益衰减量 (GR Meter)
     * @return 增益衰减量 (dB)，正值表示压缩量
     */
    fun getCompressorGR(): Float {
        if (nativeHandle == 0L) return 0.0f
        return nativeGetCompressorGR(nativeHandle)
    }

    // ==========================================
    // 低音增强 (BassBoost) API
    // ==========================================

    /** 启用/禁用低音增强 */
    fun setBassBoostEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetBassBoostEnabled(nativeHandle, enabled)
    }

    /**
     * 设置低音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 50 ~ 500
     */
    fun setBassBoostParams(gainDB: Float, frequency: Float) {
        if (nativeHandle == 0L) return
        nativeSetBassBoostParams(nativeHandle, gainDB, frequency)
    }

    // ==========================================
    // 高音增强 (TrebleBoost) API
    // ==========================================

    /** 启用/禁用高音增强 */
    fun setTrebleBoostEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetTrebleBoostEnabled(nativeHandle, enabled)
    }

    /**
     * 设置高音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 2000 ~ 16000
     */
    fun setTrebleBoostParams(gainDB: Float, frequency: Float) {
        if (nativeHandle == 0L) return
        nativeSetTrebleBoostParams(nativeHandle, gainDB, frequency)
    }

    // Compressor JNI 方法
    private external fun nativeSetCompressorEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCompressorParams(handle: Long, thresholdDB: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupGainDB: Float)
    private external fun nativeSetCompressorKneeWidth(handle: Long, kneeWidthDB: Float)
    private external fun nativeSetCompressorDetectionMode(handle: Long, mode: Int)
    private external fun nativeGetCompressorGR(handle: Long): Float

    // BassBoost JNI 方法
    private external fun nativeSetBassBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetBassBoostParams(handle: Long, gainDB: Float, frequency: Float)

    // TrebleBoost JNI 方法
    private external fun nativeSetTrebleBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetTrebleBoostParams(handle: Long, gainDB: Float, frequency: Float)

    // ==========================================
    // 360° 环绕音 (Surround360) API
    // ==========================================

    /** 启用/禁用 360° 环绕音 */
    fun setSurround360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 环绕音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     */
    fun setSurround360Params(intensity: Float, azimuthDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Params(nativeHandle, intensity, azimuthDeg)
    }

    // ==========================================
    // 360° 全景音 (Panoramic360) API
    // ==========================================

    /** 启用/禁用 360° 全景音 */
    fun setPanoramic360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 全景音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     * @param elevationDeg 仰角 (-90~+90°), 正=上方, 负=下方
     */
    fun setPanoramic360Params(intensity: Float, azimuthDeg: Float, elevationDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Params(nativeHandle, intensity, azimuthDeg, elevationDeg)
    }

    // Surround360 JNI 方法
    private external fun nativeSetSurround360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSurround360Params(handle: Long, intensity: Float, azimuthDeg: Float)

    // Panoramic360 JNI 方法
    private external fun nativeSetPanoramic360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetPanoramic360Params(handle: Long, intensity: Float, azimuthDeg: Float, elevationDeg: Float)
}
