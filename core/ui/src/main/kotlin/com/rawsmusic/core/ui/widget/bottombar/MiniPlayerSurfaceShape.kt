package com.rawsmusic.core.ui.widget.bottombar

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Returns the MiniPlayer surface curve used by both the real bottom chrome and its settings preview.
 * curve=2 keeps the legacy circular rounded-rect. Values below 2 sharpen the shoulder while
 * values above 2 progressively flatten it into a continuous/squircle-like curve without changing
 * the requested corner radius.
 *
 * The custom curve deliberately remains a [CornerBasedShape]. Backdrop's lens effect derives its
 * refraction geometry from that contract and rejects arbitrary Shapes even when their outline is a
 * valid path. The visual outline can still be [Outline.Generic]; the corner sizes give lens a safe
 * rounded-rect approximation while the actual surface/clip keeps the continuous curve.
 */
fun miniPlayerSurfaceShape(cornerRadius: Dp, curve: Float): Shape {
    val normalizedCurve = curve.coerceIn(1.2f, 8f)
    return if (abs(normalizedCurve - 2f) <= 0.01f) {
        RoundedCornerShape(cornerRadius)
    } else {
        ContinuousMiniPlayerShape(cornerRadius, normalizedCurve)
    }
}

private class ContinuousMiniPlayerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val exponent: Float,
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart,
) {
    constructor(cornerRadius: Dp, exponent: Float) : this(
        topStart = CornerSize(cornerRadius),
        topEnd = CornerSize(cornerRadius),
        bottomEnd = CornerSize(cornerRadius),
        bottomStart = CornerSize(cornerRadius),
        exponent = exponent,
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ContinuousMiniPlayerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart,
        exponent = exponent,
    )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val topLeft = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val topRight = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd

        if (topLeft <= 0.01f && topRight <= 0.01f && bottomRight <= 0.01f && bottomLeft <= 0.01f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }

        val path = Path()
        val w = size.width
        val h = size.height
        path.moveTo(topLeft, 0f)
        path.lineTo(w - topRight, 0f)
        appendQuarter(path, w - topRight, topRight, topRight, -HALF_PI, 0f)
        path.lineTo(w, h - bottomRight)
        appendQuarter(path, w - bottomRight, h - bottomRight, bottomRight, 0f, HALF_PI)
        path.lineTo(bottomLeft, h)
        appendQuarter(path, bottomLeft, h - bottomLeft, bottomLeft, HALF_PI, PI)
        path.lineTo(0f, topLeft)
        appendQuarter(path, topLeft, topLeft, topLeft, PI, PI + HALF_PI)
        path.close()
        return Outline.Generic(path)
    }

    private fun appendQuarter(
        path: Path,
        centerX: Float,
        centerY: Float,
        radius: Float,
        startAngle: Float,
        endAngle: Float,
    ) {
        if (radius <= 0.01f) {
            val endX = centerX + radius * cos(endAngle.toDouble()).toFloat()
            val endY = centerY + radius * sin(endAngle.toDouble()).toFloat()
            path.lineTo(endX, endY)
            return
        }
        val power = 2f / exponent
        val steps = 8
        for (step in 1..steps) {
            val t = step / steps.toFloat()
            val angle = startAngle + (endAngle - startAngle) * t
            val c = cos(angle.toDouble()).toFloat()
            val s = sin(angle.toDouble()).toFloat()
            val x = centerX + radius * signedPow(c, power)
            val y = centerY + radius * signedPow(s, power)
            path.lineTo(x, y)
        }
    }

    private fun signedPow(value: Float, power: Float): Float {
        if (abs(value) <= 0.000001f) return 0f
        return sign(value) * abs(value).pow(power)
    }

    private companion object {
        const val PI = 3.14159265358979323846f
        const val HALF_PI = PI / 2f
    }
}
