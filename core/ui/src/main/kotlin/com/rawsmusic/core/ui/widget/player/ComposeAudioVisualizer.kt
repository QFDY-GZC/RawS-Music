package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MONO_BAND_COUNT = 112

private class DisplaySpectrumMotion {
    val levels = FloatArray(MONO_BAND_COUNT)

    private val targets = FloatArray(MONO_BAND_COUNT)
    private val spatialScratch = FloatArray(MONO_BAND_COUNT)

    fun updateTarget(source: FloatArray, enabled: Boolean) {
        val count = if (enabled) min(source.size, targets.size) else 0
        for (index in targets.indices) {
            val raw = if (index < count) source[index].coerceIn(0f, 1f) else 0f
            // Keep the display stable between adjacent logarithmic bins without hiding attacks.
            val previous = if (index > 0 && index - 1 < count) source[index - 1].coerceIn(0f, 1f) else raw
            val next = if (index + 1 < count) source[index + 1].coerceIn(0f, 1f) else raw
            spatialScratch[index] = (raw * 0.78f + previous * 0.11f + next * 0.11f)
                .coerceIn(0f, 1f)
        }
        spatialScratch.copyInto(targets)
    }

    fun advance(deltaSeconds: Float, playing: Boolean) {
        val dt = deltaSeconds.coerceIn(1f / 240f, 1f / 20f)
        val attack = 1f - exp((-dt * 62f).toDouble()).toFloat()
        val releaseRate = if (playing) 28f else 8f
        val release = 1f - exp((-dt * releaseRate).toDouble()).toFloat()

        for (index in levels.indices) {
            val target = targets[index]
            val level = levels[index]
            val response = if (target > level) attack else release
            levels[index] = (level + (target - level) * response).coerceIn(0f, 1f)
        }
    }

    fun hasVisibleEnergy(): Boolean = levels.any { it > 0.002f }
}

enum class AudioVisualizerLayer {
    BehindArtwork,
    Foreground
}

/**
 * Mirrored inverted FFT presentation.
 *
 * Native input is a single mono FFT. The same logarithmic spectrum is mirrored from the centre
 * toward both sides and each column expands above and below one horizontal axis. Rendering follows
 * the display Choreographer, so 90/120 Hz devices interpolate smoothly between native snapshots.
 */
