package com.rawsmusic.module.data.prefs

/** Presentation styles for the shared top menu layer. */
enum class TopChromeStyle(val prefValue: String) {
    HAZE("haze"),
    TRANSPARENT("transparent"),
    FLOATING("floating"),
    FLOATING_VERTICAL("floating_vertical");

    companion object {
        fun fromPref(value: String?): TopChromeStyle =
            entries.firstOrNull { it.prefValue == value } ?: HAZE
    }
}
