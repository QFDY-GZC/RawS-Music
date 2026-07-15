package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
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


enum class LyricTextPosition(val value: Int) {
    Left(0),
    Center(1),
    Right(2);

    companion object {
        fun from(value: Int): LyricTextPosition = entries.firstOrNull { it.value == value } ?: Left
    }
}

@Composable
fun ComposeLyricView(
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean = false,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    modifier: Modifier = Modifier,
    topPadding: Dp = 140.dp,
    bottomPadding: Dp = 120.dp,
    textColor: Color = Color.White,
    dimColor: Color = Color.White.copy(alpha = 0.28f),
    secondaryColor: Color = Color.White.copy(alpha = 0.58f),
    fontFamily: FontFamily? = null,
    fontSizeSp: Int = 28,
    textPosition: LyricTextPosition = LyricTextPosition.Left,
    blurEnabled: Boolean = true,
    highlightAll: Boolean = false,
    maxPrimaryVisibleLines: Int = Int.MAX_VALUE,
    onLineClick: (Long) -> Unit = {},
    onSwipeRight: () -> Unit = {}
) {
    val lines = remember(song) { song?.lyrics.orEmpty() }
    var interpolatedPositionMs by remember { androidx.compose.runtime.mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        interpolatedPositionMs = positionMs
        if (!isPlaying) return@LaunchedEffect
        val startedAtNs = withFrameNanos { it }
        while (true) {
            val frameNs = withFrameNanos { it }
            val elapsedMs = ((frameNs - startedAtNs) / 1_000_000L).coerceIn(0L, 80L)
            interpolatedPositionMs = positionMs + elapsedMs
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val playbackState by remember(lines, interpolatedPositionMs) {
        derivedStateOf { calculateLyricPlaybackState(lines, interpolatedPositionMs) }
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

    // Only a real finger drag pauses lyric tracking. isScrollInProgress also becomes
    // true for animateScrollToItem and previously made auto-scroll disable itself.
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(userDragging) {
        if (userDragging) {
            userScrolling = true
        } else {
            kotlinx.coroutines.delay(1800)
            userScrolling = false
        }
    }

    LaunchedEffect(currentIndex, lines.size, compactWindowEnabled, userScrolling) {
        if (!compactWindowEnabled && currentIndex >= 0 && lines.isNotEmpty() && !userScrolling) {
            val lazyIndex = currentIndex + 1 // Leading spacer occupies item 0.
            val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
            if (visibleItem != null) {
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                val targetOffset = layoutInfo.viewportStartOffset + viewportHeight * 0.28f
                listState.animateScrollBy(
                    value = visibleItem.offset - targetOffset,
                    animationSpec = tween(durationMillis = 750, easing = AppleLyricsEasing)
                )
            } else {
                listState.animateScrollToItem(lazyIndex, scrollOffset = -160)
            }
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
                .observeLyricSwipeRight(onSwipeRight),
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
                    positionMs = interpolatedPositionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = if (active || highlightAll) textColor else dimColor,
                    dimColor = dimColor,
                    secondaryColor = if (active || highlightAll) secondaryColor
                    else secondaryColor.copy(alpha = 0.58f),
                    fontFamily = fontFamily,
                    fontSizeSp = fontSizeSp,
                    textPosition = textPosition,
                    modifier = Modifier
                        .fillMaxWidth()
                        .appleLyricLineVisuals(
                            active = active,
                            signedDistance = index - currentIndex,
                            pivotFractionX = textPosition.pivotFractionX,
                            blurEnabled = blurEnabled,
                            highlightAll = highlightAll
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
            .observeLyricSwipeRight(onSwipeRight),
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
                positionMs = interpolatedPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                textColor = if (active || highlightAll) textColor else dimColor,
                dimColor = dimColor,
                secondaryColor = if (active || highlightAll) secondaryColor
                else secondaryColor.copy(alpha = 0.58f),
                fontFamily = fontFamily,
                fontSizeSp = fontSizeSp,
                textPosition = textPosition,
                modifier = Modifier
                    .fillMaxWidth()
                    .appleLyricLineVisuals(
                        active = active,
                        signedDistance = index - currentIndex,
                        pivotFractionX = textPosition.pivotFractionX,
                        blurEnabled = blurEnabled && !userScrolling,
                        highlightAll = highlightAll
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
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    textColor: Color,
    dimColor: Color,
    secondaryColor: Color,
    fontFamily: FontFamily?,
    fontSizeSp: Int,
    textPosition: LyricTextPosition,
    modifier: Modifier = Modifier
) {
    val align = textPosition.horizontalAlignment
    val textAlign = textPosition.textAlign
    val primarySize = fontSizeSp.coerceIn(24, 40)
    val primaryLineHeight = primarySize + 6
    val secondarySize = (primarySize * 0.62f).toInt().coerceIn(15, 25)
    val secondaryLineHeight = secondarySize + 6
    val hasWordTiming = !line.words.isNullOrEmpty()
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = align
    ) {
        val main = line.text.orEmpty().ifBlank { "..." }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 340.dp),
            contentAlignment = textPosition.boxAlignment
        ) {
            when {
                active && hasWordTiming -> {
                    // 逐字歌词：按单词时间轴羽化、高亮并上抬。
                    KaraokeLyricLine(
                        line = line,
                        positionMs = positionMs,
                        highlightColor = textColor,
                        dimColor = dimColor,
                        fontSize = primarySize.sp,
                        lineHeight = primaryLineHeight.sp,
                        textAlign = textAlign,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                active -> {
                    // 普通逐行歌词只做当前行整体高亮，不启用羽化或时间进度扫光。
                    Text(
                        text = main,
                        color = textColor,
                        fontSize = primarySize.sp,
                        lineHeight = primaryLineHeight.sp,
                        textAlign = textAlign,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Text(
                        text = main,
                        color = textColor,
                        fontSize = primarySize.sp,
                        lineHeight = primaryLineHeight.sp,
                        textAlign = textAlign,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                fontSize = secondarySize.sp,
                lineHeight = secondaryLineHeight.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 340.dp)
            )
        }
    }
}

// ========== 行级歌词视觉 ==========

private val AppleLyricsEasing = CubicBezierEasing(0.40f, 0.10f, 0.00f, 1.00f)

@Composable
private fun Modifier.appleLyricLineVisuals(
    active: Boolean,
    signedDistance: Int,
    pivotFractionX: Float,
    blurEnabled: Boolean,
    highlightAll: Boolean
): Modifier {
    val distance = abs(signedDistance)
    val staggerDelay = appleLowerLineStaggerDelay(signedDistance)
    val targetAlpha = when {
        active || highlightAll -> 1f
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
        animationSpec = tween(
            durationMillis = 750,
            delayMillis = staggerDelay,
            easing = AppleLyricsEasing
        ),
        label = "appleLyricLineAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(
            durationMillis = 750,
            delayMillis = staggerDelay,
            easing = AppleLyricsEasing
        ),
        label = "appleLyricLineScale"
    )

    val targetBlur = when {
        !blurEnabled -> 0.dp
        active -> 0.dp
        signedDistance < 0 -> (distance * 0.72f).coerceAtMost(3.6f).dp
        else -> (distance * 1.45f).coerceAtMost(8.5f).dp
    }
    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = tween(
            durationMillis = if (blurEnabled) 750 else 120,
            delayMillis = if (blurEnabled) staggerDelay else 0,
            easing = AppleLyricsEasing
        ),
        label = "appleLyricLineBlur"
    )

    return this
        .blur(blurRadius)
        .graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(pivotFractionX, 0.5f)
        }
}

private fun Modifier.observeLyricSwipeRight(onSwipeRight: () -> Unit): Modifier {
    return pointerInput(onSwipeRight) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var end = start
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull()
                    ?: break
                end = change.position
                finished = change.changedToUpIgnoreConsumed() || !change.pressed
            }
            val totalX = end.x - start.x
            val totalY = end.y - start.y
            if (totalX > 150f && abs(totalX) > abs(totalY) * 1.35f) {
                onSwipeRight()
            }
        }
    }
}

private fun appleLowerLineStaggerDelay(signedDistance: Int): Int {
    if (signedDistance <= 0) return 0
    val itemDistance = signedDistance.coerceAtMost(4)
    return ((25f * 0.25f) *
        (5f * itemDistance - ((itemDistance + 1f) * itemDistance) / 2f)).toInt()
}

private val LyricTextPosition.horizontalAlignment: Alignment.Horizontal
    get() = when (this) {
        LyricTextPosition.Left -> Alignment.Start
        LyricTextPosition.Center -> Alignment.CenterHorizontally
        LyricTextPosition.Right -> Alignment.End
    }

private val LyricTextPosition.textAlign: TextAlign
    get() = when (this) {
        LyricTextPosition.Left -> TextAlign.Start
        LyricTextPosition.Center -> TextAlign.Center
        LyricTextPosition.Right -> TextAlign.End
    }

private val LyricTextPosition.boxAlignment: Alignment
    get() = when (this) {
        LyricTextPosition.Left -> Alignment.CenterStart
        LyricTextPosition.Center -> Alignment.Center
        LyricTextPosition.Right -> Alignment.CenterEnd
    }

private val LyricTextPosition.pivotFractionX: Float
    get() = when (this) {
        LyricTextPosition.Left -> 0f
        LyricTextPosition.Center -> 0.5f
        LyricTextPosition.Right -> 1f
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
