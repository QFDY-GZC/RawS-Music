package com.rawsmusic.core.ui.widget.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences

object LongTextMotionState {
    var enabled by mutableStateOf(AppPreferences.UI.animateLongLabels)
        private set

    var enabledEverywhere by mutableStateOf(AppPreferences.UI.animateLongLabelsEverywhere)
        private set

    fun updateEnabled(value: Boolean) {
        enabled = value
        AppPreferences.UI.animateLongLabels = value
        if (!value) updateEnabledEverywhere(false)
    }

    fun updateEnabledEverywhere(value: Boolean) {
        enabledEverywhere = value && enabled
        AppPreferences.UI.animateLongLabelsEverywhere = enabledEverywhere
    }
}
