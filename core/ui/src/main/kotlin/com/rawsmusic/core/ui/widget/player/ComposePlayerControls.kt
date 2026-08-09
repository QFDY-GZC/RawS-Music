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
