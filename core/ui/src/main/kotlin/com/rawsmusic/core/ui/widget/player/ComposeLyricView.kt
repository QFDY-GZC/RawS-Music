package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkVisibility
import com.rawsmusic.core.ui.widget.PlayerLyricsScrollDirection
import com.rawsmusic.core.ui.widget.PlayerLyricsTransitionCoordinator
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricFontManager
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val AM_LYRIC_BG_GEOMETRY_EASING = CubicBezierEasing(0.4f, 0.1f, 0.0f, 1.0f)
private val AM_LYRIC_BG_ALPHA_OUT_EASING = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1.0f)
private const val AM_LYRIC_BG_HEIGHT_MS = 750
private const val AM_LYRIC_BG_SCALE_MS = 500
private const val AM_LYRIC_BG_ALPHA_OUT_MS = 250
private const val AM_LYRIC_BG_ENTER_SCALE_DELAY_MS = 638 // round(750 * 0.85)
private const val AM_LYRIC_BG_EXIT_HEIGHT_DELAY_MS = 50 // round(250 * 0.2)

private data class ReferenceLyricSceneStyle(
    val regularTextScale: Float,
    val activeTextScale: Float,
    val horizontalInsetDeltaSp: Float,
    val regularVerticalMarginSp: Float,
    val activeVerticalMarginSp: Float,
)

/** Values from Reference's three ItemLyricsTitle scene styles. */
private fun ReferenceLyricSceneStyle(scalePercent: Int): ReferenceLyricSceneStyle {
    val scale = scalePercent.coerceIn(75, 130)
    return if (scale <= 100) {
        val fraction = (scale - 75) / 25f
        ReferenceLyricSceneStyle(
            regularTextScale = 0.65f + 0.35f * fraction,
            activeTextScale = 0.85f + 0.25f * fraction,
            // 41.5sp in the small scene and 17sp in the normal scene. The caller's normal
            // inset is retained as the baseline so custom lyric-page margins still work.
            horizontalInsetDeltaSp = 24.5f * (1f - fraction),
            regularVerticalMarginSp = 11f + 2f * fraction,
            activeVerticalMarginSp = 13f * fraction,
        )
    } else {
        val fraction = (scale - 100) / 30f
        ReferenceLyricSceneStyle(
            regularTextScale = 1f + 0.2f * fraction,
            activeTextScale = 1.1f + 0.2f * fraction,
            horizontalInsetDeltaSp = -3.5f * fraction,
            regularVerticalMarginSp = 13f + 4f * fraction,
            activeVerticalMarginSp = 13f * (1f - fraction),
        )
    }
}

private fun ReferenceLyricSceneStyle.fontSizeSp(baseFontSizeSp: Int, active: Boolean): Int =
    (baseFontSizeSp * if (active) activeTextScale else regularTextScale)
        .roundToInt()
        .coerceAtLeast(1)

private fun ReferenceLyricSceneStyle.stableLayoutFontSizeSp(baseFontSizeSp: Int): Int =
    fontSizeSp(baseFontSizeSp, active = true)

private fun ReferenceLyricSceneStyle.stableInactiveScale(): Float =
    LyricSceneScalePolicy.inactiveHolderScale(
        regularTextScale = regularTextScale,
        activeTextScale = activeTextScale,
    )

private fun ReferenceLyricSceneStyle.horizontalPadding(base: Dp): Dp =
    (base.value + horizontalInsetDeltaSp).coerceAtLeast(0f).dp

private fun ReferenceLyricSceneStyle.verticalMargin(active: Boolean): Dp =
    (if (active) activeVerticalMarginSp else regularVerticalMarginSp).dp

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

private val LyricFollowEasing = CubicBezierEasing(0.40f, 0.10f, 0f, 1f)

private fun lyricAnchorIntervalMs(
    lines: List<IRichLyricLine>,
    fromIndex: Int,
    toIndex: Int,
): Long? {
    if (fromIndex !in lines.indices || toIndex !in lines.indices || fromIndex == toIndex) return null
    return abs(effectiveLineStart(lines[toIndex]) - effectiveLineStart(lines[fromIndex]))
}
private sealed interface LyricDisplayItem {
    val key: String

    data class Line(val sourceIndex: Int, val beginMs: Long) : LyricDisplayItem {
        override val key: String = "line-$beginMs-$sourceIndex"
    }

