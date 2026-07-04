package com.rawsmusic.module.player

import android.util.Log
import com.rawsmusic.core.common.model.EqualizerPreset
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.EqualizerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EqualizerController(private val audioSessionId: Int) {

    companion object {
        private const val TAG = "EqualizerController"
    }

    // ── 状态流 ──
    private val _isEnabled = MutableStateFlow(AppPreferences.Equalizer.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _bandLevels = MutableStateFlow(AppPreferences.Equalizer.bandLevels)
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(AppPreferences.Equalizer.bassBoost)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(AppPreferences.Equalizer.virtualizer)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _channelBalance = MutableStateFlow(AppPreferences.Equalizer.channelBalance)
    val channelBalance: StateFlow<Float> = _channelBalance.asStateFlow()

    private val _loudnessEnhance = MutableStateFlow(AppPreferences.Equalizer.loudnessEnhance)
    val loudnessEnhance: StateFlow<Int> = _loudnessEnhance.asStateFlow()

    private val _presets = MutableStateFlow<List<EqualizerPreset>>(emptyList())
    val presets: StateFlow<List<EqualizerPreset>> = _presets.asStateFlow()

    private val _currentPresetId = MutableStateFlow(AppPreferences.Equalizer.currentPresetId)
    val currentPresetId: StateFlow<Long> = _currentPresetId.asStateFlow()

    private val _centerFrequencies = MutableStateFlow<List<Int>>(emptyList())
    val centerFrequencies: StateFlow<List<Int>> = _centerFrequencies.asStateFlow()

    val numberOfBands: Int = 0
    val bandLevelRange: IntRange = -1500..1500

    fun init() {
        _centerFrequencies.value = emptyList()
        loadPresets()
        Log.d(TAG, "Legacy equalizer shell initialized; native DSP owns the active audio chain")
    }

    fun setVirtualizer(strength: Int) {
        _virtualizerStrength.value = strength
        AppPreferences.Equalizer.virtualizer = strength
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        AppPreferences.Equalizer.isEnabled = enabled
    }

    fun setBandLevel(band: Int, level: Int) {}

    fun setBassBoost(strength: Int) {
        _bassBoostStrength.value = strength
        AppPreferences.Equalizer.bassBoost = strength
    }

    fun setChannelBalance(balance: Float) {
        _channelBalance.value = balance
        AppPreferences.Equalizer.channelBalance = balance
    }

    fun setLoudnessEnhance(gain: Int) {
        _loudnessEnhance.value = gain
        AppPreferences.Equalizer.loudnessEnhance = gain
    }

    fun applyPreset(preset: EqualizerPreset) {
        _currentPresetId.value = preset.id
        AppPreferences.Equalizer.currentPresetId = preset.id
        setBassBoost(preset.bassBoost)
        setVirtualizer(preset.virtualizer)
    }

    fun saveCustomPreset(name: String): Boolean {
        val preset = EqualizerPreset(
            name = name,
            bandLevels = _bandLevels.value,
            bassBoost = _bassBoostStrength.value,
            virtualizer = _virtualizerStrength.value,
            isBuiltIn = false
        )
        val result = EqualizerRepository.savePreset(preset)
        if (result) loadPresets()
        return result
    }

    fun deletePreset(id: Long): Boolean {
        val result = EqualizerRepository.deletePreset(id)
        if (result > 0) loadPresets()
        return result > 0
    }

    fun resetToDefault() {
        setBassBoost(0)
        setVirtualizer(0)
        setLoudnessEnhance(0)
        _currentPresetId.value = -1
        AppPreferences.Equalizer.currentPresetId = -1
    }

    private fun loadPresets() {
        _presets.value = EqualizerRepository.getAllPresets()
    }

    fun release() {
        Log.d(TAG, "Legacy equalizer shell released")
    }

    fun reinit(newAudioSessionId: Int) {
        Log.d(TAG, "Legacy equalizer shell ignores audioSessionId changes; native DSP remains canonical")
    }
}
