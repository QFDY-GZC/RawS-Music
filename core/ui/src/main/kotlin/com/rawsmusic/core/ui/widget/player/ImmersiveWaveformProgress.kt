package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

internal enum class ImmersiveProgressStyle(val value: Int) {
    Classic(0),
    Waveform(1),
    Seconds(2);

    companion object {
        fun from(value: Int): ImmersiveProgressStyle = entries.firstOrNull { it.value == value } ?: Classic
    }
}

/** Fixed-width timeline: one bar represents one second instead of compressing a whole track. */
@Composable
internal fun ImmersiveSecondProgressBar(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    colors: ImmersiveWaveformColors,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragSecond by remember { mutableFloatStateOf(0f) }
    val densityValue = LocalDensity.current.density
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val totalSeconds = (totalDurationMs / 1000f).coerceAtLeast(1f)
    val currentSecond = (currentPositionMs / 1000f).coerceIn(0f, totalSeconds)
    val songMotionKey = remember(currentSong?.path, currentSong?.fileSize, currentSong?.dateModified, totalDurationMs) {
        "${currentSong?.path}|${currentSong?.fileSize}|${currentSong?.dateModified}|$totalDurationMs"
    }
    var lastMotionKey by remember { mutableStateOf(songMotionKey) }
    var lastTargetSecond by remember { mutableFloatStateOf(currentSecond) }
    val shouldSnapSecond = lastMotionKey != songMotionKey || abs(currentSecond - lastTargetSecond) > 2.4f
    val settledSecond by animateFloatAsState(
        targetValue = currentSecond,
        animationSpec = tween(
            durationMillis = if (shouldSnapSecond) 0 else if (isPlaying) 520 else 180,
            easing = FastOutSlowInEasing
        ),
        label = "secondTimelineMotion"
    )
    SideEffect {
        lastMotionKey = songMotionKey
        lastTargetSecond = currentSecond
    }
    val pauseMorph by animateFloatAsState(
        targetValue = if (isPlaying || isDragging) 0f else 1f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "secondTimelinePauseMorph"
    )
    var pendingSeekSecond by remember(songMotionKey) { mutableStateOf<Float?>(null) }
    val seed = remember(currentSong?.path, currentSong?.fileSize, currentSong?.dateModified) {
        stableHash("${currentSong?.path}|${currentSong?.fileSize}|${currentSong?.dateModified}")
    }
    LaunchedEffect(pendingSeekSecond, currentSecond, songMotionKey) {
        val pending = pendingSeekSecond ?: return@LaunchedEffect
        if (abs(currentSecond - pending) <= 0.18f) {
            pendingSeekSecond = null
        } else {
            delay(520)
            if (pendingSeekSecond == pending) pendingSeekSecond = null
        }
    }
    var forceTrackStart by remember { mutableStateOf(false) }
    LaunchedEffect(songMotionKey) {
        pendingSeekSecond = null
        forceTrackStart = true
        delay(180)
        forceTrackStart = false
    }
    val displaySecond = if (isDragging) {
        dragSecond
    } else if (forceTrackStart) {
        0f
    } else {
        pendingSeekSecond ?: settledSecond
    }
    val latestDisplaySecond by rememberUpdatedState(displaySecond)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(totalDurationMs, widthPx, touchSlop, densityValue) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        if (totalDurationMs <= 0L || widthPx <= 1) return@awaitEachGesture
                        val barStep = 7.45f * densityValue
                        val startSecond = latestDisplaySecond
                        val startX = down.position.x
                        var lastSecond = startSecond
                        var started = false
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val deltaX = change.position.x - startX
                                if (!started && abs(deltaX) >= touchSlop) {
                                    started = true
                                    isDragging = true
                                    dragSecond = startSecond
                                    onSeekStart()
                                }
                                if (started) {
                                    lastSecond = (startSecond - deltaX / barStep).coerceIn(0f, totalSeconds)
                                    dragSecond = lastSecond
                                    change.consume()
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            if (started) {
                                pendingSeekSecond = lastSecond
                                isDragging = false
                                onSeekStop((lastSecond / totalSeconds).coerceIn(0f, 1f))
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            val step = (7.45f - pauseMorph * 1.25f) * density
            val barWidth = (4.9f - pauseMorph * 0.55f) * density
            val centerX = size.width * 0.5f
            val visible = (size.width / step).toInt() + 5
            val firstSecond = kotlin.math.floor(displaySecond).toInt() - visible / 2
            for (index in 0 until visible) {
                val second = firstSecond + index
                if (second !in 0..totalSeconds.toInt()) continue
                val x = centerX + (second - displaySecond) * step
                val amplitude = emphasizeWaveValue(secondPeak(seed, second))
                val liveHeight = (12.5f * density + amplitude * size.height * 0.83f).coerceAtMost(size.height * 0.96f)
                val idleHeight = (5.5f * density + amplitude * 7.0f * density).coerceAtMost(size.height * 0.26f)
                val height = liveHeight * (1f - pauseMorph) + idleHeight * pauseMorph
                val color = if (second < displaySecond) colors.played else colors.remaining
                val edgeAlpha = edgeFade(x = x, width = size.width, fade = step * 1.9f)
                val activeAlpha = if (isDragging || isPlaying) 1f else 0.68f
                val heightScale = 0.90f + 0.10f * edgeAlpha
                val easedHeight = (height * heightScale).coerceAtMost(size.height * 0.88f)
                val left = x - barWidth * 0.5f
                val right = x + barWidth * 0.5f
                val top = (size.height - easedHeight) * 0.5f
                val radius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
                val playedColor = colors.played.copy(alpha = colors.played.alpha * edgeAlpha * activeAlpha)
                val remainingColor = colors.remaining.copy(alpha = colors.remaining.alpha * edgeAlpha * activeAlpha)
                when {
                    centerX <= left -> {
                        drawRoundRect(
                            color = remainingColor,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, easedHeight),
                            cornerRadius = radius
                        )
                    }
                    centerX >= right -> {
                        drawRoundRect(
                            color = playedColor,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, easedHeight),
                            cornerRadius = radius
                        )
                    }
                    else -> {
                        clipRect(left = left, top = 0f, right = centerX, bottom = size.height) {
                            drawRoundRect(
                                color = playedColor,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, easedHeight),
                                cornerRadius = radius
                            )
                        }
                        clipRect(left = centerX, top = 0f, right = right, bottom = size.height) {
                            drawRoundRect(
                                color = remainingColor,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, easedHeight),
                                cornerRadius = radius
                            )
                        }
                    }
                }
            }
            val dragFocus = if (isDragging) 1f else 0f
            val needleWidth = (1.42f + dragFocus * 0.34f) * density
            val needleHeight = size.height * 1.34f
            val needleTop = (size.height - needleHeight) * 0.5f
            drawRoundRect(
                color = colors.needle.copy(alpha = 0.98f),
                topLeft = Offset(centerX - needleWidth * 0.5f, needleTop),
                size = Size(needleWidth, needleHeight),
                cornerRadius = CornerRadius(needleWidth, needleWidth)
            )
        }
        }
        Spacer(Modifier.height(4.dp))
        ImmersiveWaveformTimeRow(
            currentMs = (displaySecond * 1000f).toLong().coerceAtLeast(0L),
            totalMs = totalDurationMs
        )
    }
}

