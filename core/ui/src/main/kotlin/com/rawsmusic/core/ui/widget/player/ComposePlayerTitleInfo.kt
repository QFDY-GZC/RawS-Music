package com.rawsmusic.core.ui.widget.player

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.core.ui.widget.text.resolveNativeTextVerticalLayout

@Composable
fun ComposePlayerTitleInfo(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    artistColor: Color = Color(0xCCFFFFFF),
    albumColor: Color = Color(0x99FFFFFF),
    endPaddingDp: Float = 0f,
    textPosition: LyricTextPosition = LyricTextPosition.Left,
    onLongClick: (() -> Unit)? = null,
    onLongPressGestureActiveChange: (Boolean) -> Unit = {},
    motionPaused: Boolean = false,
) {
    val currentOnLongClick = rememberUpdatedState(onLongClick)
    val currentOnLongPressGestureActiveChange =
        rememberUpdatedState(onLongPressGestureActiveChange)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.pointerInput(Unit) {
                        // A long press is a complete gesture. Once it fires, consume the
                        // remaining drag until UP/CANCEL so the parent player-page swipe cannot
                        // recreate transformed title nodes and retrigger clipboard feedback.
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // Lift the lock to the player container before copying. The parent
                                // title pager listens with requireUnconsumed=false, so consuming only
                                // in this child is not sufficient to suppress its vertical gesture.
                                currentOnLongPressGestureActiveChange.value(true)
                                currentOnLongClick.value?.invoke()
                            },
                            onDragEnd = {
                                currentOnLongPressGestureActiveChange.value(false)
                            },
                            onDragCancel = {
                                currentOnLongPressGestureActiveChange.value(false)
                            },
                            onDrag = { change, _ -> change.consume() },
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        MarqueeCanvasText(
            text = title,
            color = titleColor,
            fontSizeSp = 20f,
            fontWeight = FontWeight.Bold,
            endPaddingDp = endPaddingDp,
            textPosition = textPosition,
            motionPaused = motionPaused,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )
        val secondary = listOf(artist, album)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" / ")
        MarqueeCanvasText(
            text = secondary,
            color = artistColor,
            fontSizeSp = 13f,
            endPaddingDp = endPaddingDp,
            textPosition = textPosition,
            motionPaused = motionPaused,
            modifier = Modifier
                .fillMaxWidth()
                .height(19.dp)
                .padding(top = 1.dp)
        )
    }
}

