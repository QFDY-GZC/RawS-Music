package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PersonalizationPreferences {
    private const val KEY_PREDICTIVE_BACK_ANIMATION = "personalization_predictive_back_animation"
    private const val KEY_PERFORMANCE_MODE = "personalization_performance_mode"
    private const val KEY_PERFORMANCE_MODE_DEFAULT_V2 = "personalization_performance_mode_default_v2"
    private const val KEY_BOTTOM_NAVIGATION_SCENES = "personalization_bottom_navigation_scenes_v1"

    private val allowedBottomNavigationTags = setOf(
        "home",
        "songs",
        "folders",
        "albums",
        "artists",
        "playlists",
        "queue",
        "recently_added",
        "genre",
        "audio_effects",
        "search",
        "settings",
    )

    val defaultBottomNavigationSceneTags: List<String> = listOf(
        "home",
        "songs",
        "audio_effects",
        "search",
        "settings",
    )

    var predictiveBackAnimationEnabled: Boolean
        get() = AppPreferences.storage.decodeBool(KEY_PREDICTIVE_BACK_ANIMATION, true)
        set(value) {
            AppPreferences.storage.encode(KEY_PREDICTIVE_BACK_ANIMATION, value)
        }

    private val _performanceModeEnabled = MutableStateFlow(initialPerformanceMode())
    val performanceMode = _performanceModeEnabled.asStateFlow()

    var performanceModeEnabled: Boolean
        get() = _performanceModeEnabled.value
        set(value) {
            _performanceModeEnabled.value = value
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE, value)
        }

    private val _bottomNavigationEnabled = MutableStateFlow(AppPreferences.UI.isBottomBarEnabled)
    val bottomNavigationEnabled = _bottomNavigationEnabled.asStateFlow()

    var isBottomNavigationEnabled: Boolean
        get() = _bottomNavigationEnabled.value
        set(value) {
            if (_bottomNavigationEnabled.value == value) return
            _bottomNavigationEnabled.value = value
            AppPreferences.UI.isBottomBarEnabled = value
        }

    private val _bottomNavigationSceneTags = MutableStateFlow(loadBottomNavigationSceneTags())
    val bottomNavigationSceneTags = _bottomNavigationSceneTags.asStateFlow()

    var bottomNavigationScenes: List<String>
        get() = _bottomNavigationSceneTags.value
        set(value) {
            val normalized = normalizeBottomNavigationSceneTags(value)
            _bottomNavigationSceneTags.value = normalized
            AppPreferences.storage.encode(KEY_BOTTOM_NAVIGATION_SCENES, normalized.joinToString(","))
        }

    fun resetBottomNavigationScenes() {
        bottomNavigationScenes = defaultBottomNavigationSceneTags
    }

    private fun initialPerformanceMode(): Boolean {
        if (!AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE_DEFAULT_V2, false)) {
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE_DEFAULT_V2, true)
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE, true)
            return true
        }
        return AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE, true)
    }

    private fun loadBottomNavigationSceneTags(): List<String> {
        val stored = AppPreferences.storage.decodeString(KEY_BOTTOM_NAVIGATION_SCENES, "")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (stored.isEmpty()) return defaultBottomNavigationSceneTags
        return normalizeBottomNavigationSceneTags(stored)
    }

    private fun normalizeBottomNavigationSceneTags(raw: List<String>): List<String> {
        val result = raw
            .filter { it in allowedBottomNavigationTags }
            .distinct()
            .toMutableList()

        if ("home" !in result) result.add(0, "home")
        if (result.size < 2) {
            defaultBottomNavigationSceneTags.firstOrNull { it !in result }?.let(result::add)
        }
        return result.take(5)
    }
}