private fun edgeFade(x: Float, width: Float, fade: Float): Float {
    if (fade <= 0f) return 1f
    val left = (x / fade).coerceIn(0f, 1f)
    val right = ((width - x) / fade).coerceIn(0f, 1f)
    val raw = min(left, right)
    return raw * raw * (3f - 2f * raw)
}

private fun secondPeak(seed: Int, second: Int): Float {
    var value = seed xor (second * 0x45D9F3B)
    value = value xor (value ushr 16)
    value *= 0x45D9F3B
    value = value xor (value ushr 16)
    return 0.20f + ((value ushr 8) and 0xFF) / 255f * 0.80f
}

internal data class ImmersiveClimaxSegment(
    val startFraction: Float,
    val endFraction: Float,
    val confidence: Float
)

internal data class ImmersiveWaveformColors(
    val played: Color,
    val remaining: Color,
    val climaxPlayed: Color,
    val climaxRemaining: Color,
    val needle: Color
)

@Composable
internal fun ImmersiveWaveformProgressBar(
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    colors: ImmersiveWaveformColors,
    climaxEnabled: Boolean,
    showDebugPanel: Boolean,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barWidthDp: Float = 2.35f,
    gapDp: Float = 0.56f
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val realFraction = if (totalDurationMs > 0L) {
        currentPositionMs.toFloat() / totalDurationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val seedKey = remember(currentSong?.path, currentSong?.fileSize, currentSong?.dateModified, totalDurationMs) {
        buildString {
            append(currentSong?.path.orEmpty())
            append('|')
            append(currentSong?.fileSize ?: 0L)
            append('|')
            append(currentSong?.dateModified ?: 0L)
            append('|')
            append(totalDurationMs)
        }
    }
    var pendingSeekFraction by remember(seedKey) { mutableStateOf<Float?>(null) }
    LaunchedEffect(pendingSeekFraction, realFraction, seedKey) {
        val pending = pendingSeekFraction ?: return@LaunchedEffect
        if (abs(realFraction - pending) <= 0.006f) {
            pendingSeekFraction = null
        } else {
            delay(520)
            if (pendingSeekFraction == pending) pendingSeekFraction = null
        }
    }
    var forceTrackStart by remember { mutableStateOf(false) }
    LaunchedEffect(seedKey) {
        pendingSeekFraction = null
        forceTrackStart = true
        delay(180)
        forceTrackStart = false
    }
    val pauseMorph by animateFloatAsState(
        targetValue = if (isPlaying || isDragging) 0f else 1f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "immersiveWaveformPauseMorph"
    )
    val displayFraction = if (isDragging) {
        dragFraction
    } else if (forceTrackStart) {
        0f
    } else {
        pendingSeekFraction ?: realFraction
    }

    val waveform = remember(seedKey) { generatePreviewWaveform(seedKey, sampleCount = 100) }
    val climaxSegments = remember(seedKey, waveform) { detectPreviewClimaxSegments(waveform) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
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
                            pendingSeekFraction = lastFraction
                            isDragging = false
                            onSeekStop(lastFraction)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(62.dp)) {
                val barWidth = barWidthDp.dp.toPx().coerceAtLeast(1f)
                val gap = gapDp.dp.toPx().coerceAtLeast(0.5f)
                drawRoundedWaveform(
                    peaks = waveform,
                    progress = displayFraction,
                    pauseMorph = pauseMorph,
                    colors = colors,
                    climaxSegments = if (climaxEnabled) climaxSegments else emptyList(),
                    barWidth = barWidth,
                    gap = gap,
                    isDragging = isDragging
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ImmersiveWaveformTimeRow(
            currentMs = (displayFraction.coerceIn(0f, 1f) * totalDurationMs).toLong().coerceAtLeast(0L),
            totalMs = totalDurationMs
        )
        @Suppress("UNUSED_EXPRESSION")
        showDebugPanel
    }
}

private fun DrawScope.drawRoundedWaveform(
    peaks: FloatArray,
    progress: Float,
    pauseMorph: Float,
    colors: ImmersiveWaveformColors,
    climaxSegments: List<ImmersiveClimaxSegment>,
    barWidth: Float,
    gap: Float,
    isDragging: Boolean
) {
    if (peaks.isEmpty() || size.width <= 0f || size.height <= 0f) return

    val step = barWidth + gap
    val visibleBars = max(8, (size.width / step).toInt())
    val actualStep = size.width / visibleBars.toFloat()
    val actualBarWidth = min(barWidth, actualStep * 0.88f).coerceAtLeast(1f)
    val centerY = size.height * 0.5f
    val maxHalfHeight = size.height * 0.50f
    val minHalfHeight = 2.4f * density
    val progressX = size.width * progress.coerceIn(0f, 1f)

    fun baseBarValue(index: Int): Float {
        val srcIndex = ((index + 0.5f) / visibleBars.toFloat() * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
        val live = emphasizeWaveValue(peaks[srcIndex].coerceIn(0f, 1f))
        val idle = 0.020f + when (index % 4) {
            0 -> 0.006f
            1 -> 0.014f
            2 -> 0.010f
            else -> 0.016f
        }
        val m = pauseMorph.coerceIn(0f, 1f)
        return live * (1f - m) + idle * m
    }

    fun climaxColorFor(centerX: Float, base: Color): Color {
        if (climaxSegments.isEmpty()) return base
        val fraction = (centerX / size.width).coerceIn(0f, 1f)
        val hit = climaxSegments.firstOrNull { fraction in it.startFraction..it.endFraction } ?: return base
        val confidence = hit.confidence.coerceIn(0f, 1f)
        val target = if (centerX <= progressX) colors.climaxPlayed else colors.climaxRemaining
        return lerpColor(base, target, 0.35f + 0.45f * confidence)
    }

    val radius = CornerRadius(actualBarWidth * 0.5f, actualBarWidth * 0.5f)
    for (i in 0 until visibleBars) {
        val x = i * actualStep + (actualStep - actualBarWidth) * 0.5f
        val centerX = x + actualBarWidth * 0.5f
        val shaped = baseBarValue(i)
        val halfHeight = (minHalfHeight + maxHalfHeight * shaped)
            .coerceAtMost(size.height * 0.49f)
        val top = centerY - halfHeight
        val barHeight = halfHeight * 2f
        val left = x
        val right = x + actualBarWidth
        val playedColor = climaxColorFor(centerX, colors.played)
        val remainingColor = climaxColorFor(centerX, colors.remaining)

        when {
            progressX <= left -> {
                drawRoundRect(
                    color = remainingColor,
                    topLeft = Offset(left, top),
                    size = Size(actualBarWidth, barHeight),
                    cornerRadius = radius
                )
            }
            progressX >= right -> {
                drawRoundRect(
                    color = playedColor,
                    topLeft = Offset(left, top),
                    size = Size(actualBarWidth, barHeight),
                    cornerRadius = radius
                )
            }
            else -> {
                clipRect(left = left, top = 0f, right = progressX, bottom = size.height) {
                    drawRoundRect(
                        color = playedColor,
                        topLeft = Offset(left, top),
                        size = Size(actualBarWidth, barHeight),
                        cornerRadius = radius
                    )
                }
                clipRect(left = progressX, top = 0f, right = right, bottom = size.height) {
                    drawRoundRect(
                        color = remainingColor,
                        topLeft = Offset(left, top),
                        size = Size(actualBarWidth, barHeight),
                        cornerRadius = radius
                    )
                }
            }
        }
    }

    val dragFocus = if (isDragging) 1f else 0f
    val needleWidth = (1.48f + dragFocus * 0.36f) * density
    val needleHeight = size.height * 1.36f
    val needleTop = (size.height - needleHeight) * 0.5f
    drawRoundRect(
        color = colors.needle.copy(alpha = 0.98f),
        topLeft = Offset((progressX - needleWidth * 0.5f).coerceIn(0f, size.width - needleWidth), needleTop),
        size = Size(needleWidth, needleHeight),
        cornerRadius = CornerRadius(needleWidth, needleWidth)
    )
}

@Composable
private fun ImmersiveWaveformTimeRow(
    currentMs: Long,
    totalMs: Long
) {
    val textColor = Color.White.copy(alpha = 0.66f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = AudioUtils.formatDuration(currentMs.coerceAtLeast(0L)),
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = AudioUtils.formatDuration(totalMs.coerceAtLeast(0L)),
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun emphasizeWaveValue(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return (0.05f + 0.95f * clamped.pow(1.7f)).coerceIn(0f, 1f)
}

private fun centerFocusBlend(x: Float, center: Float, radius: Float): Float {
    if (radius <= 0f) return 0f
    val t = (1f - (abs(x - center) / radius)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpColor(start: Color, end: Color, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * clamped,
        green = start.green + (end.green - start.green) * clamped,
        blue = start.blue + (end.blue - start.blue) * clamped,
        alpha = start.alpha + (end.alpha - start.alpha) * clamped
    )
}

private fun generatePreviewWaveform(seedKey: String, sampleCount: Int): FloatArray {
    var seed = stableHash(seedKey)
    fun nextUnit(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0xFFFF).toFloat() / 65535f
    }

    val base1 = 1.6f + nextUnit() * 2.2f
    val base2 = 4.3f + nextUnit() * 4.0f
    val base3 = 11.0f + nextUnit() * 7.0f
    val raw = FloatArray(sampleCount)
    for (i in 0 until sampleCount) {
        val t = i.toFloat() / (sampleCount - 1).coerceAtLeast(1).toFloat()
        val songShape = 0.45f + 0.22f * sin((t * PI * base1).toFloat()) +
            0.16f * sin((t * PI * base2 + 0.7f).toFloat()) +
            0.09f * sin((t * PI * base3 + 1.6f).toFloat())
        val noise = (nextUnit() - 0.5f) * 0.24f
        val intro = (t / 0.08f).coerceIn(0f, 1f)
        val outro = ((1f - t) / 0.08f).coerceIn(0f, 1f)
        val value = (songShape + noise) * intro * outro
        raw[i] = value.coerceIn(0.04f, 1f)
    }

    val smooth = FloatArray(sampleCount)
    for (i in raw.indices) {
        val left2 = raw[(i - 2).coerceAtLeast(0)]
        val left1 = raw[(i - 1).coerceAtLeast(0)]
        val center = raw[i]
        val right1 = raw[(i + 1).coerceAtMost(raw.lastIndex)]
        val right2 = raw[(i + 2).coerceAtMost(raw.lastIndex)]
        smooth[i] = (left2 * 0.08f + left1 * 0.18f + center * 0.48f + right1 * 0.18f + right2 * 0.08f)
            .coerceIn(0.03f, 1f)
    }
    return smooth
}

private fun detectPreviewClimaxSegments(peaks: FloatArray): List<ImmersiveClimaxSegment> {
    if (peaks.size < 64) return emptyList()
    val window = (peaks.size * 0.075f).toInt().coerceIn(32, 96)
    var bestStart = 0
    var bestScore = -1f
    for (start in (peaks.size * 0.10f).toInt() until (peaks.size * 0.82f).toInt()) {
        val end = (start + window).coerceAtMost(peaks.size)
        if (end - start < window / 2) break
        var sum = 0f
        for (i in start until end) sum += peaks[i]
        val avg = sum / (end - start).toFloat()
        val beforeStart = (start - window).coerceAtLeast(0)
        var beforeSum = 0f
        var beforeCount = 0
        for (i in beforeStart until start) {
            beforeSum += peaks[i]
            beforeCount++
        }
        val beforeAvg = if (beforeCount > 0) beforeSum / beforeCount else avg
        val lift = (avg - beforeAvg).coerceAtLeast(0f)
        val centerBias = 1f - abs((start + window * 0.5f) / peaks.size.toFloat() - 0.58f) * 0.42f
        val score = (avg * 0.75f + lift * 0.55f) * centerBias
        if (score > bestScore) {
            bestScore = score
            bestStart = start
        }
    }
    if (bestScore <= 0f) return emptyList()
    val start = (bestStart - window / 5).coerceAtLeast(0)
    val end = (bestStart + window + window / 4).coerceAtMost(peaks.size)
    return listOf(
        ImmersiveClimaxSegment(
            startFraction = start / peaks.size.toFloat(),
            endFraction = end / peaks.size.toFloat(),
            confidence = bestScore.coerceIn(0f, 1f)
        )
    )
}

private fun stableHash(value: String): Int {
    var h = -0x7ee3623b
    value.forEach { c ->
        h = h xor c.code
        h *= 16777619
    }
    return if (h == 0) 0x13579bdf else h
}
