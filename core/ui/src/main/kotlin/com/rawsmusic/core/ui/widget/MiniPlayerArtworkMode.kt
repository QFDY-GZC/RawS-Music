package com.rawsmusic.core.ui.widget

enum class MiniPlayerArtworkMode {
    Normal,
    Vinyl;

    fun toggle(): MiniPlayerArtworkMode {
        return if (this == Normal) Vinyl else Normal
    }

    companion object {
        fun fromPrefs(value: String?): MiniPlayerArtworkMode {
            // v14: reverse the display mapping and make the capsule default to vinyl.
            // Existing users that still have the old "normal" value will now see the vinyl
            // style by default; double-tap still toggles between the two styles.
            return when (value) {
                "vinyl" -> Normal
                "normal" -> Vinyl
                else -> Vinyl
            }
        }

        fun toPrefs(mode: MiniPlayerArtworkMode): String {
            return when (mode) {
                Normal -> "vinyl"
                Vinyl -> "normal"
            }
        }
    }
}
