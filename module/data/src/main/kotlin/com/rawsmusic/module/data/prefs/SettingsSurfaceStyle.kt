package com.rawsmusic.module.data.prefs

enum class SettingsSurfaceStyle(val prefValue: String) {
    SOLID("solid"),
    ACRYLIC("acrylic"),
    LIQUID_GLASS("liquid_glass");

    companion object {
        fun fromPref(value: String?): SettingsSurfaceStyle =
            entries.firstOrNull { it.prefValue == value } ?: SOLID
    }
}
