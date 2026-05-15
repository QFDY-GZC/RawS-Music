package com.rawsmusic.module.player

import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
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

    // ── 真正的 Virtualizer 实例 ──
    private var virtualizer: Virtualizer? = null

    private val handler = Handler(Looper.getMainLooper())

    fun init() {
        Log.d("VirtualizerDebug", "init called, audioSessionId=$audioSessionId")
        _centerFrequencies.value = emptyList()
        loadPresets()

        if (audioSessionId == 0) {
            Log.w(TAG, "audioSessionId is 0, skipping Virtualizer creation")
            return
        }

        try {
            virtualizer = Virtualizer(0, audioSessionId)
            virtualizer?.setEnabled(false)
            Log.d(TAG, "System Virtualizer disabled (using custom DSP instead)")
        } catch (e: RuntimeException) {
            // USB DAC 等外部音频设备通常不支持 Virtualizer 引擎，这是正常情况，无需重试
            Log.w(TAG, "Virtualizer not supported on current audio output (error: ${e.message})")
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer creation failed: ${e.message}")
        }
    }

    fun setVirtualizer(strength: Int) {
        Log.d("VirtualizerDebug", "setVirtualizer called with strength=$strength")
        _virtualizerStrength.value = strength
        AppPreferences.Equalizer.virtualizer = strength

        val v = virtualizer
        if (v == null) {
            Log.w("VirtualizerDebug", "Virtualizer instance is null, cannot set strength")
            return
        }

        try {
            val result = v.setStrength(strength.toShort())
            Log.d("VirtualizerDebug", "setStrength(${strength.toShort()}) returned $result, new roundedStrength=${v.roundedStrength}")
        } catch (e: Exception) {
            Log.e("VirtualizerDebug", "setStrength failed", e)
        }
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
        Log.d("VirtualizerDebug", "release called")
        virtualizer?.release()
        virtualizer = null
    }

    fun reinit(newAudioSessionId: Int) {
        release()
        // 外部调用 init() 重建 Virtualizer
    }
}
