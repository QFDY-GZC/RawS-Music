package com.rawsmusic.core.ui.theme

import androidx.annotation.ColorInt
import com.rawsmusic.core.common.prefs.UIPreferences

object ColorScheme {

    data class AccentColor(
        val name: String,
        @ColorInt val color: Int
    )

    val accentColors = listOf(
        AccentColor("Blue", 0xFF2196F3.toInt()),
        AccentColor("Purple", 0xFF9C27B0.toInt()),
        AccentColor("Red", 0xFFF44336.toInt()),
        AccentColor("Green", 0xFF4CAF50.toInt()),
        AccentColor("Orange", 0xFFFF9800.toInt()),
        AccentColor("Pink", 0xFFE91E63.toInt()),
        AccentColor("Cyan", 0xFF00BCD4.toInt()),
        AccentColor("Teal", 0xFF009688.toInt())
    )

    fun getCurrentAccentColor(): AccentColor {
        val savedColor = UIPreferences.accentColor
        return accentColors.find { it.color == savedColor } ?: accentColors[0]
    }

    fun setAccentColor(color: AccentColor) {
        UIPreferences.accentColor = color.color
    }
}
