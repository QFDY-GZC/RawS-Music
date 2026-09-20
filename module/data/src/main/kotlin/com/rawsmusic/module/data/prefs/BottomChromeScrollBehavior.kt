package com.rawsmusic.module.data.prefs

/** How content scrolling affects the shared player/navigation bottom chrome. */
enum class BottomChromeScrollBehavior(val prefValue: String) {
    DEFAULT("default"),
    STATIC("static");

    companion object {
        fun fromPref(value: String?): BottomChromeScrollBehavior =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
