package com.rawsmusic.module.player.dsp

import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 360° 环绕音控制器
 * 2D 水平面双耳渲染 (Woodworth 球头模型)
 * 方位角自动旋转，无需手动调节
 */
class Surround360Controller(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "Surround360Controller"
        private const val ROTATION_UPDATE_MS = 50L  // 20fps rotation update
        private const val PERSIST_DEBOUNCE_MS = 350L
    }

    // 启用状态
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // 效果强度 (0~100)
    private val _intensity = MutableStateFlow(50f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // 当前方位角 (0~360°) — 仅供UI显示，自动旋转
    private val _azimuthDeg = MutableStateFlow(0f)
    val azimuthDeg: StateFlow<Float> = _azimuthDeg.asStateFlow()

    // 自动旋转速度 (度/秒), 0=静止
    private val _rotationSpeed = MutableStateFlow(30f)
    val rotationSpeed: StateFlow<Float> = _rotationSpeed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rotationJob: Job? = null
    private var persistJob: Job? = null

    init {
        loadPersistedState()
        syncAllToNative()
        updateRotation()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        this.nativeEngine = engine
        syncAllToNative()
        updateRotation()
        Log.d(TAG, "Reconnected to DSP engine, enabled=${_isEnabled.value}")
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setSurround360Enabled(enabled)
        }
        persistImmediately()
        updateRotation()
        Log.d(TAG, "Surround360 ${if (enabled) "enabled" else "disabled"}")
    }

    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        _intensity.value = clamped
        syncParamsToNative()
        persistDebounced()
    }

    fun setRotationSpeed(value: Float) {
        val clamped = value.coerceIn(0f, 360f)
        _rotationSpeed.value = clamped
        persistDebounced()
        updateRotation()
    }

    private fun updateRotation() {
        rotationJob?.cancel()
        if (_isEnabled.value && _rotationSpeed.value > 0f) {
            rotationJob = scope.launch {
                var lastTime = System.currentTimeMillis()
                while (isActive) {
                    delay(ROTATION_UPDATE_MS)
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTime) / 1000f
                    lastTime = now
                    val newAz = (_azimuthDeg.value + _rotationSpeed.value * dt) % 360f
                    _azimuthDeg.value = newAz
                    syncParamsToNative()
                }
            }
        }
    }

    /** 由外部调用，随播放进度推进方位角（可选，用于与节拍同步） */
    fun advanceAzimuth(deltaDeg: Float) {
        _azimuthDeg.value = (_azimuthDeg.value + deltaDeg) % 360f
        syncParamsToNative()
    }

    fun destroy() {
        rotationJob?.cancel()
        persistImmediately()
        scope.cancel()
    }

    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setSurround360Enabled(_isEnabled.value)
        syncParamsToNative()
    }

    private fun syncParamsToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setSurround360Params(_intensity.value, _azimuthDeg.value)
    }

    private fun loadPersistedState() {
        try {
            _isEnabled.value = AppPreferences.Surround360.isEnabled
            _intensity.value = AppPreferences.Surround360.intensity
            _azimuthDeg.value = AppPreferences.Surround360.azimuthDeg
            _rotationSpeed.value = AppPreferences.Surround360.rotationSpeed
            Log.d(TAG, "Loaded state from preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load state", e)
        }
    }

    private fun persistState() {
        try {
            AppPreferences.Surround360.isEnabled = _isEnabled.value
            AppPreferences.Surround360.intensity = _intensity.value
            AppPreferences.Surround360.azimuthDeg = _azimuthDeg.value
            AppPreferences.Surround360.rotationSpeed = _rotationSpeed.value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state", e)
        }
    }

    private fun persistDebounced() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistState()
        }
    }

    private fun persistImmediately() {
        persistJob?.cancel()
        persistJob = null
        persistState()
    }
}
