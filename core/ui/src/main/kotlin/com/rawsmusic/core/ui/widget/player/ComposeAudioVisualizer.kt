package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

@Composable
fun ComposeAudioVisualizer(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xB0FFFFFF),
    glowColor: Color = Color(0x55FFFFFF)
) {
    Canvas(modifier = modifier) {
        if (levels.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas

        val barCount = levels.size
        val barWidth = size.width / barCount
        val centerY = size.height * 0.5f
        val maxBarHeight = max(1f, centerY - 4f)
        val strokeWidth = 2.2f * density
        val path = Path()
        val reversePath = Path()

        for (i in 0 until barCount) {
            val x = i * barWidth + barWidth * 0.5f
            val shaped = levels[i].coerceIn(0f, 1f)
            val y = centerY - maxBarHeight * shaped * shaped
            val reverseY = centerY + maxBarHeight * levels[barCount - 1 - i].coerceIn(0f, 1f).let { it * it }

            if (i == 0) {
                path.moveTo(x, y)
                reversePath.moveTo(x, reverseY)
            } else {
                path.lineTo(x, y)
                reversePath.lineTo(x, reverseY)
            }

            drawLine(
                color = color,
                start = Offset(x, centerY),
                end = Offset(x, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x, centerY),
                end = Offset(x, reverseY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawPath(path, glowColor, style = Stroke(width = strokeWidth * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = stroke)
        drawPath(reversePath, glowColor.copy(alpha = 0.4f), style = Stroke(width = strokeWidth * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(reversePath, color.copy(alpha = 0.35f), style = stroke)
        drawLine(
            color = color.copy(alpha = 0.45f),
            start = Offset(barWidth * 0.5f, centerY),
            end = Offset(size.width - barWidth * 0.5f, centerY),
            strokeWidth = 1.4f * density,
            cap = StrokeCap.Round
        )
    }
}

fun pcmWaveformLevels(
    buffer: ByteArray?,
    read: Int,
    channels: Int,
    bitsPerSample: Int,
    barCount: Int = 80
): FloatArray {
    if (buffer == null || read <= 0 || barCount <= 0) return FloatArray(barCount)

    val bytesPerSample = when {
        bitsPerSample <= 8 -> 1
        bitsPerSample <= 16 -> 2
        bitsPerSample <= 24 -> 3
        else -> 4
    }
    val safeChannels = max(1, channels)
    val frameSize = safeChannels * bytesPerSample
    val usableBytes = read.coerceAtMost(buffer.size).let { it - (it % frameSize) }
    if (usableBytes <= 0) return FloatArray(barCount)

    val frames = usableBytes / frameSize
    val step = max(1f, frames.toFloat() / barCount)
    val rawLevels = FloatArray(barCount)
    for (i in 0 until barCount) {
        val frameStart = (i * step).toInt().coerceIn(0, frames - 1)
        val frameEnd = ((i + 1) * step).toInt().coerceIn(frameStart, frames - 1)
        var peak = 0f
        var sum = 0f
        var count = 0
        for (frame in frameStart..frameEnd) {
            val base = frame * frameSize
            for (ch in 0 until safeChannels) {
                val offset = base + ch * bytesPerSample
                val sample = kotlin.math.abs(decodePcmSample(buffer, offset, bytesPerSample))
                peak = max(peak, sample)
                sum += sample
                count++
            }
        }
        val average = if (count > 0) sum / count else 0f
        rawLevels[i] = (peak * 0.72f + average * 1.65f).coerceIn(0f, 1f)
    }

    return FloatArray(barCount) { i ->
        val left = rawLevels[max(0, i - 1)]
        val center = rawLevels[i]
        val right = rawLevels[minOf(barCount - 1, i + 1)]
        (left * 0.22f + center * 0.56f + right * 0.22f).let {
            if (it < 0.015f) 0f else it.coerceIn(0f, 1f)
        }
    }
}

private fun decodePcmSample(buffer: ByteArray, offset: Int, bytesPerSample: Int): Float {
    if (offset < 0 || offset >= buffer.size) return 0f
    return when (bytesPerSample) {
        1 -> (buffer[offset].toInt() / 128f).coerceIn(-1f, 1f)
        2 -> {
            if (offset + 1 >= buffer.size) return 0f
            val sample = ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xFF))
            sample.toShort().toInt() / 32768f
        }
        3 -> {
            if (offset + 2 >= buffer.size) return 0f
            val sample = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16)
            val signed = if (sample and 0x800000 != 0) sample or -0x1000000 else sample
            signed / 8388608f
        }
        else -> {
            if (offset + 3 >= buffer.size) return 0f
            val sample = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 24)
            sample / 2147483648f
        }
    }.coerceIn(-1f, 1f)
}
