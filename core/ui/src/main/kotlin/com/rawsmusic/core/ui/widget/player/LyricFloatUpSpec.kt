package com.rawsmusic.core.ui.widget.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal data class LyricGlyphSliceSpec(
    val leftPx: Float,
    val rightPx: Float,
    val logicalCenterPx: Float,
)

/**
 * Karaoke float-up geometry derived from the supplied reference implementation.
 *
 * The effect is not a per-word spring/tween. A shared karaoke cursor moves through text geometry,
 * and each glyph samples a half-cosine spatial window. The shipped enabled property uses 10% of
 * the text scale as peak lift; disabled is exactly 0. Near the physical end of a line the cosine
 * window settles toward 1 so the final glyphs do not drop back down while the cursor leaves.
 */
internal object LyricFloatUpSpec {
    const val ENABLED_PERCENTAGE = 0.10f
    const val DISABLED_PERCENTAGE = 0f
    const val WINDOW_TEXT_SCALES = 3f

    fun peakLiftPx(
        textScalePx: Float,
        minimumLiftPx: Float = 0f,
        percentage: Float = ENABLED_PERCENTAGE,
    ): Float {
        val minimum = minimumLiftPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (!textScalePx.isFinite() || textScalePx <= 0f || !percentage.isFinite() || percentage <= 0f) {
            return minimum
        }
        // Round the dimension*percentage result before it reaches the draw properties.
        return maxOf(minimum, (textScalePx * percentage).roundToInt().toFloat())
    }

    /**
     * Spatial lift fraction for one glyph/word centre.
     *
     * Normal branch derived from the reference implementation:
     *   x = (centre - cursor + window/2) / window
     *   f = 0.5*cos(PI*x) + 0.5, clamped by x<=0 -> 1 and x>=1 -> 0.
     *
     * [lineEndPx] enables the end-settle branch. Pass NaN when this sample is not on the final
     * word/segment of the visual line. The branch smoothly changes cosine A/B so all remaining
     * samples settle at 1 as the cursor reaches/passes the line end.
     */
    fun spatialFraction(
        centerPx: Float,
        cursorPx: Float,
        textScalePx: Float,
        lineEndPx: Float = Float.NaN,
    ): Float {
        if (!centerPx.isFinite() || !cursorPx.isFinite() || !textScalePx.isFinite() || textScalePx <= 0f) {
            return 0f
        }
        val windowPx = (textScalePx * WINDOW_TEXT_SCALES).coerceAtLeast(0.001f)
        val halfWindowPx = windowPx * 0.5f

        var settle = 0f
        if (lineEndPx.isFinite() && halfWindowPx > 0f) {
            val remainingPx = lineEndPx - cursorPx
            if (remainingPx < halfWindowPx) {
                val proximity = (1f - remainingPx / halfWindowPx).coerceIn(0f, 1f)
                settle = proximity * proximity
            }
        }

        val cosineAmplitude = (1f - settle) * 0.5f
        val cosineOffset = settle * 0.5f + 0.5f
        val x = (centerPx - cursorPx + halfWindowPx) / windowPx
        return when {
            x <= 0f -> 1f
            x >= 1f -> 0f
            else -> (cos(PI * x.toDouble()).toFloat() * cosineAmplitude + cosineOffset)
                .coerceIn(0f, 1f)
        }
    }

    fun cursorPx(
        positionMs: Float,
        beginMs: Long,
        durationMs: Long,
        measuredWidthPx: Float,
    ): Float {
        if (!positionMs.isFinite() || measuredWidthPx <= 0f || durationMs <= 0L) return 0f
        val rawProgress = (positionMs - beginMs.toFloat()) / durationMs.toFloat()
        return rawProgress * measuredWidthPx
    }

    fun wordCenterFraction(
        positionMs: Float,
        beginMs: Long,
        durationMs: Long,
        measuredWidthPx: Float,
        textScalePx: Float,
        settleAtEnd: Boolean,
    ): Float {
        if (positionMs < beginMs.toFloat()) return 0f
        val width = measuredWidthPx.coerceAtLeast(1f)
        val cursor = cursorPx(positionMs, beginMs, durationMs, width)
        return spatialFraction(
            centerPx = width * 0.5f,
            cursorPx = cursor,
            textScalePx = textScalePx,
            lineEndPx = if (settleAtEnd) width else Float.NaN,
        )
    }

    /**
     * Convert physical shaped-layout glyph centres into non-overlapping clip partitions while
     * preserving the logical reading direction for the spatial cursor.
     */
    fun buildGlyphSlices(
        physicalCentersPx: List<Float>,
        widthPx: Float,
        rtl: Boolean,
    ): List<LyricGlyphSliceSpec> {
        if (!widthPx.isFinite() || widthPx <= 0f) return emptyList()
        val centers = physicalCentersPx
            .asSequence()
            .filter { it.isFinite() }
            .map { it.coerceIn(0f, widthPx) }
            .sorted()
            .toList()
        if (centers.isEmpty()) return emptyList()
        return centers.mapIndexed { index, center ->
            val left = if (index == 0) 0f else (centers[index - 1] + center) * 0.5f
            val right = if (index == centers.lastIndex) widthPx else (center + centers[index + 1]) * 0.5f
            LyricGlyphSliceSpec(
                leftPx = left.coerceIn(0f, widthPx),
                rightPx = right.coerceIn(left.coerceIn(0f, widthPx), widthPx),
                logicalCenterPx = if (rtl) widthPx - center else center,
            )
        }
    }

    /** Partition between shaped bounds, never between centres of unequal-width glyphs. */
    fun buildGlyphSlicesFromBounds(
        bounds: List<Pair<Float, Float>>,
        widthPx: Float,
        rtl: Boolean,
    ): List<LyricGlyphSliceSpec> {
        if (!widthPx.isFinite() || widthPx <= 0f) return emptyList()
        val groups = ArrayList<Pair<Float, Float>>()
        for ((left, right) in bounds.filter { (l, r) -> l.isFinite() && r.isFinite() && r > l }
            .sortedBy { it.first }) {
            val previous = groups.lastOrNull()
            // Combining marks/ligatures can share a shaped rectangle. They are one moving unit.
            if (previous != null && left < previous.second) {
                groups[groups.lastIndex] = previous.first to maxOf(previous.second, right)
            } else groups += left to right
        }
        return groups.mapIndexed { index, (left, right) ->
            val cutLeft = if (index == 0) 0f else (groups[index - 1].second + left) * 0.5f
            val cutRight = if (index == groups.lastIndex) widthPx else (right + groups[index + 1].first) * 0.5f
            val center = ((left + right) * 0.5f).coerceIn(0f, widthPx)
            LyricGlyphSliceSpec(cutLeft.coerceIn(0f, widthPx), cutRight.coerceIn(0f, widthPx),
                if (rtl) widthPx - center else center)
        }
    }
}
