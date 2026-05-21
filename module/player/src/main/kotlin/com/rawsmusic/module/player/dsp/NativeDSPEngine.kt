package com.rawsmusic.module.player.dsp

import android.util.Log

class NativeDSPEngine {
    private var nativeHandle: Long = 0
    var sampleRate: Int = 44100
        private set

    companion object {
        private const val TAG = "NativeDSPEngine"
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
        nativeSetStereoWiden(nativeHandle, factor)
    }

    fun process(buffer: ShortArray, length: Int, channels: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeProcess(nativeHandle, buffer, length, channels)
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
     * @param index 滤波器索引 (0-7)
     * @param type 滤波器类型 (0=Peak, 1=LowShelf, 2=HighShelf, 3=LowPass, 4=HighPass, 5=BandPass, 6=Notch, 7=PeakAnalog)
     * @param frequency 中心频率 (Hz)
     * @param gainDB 增益 (dB)，对于LP/HP无效
     * @param Q 品质因数
     * @param enabled 是否启用
     */
    fun setPEQFilter(index: Int, type: Int, frequency: Float, gainDB: Float, Q: Float, enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPEQFilter(nativeHandle, index, type, frequency, gainDB, Q, enabled)
    }

    /** 移除指定索引的滤波器 */
    fun removePEQFilter(index: Int) {
        if (nativeHandle == 0L) return
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
        nativeSetPreamp(nativeHandle, gainDB)
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
        nativeCalcPEQResponse(nativeHandle, frequencies, magnitudes, numPoints)
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
}
