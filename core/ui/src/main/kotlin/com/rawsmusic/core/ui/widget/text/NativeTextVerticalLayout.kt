package com.rawsmusic.core.ui.widget.text

import kotlin.math.max
import kotlin.math.min

/**
 * Resolves a baseline for native Canvas text without allowing the actual glyph ink to be clipped
 * by the owning Canvas. Scene/layout boxes may intentionally be tighter than a font's full metrics;
 * horizontal clipping still belongs to the caller, while vertical placement is corrected from the
 * real string ink bounds.
 */
internal data class NativeTextVerticalLayout(
    val baseline: Float,
    val clipTop: Float,
    val clipBottom: Float,
)

internal fun resolveNativeTextVerticalLayout(
    containerTop: Float,
    containerBottom: Float,
    canvasTop: Float,
    canvasBottom: Float,
    ascent: Float,
    descent: Float,
    fontTop: Float,
    fontBottom: Float,
    inkTop: Float,
    inkBottom: Float,
    antiAliasSlackPx: Float = 1f,
): NativeTextVerticalLayout {
    val safeCanvasTop = min(canvasTop, canvasBottom)
    val safeCanvasBottom = max(canvasTop, canvasBottom)
    val safeContainerTop = containerTop.coerceIn(safeCanvasTop, safeCanvasBottom)
    val safeContainerBottom = containerBottom.coerceIn(safeContainerTop, safeCanvasBottom)
    val slack = antiAliasSlackPx.coerceAtLeast(0f)

    // Preserve the existing visual centering as the preferred position. Only move the baseline when
    // the actual ink would otherwise escape the physical Canvas. This avoids changing the vertical
    // rhythm of rows whose text already fits.
    val preferredBaseline =
        (safeContainerTop + safeContainerBottom - ascent - descent) * 0.5f

    // getTextBounds() is string-specific and catches descenders/ink that can differ from the generic
    // font box. Fall back to FontMetrics.top/bottom for empty/degenerate bounds.
    val resolvedInkTop = if (inkBottom > inkTop) inkTop else fontTop
    val resolvedInkBottom = if (inkBottom > inkTop) inkBottom else fontBottom
    val minBaseline = safeCanvasTop + slack - resolvedInkTop
    val maxBaseline = safeCanvasBottom - slack - resolvedInkBottom
    val baseline = if (minBaseline <= maxBaseline) {
        preferredBaseline.coerceIn(minBaseline, maxBaseline)
    } else {
        // Extremely large accessibility fonts can make the ink taller than the fixed Canvas. Center
        // the ink box rather than biasing the descender out of view.
        (safeCanvasTop + safeCanvasBottom - resolvedInkTop - resolvedInkBottom) * 0.5f
    }

    // Never use a scene line box as a vertical clip. Reference's TextNode owns clipping at the
    // physical view, after its baseline/line metrics are resolved. Keep the same ownership here.
    return NativeTextVerticalLayout(
        baseline = baseline,
        clipTop = safeCanvasTop,
        clipBottom = safeCanvasBottom,
    )
}