@Composable
fun AlbumArtworkSpectrumOverlay(
    spectrum: FloatArray,
    visible: Boolean,
    isPlaying: Boolean,
    layer: AudioVisualizerLayer,
    showControls: Boolean = false,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 260 else 170,
            easing = FastOutSlowInEasing
        ),
        label = "album-spectrum-layer"
    )
    val motion = remember { DisplaySpectrumMotion() }
    val latestPlaying by rememberUpdatedState(isPlaying)
    val latestVisible by rememberUpdatedState(visible)
    var frameSerial by remember { mutableStateOf(0L) }

    SideEffect {
        motion.updateTarget(spectrum, enabled = visible)
    }

    LaunchedEffect(visible) {
        var previousFrameNs = 0L
        while (isActive) {
            withFrameNanos { frameNs ->
                val deltaSeconds = if (previousFrameNs == 0L) {
                    1f / 60f
                } else {
                    ((frameNs - previousFrameNs) / 1_000_000_000f)
                        .coerceIn(1f / 240f, 1f / 20f)
                }
                previousFrameNs = frameNs
                motion.advance(deltaSeconds, latestPlaying)
                frameSerial++
            }
            if (!latestVisible && !motion.hasVisibleEnergy()) break
        }
    }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy()) return

    Box(
        modifier = modifier.graphicsLayer {
            alpha = reveal
        }
    ) {
        val drawFrameSerial = frameSerial
        Canvas(modifier = Modifier.matchParentSize()) {
            @Suppress("UNUSED_VARIABLE")
            val frameInvalidationToken = drawFrameSerial
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            val foreground = layer == AudioVisualizerLayer.Foreground

            val widthDp = size.width / density
            val barCount = (widthDp / if (foreground) 3.2f else 3.0f)
                .roundToInt()
                .coerceIn(72, MONO_BAND_COUNT)
            val horizontalInset = 0f
            val availableWidth = size.width
            if (availableWidth <= 0f) return@Canvas

            val slotWidth = availableWidth / barCount
            val barWidth = (slotWidth * if (foreground) 0.34f else 0.40f)
                .coerceIn(0.55f * density, 1.15f * density)
            val haloWidth = (barWidth * 1.32f).coerceAtMost(slotWidth * 0.58f)
            val coreRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
            val haloRadius = CornerRadius(haloWidth * 0.5f, haloWidth * 0.5f)
            val axisY = size.height * if (foreground) 0.535f else 0.555f
            val maximumHalfHeight = size.height * if (foreground) 0.465f else 0.42f
            val minimumHalfHeight = (if (foreground) 1.0f else 1.35f) * density
            val layerAlpha = if (foreground) 0.96f else 0.72f
            val pausedAlpha = if (isPlaying) 1f else 0.86f
            val totalAlpha = layerAlpha * pausedAlpha

            val coreBrush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.70f * totalAlpha),
                0.37f to Color.White.copy(alpha = 0.88f * totalAlpha),
                0.50f to Color.White.copy(alpha = 0.98f * totalAlpha),
                0.63f to Color.White.copy(alpha = 0.88f * totalAlpha),
                1.00f to Color.White.copy(alpha = 0.70f * totalAlpha),
                startY = axisY - maximumHalfHeight,
                endY = axisY + maximumHalfHeight
            )
            val haloColor = Color.White.copy(alpha = 0.07f * totalAlpha)

            fun sampledValue(displayBand: Int): Float {
                val sourceStart = floor(displayBand * MONO_BAND_COUNT.toFloat() / barCount)
                    .toInt()
                    .coerceIn(0, MONO_BAND_COUNT - 1)
                val sourceEnd = ceil((displayBand + 1) * MONO_BAND_COUNT.toFloat() / barCount)
                    .toInt()
                    .coerceIn(sourceStart + 1, MONO_BAND_COUNT)
                var maximum = 0f
                var squareSum = 0f
                var count = 0
                for (sourceBand in sourceStart until sourceEnd) {
                    val value = motion.levels[sourceBand].coerceIn(0f, 1f)
                    maximum = max(maximum, value)
                    squareSum += value * value
                    count++
                }
                val rms = if (count > 0) sqrt(squareSum / count) else 0f
                return (maximum * 0.76f + rms * 0.24f).coerceIn(0f, 1f)
            }

            fun drawSpectrumBar(x: Float, level: Float) {
                val gated = ((level - 0.006f) / 0.994f).coerceIn(0f, 1f)
                val shaped = gated.pow(0.96f)
                val halfHeight = minimumHalfHeight + maximumHalfHeight * shaped
                val top = axisY - halfHeight
                val fullHeight = halfHeight * 2f

                if (shaped > 0.01f) {
                    drawRoundRect(
                        color = haloColor,
                        topLeft = Offset(x - haloWidth * 0.5f, top - 0.5f * density),
                        size = Size(haloWidth, fullHeight + density),
                        cornerRadius = haloRadius
                    )
                }
                drawRoundRect(
                    brush = coreBrush,
                    topLeft = Offset(x - barWidth * 0.5f, top),
                    size = Size(barWidth, fullHeight),
                    cornerRadius = coreRadius
                )
            }

            // Native bands are already ordered logarithmically from 25 Hz to 20 kHz.
            for (band in 0 until barCount) {
                val level = sampledValue(band)
                val x = (band + 0.5f) * slotWidth
                drawSpectrumBar(x, level)
            }

            drawLine(
                color = Color.White.copy(alpha = if (foreground) 0.16f else 0.09f),
                start = Offset(horizontalInset, axisY),
                end = Offset(size.width - horizontalInset, axisY),
                strokeWidth = 0.7f * density,
                cap = StrokeCap.Round
            )
        }

        if (showControls && layer == AudioVisualizerLayer.Foreground && visible) {
            AudioVisualizerLockControls(
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            )
        }
    }
}

