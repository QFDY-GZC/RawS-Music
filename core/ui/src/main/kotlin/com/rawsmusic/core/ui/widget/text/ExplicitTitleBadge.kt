package com.rawsmusic.core.ui.widget.text

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

/** Small, neutral E in a rounded rectangle immediately after the painted song title. */
internal fun explicitBadgeWidth(fontSizePx: Float): Float = fontSizePx * 0.78f
internal fun explicitBadgeGap(fontSizePx: Float): Float = fontSizePx * 0.28f

internal fun createExplicitBadgeLabelPaint(fontSizePx: Float): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = fontSizePx * 0.60f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

internal fun DrawScope.drawExplicitTitleBadge(
    left: Float,
    titleBaseline: Float,
    fontSizePx: Float,
    labelPaint: Paint,
) {
    val width = explicitBadgeWidth(fontSizePx)
    val height = fontSizePx * 0.78f
    val top = titleBaseline - fontSizePx * 0.85f
    drawRoundRect(
        color = Color(0xFF858585),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
    val fm = labelPaint.fontMetrics
    val centeredBaseline = top + height * 0.5f - (fm.ascent + fm.descent) * 0.5f
    drawContext.canvas.nativeCanvas.drawText(
        "E",
        left + (width - labelPaint.measureText("E")) * 0.5f,
        centeredBaseline,
        labelPaint,
    )
}
