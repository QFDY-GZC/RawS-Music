package com.rawsmusic.module.data.prefs

/** Presentation modes for the shared player/navigation bottom chrome. */
enum class BottomBarStyle(val prefValue: String) {
    NORMAL("normal"),
    FLOATING("floating");

    companion object {
        fun fromPref(value: String?): BottomBarStyle = when (value) {
            NORMAL.prefValue -> NORMAL
            FLOATING.prefValue,
            "acrylic",
            "liquid_glass" -> FLOATING
            else -> FLOATING
        }
    }
}

/** Independent surface material for the shared player/navigation bottom chrome. */
enum class BottomBarMaterial(val prefValue: String) {
    SOLID("solid"),
    ACRYLIC("acrylic"),
    LIQUID_GLASS("liquid_glass");

    companion object {
        fun fromPref(value: String?): BottomBarMaterial =
            entries.firstOrNull { it.prefValue == value } ?: LIQUID_GLASS

        fun fromLegacyStylePref(value: String?): BottomBarMaterial = when (value) {
            "acrylic" -> ACRYLIC
            "normal" -> SOLID
            else -> LIQUID_GLASS
        }
    }
}
