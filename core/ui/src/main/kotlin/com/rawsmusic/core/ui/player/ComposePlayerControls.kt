package com.rawsmusic.core.ui.widget.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ComposePlayerControls(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xCCFFFFFF),
    iconColor: Color = Color.White,
    playIconTint: Color = Color.White,
    @DrawableRes previousIconRes: Int,
    @DrawableRes playIconRes: Int,
    @DrawableRes pauseIconRes: Int,
    @DrawableRes nextIconRes: Int,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerProgressBar(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            textColor = textColor,
            trackColor = Color.White.copy(alpha = 0.16f),
            progressColor = Color.White.copy(alpha = 0.88f),
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerIconButton(
                iconRes = previousIconRes,
                tint = iconColor,
                size = 48.dp,
                iconSize = 40.dp,
                onClick = onPrevious
            )
            Spacer(modifier = Modifier.width(16.dp))
            PlayerIconButton(
                iconRes = if (isPlaying) pauseIconRes else playIconRes,
                tint = playIconTint,
                size = 52.dp,
                iconSize = 38.dp,
                onClick = onPlayPause
            )
            Spacer(modifier = Modifier.width(16.dp))
            PlayerIconButton(
                iconRes = nextIconRes,
                tint = iconColor,
                size = 48.dp,
                iconSize = 40.dp,
                onClick = onNext
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    @DrawableRes iconRes: Int,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(size)
            .scale(if (pressed) 0.92f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun ComposePlayerSeekBar(
    progress: Float,
    isPlaying: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekStop: (Float) -> Unit
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "seek-breath")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "seek-breath-alpha"
    )
    val intensity = when {
        isDragging -> 1.2f
        !isPlaying -> 0.8f + 0.2f * breath
        else -> 1f
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(size) {
                fun fractionFor(x: Float): Float {
                    val width = size.width.toFloat()
                    if (width <= 0f) return 0f
                    val track = seekTrackBounds(width, density.density)
                    return ((x - track.first) / (track.second - track.first)).coerceIn(0f, 1f)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main
                    )
                    var fraction = fractionFor(down.position.x)
                    if (size.width > 0) {
                        onSeekStart()
                        onSeek(fraction)
                        down.consume()
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        fraction = fractionFor(change.position.x)
                        if (change.pressed) {
                            onSeek(fraction)
                            change.consume()
                        } else {
                            change.consume()
                            break
                        }
                    }
                    if (size.width > 0) {
                        onSeekStop(fraction)
                    }
                }
            }
    ) {
        drawSeekBar(progress.coerceIn(0f, 1f), intensity)
    }
}

private fun DrawScope.drawSeekBar(progress: Float, intensity: Float) {
    val trackHeight = 4f * density
    val capRadius = trackHeight / 2f
    val headExtraHeight = 1f * density
    val headBlurRadius = trackHeight * 2f
    val (trackLeft, trackRight) = seekTrackBounds(size.width, density)
    val trackWidth = trackRight - trackLeft
    if (trackWidth <= 0f) return

    val cy = size.height / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.095f),
        topLeft = Offset(trackLeft - capRadius, cy - trackHeight / 2f),
        size = Size(trackWidth + capRadius * 2f, trackHeight),
        cornerRadius = CornerRadius(capRadius, capRadius)
    )

    val progressRight = trackLeft + progress * trackWidth
    if (progressRight - trackLeft < 1f) return

    drawGradientGlow(
        trackLeft = trackLeft,
        progressRight = progressRight,
        cy = cy,
        trackHeight = trackHeight,
        capRadius = capRadius,
        headBlurRadius = headBlurRadius,
        intensity = intensity
    )

    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFC8C8C8), Color.White),
            startX = trackLeft - capRadius,
            endX = progressRight + capRadius
        ),
        topLeft = Offset(trackLeft - capRadius, cy - trackHeight / 2f),
        size = Size(progressRight - trackLeft + capRadius * 2f, trackHeight),
        cornerRadius = CornerRadius(capRadius, capRadius),
        alpha = intensity.coerceIn(0f, 1.2f)
    )

    drawSmoothHead(
        x = progressRight,
        cy = cy,
        trackHeight = trackHeight,
        capRadius = capRadius,
        headExtraHeight = headExtraHeight,
        headBlurRadius = headBlurRadius,
        intensity = intensity
    )
}

