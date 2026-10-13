package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object HdrPreferences {
    private const val ENABLED = "hdr_window_enabled"
    private const val SCENES = "hdr_window_scenes"
    private const val HEADROOM = "hdr_window_headroom"
    private val mutableSettings by lazy {
        val stored = AppPreferences.storage.decodeStringSet(SCENES)
        MutableStateFlow(HdrWindowSettings(
            enabled = AppPreferences.storage.decodeBool(ENABLED, false),
            desiredHeadroom = AppPreferences.storage.decodeFloat(HEADROOM, 0f),
            scenes = stored?.let { keys -> HdrScene.entries.filter { it.name in keys }.toSet() }
                ?: setOf(HdrScene.PLAYER),
        ))
    }
    val settings by lazy { mutableSettings.asStateFlow() }

    @Synchronized fun setEnabled(enabled: Boolean) = save(mutableSettings.value.copy(enabled = enabled))
    @Synchronized fun setHeadroom(value: Float) {
        val safe = HdrWindowSettings(desiredHeadroom = value).requestedHeadroom(35)
        save(mutableSettings.value.copy(desiredHeadroom = safe))
    }
    @Synchronized fun setScene(scene: HdrScene, selected: Boolean) {
        val current = mutableSettings.value
        save(current.copy(scenes = if (selected) current.scenes + scene else current.scenes - scene))
    }
    private fun save(settings: HdrWindowSettings) {
        AppPreferences.storage.encode(ENABLED, settings.enabled)
        AppPreferences.storage.encode(HEADROOM, settings.desiredHeadroom)
        AppPreferences.storage.encode(SCENES, settings.scenes.map { it.name }.toSet())
        mutableSettings.value = settings
    }
}
