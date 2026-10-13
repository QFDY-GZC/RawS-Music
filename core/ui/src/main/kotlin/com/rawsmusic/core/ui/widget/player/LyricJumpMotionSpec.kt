package com.rawsmusic.core.ui.widget.player

/** Lazy lists cannot retain every intermediate paragraph during a distant seek. */
internal object LyricJumpMotionSpec {
    fun displacement(estimatedPx: Float, viewportPx: Float): Float {
        if (!estimatedPx.isFinite() || !viewportPx.isFinite() || viewportPx <= 0f) return 0f
        // Keep the incoming sentence on screen by limiting its virtualized position.
        return estimatedPx.coerceIn(-viewportPx * LyricAnchorSpec.VIEWPORT_FRACTION, viewportPx * .5f)
    }
}
