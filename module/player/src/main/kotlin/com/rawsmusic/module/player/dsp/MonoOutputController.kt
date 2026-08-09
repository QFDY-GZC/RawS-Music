package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted final-output mono switch backed by the native DSP chain. */
class MonoOutputController(private var engine: NativeDSPEngine) {
    private val _isEnabled = MutableStateFlow(AppPreferences.MonoOutput.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    init {
        syncToNative()
    }

    fun connectEngine(newEngine: NativeDSPEngine) {
        engine = newEngine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.MonoOutput.isEnabled = enabled
        engine.setMonoOutputEnabled(enabled)
    }

    private fun syncToNative() {
        engine.setMonoOutputEnabled(_isEnabled.value)
    }
}
