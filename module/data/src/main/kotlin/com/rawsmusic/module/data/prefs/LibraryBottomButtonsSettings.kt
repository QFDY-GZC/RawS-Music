package com.rawsmusic.module.data.prefs

/** reference player-compatible visibility mode for list bottom action buttons. */
enum class LibraryBottomButtonsMode(val prefValue: String, val maxAlpha: Float) {
    DISABLED("disabled", 0f),
    SEMI_TRANSPARENT("semi_transparent", 0.55f),
    ENABLED("enabled", 1f);

    companion object {
        fun fromPref(value: String?): LibraryBottomButtonsMode =
            entries.firstOrNull { it.prefValue == value } ?: ENABLED
    }
}

/** RawSMusic enhancement layered on top of the reference player-compatible bottom-button owner. */
enum class LibraryBottomButtonsLayout(val prefValue: String) {
    HORIZONTAL("horizontal"),
    VERTICAL("vertical");

    companion object {
        fun fromPref(value: String?): LibraryBottomButtonsLayout =
            entries.firstOrNull { it.prefValue == value } ?: HORIZONTAL
    }
}

/** Surface treatment for the reference player-style individual action buttons. */
enum class LibraryBottomButtonsSurface(val prefValue: String) {
    SOLID("solid"),
    FROSTED("frosted"),
    LIQUID_GLASS("liquid_glass");

    companion object {
        fun fromPref(value: String?): LibraryBottomButtonsSurface =
            entries.firstOrNull { it.prefValue == value } ?: SOLID
    }
}
