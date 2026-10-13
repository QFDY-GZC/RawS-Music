package com.rawsmusic.core.ui.widget.player

internal object LyricAmMotionSpec {
    const val STIFFNESS = 100f
    const val DAMPING_RATIO = .9f
    const val ROW_DELAY_MS = 50L
    const val HEADSTART_MS = 100L
    const val INACTIVE_SCALE = .98f
    const val LINE_SPACING_SP = 25
    const val PARAGRAPH_SPACING_DP = 39f
    const val TRANSLATION_SHOW_STIFFNESS = 150f
    const val TRANSLATION_HIDE_STIFFNESS = 130f
    const val TRANSLATION_SHOW_DAMPING_RATIO = 1.2247449f
    const val TRANSLATION_HIDE_DAMPING_RATIO = 1.315587f

    fun delayMs(rank: Int): Long = (rank - 1).coerceAtLeast(0) * ROW_DELAY_MS
    fun primaryLineHeight(sizeSp: Int, fullLyrics: Boolean): Int =
        sizeSp + if (fullLyrics) LINE_SPACING_SP else 6
}
