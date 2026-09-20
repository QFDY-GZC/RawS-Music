package com.rawsmusic.module.data.prefs

enum class MiniPlayerPlayPausePosition(val prefValue: String) {
    TRAILING("trailing"),
    ARTWORK_LEFT("artwork_left"),
    ARTWORK_RIGHT("artwork_right");

    companion object {
        fun fromPref(value: String?): MiniPlayerPlayPausePosition =
            entries.firstOrNull { it.prefValue == value } ?: TRAILING
    }
}

enum class MiniPlayerSecondaryAction(val prefValue: String) {
    DEFAULT("default"),
    QUEUE("queue"),
    HIDDEN("hidden");

    companion object {
        fun fromPref(value: String?): MiniPlayerSecondaryAction =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

data class MiniPlayerControlSettings(
    val showPrevious: Boolean = false,
    val playPausePosition: MiniPlayerPlayPausePosition = MiniPlayerPlayPausePosition.TRAILING,
    val secondaryAction: MiniPlayerSecondaryAction = MiniPlayerSecondaryAction.DEFAULT,
)
