package com.rawsmusic.core.ui.widget.powerlist

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.LocalSceneTransitionProgress
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.SizeSlotCache
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun rememberComposePowerListState(namespace: String = "default"): ComposePowerListState {
    val context = LocalContext.current
    return remember(context, namespace) {
        ComposePowerListState.fromContext(context, namespace)
    }
}

@Composable
fun ComposePowerList(
    songs: List<AudioFile>,
    state: ComposePowerListState,
    modifier: Modifier = Modifier,
    playingSongId: Long = -1L,
    currentPlayingIndex: Int = -1,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    contentTopPadding: Dp = 0.dp,
    sharedCoverSceneId: String = "",
    sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current
    val pendingTransitionScroll = remember { mutableStateOf<PendingTransitionScrollPx?>(null) }
    val transitionAnchor = remember { mutableStateOf<ComposeTransitionAnchor?>(null) }
    var listRootBounds by remember { mutableStateOf<RectF?>(null) }
    if (state.isTransitioning && transitionAnchor.value?.matches(state.sourceMode, state.targetMode) != true) {
        transitionAnchor.value = ComposeTransitionAnchor(
            sourceMode = state.sourceMode,
            targetMode = state.targetMode,
            sourceScrollYPx = state.viewportScrollY.roundToInt()
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                listRootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .powerListPointerInput(state, density.density)
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val metrics = remember(
            widthPx,
            density.density,
            density.fontScale,
            state.renderMode,
            state.currentParams
        ) {
            computePowerListMetrics(
                widthPx = widthPx,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
                mode = state.renderMode,
                params = state.currentParams
            )
        }

        val heightPx = with(density) { maxHeight.roundToPx() }
        val bottomPaddingPx = with(density) { 200.dp.roundToPx() }
        val contentTopPaddingPx = with(density) { contentTopPadding.roundToPx() }
        val maxScrollY = maxScrollForContent(
            itemCount = songs.size,
            metrics = metrics,
            viewportHeightPx = heightPx,
            bottomPaddingPx = bottomPaddingPx,
            topPaddingPx = contentTopPaddingPx
        ).toFloat()
        val geometry = remember(metrics, bottomPaddingPx, contentTopPaddingPx) {
            listGeometryFor(metrics, bottomPaddingPx, contentTopPaddingPx)
        }
        LaunchedEffect(
            revealIndexRequest,
            songs.size,
            geometry,
            state.renderMode,
            state.currentParams,
            heightPx,
            maxScrollY,
            listRootBounds,
            density.density
        ) {
            if (!state.isTransitioning && revealIndexRequest in songs.indices) {
                val targetScrollY = scrollYForIndex(
                    index = revealIndexRequest,
                    itemCount = songs.size,
                    geometry = geometry,
                    viewportHeightPx = heightPx
                ).coerceIn(0, maxScrollY.roundToInt())
                state.viewportScrollY = targetScrollY.toFloat()
                val revealSong = songs.getOrNull(revealIndexRequest)
                val target = exactCoverTargetForIndex(
                    index = revealIndexRequest,
                    geometry = geometry,
                    mode = state.renderMode,
                    params = state.currentParams,
                    scrollYPx = targetScrollY,
                    density = density.density,
                    rootBounds = listRootBounds,
                    songId = revealSong?.id ?: -1L,
                    coverKey = revealSong?.albumArtPath.orEmpty()
                )
                onRevealCoverTargetResolved(target)
            } else if (revealIndexRequest >= 0) {
                onRevealCoverTargetResolved(null)
            }
        }
        val scrollBucketHeight = geometry.rowStridePx.coerceAtLeast(1)
        val settledBaseScrollYState = remember(scrollBucketHeight) { mutableStateOf(0) }
        val settledScrollOffsetPx = remember(scrollBucketHeight) { mutableIntStateOf(0) }
        fun updateSettledBaseScroll(rawScrollY: Int) {
            val nextBase = (rawScrollY.coerceIn(0, maxScrollY.roundToInt()) / scrollBucketHeight) * scrollBucketHeight
            if (settledBaseScrollYState.value != nextBase) {
                settledBaseScrollYState.value = nextBase
            }
            settledScrollOffsetPx.intValue = rawScrollY.coerceIn(0, maxScrollY.roundToInt()) - nextBase
        }
        // 字母索引滚动请求（serial 模式，同 index 可重复触发）
        LaunchedEffect(
            state.scrollToIndexRequestSerial,
            state.isTransitioning,
            songs.size,
            geometry,
            heightPx,
            maxScrollY,
            scrollBucketHeight
        ) {
            val serial = state.scrollToIndexRequestSerial
            val targetIndex = state.scrollToIndexRequestIndex

            if (serial <= 0) return@LaunchedEffect

            if (targetIndex !in songs.indices) {
                state.consumeScrollToIndexRequest(serial)
                return@LaunchedEffect
            }

            if (state.isTransitioning) {
                return@LaunchedEffect
            }

            val targetScrollY = scrollYForIndex(
                index = targetIndex,
                itemCount = songs.size,
                geometry = geometry,
                viewportHeightPx = heightPx
            ).coerceIn(0, maxScrollY.roundToInt())

            pendingTransitionScroll.value = null
            transitionAnchor.value = null

            state.viewportScrollY = targetScrollY.toFloat()
            updateSettledBaseScroll(targetScrollY)

            state.consumeScrollToIndexRequest(serial)
        }
        LaunchedEffect(state.isTransitioning, state.currentMode, scrollBucketHeight, maxScrollY) {
            if (state.isTransitioning) {
                return@LaunchedEffect
            }
            val pending = pendingTransitionScroll.value
            if (pending != null) {
                val target = if (state.currentMode == pending.targetMode) pending.target else pending.source
                val clampedTarget = target.coerceIn(0, maxScrollY.roundToInt())
                state.viewportScrollY = clampedTarget.toFloat()
                updateSettledBaseScroll(clampedTarget)
                pendingTransitionScroll.value = null
                transitionAnchor.value = null
                return@LaunchedEffect
            }
            if (state.viewportScrollY > maxScrollY) {
                state.viewportScrollY = maxScrollY
            }
            updateSettledBaseScroll(state.viewportScrollY.toInt())
        }
        val scrollableState = rememberScrollableState { delta ->
            if (state.isTransitioning || state.isPinching || state.isBoundaryElasticActive) {
                delta
            } else {
                val old = state.viewportScrollY
                val newValue = (old - delta).coerceIn(0f, maxScrollY)
                state.viewportScrollY = newValue
                updateSettledBaseScroll(newValue.toInt())
                old - newValue
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scrollable(
                    orientation = Orientation.Vertical,
                    state = scrollableState
                )
        ) {
            if (state.isTransitioning) {
                val anchor = transitionAnchor.value ?: ComposeTransitionAnchor(
                    sourceMode = state.sourceMode,
                    targetMode = state.targetMode,
                    sourceScrollYPx = state.viewportScrollY.roundToInt()
                )
                ComposePowerListTransitionLayer(
                    songs = songs,
                    state = state,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    sourceScrollYPx = anchor.sourceScrollYPx,
                    playingSongId = playingSongId,
                    currentPlayingIndex = currentPlayingIndex,
                    selectedPositions = selectedPositions,
                    hidePlayingCover = hidePlayingCover,
                    contentTopPaddingPx = contentTopPaddingPx,
                    onPendingScrollChanged = { pendingTransitionScroll.value = it },
                    onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                    onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                    onSongClick = onSongClick,
                    onSongLongClick = onSongLongClick,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val pendingSettledScroll = pendingTransitionScroll.value?.let {
                    if (state.currentMode == it.targetMode) it.target else it.source
                }
                val settledBaseScrollY = if (pendingSettledScroll != null) {
                    val raw = pendingSettledScroll.coerceIn(0, maxScrollY.roundToInt())
                    (raw / scrollBucketHeight) * scrollBucketHeight
                } else {
                    settledBaseScrollYState.value
                }
                val settledRangeScrollY = if (pendingSettledScroll != null) {
                    pendingSettledScroll.coerceIn(0, maxScrollY.roundToInt())
                } else {
                    (settledBaseScrollY + settledScrollOffsetPx.intValue).coerceIn(0, maxScrollY.roundToInt())
                }
                ComposePowerListSettledContent(
                    songs = songs,
                    state = state,
                    metrics = metrics,
                    scrollYPx = settledBaseScrollY,
                    rangeScrollYPx = settledRangeScrollY,
                    viewportHeightPx = heightPx,
                    playingSongId = playingSongId,
                    currentPlayingIndex = currentPlayingIndex,
                    selectedPositions = selectedPositions,
                    hidePlayingCover = hidePlayingCover,
                    boundaryScale = state.boundaryElasticScale,
                    topPaddingPx = contentTopPaddingPx,
                    sharedCoverSceneId = sharedCoverSceneId,
                    sharedCoverElementIdProvider = sharedCoverElementIdProvider,
                    onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                    onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                    onSongClick = onSongClick,
                    onSongLongClick = onSongLongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            val offset = if (pendingSettledScroll != null) {
                                pendingSettledScroll.coerceIn(0, maxScrollY.roundToInt()) - settledBaseScrollY
                            } else {
                                settledScrollOffsetPx.intValue
                            }
                            androidx.compose.ui.unit.IntOffset(0, -offset)
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerListSettledContent(
    songs: List<AudioFile>,
    state: ComposePowerListState,
    metrics: ComposePowerListMetrics,
    scrollYPx: Int,
    rangeScrollYPx: Int,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScale: Float,
    topPaddingPx: Int,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ComposePowerListViewportLayer(
        songs = songs,
        mode = state.currentMode,
        params = state.currentParams,
        metrics = metrics,
        scrollYPx = scrollYPx,
        rangeScrollYPx = rangeScrollYPx,
        viewportHeightPx = viewportHeightPx,
        playingSongId = playingSongId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        hidePlayingCover = hidePlayingCover,
        boundaryScale = boundaryScale,
        topPaddingPx = topPaddingPx,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerListViewportLayer(
    songs: List<AudioFile>,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    metrics: ComposePowerListMetrics,
    scrollYPx: Int,
    rangeScrollYPx: Int,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScale: Float,
    topPaddingPx: Int,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
    ) {
    val geometry = remember(metrics, topPaddingPx) {
        listGeometryFor(metrics, bottomPaddingPx = 0, topPaddingPx = topPaddingPx)
    }
    val densityValue = LocalDensity.current.density
    val baseRange = visibleRangeForScroll(
        itemCount = songs.size,
        mode = mode,
        geometry = geometry,
        scrollYPx = rangeScrollYPx.coerceAtLeast(0),
        viewportHeightPx = viewportHeightPx
    )
    val range = if (baseRange.isEmpty() || abs(boundaryScale - 1f) < 0.001f) {
        baseRange
    } else {
        expandRangeByRows(
            range = baseRange,
            itemCount = songs.size,
            columns = geometry.columns,
            rowsBefore = 2,
            rowsAfter = 2
        )
    }
    val prefetchRange = if (range.isEmpty()) {
        IntRange.EMPTY
    } else {
        expandRangeByRows(
            range = range,
            itemCount = songs.size,
            columns = geometry.columns,
            rowsBefore = 0,
            rowsAfter = if (mode.isGrid) 1 else 2
        )
    }
    LaunchedEffect(prefetchRange.first, prefetchRange.last, mode, params, metrics) {
        prewarmSettledBitmaps(
            songs = songs,
            range = prefetchRange,
            visibleRange = range,
            mode = mode,
            params = params,
            geometry = geometry,
            scrollYPx = rangeScrollYPx.coerceAtLeast(0),
            density = densityValue
        )
    }
    Box(modifier = modifier) {
        if (range.isEmpty()) return@Box
        for (index in range.first..range.last) {
            val song = songs.getOrNull(index) ?: continue
            val itemKey = powerListKey(song, index)
            val position = positionFor(
                index = index,
                geometry = geometry,
                mode = mode,
                scrollYPx = scrollYPx.coerceAtLeast(0)
            )
            if (position.isEmpty()) continue
            val isPlaying = if (playingSongId > 0L) {
                song.id == playingSongId
            } else {
                index == currentPlayingIndex
            }
            val hideCover = hidePlayingCover && isPlaying
            ComposePowerListTransitionItem(
                compositionSlot = "settled-slot-$itemKey",
                song = song,
                index = index,
                revealBaseIndex = range.first,
                mode = mode,
                params = params,
                position = position,
                deferBitmapLoad = false,
                isPlaying = isPlaying,
                isSelected = index in selectedPositions,
                selectionActive = selectedPositions.isNotEmpty(),
                boundaryScale = boundaryScale,
                hideCover = hideCover,
                sharedCoverSceneId = sharedCoverSceneId,
                sharedCoverElementId = sharedCoverElementIdProvider(song, index),
                onCoverBoundsChanged = if (isPlaying) onPlayingCoverBoundsChanged else { _: RectF? -> },
                onCoverTargetChanged = if (isPlaying) onPlayingCoverTargetChanged else { _: CoverTransitionTarget? -> },
                onClick = { target ->
                    onPlayingCoverBoundsChanged(target?.bounds)
                    onPlayingCoverTargetChanged(target)
                    onSongClick(song, index)
                },
                onLongClick = { onSongLongClick(song, index) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerListTransitionLayer(
    songs: List<AudioFile>,
    state: ComposePowerListState,
    widthPx: Int,
    heightPx: Int,
    sourceScrollYPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    contentTopPaddingPx: Int,
    onPendingScrollChanged: (PendingTransitionScrollPx) -> Unit,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sourceParams = state.sourceMode.listLevel?.let { paramsFor(it) } ?: paramsFor(state.currentLevel)
    val targetParams = state.targetMode.listLevel?.let { paramsFor(it) } ?: paramsFor(state.currentLevel)
    val sourceMetrics = remember(widthPx, density.density, density.fontScale, state.sourceMode, sourceParams) {
        computePowerListMetrics(
            widthPx = widthPx,
            density = density.density,
            scaledDensity = density.density * density.fontScale,
            mode = state.sourceMode,
            params = sourceParams
        )
    }
    val targetMetrics = remember(widthPx, density.density, density.fontScale, state.targetMode, targetParams) {
        computePowerListMetrics(
            widthPx = widthPx,
            density = density.density,
            scaledDensity = density.density * density.fontScale,
            mode = state.targetMode,
            params = targetParams
        )
    }
    val bottomPaddingPx = with(density) { 200.dp.roundToPx() }
    val sourceGeometry = remember(sourceMetrics, bottomPaddingPx, contentTopPaddingPx) {
        listGeometryFor(sourceMetrics, bottomPaddingPx, contentTopPaddingPx)
    }
    val targetGeometry = remember(targetMetrics, bottomPaddingPx, contentTopPaddingPx) {
        listGeometryFor(targetMetrics, bottomPaddingPx, contentTopPaddingPx)
    }
    val scrollModel = remember(
        state.sourceMode,
        state.targetMode,
        sourceGeometry,
        targetGeometry,
        heightPx,
        sourceScrollYPx,
        songs.size
    ) {
        transitionScrollModel(
            itemCount = songs.size,
            sourceMode = state.sourceMode,
            targetMode = state.targetMode,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            viewportHeightPx = heightPx,
            sourceScrollYPx = sourceScrollYPx
        )
    }
    SideEffect {
        onPendingScrollChanged(scrollModel.toPendingScroll(state.sourceMode, state.targetMode))
    }
    val slotPool = remember(state.sourceMode, state.targetMode) { ComposeSlotPool() }
    val transitionItems = remember(
        songs,
        state.sourceMode,
        state.targetMode,
        sourceParams,
        targetParams,
        sourceGeometry,
        targetGeometry,
        scrollModel,
        density.density,
        slotPool
    ) {
        buildTransitionItems(
            songs = songs,
            sourceMode = state.sourceMode,
            targetMode = state.targetMode,
            sourceParams = sourceParams,
            targetParams = targetParams,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            scrollModel = scrollModel,
            density = density.density,
            slotPool = slotPool
        )
    }
    LaunchedEffect(transitionItems) {
        prewarmTransitionBitmaps(transitionItems)
    }
    val progressProvider = remember(state) { { state.transitionProgress.coerceIn(0f, 1f) } }
    val zoomInTransition = powerListModeOrder(state.targetMode) > powerListModeOrder(state.sourceMode)
    Box(modifier = modifier) {
        for (item in transitionItems) {
            val song = item.song
            val index = item.index
            val isPlaying = if (playingSongId > 0L) {
                song.id == playingSongId
            } else {
                index == currentPlayingIndex
            }

            when (item.kind) {
                ComposeTransitionItemKind.SHARED -> {
                    ComposePowerListInterpolatedItem(
                        compositionSlot = item.compositionSlot,
                        song = song,
                        index = index,
                        sourcePosition = item.sourcePosition,
                        targetPosition = item.targetPosition,
                        sourceRects = item.sourceRects,
                        targetRects = item.targetRects,
                        progressProvider = progressProvider,
                        isPlaying = isPlaying,
                        isSelected = index in selectedPositions,
                        selectionActive = selectedPositions.isNotEmpty(),
                        onCoverBoundsChanged = if (isPlaying) onPlayingCoverBoundsChanged else { _: RectF? -> },
                        onCoverTargetChanged = if (isPlaying) onPlayingCoverTargetChanged else { _: CoverTransitionTarget? -> },
                        onClick = { target ->
                            onPlayingCoverBoundsChanged(target?.bounds)
                            onPlayingCoverTargetChanged(target)
                            onSongClick(song, index)
                        },
                        onLongClick = { onSongLongClick(song, index) }
                    )
                }
                ComposeTransitionItemKind.SOURCE_ONLY -> ComposePowerListOneSlotItem(
                    compositionSlot = item.compositionSlot,
                    song = song,
                    index = index,
                    mode = state.sourceMode,
                    params = sourceParams,
                    basePosition = item.sourcePosition,
                    fadeOut = true,
                    pivotX = widthPx / 2f,
                    pivotY = heightPx / 2f,
                    oneSlotScaleBase = if (zoomInTransition) 1.5f else 0.5f,
                    progressProvider = progressProvider,
                    isPlaying = isPlaying,
                    isSelected = index in selectedPositions,
                    selectionActive = selectedPositions.isNotEmpty(),
                    onCoverBoundsChanged = if (isPlaying) onPlayingCoverBoundsChanged else { _: RectF? -> },
                    onCoverTargetChanged = if (isPlaying) onPlayingCoverTargetChanged else { _: CoverTransitionTarget? -> },
                    onClick = { target ->
                        onPlayingCoverBoundsChanged(target?.bounds)
                        onPlayingCoverTargetChanged(target)
                        onSongClick(song, index)
                    },
                    onLongClick = { onSongLongClick(song, index) }
                )
                ComposeTransitionItemKind.TARGET_ONLY -> ComposePowerListOneSlotItem(
                    compositionSlot = item.compositionSlot,
                    song = song,
                    index = index,
                    mode = state.targetMode,
                    params = targetParams,
                    basePosition = item.targetPosition,
                    fadeOut = false,
                    pivotX = widthPx / 2f,
                    pivotY = heightPx / 2f,
                    oneSlotScaleBase = if (zoomInTransition) 0.5f else 1.5f,
                    progressProvider = progressProvider,
                    isPlaying = isPlaying,
                    isSelected = index in selectedPositions,
                    selectionActive = selectedPositions.isNotEmpty(),
                    onCoverBoundsChanged = if (isPlaying) onPlayingCoverBoundsChanged else { _: RectF? -> },
                    onCoverTargetChanged = if (isPlaying) onPlayingCoverTargetChanged else { _: CoverTransitionTarget? -> },
                    onClick = { target ->
                        onPlayingCoverBoundsChanged(target?.bounds)
                        onPlayingCoverTargetChanged(target)
                        onSongClick(song, index)
                    },
                    onLongClick = { onSongLongClick(song, index) }
                )
            }
        }
    }
}

@Composable
private fun ComposePowerListTransitionItem(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    revealBaseIndex: Int = index,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    position: ComposeItemPosition,
    layoutPosition: ComposeItemPosition = position,
    deferBitmapLoad: Boolean = false,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    hideCover: Boolean = false,
    sharedCoverSceneId: String = "",
    sharedCoverElementId: String = "",
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    contentAlpha: Float = 1f
) {
    val density = LocalDensity.current
    val itemModifier = Modifier
        .offset {
            androidx.compose.ui.unit.IntOffset(
                layoutPosition.bounds.left,
                layoutPosition.bounds.top
            )
        }
        .requiredSize(
            width = with(density) { layoutPosition.width.toDp() },
            height = with(density) { layoutPosition.height.toDp() }
        )
        .graphicsLayer { alpha = position.alpha.coerceIn(0f, 1f) }

    key(compositionSlot) {
        Box(modifier = itemModifier.graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }) {
            ComposePowerListDrawnItem(
                song = song,
                index = index,
                revealBaseIndex = revealBaseIndex,
                mode = mode,
                params = params,
                position = position,
                deferBitmapLoad = deferBitmapLoad,
                isPlaying = isPlaying,
                isSelected = isSelected,
                selectionActive = selectionActive,
                boundaryScale = boundaryScale,
                hideCover = hideCover,
                sharedCoverSceneId = sharedCoverSceneId,
                sharedCoverElementId = sharedCoverElementId,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onCoverTargetChanged = onCoverTargetChanged,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ComposePowerListInterpolatedItem(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    sourcePosition: ComposeItemPosition,
    targetPosition: ComposeItemPosition,
    sourceRects: ComposeTransitionRects,
    targetRects: ComposeTransitionRects,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit
) {
    val density = LocalDensity.current
    key(compositionSlot) {
        Box(
            modifier = Modifier
                .offset {
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    androidx.compose.ui.unit.IntOffset(
                        frame.bounds.left,
                        frame.bounds.top
                    )
                }
                .requiredSize(
                    width = with(density) {
                        dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider()).width.coerceAtLeast(1).toDp()
                    },
                    height = with(density) {
                        dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider()).height.coerceAtLeast(1).toDp()
                    }
                )
                .graphicsLayer {
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    alpha = frame.alpha
                    scaleX = frame.scaleX
                    scaleY = frame.scaleY
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            ComposePowerTransitionVisualFast(
                song = song,
                index = index,
                source = sourceRects,
                target = targetRects,
                progressProvider = progressProvider,
                isPlaying = isPlaying,
                isSelected = isSelected,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onCoverTargetChanged = onCoverTargetChanged,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ComposePowerListOneSlotItem(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    basePosition: ComposeItemPosition,
    fadeOut: Boolean,
    pivotX: Float,
    pivotY: Float,
    oneSlotScaleBase: Float,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit
) {
    val density = LocalDensity.current
    key(compositionSlot) {
        Box(
            modifier = Modifier
                .offset {
                    val frame = oneSlotRenderFrame(
                        basePosition = basePosition,
                        scaleBase = oneSlotScaleBase,
                        transitionFraction = if (fadeOut) progressProvider() else 1f - progressProvider(),
                        pivotX = pivotX,
                        pivotY = pivotY
                    )
                    androidx.compose.ui.unit.IntOffset(
                        frame.bounds.left,
                        frame.bounds.top
                    )
                }
                .requiredSize(
                    width = with(density) { basePosition.width.coerceAtLeast(1).toDp() },
                    height = with(density) { basePosition.height.coerceAtLeast(1).toDp() }
                )
                .graphicsLayer {
                    val p = progressProvider()
                    val f = if (fadeOut) p else 1f - p
                    val frame = oneSlotRenderFrame(
                        basePosition = basePosition,
                        scaleBase = oneSlotScaleBase,
                        transitionFraction = f,
                        pivotX = pivotX,
                        pivotY = pivotY
                    )
                    alpha = frame.alpha
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    scaleX = frame.scaleX
                    scaleY = frame.scaleY
                }
        ) {
            ComposePowerListDrawnItem(
                song = song,
                index = index,
                revealBaseIndex = index,
                mode = mode,
                params = params,
                position = basePosition,
                deferBitmapLoad = false,
                isPlaying = isPlaying,
                isSelected = isSelected,
                selectionActive = selectionActive,
                boundaryScale = boundaryScale,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onCoverTargetChanged = onCoverTargetChanged,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerListDrawnItem(
    song: AudioFile,
    index: Int,
    revealBaseIndex: Int = index,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    position: ComposeItemPosition,
    deferBitmapLoad: Boolean = false,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    hideCover: Boolean = false,
    sharedCoverSceneId: String = "",
    sharedCoverElementId: String = "",
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val dark = ThemeManager.isDarkMode(context)
    val colors = powerListColors(dark, isPlaying, isSelected)
    val selectionGutterPx = if (selectionActive && !mode.isGrid) {
        ((position.height * 0.32f).roundToInt())
            .coerceIn(with(density) { 28.dp.roundToPx() }, with(density) { 44.dp.roundToPx() })
    } else {
        0
    }
    val edgeScale = boundaryScale.coerceIn(0.9105f, 1.0895f)
    val rects = remember(position, mode, params, density.density, selectionGutterPx, edgeScale) {
        val base = composeAAItemSceneRects(position, mode, params, density.density)
        val selectedBase = if (selectionGutterPx > 0) base.offsetForListSelection(selectionGutterPx) else base
        if (edgeScale == 1f) {
            selectedBase
        } else {
            selectedBase.scaledAbout(
                scale = edgeScale,
                pivotX = position.width * 0.5f,
                pivotY = position.height * 0.5f
            )
        }
    }
    val coverSize = max(rects.cover.width, rects.cover.height).coerceAtLeast(1)
    val bitmap = rememberPowerListBitmap(
        key = song.albumArtPath,
        targetWidth = coverSize,
        targetHeight = coverSize,
        deferLoad = deferBitmapLoad,
        index = index,
        modeLabel = mode.name
    )
    val coverAlpha = remember(song.albumArtPath) {
        Animatable(if (bitmap != null && !bitmap.isRecycled) 1f else 0f)
    }
    var lastDrawnBitmap by remember(song.albumArtPath) {
        mutableStateOf<Bitmap?>(bitmap?.takeIf { !it.isRecycled })
    }
    LaunchedEffect(bitmap, song.albumArtPath, revealBaseIndex) {
        val current = bitmap?.takeIf { !it.isRecycled }
        if (current == null) {
            lastDrawnBitmap = null
            coverAlpha.snapTo(0f)
            powerListArtLog(
                "DISPLAY_EMPTY index=$index mode=$mode size=$coverSize defer=$deferBitmapLoad key=${song.albumArtPath.tailForLog()}"
            )
            return@LaunchedEffect
        }
        val previous = lastDrawnBitmap
        lastDrawnBitmap = current
        powerListArtLog(
            "DISPLAY_READY index=$index mode=$mode size=$coverSize bitmap=${current.width}x${current.height} first=${previous == null} key=${song.albumArtPath.tailForLog()}"
        )
        val animEnabled = runCatching {
            com.rawsmusic.module.data.prefs.AppPreferences.AlbumArt.coverAnimation
        }.getOrDefault(true)
        if (!animEnabled) {
            coverAlpha.snapTo(1f)
        } else if (previous == null) {
            val revealOffset = (index - revealBaseIndex).coerceAtLeast(0).coerceAtMost(36)
            delay((revealOffset * POWER_LIST_REVEAL_STAGGER_MS).toLong())
            coverAlpha.snapTo(0f)
            coverAlpha.animateTo(1f, tween(durationMillis = 260))
        } else if (previous !== current) {
            coverAlpha.snapTo(0.28f)
            coverAlpha.animateTo(1f, tween(durationMillis = 180))
        } else if (coverAlpha.value < 1f) {
            coverAlpha.animateTo(1f, tween(durationMillis = 160))
        }
    }
    val title = song.displayName
    val subtitle = song.subtitle()
    val meta = song.metaText()

    // 场景过渡进度（由 SceneTransitionHost 通过 CompositionLocal 提供）
    val transitionProgress = LocalSceneTransitionProgress.current

    // 文本颜色动画状态
    // 当场景过渡时，使用逐通道 ARGB 插值实现平滑颜色渐变
    val titleAnimState = remember { FastTextAnimState() }
    val subtitleAnimState = remember { FastTextAnimState() }
    val metaAnimState = remember { FastTextAnimState() }

    // 在 draw 块外准备颜色：当过渡进度变化时更新插值状态
    val titleColorArgb = colors.title.toArgb()
    val subtitleColorArgb = colors.secondary.toArgb()
    val metaColorArgb = colors.meta.toArgb()

    // 场景切换时设置颜色过渡
    // 注意：当前同一 item 在过渡中颜色不变（isPlaying/isSelected 不变），
    // 所以 fromColor == toColor，interpolateColor() 直接返回 toColor。
    // 当未来场景级别颜色变化时（如不同场景用不同配色），
    // fromColor 和 toColor 会不同，插值将产生平滑颜色渐变。
    titleAnimState.setupTransition(titleColorArgb, titleColorArgb)
    subtitleAnimState.setupTransition(subtitleColorArgb, subtitleColorArgb)
    metaAnimState.setupTransition(metaColorArgb, metaColorArgb)

    // 更新插值因子
    titleAnimState.updateRatio(transitionProgress)
    subtitleAnimState.updateRatio(transitionProgress)
    metaAnimState.updateRatio(transitionProgress)

    // 获取插值后的颜色
    val titleColor = Color(titleAnimState.interpolateColor())
    val subtitleColor = Color(subtitleAnimState.interpolateColor())
    val metaColor = Color(metaAnimState.interpolateColor())

    var rootBounds by remember { mutableStateOf<RectF?>(null) }
    val shouldHideForSharedCover = shouldHideSharedCover(
        sceneId = sharedCoverSceneId,
        elementId = sharedCoverElementId
    )

    fun clickedCoverTarget(): CoverTransitionTarget? {
        val root = rootBounds ?: return null
        val cover = rects.cover
        val bounds = RectF(
            root.left + cover.left,
            root.top + cover.top,
            root.left + cover.left + cover.width,
            root.top + cover.top + cover.height
        )
        return CoverTransitionTarget(
            bounds = bounds,
            radiusDp = rects.coverRadiusDp,
            source = CoverTransitionTarget.Source.ListCover,
            songId = song.id,
            coverKey = song.albumArtPath
        )
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                rootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .then(if (isPlaying) Modifier.trackDrawnCoverBounds(rects.cover, onCoverBoundsChanged) else Modifier)
            .then(if (isPlaying) Modifier.trackDrawnCoverTarget(rects.cover, rects.coverRadiusDp, song.id, song.albumArtPath, -1, onCoverTargetChanged) else Modifier)
            .trackSharedCoverSlot(
                sceneId = sharedCoverSceneId,
                elementId = sharedCoverElementId,
                cover = rects.cover,
                radiusDp = rects.coverRadiusDp,
                coverKey = song.albumArtPath
            )
            .combinedClickable(onClick = { onClick(clickedCoverTarget()) }, onLongClick = onLongClick)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            if (colors.background.alpha > 0f) {
                powerListPaint.color = colors.background.toArgb()
                powerListPaint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, size.width, size.height, powerListPaint)
            }

            val cover = rects.cover
            val coverLeft = cover.left.toFloat()
            val coverTop = cover.top.toFloat()
            val coverRight = coverLeft + cover.width
            val coverBottom = coverTop + cover.height
            val radius = rects.coverRadiusDp * density.density
            powerListPath.reset()
            powerListPath.addRoundRect(
                coverLeft,
                coverTop,
                coverRight,
                coverBottom,
                radius,
                radius,
                Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(powerListPath)
            powerListPaint.shader = null
            if (hideCover || shouldHideForSharedCover) {
                // Keep the slot empty while the shared cover overlay is flying.
            } else {
                powerListBitmapPaint.alpha = 255
                powerListPaint.color = colors.secondary.copy(alpha = 0.085f).toArgb()
                canvas.drawRoundRect(coverLeft, coverTop, coverRight, coverBottom, radius, radius, powerListPaint)
            }
            if (!hideCover && !shouldHideForSharedCover && bitmap != null && !bitmap.isRecycled) {
                powerListBitmapMatrix.reset()
                configureCenterCropMatrix(
                    matrix = powerListBitmapMatrix,
                    bitmap = bitmap,
                    left = coverLeft,
                    top = coverTop,
                    width = cover.width.toFloat(),
                    height = cover.height.toFloat()
                )
                powerListBitmapPaint.alpha = (coverAlpha.value.coerceIn(0f, 1f) * 255f).roundToInt()
                canvas.drawBitmap(bitmap, powerListBitmapMatrix, powerListBitmapPaint)
                powerListBitmapPaint.alpha = 255
            }
            canvas.restore()

            drawPowerListText(
                canvas = canvas,
                text = title,
                rect = rects.title,
                color = titleColor,
                density = density.density,
                bold = true
            )
            drawPowerListText(
                canvas = canvas,
                text = subtitle,
                rect = rects.subtitle,
                color = subtitleColor,
                density = density.density,
                bold = false
            )
            drawPowerListText(
                canvas = canvas,
                text = meta,
                rect = rects.meta,
                color = metaColor,
                density = density.density,
                bold = false
            )
            canvas.restore()
        }

        SelectionCheckOverlay(
            visible = selectionActive,
            selected = isSelected,
            listMode = !mode.isGrid,
            itemHeightPx = position.height,
            gutterWidthPx = selectionGutterPx,
            cover = rects.cover,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SelectionCheckOverlay(
    visible: Boolean,
    selected: Boolean,
    listMode: Boolean,
    itemHeightPx: Int,
    gutterWidthPx: Int,
    cover: ComposeItemRect,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val boxPx = if (listMode) {
        (itemHeightPx * 0.34f).roundToInt()
            .coerceIn(with(density) { 14.dp.roundToPx() }, with(density) { 22.dp.roundToPx() })
    } else {
        (cover.width * 0.16f).roundToInt()
            .coerceIn(with(density) { 16.dp.roundToPx() }, with(density) { 28.dp.roundToPx() })
    }
    val boxSize = with(density) { boxPx.toDp() }
    val offsetPx = if (listMode) 0 else with(density) { 4.dp.roundToPx() }
    val leftPx = if (listMode) {
        ((gutterWidthPx - boxPx) / 2).coerceAtLeast(with(density) { 6.dp.roundToPx() })
    } else {
        cover.left + offsetPx
    }
    val topPx = if (listMode) {
        ((itemHeightPx - boxPx) / 2).coerceAtLeast(0)
    } else {
        cover.top + offsetPx
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = leftPx,
                        y = topPx
                    )
                }
                .size(boxSize),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + scaleIn(
                    initialScale = 0.68f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                exit = fadeOut() + scaleOut(targetScale = 0.68f),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (selected) 1f else 0.78f
                            scaleX = if (selected) 1f else 0.86f
                            scaleY = if (selected) 1f else 0.86f
                        }
                        .background(
                            color = if (selected) Color(0xFF2F7DFF) else Color.Black.copy(alpha = 0.38f),
                            shape = RoundedCornerShape(50)
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Ok,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private fun ComposeTransitionRects.offsetForListSelection(dx: Int): ComposeTransitionRects {
    fun ComposeItemRect.move(shrink: Boolean = false): ComposeItemRect {
        return copy(
            left = left + dx,
            width = if (shrink) (width - dx).coerceAtLeast(1) else width
        )
    }
    return copy(
        cover = cover.move(),
        title = title.move(shrink = true),
        subtitle = subtitle.move(shrink = true),
        meta = meta.move(shrink = true)
    )
}

private fun ComposeTransitionRects.scaledAbout(
    scale: Float,
    pivotX: Float,
    pivotY: Float
): ComposeTransitionRects {
    fun ComposeItemRect.scaleRect(): ComposeItemRect {
        val leftF = pivotX + (left - pivotX) * scale
        val topF = pivotY + (top - pivotY) * scale
        return copy(
            left = leftF.roundToInt(),
            top = topF.roundToInt(),
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1),
            fontSizeSp = fontSizeSp * scale
        )
    }
    return copy(
        cover = cover.scaleRect(),
        title = title.scaleRect(),
        subtitle = subtitle.scaleRect(),
        meta = meta.scaleRect()
    )
}

@Composable
private fun rememberPowerListBitmap(
    key: String,
    targetWidth: Int,
    targetHeight: Int,
    deferLoad: Boolean = false,
    index: Int = -1,
    modeLabel: String = ""
): Bitmap? {
    val decodeWidth = remember(targetWidth) {
        targetWidth.coerceAtLeast(1).coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
    }
    val decodeHeight = remember(targetHeight) {
        targetHeight.coerceAtLeast(1).coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
    }
    val bucket = remember(decodeWidth, decodeHeight) {
        SizeSlotCache.computeBucket(decodeWidth, decodeHeight)
    }
    val memoryKey = remember(key, bucket) { "$key@$bucket" }
    val bitmapState = remember(memoryKey) {
        mutableStateOf(
            if (key.isBlank()) null else powerListRememberedBitmap(memoryKey)?.also {
                powerListArtLog(
                    "CACHE_HIT_LOCAL index=$index mode=$modeLabel bucket=$bucket size=${decodeWidth}x${decodeHeight} key=${key.tailForLog()}"
                )
            }
                ?: BitmapProvider.peekThumbnail(key, decodeWidth, decodeHeight)?.also {
                    powerListArtLog(
                        "CACHE_HIT_THUMB index=$index mode=$modeLabel bucket=$bucket size=${decodeWidth}x${decodeHeight} key=${key.tailForLog()}"
                    )
                    rememberPowerListBitmap(memoryKey, it)
                } ?: BitmapProvider.peekAny(key)?.also {
                    powerListArtLog(
                        "CACHE_HIT_ANY index=$index mode=$modeLabel bucket=$bucket bitmap=${it.width}x${it.height} key=${key.tailForLog()}"
                    )
                    rememberPowerListBitmap(memoryKey, it)
                }
        )
    }
    LaunchedEffect(memoryKey, targetWidth, targetHeight, deferLoad, index, modeLabel) {
        if (key.isBlank()) {
            powerListArtLog("REQUEST_SKIP_BLANK index=$index mode=$modeLabel")
            bitmapState.value = null
            return@LaunchedEffect
        }
        powerListArtLog(
            "VISIBLE index=$index mode=$modeLabel target=${targetWidth}x${targetHeight} decode=${decodeWidth}x${decodeHeight} defer=$deferLoad key=${key.tailForLog()}"
        )
        powerListRememberedBitmap(memoryKey)?.let { remembered ->
            powerListArtLog(
                "CACHE_HIT_LOCAL_EFFECT index=$index mode=$modeLabel bucket=$bucket bitmap=${remembered.width}x${remembered.height} key=${key.tailForLog()}"
            )
            bitmapState.value = remembered
            return@LaunchedEffect
        }
        val cached = BitmapProvider.peekThumbnail(key, decodeWidth, decodeHeight)
            ?: BitmapProvider.peek(key, targetWidth, targetHeight)
            ?: BitmapProvider.peekAny(key)
        if (cached != null && !cached.isRecycled) {
            powerListArtLog(
                "CACHE_HIT_PROVIDER_EFFECT index=$index mode=$modeLabel bucket=$bucket bitmap=${cached.width}x${cached.height} key=${key.tailForLog()}"
            )
            rememberPowerListBitmap(memoryKey, cached)
            bitmapState.value = cached
            return@LaunchedEffect
        }
        if (deferLoad) {
            powerListArtLog(
                "REQUEST_DEFER index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} key=${key.tailForLog()}"
            )
            return@LaunchedEffect
        }
    }
    DisposableEffect(memoryKey, targetWidth, targetHeight, deferLoad, index, modeLabel) {
        if (key.isBlank() || deferLoad || bitmapState.value?.isRecycled == false) {
            onDispose { }
        } else {
            val seq = powerListArtSeq.incrementAndGet()
            powerListArtLog(
                "REQUEST_LOAD seq=$seq index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} key=${key.tailForLog()}"
            )
            val request = BitmapProvider.loadThumbnail(
                key = key,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                priority = BitmapRequest.Priority.LOADING_LIST
            ) { loaded ->
                if (loaded != null && !loaded.isRecycled) {
                    powerListArtLog(
                        "REQUEST_CALLBACK seq=$seq index=$index mode=$modeLabel bitmap=${loaded.width}x${loaded.height} key=${key.tailForLog()}"
                    )
                    rememberPowerListBitmap(memoryKey, loaded)
                    bitmapState.value = loaded
                } else {
                    powerListArtLog(
                        "REQUEST_CALLBACK_NULL seq=$seq index=$index mode=$modeLabel key=${key.tailForLog()}"
                    )
                }
            }
            onDispose {
                powerListArtLog(
                    "REQUEST_DISPOSE_KEEP_CACHE seq=$seq index=$index mode=$modeLabel cancelled=${request.isCancelled} key=${key.tailForLog()}"
                )
                BitmapProvider.cancel(request, keepDecoding = true)
            }
        }
    }
    return bitmapState.value
}

private const val POWER_LIST_COVER_DECODE_MAX = 256
private const val POWER_LIST_PREFETCH_MAX_ITEMS = 8
private const val POWER_LIST_REVEAL_STAGGER_MS = 14
private const val POWER_LIST_BITMAP_MEMORY_MAX = 384
private const val POWER_LIST_ART_TAG = "PowerListArtTrace"
private val powerListArtSeq = AtomicLong(0L)
private val powerListBitmapMemoryLock = Any()
private val powerListBitmapMemory = object : LinkedHashMap<String, Bitmap>(
    POWER_LIST_BITMAP_MEMORY_MAX,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
        return size > POWER_LIST_BITMAP_MEMORY_MAX
    }
}

private fun powerListRememberedBitmap(memoryKey: String): Bitmap? {
    val bitmap = synchronized(powerListBitmapMemoryLock) {
        powerListBitmapMemory[memoryKey]
    } ?: return null
    if (bitmap.isRecycled) {
        synchronized(powerListBitmapMemoryLock) {
            powerListBitmapMemory.remove(memoryKey)
        }
        return null
    }
    return bitmap
}

private fun rememberPowerListBitmap(memoryKey: String, bitmap: Bitmap) {
    if (!bitmap.isRecycled) {
        synchronized(powerListBitmapMemoryLock) {
            powerListBitmapMemory[memoryKey] = bitmap
        }
    }
}

private fun powerListArtLog(message: String) {
    Log.d(POWER_LIST_ART_TAG, message)
}

private fun String.tailForLog(): String {
    return takeLast(72)
}

@Composable
private fun PowerListBitmapCanvas(
    bitmap: Bitmap?,
    placeholderColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val canvas = drawContext.canvas.nativeCanvas
        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            powerListBitmapMatrix.reset()
            configureCenterCropMatrix(
                matrix = powerListBitmapMatrix,
                bitmap = currentBitmap,
                left = 0f,
                top = 0f,
                width = size.width,
                height = size.height
            )
            powerListBitmapPaint.alpha = 255
            canvas.drawBitmap(currentBitmap, powerListBitmapMatrix, powerListBitmapPaint)
        } else if (placeholderColor.alpha > 0f) {
            powerListPaint.shader = null
            powerListPaint.color = placeholderColor.toArgb()
            powerListPaint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, size.width, size.height, powerListPaint)
        }
    }
}

private fun Modifier.trackDrawnCoverBounds(
    cover: ComposeItemRect,
    onBoundsChanged: (RectF?) -> Unit
): Modifier {
    return composed {
        val lastBounds = remember { arrayOfNulls<RectF>(1) }
        onGloballyPositioned { coordinates ->
            val itemBounds = coordinates.boundsInRoot()
            val rect = RectF(
                itemBounds.left + cover.left,
                itemBounds.top + cover.top,
                itemBounds.left + cover.left + cover.width,
                itemBounds.top + cover.top + cover.height
            )
            val previous = lastBounds[0]
            if (previous == null || !previous.nearlyEquals(rect, tolerance = 1f)) {
                lastBounds[0] = RectF(rect)
                onBoundsChanged(rect)
            }
        }
    }
}

@Composable
private fun shouldHideSharedCover(
    sceneId: String,
    elementId: String
): Boolean {
    if (sceneId.isBlank() || elementId.isBlank()) return false

    val spec = LocalSharedTransitionSpec.current
    if (!spec.active) return false

    val isEndpoint = sceneId == spec.fromSceneId || sceneId == spec.toSceneId
    if (!isEndpoint) return false

    val registry = LocalSharedCoverRegistry.current
    return registry.hasPair(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId,
        elementId = elementId
    )
}

private fun Modifier.trackSharedCoverSlot(
    sceneId: String,
    elementId: String,
    cover: ComposeItemRect,
    radiusDp: Float,
    coverKey: String
): Modifier {
    if (sceneId.isBlank() || elementId.isBlank() || coverKey.isBlank()) return this

    return composed {
        val registry = LocalSharedCoverRegistry.current

        DisposableEffect(sceneId, elementId) {
            onDispose {
                registry.unregister(sceneId, elementId)
            }
        }

        onGloballyPositioned { coordinates ->
            val pos = coordinates.positionInWindow()
            registry.register(
                sceneId = sceneId,
                elementId = elementId,
                snapshot = SharedCoverSnapshot(
                    sceneId = sceneId,
                    elementId = elementId,
                    boundsInWindow = Rect(
                        left = pos.x + cover.left,
                        top = pos.y + cover.top,
                        right = pos.x + cover.left + cover.width,
                        bottom = pos.y + cover.top + cover.height
                    ),
                    coverKey = coverKey,
                    radiusDp = radiusDp
                )
            )
        }
    }
}

private val powerListPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val powerListBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
private val powerListTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
private val powerListBitmapMatrix = Matrix()
private val powerListPath = Path()

private fun configureCenterCropMatrix(
    matrix: Matrix,
    bitmap: Bitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float
) {
    val bitmapWidth = bitmap.width.toFloat().coerceAtLeast(1f)
    val bitmapHeight = bitmap.height.toFloat().coerceAtLeast(1f)
    val scale: Float
    val dx: Float
    val dy: Float
    if (bitmapWidth * height > width * bitmapHeight) {
        scale = height / bitmapHeight
        dx = (width - bitmapWidth * scale) * 0.5f
        dy = 0f
    } else {
        scale = width / bitmapWidth
        dx = 0f
        dy = (height - bitmapHeight * scale) * 0.5f
    }
    matrix.setScale(scale, scale)
    matrix.postTranslate(left + dx, top + dy)
}

private fun drawPowerListText(
    canvas: android.graphics.Canvas,
    text: String,
    rect: ComposeItemRect,
    color: Color,
    density: Float,
    bold: Boolean
) {
    if (text.isBlank() || rect.alpha <= 0f || rect.width <= 0 || rect.height <= 0) return
    val fontSizeSp = rect.fontSizeSp.takeIf { it > 0f } ?: 14f
    powerListTextPaint.color = color.copy(alpha = color.alpha * rect.alpha.coerceIn(0f, 1f)).toArgb()
    powerListTextPaint.textSize = fontSizeSp * density
    powerListTextPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    val availableWidth = rect.width.toFloat().coerceAtLeast(1f)
    val display = TextUtils.ellipsize(
        text,
        powerListTextPaint,
        availableWidth,
        TextUtils.TruncateAt.END
    )
    val fontMetrics = powerListTextPaint.fontMetrics
    val baseline = rect.top + (rect.height - fontMetrics.ascent - fontMetrics.descent) * 0.5f
    canvas.drawText(display.toString(), rect.left.toFloat(), baseline, powerListTextPaint)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerTransitionVisualFast(
    song: AudioFile,
    index: Int,
    source: ComposeTransitionRects,
    target: ComposeTransitionRects,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = ThemeManager.isDarkMode(context)
    val colors = powerListColors(dark, isPlaying, isSelected)
    var rootBounds by remember { mutableStateOf<RectF?>(null) }
    fun clickedCoverTarget(): CoverTransitionTarget? {
        val root = rootBounds ?: return null
        val rect = transitionLocalRect(source.cover, target.cover, progressProvider())
        val bounds = RectF(
            root.left + rect.left,
            root.top + rect.top,
            root.left + rect.left + rect.width,
            root.top + rect.top + rect.height
        )
        return CoverTransitionTarget(
            bounds = bounds,
            radiusDp = lerpFloatLocal(source.coverRadiusDp, target.coverRadiusDp, progressProvider()),
            source = CoverTransitionTarget.Source.ListCover,
            songId = song.id,
            coverKey = song.albumArtPath
        )
    }
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                rootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .combinedClickable(onClick = { onClick(clickedCoverTarget()) }, onLongClick = onLongClick)
            .background(colors.background)
    ) {
        TransitionCover(
            song = song,
            index = index,
            source = source.cover,
            target = target.cover,
            sourceRadiusDp = source.coverRadiusDp,
            targetRadiusDp = target.coverRadiusDp,
            progressProvider = progressProvider,
            isPlaying = isPlaying,
            onCoverBoundsChanged = onCoverBoundsChanged,
            onCoverTargetChanged = onCoverTargetChanged
        )
        TransitionText(
            text = song.displayName,
            color = colors.title,
            fontWeight = FontWeight.Bold,
            source = source.title,
            target = target.title,
            progressProvider = progressProvider
        )
        TransitionText(
            text = song.subtitle(),
            color = colors.secondary,
            fontWeight = null,
            source = source.subtitle,
            target = target.subtitle,
            progressProvider = progressProvider
        )
        TransitionText(
            text = song.metaText(),
            color = colors.meta,
            fontWeight = null,
            source = source.meta,
            target = target.meta,
            progressProvider = progressProvider
        )
    }
}

@Composable
private fun TransitionCover(
    song: AudioFile,
    index: Int,
    source: ComposeItemRect,
    target: ComposeItemRect,
    sourceRadiusDp: Float,
    targetRadiusDp: Float,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit
) {
    val density = LocalDensity.current
    val coverSize = maxOf(source.width, source.height, target.width, target.height).coerceAtLeast(1)
    val bitmap = rememberPowerListBitmap(
        key = song.albumArtPath,
        targetWidth = coverSize,
        targetHeight = coverSize,
        index = index,
        modeLabel = "TRANSITION"
    )
    Box(
        modifier = Modifier
            .offset {
                val p = progressProvider()
                val rect = transitionLocalRect(source, target, p)
                androidx.compose.ui.unit.IntOffset(
                    rect.left,
                    rect.top
                )
            }
            .then(if (isPlaying) Modifier.trackCoverBounds(onCoverBoundsChanged) else Modifier)
            .then(
                if (isPlaying) {
                    Modifier.trackCoverTarget(
                        radiusProvider = { lerpFloatLocal(sourceRadiusDp, targetRadiusDp, progressProvider()) },
                        songId = song.id,
                        coverKey = song.albumArtPath,
                        index = -1,
                        onTargetChanged = onCoverTargetChanged
                    )
                } else {
                    Modifier
                }
            )
            .requiredSize(
                width = with(density) {
                    val p = progressProvider()
                    transitionLocalRect(source, target, p).width.coerceAtLeast(1).toDp()
                },
                height = with(density) {
                    val p = progressProvider()
                    transitionLocalRect(source, target, p).height.coerceAtLeast(1).toDp()
                }
            )
            .graphicsLayer {
                shape = RoundedCornerShape(lerpFloatLocal(sourceRadiusDp, targetRadiusDp, progressProvider()).dp)
                clip = true
            }
    ) {
        PowerListBitmapCanvas(
            bitmap = bitmap,
            placeholderColor = Color.Transparent,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TransitionText(
    text: String,
    color: Color,
    fontWeight: FontWeight?,
    source: ComposeItemRect,
    target: ComposeItemRect,
    progressProvider: () -> Float
) {
    val density = LocalDensity.current
    val sourceFontSize = source.fontSizeSp.takeIf { it > 0f } ?: 14f
    val targetFontSize = target.fontSizeSp.takeIf { it > 0f } ?: 14f
    Text(
        text = text,
        color = color,
        fontSize = lerpFloatLocal(sourceFontSize, targetFontSize, progressProvider()).sp,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .offset {
                val p = progressProvider()
                val rect = transitionLocalRect(source, target, p)
                val measuredHeight = transitionTextMeasuredHeightPx(
                    density = density,
                    rect = rect,
                    source = source,
                    target = target,
                    sourceFontSizeSp = sourceFontSize,
                    targetFontSizeSp = targetFontSize,
                    progress = p
                )
                val verticalInset = ((measuredHeight - rect.height).coerceAtLeast(0) / 2f).roundToInt()
                androidx.compose.ui.unit.IntOffset(
                    rect.left,
                    rect.top - verticalInset
                )
            }
            .requiredSize(
                width = with(density) {
                    val p = progressProvider()
                    transitionLocalRect(source, target, p).width.coerceAtLeast(1).toDp()
                },
                height = with(density) {
                    val p = progressProvider()
                    val rect = transitionLocalRect(source, target, p)
                    transitionTextMeasuredHeightPx(
                        density = density,
                        rect = rect,
                        source = source,
                        target = target,
                        sourceFontSizeSp = sourceFontSize,
                        targetFontSizeSp = targetFontSize,
                        progress = p
                    ).coerceAtLeast(1).toDp()
                }
            )
            .graphicsLayer {
                val p = progressProvider()
                alpha = source.alpha + (target.alpha - source.alpha) * p
            }
    )
}

private fun Modifier.trackCoverBounds(onBoundsChanged: (RectF?) -> Unit): Modifier {
    return composed {
        val lastBounds = remember { arrayOfNulls<RectF>(1) }
        onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            val rect = RectF(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom
            )
            val previous = lastBounds[0]
            if (previous == null || !previous.nearlyEquals(rect, tolerance = 1f)) {
                lastBounds[0] = RectF(rect)
                onBoundsChanged(rect)
            }
        }
    }
}

private fun RectF.nearlyEquals(other: RectF, tolerance: Float): Boolean {
    return kotlin.math.abs(left - other.left) <= tolerance &&
        kotlin.math.abs(top - other.top) <= tolerance &&
        kotlin.math.abs(right - other.right) <= tolerance &&
        kotlin.math.abs(bottom - other.bottom) <= tolerance
}

private data class PowerListColors(
    val background: Color,
    val title: Color,
    val secondary: Color,
    val meta: Color
)

private fun powerListColors(dark: Boolean, playing: Boolean, selected: Boolean): PowerListColors {
    val baseTitle = if (dark) Color.White else Color(0xFF1D1B19)
    val highlight = if (dark) Color(0xFFD7B98D) else Color(0xFFC28E5E)
    val background = when {
        selected -> highlight.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    return PowerListColors(
        background = background,
        title = if (playing) highlight else baseTitle,
        secondary = baseTitle.copy(alpha = 0.72f),
        meta = baseTitle.copy(alpha = 0.52f)
    )
}

private fun AudioFile.subtitle(): String {
    return buildString {
        if (artist.isNotBlank()) append(artist)
        if (album.isNotBlank()) {
            if (isNotBlank()) append(" · ")
            append(album)
        }
    }.ifBlank { path.substringBeforeLast('/').substringAfterLast('/') }
}

private fun AudioFile.metaText(): String {
    return buildString {
        if (duration > 0) append(formatDuration(duration))
        if (sampleRate > 0) {
            if (isNotBlank()) append(" · ")
            append(sampleRate / 1000)
            append("kHz")
        }
        if (bitsPerSample > 0) {
            if (isNotBlank()) append(" · ")
            append(bitsPerSample)
            append("bit")
        }
        val displayBitrate = if (bitRate > 0) bitRate
            else if (fileSize > 0 && duration > 0) ((fileSize * 8.0) / (duration / 1000.0)).toInt()
            else 0
        if (displayBitrate > 0) {
            if (isNotBlank()) append(" · ")
            append("${(displayBitrate / 1000.0).roundToInt()}kbps")
        }
        if (format.isNotBlank()) {
            if (isNotBlank()) append(" · ")
            append(format.uppercase())
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun powerListKey(song: AudioFile, index: Int): Any {
    return song.id.takeIf { it != 0L } ?: song.path.ifBlank { index.toString() }
}

private fun sharedRenderMode(
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
    progress: Float
): ComposePowerListDisplayMode {
    return sourceMode
}

private fun sharedRenderParams(
    sourceParams: ListZoomParams,
    targetParams: ListZoomParams,
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
    progress: Float
): ListZoomParams {
    return sourceParams
}

private fun dualSlotRenderFrame(
    source: ComposeItemPosition,
    target: ComposeItemPosition,
    progress: Float
): ComposeItemPosition {
    val f = progress.coerceIn(0f, 1f)
    return ComposeItemPosition(
        bounds = IntRect(
            left = lerpIntLocal(source.bounds.left, target.bounds.left, f),
            top = lerpIntLocal(source.bounds.top, target.bounds.top, f),
            right = lerpIntLocal(source.bounds.right, target.bounds.right, f),
            bottom = lerpIntLocal(source.bounds.bottom, target.bounds.bottom, f)
        ),
        alpha = lerpFloatLocal(source.alpha, target.alpha, f),
        scaleX = lerpFloatLocal(source.scaleX, target.scaleX, f),
        scaleY = lerpFloatLocal(source.scaleY, target.scaleY, f),
        sceneId = if (f < 0.5f) source.sceneId else target.sceneId
    )
}

private fun oneSlotScale(base: Float, transitionFraction: Float): Float {
    val f = transitionFraction.coerceIn(0f, 1f)
    return ((base - 1f) * f) + 1f
}

private fun oneSlotRenderFrame(
    basePosition: ComposeItemPosition,
    scaleBase: Float,
    transitionFraction: Float,
    pivotX: Float,
    pivotY: Float
): ComposeItemPosition {
    val f = transitionFraction.coerceIn(0f, 1f)
    val scale = oneSlotScale(scaleBase, f)
    val delta = scale - 1f
    val centerX = (basePosition.bounds.left + basePosition.bounds.right) / 2f
    val centerY = (basePosition.bounds.top + basePosition.bounds.bottom) / 2f
    val left = (basePosition.bounds.left + (centerX - pivotX) * delta).toInt()
    val top = (basePosition.bounds.top + (centerY - pivotY) * delta).toInt()
    return basePosition.copy(
        bounds = IntRect(
            left = left,
            top = top,
            right = left + basePosition.width,
            bottom = top + basePosition.height
        ),
        alpha = 1f - f,
        scaleX = scale,
        scaleY = scale
    )
}

private fun transitionLocalRect(
    source: ComposeItemRect,
    target: ComposeItemRect,
    progress: Float
): ComposeItemRect {
    val f = progress.coerceIn(0f, 1f)
    return ComposeItemRect(
        left = lerpIntLocal(source.left, target.left, f),
        top = lerpIntLocal(source.top, target.top, f),
        width = lerpIntLocal(source.width, target.width, f).coerceAtLeast(1),
        height = lerpIntLocal(source.height, target.height, f).coerceAtLeast(1),
        alpha = source.alpha + (target.alpha - source.alpha) * f,
        fontSizeSp = source.fontSizeSp + (target.fontSizeSp - source.fontSizeSp) * f
    )
}

private fun transitionTextMeasuredHeightPx(
    density: androidx.compose.ui.unit.Density,
    rect: ComposeItemRect,
    source: ComposeItemRect,
    target: ComposeItemRect,
    sourceFontSizeSp: Float,
    targetFontSizeSp: Float,
    progress: Float
): Int {
    val fontSizeSp = lerpFloatLocal(sourceFontSizeSp, targetFontSizeSp, progress)
    val fontHeightPx = with(density) { fontSizeSp.sp.toPx() }
    return max(
        max(source.height, target.height),
        max(rect.height, (fontHeightPx * 1.28f).roundToInt())
    ).coerceAtLeast(1)
}

private fun powerListModeOrder(mode: ComposePowerListDisplayMode): Int = when (mode) {
    ComposePowerListDisplayMode.LIST_SMALL -> 0
    ComposePowerListDisplayMode.LIST_NORMAL -> 1
    ComposePowerListDisplayMode.LIST_ZOOMED -> 2
    ComposePowerListDisplayMode.GRID_4 -> 3
    ComposePowerListDisplayMode.GRID_3 -> 4
    ComposePowerListDisplayMode.GRID_2 -> 5
}

private enum class ComposeTransitionItemKind {
    SHARED,
    SOURCE_ONLY,
    TARGET_ONLY
}

private data class ComposeTransitionItem(
    val index: Int,
    val song: AudioFile,
    val compositionSlot: Any,
    val kind: ComposeTransitionItemKind,
    val sourcePosition: ComposeItemPosition,
    val targetPosition: ComposeItemPosition,
    val sourceRects: ComposeTransitionRects,
    val targetRects: ComposeTransitionRects
)

private fun buildTransitionItems(
    songs: List<AudioFile>,
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
    sourceParams: ListZoomParams,
    targetParams: ListZoomParams,
    sourceGeometry: ComposePowerListGeometry,
    targetGeometry: ComposePowerListGeometry,
    scrollModel: TransitionScrollModel,
    density: Float,
    slotPool: ComposeSlotPool
): List<ComposeTransitionItem> {
    val visibleRange = mergedLayoutRange(
        itemCount = songs.size,
        sourceRange = scrollModel.sourceLayoutRange,
        targetRange = scrollModel.targetLayoutRange
    )
    if (visibleRange.isEmpty()) return emptyList()

    val activeIndices = LinkedHashSet<Int>()
    val sourceRenderableIndices = HashSet<Int>()
    val targetRenderableIndices = HashSet<Int>()
    val sourceLayoutSet = HashSet<Int>()
    val targetLayoutSet = HashSet<Int>()
    val sourcePositions = HashMap<Int, ComposeItemPosition>()
    val targetPositions = HashMap<Int, ComposeItemPosition>()

    for (index in visibleRange.first..visibleRange.last) {
        if (index in scrollModel.sourceLayoutRange) {
            val position = positionFor(index, sourceGeometry, sourceMode, scrollModel.sourceScrollYPx)
            sourceLayoutSet += index
            sourcePositions[index] = position
        }
        if (index in scrollModel.targetLayoutRange) {
            val position = positionFor(index, targetGeometry, targetMode, scrollModel.targetScrollYPx)
            targetLayoutSet += index
            targetPositions[index] = position
        }
    }

    val sharedSlotSet = sourceLayoutSet.intersect(targetLayoutSet)
    activeIndices += sharedSlotSet
    sourceRenderableIndices += sharedSlotSet
    targetRenderableIndices += sharedSlotSet

    val sourceOnlyCandidates = sourceLayoutSet
        .filter { it !in sharedSlotSet }
        .sorted()
    val targetOnlyCandidates = targetLayoutSet
        .filter { it !in sharedSlotSet }
        .sorted()
    for (index in sourceOnlyCandidates) {
        activeIndices += index
        sourceRenderableIndices += index
    }

    for (index in targetOnlyCandidates) {
        activeIndices += index
        targetRenderableIndices += index
    }
    if (activeIndices.isEmpty()) return emptyList()
    slotPool.beginFrame(
        firstVisibleIndex = visibleRange.first,
        visibleCount = visibleRange.last - visibleRange.first + 1
    )

    for (index in visibleRange.first..visibleRange.last) {
        val song = songs.getOrNull(index) ?: continue
        val key = powerListKey(song, index)
        if (index !in activeIndices) continue
        if (index in scrollModel.sourceLayoutRange) {
            slotPool.setPosition(
                index = index,
                key = key,
                slot = COMPOSE_SLOT_SOURCE,
                position = sourcePositions[index]
                    ?: positionFor(index, sourceGeometry, sourceMode, scrollModel.sourceScrollYPx)
            )
        }
        if (index in scrollModel.targetLayoutRange) {
            slotPool.setPosition(
                index = index,
                key = key,
                slot = COMPOSE_SLOT_TARGET,
                position = targetPositions[index]
                    ?: positionFor(index, targetGeometry, targetMode, scrollModel.targetScrollYPx)
            )
        }
    }

    val result = ArrayList<ComposeTransitionItem>(activeIndices.size)
    for (index in visibleRange.first..visibleRange.last) {
        val song = songs.getOrNull(index) ?: continue
        val key = powerListKey(song, index)
        if (index !in activeIndices) continue

        val sourceRenderable = index in sourceRenderableIndices
        val targetRenderable = index in targetRenderableIndices
        val sourcePosition = slotPool.getPosition(index, COMPOSE_SLOT_SOURCE)
        val targetPosition = slotPool.getPosition(index, COMPOSE_SLOT_TARGET)
        val kind = when {
            sourceRenderable && targetRenderable -> ComposeTransitionItemKind.SHARED
            sourceRenderable -> ComposeTransitionItemKind.SOURCE_ONLY
            else -> ComposeTransitionItemKind.TARGET_ONLY
        }
        val baseSource = sourcePosition ?: targetPosition ?: continue
        val baseTarget = targetPosition ?: sourcePosition ?: continue
        val physicalSlot = slotPool.slotIdFor(index).takeIf { it >= 0 } ?: (index - visibleRange.first)
        val sourceRects = composeAAItemSceneRects(baseSource, sourceMode, sourceParams, density)
        val targetRects = composeAAItemSceneRects(baseTarget, targetMode, targetParams, density)
        result += ComposeTransitionItem(
            index = index,
            song = song,
            compositionSlot = "transition-slot-$physicalSlot",
            kind = kind,
            sourcePosition = baseSource,
            targetPosition = baseTarget,
            sourceRects = sourceRects,
            targetRects = targetRects
        )
    }
    return result
}

private fun prewarmTransitionBitmaps(items: List<ComposeTransitionItem>) {
    if (items.isEmpty()) return
    for (item in items.take(POWER_LIST_PREFETCH_MAX_ITEMS)) {
        val targetCover = item.targetRects.cover
        val sourceCover = item.sourceRects.cover
        val targetSize = max(targetCover.width, targetCover.height)
            .coerceAtLeast(1)
            .coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
        val sourceSize = max(sourceCover.width, sourceCover.height)
            .coerceAtLeast(1)
            .coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
        val targetKey = item.song.albumArtPath
        if (targetKey.isNotBlank()) {
            if (BitmapProvider.peekThumbnail(targetKey, targetSize, targetSize) == null) {
                BitmapProvider.loadThumbnail(targetKey, targetSize, targetSize, BitmapRequest.Priority.LOADING_PREFETCH)
            }
            if (sourceSize != targetSize && BitmapProvider.peekThumbnail(targetKey, sourceSize, sourceSize) == null) {
                BitmapProvider.loadThumbnail(targetKey, sourceSize, sourceSize, BitmapRequest.Priority.LOADING_PREFETCH)
            }
        }
    }
}

private fun Modifier.trackCoverTarget(
    radiusProvider: () -> Float,
    songId: Long,
    coverKey: String,
    index: Int,
    onTargetChanged: (CoverTransitionTarget?) -> Unit
): Modifier {
    return composed {
        val lastBounds = remember { arrayOfNulls<RectF>(1) }
        onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            val rect = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val previous = lastBounds[0]
            if (previous == null || !previous.nearlyEquals(rect, tolerance = 1f)) {
                lastBounds[0] = RectF(rect)
                onTargetChanged(
                    CoverTransitionTarget(
                        bounds = rect,
                        radiusDp = radiusProvider(),
                        source = CoverTransitionTarget.Source.ListCover,
                        songId = songId,
                        coverKey = coverKey,
                        index = index
                    )
                )
            }
        }
    }
}

private fun Modifier.trackDrawnCoverTarget(
    cover: ComposeItemRect,
    radiusDp: Float,
    songId: Long,
    coverKey: String,
    index: Int,
    onTargetChanged: (CoverTransitionTarget?) -> Unit
): Modifier {
    return composed {
        val lastBounds = remember { arrayOfNulls<RectF>(1) }
        onGloballyPositioned { coordinates ->
            val itemBounds = coordinates.boundsInRoot()
            val rect = RectF(
                itemBounds.left + cover.left,
                itemBounds.top + cover.top,
                itemBounds.left + cover.left + cover.width,
                itemBounds.top + cover.top + cover.height
            )
            val previous = lastBounds[0]
            if (previous == null || !previous.nearlyEquals(rect, tolerance = 1f)) {
                lastBounds[0] = RectF(rect)
                onTargetChanged(
                    CoverTransitionTarget(
                        bounds = rect,
                        radiusDp = radiusDp,
                        source = CoverTransitionTarget.Source.ListCover,
                        songId = songId,
                        coverKey = coverKey,
                        index = index
                    )
                )
            }
        }
    }
}

private fun prewarmSettledBitmaps(
    songs: List<AudioFile>,
    range: IntRange,
    visibleRange: IntRange,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    density: Float
) {
    if (range.isEmpty()) return
    var requested = 0
    for (index in range.first..range.last) {
        if (requested >= POWER_LIST_PREFETCH_MAX_ITEMS) break
        if (!visibleRange.isEmpty() && index in visibleRange) continue
        val song = songs.getOrNull(index) ?: continue
        val key = song.albumArtPath
        if (key.isBlank()) continue
        val position = positionFor(
            index = index,
            geometry = geometry,
            mode = mode,
            scrollYPx = scrollYPx
        )
        val rects = composeAAItemSceneRects(position, mode, params, density)
        val size = max(rects.cover.width, rects.cover.height)
            .coerceAtLeast(1)
            .coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
        if (BitmapProvider.peekThumbnail(key, size, size) == null) {
            BitmapProvider.loadThumbnail(key, size, size, BitmapRequest.Priority.LOADING_PREFETCH)
            requested++
        }
    }
}

private data class ComposePowerListGeometry(
    val columns: Int,
    val availableWidthPx: Int,
    val cellWidthPx: Int,
    val itemHeightPx: Int,
    val rowStridePx: Int,
    val rowSpacingPx: Int,
    val bottomPaddingPx: Int,
    val paddingLeftPx: Int = 0,
    val paddingTopPx: Int = 0
)

private fun listGeometryFor(
    metrics: ComposePowerListMetrics,
    bottomPaddingPx: Int,
    topPaddingPx: Int = 0
): ComposePowerListGeometry {
    val columns = metrics.columns.coerceAtLeast(1)
    val availableWidth = metrics.cellWidthPx.coerceAtLeast(1) * columns
    val cellWidth = if (columns <= 1) availableWidth else availableWidth / columns
    val itemHeight = metrics.cellHeightPx.coerceAtLeast(1)
    return ComposePowerListGeometry(
        columns = columns,
        availableWidthPx = availableWidth,
        cellWidthPx = cellWidth,
        itemHeightPx = itemHeight,
        rowStridePx = itemHeight,
        rowSpacingPx = 0,
        bottomPaddingPx = bottomPaddingPx.coerceAtLeast(0),
        paddingTopPx = topPaddingPx.coerceAtLeast(0)
    )
}

private fun positionFor(
    index: Int,
    geometry: ComposePowerListGeometry,
    mode: ComposePowerListDisplayMode,
    scrollYPx: Int
): ComposeItemPosition {
    val columns = geometry.columns.coerceAtLeast(1)
    val row = index / columns
    val col = index % columns
    val left = geometry.paddingLeftPx + col * geometry.cellWidthPx
    val top = geometry.paddingTopPx + row * geometry.rowStridePx - scrollYPx
    val right = left + geometry.cellWidthPx
    val bottom = top + geometry.itemHeightPx
    return ComposeItemPosition(
        bounds = androidx.compose.ui.unit.IntRect(left, top, right, bottom),
        alpha = 1f,
        scaleX = 1f,
        scaleY = 1f,
        sceneId = mode.listLevel?.let { sceneIdForZoomIndex(it) } ?: PowerListSceneItem.SCENE_GRID
    )
}

private fun ComposeItemPosition.intersectsViewport(
    geometry: ComposePowerListGeometry,
    viewportHeightPx: Int
): Boolean {
    return bounds.right > 0 &&
        bounds.left < geometry.availableWidthPx &&
        bounds.bottom > 0 &&
        bounds.top < viewportHeightPx.coerceAtLeast(0)
}

private fun interpolatedTransitionRects(
    sourcePosition: ComposeItemPosition,
    targetPosition: ComposeItemPosition,
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
    sourceParams: ListZoomParams,
    targetParams: ListZoomParams,
    density: Float,
    progress: Float
): ComposeTransitionRects {
    val source = composeAAItemSceneRects(sourcePosition, sourceMode, sourceParams, density)
    val target = composeAAItemSceneRects(targetPosition, targetMode, targetParams, density)
    val f = progress.coerceIn(0f, 1f)
    val itemLeft = lerpIntLocal(sourcePosition.bounds.left, targetPosition.bounds.left, f)
    val itemTop = lerpIntLocal(sourcePosition.bounds.top, targetPosition.bounds.top, f)
    return ComposeTransitionRects(
        cover = lerpRectLocal(source.cover, target.cover, f, itemLeft, itemTop),
        title = lerpRectLocal(source.title, target.title, f, itemLeft, itemTop),
        subtitle = lerpRectLocal(source.subtitle, target.subtitle, f, itemLeft, itemTop),
        meta = lerpRectLocal(source.meta, target.meta, f, itemLeft, itemTop),
        coverRadiusDp = lerpFloatLocal(source.coverRadiusDp, target.coverRadiusDp, f)
    )
}

private fun lerpRectLocal(
    source: ComposeItemRect,
    target: ComposeItemRect,
    fraction: Float,
    itemLeft: Int,
    itemTop: Int
): ComposeItemRect {
    return ComposeItemRect(
        left = lerpIntLocal(source.left, target.left, fraction) - itemLeft,
        top = lerpIntLocal(source.top, target.top, fraction) - itemTop,
        width = lerpIntLocal(source.width, target.width, fraction).coerceAtLeast(1),
        height = lerpIntLocal(source.height, target.height, fraction).coerceAtLeast(1),
        alpha = source.alpha + (target.alpha - source.alpha) * fraction.coerceIn(0f, 1f),
        fontSizeSp = source.fontSizeSp + (target.fontSizeSp - source.fontSizeSp) * fraction.coerceIn(0f, 1f)
    )
}

private fun lerpIntLocal(from: Int, to: Int, fraction: Float): Int {
    return (from + (to - from) * fraction).roundToInt()
}

private fun lerpFloatLocal(from: Float, to: Float, fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return from + (to - from) * f
}

private fun maxScrollForContent(
    itemCount: Int,
    metrics: ComposePowerListMetrics,
    viewportHeightPx: Int,
    bottomPaddingPx: Int,
    topPaddingPx: Int = 0
): Int {
    if (itemCount <= 0) return 0
    val geometry = listGeometryFor(metrics, bottomPaddingPx, topPaddingPx)
    return maxScrollForGeometry(itemCount, geometry, viewportHeightPx)
}

private fun maxScrollForGeometry(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    viewportHeightPx: Int
): Int {
    if (itemCount <= 0) return 0
    val rowCount = (itemCount + geometry.columns - 1) / geometry.columns
    val contentHeight = geometry.paddingTopPx + rowCount * geometry.rowStridePx - geometry.rowSpacingPx + geometry.bottomPaddingPx
    return (contentHeight - viewportHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
}

private fun scrollYForIndex(
    index: Int,
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    viewportHeightPx: Int
): Int {
    if (itemCount <= 0) return 0
    val columns = geometry.columns.coerceAtLeast(1)
    val row = index.coerceIn(0, itemCount - 1) / columns
    val desiredTop = (viewportHeightPx * 0.30f).roundToInt()
    val raw = geometry.paddingTopPx + row * geometry.rowStridePx - desiredTop
    return raw.coerceIn(0, maxScrollForGeometry(itemCount, geometry, viewportHeightPx))
}

private fun exactCoverTargetForIndex(
    index: Int,
    geometry: ComposePowerListGeometry,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    scrollYPx: Int,
    density: Float,
    rootBounds: RectF?,
    songId: Long = -1L,
    coverKey: String = ""
): CoverTransitionTarget? {
    val root = rootBounds ?: return null
    val itemPosition = positionFor(
        index = index,
        geometry = geometry,
        mode = mode,
        scrollYPx = scrollYPx.coerceAtLeast(0)
    )
    if (itemPosition.isEmpty()) return null
    val rects = composeAAItemSceneRects(itemPosition, mode, params, density)
    val cover = rects.cover
    val bounds = RectF(
        root.left + itemPosition.bounds.left + cover.left,
        root.top + itemPosition.bounds.top + cover.top,
        root.left + itemPosition.bounds.left + cover.left + cover.width,
        root.top + itemPosition.bounds.top + cover.top + cover.height
    )
    if (bounds.width() < 8f || bounds.height() < 8f) return null
    return CoverTransitionTarget(
        bounds = bounds,
        radiusDp = rects.coverRadiusDp,
        source = CoverTransitionTarget.Source.ListCover,
        songId = songId,
        coverKey = coverKey,
        index = index
    )
}

private fun ComposeItemPosition.withViewportScale(
    scale: Float,
    centerX: Float,
    centerY: Float
): ComposeItemPosition {
    val s = scale.coerceIn(0.85f, 1.15f)
    if (s == 1f) return this
    val left = (centerX + (bounds.left - centerX) * s).roundToInt()
    val top = (centerY + (bounds.top - centerY) * s).roundToInt()
    val width = (width * s).roundToInt().coerceAtLeast(1)
    val height = (height * s).roundToInt().coerceAtLeast(1)
    return copy(
        bounds = androidx.compose.ui.unit.IntRect(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height
        )
    )
}

private data class TransitionScrollModel(
    val sourceScrollYPx: Int,
    val targetScrollYPx: Int,
    val sourceLayoutRange: IntRange,
    val targetLayoutRange: IntRange,
    val viewportHeightPx: Int
)

private data class ComposeTransitionAnchor(
    val sourceMode: ComposePowerListDisplayMode,
    val targetMode: ComposePowerListDisplayMode,
    val sourceScrollYPx: Int
) {
    fun matches(source: ComposePowerListDisplayMode, target: ComposePowerListDisplayMode): Boolean {
        return sourceMode == source && targetMode == target
    }
}

private data class PendingTransitionScrollPx(
    val sourceMode: ComposePowerListDisplayMode,
    val targetMode: ComposePowerListDisplayMode,
    val source: Int,
    val target: Int
)

private fun TransitionScrollModel.toPendingScroll(
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode
): PendingTransitionScrollPx {
    return PendingTransitionScrollPx(
        sourceMode = sourceMode,
        targetMode = targetMode,
        source = sourceScrollYPx,
        target = targetScrollYPx
    )
}

private fun transitionScrollModel(
    itemCount: Int,
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
    sourceGeometry: ComposePowerListGeometry,
    targetGeometry: ComposePowerListGeometry,
    viewportHeightPx: Int,
    sourceScrollYPx: Int
): TransitionScrollModel {
    if (itemCount <= 0) {
        return TransitionScrollModel(0, 0, IntRange.EMPTY, IntRange.EMPTY, viewportHeightPx)
    }
    val sourceScrollY = sourceScrollYPx.coerceIn(
        0,
        maxScrollForGeometry(itemCount, sourceGeometry, viewportHeightPx)
    )
    val sourceStride = sourceGeometry.rowStridePx.coerceAtLeast(1)
    val targetStride = targetGeometry.rowStridePx.coerceAtLeast(1)
    val sourceCenterY = sourceScrollY + viewportHeightPx.coerceAtLeast(0) / 2
    val sourceCenterRow = sourceCenterY / sourceStride
    val sourceCenterOffset = sourceCenterY % sourceStride
    val anchorPosition = (sourceCenterRow * sourceMode.columns.coerceAtLeast(1)).coerceIn(0, itemCount - 1)
    val targetRow = anchorPosition / targetMode.columns.coerceAtLeast(1)
    val targetCenterY = targetRow * targetStride + if (sourceStride > 0) {
        (sourceCenterOffset.toLong() * targetStride / sourceStride).toInt()
    } else {
        sourceCenterOffset
    }
    val targetScrollY = (targetCenterY - viewportHeightPx.coerceAtLeast(0) / 2).coerceIn(
        0,
        maxScrollForGeometry(itemCount, targetGeometry, viewportHeightPx)
    )
    val sourceLayoutRange = visibleRangeForScroll(
        itemCount = itemCount,
        mode = sourceMode,
        geometry = sourceGeometry,
        scrollYPx = sourceScrollY,
        viewportHeightPx = viewportHeightPx
    )
    val targetLayoutRange = visibleRangeForScroll(
        itemCount = itemCount,
        mode = targetMode,
        geometry = targetGeometry,
        scrollYPx = targetScrollY,
        viewportHeightPx = viewportHeightPx
    )
    return TransitionScrollModel(
        sourceScrollYPx = sourceScrollY,
        targetScrollYPx = targetScrollY,
        sourceLayoutRange = sourceLayoutRange,
        targetLayoutRange = targetLayoutRange,
        viewportHeightPx = viewportHeightPx
    )
}

private fun visibleRangeForScroll(
    itemCount: Int,
    mode: ComposePowerListDisplayMode,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int
): IntRange {
    val range = boundsIntersectingRangeForScroll(
        itemCount = itemCount,
        geometry = geometry,
        scrollYPx = scrollYPx,
        viewportHeightPx = viewportHeightPx
    )
    if (range.isEmpty()) return range
    val extraRows = if (mode == ComposePowerListDisplayMode.GRID_3 || mode == ComposePowerListDisplayMode.GRID_4) 1 else 0
    if (extraRows <= 0) return range
    return expandRangeByRows(range, itemCount, geometry.columns, extraRows, extraRows)
}

private fun boundsIntersectingRangeForScroll(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int
): IntRange {
    if (itemCount <= 0) return IntRange.EMPTY
    val columns = geometry.columns.coerceAtLeast(1)
    val rowStride = geometry.rowStridePx.coerceAtLeast(1)
    val viewportTop = 0
    val viewportBottom = viewportHeightPx.coerceAtLeast(0)
    if (viewportBottom <= viewportTop) return IntRange.EMPTY

    val rowCount = (itemCount + columns - 1) / columns
    val startRow = (scrollYPx.coerceAtLeast(0) / rowStride).coerceIn(0, (rowCount - 1).coerceAtLeast(0))
    var first = Int.MAX_VALUE
    var last = Int.MIN_VALUE

    var row = startRow
    while (row < rowCount) {
        val top = geometry.paddingTopPx + row * rowStride - scrollYPx
        val bottom = top + geometry.itemHeightPx
        if (bottom > viewportTop && top < viewportBottom) {
            val rowFirst = row * columns
            val rowLast = (rowFirst + columns - 1).coerceAtMost(itemCount - 1)
            first = minOf(first, rowFirst)
            last = maxOf(last, rowLast)
        } else if (top >= viewportBottom && first != Int.MAX_VALUE) {
            break
        }
        row++
    }

    row = startRow - 1
    while (row >= 0) {
        val top = geometry.paddingTopPx + row * rowStride - scrollYPx
        val bottom = top + geometry.itemHeightPx
        if (bottom > viewportTop && top < viewportBottom) {
            val rowFirst = row * columns
            val rowLast = (rowFirst + columns - 1).coerceAtMost(itemCount - 1)
            first = minOf(first, rowFirst)
            last = maxOf(last, rowLast)
        } else if (bottom <= viewportTop && first != Int.MAX_VALUE) {
            break
        }
        row--
    }

    return if (first == Int.MAX_VALUE) IntRange.EMPTY else first..last
}

private fun expandRangeByRows(
    range: IntRange,
    itemCount: Int,
    columns: Int,
    rowsBefore: Int,
    rowsAfter: Int
): IntRange {
    if (itemCount <= 0 || range.isEmpty()) return IntRange.EMPTY
    val colCount = columns.coerceAtLeast(1)
    val firstRow = (range.first / colCount - rowsBefore).coerceAtLeast(0)
    val lastRow = (range.last / colCount + rowsAfter).coerceAtMost((itemCount - 1) / colCount)
    val first = (firstRow * colCount).coerceIn(0, itemCount - 1)
    val last = (lastRow * colCount + colCount - 1).coerceIn(first, itemCount - 1)
    return first..last
}

private fun layoutRangeForScroll(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int,
    extraRowsBefore: Int = 0,
    extraRowsAfter: Int = 0
): IntRange {
    if (itemCount <= 0) return IntRange.EMPTY
    val columns = geometry.columns.coerceAtLeast(1)
    val rowStride = geometry.rowStridePx.coerceAtLeast(1)
    val firstRow = ((scrollYPx / rowStride) - extraRowsBefore).coerceAtLeast(0)
    val lastViewportRow = ((scrollYPx + viewportHeightPx.coerceAtLeast(0) - 1).coerceAtLeast(scrollYPx) / rowStride)
    val lastRow = (lastViewportRow + extraRowsAfter).coerceAtLeast(firstRow)
    val first = (firstRow * columns).coerceIn(0, itemCount - 1)
    val last = (lastRow * columns + columns - 1).coerceIn(first, itemCount - 1)
    return first..last
}

private fun mergedLayoutRange(
    itemCount: Int,
    sourceRange: IntRange,
    targetRange: IntRange
): IntRange {
    if (itemCount <= 0 || (sourceRange.isEmpty() && targetRange.isEmpty())) return IntRange.EMPTY
    val first = minOf(
        if (sourceRange.isEmpty()) itemCount - 1 else sourceRange.first,
        if (targetRange.isEmpty()) itemCount - 1 else targetRange.first
    ).coerceIn(0, itemCount - 1)
    val last = maxOf(
        if (sourceRange.isEmpty()) 0 else sourceRange.last,
        if (targetRange.isEmpty()) 0 else targetRange.last
    ).coerceIn(first, itemCount - 1)
    return first..last
}
