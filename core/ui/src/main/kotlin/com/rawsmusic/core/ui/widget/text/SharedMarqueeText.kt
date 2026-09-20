package com.rawsmusic.core.ui.widget.text

import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Rect as AndroidRect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.core.ui.widget.player.LyricDirectFrameListener
import com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock
import com.rawsmusic.core.ui.widget.player.LyricRainbowColors
import com.rawsmusic.module.data.prefs.FontManager

/** Text marquee driven by the app-wide phase, with no frame loop of its own. */
@Composable
internal fun SharedMarqueeText(
    text: String,
    color: Color,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    visible: Boolean = true,
    highlightProgress: Float? = null,
    highlightCharacterPositionProvider: (() -> Float)? = null,
    highlightColor: Color = color,
    colorProvider: (() -> Color)? = null,
    highlightColorProvider: (() -> Color)? = null,
    highlightFromEnd: Boolean = false,
    rainbowGradient: Boolean = false,
    drawClock: LyricDirectRenderClock? = null,
    edgeFeather: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val runtimeVersion = RawThemeRuntimeState.version
    val paint = remember(
        text,
        color,
        fontSizeSp,
        fontWeight,
        density.density,
        density.fontScale,
        runtimeVersion
    ) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color.toArgb()
            textSize = with(density) { fontSizeSp.sp.toPx() }
            typeface = FontManager.resolveTypeface(fontWeight.weight)
        }
    }
    val textWidth = remember(text, paint.textSize, paint.typeface) { paint.measureText(text) }
    val cumulativeCharacterWidths = remember(text, paint.textSize, paint.typeface) {
        val characterWidths = FloatArray(text.length)
        if (text.isNotEmpty()) paint.getTextWidths(text, characterWidths)
        FloatArray(text.length + 1).also { cumulative ->
            characterWidths.forEachIndexed { index, width ->
                cumulative[index + 1] = cumulative[index] + width
            }
        }
    }
    val highlightPaint = remember(paint, highlightColor) {
        Paint(paint).apply { this.color = highlightColor.toArgb() }
    }
    val rainbowShader = remember(rainbowGradient, textWidth) {
        if (!rainbowGradient || textWidth <= 0f) {
            null
        } else {
            LinearGradient(
                0f,
                0f,
                textWidth,
                0f,
                LyricRainbowColors.map { it.toArgb() }.toIntArray(),
                null,
                Shader.TileMode.CLAMP,
            )
        }
    }
    val rainbowMatrix = remember { Matrix() }
    // A draw modifier sitting outside Canvas only invalidates its own display-list node; the
    // Canvas draw lambda can still be replayed from cache and therefore sample karaoke progress at
    // a much lower cadence. Publish the render clock into Snapshot state that is read *inside* the
    // Canvas draw scope. Snapshot observes that read as a draw dependency, so every Choreographer
    // callback invalidates this Canvas directly without recomposing the MiniPlayer subtree.
    val drawClockPosition = remember(drawClock) {
        mutableFloatStateOf(drawClock?.currentPosition() ?: 0f)
    }
    DisposableEffect(drawClock) {
        val clock = drawClock
        if (clock == null) {
            onDispose { }
        } else {
            drawClockPosition.floatValue = clock.currentPosition()
            val listener = LyricDirectFrameListener { positionMs ->
                drawClockPosition.floatValue = positionMs
            }
            clock.addListener(listener)
            onDispose { clock.removeListener(listener) }
        }
    }
    val textInkBounds = remember(text, paint.textSize, paint.typeface) {
        AndroidRect().also { bounds ->
            if (text.isNotEmpty()) paint.getTextBounds(text, 0, text.length, bounds)
        }
    }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val overflowPx = (textWidth - measuredWidth).coerceAtLeast(0f)
    val marqueeEnabled =
        frameAnimationActive && visible && LongTextMotionState.enabled &&
            LongTextMotionState.enabledEverywhere && overflowPx > 0.5f
    DisposableEffect(marqueeEnabled) {
        if (marqueeEnabled) LongTextMotionState.acquireMarquee()
        onDispose {
            if (marqueeEnabled) LongTextMotionState.releaseMarquee()
        }
    }
    val marqueeElapsedMs = if (marqueeEnabled) {
        LongTextMotionState.marqueeElapsedMs
    } else {
        0L
    }
    val marqueeOffset = LongTextMotionState.marqueeOffset(
        elapsedMs = marqueeElapsedMs,
        overflowPx = overflowPx,
        speedPxPerSecond = with(density) { 42.5.dp.toPx() },
        enabled = marqueeEnabled
    )

    val featherLayer = if (edgeFeather > 0.dp) {
        Modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { measuredWidth = it.width.toFloat() }
            .then(featherLayer)
    ) {
        if (drawClock != null) {
            // Register this Canvas draw node as the Snapshot observer for the frame signal.
            drawClockPosition.floatValue
        }
        // A newly composed track-transition lane can draw before the onSizeChanged state commit is
        // observed by composition. Use the DrawScope width immediately so the first frame already
        // contains text; marquee measurement can catch up on the following snapshot without a flash.
        val drawWidth = measuredWidth.takeIf { it > 0f } ?: size.width
        if (drawWidth <= 0f || text.isEmpty()) return@Canvas
        val fontMetrics = paint.fontMetrics
        val verticalLayout = resolveNativeTextVerticalLayout(
            containerTop = 0f,
            containerBottom = size.height,
            canvasTop = 0f,
            canvasBottom = size.height,
            ascent = fontMetrics.ascent,
            descent = fontMetrics.descent,
            fontTop = fontMetrics.top,
            fontBottom = fontMetrics.bottom,
            inkTop = textInkBounds.top.toFloat(),
            inkBottom = textInkBounds.bottom.toFloat(),
        )
        val baseline = verticalLayout.baseline
        val staticStartX = when (textAlign) {
            TextAlign.Center -> ((drawWidth - textWidth) * 0.5f).coerceAtLeast(0f)
            TextAlign.End, TextAlign.Right -> (drawWidth - textWidth).coerceAtLeast(0f)
            else -> 0f
        }
        val drawX = if (marqueeEnabled) -marqueeOffset else staticStartX
        val resolvedBaseColor = colorProvider?.invoke() ?: color
        val resolvedHighlightColor = highlightColorProvider?.invoke() ?: highlightColor
        if (rainbowShader != null) {
            rainbowMatrix.setTranslate(drawX, 0f)
            rainbowShader.setLocalMatrix(rainbowMatrix)
            paint.shader = rainbowShader
            highlightPaint.shader = rainbowShader
            paint.alpha = (resolvedBaseColor.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            highlightPaint.alpha =
                (resolvedHighlightColor.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        } else {
            paint.shader = null
            highlightPaint.shader = null
            paint.alpha = 255
            highlightPaint.alpha = 255
            paint.color = resolvedBaseColor.toArgb()
            highlightPaint.color = resolvedHighlightColor.toArgb()
        }
        clipRect(
            left = 0f,
            top = verticalLayout.clipTop,
            right = drawWidth,
            bottom = verticalLayout.clipBottom,
        ) {
            drawIntoNativeText(text, paint, Offset(drawX, baseline))
            val highlightCharacterPosition = highlightCharacterPositionProvider?.invoke()
                ?: highlightProgress?.let { progress -> progress.coerceIn(0f, 1f) * text.length }
            highlightCharacterPosition?.let { rawCharacterPosition ->
                val characterPosition = rawCharacterPosition.coerceIn(0f, text.length.toFloat())
                if (characterPosition > 0f && textWidth > 0f) {
                    val characterIndex = characterPosition.toInt().coerceIn(0, text.length)
                    val characterFraction = (characterPosition - characterIndex.toFloat()).coerceIn(0f, 1f)
                    val highlightWidth = if (characterIndex >= text.length) {
                        textWidth
                    } else {
                        cumulativeCharacterWidths[characterIndex] +
                            (cumulativeCharacterWidths[characterIndex + 1] -
                                cumulativeCharacterWidths[characterIndex]) * characterFraction
                    }.coerceIn(0f, textWidth)
                    val textLeft = drawX
                    val textRight = drawX + textWidth
                    val highlightLeft = if (highlightFromEnd) {
                        textRight - highlightWidth
                    } else {
                        textLeft
                    }
                    val highlightRight = if (highlightFromEnd) {
                        textRight
                    } else {
                        textLeft + highlightWidth
                    }
                    clipRect(
                        left = highlightLeft,
                        top = verticalLayout.clipTop,
                        right = highlightRight,
                        bottom = verticalLayout.clipBottom,
                    ) {
                        drawIntoNativeText(text, highlightPaint, Offset(drawX, baseline))
                    }
                }
            }
        }

        if (edgeFeather > 0.dp && textWidth > drawWidth + 0.5f) {
            val featherPx = edgeFeather.toPx().coerceAtMost(drawWidth * 0.22f)
            if (featherPx > 0.5f && drawWidth > 1f) {
                val featherFraction = (featherPx / drawWidth).coerceIn(0f, 0.22f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            featherFraction to Color.White,
                            (1f - featherFraction) to Color.White,
                            1f to Color.Transparent,
                        ),
                        startX = 0f,
                        endX = drawWidth,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

private fun DrawScope.drawIntoNativeText(text: String, paint: Paint, offset: Offset) {
    drawContext.canvas.nativeCanvas.drawText(text, offset.x, offset.y, paint)
}
