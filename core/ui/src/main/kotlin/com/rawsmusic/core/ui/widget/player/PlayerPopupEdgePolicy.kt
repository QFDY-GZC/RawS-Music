package com.rawsmusic.core.ui.widget.player

internal fun popupEdgeBlurWeight(y: Float, height: Float, edge: Float, top: Boolean, bottom: Boolean): Float {
    if (height <= 0f || edge <= 0f) return 0f
    val band = minOf(edge, height / 3f)
    val distance = minOf(if (top) y.coerceAtLeast(0f) else band,
        if (bottom) (height - y).coerceAtLeast(0f) else band)
    val t = (1f - distance / band).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
