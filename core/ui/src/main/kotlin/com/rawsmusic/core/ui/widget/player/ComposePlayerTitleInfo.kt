package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Dp
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.core.ui.widget.text.createExplicitBadgeLabelPaint
import com.rawsmusic.core.ui.widget.text.drawExplicitTitleBadge
import com.rawsmusic.core.ui.widget.text.explicitBadgeGap
import com.rawsmusic.core.ui.widget.text.explicitBadgeWidth
import com.rawsmusic.core.ui.widget.text.resolveNativeTextVerticalLayout
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListMarqueeMotion

private data class PlayerTitleFrame(val title: String, val artist: String, val album: String, val explicit: Boolean)

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
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongPressGestureActiveChange: (Boolean) -> Unit = {},
    marqueeVisible: Boolean = true,
    explicitBadge: Boolean = false,
    trackKey: Any? = listOf(title, artist, album),
    contentCrossfadeEnabled: Boolean = true,
) {
    val currentOnLongClick = rememberUpdatedState(onLongClick)
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnLongPressGestureActiveChange =
        rememberUpdatedState(onLongPressGestureActiveChange)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable { currentOnClick.value?.invoke() }
                } else Modifier
            )
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
        PlayerContentCrossfade(key = trackKey,
            enabled = contentCrossfadeEnabled,
            value = PlayerTitleFrame(title, artist, album, explicitBadge), modifier = Modifier.fillMaxWidth()) { info ->
        Column(Modifier.fillMaxWidth()) {
        MarqueeCanvasText(
            text = info.title,
            explicitBadge = info.explicit,
            color = titleColor,
            fontSizeSp = 20f,
            fontWeight = FontWeight.Bold,
            endPaddingDp = endPaddingDp,
            textPosition = textPosition,
            marqueeVisible = marqueeVisible,
            minimumHeight = 28.dp,
            modifier = Modifier
                .fillMaxWidth()
        )
        val secondary = listOf(info.artist, info.album)
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
            marqueeVisible = marqueeVisible,
            minimumHeight = 19.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 1.dp)
        )
        }
        }
    }
}

@Composable
private fun MarqueeCanvasText(
    text: String,
    explicitBadge: Boolean = false,
    color: Color,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    endPaddingDp: Float = 0f,
    textPosition: LyricTextPosition = LyricTextPosition.Left,
    marqueeVisible: Boolean = true,
    minimumHeight: Dp = 28.dp,
) {
    val density = LocalDensity.current
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val fontRuntimeVersion = RawThemeRuntimeState.version
    val paint = remember(text, color, fontSizeSp, fontWeight, density.density, density.fontScale, fontRuntimeVersion) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = with(density) { fontSizeSp.sp.toPx() }
            typeface = FontManager.resolveTypeface(fontWeight.weight)
        }
    }
    val textWidth = remember(text, paint.textSize, fontWeight, paint.typeface) {
        paint.measureText(text)
    }
    val completeWidth = textWidth + if (explicitBadge) {
        explicitBadgeGap(paint.textSize) + explicitBadgeWidth(paint.textSize)
    } else 0f
    val explicitLabelPaint = remember(explicitBadge, paint.textSize) {
        if (explicitBadge) createExplicitBadgeLabelPaint(paint.textSize) else null
    }
    val fontMetrics = remember(paint) { paint.fontMetrics }
    val laneHeight = with(density) {
        playerTitleLineHeightPx(minimumHeight.toPx(), fontMetrics.top, fontMetrics.bottom).toDp()
    }
    val marqueeMotion = remember(text, paint.textSize, fontWeight, paint.typeface) {
        VirtualListMarqueeMotion()
    }
    val speed = with(density) { 42.5.dp.toPx() }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val availableWidth = (measuredWidth - endPaddingDp * density.density).coerceAtLeast(0f)
    val overflowWidth = (completeWidth - availableWidth).coerceAtLeast(0f)
    // Visible text keeps its own phase while its physical artwork holder moves or is promoted.
    // Parked holders do not register for frames. The existing frame driver remains shared.
    val marqueeEnabled = frameAnimationActive && marqueeVisible && LongTextMotionState.enabled &&
        LongTextMotionState.enabledEverywhere && overflowWidth > 10f
    DisposableEffect(marqueeEnabled, marqueeMotion) {
        if (!marqueeEnabled) marqueeMotion.hide()
        if (marqueeEnabled) LongTextMotionState.acquireMarquee()
        onDispose {
            if (marqueeEnabled) LongTextMotionState.releaseMarquee()
        }
    }
    // Read the shared marquee clock only from draw phase. Reading marqueeElapsedMs in composition
    // made every visible player title recompose on each frame, exactly while ArtworkPager-style card motion
    // was trying to update its RenderNode.
    fun currentMarqueeOffset(): Float = marqueeMotion.update(
        now = LongTextMotionState.marqueeElapsedMs,
        distance = overflowWidth,
        pixelsPerSecond = speed,
        enabled = marqueeEnabled,
        visible = marqueeEnabled,
        layoutChanged = false,
        preserveProgress = true,
    )

    Canvas(
        modifier = modifier
            .height(laneHeight)
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
        val fm = fontMetrics
        val verticalLayout = resolveNativeTextVerticalLayout(
            containerTop = 0f,
            containerBottom = size.height,
            canvasTop = 0f,
            canvasBottom = size.height,
            ascent = fm.ascent,
            descent = fm.descent,
            fontTop = fm.top,
            fontBottom = fm.bottom,
            inkTop = fm.top,
            inkBottom = fm.bottom,
        )
        val baseline = verticalLayout.baseline
        val staticStartX = when (textPosition) {
            LyricTextPosition.Left -> 0f
            LyricTextPosition.Center -> ((availableWidth - completeWidth) * 0.5f).coerceAtLeast(0f)
            LyricTextPosition.Right -> (availableWidth - completeWidth).coerceAtLeast(0f)
        }
        val drawX = if (marqueeEnabled) -currentMarqueeOffset() else staticStartX
        clipRect(
            left = 0f,
            top = verticalLayout.clipTop,
            right = availableWidth,
            bottom = verticalLayout.clipBottom,
        ) {
            drawIntoNativeText(text, paint, Offset(drawX, baseline))
            if (explicitBadge && explicitLabelPaint != null) {
                drawExplicitTitleBadge(
                    left = drawX + textWidth + explicitBadgeGap(paint.textSize),
                    titleBaseline = baseline,
                    fontSizePx = paint.textSize,
                    labelPaint = explicitLabelPaint,
                )
            }
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
