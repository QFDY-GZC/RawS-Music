package com.rawsmusic.module.player.dsp

import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls the final-output automatic linked limiter.
 *
 * The native engine exposes only the automatic linked peak limiter. Attack,
 * release, ceiling and channel linking are fixed by the DSP implementation.
 */
class CompressorController(private var nativeEngine: NativeDSPEngine) {

    companion object {
        private const val TAG = "CompressorController"
    }

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentGR = MutableStateFlow(0f)
    val currentGR: StateFlow<Float> = _currentGR.asStateFlow()

    init {
        loadPersistedState()
        syncAllToNative()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        nativeEngine = engine
        syncAllToNative()
        Log.d(TAG, "Reconnected automatic limiter, enabled=${_isEnabled.value}")
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (nativeEngine.isInitialized()) {
            nativeEngine.setCompressorEnabled(enabled)
        }
        AppPreferences.Compressor.isEnabled = enabled
        if (!enabled) _currentGR.value = 0f
        Log.d(TAG, "Automatic limiter ${if (enabled) "enabled" else "disabled"}")
    }

    fun updateGR() {
        _currentGR.value = if (nativeEngine.isInitialized() && _isEnabled.value) {
            nativeEngine.getCompressorGR()
        } else {
            0f
        }
    }

    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        nativeEngine.setCompressorEnabled(_isEnabled.value)
    }

    private fun loadPersistedState() {
        runCatching {
            _isEnabled.value = AppPreferences.Compressor.isEnabled
        }.onFailure { error ->
            Log.e(TAG, "Failed to load automatic limiter state", error)
        }
    }
}
