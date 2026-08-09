package com.rawsmusic.module.data.prefs

/** Presentation modes for the shared player/navigation bottom chrome. */
enum class BottomBarStyle(val prefValue: String) {
    NORMAL("normal"),
    FLOATING("floating");

    companion object {
        fun fromPref(value: String?): BottomBarStyle =
            entries.firstOrNull { it.prefValue == value } ?: FLOATING
    }
}
