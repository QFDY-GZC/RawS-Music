package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExperimentalGainController(private var nativeEngine: NativeDSPEngine) {
    private val _isEnabled = MutableStateFlow(AppPreferences.ExperimentalGain.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _gainDb = MutableStateFlow(AppPreferences.ExperimentalGain.gainDb)
    val gainDb: StateFlow<Float> = _gainDb.asStateFlow()

    init {
        if (nativeEngine.isInitialized()) syncToNative()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        nativeEngine = engine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.ExperimentalGain.isEnabled = enabled
        if (nativeEngine.isInitialized()) nativeEngine.setExperimentalGainEnabled(enabled)
    }

    fun setGainDb(gainDb: Float) {
        val safeGain = gainDb.coerceIn(0f, 30f)
        _gainDb.value = safeGain
        AppPreferences.ExperimentalGain.gainDb = safeGain
        if (nativeEngine.isInitialized()) nativeEngine.setExperimentalGainDb(safeGain)
    }

    private fun syncToNative() {
        nativeEngine.setExperimentalGainDb(_gainDb.value)
        nativeEngine.setExperimentalGainEnabled(_isEnabled.value)
    }
}