private fun DrawScope.drawGradientGlow(
    trackLeft: Float,
    progressRight: Float,
    cy: Float,
    trackHeight: Float,
    capRadius: Float,
    headBlurRadius: Float,
    intensity: Float
) {
    val glowCoverage = 0.108f
    val glowLeft = (progressRight - (progressRight - trackLeft) * glowCoverage).coerceAtLeast(trackLeft)
    if (glowLeft >= progressRight) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                glowLeft,
                0f,
                progressRight,
                0f,
                intArrayOf(android.graphics.Color.argb(0, 255, 255, 255), android.graphics.Color.argb(140, 255, 255, 255)),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            maskFilter = android.graphics.BlurMaskFilter(headBlurRadius * 0.8f * intensity, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = (200 * intensity).roundToInt().coerceIn(0, 255)
            style = android.graphics.Paint.Style.FILL
        }
        val rect = android.graphics.RectF(
            glowLeft - capRadius,
            cy - trackHeight / 2f,
            progressRight + capRadius,
            cy + trackHeight / 2f
        )
        canvas.nativeCanvas.drawRoundRect(rect, capRadius, capRadius, paint)
    }
}

private fun DrawScope.drawSmoothHead(
    x: Float,
    cy: Float,
    trackHeight: Float,
    capRadius: Float,
    headExtraHeight: Float,
    headBlurRadius: Float,
    intensity: Float
) {
    val headWidth = trackHeight * 2.5f
    val headHeight = trackHeight + headExtraHeight * 2f
    drawIntoCanvas { canvas ->
        val rect = android.graphics.RectF(
            x - headWidth,
            cy - headHeight / 2f,
            x + capRadius,
            cy + headHeight / 2f
        )
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                rect.left,
                0f,
                rect.right,
                0f,
                intArrayOf(android.graphics.Color.argb(0, 255, 255, 255), android.graphics.Color.WHITE),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            maskFilter = android.graphics.BlurMaskFilter(headBlurRadius * 0.6f * intensity, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = (220 * intensity).roundToInt().coerceIn(0, 255)
            style = android.graphics.Paint.Style.FILL
        }
        canvas.nativeCanvas.drawRoundRect(rect, capRadius, capRadius, paint)
    }
}

private fun seekTrackBounds(width: Float, density: Float): Pair<Float, Float> {
    val trackHeight = 4f * density
    val capRadius = trackHeight / 2f
    val headExtraHeight = 1f * density
    val headBlurRadius = trackHeight * 2f
    val padding = capRadius + headBlurRadius + headExtraHeight
    val left = 8f * density + padding
    val right = width - 8f * density - padding
    return left to right.coerceAtLeast(left + 1f)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun PlayerProgressBar(
    currentPositionMs: Long,
    totalDurationMs: Long,
    textColor: Color,
    trackColor: Color,
    progressColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val realFraction = if (totalDurationMs > 0L) {
        currentPositionMs.toFloat() / totalDurationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)

    val displayFraction = if (isDragging) dragFraction else realFraction
    val displayPositionMs = if (isDragging && totalDurationMs > 0L) {
        (displayFraction * totalDurationMs).toLong()
    } else {
        currentPositionMs
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(totalDurationMs, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main
                        )
                        if (totalDurationMs <= 0L || widthPx <= 1) {
                            down.consume()
                            return@awaitEachGesture
                        }
                        var lastFraction = (down.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        isDragging = true
                        dragFraction = lastFraction
                        onSeekStart()
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                lastFraction = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                                dragFraction = lastFraction
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            isDragging = false
                            onSeekStop(lastFraction)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(progressColor)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(displayPositionMs), color = textColor.copy(alpha = 0.54f), fontSize = 14.sp)
            Text(formatDuration(totalDurationMs), color = textColor.copy(alpha = 0.54f), fontSize = 14.sp)
        }
    }
}
