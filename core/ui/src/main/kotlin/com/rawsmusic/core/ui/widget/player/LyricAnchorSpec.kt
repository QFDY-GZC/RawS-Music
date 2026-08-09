package com.rawsmusic.core.ui.widget.player

import kotlin.math.max

/** Positioning constants shared by the lyric follow path. */
internal object LyricAnchorSpec {
    /** The active lyric follows one quarter of the visible viewport. */
    const val VIEWPORT_FRACTION: Float = 0.25f
    const val CORRECTION_THRESHOLD_PX: Float = 1.5f
    const val MAX_CORRECTION_PASSES: Int = 2

    fun targetOffsetPx(viewportStartOffset: Int, viewportEndOffset: Int): Float {
        val viewportHeight = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)
        return viewportStartOffset + viewportHeight * VIEWPORT_FRACTION
    }

    /**
     * Reserve enough content after the final line for its top edge to reach the follow anchor.
     * A full trailing 75% viewport is intentionally conservative because the final row height is
     * not known when content padding is composed.
     */
    fun requiredTrailingPaddingPx(viewportHeightPx: Float): Float {
        if (!viewportHeightPx.isFinite() || viewportHeightPx <= 0f) return 0f
        return max(0f, viewportHeightPx * (1f - VIEWPORT_FRACTION))
    }
}
