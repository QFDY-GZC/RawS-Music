package com.rawsmusic.module.player.dsp

import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MoogLadderController(private var engine: NativeDSPEngine) {
    enum class Mode(val nativeCode: Int) {
        LOW_PASS_24(0),
        LOW_PASS_12(1),
        HIGH_PASS_24(2),
        BAND_PASS_12(3),
        NOTCH(4);

        companion object {
            fun fromCode(code: Int): Mode = entries.firstOrNull { it.nativeCode == code }
                ?: LOW_PASS_24
        }
    }

    private val _isEnabled = MutableStateFlow(AppPreferences.MoogLadder.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    private val _mode = MutableStateFlow(Mode.fromCode(AppPreferences.MoogLadder.mode))
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _cutoffHz = MutableStateFlow(AppPreferences.MoogLadder.cutoffHz)
    val cutoffHz: StateFlow<Float> = _cutoffHz.asStateFlow()
    private val _resonancePercent = MutableStateFlow(AppPreferences.MoogLadder.resonancePercent)
    val resonancePercent: StateFlow<Float> = _resonancePercent.asStateFlow()
    private val _driveDb = MutableStateFlow(AppPreferences.MoogLadder.driveDb)
    val driveDb: StateFlow<Float> = _driveDb.asStateFlow()
    private val _mixPercent = MutableStateFlow(AppPreferences.MoogLadder.mixPercent)
    val mixPercent: StateFlow<Float> = _mixPercent.asStateFlow()

    init { syncToNative() }

    fun connectEngine(newEngine: NativeDSPEngine) {
        engine = newEngine
        syncToNative()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.MoogLadder.isEnabled = enabled
        engine.setMoogLadderEnabled(enabled)
    }

    fun setMode(mode: Mode) {
        _mode.value = mode
        AppPreferences.MoogLadder.mode = mode.nativeCode
        syncParameters()
    }

    fun setCutoffHz(value: Float) {
        _cutoffHz.value = value.coerceIn(20f, 20000f)
        AppPreferences.MoogLadder.cutoffHz = _cutoffHz.value
        syncParameters()
    }

    fun setResonancePercent(value: Float) {
        _resonancePercent.value = value.coerceIn(0f, 100f)
        AppPreferences.MoogLadder.resonancePercent = _resonancePercent.value
        syncParameters()
    }

    fun setDriveDb(value: Float) {
        _driveDb.value = value.coerceIn(0f, 18f)
        AppPreferences.MoogLadder.driveDb = _driveDb.value
        syncParameters()
    }

    fun setMixPercent(value: Float) {
        _mixPercent.value = value.coerceIn(0f, 100f)
        AppPreferences.MoogLadder.mixPercent = _mixPercent.value
        syncParameters()
    }

    private fun syncParameters() = engine.setMoogLadderParameters(
        _mode.value.nativeCode,
        _cutoffHz.value,
        _resonancePercent.value,
        _driveDb.value,
        _mixPercent.value
    )

    private fun syncToNative() {
        syncParameters()
        engine.setMoogLadderEnabled(_isEnabled.value)
    }
}
