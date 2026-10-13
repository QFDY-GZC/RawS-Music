package com.rawsmusic.core.ui.widget.bitmaps

/** Provider request policy, independent of whether a holder may retain another song's pixels. */
internal data class PlaybackArtworkLoadPlan(
    val targetSide: Int,
    val foreground: Boolean,
    val retainPreviousPixels: Boolean,
)

internal fun playbackArtworkLoadPlan(
    preferredSide: Int,
    isCurrent: Boolean,
    isTarget: Boolean,
    isMoving: Boolean,
): PlaybackArtworkLoadPlan = PlaybackArtworkLoadPlan(
    targetSide = preferredSide.coerceAtLeast(1),
    foreground = isCurrent || isTarget,
    retainPreviousPixels = isCurrent && !isMoving,
)
