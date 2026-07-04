package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Karaoke lyric line.
 *
 * - sweep progress 直接由 positionMs 计算，避免逐字扫光和 PCM 播放位置各跑各的
 * - lift 仍使用本地 spring，只影响视觉抬升，不影响歌词时间轴
 */
@Composable
fun KaraokeLyricLine(
    line: IRichLyricLine,
    positionMs: Long,
    highlightColor: Color,
    dimColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 28.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 34.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    fontFamily: FontFamily? = null,
    wordLiftDp: Dp = 0.25.dp,
    wordLiftScale: Float = 0f,
    modifier: Modifier = Modifier
) {
    val words = line.words.orEmpty()
    val text = line.text.orEmpty()
    if (words.isEmpty() || text.isBlank()) return

    val segments = remember(text, words) { buildLyricSegments(text, words) }
    val lineEndMs = remember(line, words) { effectiveSingleLineEnd(line, words) }

    val style = TextStyle(
        fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, fontFamily = fontFamily
    )

    BaselineFlowRow(modifier = modifier.widthIn(max = 340.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is LyricSegment.Space -> {
                    StaticLyricText(text = segment.text, color = dimColor, style = style)
                }
                is LyricSegment.Word -> {
                    val timing = wordTimingState(
                        positionMs = positionMs,
                        word = segment.word,
                        nextWordBeginMs = segment.nextWordBeginMs,
                        lineEndMs = lineEndMs
                    )
                    val isCurrentWord = timing.progress > 0f && timing.progress < 1f
                    val isCompleted = timing.progress >= 1f

                    KaraokeWordGroup(
                        text = segment.text,
                        progress = timing.progress,
                        wordBeginMs = timing.beginMs,
                        durationMs = timing.durationMs,
                        isCurrentWord = isCurrentWord,
                        isCompleted = isCompleted,
                        highlightColor = highlightColor,
                        dimColor = dimColor,
                        style = style,
                        wordLiftDp = wordLiftDp,
                        wordLiftScale = wordLiftScale
                    )
                }
            }
        }
    }
}

// ========== Word Group ==========