@Composable
private fun MarqueeCanvasText(
    text: String,
    color: Color,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    endPaddingDp: Float = 0f,
    textPosition: LyricTextPosition = LyricTextPosition.Left,
    motionPaused: Boolean = false,
) {
    val density = LocalDensity.current
    val fontRuntimeVersion = RawThemeRuntimeState.version
    val paint = remember(text, color, fontSizeSp, fontWeight, density.density, density.fontScale, fontRuntimeVersion) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = with(density) { fontSizeSp.sp.toPx() }
            val configuredTypeface = FontManager.typeface
            typeface = if (configuredTypeface != null) {
                android.graphics.Typeface.create(
                    configuredTypeface,
                    if (fontWeight >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            } else {
                android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    if (fontWeight >= FontWeight.Bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            }
        }
    }
    val textWidth = remember(text, paint.textSize, fontWeight, paint.typeface) {
        paint.measureText(text)
    }
    val textInkBounds = remember(text, paint.textSize, fontWeight, paint.typeface) {
        AndroidRect().also { bounds ->
            if (text.isNotEmpty()) paint.getTextBounds(text, 0, text.length, bounds)
        }
    }
    val speed = with(density) { 42.5.dp.toPx() }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val availableWidth = (measuredWidth - endPaddingDp * density.density).coerceAtLeast(0f)
    val overflowWidth = (textWidth - availableWidth).coerceAtLeast(0f)
    // Reference AAItemView keeps its FastTextViews inside the same physical holder and disables
    // hidden-holder marquee work when alpha reaches zero. Player AA motion also should not run a
    // second text animation clock under the card transform.
    val marqueeEnabled = !motionPaused && LongTextMotionState.enabled &&
        LongTextMotionState.enabledEverywhere && overflowWidth > 0.5f
    DisposableEffect(marqueeEnabled) {
        if (marqueeEnabled) LongTextMotionState.acquireMarquee()
        onDispose {
            if (marqueeEnabled) LongTextMotionState.releaseMarquee()
        }
    }
    // Read the shared marquee clock only from draw phase. Reading marqueeElapsedMs in composition
    // made every visible player title recompose on each frame, exactly while C0889-style card motion
    // was trying to update its RenderNode.
    fun currentMarqueeOffset(): Float = LongTextMotionState.marqueeOffset(
        elapsedMs = if (marqueeEnabled) LongTextMotionState.marqueeElapsedMs else 0L,
        overflowPx = overflowWidth,
        speedPxPerSecond = speed,
        enabled = marqueeEnabled
    )

    Canvas(
        modifier = modifier
            .onSizeChanged { measuredWidth = it.width.toFloat() }
            .then(
                if (marqueeEnabled) {
                    Modifier.playerTitleHorizontalEdgeFeather(
                        leftActive = { currentMarqueeOffset() > 0.5f },
                        rightActive = { currentMarqueeOffset() < (overflowWidth - 0.5f) },
                    )
                } else {
                    Modifier
                }
            )
    ) {
        if (availableWidth <= 0f) return@Canvas
        val fm = paint.fontMetrics
        val verticalLayout = resolveNativeTextVerticalLayout(
            containerTop = 0f,
            containerBottom = size.height,
            canvasTop = 0f,
            canvasBottom = size.height,
            ascent = fm.ascent,
            descent = fm.descent,
            fontTop = fm.top,
            fontBottom = fm.bottom,
            inkTop = textInkBounds.top.toFloat(),
            inkBottom = textInkBounds.bottom.toFloat(),
        )
        val baseline = verticalLayout.baseline
        val staticStartX = when (textPosition) {
            LyricTextPosition.Left -> 0f
            LyricTextPosition.Center -> ((availableWidth - textWidth) * 0.5f).coerceAtLeast(0f)
            LyricTextPosition.Right -> (availableWidth - textWidth).coerceAtLeast(0f)
        }
        val drawX = if (marqueeEnabled) -currentMarqueeOffset() else staticStartX
        clipRect(
            left = 0f,
            top = verticalLayout.clipTop,
            right = availableWidth,
            bottom = verticalLayout.clipBottom,
        ) {
            drawIntoNativeText(text, paint, Offset(drawX, baseline))
        }
    }
}

private fun Modifier.playerTitleHorizontalEdgeFeather(
    leftActive: () -> Boolean,
    rightActive: () -> Boolean,
    feather: androidx.compose.ui.unit.Dp = 14.dp,
    minimumEdgeAlpha: Float = 0.12f,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val fadeFraction = if (size.width > 0f) {
            (feather.toPx() / size.width).coerceIn(0f, 0.18f)
        } else {
            0f
        }
        val edgeAlpha = minimumEdgeAlpha.coerceIn(0f, 1f)

        onDrawWithContent {
            drawContent()
            if (fadeFraction <= 0f) return@onDrawWithContent

            val fadeLeft = leftActive()
            val fadeRight = rightActive()
            if (!fadeLeft && !fadeRight) return@onDrawWithContent

            val stops = when {
                fadeLeft && fadeRight -> arrayOf(
                    0f to Color.White.copy(alpha = edgeAlpha),
                    fadeFraction to Color.White,
                    (1f - fadeFraction).coerceAtLeast(fadeFraction) to Color.White,
                    1f to Color.White.copy(alpha = edgeAlpha),
                )
                fadeLeft -> arrayOf(
                    0f to Color.White.copy(alpha = edgeAlpha),
                    fadeFraction to Color.White,
                    1f to Color.White,
                )
                else -> arrayOf(
                    0f to Color.White,
                    (1f - fadeFraction).coerceAtLeast(0f) to Color.White,
                    1f to Color.White.copy(alpha = edgeAlpha),
                )
            }

            drawRect(
                brush = Brush.horizontalGradient(colorStops = stops),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoNativeText(
    text: String,
    paint: android.graphics.Paint,
    offset: Offset
) {
    drawContext.canvas.nativeCanvas.drawText(text, offset.x, offset.y, paint)
}
