package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonoBassController(private var engine: NativeDSPEngine) {
    private val _isEnabled = MutableStateFlow(AppPreferences.MonoBass.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    private val _crossoverHz = MutableStateFlow(AppPreferences.MonoBass.crossoverHz)
    val crossoverHz: StateFlow<Float> = _crossoverHz.asStateFlow()
    private val _amountPercent = MutableStateFlow(AppPreferences.MonoBass.amountPercent)
    val amountPercent: StateFlow<Float> = _amountPercent.asStateFlow()

    init { syncToNative() }

    fun connectEngine(newEngine: NativeDSPEngine) {
        engine = newEngine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.MonoBass.isEnabled = enabled
        engine.setMonoBassEnabled(enabled)
    }

    fun setCrossoverHz(value: Float) {
        _crossoverHz.value = value.coerceIn(60f, 300f)
        AppPreferences.MonoBass.crossoverHz = _crossoverHz.value
        syncParameters()
    }

    fun setAmountPercent(value: Float) {
        _amountPercent.value = value.coerceIn(0f, 100f)
        AppPreferences.MonoBass.amountPercent = _amountPercent.value
        syncParameters()
    }

    private fun syncParameters() = engine.setMonoBassParameters(
        _crossoverHz.value,
        _amountPercent.value
    )

    private fun syncToNative() {
        syncParameters()
        engine.setMonoBassEnabled(_isEnabled.value)
    }
}
