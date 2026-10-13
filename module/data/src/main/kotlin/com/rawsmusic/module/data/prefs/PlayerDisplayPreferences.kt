package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlayerDisplayPreferences {
    private const val KEEP_SCREEN_ON = "player_keep_screen_on"
    private val mutableKeepScreenOn by lazy {
        MutableStateFlow(AppPreferences.storage.decodeBool(KEEP_SCREEN_ON, false))
    }
    val keepScreenOn by lazy { mutableKeepScreenOn.asStateFlow() }

    fun setKeepScreenOn(enabled: Boolean) {
        AppPreferences.storage.encode(KEEP_SCREEN_ON, enabled)
        mutableKeepScreenOn.value = enabled
    }
}