    data class Interlude(val value: LyricInterlude) : LyricDisplayItem {
        override val key: String = "interlude-${value.startMs}-${value.endMs}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    primaryFontSizeRange: IntRange = 24..40,
    secondaryFontSizeRange: IntRange = 15..25,
    lineHorizontalPadding: Dp = 28.dp,
    compactLineSpacing: Dp? = null,
    enforceCompactInferredLineLimit: Boolean = true,
    maxPrimaryVisibleLines: Int = Int.MAX_VALUE,
    scrollingHeader: (@Composable () -> Unit)? = null,
    scrollingHeaderSpacing: Dp = 18.dp,
    onScrollingHeaderVisibilityChanged: (Boolean) -> Unit = {},
    onScrollingHeaderViewportChanged: (LyricScrollingHeaderViewportState) -> Unit = {},
    onLineClick: (Long) -> Unit = {},
    onDoubleTap: (() -> Unit)? = null,
    zoomGestureEnabled: Boolean = true,
    onSwipeRight: () -> Unit = {},
    swipeRightGestureEnabled: Boolean = true,
    onSwipeRightStart: (() -> Unit)? = null,
    onSwipeRightProgress: ((Float) -> Unit)? = null,
    onSwipeRightEnd: ((Boolean, Float) -> Unit)? = null
) {
    val lyricFontRevision by LyricFontManager.revision.collectAsState()
    val configuredFontPath = AppPreferences.LyricFont.fontPath
    val configuredFontWeight = AppPreferences.LyricFont.fontWeight
    val configuredFontScale = AppPreferences.LyricFont.fontScale
    var effectiveFontScale by remember(song) { mutableIntStateOf(configuredFontScale) }
    LaunchedEffect(configuredFontScale) {
        effectiveFontScale = configuredFontScale
    }
    val latestConfiguredFontScale = rememberUpdatedState(configuredFontScale)
    val configuredFontFamily = remember(
        lyricFontRevision,
        configuredFontPath,
        configuredFontWeight
    ) {
        if (configuredFontPath.isBlank()) {
            null
        } else {
            runCatching {
                FontFamily(Font(File(configuredFontPath), FontWeight(configuredFontWeight)))
            }.getOrNull()
        }
    }
    val resolvedFontFamily = fontFamily ?: configuredFontFamily
    val lyricSceneStyle = remember(effectiveFontScale, lyricFontRevision) {
        ReferenceLyricSceneStyle(effectiveFontScale)
    }
    val resolvedLineHorizontalPadding = remember(lineHorizontalPadding, lyricSceneStyle) {
        lyricSceneStyle.horizontalPadding(lineHorizontalPadding)
    }
    val resolvedFontSizeSp = remember(fontSizeSp, effectiveFontScale, lyricFontRevision) {
        (fontSizeSp * effectiveFontScale / 100f).roundToInt().coerceAtLeast(1)
    }
    val lines = remember(song) { song?.lyrics.orEmpty() }
    val interludes = remember(lines) { calculateLyricInterludes(lines) }
    val lyricTimeline = remember(lines, interludes) { LyricTimelineIndex(lines, interludes) }
    val renderableIndices = remember(lines) { visibleLyricLineIndices(lines) }
    val duetLayout = remember(lines) { lines.any { it.isAlignedRight } }
    val lyricDirectRenderClock = rememberLyricDirectRenderClock(
        positionMs = positionMs,
        // One shared Choreographer callback advances the lyric render clock. Active DrawModifierNode(s)
        // subscribe as lightweight listeners; the clock never publishes per-vsync Snapshot State.
        isPlaying = isPlaying && karaokeLiftEnabled,
        durationMs = song?.duration ?: 0L,
    )
    val playbackPositionMs = positionMs.coerceAtLeast(0L)
    val latestPlaybackPositionMs = rememberUpdatedState(playbackPositionMs)
    // Audio engines can publish position at block cadence. The list does not consume wordProgress,
    // so keep only layout ownership semantics in composition and preserve object identity while
    // those semantics are unchanged. Ordinary karaoke motion reads the direct render clock only.
    val computedLayoutPlaybackState = lyricTimeline.layoutPlaybackStateAt(playbackPositionMs)
    val playbackState = remember(computedLayoutPlaybackState) { computedLayoutPlaybackState }
    val anchorIndex = playbackState.anchorLineIndex
    val latestAnchorIndex = rememberUpdatedState(anchorIndex)
    val lyricPullField = rememberLyricPullFieldState(song)
    val lyricZoomState = rememberLyricZoomTransitionState(song)
    val lyricFloatMotionActive = isPlaying && karaokeLiftEnabled &&
        playbackState.anyActive { index ->
            lines.getOrNull(index)?.let { line ->
                line.words.orEmpty().isNotEmpty() || line.secondaryWords.orEmpty().isNotEmpty()
            } == true
        }
    LyricMotionFrameRateHint(
        enabled = lyricFloatMotionActive || lyricZoomState.active || lyricZoomState.settling,
    )
    var pendingManualSeekLineIndex by remember(song) { mutableIntStateOf(-1) }
    val compactWindowEnabled = maxPrimaryVisibleLines != Int.MAX_VALUE
    val inferredCompactLineLimit = remember(lines, displayTranslation, displayRoma) {
        immersivePrimaryLyricLineLimit(lines, displayTranslation, displayRoma)
    }
    val compactLineLimit = remember(
        compactWindowEnabled,
        maxPrimaryVisibleLines,
        inferredCompactLineLimit,
        enforceCompactInferredLineLimit
    ) {
        if (!compactWindowEnabled) Int.MAX_VALUE
        else if (enforceCompactInferredLineLimit) {
            maxOf(maxPrimaryVisibleLines, inferredCompactLineLimit).coerceIn(1, 5)
        } else {
            maxPrimaryVisibleLines.coerceIn(1, 5)
        }
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
                text = song?.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.player_no_lyric),
                color = secondaryColor,
                fontSize = 18.sp,
                fontFamily = resolvedFontFamily,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val gestureDensity = LocalDensity.current.density
    val doubleTapObserver = Modifier.observeLyricDoubleTap(onDoubleTap)
    val displayItems = remember(lines, renderableIndices, interludes) {
        val interludeByNextLine = interludes.associateBy { it.nextLineIndex }
        buildList {
            renderableIndices.forEach { index ->
                interludeByNextLine[index]?.let { add(LyricDisplayItem.Interlude(it)) }
                add(LyricDisplayItem.Line(index, lines[index].begin))
            }
        }
    }
    // Freeze only transition-only holders. Real lyric rows keep their normal karaoke clock; hidden
    // endpoint layouts must not wake up on every playback frame while pinch owns the scene.
    // `active` can be cancelled and re-entered within one pointer frame. Use the gesture
    // generation instead of the boolean as the key, otherwise a second pinch can reuse the
    // previous gesture's lyric clock/highlight snapshot and the entering group jumps.
    val zoomSnapshotPositionMs = remember(lyricZoomState.sessionGeneration) { playbackPositionMs }
    val zoomSnapshotActiveLineIndices = remember(lyricZoomState.sessionGeneration) {
        playbackState.activeLineIndicesList
    }
    val zoomSnapshotInterlude = remember(lyricZoomState.sessionGeneration) { playbackState.activeInterlude }
    if (compactWindowEnabled) {
        val resolvedCompactLineSpacing = compactLineSpacing
            ?: if (visibleLineIndices.size <= 3) 18.dp else 12.dp
        var previousCompactAnchor by remember(song) { mutableIntStateOf(anchorIndex) }
        val compactStackMotion = rememberLyricStackMotionState(song)
        LaunchedEffect(anchorIndex, isPlaying, playbackState.activeInterlude, visibleLineIndices, lyricZoomState.active) {
            if (lyricZoomState.active) {
                lyricPullField.cancel()
                compactStackMotion.cancel()
                return@LaunchedEffect
            }
            val previous = previousCompactAnchor
            previousCompactAnchor = anchorIndex
            val manualSeekTransition = pendingManualSeekLineIndex == anchorIndex
            if (manualSeekTransition) pendingManualSeekLineIndex = -1
            val transitionDistancePx = lyricPullField.estimateCompactTransitionDistancePx(
                previousAnchor = previous,
                newAnchor = anchorIndex,
                orderedRenderableIndices = renderableIndices,
                spacingPx = resolvedCompactLineSpacing.value * gestureDensity
            )
            val shouldAnimate = transitionDistancePx != 0f &&
                playbackState.activeInterlude == null &&
                (isPlaying || manualSeekTransition)
            if (!shouldAnimate) {
                if (!lyricPullField.running) lyricPullField.cancel()
                compactStackMotion.cancel()
                return@LaunchedEffect
            }
            if (transitionDistancePx > 0f) {
                lyricPullField.setVisibleIndices(visibleLineIndices)
                val frameTimeMs = withFrameNanos { it / 1_000_000L }
                lyricPullField.beginForwardPull(
                    anchorIndex = anchorIndex,
                    pullDistancePx = transitionDistancePx,
                    frameTimeMs = frameTimeMs,
                )
            } else {
                lyricPullField.cancel()
            }
            val motion = lyricFollowMotion(
                intervalMs = lyricAnchorIntervalMs(lines, previous, anchorIndex),
                isInterlude = playbackState.activeInterlude != null,
            )
            compactStackMotion.beginTransition(transitionDistancePx, motion)
        }

        val latestCompactZoomCommit = rememberUpdatedState<suspend (Int) -> Unit> { targetScale ->
            val liveLayoutBefore = lyricZoomState.liveLayoutRevision
            effectiveFontScale = targetScale
            if (targetScale != latestConfiguredFontScale.value) {
                LyricFontManager.setFontScale(targetScale)
            }
            var readyFrames = 0
            while (lyricZoomState.liveLayoutRevision <= liveLayoutBefore && readyFrames < 8) {
                withFrameNanos { }
                readyFrames++
            }
            // CompactLyricViewport centers its anchor by construction. Keep the completed overlay
            // for one measured target frame and release it without introducing another settle.
            withFrameNanos { }
            lyricZoomState.prepareTargetLayoutHandoff(targetScale)
        }
        val compactZoomModifier = if (zoomGestureEnabled) {
            Modifier.lyricLayoutZoomGesture(
                zoomState = lyricZoomState,
                currentScale = { latestConfiguredFontScale.value },
                currentAnchorIndex = { latestAnchorIndex.value },
                onGestureCaptured = {
                    lyricPullField.cancel()
                    scope.launch {
                        compactStackMotion.cancel()
                    }
                },
                onCommitScale = { targetScale -> latestCompactZoomCommit.value(targetScale) },
            )
        } else {
            Modifier
        }
        val compactTransitionAnchorIndex = if (lyricZoomState.active) {
            lyricZoomState.frozenAnchorIndex().takeIf { it >= 0 } ?: anchorIndex
        } else {
            anchorIndex
        }
        val compactAnchorChildIndex = if (playbackState.activeInterlude != null) {
            0
        } else {
            visibleLineIndices.indexOf(anchorIndex).coerceAtLeast(0)
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .then(compactZoomModifier)
                .then(doubleTapObserver)
                .onSizeChanged { lyricPullField.updateViewportHeight(it.height) }
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    lyricZoomState.updateViewportWindowBounds(bounds.top, bounds.bottom)
                }
                .then(
                    if (swipeRightGestureEnabled) {
                        Modifier.observeLyricSwipeRight(
                            onSwipeRight = onSwipeRight,
                            onSwipeRightStart = onSwipeRightStart,
                            onSwipeRightProgress = onSwipeRightProgress,
                            onSwipeRightEnd = onSwipeRightEnd,
                            density = gestureDensity
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            CompactLyricViewport(
                anchorChildIndex = compactAnchorChildIndex,
                spacing = resolvedCompactLineSpacing,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = compactStackMotion.offsetPx }
            ) {
                        val activeInterlude = playbackState.activeInterlude
                if (activeInterlude != null) {
                    InstrumentalInterlude(
                        interlude = activeInterlude,
                        positionMs = latestPlaybackPositionMs.value,
                        active = true,
                        color = textColor,
                        textPosition = textPosition,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                    )
                } else {
                    visibleLineIndices.forEach { index ->
                        val line = lines[index]
                        val active = playbackState.isActive(index)
                        val highlighted = playbackState.isHighlighted(index)
                        val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                        key(index) {
                            ComposeLyricLine(
                                line = line,
                                active = active,
                                highlighted = highlighted,
                                positionMs = when {
                                    !active -> Long.MIN_VALUE
                                    lyricLineNeedsCompositionPosition(line) -> latestPlaybackPositionMs.value
                                    else -> line.begin
                                },
                                positionState = null,
                                positionFractionState = null,
                                renderClock = lyricDirectRenderClock.takeIf { active && karaokeLiftEnabled },
                                displayTranslation = displayTranslation,
                                displayRoma = displayRoma,
                                textColor = if (highlighted || highlightAll) textColor else dimColor,
                                dimColor = dimColor,
                                secondaryColor = if (highlighted || highlightAll) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                                fontFamily = resolvedFontFamily,
                                fontSizeSp = resolvedFontSizeSp,
                                textPosition = resolvedPosition,
                                karaokeGlowEnabled = karaokeGlowEnabled,
                                karaokeLiftEnabled = karaokeLiftEnabled,
                                primaryFontSizeRange = primaryFontSizeRange,
                                secondaryFontSizeRange = secondaryFontSizeRange,
                                horizontalPadding = lineHorizontalPadding,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { lyricPullField.updateRowHeight(index, it.height) }
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        lyricZoomState.updateRowWindowBounds(index, bounds.top, bounds.bottom)
                                    }
                                    .lyricLineVisuals(
                                        active = active,
                                        highlighted = highlighted,
                                        signedDistance = index - compactTransitionAnchorIndex,
                                        pivotFractionX = resolvedPosition.pivotFractionX,
                                        blurEnabled = blurEnabled && !lyricZoomState.active,
                                        highlightAll = highlightAll,
                                        motionActive = lyricZoomState.active,
                                        pullField = lyricPullField,
                                        pullIndex = index,
                                        pullEnabled = !lyricZoomState.active,
                                        zoomState = lyricZoomState,
                                        zoomBaseFontSizeSp = fontSizeSp,
                                        zoomPrimaryFontSizeRange = primaryFontSizeRange,
                                    )
                                    .clickable {
                                        pendingManualSeekLineIndex = index
                                        scope.launch { onLineClick(line.begin) }
                                    }
                            )
                        }
                    }
                }
            }

            if (zoomGestureEnabled && !lyricZoomState.active) {
                LyricZoomPrewarmScenes(
                    zoomState = lyricZoomState,
                    currentScalePercent = effectiveFontScale,
                    orderedLineIndices = renderableIndices,
                    displayItems = displayItems,
                    lines = lines,
                    anchorIndex = anchorIndex,
                    activeLineIndices = playbackState.activeLineIndicesList,
                    activeInterlude = playbackState.activeInterlude,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    fontFamily = resolvedFontFamily,
                    baseFontSizeSp = fontSizeSp,
                    textPosition = textPosition,
                    duetLayout = duetLayout,
                    primaryFontSizeRange = primaryFontSizeRange,
                    secondaryFontSizeRange = secondaryFontSizeRange,
                    lineHorizontalPadding = lineHorizontalPadding,
                    lineSpacing = resolvedCompactLineSpacing,
                    ReferenceSceneLayout = false,
                )
            }

            if (lyricZoomState.active && !lyricZoomState.handoffPrepared) {
                LyricZoomTransitionRows(
                    zoomState = lyricZoomState,
                    orderedLineIndices = renderableIndices,
                    displayItems = displayItems,
                    lines = lines,
                    anchorIndex = compactTransitionAnchorIndex,
                    activeLineIndices = zoomSnapshotActiveLineIndices,
                    activeInterlude = zoomSnapshotInterlude,
                    positionMs = zoomSnapshotPositionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    textColor = textColor,
                    dimColor = dimColor,
                    secondaryColor = secondaryColor,
                    fontFamily = resolvedFontFamily,
                    baseFontSizeSp = fontSizeSp,
                    textPosition = textPosition,
                    duetLayout = duetLayout,
                    karaokeGlowEnabled = karaokeGlowEnabled,
                    karaokeLiftEnabled = karaokeLiftEnabled,
                    primaryFontSizeRange = primaryFontSizeRange,
                    secondaryFontSizeRange = secondaryFontSizeRange,
                    lineHorizontalPadding = lineHorizontalPadding,
                    lineSpacing = resolvedCompactLineSpacing,
                    ReferenceSceneLayout = false,
                    highlightAll = highlightAll,
                    sessionGeneration = lyricZoomState.sessionGeneration,
                    layoutGeneration = lyricZoomState.layoutGeneration,
                )
            }
        }
        return
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
    // Keep the list's logical scroll position authoritative, but animate the visual handoff on
    // one GPU layer. `LazyColumn.animateScrollBy` continuously invalidates lazy layout while the
    // active row is also changing its karaoke paint; that is the source of the sticky, stepped
    // upward motion on device. We snap the list once, then carry the old screen position in this
    // layer and settle it from the absolute frame clock.
    val fullStackMotion = rememberLyricStackMotionState(song)
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
    val lyricInteractionActive = userScrolling || lyricZoomState.active

    val latestZoomCommitScale = rememberUpdatedState<suspend (Int) -> Unit> { targetScale ->
        val liveLayoutBefore = lyricZoomState.liveLayoutRevision
        effectiveFontScale = targetScale
        val frozenAnchor = lyricZoomState.frozenAnchorIndex()

        // Keep the temporary source/target renderer visible while the real target typography is
        // published underneath it. Releasing overlay ownership before the preference/layout frame
        // is ready makes the release look like an instant font-size switch.
        if (targetScale != latestConfiguredFontScale.value) {
            LyricFontManager.setFontScale(targetScale)
        }

        // Preference observation and text remeasurement are frame-bound. Hold the completed endpoint
        // over the real LazyColumn until the configured scale has propagated and one target frame has
        // actually been rendered, then hand off atomically.
        var readyFrames = 0
        while (lyricZoomState.liveLayoutRevision <= liveLayoutBefore && readyFrames < 8) {
            withFrameNanos { }
            readyFrames++
        }

        // Match the real target holder to the exact center owned by the completed transition.
        // A fixed viewport fraction cannot preserve an arbitrary user/list position and caused the
        // visible post-pinch jump. Correct residual layout movement while the overlay still owns
        // the text, then hand both layers over on the same settled coordinate.
        repeat(3) {
            withFrameNanos { }
            val liveAnchor = lyricZoomState.liveRowBounds(frozenAnchor) ?: return@repeat
            val residualPx = liveAnchor.centerYPx - lyricZoomState.frozenAnchorCenterYPx()
            if (abs(residualPx) <= 0.5f) return@repeat
            listState.scrollBy(residualPx)
        }
        withFrameNanos { }
        lyricZoomState.prepareTargetLayoutHandoff(targetScale)
    }
    val lyricZoomModifier = if (zoomGestureEnabled) {
        Modifier.lyricLayoutZoomGesture(
            zoomState = lyricZoomState,
            currentScale = { latestConfiguredFontScale.value },
            currentAnchorIndex = { latestAnchorIndex.value },
            onGestureCaptured = { lyricPullField.cancel() },
            onCommitScale = { targetScale -> latestZoomCommitScale.value(targetScale) }
        )
    } else {
        Modifier
    }
    val transitionAnchorIndex = if (lyricZoomState.active) {
        lyricZoomState.frozenAnchorIndex().takeIf { it >= 0 } ?: anchorIndex
    } else {
        anchorIndex
    }
    var previousObservedAnchor by remember(song) { mutableIntStateOf(anchorIndex) }
    var lastPulledTransitionSequence by remember(song) { mutableIntStateOf(-1) }
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
            fullStackMotion.cancel()
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
    LaunchedEffect(anchorIndex, isPlaying, lyricInteractionActive, playbackState.activeInterlude) {
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
                userScrolling = lyricInteractionActive,
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
        lyricInteractionActive,
        displayItems.size,
        pendingAnchorTransition.sequence,
        listViewportHeightPx,
    ) {
        if (lazyScrollTargetIndex < 0 || lyricInteractionActive || listViewportHeightPx <= 0) {
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
            val pullFollowingRows = pendingAnchorTransition.pullFollowingRows &&
                pendingAnchorTransition.toIndex == anchorIndex &&
                pendingAnchorTransition.fromIndex >= 0 &&
                anchorIndex > pendingAnchorTransition.fromIndex &&
                pendingAnchorTransition.sequence != lastPulledTransitionSequence &&
                scrollDeltaPx > 0f
            if (pullFollowingRows) {
                val frameTimeMs = withFrameNanos { it / 1_000_000L }
                if (lyricPullField.beginForwardPull(anchorIndex, movementPx, frameTimeMs)) {
                    lastPulledTransitionSequence = pendingAnchorTransition.sequence
                }
            } else if (!lyricPullField.running) {
                lyricPullField.cancel()
            }
            // The list is moved to its logical target once. The visual compensation then settles
            // on one graphics layer, while LyricPullField adds its independent positive offset to
            // rows below the anchor. This preserves the Apple-style trailing wave without making
            // LazyColumn and the row pull animate the same base position.
            listState.scrollBy(scrollDeltaPx)
            val motion = lyricFollowMotion(
                intervalMs = lyricAnchorIntervalMs(
                    lines,
                    pendingAnchorTransition.fromIndex,
                    anchorIndex,
                ),
                isInterlude = playbackState.activeInterlude != null,
            )
            fullStackMotion.beginTransition(scrollDeltaPx, motion)
        } else {
            lyricPullField.cancel()
            fullStackMotion.cancel()
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
            if (lyricInteractionActive || pendingAnchorTransition.toIndex != anchorIndex) {
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
            listState.scrollBy(residualPx)
            fullStackMotion.beginTransition(
                deltaPx = residualPx,
                motion = LyricFollowMotion(
                    dampingRatio = 1f,
                    stiffness = 220f,
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .then(lyricZoomModifier)
            .then(doubleTapObserver)
            .onSizeChanged {
                listViewportHeightPx = it.height
                lyricPullField.updateViewportHeight(it.height)
            }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                lyricZoomState.updateViewportWindowBounds(bounds.top, bounds.bottom)
            }
            .then(
                if (swipeRightGestureEnabled) {
                    Modifier.observeLyricSwipeRight(
                        onSwipeRight = onSwipeRight,
                        onSwipeRightStart = onSwipeRightStart,
                        onSwipeRightProgress = onSwipeRightProgress,
                        onSwipeRightEnd = onSwipeRightEnd,
                        density = gestureDensity
                    )
                } else {
                    Modifier
                }
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = fullStackMotion.offsetPx },
            contentPadding = PaddingValues(top = topPadding, bottom = anchoredBottomPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
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
                        positionMs = if (item.value == playbackState.activeInterlude) latestPlaybackPositionMs.value else Long.MIN_VALUE,
                        active = item.value == playbackState.activeInterlude,
                        color = textColor,
                        textPosition = textPosition,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                    )
                    is LyricDisplayItem.Line -> {
                        val index = item.sourceIndex
                        val line = lines[index]
                        val active = playbackState.isActive(index)
                        val highlighted = playbackState.isHighlighted(index)
                        val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                        ComposeLyricLine(
                            line = line,
                            active = active,
                            highlighted = highlighted,
                            positionMs = when {
                                !active -> Long.MIN_VALUE
                                lyricLineNeedsCompositionPosition(line) -> latestPlaybackPositionMs.value
                                else -> line.begin
                            },
                            positionState = null,
                            positionFractionState = null,
                            renderClock = lyricDirectRenderClock.takeIf { active && karaokeLiftEnabled },
                            displayTranslation = displayTranslation,
                            displayRoma = displayRoma,
                            textColor = if (highlighted || highlightAll) textColor else dimColor,
                            dimColor = dimColor,
                            secondaryColor = if (highlighted || highlightAll) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                            fontFamily = resolvedFontFamily,
                            // Keep the measured row at its active-line size. The inactive
                            // reduction is a graphics-layer scale, so a line handoff cannot
                            // trigger a new Text/Layout measurement while the list is moving.
                            fontSizeSp = lyricSceneStyle.stableLayoutFontSizeSp(fontSizeSp),
                            textPosition = resolvedPosition,
                            karaokeGlowEnabled = karaokeGlowEnabled,
                            karaokeLiftEnabled = karaokeLiftEnabled,
                            primaryFontSizeRange = primaryFontSizeRange,
                            secondaryFontSizeRange = secondaryFontSizeRange,
                            horizontalPadding = resolvedLineHorizontalPadding,
                            verticalPadding = lyricSceneStyle.verticalMargin(active = true),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { size ->
                                    lyricPullField.updateRowHeight(index, size.height)
                                }
                                .onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInWindow()
                                    lyricZoomState.updateRowWindowBounds(index, bounds.top, bounds.bottom)
                                }
                                .lyricLineVisuals(
                                    active = active,
                                    highlighted = highlighted,
                                    signedDistance = index - transitionAnchorIndex,
                                    pivotFractionX = resolvedPosition.pivotFractionX,
                                    blurEnabled = blurEnabled && !lyricInteractionActive,
                                    highlightAll = highlightAll,
                                    pullField = lyricPullField,
                                    pullIndex = index,
                                    pullEnabled = !lyricInteractionActive,
                                    motionActive = lyricPullField.running || lyricZoomState.active,
                                    inactiveScale = lyricSceneStyle.stableInactiveScale(),
                                    zoomState = lyricZoomState,
                                    zoomBaseFontSizeSp = fontSizeSp,
                                    zoomPrimaryFontSizeRange = primaryFontSizeRange,
                                )
                                .clickable {
                                    pendingManualSeekLineIndex = index
                                    userScrolling = false
                                    if (index <= anchorIndex) lyricPullField.cancel()
                                    scope.launch { onLineClick(line.begin) }
                                }
                        )
                    }
                }
            }
        }

        if (zoomGestureEnabled && !lyricZoomState.active) {
            LyricZoomPrewarmScenes(
                zoomState = lyricZoomState,
                currentScalePercent = effectiveFontScale,
                orderedLineIndices = renderableIndices,
                displayItems = displayItems,
                lines = lines,
                anchorIndex = anchorIndex,
                activeLineIndices = playbackState.activeLineIndicesList,
                activeInterlude = playbackState.activeInterlude,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                fontFamily = resolvedFontFamily,
                baseFontSizeSp = fontSizeSp,
                textPosition = textPosition,
                duetLayout = duetLayout,
                primaryFontSizeRange = primaryFontSizeRange,
                secondaryFontSizeRange = secondaryFontSizeRange,
                lineHorizontalPadding = lineHorizontalPadding,
                lineSpacing = 0.dp,
                ReferenceSceneLayout = true,
            )
        }

        if (lyricZoomState.active && !lyricZoomState.handoffPrepared) {
            LyricZoomTransitionRows(
                zoomState = lyricZoomState,
                orderedLineIndices = renderableIndices,
                displayItems = displayItems,
                lines = lines,
                anchorIndex = transitionAnchorIndex,
                activeLineIndices = zoomSnapshotActiveLineIndices,
                activeInterlude = zoomSnapshotInterlude,
                positionMs = zoomSnapshotPositionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                textColor = textColor,
                dimColor = dimColor,
                secondaryColor = secondaryColor,
                fontFamily = resolvedFontFamily,
                baseFontSizeSp = fontSizeSp,
                textPosition = textPosition,
                duetLayout = duetLayout,
                karaokeGlowEnabled = karaokeGlowEnabled,
                karaokeLiftEnabled = karaokeLiftEnabled,
                primaryFontSizeRange = primaryFontSizeRange,
                secondaryFontSizeRange = secondaryFontSizeRange,
                lineHorizontalPadding = lineHorizontalPadding,
                lineSpacing = 0.dp,
                ReferenceSceneLayout = true,
                highlightAll = highlightAll,
                sessionGeneration = lyricZoomState.sessionGeneration,
                layoutGeneration = lyricZoomState.layoutGeneration,
            )
        }
    }
}

private fun Modifier.observeLyricDoubleTap(onDoubleTap: (() -> Unit)?): Modifier {
    if (onDoubleTap == null) return this
    return pointerInput(onDoubleTap) {
        awaitPointerEventScope {
            var downPosition: Offset? = null
            var moved = false
            var lastTapTimeMs = 0L
            var lastTapPosition = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: continue
                if (change.changedToDownIgnoreConsumed()) {
                    downPosition = change.position
                    moved = false
                }
                val start = downPosition
                if (start != null && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                if (change.changedToUpIgnoreConsumed()) {
                    if (!moved) {
                        val elapsed = change.uptimeMillis - lastTapTimeMs
                        val sameArea = (change.position - lastTapPosition).getDistance() <=
                            viewConfiguration.touchSlop * 4f
                        if (lastTapTimeMs > 0L &&
                            elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                            sameArea
                        ) {
                            onDoubleTap()
                            lastTapTimeMs = 0L
                        } else {
                            lastTapTimeMs = change.uptimeMillis
                            lastTapPosition = change.position
                        }
                    }
                    downPosition = null
                } else if (event.changes.none { it.pressed }) {
                    downPosition = null
                }
            }
        }
    }
}

@Composable
private fun CompactLyricViewport(
    anchorChildIndex: Int,
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val spacingPx = with(LocalDensity.current) { spacing.roundToPx() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            if (placeables.isEmpty()) return@layout
            val anchor = anchorChildIndex.coerceIn(placeables.indices)
            val topPositions = IntArray(placeables.size)
            topPositions[anchor] = (height - placeables[anchor].height) / 2
            for (index in anchor - 1 downTo 0) {
                topPositions[index] = topPositions[index + 1] - spacingPx - placeables[index].height
            }
            for (index in anchor + 1 until placeables.size) {
                topPositions[index] = topPositions[index - 1] + placeables[index - 1].height + spacingPx
            }
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(0, topPositions[index])
            }
        }
    }
}

