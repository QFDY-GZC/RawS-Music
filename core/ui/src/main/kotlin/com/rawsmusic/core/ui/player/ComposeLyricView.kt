package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkVisibility
import com.rawsmusic.core.ui.widget.PlayerLyricsScrollDirection
import com.rawsmusic.core.ui.widget.PlayerLyricsTransitionCoordinator
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

enum class LyricTextPosition(val value: Int) {
    Left(0),
    Center(1),
    Right(2);

    companion object {
        fun from(value: Int): LyricTextPosition = entries.firstOrNull { it.value == value } ?: Left
    }
}

data class LyricScrollingHeaderViewportState(
    val visibility: PlayerLyricsArtworkVisibility,
    val visibleFraction: Float,
    val normalizedViewportPosition: Float?,
    val offscreenDistancePx: Float,
    val scrollDirection: PlayerLyricsScrollDirection,
    val isScrollInProgress: Boolean,
    val isAtTop: Boolean
)

private data class LyricScrollingHeaderSample(
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val headerOffset: Int?,
    val headerSize: Int?,
    val isScrollInProgress: Boolean
)

private data class LyricAnchorTransition(
    val sequence: Int,
    val fromIndex: Int,
    val toIndex: Int,
    val pullFollowingRows: Boolean
)

private val AppleLyricsEasing = CubicBezierEasing(0.40f, 0.10f, 0f, 1f)

private sealed interface LyricDisplayItem {
    val key: String

    data class Line(val sourceIndex: Int, val beginMs: Long) : LyricDisplayItem {
        override val key: String = "line-$beginMs-$sourceIndex"
    }

