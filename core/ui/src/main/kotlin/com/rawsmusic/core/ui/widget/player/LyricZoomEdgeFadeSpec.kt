package com.rawsmusic.core.ui.widget.player

/**
 * VirtualList-style edge-holder handoff for lyric zoom.
 *
 * Shared holders never crossfade typography. Only a holder that exists at one endpoint is moved
 * through an off-screen extension lane, where its alpha follows its physical distance from the
 * viewport. This lets the stack make room before a row disappears/appears and avoids a dual-text
 * flash in the middle of the pinch.
 */
internal object LyricZoomEdgeFadeSpec {
    fun extendedCenterPx(
        endpointCenterPx: Float,
        referenceCenterPx: Float,
        viewportHeightPx: Float,
        rowHeightPx: Float,
    ): Float {
        val viewport = viewportHeightPx.coerceAtLeast(1f)
        val row = rowHeightPx.coerceAtLeast(1f)
        val topSide = when {
            endpointCenterPx < 0f -> true
            endpointCenterPx > viewport -> false
            else -> referenceCenterPx < viewport * 0.5f
        }
        return if (topSide) {
            minOf(endpointCenterPx, -row)
        } else {
            maxOf(endpointCenterPx, viewport + row)
        }
    }

    fun alphaForCenter(
        centerPx: Float,
        viewportHeightPx: Float,
        rowHeightPx: Float,
    ): Float {
        if (!centerPx.isFinite()) return 0f
        val viewport = viewportHeightPx.coerceAtLeast(1f)
        val fadeDistance = rowHeightPx.coerceAtLeast(1f)
        val linear = when {
            centerPx < 0f -> 1f + centerPx / fadeDistance
            centerPx > viewport -> 1f - (centerPx - viewport) / fadeDistance
            else -> 1f
        }.coerceIn(0f, 1f)
        // VirtualList alpha transitions are visually eased while holder geometry remains directly
        // tied to the gesture. Smoothstep preserves exact 0/1 endpoints without a hard threshold.
        return linear * linear * (3f - 2f * linear)
    }
}
