package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoudnessBalanceController(private var engine: NativeDSPEngine) {
    private val _isEnabled = MutableStateFlow(AppPreferences.LoudnessBalance.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    private val _loudnessPercent = MutableStateFlow(AppPreferences.LoudnessBalance.loudnessPercent)
    val loudnessPercent: StateFlow<Float> = _loudnessPercent.asStateFlow()
    private val _balance = MutableStateFlow(AppPreferences.LoudnessBalance.balance)
    val balance: StateFlow<Float> = _balance.asStateFlow()

    init { syncToNative() }

    fun connectEngine(newEngine: NativeDSPEngine) {
        engine = newEngine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.LoudnessBalance.isEnabled = enabled
        engine.setLoudnessBalanceEnabled(enabled)
    }

    fun setLoudnessPercent(value: Float) {
        _loudnessPercent.value = value.coerceIn(0f, 100f)
        AppPreferences.LoudnessBalance.loudnessPercent = _loudnessPercent.value
        syncParameters()
    }

    fun setBalance(value: Float) {
        _balance.value = value.coerceIn(-1f, 1f)
        AppPreferences.LoudnessBalance.balance = _balance.value
        syncParameters()
    }

    private fun syncParameters() = engine.setLoudnessBalanceParameters(
        _loudnessPercent.value,
        _balance.value
    )

    private fun syncToNative() {
        syncParameters()
        engine.setLoudnessBalanceEnabled(_isEnabled.value)
    }
}