@Composable
private fun ComposeLyricLine(
    line: IRichLyricLine,
    active: Boolean,
    highlighted: Boolean = active,
    positionMs: Long,
    positionState: State<Long>? = null,
    positionFractionState: State<Float>? = null,
    renderClock: LyricDirectRenderClock? = null,
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
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    horizontalPadding: Dp,
    verticalPadding: Dp = 0.dp,
    animateBackground: Boolean = true,
    modifier: Modifier = Modifier
) {
    val primarySize = fontSizeSp.coerceIn(primaryFontSizeRange.first, primaryFontSizeRange.last)
    val primaryLineHeight = primarySize + 6
    val secondarySize = (primarySize * 0.62f).toInt().coerceIn(
        secondaryFontSizeRange.first,
        secondaryFontSizeRange.last
    )
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
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
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
            if (mainWords.isNotEmpty()) {
                KaraokeTimedText(
                    text = displayedMain,
                    words = mainWords,
                    lineEndMs = effectiveSingleLineEnd(line, mainWords),
                    positionMs = if (active) positionMs else Long.MIN_VALUE,
                    highlightColor = textColor,
                    dimColor = if (active) dimColor else textColor,
                    fontSize = primarySize.sp,
                    lineHeight = primaryLineHeight.sp,
                    textAlign = textAlign,
                    positionState = positionState,
                    positionFractionState = positionFractionState,
                    renderClock = renderClock,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    glowEnabled = active && karaokeGlowEnabled,
                    liftEnabled = active && karaokeLiftEnabled,
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
            val backgroundTransformOrigin = when (textPosition) {
                LyricTextPosition.Left -> TransformOrigin(0f, 1f)
                LyricTextPosition.Center -> TransformOrigin(0.5f, 1f)
                LyricTextPosition.Right -> TransformOrigin(1f, 1f)
            }
            // Keep the backing-vocal slot mounted for the lifetime of the row. AnimatedVisibility
            // changes the parent height on every frame and makes the current-line handoff
            // remeasure the whole LazyColumn. The same reveal is now a layer-only transition.
            val backgroundAlpha by animateFloatAsState(
                targetValue = if (backgroundActive) 1f else 0f,
                animationSpec = if (animateBackground) {
                    tween(AM_LYRIC_BG_SCALE_MS, delayMillis = if (backgroundActive) {
                        AM_LYRIC_BG_ENTER_SCALE_DELAY_MS
                    } else {
                        0
                    }, easing = if (backgroundActive) {
                        AM_LYRIC_BG_GEOMETRY_EASING
                    } else {
                        AM_LYRIC_BG_ALPHA_OUT_EASING
                    })
                } else {
                    snap()
                },
                label = "lyricBackgroundAlpha"
            )
            val backgroundScale by animateFloatAsState(
                targetValue = if (backgroundActive) 1f else 0.9f,
                animationSpec = if (animateBackground) {
                    tween(AM_LYRIC_BG_SCALE_MS, easing = AM_LYRIC_BG_GEOMETRY_EASING)
                } else {
                    snap()
                },
                label = "lyricBackgroundScale"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = backgroundAlpha
                        scaleX = backgroundScale
                        scaleY = backgroundScale
                        transformOrigin = backgroundTransformOrigin
                    },
                horizontalAlignment = align
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
                            positionState = positionState,
                            positionFractionState = positionFractionState,
                            renderClock = renderClock,
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
private fun LyricZoomPrewarmScenes(
    zoomState: LyricZoomTransitionState,
    currentScalePercent: Int,
    orderedLineIndices: List<Int>,
    displayItems: List<LyricDisplayItem>,
    lines: List<IRichLyricLine>,
    anchorIndex: Int,
    activeLineIndices: Collection<Int>,
    activeInterlude: LyricInterlude?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    fontFamily: FontFamily?,
    baseFontSizeSp: Int,
    textPosition: LyricTextPosition,
    duetLayout: Boolean,
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    lineHorizontalPadding: Dp,
    lineSpacing: Dp,
    ReferenceSceneLayout: Boolean,
) {
    if (zoomState.active || orderedLineIndices.isEmpty()) return
    // Prewarm is semantic-anchor driven, not placement-frame driven. Natural lyric follow may move
    // the live list for hundreds of milliseconds; the cached target scene is aligned to the exact
    // captured anchor when pinch begins instead of remeasuring throughout that scroll.
    val indices = zoomState.livePrewarmRunwayIndices(orderedLineIndices, anchorIndex, perEdge = 12)
    if (indices.isEmpty()) return
    val anchorCenterWindowPx = zoomState.liveAnchorCenterYPx(anchorIndex) ?: return
    val viewportTopPx = zoomState.liveViewportTopPx()
    val anchorCenterLocalPx = anchorCenterWindowPx - viewportTopPx
    val targetScales = remember(currentScalePercent) {
        listOf(
            LyricZoomSpec.adjacentSceneScale(currentScalePercent, -1),
            LyricZoomSpec.adjacentSceneScale(currentScalePercent, 1),
        ).filter { it != currentScalePercent }.distinct()
    }
    if (targetScales.isEmpty()) return

    val density = LocalDensity.current
    val spacingPx = with(density) { lineSpacing.toPx() }
    val interludeHeightPx = with(density) { 48.dp.toPx() }
    val displayPositionByLine = remember(displayItems) {
        buildMap {
            displayItems.forEachIndexed { position, item ->
                if (item is LyricDisplayItem.Line) put(item.sourceIndex, position)
            }
        }
    }

    targetScales.forEach { scalePercent ->
        val endpointStyle = remember(scalePercent) { ReferenceLyricSceneStyle(scalePercent) }
        val endpointHorizontalPadding = remember(lineHorizontalPadding, endpointStyle, ReferenceSceneLayout) {
            if (ReferenceSceneLayout) endpointStyle.horizontalPadding(lineHorizontalPadding)
            else lineHorizontalPadding
        }
        val measuredWindowBounds = remember(scalePercent, anchorIndex, indices) {
            linkedMapOf<Int, LyricZoomRowBounds>()
        }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .graphicsLayer { alpha = 0f },
            content = {
                indices.forEach { index ->
                    val line = lines.getOrNull(index) ?: return@forEach
                    val active = index in activeLineIndices
                    val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                    key("lyric-zoom-prewarm-$scalePercent-$anchorIndex-$index") {
                        LyricZoomMeasureLine(
                            line = line,
                            active = active,
                            // Geometry is playback-position independent: background-vocal slots are
                            // permanently mounted just like the visible row.
                            positionMs = line.begin,
                            displayTranslation = displayTranslation,
                            displayRoma = displayRoma,
                            fontFamily = fontFamily,
                            fontSizeSp = if (ReferenceSceneLayout) {
                                endpointStyle.fontSizeSp(baseFontSizeSp, active)
                            } else {
                                (baseFontSizeSp * scalePercent / 100f).roundToInt().coerceAtLeast(1)
                            },
                            textPosition = resolvedPosition,
                            primaryFontSizeRange = primaryFontSizeRange,
                            secondaryFontSizeRange = secondaryFontSizeRange,
                            horizontalPadding = endpointHorizontalPadding,
                            verticalPadding = if (ReferenceSceneLayout) endpointStyle.verticalMargin(active) else 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            if (placeables.isEmpty()) {
                layout(constraints.maxWidth, constraints.maxHeight) { }
            } else {
                val anchorPosition = indices.indexOf(anchorIndex).takeIf { it >= 0 }
                    ?: indices.indices.minByOrNull { position -> abs(indices[position] - anchorIndex) }
                    ?: 0
                val tops = IntArray(placeables.size)
                tops[anchorPosition] = (anchorCenterLocalPx - placeables[anchorPosition].height * 0.5f).roundToInt()
                for (position in anchorPosition + 1 until placeables.size) {
                    val gap = lyricZoomLineGapPx(
                        displayItems = displayItems,
                        displayPositionByLine = displayPositionByLine,
                        fromLineIndex = indices[position - 1],
                        toLineIndex = indices[position],
                        spacingPx = spacingPx,
                        interludeHeightPx = interludeHeightPx,
                        activeInterlude = activeInterlude,
                    )
                    tops[position] = (tops[position - 1] + placeables[position - 1].height + gap).roundToInt()
                }
                for (position in anchorPosition - 1 downTo 0) {
                    val gap = lyricZoomLineGapPx(
                        displayItems = displayItems,
                        displayPositionByLine = displayPositionByLine,
                        fromLineIndex = indices[position],
                        toLineIndex = indices[position + 1],
                        spacingPx = spacingPx,
                        interludeHeightPx = interludeHeightPx,
                        activeInterlude = activeInterlude,
                    )
                    tops[position] = (tops[position + 1] - gap - placeables[position].height).roundToInt()
                }
                // Bounds are in the lyric viewport's window coordinate space because this hidden
                // scene shares the same full-size Box. Publish one complete scene transaction before
                // returning the MeasureResult; the map itself is non-Snapshot cache state.
                measuredWindowBounds.clear()
                indices.forEachIndexed { position, index ->
                    val top = viewportTopPx + tops[position]
                    measuredWindowBounds[index] = LyricZoomRowBounds(top, top + placeables[position].height)
                }
                zoomState.updatePrewarmedScene(
                    scalePercent = scalePercent,
                    anchorIndex = anchorIndex,
                    anchorCenterYPx = anchorCenterWindowPx,
                    boundsByIndex = measuredWindowBounds,
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { position, placeable ->
                        placeable.placeRelative(0, tops[position])
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricZoomEndpointMeasureLayer(
    zoomState: LyricZoomTransitionState,
    scalePercent: Int,
    orderedLineIndices: List<Int>,
    displayItems: List<LyricDisplayItem>,
    lines: List<IRichLyricLine>,
    anchorIndex: Int,
    activeLineIndices: Collection<Int>,
    activeInterlude: LyricInterlude?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    fontFamily: FontFamily?,
    baseFontSizeSp: Int,
    textPosition: LyricTextPosition,
    duetLayout: Boolean,
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    lineHorizontalPadding: Dp,
    lineSpacing: Dp = 0.dp,
    ReferenceSceneLayout: Boolean,
    sessionGeneration: Int,
    layoutGeneration: Int,
) {
    val indices = remember(sessionGeneration) {
        // Measure the same attached-holder runway that the visible stack renders.  If the
        // endpoint only registers the frozen rows, edge holders fall back to projected bounds and
        // their real wrap height arrives late, which is exactly the delayed overlap seen on pinch.
        zoomState.transitionLineIndices(orderedLineIndices, perEdge = 6)
    }
    if (indices.isEmpty()) return
    val measuredWindowBounds = remember(sessionGeneration, scalePercent, indices) {
        linkedMapOf<Int, LyricZoomRowBounds>()
    }

    // Register the target holder set in the same composition commit that creates the hidden
    // endpoint layout. Reference's target layout is part of the active transition immediately; a
    // coroutine-delayed registration adds an avoidable frame before it can become ready.
    SideEffect {
        zoomState.setLayoutExpectedIndices(scalePercent, indices)
    }

    val endpointSceneStyle = remember(scalePercent) { ReferenceLyricSceneStyle(scalePercent) }
    val endpointHorizontalPadding = remember(lineHorizontalPadding, endpointSceneStyle, ReferenceSceneLayout) {
        if (ReferenceSceneLayout) endpointSceneStyle.horizontalPadding(lineHorizontalPadding)
        else lineHorizontalPadding
    }
    val density = LocalDensity.current
    val spacingPx = with(density) { lineSpacing.toPx() }
    val interludeHeightPx = with(density) { 48.dp.toPx() }
    val viewportTopPx = zoomState.frozenViewportTopPx()
    val anchorCenterLocalPx = zoomState.frozenAnchorCenterYPx() - viewportTopPx
    val displayPositionByLine = remember(sessionGeneration, displayItems) {
        buildMap {
            displayItems.forEachIndexed { position, item ->
                if (item is LyricDisplayItem.Line) put(item.sourceIndex, position)
            }
        }
    }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .graphicsLayer { alpha = 0f },
        content = {
            indices.forEach { index ->
                val line = lines.getOrNull(index) ?: return@forEach
                val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                key("lyric-zoom-measure-$sessionGeneration-$scalePercent-$index") {
                    LyricZoomMeasureLine(
                        line = line,
                        active = index in activeLineIndices,
                        positionMs = positionMs,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        fontFamily = fontFamily,
                        fontSizeSp = if (ReferenceSceneLayout) {
                            endpointSceneStyle.fontSizeSp(baseFontSizeSp, index in activeLineIndices)
                        } else {
                            (baseFontSizeSp * scalePercent / 100f).roundToInt().coerceAtLeast(1)
                        },
                        textPosition = resolvedPosition,
                        primaryFontSizeRange = primaryFontSizeRange,
                        secondaryFontSizeRange = secondaryFontSizeRange,
                        horizontalPadding = endpointHorizontalPadding,
                        verticalPadding = if (ReferenceSceneLayout) {
                            endpointSceneStyle.verticalMargin(index in activeLineIndices)
                        } else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                measuredWindowBounds[index] = LyricZoomRowBounds(bounds.top, bounds.bottom)
                                if (!zoomState.layoutReady(scalePercent) &&
                                    indices.all(measuredWindowBounds::containsKey)
                                ) {
                                    zoomState.updateLayoutSceneWindowBounds(
                                        scalePercent = scalePercent,
                                        boundsByIndex = measuredWindowBounds,
                                    )
                                }
                            },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minHeight = 0))
        }
        if (placeables.isEmpty()) {
            layout(constraints.maxWidth, constraints.maxHeight) { }
        } else {
            val anchorPosition = indices.indexOf(anchorIndex).takeIf { it >= 0 }
                ?: indices.indices.minByOrNull { position -> abs(indices[position] - anchorIndex) }
                ?: 0
            val tops = IntArray(placeables.size)
            tops[anchorPosition] = (anchorCenterLocalPx - placeables[anchorPosition].height * 0.5f).roundToInt()

            for (position in anchorPosition + 1 until placeables.size) {
                val gap = lyricZoomLineGapPx(
                    displayItems = displayItems,
                    displayPositionByLine = displayPositionByLine,
                    fromLineIndex = indices[position - 1],
                    toLineIndex = indices[position],
                    spacingPx = spacingPx,
                    interludeHeightPx = interludeHeightPx,
                    activeInterlude = activeInterlude,
                )
                tops[position] = (tops[position - 1] + placeables[position - 1].height + gap).roundToInt()
            }
            for (position in anchorPosition - 1 downTo 0) {
                val gap = lyricZoomLineGapPx(
                    displayItems = displayItems,
                    displayPositionByLine = displayPositionByLine,
                    fromLineIndex = indices[position],
                    toLineIndex = indices[position + 1],
                    spacingPx = spacingPx,
                    interludeHeightPx = interludeHeightPx,
                    activeInterlude = activeInterlude,
                )
                tops[position] = (tops[position + 1] - gap - placeables[position].height).roundToInt()
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { position, placeable ->
                    placeable.placeRelative(0, tops[position])
                }
            }
        }
    }
}

@Composable
private fun LyricZoomMeasureLine(
    line: IRichLyricLine,
    active: Boolean,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    fontFamily: FontFamily?,
    fontSizeSp: Int,
    textPosition: LyricTextPosition,
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    horizontalPadding: Dp,
    verticalPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val primarySize = fontSizeSp.coerceIn(primaryFontSizeRange.first, primaryFontSizeRange.last)
    val primaryLineHeight = primarySize + 6
    val secondarySize = (primarySize * 0.62f).toInt().coerceIn(
        secondaryFontSizeRange.first,
        secondaryFontSizeRange.last,
    )
    val secondaryLineHeight = secondarySize + 6
    val mainText = line.text.orEmpty()
    val backgroundText = line.secondary.orEmpty()
    val backgroundOnly = mainText.isBlank() && backgroundText.isNotBlank()
    val displayedMain = if (backgroundOnly) backgroundText else mainText.ifBlank { "♪" }
    val mainWords = if (backgroundOnly) line.secondaryWords.orEmpty() else line.words.orEmpty()
    val textAlign = textPosition.textAlign
    val align = textPosition.horizontalAlignment
    val transparent = Color.Transparent

    // This one-shot hidden layout mirrors ComposeLyricLine's wrapping rules. Timed lines keep the
    // same BaselineFlowRow while active and inactive so a playback-state change cannot alter line
    // breaks or hand the zoom transition a different row height.
    Column(
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalAlignment = align,
    ) {
        if (displayRoma && !line.roma.isNullOrBlank()) {
            Text(
                text = line.roma.orEmpty(),
                color = transparent,
                fontSize = secondarySize.sp,
                lineHeight = secondaryLineHeight.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(5.dp))
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = textPosition.boxAlignment,
        ) {
            if (mainWords.isNotEmpty()) {
                KaraokeTimedMeasureText(
                    text = displayedMain,
                    words = mainWords,
                    fontSize = primarySize.sp,
                    lineHeight = primaryLineHeight.sp,
                    textAlign = textAlign,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = displayedMain,
                    color = transparent,
                    fontSize = primarySize.sp,
                    lineHeight = primaryLineHeight.sp,
                    textAlign = textAlign,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (displayTranslation && !line.translation.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = line.translation.orEmpty(),
                color = transparent,
                fontSize = secondarySize.sp,
                lineHeight = secondaryLineHeight.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!backgroundOnly && backgroundText.isNotBlank()) {
            val backgroundWords = line.secondaryWords.orEmpty()
            // ComposeLyricLine keeps this slot mounted even when its alpha is zero, so target
            // geometry must do the same. Conditioning hidden measurement on playback position made
            // scene height change at background-vocal boundaries and also forced positionMs into the
            // endpoint measure subtree.
            Spacer(Modifier.height(7.dp))
            if (backgroundWords.isNotEmpty()) {
                KaraokeTimedMeasureText(
                    text = backgroundText,
                    words = backgroundWords,
                    fontSize = secondarySize.sp,
                    lineHeight = secondaryLineHeight.sp,
                    textAlign = textAlign,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = fontFamily,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = backgroundText,
                    color = transparent,
                    fontSize = secondarySize.sp,
                    lineHeight = secondaryLineHeight.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = fontFamily,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (displayTranslation && !line.backgroundTranslation.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = line.backgroundTranslation.orEmpty(),
                    color = transparent,
                    fontSize = secondarySize.sp,
                    lineHeight = secondaryLineHeight.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun lyricZoomLineGapPx(
    displayItems: List<LyricDisplayItem>,
    displayPositionByLine: Map<Int, Int>,
    fromLineIndex: Int,
    toLineIndex: Int,
    spacingPx: Float,
    interludeHeightPx: Float,
    activeInterlude: LyricInterlude?,
): Float {
    val fromPosition = displayPositionByLine[fromLineIndex]
    val toPosition = displayPositionByLine[toLineIndex]
    if (fromPosition == null || toPosition == null || fromPosition == toPosition) return spacingPx
    val first = minOf(fromPosition, toPosition)
    val last = maxOf(fromPosition, toPosition)
    var activeInterludeCount = 0
    for (position in (first + 1) until last) {
        val interlude = (displayItems[position] as? LyricDisplayItem.Interlude)?.value
        if (interlude != null && interlude == activeInterlude) activeInterludeCount++
    }
    return LyricZoomSpec.displayGapPx(
        spacingPx = spacingPx,
        displayItemDistance = last - first,
        activeInterludeCount = activeInterludeCount,
        interludeHeightPx = interludeHeightPx,
    )
}

@Composable
private fun LyricZoomTransitionRows(
    zoomState: LyricZoomTransitionState,
    orderedLineIndices: List<Int>,
    displayItems: List<LyricDisplayItem>,
    lines: List<IRichLyricLine>,
    anchorIndex: Int,
    activeLineIndices: Collection<Int>,
    activeInterlude: LyricInterlude?,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    textColor: Color,
    dimColor: Color,
    secondaryColor: Color,
    fontFamily: FontFamily?,
    baseFontSizeSp: Int,
    textPosition: LyricTextPosition,
    duetLayout: Boolean,
    karaokeGlowEnabled: Boolean,
    karaokeLiftEnabled: Boolean,
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    lineHorizontalPadding: Dp,
    lineSpacing: Dp,
    ReferenceSceneLayout: Boolean,
    highlightAll: Boolean,
    sessionGeneration: Int,
    layoutGeneration: Int,
) {
    val indices = remember(sessionGeneration) {
        zoomState.transitionLineIndices(orderedLineIndices, perEdge = 6)
    }
    if (indices.isEmpty()) return

    // IMPORTANT: do not read frameRevision in composition. Reference mutates LayoutRes/View
    // properties on attached holders; it does not rebuild the holder tree for every pinch sample.
    // In 9A35 this read recomposed the whole endpoint overlay every pointer frame.
    val sourceScalePercent = zoomState.segmentSourceScalePercent
    val targetScalePercent = zoomState.segmentTargetScalePercent
    if (sourceScalePercent == targetScalePercent) return

    // Measure the adjacent target scene offscreen first. GeometryRevision changes only when this
    // endpoint transaction completes (not on pointer frames), so it is safe to observe here and
    // use it as the one-shot handoff trigger.
    zoomState.geometryRevision
    if (!zoomState.layoutReady(targetScalePercent)) {
        // Cold fallback only. The normal path imports the idle-prewarmed adjacent scene in begin(),
        // so no hidden Text measurement is created after the fingers have captured the gesture.
        LyricZoomEndpointMeasureLayer(
            zoomState = zoomState,
            scalePercent = targetScalePercent,
            orderedLineIndices = orderedLineIndices,
            displayItems = displayItems,
            lines = lines,
            anchorIndex = anchorIndex,
            activeLineIndices = activeLineIndices,
            activeInterlude = activeInterlude,
            positionMs = positionMs,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            fontFamily = fontFamily,
            baseFontSizeSp = baseFontSizeSp,
            textPosition = textPosition,
            duetLayout = duetLayout,
            primaryFontSizeRange = primaryFontSizeRange,
            secondaryFontSizeRange = secondaryFontSizeRange,
            lineHorizontalPadding = lineHorizontalPadding,
            lineSpacing = lineSpacing,
            ReferenceSceneLayout = ReferenceSceneLayout,
            sessionGeneration = sessionGeneration,
            layoutGeneration = layoutGeneration,
        )
    }

    zoomState.prepareTransitionFrame(indices)
    if (!zoomState.overlayOwnsText()) return

    LyricZoomTargetOnlyScene(
        zoomState = zoomState,
        sourceScalePercent = sourceScalePercent,
        targetScalePercent = targetScalePercent,
        indices = indices,
        lines = lines,
        anchorIndex = anchorIndex,
        activeLineIndices = activeLineIndices,
        positionMs = positionMs,
        displayTranslation = displayTranslation,
        displayRoma = displayRoma,
        textColor = textColor,
        dimColor = dimColor,
        secondaryColor = secondaryColor,
        fontFamily = fontFamily,
        baseFontSizeSp = baseFontSizeSp,
        textPosition = textPosition,
        duetLayout = duetLayout,
        karaokeGlowEnabled = karaokeGlowEnabled,
        karaokeLiftEnabled = karaokeLiftEnabled,
        primaryFontSizeRange = primaryFontSizeRange,
        secondaryFontSizeRange = secondaryFontSizeRange,
        lineHorizontalPadding = lineHorizontalPadding,
        ReferenceSceneLayout = ReferenceSceneLayout,
        highlightAll = highlightAll,
        sessionGeneration = sessionGeneration,
        layoutGeneration = layoutGeneration,
    )
}

@Composable
private fun LyricZoomTargetOnlyScene(
    zoomState: LyricZoomTransitionState,
    sourceScalePercent: Int,
    targetScalePercent: Int,
    indices: List<Int>,
    lines: List<IRichLyricLine>,
    anchorIndex: Int,
    activeLineIndices: Collection<Int>,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    textColor: Color,
    dimColor: Color,
    secondaryColor: Color,
    fontFamily: FontFamily?,
    baseFontSizeSp: Int,
    textPosition: LyricTextPosition,
    duetLayout: Boolean,
    karaokeGlowEnabled: Boolean,
    karaokeLiftEnabled: Boolean,
    primaryFontSizeRange: IntRange,
    secondaryFontSizeRange: IntRange,
    lineHorizontalPadding: Dp,
    ReferenceSceneLayout: Boolean,
    highlightAll: Boolean,
    sessionGeneration: Int,
    layoutGeneration: Int,
) {
    // Source/shared holders stay in the real LazyColumn for the whole pinch. Only rows that do
    // not exist in the captured viewport need a target-scene holder here. This removes the
    // expensive live -> full-overlay -> stable ownership swap that was still happening in 9A40.
    val targetOnlyIndices = remember(sessionGeneration, layoutGeneration, indices) {
        zoomState.targetOnlyLineIndices(indices)
    }
    if (targetOnlyIndices.isEmpty()) return

    val targetStyle = remember(targetScalePercent) { ReferenceLyricSceneStyle(targetScalePercent) }
    val viewportTopPx = zoomState.frozenViewportTopPx()

    Layout(
        modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
        content = {
            targetOnlyIndices.forEach { index ->
                val line = lines.getOrNull(index) ?: return@forEach
                val active = index in activeLineIndices
                val resolvedPosition = line.resolvedTextPosition(textPosition, duetLayout)
                val horizontalPadding = if (ReferenceSceneLayout) {
                    targetStyle.horizontalPadding(lineHorizontalPadding)
                } else {
                    lineHorizontalPadding
                }
                key("lyric-zoom-holder-$sessionGeneration-$layoutGeneration-$index") {
                    ComposeLyricLine(
                        line = line,
                        active = active,
                        positionMs = if (active) positionMs else Long.MIN_VALUE,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        textColor = if (active || highlightAll) textColor else dimColor,
                        dimColor = dimColor,
                        secondaryColor = if (active || highlightAll) secondaryColor else secondaryColor.copy(alpha = 0.58f),
                        fontFamily = fontFamily,
                        fontSizeSp = if (ReferenceSceneLayout) {
                            targetStyle.fontSizeSp(baseFontSizeSp, active)
                        } else {
                            (baseFontSizeSp * targetScalePercent / 100f)
                                .roundToInt()
                                .coerceIn(primaryFontSizeRange.first, primaryFontSizeRange.last)
                        },
                        textPosition = resolvedPosition,
                        karaokeGlowEnabled = karaokeGlowEnabled,
                        karaokeLiftEnabled = karaokeLiftEnabled,
                        primaryFontSizeRange = primaryFontSizeRange,
                        secondaryFontSizeRange = secondaryFontSizeRange,
                        horizontalPadding = horizontalPadding,
                        verticalPadding = if (ReferenceSceneLayout) targetStyle.verticalMargin(active) else 0.dp,
                        animateBackground = false,
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Geometry composition/measurement is one-shot. Pointer and settle samples must not
            // rerun the Layout placement block; Reference mutates attached holder properties only.
            // Read frameRevision inside each graphics-layer block so Compose invalidates just the
            // layer properties while the source placement remains frozen for the transaction.
            zoomState.prepareTransitionFrame(indices)
            placeables.forEachIndexed { position, placeable ->
                val index = targetOnlyIndices[position]
                val sourceBounds = zoomState.transitionSourceRowBounds(index) ?: return@forEachIndexed
                val active = index in activeLineIndices
                val distance = abs(index - anchorIndex)
                val baseAlpha = when {
                    active || highlightAll -> 1f
                    distance == 1 -> 0.42f
                    distance == 2 -> 0.28f
                    else -> 0.16f
                }
                val y = (sourceBounds.topPx - viewportTopPx).roundToInt()
                placeable.placeWithLayer(0, y) {
                    zoomState.frameRevision
                    val transform = zoomState.singleHolderTransform(index, indices)
                    alpha = baseAlpha * transform.alpha
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationY = transform.translationYPx
                    transformOrigin = TransformOrigin.Center
                    clip = false
                }
            }
        }
    }
}

@Composable
private fun Modifier.lyricLineVisuals(
    active: Boolean,
    highlighted: Boolean = active,
    signedDistance: Int,
    pivotFractionX: Float,
    blurEnabled: Boolean,
    highlightAll: Boolean,
    pullField: LyricPullFieldState? = null,
    pullIndex: Int = -1,
    pullEnabled: Boolean = false,
    motionActive: Boolean = false,
    inactiveScale: Float = 0.92f,
    zoomState: LyricZoomTransitionState? = null,
    zoomBaseFontSizeSp: Int = 0,
    zoomPrimaryFontSizeRange: IntRange = 1..1,
): Modifier {
    val distance = abs(signedDistance)
    val visualActive = active || highlighted
    val zoomHandoffPrepared = zoomState?.handoffPrepared == true
    val zoomMotionActive = zoomState?.active == true && !zoomHandoffPrepared
    val alphaTarget = when {
            visualActive || highlightAll -> 1f
            distance == 1 -> 0.42f
            distance == 2 -> 0.28f
            else -> 0.16f
        }
    val animatedAlpha by animateFloatAsState(
        targetValue = alphaTarget,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
        label = "lyricLineAlpha"
    )
    // Natural lyric handoff must keep the animated layer alive.  `motionActive` is also true
    // while the pull field is moving; bypassing this animation there makes the old line jump to
    // its compact scale on the exact frame that playback advances to the next line.
    val alpha = if (zoomHandoffPrepared || zoomMotionActive) alphaTarget else animatedAlpha
    val animatedScale by animateFloatAsState(
        targetValue = if (visualActive) 1f else inactiveScale,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 340f),
        label = "lyricLineScale"
    )
    val scale = if (zoomHandoffPrepared || zoomMotionActive) {
        if (visualActive) 1f else inactiveScale
    } else {
        animatedScale
    }

    // Keep line-follow motion on local properties instead of relaying every animation
    // frame through RecyclerView layout.  During RawSMusic's natural follow, keep the expensive
    // blur effect completely out of the 550 ms pull/scroll window; alpha/scale and trailing pull
    // remain GPU-layer properties. Manual scrolling already disables blur through blurEnabled.
    val blurTarget = when {
        !blurEnabled || highlightAll || visualActive || distance < 2 -> 0.dp
        else -> (2f + distance.coerceAtMost(4)).dp
    }
    val animatedBlur by animateDpAsState(
        targetValue = blurTarget,
        animationSpec = spring(dampingRatio = 0.90f, stiffness = 360f),
        label = "lyricLineBlur"
    )
    val blurRadius = if (motionActive) {
        0.dp
    } else if (zoomHandoffPrepared) {
        blurTarget
    } else {
        animatedBlur
    }

    return graphicsLayer {
        // Natural pull remains a live layer animation. Zoom is different: once the measured
        // PowerList-style overlay owns text, the hidden LazyColumn must stop subscribing to every
        // pointer frame or we update two complete lyric trees for no visible result. Geometry
        // revision is the one-shot ownership handoff; frameRevision is observed only while the
        // live captured holders are actually visible before target measurement is ready.
        pullField?.frameRevision
        zoomState?.geometryRevision
        val trailingOffset = if (pullEnabled && pullField != null && pullIndex >= 0) {
            pullField.offsetPx(pullIndex)
        } else {
            0f
        }
        val zoomTransitionActive = zoomState?.transitionOwnsText() == true
        val frozenSourceHolder = zoomTransitionActive &&
            zoomState != null &&
            pullIndex >= 0 &&
            zoomState.isFrozenSourceRow(pullIndex)
        val liveZoomTransform = if (frozenSourceHolder) {
            zoomState!!.frameRevision
            // Keep the actual attached LazyColumn holder as the source/shared owner for the
            // complete pinch. It consumes the same measured source->target stack as the entering
            // overlay, so there is no live->full-overlay ownership swap under the fingers.
            zoomState.sourceHolderTransform(pullIndex)
        } else {
            null
        }
        val hideUncapturedLiveRow = zoomTransitionActive && pullIndex >= 0 && !frozenSourceHolder
        this.alpha = if (hideUncapturedLiveRow) {
            0f
        } else {
            alpha.coerceIn(0f, 1f) * (liveZoomTransform?.alpha ?: 1f)
        }
        val liveZoomScale = liveZoomTransform?.scale ?: 1f
        scaleX = scale * liveZoomScale
        scaleY = scale * liveZoomScale
        translationY = trailingOffset + (liveZoomTransform?.translationYPx ?: 0f)
        transformOrigin = TransformOrigin(pivotFractionX, 0.5f)
        clip = false
    }.blur(blurRadius)
}

private fun Modifier.lyricLayoutZoomGesture(
    zoomState: LyricZoomTransitionState,
    currentScale: () -> Int,
    currentAnchorIndex: () -> Int,
    onGestureCaptured: () -> Unit,
    onCommitScale: suspend (Int) -> Unit,
): Modifier = pointerInput(zoomState) {
    var settleJob: Job? = null
    var settleGeneration = 0
    try {
        coroutineScope {
            val gestureScope = this
            fun launchSettleAnimation(targetScale: Int) {
                settleGeneration++
                val thisSettleGeneration = settleGeneration
                settleJob?.cancel()
                settleJob = gestureScope.launch {
                    zoomState.beginSettling(targetScale)
                    val releaseSettle = Animatable(0f)
                    releaseSettle.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 500,
                            easing = Easing { fraction ->
                                ((kotlin.math.cos((fraction + 1f) * Math.PI) / 2.0) + 0.5).toFloat()
                            },
                        ),
                    ) {
                        zoomState.setSettleFraction(value)
                    }
                    onCommitScale(targetScale)
                    withFrameNanos { }
                    if (thisSettleGeneration == settleGeneration) {
                        zoomState.cancel()
                        settleJob = null
                    }
                }
            }
            while (true) {
            awaitPointerEventScope {
                // Reference's q1 ZoomGesture is a dedicated scale-gesture owner.  Synchronize each
                // transaction to a real DOWN sequence instead of leaving one long-lived event loop
                // waiting for "two currently pressed" pointers across unrelated gestures.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Keep the previous settle alive while idle, but transfer ownership immediately
                // when a new physical gesture begins.
                settleGeneration++
                settleJob?.cancel()
                settleJob = null
                zoomState.cancel()

                var captured = false
                var baseScale = currentScale().coerceIn(
                    LyricZoomSpec.MIN_SCALE,
                    LyricZoomSpec.MAX_SCALE,
                )
                var baseDistance = 0f
                var lastSpan = 0f
                var lastScaleUptimeMs = 0L
                var rawScaleVelocityPercentPerSecond = 0f
                var anyPointerStillPressed = true

                while (!captured) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        anyPointerStillPressed = false
                        break
                    }
                    if (pressed.size < 2) continue

                    val first = pressed[0]
                    val second = pressed[1]
                    val distance = lyricPointerDistance(first.position, second.position)
                        .coerceAtLeast(1f)
                    if (baseDistance <= 0f) {
                        baseDistance = distance
                        lastScaleUptimeMs = first.uptimeMillis
                        continue
                    }
                    // Reference does not capture a scale owner just because a second pointer exists.
                    // The span must cross the platform slop first; otherwise a two-finger touch that
                    // is really a scroll/tap leaks into the zoom scene and causes a visible jump.
                    if (abs(distance - baseDistance) < viewConfiguration.touchSlop) continue
                    baseScale = currentScale().coerceIn(
                        LyricZoomSpec.MIN_SCALE,
                        LyricZoomSpec.MAX_SCALE,
                    )

                    // LayoutTransaction.begin() derives the source holders directly from the live
                    // measured viewport.  It never waits for target layout readiness, matching
                    // Reference: q1 starts ZoomIn/ZoomOut first; target layout preparation belongs to
                    // PowerListZoomTransitionBase and may complete immediately after capture.
                    captured = zoomState.begin(baseScale, currentAnchorIndex())
                    if (captured) {
                        val rawScale = LyricZoomSpec.rawScaleFromPinchRatio(
                            baseScale = baseScale,
                            pinchRatio = distance / baseDistance,
                        )
                        zoomState.updatePinch(rawScale)
                        lastSpan = distance
                        onGestureCaptured()
                        event.changes.forEach { change ->
                            if (change.pressed) change.consume()
                        }
                    }
                }

                if (!captured) {
                    null
                } else {
                    while (true) {
                        val move = awaitPointerEvent(PointerEventPass.Initial)
                        val activePointers = move.changes.filter { it.pressed }

                        // Once the second pointer has established ZoomGesture ownership, keep every
                        // remaining pointer in this stream away from LazyColumn, click, double-tap
                        // and PLAYER<-LYRIC swipe recognizers.  Reference likewise routes scale input
                        // through the list gesture owner rather than asking each child to re-arbitrate.
                        move.changes.forEach { it.consume() }

                        if (activePointers.size < 2) {
                            anyPointerStillPressed = activePointers.isNotEmpty()
                            // ScaleGesture finishes as soon as one pointer leaves. Start the visual
                            // settle on this frame; the remaining pointer is consumed below only to
                            // prevent it becoming a LazyColumn drag.
                            launchSettleAnimation(zoomState.releaseTargetScale())
                            break
                        }

                        val span = lyricPointerDistance(
                            activePointers[0].position,
                            activePointers[1].position,
                        ).coerceAtLeast(1f)
                        val rawScale = LyricZoomSpec.rawScaleFromPinchRatio(
                            baseScale = baseScale,
                            pinchRatio = span / baseDistance,
                        )
                        val now = move.changes.firstOrNull()?.uptimeMillis ?: lastScaleUptimeMs
                        val elapsedMs = (now - lastScaleUptimeMs).coerceAtLeast(1L)
                        rawScaleVelocityPercentPerSecond =
                            ((span - lastSpan) / density) * 1_000f / elapsedMs
                        zoomState.updatePinch(rawScale, rawScaleVelocityPercentPerSecond)
                        lastSpan = span
                        lastScaleUptimeMs = now
                    }

                    // ScaleGesture ends when one finger leaves, but keep the rest of that physical
                    // stream consumed until all fingers are up.  The visual release settle runs
                    // immediately afterwards and cannot leak a one-finger list scroll into the
                    // underlying LazyColumn.
                    while (anyPointerStillPressed) {
                        val tail = awaitPointerEvent(PointerEventPass.Initial)
                        tail.changes.forEach { it.consume() }
                        anyPointerStillPressed = tail.changes.any { it.pressed }
                    }

                    null
                }
            }
        }
        }
    } finally {
        settleGeneration++
        settleJob?.cancel()
        zoomState.cancel()
    }
}

private fun lyricPointerDistance(a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
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

            // A two-pointer sequence belongs to lyric zoom. Do not let the first finger of a pinch
            // arm the PLAYER <- LYRIC horizontal return gesture before the zoom owner captures it.
            if (event.changes.count { it.pressed } >= 2) {
                if (accepted) onSwipeRightEnd?.invoke(false, 0f)
                accepted = false
                rejected = true
            }
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
