package com.rawsmusic.module.player.dsp

import android.util.Log
import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 360° 全景音控制器
 * 3D 球面双耳渲染 (耳廓 EQ + 早期反射 + FDN 混响)
 */
class Panoramic360Controller(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "Panoramic360Controller"
        private const val PERSIST_DEBOUNCE_MS = 350L
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistState() }

    // 启用状态
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // 效果强度 (0~100)
    private val _intensity = MutableStateFlow(50f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // 方位角 (0~360°)
    private val _azimuthDeg = MutableStateFlow(0f)
    val azimuthDeg: StateFlow<Float> = _azimuthDeg.asStateFlow()

    // 仰角 (-90~+90°)
    private val _elevationDeg = MutableStateFlow(0f)
    val elevationDeg: StateFlow<Float> = _elevationDeg.asStateFlow()

    init {
        loadPersistedState()
        syncAllToNative()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        this.nativeEngine = engine
        syncAllToNative()
        Log.d(TAG, "Reconnected to DSP engine, enabled=${_isEnabled.value}")
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setPanoramic360Enabled(enabled)
        }
        persistImmediately()
        Log.d(TAG, "Panoramic360 ${if (enabled) "enabled" else "disabled"}")
    }

    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        _intensity.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    fun setAzimuthDeg(value: Float) {
        val clamped = value.coerceIn(0f, 360f)
        _azimuthDeg.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    fun setElevationDeg(value: Float) {
        val clamped = value.coerceIn(-90f, 90f)
        _elevationDeg.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setPanoramic360Enabled(_isEnabled.value)
        syncParamsToNative()
    }

    private fun syncParamsToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setPanoramic360Params(_intensity.value, _azimuthDeg.value, _elevationDeg.value)
    }

    private fun loadPersistedState() {
        try {
            _isEnabled.value = AppPreferences.Panoramic360.isEnabled
            _intensity.value = AppPreferences.Panoramic360.intensity
            _azimuthDeg.value = AppPreferences.Panoramic360.azimuthDeg
            _elevationDeg.value = AppPreferences.Panoramic360.elevationDeg
            Log.d(TAG, "Loaded state from preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load state", e)
        }
    }

    private fun persistState() {
        try {
            AppPreferences.Panoramic360.isEnabled = _isEnabled.value
            AppPreferences.Panoramic360.intensity = _intensity.value
            AppPreferences.Panoramic360.azimuthDeg = _azimuthDeg.value
            AppPreferences.Panoramic360.elevationDeg = _elevationDeg.value
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