    data class Interlude(val value: LyricInterlude) : LyricDisplayItem {
        override val key: String = "interlude-${value.startMs}-${value.endMs}"
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
    karaokeGlowEnabled: Boolean = true,
    karaokeLiftEnabled: Boolean = true,
    maxPrimaryVisibleLines: Int = Int.MAX_VALUE,
    scrollingHeader: (@Composable () -> Unit)? = null,
    scrollingHeaderSpacing: Dp = 18.dp,
    onScrollingHeaderVisibilityChanged: (Boolean) -> Unit = {},
    onScrollingHeaderViewportChanged: (LyricScrollingHeaderViewportState) -> Unit = {},
    onLineClick: (Long) -> Unit = {},
    onSwipeRight: () -> Unit = {},
    onSwipeRightStart: (() -> Unit)? = null,
    onSwipeRightProgress: ((Float) -> Unit)? = null,
    onSwipeRightEnd: ((Boolean, Float) -> Unit)? = null
) {
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val interludes = remember(lines) { calculateLyricInterludes(lines) }
    val renderableIndices = remember(lines) { visibleLyricLineIndices(lines) }
    val duetLayout = remember(lines) { lines.any { it.isAlignedRight } }
    var smoothPositionMs by remember { androidx.compose.runtime.mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        val anchorPositionMs = positionMs
        val anchorFrameNs = withFrameNanos { it }
        smoothPositionMs = anchorPositionMs
        while (isPlaying) {
            val frameNs = withFrameNanos { it }
            smoothPositionMs = anchorPositionMs + (frameNs - anchorFrameNs) / 1_000_000L
        }
    }

    val playbackState by remember(lines, interludes, smoothPositionMs) {
        derivedStateOf { calculateLyricPlaybackState(lines, smoothPositionMs, interludes) }
    }
    val anchorIndex = playbackState.anchorLineIndex
    val lyricPullField = rememberLyricPullFieldState(song)
    var pendingManualSeekLineIndex by remember(song) { mutableIntStateOf(-1) }
    val compactWindowEnabled = maxPrimaryVisibleLines != Int.MAX_VALUE
    val inferredCompactLineLimit = remember(lines, displayTranslation, displayRoma) {
        immersivePrimaryLyricLineLimit(lines, displayTranslation, displayRoma)
    }
    val compactLineLimit = remember(compactWindowEnabled, maxPrimaryVisibleLines, inferredCompactLineLimit) {
        if (!compactWindowEnabled) Int.MAX_VALUE
        else maxOf(maxPrimaryVisibleLines, inferredCompactLineLimit).coerceIn(1, 5)
    }
    val visibleLineIndices = remember(renderableIndices, anchorIndex, compactLineLimit) {
        if (!compactWindowEnabled) renderableIndices
        else centeredLyricWindowIndices(renderableIndices, anchorIndex, compactLineLimit)
    }

    if (lines.isEmpty() || renderableIndices.isEmpty()) {
        LaunchedEffect(scrollingHeader) {
            if (scrollingHeader != null) {
                onScrollingHeaderVisibilityChanged(true)
                onScrollingHeaderViewportChanged(
                    LyricScrollingHeaderViewportState(
                        visibility = PlayerLyricsArtworkVisibility.Visible,
                        visibleFraction = 1f,
                        normalizedViewportPosition = 0f,
                        offscreenDistancePx = 0f,
                        scrollDirection = PlayerLyricsScrollDirection.Still,
                        isScrollInProgress = false,
                        isAtTop = true
                    )
                )
            }
        }
        Box(modifier = modifier.fillMaxSize()) {
            if (scrollingHeader != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = topPadding)) {
                    scrollingHeader()
                }
            }
            Text(
                text = song?.name?.takeIf { it.isNotBlank() } ?: "暂无歌词",
                color = secondaryColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val gestureDensity = LocalDensity.current.density
    if (compactWindowEnabled) {
        val compactLineSpacing = if (visibleLineIndices.size <= 3) 18.dp else 12.dp
        var previousCompactAnchor by remember(song) { mutableIntStateOf(anchorIndex) }
        LaunchedEffect(visibleLineIndices) {
            lyricPullField.setVisibleIndices(visibleLineIndices)
        }
        LaunchedEffect(anchorIndex, isPlaying, playbackState.activeInterlude, visibleLineIndices) {
            val previous = previousCompactAnchor
            previousCompactAnchor = anchorIndex
            val manualSeekTransition = pendingManualSeekLineIndex == anchorIndex
            if (manualSeekTransition) pendingManualSeekLineIndex = -1
            val pullFollowingRows = shouldPullFollowingLyrics(
                previousIndex = previous,
                newIndex = anchorIndex,
                manualSeekTransition = manualSeekTransition,
                isPlaying = isPlaying,
                userScrolling = false,
                hasActiveInterlude = playbackState.activeInterlude != null
            )
            if (!pullFollowingRows) {
                lyricPullField.cancel()
                return@LaunchedEffect
            }
            lyricPullField.setVisibleIndices(visibleLineIndices)
            val pullDistancePx = lyricPullField.estimateCompactPullDistancePx(
                previousAnchor = previous,
                newAnchor = anchorIndex,
                orderedRenderableIndices = renderableIndices,
                spacingPx = compactLineSpacing.value * gestureDensity
            )
            if (pullDistancePx <= 0f) {
                lyricPullField.cancel()
                return@LaunchedEffect
            }
            val frameTimeMs = withFrameNanos { it / 1_000_000L }
            lyricPullField.beginForwardPull(anchorIndex, pullDistancePx, frameTimeMs)
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { lyricPullField.updateViewportHeight(it.height) }
                .observeLyricSwipeRight(
                    onSwipeRight = onSwipeRight,
                    onSwipeRightStart = onSwipeRightStart,
                    onSwipeRightProgress = onSwipeRightProgress,
                    onSwipeRightEnd = onSwipeRightEnd,
                    density = gestureDensity
                ),
            verticalArrangement = Arrangement.spacedBy(
                space = compactLineSpacing,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val activeInterlude = playbackState.activeInterlude
            if (activeInterlude != null) {
                InstrumentalInterlude(
                    interlude = activeInterlude,
                    positionMs = smoothPositionMs,
                    active = true,
                    color = textColor,
                    textPosition = textPosition,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                )
            } else {
                visibleLineIndices.forEach { index ->
                    val line = lines[index]
                    val active = index in playbackState.activeLineIndices
                    ComposeLyricLine(
                        line = line,
                        active = active,
                        positionMs = if (active) smoothPositionMs else Long.MIN_VALUE,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        textColor = if (active || highlightAll) textColor else dimColor,
                        dimColor = dimColor,
                        secondaryColor = if (active || highlightAll) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                        fontFamily = fontFamily,
                        fontSizeSp = fontSizeSp,
                        textPosition = line.resolvedTextPosition(textPosition, duetLayout),
                        karaokeGlowEnabled = karaokeGlowEnabled,
                        karaokeLiftEnabled = karaokeLiftEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { lyricPullField.updateRowHeight(index, it.height) }
                            .lyricPullPlacement(lyricPullField.offsetPx(index))
                            .lyricLineVisuals(
                                active = active,
                                signedDistance = index - anchorIndex,
                                pivotFractionX = line.resolvedTextPosition(textPosition, duetLayout).pivotFractionX,
                                blurEnabled = blurEnabled,
                                highlightAll = highlightAll
                            )
                            .clickable {
                                pendingManualSeekLineIndex = index
                                if (index <= anchorIndex) lyricPullField.cancel()
                                scope.launch { onLineClick(line.begin) }
                            }
                    )
                }
            }
        }
        return
    }

    val displayItems = remember(lines, renderableIndices, interludes) {
        val interludeByNextLine = interludes.associateBy { it.nextLineIndex }
        buildList {
            renderableIndices.forEach { index ->
                interludeByNextLine[index]?.let { add(LyricDisplayItem.Interlude(it)) }
                add(LyricDisplayItem.Line(index, lines[index].begin))
            }
        }
    }
    val scrollTargetIndex = remember(displayItems, playbackState.activeInterlude, anchorIndex) {
        val activeInterlude = playbackState.activeInterlude
        if (activeInterlude != null) {
            displayItems.indexOfFirst { it is LyricDisplayItem.Interlude && it.value == activeInterlude }
        } else {
            displayItems.indexOfFirst { it is LyricDisplayItem.Line && it.sourceIndex == anchorIndex }
        }
    }
    val listState = rememberLazyListState()
    var listViewportHeightPx by remember(song) { mutableIntStateOf(0) }
    val anchoredBottomPadding = with(LocalDensity.current) {
        maxOf(
            bottomPadding,
            LyricAnchorSpec.requiredTrailingPaddingPx(listViewportHeightPx.toFloat()).toDp()
        )
    }
    LaunchedEffect(listState, scrollingHeader) {
        if (scrollingHeader == null) return@LaunchedEffect
        var previousPosition: Long? = null
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                null
            } else {
                val headerInfo = layoutInfo.visibleItemsInfo.firstOrNull {
                    it.key == "lyric-scrolling-header"
                }
                LyricScrollingHeaderSample(
                    viewportStartOffset = layoutInfo.viewportStartOffset,
                    viewportEndOffset = layoutInfo.viewportEndOffset,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    headerOffset = headerInfo?.offset,
                    headerSize = headerInfo?.size,
                    isScrollInProgress = listState.isScrollInProgress
                )
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { sample ->
                val currentPosition =
                    sample.firstVisibleItemIndex.toLong() * 1_000_000L +
                        sample.firstVisibleItemScrollOffset.toLong()
                val direction = when {
                    previousPosition == null -> PlayerLyricsScrollDirection.Still
                    currentPosition > previousPosition!! -> PlayerLyricsScrollDirection.Down
                    currentPosition < previousPosition!! -> PlayerLyricsScrollDirection.Up
                    else -> PlayerLyricsScrollDirection.Still
                }
                previousPosition = currentPosition

                val headerTop = sample.headerOffset
                val headerSize = sample.headerSize
                val headerBottom = if (headerTop != null && headerSize != null) {
                    headerTop + headerSize
                } else {
                    null
                }
                val visiblePixels = if (headerTop != null && headerBottom != null) {
                    (
                        minOf(headerBottom, sample.viewportEndOffset) -
                            maxOf(headerTop, sample.viewportStartOffset)
                        ).coerceAtLeast(0)
                } else {
                    0
                }
                val visibleFraction = if (headerSize != null && headerSize > 0) {
                    visiblePixels.toFloat() / headerSize.toFloat()
                } else {
                    0f
                }
                // Accept a real item only while its normalized position remains inside the open
                // (-0.967, 0.967) interval.  Map the header center into [-1, 1] at the two
                // no-overlap boundaries; unlike a visible-area threshold, this keeps every real
                // partially visible candidate and rejects the first fully off-screen frame.
                val normalizedViewportPosition = if (
                    headerTop != null && headerBottom != null && headerSize != null && headerSize > 0
                ) {
                    val viewportCenter =
                        (sample.viewportStartOffset + sample.viewportEndOffset) * 0.5f
                    val headerCenter = (headerTop + headerBottom) * 0.5f
                    val noOverlapHalfDistance =
                        ((sample.viewportEndOffset - sample.viewportStartOffset) + headerSize) * 0.5f
                    (headerCenter - viewportCenter) /
                        noOverlapHalfDistance.coerceAtLeast(1f)
                } else {
                    null
                }
                val visibility = when {
                    visiblePixels > 0 -> PlayerLyricsArtworkVisibility.Visible
                    headerBottom != null && headerBottom <= sample.viewportStartOffset ->
                        PlayerLyricsArtworkVisibility.AboveViewport
                    headerTop != null && headerTop >= sample.viewportEndOffset ->
                        PlayerLyricsArtworkVisibility.BelowViewport
                    sample.firstVisibleItemIndex > 0 ->
                        PlayerLyricsArtworkVisibility.AboveViewport
                    else -> PlayerLyricsArtworkVisibility.Missing
                }
                val viewportHeight =
                    (sample.viewportEndOffset - sample.viewportStartOffset).coerceAtLeast(0)
                val offscreenDistance = when (visibility) {
                    PlayerLyricsArtworkVisibility.AboveViewport -> when {
                        headerBottom != null ->
                            (sample.viewportStartOffset - headerBottom).coerceAtLeast(0).toFloat()
                        else ->
                            (sample.firstVisibleItemScrollOffset + viewportHeight)
                                .coerceAtLeast(0)
                                .toFloat()
                    }
                    PlayerLyricsArtworkVisibility.BelowViewport -> when {
                        headerTop != null ->
                            (headerTop - sample.viewportEndOffset).coerceAtLeast(0).toFloat()
                        else -> viewportHeight.toFloat()
                    }
                    else -> 0f
                }
                val state = LyricScrollingHeaderViewportState(
                    visibility = visibility,
                    visibleFraction = visibleFraction.coerceIn(0f, 1f),
                    normalizedViewportPosition = normalizedViewportPosition,
                    offscreenDistancePx = offscreenDistance,
                    scrollDirection = direction,
                    isScrollInProgress = sample.isScrollInProgress,
                    isAtTop = sample.firstVisibleItemIndex == 0 &&
                        sample.firstVisibleItemScrollOffset <= 1
                )
                onScrollingHeaderVisibilityChanged(
                    state.visibility == PlayerLyricsArtworkVisibility.Visible
                )
                onScrollingHeaderViewportChanged(state)
            }
    }
    val scrollingHeaderItemCount = if (scrollingHeader == null) 0 else 1
    val lazyScrollTargetIndex = if (scrollTargetIndex < 0) -1 else scrollTargetIndex + scrollingHeaderItemCount
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    var previousObservedAnchor by remember(song) { mutableIntStateOf(anchorIndex) }
    var nextTransitionSequence by remember(song) { mutableIntStateOf(0) }
    var pendingAnchorTransition by remember(song) {
        mutableStateOf(
            LyricAnchorTransition(
                sequence = 0,
                fromIndex = -1,
                toIndex = anchorIndex,
                pullFollowingRows = false
            )
        )
    }
    LaunchedEffect(userDragging) {
        if (userDragging) {
            userScrolling = true
            lyricPullField.cancel()
        } else {
            kotlinx.coroutines.delay(1_800L)
            userScrolling = false
        }
    }
    LaunchedEffect(isPlaying, playbackState.activeInterlude) {
        if (!isPlaying || playbackState.activeInterlude != null) {
            lyricPullField.cancel()
        }
    }
    LaunchedEffect(anchorIndex, isPlaying, userScrolling, playbackState.activeInterlude) {
        if (anchorIndex == previousObservedAnchor) return@LaunchedEffect
        val previous = previousObservedAnchor
        previousObservedAnchor = anchorIndex
        val manualSeekTransition = pendingManualSeekLineIndex == anchorIndex
        if (manualSeekTransition) pendingManualSeekLineIndex = -1
        nextTransitionSequence += 1
        pendingAnchorTransition = LyricAnchorTransition(
            sequence = nextTransitionSequence,
            fromIndex = previous,
            toIndex = anchorIndex,
            pullFollowingRows = shouldPullFollowingLyrics(
                previousIndex = previous,
                newIndex = anchorIndex,
                manualSeekTransition = manualSeekTransition,
                isPlaying = isPlaying,
                userScrolling = userScrolling,
                hasActiveInterlude = playbackState.activeInterlude != null
            )
        )
    }
    LaunchedEffect(listState, displayItems, scrollingHeaderItemCount) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val displayIndex = info.index - scrollingHeaderItemCount
                (displayItems.getOrNull(displayIndex) as? LyricDisplayItem.Line)?.sourceIndex
            }
        }.distinctUntilChanged().collect { visibleSourceIndices ->
            lyricPullField.setVisibleIndices(visibleSourceIndices)
        }
    }
    LaunchedEffect(
        lazyScrollTargetIndex,
        userScrolling,
        displayItems.size,
        pendingAnchorTransition.sequence,
        listViewportHeightPx
    ) {
        if (lazyScrollTargetIndex < 0 || userScrolling || listViewportHeightPx <= 0) {
            return@LaunchedEffect
        }
        // lazyScrollTargetIndex is recomputed one composition before the transition record is
        // published. Never consume that intermediate frame; wait for the matching anchor event.
        if (pendingAnchorTransition.toIndex != anchorIndex) return@LaunchedEffect

        val viewportHeight = snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.filter { it > 0 }.first()
        lyricPullField.updateViewportHeight(viewportHeight)
        // PowerList exposes a 0.25 follow position. Keep the current lyric at the same viewport
        // quarter instead of using the old approximate 0.24 value.
        val targetOffset = LyricAnchorSpec.targetOffsetPx(
            viewportStartOffset = listState.layoutInfo.viewportStartOffset,
            viewportEndOffset = listState.layoutInfo.viewportEndOffset
        )

        // Let the new active-row scene and dynamic trailing padding participate in one layout
        // before measuring the movement. Marking a transition handled before this frame was the
        // Step 20 regression: a stale geometry read could permanently consume the only request.
        withFrameNanos { }
        val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == lazyScrollTargetIndex
        }
        if (visibleItem != null) {
            val visibleSourceIndices = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val displayIndex = info.index - scrollingHeaderItemCount
                (displayItems.getOrNull(displayIndex) as? LyricDisplayItem.Line)?.sourceIndex
            }
            lyricPullField.setVisibleIndices(visibleSourceIndices)
            val scrollDeltaPx = visibleItem.offset - targetOffset
            val movementPx = abs(scrollDeltaPx)
            val transitionMatchesAnchor = pendingAnchorTransition.toIndex == anchorIndex &&
                pendingAnchorTransition.fromIndex >= 0 &&
                anchorIndex > pendingAnchorTransition.fromIndex
            val pullFollowingRows = pendingAnchorTransition.pullFollowingRows &&
                transitionMatchesAnchor && scrollDeltaPx > 0f
            if (pullFollowingRows) {
                val frameTimeMs = withFrameNanos { it / 1_000_000L }
                lyricPullField.beginForwardPull(anchorIndex, movementPx, frameTimeMs)
                listState.animateScrollBy(
                    value = scrollDeltaPx,
                    animationSpec = tween(
                        durationMillis = LyricPullSpec.DURATION_MS,
                        easing = Easing { progress ->
                            LyricPullSpec.interpolate(progress, movementPx)
                        }
                    )
                )
            } else {
                lyricPullField.cancel()
                listState.animateScrollBy(
                    value = scrollDeltaPx,
                    animationSpec = tween(durationMillis = 550, easing = AppleLyricsEasing)
                )
            }
        } else {
            lyricPullField.cancel()
            listState.animateScrollToItem(
                index = lazyScrollTargetIndex,
                scrollOffset = -targetOffset.roundToInt()
            )
        }

        // Item measurement, translation/roma visibility and end-of-list clamping may settle one
        // frame after the main animation. Re-read the real item rect and correct any residual; the
        // transition is considered complete only after the requested line actually reaches the
        // follow anchor.
        repeat(LyricAnchorSpec.MAX_CORRECTION_PASSES) {
            withFrameNanos { }
            if (userScrolling || pendingAnchorTransition.toIndex != anchorIndex) {
                lyricPullField.cancel()
                return@LaunchedEffect
            }
            val currentTargetOffset = LyricAnchorSpec.targetOffsetPx(
                viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                viewportEndOffset = listState.layoutInfo.viewportEndOffset
            )
            val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == lazyScrollTargetIndex
            } ?: return@repeat
            val residualPx = currentItem.offset - currentTargetOffset
            if (abs(residualPx) <= LyricAnchorSpec.CORRECTION_THRESHOLD_PX) {
                return@LaunchedEffect
            }
            listState.animateScrollBy(
                value = residualPx,
                animationSpec = tween(durationMillis = 120, easing = AppleLyricsEasing)
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                listViewportHeightPx = it.height
                lyricPullField.updateViewportHeight(it.height)
            }
            .observeLyricSwipeRight(
                onSwipeRight = onSwipeRight,
                onSwipeRightStart = onSwipeRightStart,
                onSwipeRightProgress = onSwipeRightProgress,
                onSwipeRightEnd = onSwipeRightEnd,
                density = gestureDensity
            ),
        contentPadding = PaddingValues(top = topPadding, bottom = anchoredBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (scrollingHeader != null) {
            item(key = "lyric-scrolling-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    scrollingHeader()
                    Spacer(Modifier.height(scrollingHeaderSpacing))
                }
            }
        }
        items(
            count = displayItems.size,
            key = { displayItems[it].key }
        ) { itemIndex ->
            when (val item = displayItems[itemIndex]) {
                is LyricDisplayItem.Interlude -> InstrumentalInterlude(
                    interlude = item.value,
                    positionMs = if (item.value == playbackState.activeInterlude) smoothPositionMs else Long.MIN_VALUE,
                    active = item.value == playbackState.activeInterlude,
                    color = textColor,
                    textPosition = textPosition,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                )
                is LyricDisplayItem.Line -> {
                    val index = item.sourceIndex
                    val line = lines[index]
                    val active = index in playbackState.activeLineIndices
                    val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                    ComposeLyricLine(
                        line = line,
                        active = active,
                        positionMs = if (active) smoothPositionMs else Long.MIN_VALUE,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        textColor = if (active || highlightAll) textColor else dimColor,
                        dimColor = dimColor,
                        secondaryColor = if (active || highlightAll) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                        fontFamily = fontFamily,
                        fontSizeSp = fontSizeSp,
                        textPosition = resolvedPosition,
                        karaokeGlowEnabled = karaokeGlowEnabled,
                        karaokeLiftEnabled = karaokeLiftEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { lyricPullField.updateRowHeight(index, it.height) }
                            .lyricPullPlacement(
                                if (userScrolling) 0f else lyricPullField.offsetPx(index)
                            )
                            .lyricLineVisuals(
                                active = active,
                                signedDistance = index - anchorIndex,
                                pivotFractionX = resolvedPosition.pivotFractionX,
                                blurEnabled = blurEnabled && !userScrolling,
                                highlightAll = highlightAll
                            )
                            .clickable {
                                pendingManualSeekLineIndex = index
                                userScrolling = false
                                // Retarget an in-flight forward pull from its current item
                                // positions. Do not snap it to zero before a manual forward seek.
                                if (index <= anchorIndex) lyricPullField.cancel()
                                scope.launch { onLineClick(line.begin) }
                            }
                    )
                }
            }
        }
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
    karaokeGlowEnabled: Boolean,
    karaokeLiftEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val primarySize = fontSizeSp.coerceIn(24, 40)
    val primaryLineHeight = primarySize + 6
    val secondarySize = (primarySize * 0.62f).toInt().coerceIn(15, 25)
    val secondaryLineHeight = secondarySize + 6
    val mainText = line.text.orEmpty()
    val backgroundText = line.secondary.orEmpty()
    val backgroundOnly = mainText.isBlank() && backgroundText.isNotBlank()
    val displayedMain = if (backgroundOnly) backgroundText else mainText.ifBlank { "♪" }
    val mainWords = if (backgroundOnly) line.secondaryWords.orEmpty() else line.words.orEmpty()
    val align = textPosition.horizontalAlignment
    val textAlign = textPosition.textAlign
    val secondaryStyleModifier = Modifier.fillMaxWidth()

    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = align
    ) {
        if (displayRoma && !line.roma.isNullOrBlank()) {
            Text(
                text = line.roma.orEmpty(),
                color = secondaryColor.copy(alpha = secondaryColor.alpha * 0.90f),
                fontSize = secondarySize.sp,
                lineHeight = secondaryLineHeight.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = secondaryStyleModifier
            )
            Spacer(Modifier.height(5.dp))
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = textPosition.boxAlignment
        ) {
            if (active && mainWords.isNotEmpty()) {
                KaraokeTimedText(
                    text = displayedMain,
                    words = mainWords,
                    lineEndMs = effectiveSingleLineEnd(line, mainWords),
                    positionMs = positionMs,
                    highlightColor = textColor,
                    dimColor = dimColor,
                    fontSize = primarySize.sp,
                    lineHeight = primaryLineHeight.sp,
                    textAlign = textAlign,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    glowEnabled = karaokeGlowEnabled,
                    liftEnabled = karaokeLiftEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = displayedMain,
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

        if (displayTranslation && !line.translation.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = line.translation.orEmpty(),
                color = secondaryColor,
                fontSize = secondarySize.sp,
                lineHeight = secondaryLineHeight.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = secondaryStyleModifier
            )
        }

        if (!backgroundOnly && backgroundText.isNotBlank()) {
            val backgroundWords = line.secondaryWords.orEmpty()
            val backgroundStart = backgroundWords.minOfOrNull { it.begin } ?: line.begin
            val backgroundEnd = backgroundWords.maxOfOrNull { it.end }
                ?: line.end.takeIf { it > backgroundStart }
                ?: (backgroundStart + 3_000L)
            val backgroundActive = active && positionMs in backgroundStart until backgroundEnd
            AnimatedVisibility(
                visible = backgroundActive,
                enter = expandVertically(spring(dampingRatio = 0.72f, stiffness = 340f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.90f, stiffness = 460f)) + fadeOut()
            ) {
                Column(horizontalAlignment = align) {
                    Spacer(Modifier.height(7.dp))
                    if (backgroundWords.isNotEmpty()) {
                        KaraokeTimedText(
                            text = backgroundText,
                            words = backgroundWords,
                            lineEndMs = backgroundEnd,
                            positionMs = positionMs,
                            highlightColor = textColor.copy(alpha = 0.78f),
                            dimColor = dimColor.copy(alpha = 0.72f),
                            fontSize = secondarySize.sp,
                            lineHeight = secondaryLineHeight.sp,
                            textAlign = textAlign,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fontFamily,
                            glowEnabled = karaokeGlowEnabled,
                            liftEnabled = karaokeLiftEnabled,
                            modifier = secondaryStyleModifier
                        )
                    } else {
                        Text(
                            text = backgroundText,
                            color = secondaryColor,
                            fontSize = secondarySize.sp,
                            lineHeight = secondaryLineHeight.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fontFamily,
                            textAlign = textAlign,
                            modifier = secondaryStyleModifier
                        )
                    }
                    if (displayTranslation && !line.backgroundTranslation.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = line.backgroundTranslation.orEmpty(),
                            color = secondaryColor.copy(alpha = secondaryColor.alpha * 0.84f),
                            fontSize = secondarySize.sp,
                            lineHeight = secondaryLineHeight.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = fontFamily,
                            textAlign = textAlign,
                            modifier = secondaryStyleModifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstrumentalInterlude(
    interlude: LyricInterlude,
    positionMs: Long,
    active: Boolean,
    color: Color,
    textPosition: LyricTextPosition,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(spring(dampingRatio = 0.78f, stiffness = 360f)) + fadeIn(),
        exit = shrinkVertically(spring(dampingRatio = 0.90f, stiffness = 480f)) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = textPosition.boxAlignment
        ) {
            val duration = (interlude.endMs - interlude.startMs).coerceAtLeast(1L)
            val elapsed = (positionMs - interlude.startMs).coerceAtLeast(0L)
            val pulseScale = 1f + 0.1f * sin((elapsed.toFloat() / 4_000f) * 2f * PI.toFloat())
            val progress = (elapsed.toFloat() / (duration - 800L).coerceAtLeast(1L)).coerceIn(0f, 1f)
            Row {
                repeat(3) { index ->
                    val dotProgress = ((progress - index / 3f) * 3f).coerceIn(0f, 1f)
                    val dotAlpha by animateFloatAsState(
                        targetValue = 0.18f + 0.67f * dotProgress,
                        animationSpec = spring(dampingRatio = 0.90f, stiffness = 440f),
                        label = "instrumentalDot$index"
                    )
                    Canvas(Modifier.size(16.dp)) {
                        drawCircle(color.copy(alpha = dotAlpha), radius = 5.dp.toPx() * pulseScale)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.lyricPullPlacement(offsetPx: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, offsetPx.roundToInt())
    }
}

@Composable
private fun Modifier.lyricLineVisuals(
    active: Boolean,
    signedDistance: Int,
    pivotFractionX: Float,
    blurEnabled: Boolean,
    highlightAll: Boolean
): Modifier {
    val distance = abs(signedDistance)
    val alpha by animateFloatAsState(
        targetValue = when {
            active || highlightAll -> 1f
            distance == 1 -> 0.42f
            distance == 2 -> 0.28f
            else -> 0.16f
        },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
        label = "lyricLineAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 340f),
        label = "lyricLineScale"
    )
    val blurRadius by animateDpAsState(
        targetValue = when {
            !blurEnabled || highlightAll || active || distance < 2 -> 0.dp
            else -> (2f + distance.coerceAtMost(4)).dp
        },
        animationSpec = spring(dampingRatio = 0.90f, stiffness = 360f),
        label = "lyricLineBlur"
    )
    // Keep the visual layer inside lyricPullPlacement. When blur owns an outer layer and pull is
    // applied inside it, the layer's rectangular bounds clip the last translation row of
    // Keep pronunciation, primary text, and translation inside one moving row rectangle.
    return graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
        translationY = signedDistance.coerceIn(-4, 4) * -2f * density
        transformOrigin = TransformOrigin(pivotFractionX, 0.5f)
        clip = false
    }.blur(blurRadius)
}

private fun Modifier.observeLyricSwipeRight(
    onSwipeRight: () -> Unit,
    onSwipeRightStart: (() -> Unit)?,
    onSwipeRightProgress: ((Float) -> Unit)?,
    onSwipeRightEnd: ((Boolean, Float) -> Unit)?,
    density: Float
): Modifier = pointerInput(
    onSwipeRight,
    onSwipeRightStart,
    onSwipeRightProgress,
    onSwipeRightEnd,
    density
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        val pointerId = down.id
        val start = down.position
        val velocityTracker = VelocityTracker().apply {
            addPosition(down.uptimeMillis, down.position)
        }
        var accepted = false
        var rejected = false
        var lastDx = 0f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: event.changes.firstOrNull()
                ?: break
            velocityTracker.addPosition(change.uptimeMillis, change.position)

            val dx = change.position.x - start.x
            val dy = change.position.y - start.y
            lastDx = dx

            if (!accepted && !rejected &&
                (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)
            ) {
                if (dx > 0f && abs(dx) > abs(dy) * 1.25f) {
                    accepted = true
                    onSwipeRightStart?.invoke()
                } else {
                    rejected = true
                }
            }

            if (accepted) {
                val travelDistance = size.width.toFloat().coerceAtLeast(1f)
                onSwipeRightProgress?.invoke((dx / travelDistance).coerceIn(0f, 1f))
                change.consume()
            }

            if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                if (accepted) {
                    val travelDistance = size.width.toFloat().coerceAtLeast(1f)
                    val progress = (lastDx / travelDistance).coerceIn(0f, 1f)
                    val velocityX = velocityTracker.calculateVelocity().x
                    val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                        progress = progress,
                        velocityPxPerSecond = velocityX,
                        density = density,
                        expectedVelocitySign = 1
                    )
                    val settleVelocity = PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                        progress = progress,
                        commit = commit,
                        velocityPxPerSecond = velocityX,
                        travelDistancePx = travelDistance,
                        expectedVelocitySign = 1
                    )
                    if (onSwipeRightEnd != null) {
                        onSwipeRightEnd(commit, settleVelocity)
                    } else if (commit) {
                        onSwipeRight()
                    }
                }
                break
            }
        }
    }
}

private fun IRichLyricLine.resolvedTextPosition(
    preferred: LyricTextPosition,
    duetLayout: Boolean
): LyricTextPosition = when {
    !duetLayout -> preferred
    isAlignedRight -> LyricTextPosition.Right
    else -> LyricTextPosition.Left
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
        displayTranslation && (!line.translation.isNullOrBlank() || !line.backgroundTranslation.isNullOrBlank()) ||
            displayRoma && !line.roma.isNullOrBlank() ||
            !line.secondary.isNullOrBlank()
    }
    return if (hasSecondaryText) 3 else 5
}

private fun centeredLyricWindowIndices(
    availableIndices: List<Int>,
    currentIndex: Int,
    maxLines: Int
): List<Int> {
    if (availableIndices.isEmpty()) return emptyList()
    val count = maxLines.coerceIn(1, availableIndices.size)
    val anchorPosition = availableIndices.indexOf(currentIndex).takeIf { it >= 0 } ?: 0
    val start = (anchorPosition - count / 2)
        .coerceIn(0, (availableIndices.size - count).coerceAtLeast(0))
    return availableIndices.subList(start, start + count)
}
