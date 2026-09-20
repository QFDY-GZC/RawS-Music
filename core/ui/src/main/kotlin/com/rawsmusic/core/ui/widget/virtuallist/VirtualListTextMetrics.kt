package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.max
import kotlin.math.min

/**
 * Vertical clip owned by one VirtualList text lane.
 *
 * The scene rect describes layout/transition geometry, not the full glyph box. Android fonts can
 * extend a little beyond ascent/descent (most visibly on descenders such as g/p/q/y). Keep the
 * horizontal lane clipping strict for marquee, but expand the vertical clip just enough to cover
 * Paint.FontMetrics.top..bottom. The row Canvas remains the final clipping owner.
 */
internal data class VirtualListTextVerticalClip(
    val top: Float,
    val bottom: Float,
)

internal fun resolveVirtualListTextVerticalClip(
    rectTop: Float,
    rectBottom: Float,
    canvasHeight: Float,
    baseline: Float,
    fontTop: Float,
    fontBottom: Float,
    antiAliasSlackPx: Float = 1f,
): VirtualListTextVerticalClip {
    val height = canvasHeight.coerceAtLeast(0f)
    val slack = antiAliasSlackPx.coerceAtLeast(0f)
    val desiredTop = min(rectTop, baseline + fontTop - slack)
    val desiredBottom = max(rectBottom, baseline + fontBottom + slack)
    val top = desiredTop.coerceIn(0f, height)
    val bottom = desiredBottom.coerceIn(top, height)
    return VirtualListTextVerticalClip(top = top, bottom = bottom)
}
