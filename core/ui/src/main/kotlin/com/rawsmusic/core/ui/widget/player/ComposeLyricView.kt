package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ComposeLyricView(
    song: Song?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    modifier: Modifier = Modifier,
    topPadding: Dp = 140.dp,
    bottomPadding: Dp = 120.dp,
    textColor: Color = Color.White,
    dimColor: Color = Color.White.copy(alpha = 0.28f),
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    fontFamily: FontFamily? = null,
    maxPrimaryVisibleLines: Int = Int.MAX_VALUE,
    onLineClick: (Long) -> Unit = {},
    onSwipeRight: () -> Unit = {}
) {
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val playbackState by remember(lines, positionMs) {
        derivedStateOf { calculateLyricPlaybackState(lines, positionMs) }
    }
    val currentIndex = playbackState.currentLineIndex
    val compactWindowEnabled = maxPrimaryVisibleLines != Int.MAX_VALUE
    val inferredCompactLineLimit = remember(lines, displayTranslation, displayRoma) {
        immersivePrimaryLyricLineLimit(lines, displayTranslation, displayRoma)
    }
    val compactLineLimit = remember(compactWindowEnabled, maxPrimaryVisibleLines, inferredCompactLineLimit) {
        if (!compactWindowEnabled) {
            Int.MAX_VALUE
        } else {
            maxOf(maxPrimaryVisibleLines, inferredCompactLineLimit).coerceIn(1, 5)
        }
    }
    val visibleLineIndices = remember(lines, currentIndex, compactLineLimit) {
        if (!compactWindowEnabled || lines.isEmpty()) {
            lines.indices.toList()
        } else {
            centeredLyricWindowIndices(
                total = lines.size,
                currentIndex = currentIndex,
                maxLines = compactLineLimit
            )
        }
    }

    // 用户手动滚动时暂停自动追踪
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolling = true
        } else {
            kotlinx.coroutines.delay(1800)
            userScrolling = false
        }
    }

    LaunchedEffect(currentIndex, lines.size, compactWindowEnabled) {
        if (!compactWindowEnabled && currentIndex >= 0 && lines.isNotEmpty() && !userScrolling && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex, scrollOffset = -160)
        }
    }

    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = song?.name?.takeIf { it.isNotBlank() } ?: "暂无歌词",
                color = secondaryColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (compactWindowEnabled) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = {
                            totalX = 0f
                            totalY = 0f
                        },
                        onDragEnd = {
                            if (totalX > 150f && abs(totalX) > abs(totalY)) onSwipeRight()
                        },
                        onDrag = { change, dragAmount ->
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            change.consume()
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(
                space = if (visibleLineIndices.size <= 3) 18.dp else 12.dp,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            visibleLineIndices.forEach { index ->
                val line = lines[index]
                val active = index == currentIndex
                val distance = if (currentIndex >= 0) abs(index - currentIndex) else 4

                ComposeLyricLine(
                    line = line,
                    active = active,
                    progress = if (active) playbackState.lineProgress else 0f,
                    positionMs = positionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = if (active) textColor else dimColor,
                    dimColor = dimColor,
                    secondaryColor = if (active) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                    fontFamily = fontFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .appleLyricLineVisuals(
                            active = active,
                            distance = distance,
                            alignedRight = line.isAlignedRight
                        )
                        .clickable {
                            scope.launch { onLineClick(line.begin) }
                        }
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragStart = {
                        totalX = 0f
                        totalY = 0f
                    },
                    onDragEnd = {
                        if (totalX > 150f && abs(totalX) > abs(totalY)) onSwipeRight()
                    },
                    onDrag = { change, dragAmount ->
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        change.consume()
                    }
                )
            },
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Spacer(Modifier.height(topPadding)) }
        items(visibleLineIndices.size, key = { itemIndex ->
            val sourceIndex = visibleLineIndices[itemIndex]
            "${lines[sourceIndex].begin}-$sourceIndex"
        }) { itemIndex ->
            val index = visibleLineIndices[itemIndex]
            val line = lines[index]
            val active = index == currentIndex
            val distance = if (currentIndex >= 0) abs(index - currentIndex) else 4

            ComposeLyricLine(
                line = line,
                active = active,
                progress = if (active) playbackState.lineProgress else 0f,
                positionMs = positionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                textColor = if (active) textColor else dimColor,
                dimColor = dimColor,
                secondaryColor = if (active) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                fontFamily = fontFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .appleLyricLineVisuals(
                        active = active,
                        distance = distance,
                        alignedRight = line.isAlignedRight
                    )
                    .clickable {
                        scope.launch { onLineClick(line.begin) }
                    }
            )
        }
        item { Spacer(Modifier.height(bottomPadding)) }
    }
}

