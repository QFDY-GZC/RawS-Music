package com.rawsmusic.core.ui.widget

/** Only tablet landscape replaces the separate lyric scene with an interactive inline viewport. */
fun usesInlinePlayerLyrics(smallestWidthDp: Int, widthDp: Float, heightDp: Float): Boolean =
    smallestWidthDp >= 600 && widthDp.isFinite() && heightDp.isFinite() &&
        heightDp > 0f && widthDp > heightDp