/** Animated open/closed lock used by the player menu and the foreground overlay. */
@Composable
fun AudioVisualizerToggleGlyph(
    locked: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    animateOnEnter: Boolean = false
) {
    val entered = remember { Animatable(if (animateOnEnter) 0f else if (locked) 1f else 0f) }
    LaunchedEffect(locked, animateOnEnter) {
        val target = if (locked) 1f else 0f
        if (animateOnEnter && locked && entered.value == 0f) {
            entered.animateTo(target, tween(430, easing = FastOutSlowInEasing))
        } else {
            entered.animateTo(target, tween(330, easing = FastOutSlowInEasing))
        }
    }
    val progress = entered.value.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stroke = max(1.7f * density, width * 0.075f)
        val bodyTop = height * 0.47f
        val bodyLeft = width * 0.18f
        val bodyWidth = width * 0.64f
        val bodyHeight = height * 0.40f

        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(width * 0.12f, width * 0.12f)
        )

        // Three tiny spectrum columns keep the lock recognisable as the visualizer switch.
        val innerColor = Color.Black.copy(alpha = if (tint.luminanceCompat() > 0.55f) 0.56f else 0.32f)
        val columnWidth = width * 0.065f
        val gap = width * 0.055f
        val centerX = width * 0.5f
        val columnBottom = bodyTop + bodyHeight * 0.72f
        val heights = floatArrayOf(0.18f, 0.31f, 0.23f)
        for (index in heights.indices) {
            val x = centerX + (index - 1) * (columnWidth + gap) - columnWidth * 0.5f
            val columnHeight = bodyHeight * heights[index] * (0.72f + 0.28f * progress)
            drawRoundRect(
                color = innerColor,
                topLeft = Offset(x, columnBottom - columnHeight),
                size = Size(columnWidth, columnHeight),
                cornerRadius = CornerRadius(columnWidth * 0.5f, columnWidth * 0.5f)
            )
        }

        val shackleLeft = width * 0.31f
        val shackleTop = height * 0.14f
        val shackleSize = width * 0.38f
        val unlockRotation = -34f * (1f - progress)
        val unlockShiftX = -width * 0.055f * (1f - progress)
        val unlockShiftY = -height * 0.045f * (1f - progress)

        withTransform({
            rotate(
                degrees = unlockRotation,
                pivot = Offset(width * 0.67f, bodyTop + stroke * 0.5f)
            )
            translate(left = unlockShiftX, top = unlockShiftY)
        }) {
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(shackleLeft, shackleTop),
                size = Size(shackleSize, shackleSize),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawLine(
                color = tint,
                start = Offset(shackleLeft, shackleTop + shackleSize * 0.5f),
                end = Offset(shackleLeft, bodyTop + stroke * 0.2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(shackleLeft + shackleSize, shackleTop + shackleSize * 0.5f),
                end = Offset(shackleLeft + shackleSize, bodyTop + stroke * 0.2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun AudioVisualizerLockControls(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val eyePulse = remember { Animatable(0f) }
    val lockPulse = remember { Animatable(0f) }
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        eyePulse.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        eyePulse.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        delay(90)
        lockPulse.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        lockPulse.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        delay(1900)
        showHint = false
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(280))
        ) {
            Text(
                text = stringResource(R.string.audio_visualizer_controls_hint),
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f * eyePulse.value))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_audio_visualizer_visibility),
                contentDescription = stringResource(R.string.audio_visualizer_close),
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.96f)),
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f * lockPulse.value)),
            contentAlignment = Alignment.Center
        ) {
            AudioVisualizerToggleGlyph(
                locked = true,
                tint = Color.White.copy(alpha = 0.94f),
                animateOnEnter = true,
                modifier = Modifier.size(20.dp)
            )
        }
        }
    }
}

private fun Color.luminanceCompat(): Float {
    fun channel(value: Float): Float = if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return channel(red) * 0.2126f + channel(green) * 0.7152f + channel(blue) * 0.0722f
}
