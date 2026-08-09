package com.rawsmusic.module.player.dsp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Independent fixed-band equalizer backed by its own native filter bank. */
class GraphicEQController(private var nativeEngine: NativeDSPEngine) {
    companion object {
        private const val TAG = "GraphicEQController"
        private const val CUSTOM_PRESET_NAME = "Custom"
        private const val PERSIST_DEBOUNCE_MS = 250L
    }

    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistGainsRunnable = Runnable {
        AppPreferences.GraphicEQ.gains = _gains.value
    }

    private val _bandCount = MutableStateFlow(AppPreferences.GraphicEQ.bandCount)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _isEnabled = MutableStateFlow(AppPreferences.GraphicEQ.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _preamp = MutableStateFlow(AppPreferences.GraphicEQ.preamp)
    val preamp: StateFlow<Float> = _preamp.asStateFlow()

    private val _gains = MutableStateFlow(normalizeSavedGains(AppPreferences.GraphicEQ.gains, _bandCount.value))
    val gains: StateFlow<List<Float>> = _gains.asStateFlow()

    private val _presetName = MutableStateFlow(AppPreferences.GraphicEQ.presetName)
    val presetName: StateFlow<String> = _presetName.asStateFlow()

    private val _presets = MutableStateFlow(GraphicEQPreset.builtInPresetsForCount(_bandCount.value))
    val presets: StateFlow<List<GraphicEQPreset>> = _presets.asStateFlow()

    /** Called before GEQ is enabled so the owner can disable PEQ state. */
    var onExclusiveEnable: (() -> Unit)? = null

    init {
        if (nativeEngine.isInitialized()) syncAllToNative()
    }

    fun connectEngine(engine: NativeDSPEngine) {
        nativeEngine = engine
        syncAllToNative()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) onExclusiveEnable?.invoke()
        _isEnabled.value = enabled
        AppPreferences.GraphicEQ.isEnabled = enabled
        if (nativeEngine.isInitialized()) {
            if (enabled) syncFiltersToNative()
            nativeEngine.setGraphicEQEnabled(enabled)
        }
    }

    internal fun disableForMutualExclusion() {
        if (!_isEnabled.value) return
        _isEnabled.value = false
        AppPreferences.GraphicEQ.isEnabled = false
        if (nativeEngine.isInitialized()) nativeEngine.setGraphicEQEnabled(false)
    }

    fun setPreamp(gainDB: Float) {
        val safeGain = gainDB.coerceIn(-12f, 12f)
        _preamp.value = safeGain
        AppPreferences.GraphicEQ.preamp = safeGain
        if (nativeEngine.isInitialized()) nativeEngine.setGraphicEQPreamp(safeGain)
    }

    fun setBandCount(count: Int) {
        val target = count.coerceIn(PEQFilter.MIN_FILTERS, PEQFilter.MAX_FILTERS)
        if (target == _bandCount.value) return

        val converted = GraphicEQPreset.resamplePreset(
            GraphicEQPreset(CUSTOM_PRESET_NAME, _bandCount.value, _gains.value.toFloatArray()),
            target
        )
        _bandCount.value = target
        _gains.value = converted.gains.toList()
        _presetName.value = CUSTOM_PRESET_NAME
        _presets.value = GraphicEQPreset.builtInPresetsForCount(target)
        persistAll()
        syncAllToNative()
        Log.d(TAG, "Band count changed to $target")
    }

    fun setGain(index: Int, gainDB: Float) {
        val current = _gains.value.toMutableList()
        if (index !in current.indices) return

        val safeGain = gainDB.coerceIn(-12f, 12f)
        current[index] = safeGain
        _gains.value = current
        _presetName.value = CUSTOM_PRESET_NAME
        AppPreferences.GraphicEQ.presetName = CUSTOM_PRESET_NAME
        persistHandler.removeCallbacks(persistGainsRunnable)
        persistHandler.postDelayed(persistGainsRunnable, PERSIST_DEBOUNCE_MS)
        syncBandToNative(index, safeGain)
    }

    fun resetBand(index: Int) = setGain(index, 0f)

    fun resetToFlat() {
        _gains.value = List(_bandCount.value) { 0f }
        _presetName.value = "Normal"
        persistAll()
        syncAllToNative()
    }

    fun applyPreset(preset: GraphicEQPreset) {
        val resolved = if (preset.bandCount == _bandCount.value) {
            preset
        } else {
            GraphicEQPreset.resamplePreset(preset, _bandCount.value)
        }
        _gains.value = resolved.gains.map { it.coerceIn(-12f, 12f) }
        _presetName.value = resolved.name
        persistAll()
        syncAllToNative()
        Log.d(TAG, "Applied preset: ${resolved.name}, bands=${_bandCount.value}")
    }

    private fun syncAllToNative() {
        if (!nativeEngine.isInitialized()) return
        syncFiltersToNative()
        nativeEngine.setGraphicEQPreamp(_preamp.value)
        nativeEngine.setGraphicEQEnabled(_isEnabled.value)
    }

    private fun syncFiltersToNative() {
        nativeEngine.clearGraphicEQFilters()
        _gains.value.forEachIndexed(::syncBandToNative)
    }

    private fun syncBandToNative(index: Int, gainDB: Float) {
        if (!nativeEngine.isInitialized()) return
        val frequencies = PEQFilter.defaultFreqsForCount(_bandCount.value)
        if (index !in frequencies.indices) return
        nativeEngine.setGraphicEQFilter(
            index = index,
            frequency = frequencies[index],
            gainDB = gainDB,
            Q = PEQFilter.qForGraphicBand(_bandCount.value),
            enabled = abs(gainDB) > 0.0001f
        )
    }

    private fun persistAll() {
        persistHandler.removeCallbacks(persistGainsRunnable)
        AppPreferences.GraphicEQ.bandCount = _bandCount.value
        AppPreferences.GraphicEQ.preamp = _preamp.value
        AppPreferences.GraphicEQ.presetName = _presetName.value
        AppPreferences.GraphicEQ.gains = _gains.value
    }

    private fun normalizeSavedGains(saved: List<Float>, count: Int): List<Float> {
        if (saved.isEmpty()) return List(count) { 0f }
        if (saved.size == count) return saved.map { it.coerceIn(-12f, 12f) }
        return GraphicEQPreset.resamplePreset(
            GraphicEQPreset(CUSTOM_PRESET_NAME, saved.size, saved.toFloatArray()),
            count
        ).gains.map { it.coerceIn(-12f, 12f) }
    }
}
