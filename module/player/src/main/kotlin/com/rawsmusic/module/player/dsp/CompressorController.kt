package com.rawsmusic.module.player.dsp

import android.util.Log
import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 压限器控制器
 * 管理压限器状态并桥接NativeDSPEngine
 */
class CompressorController(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "CompressorController"
        private const val PERSIST_DEBOUNCE_MS = 350L
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistState() }

    // 启用状态
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // 阈值 (dB)
    private val _thresholdDB = MutableStateFlow(-20f)
    val thresholdDB: StateFlow<Float> = _thresholdDB.asStateFlow()

    // 压缩比
    private val _ratio = MutableStateFlow(4f)
    val ratio: StateFlow<Float> = _ratio.asStateFlow()

    // 启动时间 (ms)
    private val _attackMs = MutableStateFlow(10f)
    val attackMs: StateFlow<Float> = _attackMs.asStateFlow()

    // 释放时间 (ms)
    private val _releaseMs = MutableStateFlow(200f)
    val releaseMs: StateFlow<Float> = _releaseMs.asStateFlow()

    // 补偿增益 (dB)
    private val _makeupGainDB = MutableStateFlow(0f)
    val makeupGainDB: StateFlow<Float> = _makeupGainDB.asStateFlow()

    // 拐点宽度 (dB)
    private val _kneeWidthDB = MutableStateFlow(6f)
    val kneeWidthDB: StateFlow<Float> = _kneeWidthDB.asStateFlow()

    // 检测模式 (0=Peak, 1=RMS)
    private val _detectionMode = MutableStateFlow(1)
    val detectionMode: StateFlow<Int> = _detectionMode.asStateFlow()

    // 当前增益衰减量 (GR Meter)
    private val _currentGR = MutableStateFlow(0f)
    val currentGR: StateFlow<Float> = _currentGR.asStateFlow()

    init {
        loadPersistedState()
        syncAllToNative()
    }

    /**
     * 重新连接到新的 DSP 引擎实例
     */
    fun connectEngine(engine: NativeDSPEngine) {
        this.nativeEngine = engine
        syncAllToNative()
        Log.d(TAG, "Reconnected to DSP engine, enabled=${_isEnabled.value}")
    }

    /**
     * 启用/禁用压限器
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setCompressorEnabled(enabled)
        }
        persistImmediately()
        Log.d(TAG, "Compressor ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * 设置阈值
     * @param dB 阈值 (dB)，范围 -60 ~ 0
     */
    fun setThreshold(dB: Float) {
        val clamped = dB.coerceIn(-60f, 0f)
        _thresholdDB.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置压缩比
     * @param ratio 压缩比，范围 1 ~ 20
     */
    fun setRatio(ratio: Float) {
        val clamped = ratio.coerceIn(1f, 20f)
        _ratio.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置启动时间
     * @param ms 启动时间 (ms)，范围 0.1 ~ 100
     */
    fun setAttack(ms: Float) {
        val clamped = ms.coerceIn(0.1f, 100f)
        _attackMs.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置释放时间
     * @param ms 释放时间 (ms)，范围 10 ~ 1000
     */
    fun setRelease(ms: Float) {
        val clamped = ms.coerceIn(10f, 1000f)
        _releaseMs.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置补偿增益
     * @param dB 补偿增益 (dB)，范围 0 ~ 24
     */
    fun setMakeupGain(dB: Float) {
        val clamped = dB.coerceIn(0f, 24f)
        _makeupGainDB.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置拐点宽度
     * @param dB 拐点宽度 (dB)，范围 0 ~ 30
     */
    fun setKneeWidth(dB: Float) {
        val clamped = dB.coerceIn(0f, 30f)
        _kneeWidthDB.value = clamped
        if (nativeEngine.isInitialized()) {
            nativeEngine.setCompressorKneeWidth(clamped)
        }
        persistDebounced()
    }

    /**
     * 设置检测模式
     * @param mode 0=Peak, 1=RMS
     */
    fun setDetectionMode(mode: Int) {
        val clamped = mode.coerceIn(0, 1)
        _detectionMode.value = clamped
        if (nativeEngine.isInitialized()) {
            nativeEngine.setCompressorDetectionMode(clamped)
        }
        persistImmediately()
    }

    /**
     * 更新GR Meter值（需要定期调用）
     */
    fun updateGR() {
        if (nativeEngine.isInitialized() && _isEnabled.value) {
            _currentGR.value = nativeEngine.getCompressorGR()
        } else {
            _currentGR.value = 0f
        }
    }

    /**
     * 同步所有参数到native
     */
    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setCompressorEnabled(_isEnabled.value)
        syncParamsToNative()
        nativeEngine.setCompressorKneeWidth(_kneeWidthDB.value)
        nativeEngine.setCompressorDetectionMode(_detectionMode.value)
    }

    /**
     * 同步核心参数到native
     */
    private fun syncParamsToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setCompressorParams(
            _thresholdDB.value,
            _ratio.value,
            _attackMs.value,
            _releaseMs.value,
            _makeupGainDB.value
        )
    }

    /**
     * 从持久化存储加载状态
     */
    private fun loadPersistedState() {
        try {
            _isEnabled.value = AppPreferences.Compressor.isEnabled
            _thresholdDB.value = AppPreferences.Compressor.thresholdDB
            _ratio.value = AppPreferences.Compressor.ratio
            _attackMs.value = AppPreferences.Compressor.attackMs
            _releaseMs.value = AppPreferences.Compressor.releaseMs
            _makeupGainDB.value = AppPreferences.Compressor.makeupGainDB
            _kneeWidthDB.value = AppPreferences.Compressor.kneeWidthDB
            _detectionMode.value = AppPreferences.Compressor.detectionMode
            Log.d(TAG, "Loaded state from preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load state", e)
        }
    }

    /**
     * 持久化当前状态
     */
    private fun persistState() {
        try {
            AppPreferences.Compressor.isEnabled = _isEnabled.value
            AppPreferences.Compressor.thresholdDB = _thresholdDB.value
            AppPreferences.Compressor.ratio = _ratio.value
            AppPreferences.Compressor.attackMs = _attackMs.value
            AppPreferences.Compressor.releaseMs = _releaseMs.value
            AppPreferences.Compressor.makeupGainDB = _makeupGainDB.value
            AppPreferences.Compressor.kneeWidthDB = _kneeWidthDB.value
            AppPreferences.Compressor.detectionMode = _detectionMode.value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state", e)
        }
    }

    private fun persistDebounced() {
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun persistImmediately() {
        persistHandler.removeCallbacks(persistRunnable)
        persistState()
    }
}
