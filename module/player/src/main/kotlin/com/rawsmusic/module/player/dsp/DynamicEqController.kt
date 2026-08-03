package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DynamicEqController(private var engine: NativeDSPEngine) {
    private val _isEnabled = MutableStateFlow(AppPreferences.DynamicEq.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    private val _intensityPercent = MutableStateFlow(AppPreferences.DynamicEq.intensityPercent)
    val intensityPercent: StateFlow<Float> = _intensityPercent.asStateFlow()
    private val _deEsserPercent = MutableStateFlow(AppPreferences.DynamicEq.deEsserPercent)
    val deEsserPercent: StateFlow<Float> = _deEsserPercent.asStateFlow()
    private val _deEsserFrequencyHz = MutableStateFlow(AppPreferences.DynamicEq.deEsserFrequencyHz)
    val deEsserFrequencyHz: StateFlow<Float> = _deEsserFrequencyHz.asStateFlow()

    init { syncToNative() }

    fun connectEngine(newEngine: NativeDSPEngine) {
        engine = newEngine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.DynamicEq.isEnabled = enabled
        engine.setDynamicEqEnabled(enabled)
    }

    fun setIntensityPercent(value: Float) {
        _intensityPercent.value = value.coerceIn(0f, 100f)
        AppPreferences.DynamicEq.intensityPercent = _intensityPercent.value
        syncParameters()
    }

    fun setDeEsserPercent(value: Float) {
        _deEsserPercent.value = value.coerceIn(0f, 100f)
        AppPreferences.DynamicEq.deEsserPercent = _deEsserPercent.value
        syncParameters()
    }

    fun setDeEsserFrequencyHz(value: Float) {
        _deEsserFrequencyHz.value = value.coerceIn(4000f, 10000f)
        AppPreferences.DynamicEq.deEsserFrequencyHz = _deEsserFrequencyHz.value
        syncParameters()
    }

    private fun syncParameters() = engine.setDynamicEqParameters(
        _intensityPercent.value,
        _deEsserPercent.value,
        _deEsserFrequencyHz.value
    )

    private fun syncToNative() {
        syncParameters()
        engine.setDynamicEqEnabled(_isEnabled.value)
    }
}
