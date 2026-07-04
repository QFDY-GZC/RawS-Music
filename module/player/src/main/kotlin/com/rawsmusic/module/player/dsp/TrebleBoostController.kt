package com.rawsmusic.module.player.dsp

import android.util.Log
import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 高音增强控制器
 * 管理高音增强状态并桥接NativeDSPEngine
 */
class TrebleBoostController(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "TrebleBoostController"
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
    private val _frequency = MutableStateFlow(8000f)
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
     * 启用/禁用高音增强
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setTrebleBoostEnabled(enabled)
        }
        persistImmediately()
        Log.d(TAG, "TrebleBoost ${if (enabled) "enabled" else "disabled"}")
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
     * @param freq 转折频率 (Hz)，范围 2000 ~ 16000
     */
    fun setFrequency(freq: Float) {
        val clamped = freq.coerceIn(2000f, 16000f)
        _frequency.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    /**
     * 同步所有参数到native
     */
    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setTrebleBoostEnabled(_isEnabled.value)
        syncParamsToNative()
    }

    /**
     * 同步参数到native
     */
    private fun syncParamsToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setTrebleBoostParams(_gainDB.value, _frequency.value)
    }

    /**
     * 从持久化存储加载状态
     */
    private fun loadPersistedState() {
        try {
            _isEnabled.value = AppPreferences.TrebleBoost.isEnabled
            _gainDB.value = AppPreferences.TrebleBoost.gainDB
            _frequency.value = AppPreferences.TrebleBoost.frequency
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
            AppPreferences.TrebleBoost.isEnabled = _isEnabled.value
            AppPreferences.TrebleBoost.gainDB = _gainDB.value
            AppPreferences.TrebleBoost.frequency = _frequency.value
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