@Composable
private fun KaraokeWordGroup(
    text: String,
    progress: Float,
    wordBeginMs: Long,
    durationMs: Int,
    isCurrentWord: Boolean,
    isCompleted: Boolean,
    highlightColor: Color,
    dimColor: Color,
    style: TextStyle,
    wordLiftDp: Dp,
    wordLiftScale: Float
) {
    var widthPx by remember(text) { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val maxLiftPx = with(density) { wordLiftDp.toPx() }

    // Lift：从当前值 spring，不 snapTo(0)（damping=0.93, stiffness=25）
    val lift = remember(text, wordBeginMs) { Animatable(0f) }

    LaunchedEffect(text, wordBeginMs, isCurrentWord, durationMs) {
        if (isCurrentWord) {
            lift.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.93f, stiffness = 25f)
            )
            val holdMs = (durationMs * 0.10f).roundToInt().coerceIn(20, 70)
            delay(holdMs.toLong())
            lift.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.96f, stiffness = 32f)
            )
        } else {
            lift.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.96f, stiffness = 32f)
            )
        }
    }

    val p = progress.coerceIn(0f, 1f)

    val featherPx = with(density) { 22.dp.toPx() }.coerceAtMost(widthPx * 0.88f)
    val sweepPx = widthPx * p
    val fadeStart = ((sweepPx - featherPx) / widthPx).coerceIn(0f, 1f)
    val fadeEnd = (sweepPx / widthPx).coerceIn(0f, 1f)

    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to highlightColor,
            fadeStart to highlightColor,
            fadeEnd to highlightColor.copy(alpha = 0f),
            1f to highlightColor.copy(alpha = 0f)
        ),
        start = Offset.Zero,
        end = Offset(widthPx, 0f)
    )

    Layout(
        modifier = Modifier.graphicsLayer {
            translationY = -maxLiftPx * lift.value
            val s = 1f + wordLiftScale * lift.value
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
        content = {
            Text(
                text = text, color = dimColor, style = style,
                onTextLayout = { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            )
            when {
                p <= 0.001f -> Unit
                isCompleted -> Text(text = text, color = highlightColor, style = style)
                else -> Text(text = text, style = style.copy(brush = brush))
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        val baseline = placeables.firstOrNull()
            ?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified } ?: h
        layout(w, h, alignmentLines = mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

// ========== StaticLyricText ==========

@Composable
private fun StaticLyricText(text: String, color: Color, style: TextStyle) {
    Layout(
        content = { Text(text = text, color = color, style = style) }
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val baseline = placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        layout(placeable.width, placeable.height, alignmentLines = mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {
            placeable.placeRelative(0, 0)
        }
    }
}

// ========== BaselineFlowRow ==========

@Composable
private fun BaselineFlowRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val maxWidth = constraints.maxWidth

        val lines = mutableListOf<List<Placeable>>()
        var currentLine = mutableListOf<Placeable>()
        var currentWidth = 0

        measurables.forEach { measurable ->
            val placeable = measurable.measure(childConstraints)
            if (currentLine.isNotEmpty() && currentWidth + placeable.width > maxWidth) {
                lines += currentLine
                currentLine = mutableListOf()
                currentWidth = 0
            }
            currentLine += placeable
            currentWidth += placeable.width
        }
        if (currentLine.isNotEmpty()) lines += currentLine

        val lineBaselines = mutableListOf<Int>()
        val lineHeights = mutableListOf<Int>()

        lines.forEach { line ->
            val baselines = line.map { pl ->
                val b = pl[FirstBaseline]
                if (b != AlignmentLine.Unspecified) b else pl.height
            }
            val lineBaseline = baselines.maxOrNull() ?: 0
            val lineDescent = line.mapIndexed { i, pl -> pl.height - baselines[i] }.maxOrNull() ?: 0
            lineBaselines += lineBaseline
            lineHeights += lineBaseline + lineDescent
        }

        val maxLineW = lines.maxOfOrNull { line -> line.sumOf { it.width } } ?: 0
        val layoutWidth = maxLineW.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = lineHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            var y = 0
            lines.forEachIndexed { lineIdx, line ->
                val baseline = lineBaselines[lineIdx]
                var x = 0
                line.forEach { placeable ->
                    val childBaseline = placeable[FirstBaseline].let {
                        if (it != AlignmentLine.Unspecified) it else placeable.height
                    }
                    placeable.placeRelative(x = x, y = y + baseline - childBaseline)
                    x += placeable.width
                }
                y += lineHeights[lineIdx]
            }
        }
    }
}

// ========== Segment ==========

private sealed interface LyricSegment {
    val text: String
    data class Space(override val text: String) : LyricSegment
    data class Word(override val text: String, val word: LyricWord, val nextWordBeginMs: Long?) : LyricSegment
}

private fun buildLyricSegments(fullText: String, words: List<LyricWord>): List<LyricSegment> {
    val result = mutableListOf<LyricSegment>()
    var searchFrom = 0
    for (index in words.indices) {
        val word = words[index]
        val raw = word.text.orEmpty()
        if (raw.isEmpty()) continue
        val start = fullText.indexOf(raw, startIndex = searchFrom)
            .takeIf { it >= 0 } ?: searchFrom.coerceAtMost(fullText.length)
        if (start > searchFrom) {
            result += LyricSegment.Space(text = fullText.substring(searchFrom, start))
        }
        val end = (start + raw.length).coerceAtMost(fullText.length)
        result += LyricSegment.Word(
            text = fullText.substring(start, end),
            word = word,
            nextWordBeginMs = words.getOrNull(index + 1)?.begin
        )
        searchFrom = end
    }
    if (searchFrom < fullText.length) {
        result += LyricSegment.Space(text = fullText.substring(searchFrom))
    }
    return result
}

// ========== 时间计算 ==========

private data class WordTimingState(
    val progress: Float,
    val beginMs: Long,
    val endMs: Long,
    val durationMs: Int
)

private fun wordTimingState(
    positionMs: Long, word: LyricWord,
    nextWordBeginMs: Long?, lineEndMs: Long
): WordTimingState {
    val begin = word.begin
    val fallbackEnd = when {
        nextWordBeginMs != null && nextWordBeginMs > begin -> nextWordBeginMs
        lineEndMs > begin -> lineEndMs
        else -> begin + 240L
    }
    val end = when {
        word.end > begin -> word.end
        else -> fallbackEnd
    }.let { candidate ->
        if (nextWordBeginMs != null && nextWordBeginMs > begin) minOf(candidate, nextWordBeginMs)
        else minOf(candidate, lineEndMs)
    }.coerceAtLeast(begin + 1L)

    val duration = (end - begin).coerceAtLeast(1L).toInt()
    val progress = when {
        positionMs < begin -> 0f
        positionMs >= end -> 1f
        else -> ((positionMs - begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
    return WordTimingState(progress = progress, beginMs = begin, endMs = end, durationMs = duration)
}
