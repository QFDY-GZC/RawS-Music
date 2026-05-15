package com.rawsmusic.core.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.rawsmusic.core.common.prefs.UIPreferences

object ThemeManager {

    enum class ThemeMode(val value: Int) {
        SYSTEM(0),
        LIGHT(1),
        DARK(2)
    }

    fun applyTheme(mode: ThemeMode) {
        UIPreferences.themeMode = mode.value
        when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            ThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ThemeMode.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun getCurrentTheme(): ThemeMode {
        return ThemeMode.entries.getOrElse(UIPreferences.themeMode) { ThemeMode.SYSTEM }
    }

    fun isDarkMode(context: Context): Boolean {
        return when (getCurrentTheme()) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
}
