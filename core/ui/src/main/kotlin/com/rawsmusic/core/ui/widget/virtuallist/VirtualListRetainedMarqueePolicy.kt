package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained text child extent for VirtualList marquee rendering.
 *
 * The visible viewport remains the artwork text rect. Only the child display-list bounds grow, and only
 * when marquee is actually enabled and overflowing, so trailing glyphs survive RenderNode recording
 * without inflating ordinary/non-animated rows.
 */
internal fun virtualListRetainedTextNodeWidth(
    holderWidth: Int,
    rectLeft: Int,
    leftInsetPx: Float,
    measuredWidthPx: Float,
    overflowPx: Float,
    marqueeAllowed: Boolean,
): Int {
    val safeHolderWidth = holderWidth.coerceAtLeast(1)
    if (!marqueeAllowed || overflowPx <= 0.5f) return safeHolderWidth
    val fullRight = rectLeft.toFloat() + leftInsetPx + measuredWidthPx.coerceAtLeast(0f) + 2f
    return max(safeHolderWidth, ceil(fullRight.toDouble()).toInt()).coerceAtLeast(1)
}
