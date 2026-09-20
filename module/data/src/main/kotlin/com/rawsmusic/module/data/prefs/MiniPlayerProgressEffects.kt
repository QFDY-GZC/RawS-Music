package com.rawsmusic.module.data.prefs

enum class MiniPlayerProgressDirection(val prefValue: String) {
    LEFT_TO_RIGHT("ltr"),
    RIGHT_TO_LEFT("rtl");

    companion object {
        fun fromPref(value: String?): MiniPlayerProgressDirection =
            entries.firstOrNull { it.prefValue == value } ?: LEFT_TO_RIGHT
    }
}

enum class MiniPlayerBubbleMotion(val prefValue: String) {
    FLOAT("float"),
    WAVE("wave"),
    ORBIT("orbit");

    companion object {
        fun fromPref(value: String?): MiniPlayerBubbleMotion =
            entries.firstOrNull { it.prefValue == value } ?: FLOAT
    }
}

enum class MiniPlayerBubbleOrigin(val prefValue: String) {
    START_EDGE("start_edge"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromPref(value: String?): MiniPlayerBubbleOrigin =
            entries.firstOrNull { it.prefValue == value } ?: START_EDGE
    }
}

data class MiniPlayerProgressEffects(
    val direction: MiniPlayerProgressDirection = MiniPlayerProgressDirection.LEFT_TO_RIGHT,
    val bubbleCount: Int = 14,
    val bubbleMotion: MiniPlayerBubbleMotion = MiniPlayerBubbleMotion.FLOAT,
    val bubbleSpeed: Float = 1f,
    val highEnergyHighlight: Boolean = false,
    val bubbleOrigin: MiniPlayerBubbleOrigin = MiniPlayerBubbleOrigin.START_EDGE,
) {
    fun normalized(): MiniPlayerProgressEffects = copy(
        bubbleCount = bubbleCount.coerceIn(0, 32),
        bubbleSpeed = bubbleSpeed.coerceIn(0.25f, 2.5f),
    )
}