@Composable
private fun ComposeLyricLine(
    line: IRichLyricLine,
    active: Boolean,
    progress: Float,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    textColor: Color,
    dimColor: Color,
    secondaryColor: Color,
    fontFamily: FontFamily?,
    modifier: Modifier = Modifier
) {
    val align = if (line.isAlignedRight) Alignment.End else Alignment.Start
    val textAlign = if (line.isAlignedRight) TextAlign.End else TextAlign.Start
    val hasWordTiming = !line.words.isNullOrEmpty()
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = align
    ) {
        val main = line.text.orEmpty().ifBlank { "..." }
        Box(modifier = Modifier.widthIn(max = 340.dp)) {
            if (active && hasWordTiming) {
                // 逐字渐变扫过
                KaraokeLyricLine(
                    line = line,
                    positionMs = positionMs,
                    highlightColor = textColor,
                    dimColor = dimColor,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = main,
                    color = textColor,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val secondaryText: String? = when {
            displayTranslation && !line.translation.isNullOrBlank() -> line.translation
            displayRoma && !line.roma.isNullOrBlank() -> line.roma
            !line.secondary.isNullOrBlank() -> line.secondary
            else -> null
        }
        val visibleSecondary = secondaryText?.takeIf { it.isNotBlank() }
        if (visibleSecondary != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = visibleSecondary,
                color = secondaryColor,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = Modifier.widthIn(max = 340.dp)
            )
        }
    }
}

// ========== 行级歌词视觉 ==========

private val AppleLyricsEasing = CubicBezierEasing(0.25f, 0.10f, 0.25f, 1.00f)

@Composable
private fun Modifier.appleLyricLineVisuals(
    active: Boolean,
    distance: Int,
    alignedRight: Boolean
): Modifier {
    val targetAlpha = when {
        active -> 1f
        distance == 1 -> 0.72f
        distance == 2 -> 0.46f
        distance == 3 -> 0.30f
        else -> 0.20f
    }

    val targetScale = when {
        active -> 1.035f
        distance == 1 -> 0.965f
        distance == 2 -> 0.94f
        else -> 0.92f
    }

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 260, easing = AppleLyricsEasing),
        label = "appleLyricLineAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 360, easing = AppleLyricsEasing),
        label = "appleLyricLineScale"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
        transformOrigin = if (alignedRight) {
            TransformOrigin(1f, 0.5f)
        } else {
            TransformOrigin(0f, 0.5f)
        }
    }
}

private fun immersivePrimaryLyricLineLimit(
    lines: List<IRichLyricLine>,
    displayTranslation: Boolean,
    displayRoma: Boolean
): Int {
    val hasSecondaryText = lines.any { line ->
        displayTranslation && !line.translation.isNullOrBlank() ||
            displayRoma && !line.roma.isNullOrBlank() ||
            !line.secondary.isNullOrBlank()
    }
    return if (hasSecondaryText) 3 else 5
}


private fun centeredLyricWindowIndices(total: Int, currentIndex: Int, maxLines: Int): List<Int> {
    if (total <= 0) return emptyList()
    val count = maxLines.coerceIn(1, total)
    val anchor = currentIndex.coerceIn(0, total - 1)
    var start = anchor - count / 2
    start = start.coerceIn(0, (total - count).coerceAtLeast(0))
    return (start until start + count).toList()
}
