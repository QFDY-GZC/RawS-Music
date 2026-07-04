package com.rawsmusic.module.player.dsp

import android.util.Log
import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 低音增强控制器
 * 管理低音增强状态并桥接NativeDSPEngine
 */
class BassBoostController(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "BassBoostController"
        private const val PERSIST_DEBOUNCE_MS = 350L
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistState() }

    // 启用状态
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // 增益 (dB)
    private val _gainDB = MutableStateFlow(0f)
    val gainDB: StateFlow<Float> = _gainDB.asStateFlow()

    // 转折频率 (Hz)
    private val _frequency = MutableStateFlow(100f)
    val frequency: StateFlow<Float> = _frequency.asStateFlow()

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
     * 启用/禁用低音增强
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setBassBoostEnabled(enabled)
        }
        persistImmediately()
        Log.d(TAG, "BassBoost ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * 设置增益
     * @param dB 增益 (dB)，范围 -12 ~ +12
     */
    fun setGain(dB: Float) {
        val clamped = dB.coerceIn(-12f, 12f)
        _gainDB.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 设置转折频率
     * @param freq 转折频率 (Hz)，范围 50 ~ 500
     */
    fun setFrequency(freq: Float) {
        val clamped = freq.coerceIn(50f, 500f)
        _frequency.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 同步所有参数到native
     */
    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setBassBoostEnabled(_isEnabled.value)
        syncParamsToNative()
    }

    /**
     * 同步参数到native
     */
    private fun syncParamsToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setBassBoostParams(_gainDB.value, _frequency.value)
    }

    /**
     * 从持久化存储加载状态
     */
    private fun loadPersistedState() {
        try {
            _isEnabled.value = AppPreferences.BassBoost.isEnabled
            _gainDB.value = AppPreferences.BassBoost.gainDB
            _frequency.value = AppPreferences.BassBoost.frequency
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
            AppPreferences.BassBoost.isEnabled = _isEnabled.value
            AppPreferences.BassBoost.gainDB = _gainDB.value
            AppPreferences.BassBoost.frequency = _frequency.value
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
