package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun usesRelativeLineProgress(enabled: Boolean, active: Boolean, timedWordCount: Int): Boolean =
    enabled && active && timedWordCount == 0

internal fun relativeLyricLineProgress(positionMs: Float, beginMs: Long, endMs: Long): Float {
    if (endMs <= beginMs) return if (positionMs >= beginMs) 1f else 0f
    return ((positionMs - beginMs) / (endMs - beginMs)).coerceIn(0f, 1f)
}

/** Distribute a source line's duration over its measured visual rows, without inventing word times. */
internal fun relativeVisualRowProgress(progress: Float, rowWidths: FloatArray, rowIndex: Int): Float {
    if (rowIndex !in rowWidths.indices) return 0f
    val total = rowWidths.sum().coerceAtLeast(1f)
    var before = 0f
    for (index in 0 until rowIndex) before += rowWidths[index].coerceAtLeast(0f)
    val rowWidth = rowWidths[rowIndex].coerceAtLeast(0f)
    if (rowWidth == 0f) return if (progress * total >= before) 1f else 0f
    return ((progress.coerceIn(0f, 1f) * total - before) / rowWidth).coerceIn(0f, 1f)
}

internal fun lyricDepthBlurTarget(
    enabled: Boolean,
    highlighted: Boolean,
    distance: Int,
    highlightAll: Boolean,
): Dp = when {
    !enabled || highlighted || highlightAll || distance < 2 -> 0.dp
    else -> ((2f + distance.coerceAtMost(4)) * 0.8f).dp
}

internal fun lyricDepthBlurForFrame(
    animatedRadius: Dp,
    naturalFollowRunning: Boolean,
    zoomActive: Boolean,
): Dp = when {
    naturalFollowRunning -> animatedRadius
    zoomActive -> 0.dp
    else -> animatedRadius
}

@Composable
internal fun RelativeLyricLineText(
    text: String,
    beginMs: Long,
    endMs: Long,
    clock: LyricDirectRenderClock?,
    fallbackPositionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var layout by remember(text, style) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        color = dimColor,
        style = style,
        onTextLayout = { if (layout != it) layout = it },
        modifier = modifier
            .fillMaxWidth()
            .lyricClockDrawInvalidation(clock)
            .drawWithCache {
                val measured = layout
                val widths = measured?.let { result ->
                    FloatArray(result.lineCount) { row ->
                        (result.getLineRight(row) - result.getLineLeft(row)).coerceAtLeast(0f)
                    }
                }
                onDrawWithContent {
                    drawContent()
                    if (measured == null || widths == null) return@onDrawWithContent
                    val position = clock?.currentPosition() ?: fallbackPositionMs.toFloat()
                    val progress = relativeLyricLineProgress(position, beginMs, endMs)
                    if (progress <= 0f) return@onDrawWithContent
                    for (row in 0 until measured.lineCount) {
                        val amount = relativeVisualRowProgress(progress, widths, row)
                        if (amount <= 0f) continue
                        val left = measured.getLineLeft(row)
                        val right = measured.getLineRight(row)
                        val rtl = measured.getParagraphDirection(measured.getLineStart(row)) == ResolvedTextDirection.Rtl
                        val edge = if (rtl) right - (right - left) * amount else left + (right - left) * amount
                        clipRect(
                            left = if (rtl) edge else left,
                            top = measured.getLineTop(row),
                            right = if (rtl) right else edge,
                            bottom = measured.getLineBottom(row),
                        ) {
                            drawText(measured, color = highlightColor)
                        }
                    }
                }
            },
    )
}
