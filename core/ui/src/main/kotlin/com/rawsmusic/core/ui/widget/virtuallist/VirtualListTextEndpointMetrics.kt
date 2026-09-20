package com.rawsmusic.core.ui.widget.virtuallist

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint

/** Holder-owned endpoint measurements. Geometry/alpha ticks must not reshape the text. */
internal class VirtualListTextEndpointMetrics {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val ink = Rect()
    val font = Paint.FontMetrics()
    var advance = 0f
        private set
    private var source: String? = null
    private var size = Float.NaN
    private var face: Typeface? = null

    fun prepare(text: String, textSize: Float, typeface: Typeface) {
        if (source == text && size == textSize && face === typeface) return
        source = text
        size = textSize
        face = typeface
        paint.textSize = textSize
        paint.typeface = typeface
        ink.setEmpty()
        if (text.isNotEmpty()) paint.getTextBounds(text, 0, text.length, ink)
        paint.getFontMetrics(font)
        advance = paint.measureText(text)
    }
}

internal fun virtualListTextMetricFraction(size: Float, source: Float, target: Float): Float =
    if (source == target) 0f else ((size - source) / (target - source)).coerceIn(0f, 1f)

/**
 * Keep one glyph raster size for a transition and scale that run instead of reshaping it at every
 * progress tick. Choosing the larger endpoint avoids upscaling a smaller glyph atlas at the dense
 * endpoint while preserving both directions with the same rule.
 */
internal fun virtualListTransitionRasterTextSize(source: Float, target: Float): Float =
    maxOf(source, target).coerceAtLeast(0f)

internal fun virtualListTransitionGlyphScale(visualSize: Float, rasterSize: Float): Float =
    if (rasterSize <= 0f) 1f else (visualSize / rasterSize).coerceAtLeast(0f)

/**
 * Scene geometry is not text content. The text node keeps its glyph display list while
 * the scene host advances bounds/scale/translation; only a real raster-content change records a
 * new glyph run. Width participates only when the settled view is ellipsized.
 */
internal fun virtualListTextNeedsRasterRecord(
    textChanged: Boolean,
    rasterSizeChanged: Boolean,
    typefaceChanged: Boolean,
    colorChanged: Boolean,
    leftInsetChanged: Boolean,
    ellipsizeChanged: Boolean,
    widthChanged: Boolean,
    ellipsize: Boolean,
): Boolean =
    textChanged ||
        rasterSizeChanged ||
        typefaceChanged ||
        colorChanged ||
        leftInsetChanged ||
        ellipsizeChanged ||
        (ellipsize && widthChanged)
