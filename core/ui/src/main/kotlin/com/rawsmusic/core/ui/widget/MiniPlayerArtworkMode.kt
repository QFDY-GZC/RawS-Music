package com.rawsmusic.core.ui.widget

enum class MiniPlayerArtworkMode {
    Normal,
    Vinyl;

    fun toggle(): MiniPlayerArtworkMode {
        return if (this == Normal) Vinyl else Normal
    }

    companion object {
        fun fromPrefs(value: String?): MiniPlayerArtworkMode {
            return when (value) {
                "vinyl" -> Vinyl
                else -> Normal
            }
        }

        fun toPrefs(mode: MiniPlayerArtworkMode): String {
            return when (mode) {
                Normal -> "normal"
                Vinyl -> "vinyl"
            }
        }
    }
}
