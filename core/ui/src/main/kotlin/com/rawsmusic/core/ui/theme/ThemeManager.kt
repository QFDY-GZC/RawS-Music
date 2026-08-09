package com.rawsmusic.core.ui.theme

import android.content.Context
import com.rawsmusic.core.common.prefs.UIPreferences

object ThemeManager {

    @Volatile
    var isLightBackground: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                listeners.forEach { it(value) }
            }
        }

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun addOnBackgroundChangeListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeOnBackgroundChangeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    enum class ThemeMode(val value: Int) {
        SYSTEM(0),
        LIGHT(1),
        DARK(2)
    }

    fun applyTheme(mode: ThemeMode) {
        UIPreferences.themeMode = mode.value
        RawThemeRuntimeState.invalidate()
    }

    fun applyStoredTheme() = Unit

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
