package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PersonalizationPreferences {
    private const val KEY_PREDICTIVE_BACK_ANIMATION = "personalization_predictive_back_animation"
    private const val KEY_PERFORMANCE_MODE = "personalization_performance_mode"
    private const val KEY_PERFORMANCE_MODE_DEFAULT_V2 = "personalization_performance_mode_default_v2"

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

    private fun initialPerformanceMode(): Boolean {
        if (!AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE_DEFAULT_V2, false)) {
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE_DEFAULT_V2, true)
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE, true)
            return true
        }
        return AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE, true)
    }
}
