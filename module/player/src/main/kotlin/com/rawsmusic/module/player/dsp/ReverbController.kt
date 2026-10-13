package com.rawsmusic.module.player.dsp

import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ReverbController(private var engine: NativeDSPEngine) {
    private val handler=Handler(Looper.getMainLooper())
    private val enabledState=MutableStateFlow(AppPreferences.Reverb.isEnabled)
    private val settingsState=MutableStateFlow(ReverbSettings.decode(AppPreferences.Reverb.parameters))
    private val presetsState=MutableStateFlow(loadPresets())
    val isEnabled=enabledState.asStateFlow()
    val settings=settingsState.asStateFlow()
    val customPresets=presetsState.asStateFlow()
    private val persist=Runnable { AppPreferences.Reverb.parameters=settingsState.value.encode() }
    init { sync() }
    fun connectEngine(native: NativeDSPEngine) { engine=native; sync() }
    private fun sync() {
        engine.setReverbParams(settingsState.value)
        engine.setReverbEnabled(enabledState.value)
    }
    fun setEnabled(value: Boolean) {
        enabledState.value=value; engine.setReverbEnabled(value); AppPreferences.Reverb.isEnabled=value
        handler.removeCallbacks(persist); persist.run()
    }
    fun updateSettings(value: ReverbSettings) {
        val safe=value.sanitized(); settingsState.value=safe; engine.setReverbParams(safe)
        handler.removeCallbacks(persist); handler.postDelayed(persist,350L)
    }
    fun selectPreset(preset: ReverbPreset) { updateSettings(preset.settings) }
    fun resetDefaults() { updateSettings(ReverbSettings()) }
    fun savePreset(name: String) {
        presetsState.value=storeReverbPreset(presetsState.value,name,settingsState.value)?:return
        persistPresets()
    }
    fun deletePreset(name: String) { presetsState.value=presetsState.value.filterNot { it.name==name }; persistPresets() }
    private fun persistPresets() {
        val array=JSONArray(); presetsState.value.forEach { array.put(JSONObject().put("name",it.name).put("params",it.settings.encode())) }
        AppPreferences.Reverb.presets=array.toString()
    }
    private fun loadPresets(): List<ReverbPreset> = runCatching {
        val array=JSONArray(AppPreferences.Reverb.presets)
        (0 until minOf(array.length(),20)).mapNotNull { index ->
            val item=array.optJSONObject(index)?:return@mapNotNull null
            val name=item.optString("name").trim().take(40)
            if(name.isBlank())null else ReverbPreset(name,ReverbSettings.decode(item.optString("params")))
        }.distinctBy { it.name }
    }.getOrDefault(emptyList())
}
