package com.rawsmusic.core.ui.widget.powerlist

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalBottomChromeScrollState
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.scene.powerListSceneTransitionItem
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.shouldShowDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.FileArtworkId
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtwork
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtworkModel
import com.rawsmusic.core.ui.widget.bitmaps.SizeSlotCache
import com.rawsmusic.core.ui.widget.player.copySongInfoToClipboard
import com.rawsmusic.module.data.prefs.FontManager
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import java.util.concurrent.atomic.AtomicLong
import coil.request.ImageRequest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
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
    persistentHeaderHeight: Dp = 0.dp,
    persistentHeaderVisibilityHeight: Dp = persistentHeaderHeight,
    persistentHeaderSceneItemId: String = "",
    persistentHeaderContent: @Composable (visible: Boolean) -> Unit = {},
    contentBottomPadding: Dp = 200.dp,
    sectionHeaders: List<PowerListSectionHeader> = emptyList(),
    sectionHeaderHeight: Dp = 54.dp,
    sectionHeaderContent: @Composable (PowerListSectionHeader) -> Unit = {},
    pinchEnabled: Boolean = true,
    sharedCoverSceneId: String = "",
    sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current
    val bottomChromeScrollState = LocalBottomChromeScrollState.current
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
            .then(
                if (pinchEnabled) {
                    Modifier.powerListPointerInput(state, density.density)
                } else {
                    Modifier
                }
            )
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
        val bottomPaddingPx = with(density) { contentBottomPadding.roundToPx() }
        val staticTopPaddingPx = with(density) { contentTopPadding.roundToPx() }
        val persistentHeaderHeightPx = with(density) { persistentHeaderHeight.roundToPx() }
        val persistentHeaderVisibilityHeightPx = with(density) {
            persistentHeaderVisibilityHeight.roundToPx()
        }
        val contentTopPaddingPx = staticTopPaddingPx + persistentHeaderHeightPx
        val sectionHeaderHeightPx = with(density) { sectionHeaderHeight.roundToPx() }
        val maxScrollY = maxScrollForContent(
            itemCount = songs.size,
            metrics = metrics,
            viewportHeightPx = heightPx,
            bottomPaddingPx = bottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx
        ).toFloat()
        val geometry = remember(metrics, bottomPaddingPx, contentTopPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx) {
            listGeometryFor(
                metrics = metrics,
                bottomPaddingPx = bottomPaddingPx,
                topPaddingPx = contentTopPaddingPx,
                itemCount = songs.size,
                sectionHeaders = sectionHeaders,
                sectionHeaderHeightPx = sectionHeaderHeightPx
            )
        }
        val scrollBucketHeight = geometry.rowStridePx.coerceAtLeast(1)
        val settledBaseScrollYState = remember(scrollBucketHeight) { mutableStateOf(0) }
        val settledScrollOffsetPx = remember(scrollBucketHeight) { mutableIntStateOf(0) }
        fun updateSettledBaseScroll(rawScrollY: Int) {
            val clampedScrollY = rawScrollY.coerceIn(0, maxScrollY.roundToInt())
            val nextBase = (clampedScrollY / scrollBucketHeight) * scrollBucketHeight
            if (settledBaseScrollYState.value != nextBase) {
                settledBaseScrollYState.value = nextBase
            }
            settledScrollOffsetPx.intValue = clampedScrollY - nextBase
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
                val currentScrollY = state.viewportScrollY
                    .roundToInt()
                    .coerceIn(0, maxScrollY.roundToInt())
                val preserveVisiblePosition = state.isIndexVisible(revealIndexRequest)
                val targetScrollY = if (preserveVisiblePosition) {
                    currentScrollY
                } else {
                    scrollYForIndex(
                        index = revealIndexRequest,
                        itemCount = songs.size,
                        geometry = geometry,
                        viewportHeightPx = heightPx
                    ).coerceIn(0, maxScrollY.roundToInt())
                }
                if (!preserveVisiblePosition) {
                    state.viewportScrollY = targetScrollY.toFloat()
                }
                // Keep the settled renderer and the logical viewport on the same frame. When the
                // playing row is already visible, retain the exact viewport instead of centering it
                // again during the player return transition.
                updateSettledBaseScroll(targetScrollY)
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
                    coverKey = revealSong?.coverKey.orEmpty()
                )
                onRevealCoverTargetResolved(target)
            } else if (revealIndexRequest >= 0) {
                onRevealCoverTargetResolved(null)
            }
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
                if (newValue != old) {
                    bottomChromeScrollState?.onContentScroll(newValue - old)
                }
                state.viewportScrollY = newValue
                updateSettledBaseScroll(newValue.toInt())
                old - newValue
            }
        }
        // Temporary diagnostics only. This observes frame gaps while scrolling/transitioning;
        // it does not drive layout, animation, or artwork state.
        if (POWER_LIST_TRACE_FRAMES) {
            LaunchedEffect(scrollableState.isScrollInProgress, state.isTransitioning) {
                if (!scrollableState.isScrollInProgress && !state.isTransitioning) {
                    return@LaunchedEffect
                }
                var previousFrameNs = withFrameNanos { it }
                while (true) {
                    val frameNs = withFrameNanos { it }
                    val gapMs = (frameNs - previousFrameNs) / 1_000_000L
                    if (gapMs >= POWER_LIST_TRACE_FRAME_GAP_MS) {
                        Log.w(
                            POWER_LIST_TRACE_TAG,
                            "POWER_LIST_TRACE frame_gap_ms=$gapMs " +
                                "scrolling=${scrollableState.isScrollInProgress} " +
                                "transitioning=${state.isTransitioning} " +
                                "scrollY=${state.viewportScrollY.roundToInt()}"
                        )
                    }
                    previousFrameNs = frameNs
                    if (!scrollableState.isScrollInProgress && !state.isTransitioning) {
                        break
                    }
                }
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
                    contentBottomPaddingPx = bottomPaddingPx,
                    sectionHeaders = sectionHeaders,
                    sectionHeaderHeightPx = sectionHeaderHeightPx,
                    sectionHeaderContent = sectionHeaderContent,
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
                ComposePowerListSettledContent(
                    songs = songs,
                    state = state,
                    metrics = metrics,
                    scrollYPx = settledBaseScrollY,
                    viewportHeightPx = heightPx,
                    playingSongId = playingSongId,
                    currentPlayingIndex = currentPlayingIndex,
                    selectedPositions = selectedPositions,
                    hidePlayingCover = hidePlayingCover,
                    boundaryScale = state.boundaryElasticScale,
                    interactionActive = scrollableState.isScrollInProgress || state.isPinching || state.isBoundaryElasticActive,
                    topPaddingPx = contentTopPaddingPx,
                    sectionHeaders = sectionHeaders,
                    sectionHeaderHeightPx = sectionHeaderHeightPx,
                    sectionHeaderContent = sectionHeaderContent,
                    sharedCoverSceneId = sharedCoverSceneId,
                    sharedCoverElementIdProvider = sharedCoverElementIdProvider,
                    onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                    onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                    onSongClick = onSongClick,
                    onSongLongClick = onSongLongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            // Keep the child tree in stable content coordinates. A row-boundary
                            // update must not reset the inner layer and then apply the remainder
                            // in a second layout pass, otherwise visible covers flash for one frame.
                            val offset = if (pendingSettledScroll != null) {
                                pendingSettledScroll.coerceIn(0, maxScrollY.roundToInt())
                            } else {
                                settledBaseScrollY + settledScrollOffsetPx.intValue
                            }
                            androidx.compose.ui.unit.IntOffset(0, -offset)
                        }
                )
            }

            if (persistentHeaderHeightPx > 0) {
                // Keep the exact scroll read inside the header subtree. Reading viewportScrollY
                // here would invalidate this whole PowerList on every pixel; Poweramp scrolls its
                // existing child views without rebuilding the rows during a fling.
                ComposePowerListPersistentHeader(
                    state = state,
                    pendingTransitionScroll = pendingTransitionScroll,
                    staticTopPaddingPx = staticTopPaddingPx,
                    persistentHeaderHeight = persistentHeaderHeight,
                    persistentHeaderVisibilityHeightPx = persistentHeaderVisibilityHeightPx,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    sharedCoverSceneId = sharedCoverSceneId,
                    persistentHeaderSceneItemId = persistentHeaderSceneItemId,
                    persistentHeaderContent = persistentHeaderContent
                )
            }
        }
    }
}

/**
 * Scroll-sensitive chrome is isolated from the row renderer. The child still follows exact pixel
 * movement, but changing its snapshot state no longer recomposes the visible grid/list cells.
 */
@Composable
private fun ComposePowerListPersistentHeader(
    state: ComposePowerListState,
    pendingTransitionScroll: MutableState<PendingTransitionScrollPx?>,
    staticTopPaddingPx: Int,
    persistentHeaderHeight: Dp,
    persistentHeaderVisibilityHeightPx: Int,
    widthPx: Int,
    heightPx: Int,
    sharedCoverSceneId: String,
    persistentHeaderSceneItemId: String,
    persistentHeaderContent: @Composable (visible: Boolean) -> Unit
) {
    val density = LocalDensity.current
    val pending = pendingTransitionScroll.value
    val headerScrollYPx = when {
        state.isTransitioning && pending != null -> lerpIntLocal(
            pending.source,
            pending.target,
            state.transitionProgress.coerceIn(0f, 1f)
        )
        pending != null -> if (state.currentMode == pending.targetMode) {
            pending.target
        } else {
            pending.source
        }
        else -> state.viewportScrollY.roundToInt()
    }
    val headerTopPx = staticTopPaddingPx - headerScrollYPx
    val headerVisible = headerTopPx < heightPx &&
        headerTopPx + persistentHeaderVisibilityHeightPx > 0

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, headerTopPx) }
            .requiredSize(
                width = with(density) { widthPx.toDp() },
                height = persistentHeaderHeight
            )
            .graphicsLayer {
                scaleX = state.boundaryElasticScale
                scaleY = state.boundaryElasticScale
                transformOrigin = TransformOrigin.Center
            }
            .powerListSceneTransitionItem(
                sceneId = sharedCoverSceneId,
                itemId = persistentHeaderSceneItemId.takeIf { headerVisible }.orEmpty()
            )
    ) {
        persistentHeaderContent(headerVisible)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerListSettledContent(
    songs: List<AudioFile>,
    state: ComposePowerListState,
    metrics: ComposePowerListMetrics,
    scrollYPx: Int,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScale: Float,
    interactionActive: Boolean,
    topPaddingPx: Int,
    sectionHeaders: List<PowerListSectionHeader>,
    sectionHeaderHeightPx: Int,
    sectionHeaderContent: @Composable (PowerListSectionHeader) -> Unit,
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
        state = state,
        mode = state.currentMode,
        params = state.currentParams,
        metrics = metrics,
        scrollYPx = scrollYPx,
        viewportHeightPx = viewportHeightPx,
        playingSongId = playingSongId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        hidePlayingCover = hidePlayingCover,
        boundaryScale = boundaryScale,
        interactionActive = interactionActive,
        topPaddingPx = topPaddingPx,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeightPx = sectionHeaderHeightPx,
        sectionHeaderContent = sectionHeaderContent,
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
    state: ComposePowerListState,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    metrics: ComposePowerListMetrics,
    scrollYPx: Int,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScale: Float,
    interactionActive: Boolean,
    topPaddingPx: Int,
    sectionHeaders: List<PowerListSectionHeader>,
    sectionHeaderHeightPx: Int,
    sectionHeaderContent: @Composable (PowerListSectionHeader) -> Unit,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    modifier: Modifier = Modifier
    ) {
    val geometry = remember(metrics, topPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx) {
        listGeometryFor(
            metrics = metrics,
            bottomPaddingPx = 0,
            topPaddingPx = topPaddingPx,
            itemCount = songs.size,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx
        )
    }
    // Match Poweramp's FastLayout contract: children are laid out only when a row enters or
    // leaves the viewport; the remaining pixel motion belongs to the parent container. Passing
    // exact scrollY into every item makes every Canvas/text/click target recompute on every touch
    // frame, which is the main source of the dense-grid hitch.
    val rowStridePx = geometry.rowStridePx.coerceAtLeast(1)
    val layoutScrollRow = scrollYPx.coerceAtLeast(0) / rowStridePx
    val layoutScrollYPx = layoutScrollRow * rowStridePx
    // Project-style album-art binding is split in two:
    // 1) renderRange keeps every actually visible item composed so covers never blink out at
    //    the bottom edge during a drag;
    // 2) the provider-visible art window now follows renderRange.  The previous bottom-obscured
    //    window deferred lower-edge cells, causing DISPLAY_EMPTY_NEW_ID until another scroll pass.
    val rawRenderRange = remember(
        songs.size,
        mode,
        geometry,
        layoutScrollRow,
        viewportHeightPx
    ) {
        visibleRangeForScroll(
            itemCount = songs.size,
            mode = mode,
            geometry = geometry,
            scrollYPx = layoutScrollYPx,
            viewportHeightPx = viewportHeightPx
        )
    }
    // Keep the physical holder window row-banded. The outer layer still applies the exact
    // pixel offset, so this preserves smooth motion without rebuilding the visible grid when a
    // partial row crosses the viewport edge during a fling. Retain one trailing row so the next
    // row is already attached before it becomes visible.
    val maxRenderItems = remember(geometry.columns, geometry.rowStridePx, viewportHeightPx) {
        powerListMaxRenderItems(
            columns = geometry.columns,
            rowStridePx = geometry.rowStridePx,
            viewportHeightPx = viewportHeightPx
        )
    }
    val renderRange = remember(rawRenderRange, songs.size, geometry.columns, maxRenderItems) {
        capPowerListRange(
            expandRangeByRows(
                range = rawRenderRange,
                itemCount = songs.size,
                columns = geometry.columns,
                rowsBefore = 0,
                rowsAfter = 1
            ),
            maxItems = maxRenderItems,
            columns = geometry.columns
        )
    }
    val range = remember(renderRange, songs.size, geometry.columns, boundaryScale) {
        if (renderRange.isEmpty() || abs(boundaryScale - 1f) < 0.001f) {
            renderRange
        } else {
            expandRangeByRows(
                range = renderRange,
                itemCount = songs.size,
                columns = geometry.columns,
                rowsBefore = 1,
                rowsAfter = 1
            )
        }
    }
    // Project-style settled holder pool. The previous step26 range-relative slot
    // (`index - range.first`) recreates every visible cell whenever the first visible row changes.
    // The new trace confirms that this turns dense-grid flings into callback-lost request waves:
    // many requests finish with waiters=0 and then immediately re-request the same 384px cache.
    // ComposeSlotPool is the public RawS-Music smooth baseline: the physical slot survives row
    // shifts, while the artwork cell state below atomically rebinds FileArtworkId/current wrapper.
    val settledSlotPool = remember(mode) { ComposeSlotPool() }
    // FastLayout does not clear/rebuild its child slots for fractional pixel motion.  Keep this
    // operation row-windowed as well; calling beginFrame on every Compose frame clears all ring
    // entries and performs O(visibleItems) work without changing any holder identity.
    remember(range, mode) {
        settledSlotPool.beginFrame(
            firstVisibleIndex = range.first.coerceAtLeast(0),
            visibleCount = if (range.isEmpty()) 0 else range.last - range.first + 1
        )
    }
    if (state.currentVisibleRange != renderRange) {
        SideEffect {
            state.updateVisibleRangeForNavigation(renderRange)
        }
    }
    Box(
        // The parent modifier owns the exact scroll offset. Keeping this subtree in world
        // coordinates means a row-window change cannot race a fractional translation update.
        modifier = modifier
    ) {
        ComposePowerListSectionHeaders(
            headers = sectionHeaders,
            geometry = geometry,
            scrollYPx = 0,
            viewportHeightPx = viewportHeightPx,
            boundaryScale = boundaryScale,
            content = sectionHeaderContent
        )
        if (range.isEmpty()) return@Box
        val positionByIndex = remember(range, geometry, mode) {
            HashMap<Int, ComposeItemPosition>(range.last - range.first + 1).apply {
                for (itemIndex in range.first..range.last) {
                    val itemPosition = positionFor(
                        index = itemIndex,
                        geometry = geometry,
                        mode = mode,
                        scrollYPx = 0
                    )
                    if (!itemPosition.isEmpty()) put(itemIndex, itemPosition)
                }
            }
        }
        // Compose preserves a keyed child most reliably when both its key and its sibling order
        // stay stable. Iterating by song index rotates the physical slot order at every row
        // boundary (for example 4..27,0..3), which disposed the entire visible window even
        // though each overlapping song still owned the same slot. Render in physical slot order
        // and only update the slot's bound index, matching a retained grid holder pool.
        val settledSlotBindings = remember(range, mode) {
            ArrayList<Pair<Int, Int>>(range.last - range.first + 1).apply {
                for (itemIndex in range.first..range.last) {
                    val slotId = settledSlotPool.slotIdFor(itemIndex)
                    if (slotId >= 0) add(slotId to itemIndex)
                }
                sortBy { it.first }
            }
        }
        for ((physicalSlotId, index) in settledSlotBindings) {
            val song = songs.getOrNull(index) ?: continue
            val position = positionByIndex[index] ?: continue
            val isPlaying = if (playingSongId > 0L) {
                song.id == playingSongId
            } else {
                index == currentPlayingIndex
            }
            val hideCover = hidePlayingCover && isPlaying
            val compositionSlot = "settled-slot-${mode.name}-$physicalSlotId"
            val sharedCoverElementId = sharedCoverElementIdProvider(song, index)
            val artworkPriority = if (index !in rawRenderRange) {
                BitmapRequest.Priority.LOADING_PREFETCH
            } else {
                BitmapRequest.Priority.LOADING_LIST
            }
            key(compositionSlot) {
                val noopCoverBoundsChanged = remember { { _: RectF? -> } }
                val noopCoverTargetChanged = remember { { _: CoverTransitionTarget? -> } }
                val coverBoundsChanged = if (isPlaying) {
                    onPlayingCoverBoundsChanged
                } else {
                    noopCoverBoundsChanged
                }
                val coverTargetChanged = if (isPlaying) {
                    onPlayingCoverTargetChanged
                } else {
                    noopCoverTargetChanged
                }
                val itemClick = remember(
                    song.id,
                    song.path,
                    onSongClick,
                    onPlayingCoverBoundsChanged,
                    onPlayingCoverTargetChanged
                ) {
                    { target: CoverTransitionTarget? ->
                        onPlayingCoverBoundsChanged(target?.bounds)
                        onPlayingCoverTargetChanged(target)
                        onSongClick(song, index)
                    }
                }
                val itemLongClick = remember(song.id, song.path, onSongLongClick) {
                    { onSongLongClick(song, index) }
                }
                ComposePowerListTransitionItem(
                    // Project-style physical holder slot. The holder persists across row-boundary
                    // movement; artwork identity is bound inside the holder, not by tearing down the
                    // whole cell when range.first advances. This prevents request detach/callback-lost
                    // bursts in 3/4-column grids while keeping cross-id artwork leakage guarded by the
                    // artwork cell's FileArtworkId/no-art gate.
                    compositionSlot = compositionSlot,
                    song = song,
                    index = index,
                    mode = mode,
                    params = params,
                    position = position,
                    // Every rendered holder binds immediately. The provider owns source records and
                    // request coalescing; the scroll layer only moves physical holders and must not
                    // suspend or restart artwork work at row boundaries.
                    deferBitmapLoad = false,
                    isPlaying = isPlaying,
                    isSelected = index in selectedPositions,
                    selectionActive = selectedPositions.isNotEmpty(),
                    boundaryScale = boundaryScale,
                    hideCover = hideCover,
                    interactionActive = interactionActive,
                    artworkPriority = artworkPriority,
                    sharedCoverSceneId = sharedCoverSceneId,
                    sharedCoverElementId = sharedCoverElementId,
                    onCoverBoundsChanged = coverBoundsChanged,
                    onCoverTargetChanged = coverTargetChanged,
                    onClick = itemClick,
                    onLongClick = itemLongClick
                )
            }
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
    contentBottomPaddingPx: Int,
    sectionHeaders: List<PowerListSectionHeader>,
    sectionHeaderHeightPx: Int,
    sectionHeaderContent: @Composable (PowerListSectionHeader) -> Unit,
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
    val sourceGeometry = remember(sourceMetrics, contentBottomPaddingPx, contentTopPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx) {
        listGeometryFor(
            metrics = sourceMetrics,
            bottomPaddingPx = contentBottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            itemCount = songs.size,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx
        )
    }
    val targetGeometry = remember(targetMetrics, contentBottomPaddingPx, contentTopPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx) {
        listGeometryFor(
            metrics = targetMetrics,
            bottomPaddingPx = contentBottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            itemCount = songs.size,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx
        )
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
    val progressProvider = remember(state) { { state.transitionProgress.coerceIn(0f, 1f) } }
    val zoomInTransition = powerListModeOrder(state.targetMode) > powerListModeOrder(state.sourceMode)
    Box(modifier = modifier) {
        ComposePowerListTransitionSectionHeaders(
            headers = sectionHeaders,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            sourceScrollYPx = scrollModel.sourceScrollYPx,
            targetScrollYPx = scrollModel.targetScrollYPx,
            viewportHeightPx = heightPx,
            progressProvider = progressProvider,
            content = sectionHeaderContent
        )
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
                        sourceMode = state.sourceMode,
                        targetMode = state.targetMode,
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
    interactionActive: Boolean = false,
    artworkPriority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
    sharedCoverSceneId: String = "",
    sharedCoverElementId: String = "",
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    contentAlpha: Float = 1f
) {
    val density = LocalDensity.current
    if (POWER_LIST_TRACE_ART) {
        DisposableEffect(compositionSlot, song.id, song.path) {
            powerListArtLog(
                "ITEM_BIND slot=$compositionSlot index=$index song=${song.id} " +
                    "key=${song.coverKey.tailForLog()}"
            )
            onDispose {
                powerListArtLog(
                    "ITEM_DISPOSE slot=$compositionSlot index=$index song=${song.id} " +
                        "key=${song.coverKey.tailForLog()}"
                )
            }
        }
    }
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
        .then(
            if (!interactionActive || isPlaying) {
                Modifier.powerListSceneTransitionItem(
                    sceneId = sharedCoverSceneId,
                    itemId = sharedCoverElementId
                )
            } else {
                Modifier
            }
        )
        .graphicsLayer { alpha = position.alpha.coerceIn(0f, 1f) }

    key(compositionSlot) {
        Box(modifier = itemModifier.graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }) {
            ComposePowerListDrawnItem(
                song = song,
                index = index,
                mode = mode,
                params = params,
                position = position,
                deferBitmapLoad = deferBitmapLoad,
                isPlaying = isPlaying,
                isSelected = isSelected,
                selectionActive = selectionActive,
                boundaryScale = boundaryScale,
                hideCover = hideCover,
                interactionActive = interactionActive,
                artworkPriority = artworkPriority,
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
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
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
                sourceMode = sourceMode,
                targetMode = targetMode,
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
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    position: ComposeItemPosition,
    deferBitmapLoad: Boolean = false,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    hideCover: Boolean = false,
    interactionActive: Boolean = false,
    artworkPriority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
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
    // Cached wrappers bind immediately.  Cold source decoding stays on BitmapProvider's worker
    // lane, so the holder never performs file work during a scroll frame.
    val effectiveDeferBitmapLoad = deferBitmapLoad
    val useCoilArtwork = POWER_LIST_USE_COIL_ARTWORK
    val artworkState = if (useCoilArtwork) {
        PowerListBitmapState(bitmap = null, terminalNoArt = false)
    } else {
        rememberPowerListBitmap(
            key = song.coverKey,
            albumAliasKey = "",
            externalArtworkPath = song.albumArtPath,
            targetWidth = coverSize,
            targetHeight = coverSize,
            // Visible artwork always joins the serial list queue. The provider already keeps this
            // queue ordered, so gating until a full gesture stop only turns a smooth stream of covers
            // into a simultaneous post-scroll burst.
            deferLoad = effectiveDeferBitmapLoad,
            index = index,
            modeLabel = mode.name,
            priority = artworkPriority
        )
    }
    val bitmap = artworkState.bitmap
    val terminalNoArtwork = artworkState.terminalNoArt
    val drawableBitmap = if (useCoilArtwork) {
        null
    } else {
        artworkState.handle
            ?.takeIf { it.isValid }
            ?.bitmap
            ?.takeIf { !it.isRecycled }
            ?: bitmap?.takeIf { !it.isRecycled }
    }
    // Keep the shader attached to the physical holder. Drawing a rounded bitmap with a
    // BitmapShader avoids rebuilding a Path and entering/leaving a clip stack for every cell on
    // every scroll frame. This is the same retained-source shape used by Poweramp's AAImageView.
    val previousBitmap = artworkState.previousBitmap
        ?.takeIf { !it.isRecycled && it !== drawableBitmap }
    val drawableShader = remember(drawableBitmap) {
        drawableBitmap
            ?.takeIf { !it.isRecycled }
            ?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
    val previousShader = remember(previousBitmap) {
        previousBitmap
            ?.takeIf { !it.isRecycled }
            ?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
    val title = song.displayName
    val subtitle = song.subtitle()
    val meta = song.metaText()
    val hasCollectionMetaIcon = song.encodingFormat == POWER_LIST_COLLECTION_ENCODING
    val copyTextBounds = remember(mode, rects) {
        if (mode.isGrid) {
            null
        } else {
            val visibleRects = listOf(rects.title, rects.subtitle, rects.meta)
                .filter { it.alpha > 0f && it.width > 0 && it.height > 0 }
            if (visibleRects.isEmpty()) {
                null
            } else {
                IntRect(
                    left = visibleRects.minOf { it.left },
                    top = visibleRects.minOf { it.top },
                    right = visibleRects.maxOf { it.left + it.width },
                    bottom = visibleRects.maxOf { it.top + it.height }
                )
            }
        }
    }

    val titleColor = colors.title
    val subtitleColor = colors.secondary
    val metaColor = colors.meta
    val configuredTypeface = FontManager.typeface
    val titleText = rememberPowerListPreparedText(
        text = title,
        rect = rects.title,
        density = density.density * density.fontScale,
        bold = true,
        configuredTypeface = configuredTypeface
    )
    val subtitleText = rememberPowerListPreparedText(
        text = subtitle,
        rect = rects.subtitle,
        density = density.density * density.fontScale,
        bold = false,
        configuredTypeface = configuredTypeface
    )
    val metaText = rememberPowerListPreparedText(
        text = meta,
        rect = rects.meta,
        density = density.density * density.fontScale,
        bold = false,
        leftInsetPx = if (hasCollectionMetaIcon) 16f * density.density else 0f,
        configuredTypeface = configuredTypeface
    )

    // The bounds are only read when this physical holder is clicked. Keeping them in a stable
    // slot avoids invalidating every list cell on every scroll-layout callback; the old
    // mutableState value made dense 3/4-column flings recompose the artwork and text together.
    val rootBounds = remember { arrayOfNulls<RectF>(1) }
    val trackRootBounds = isPlaying ||
        (!interactionActive && sharedCoverSceneId.isNotBlank() && sharedCoverElementId.isNotBlank())
    val shouldHideForSharedCover = shouldHideSharedCover(
        sceneId = sharedCoverSceneId,
        elementId = sharedCoverElementId
    )

    fun clickedCoverTarget(): CoverTransitionTarget? {
        val root = rootBounds[0] ?: return null
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
            coverKey = song.coverKey
        )
    }

    val rootBoundsModifier = if (trackRootBounds) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            val next = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val previous = rootBounds[0]
            if (previous == null || !previous.nearlyEquals(next, tolerance = 1f)) {
                rootBounds[0] = next
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(rootBoundsModifier)
            .then(if (isPlaying) Modifier.trackDrawnCoverBounds(rects.cover, onCoverBoundsChanged) else Modifier)
            .then(if (isPlaying) Modifier.trackDrawnCoverTarget(rects.cover, rects.coverRadiusDp, song.id, song.coverKey, -1, onCoverTargetChanged) else Modifier)
            .then(
                if (!interactionActive || isPlaying) {
                    Modifier.trackSharedCoverSlot(
                        sceneId = sharedCoverSceneId,
                        elementId = sharedCoverElementId,
                        cover = rects.cover,
                        radiusDp = rects.coverRadiusDp,
                        coverKey = song.coverKey
                    )
                } else {
                    Modifier
                }
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
            powerListPaint.shader = null
            if (hideCover || shouldHideForSharedCover) {
                // Keep the slot empty while the shared cover overlay is flying.
            } else {
                // Keep the placeholder behind the accepted source while a holder is resolving.
                // When a new source is accepted, draw the retained source first and fade the new
                // shader over it. This is the same two-layer operation used by Poweramp's
                // AAImageView, with its normal 200 ms artwork transition.
                val fade = artworkState.fadeProgress.coerceIn(0f, 1f)
                if (drawableShader == null && previousShader == null) {
                    powerListPaint.color = colors.secondary.copy(alpha = 0.085f).toArgb()
                    canvas.drawRoundRect(coverLeft, coverTop, coverRight, coverBottom, radius, radius, powerListPaint)
                } else {
                    fun drawArtworkLayer(shader: BitmapShader, bitmap: Bitmap, alpha: Int) {
                        powerListBitmapMatrix.reset()
                        configureCenterCropMatrix(
                            matrix = powerListBitmapMatrix,
                            bitmap = bitmap,
                            left = coverLeft,
                            top = coverTop,
                            width = cover.width.toFloat(),
                            height = cover.height.toFloat()
                        )
                        shader.setLocalMatrix(powerListBitmapMatrix)
                        powerListPaint.color = android.graphics.Color.WHITE
                        powerListPaint.alpha = alpha.coerceIn(0, 255)
                        powerListPaint.shader = shader
                        canvas.drawRoundRect(coverLeft, coverTop, coverRight, coverBottom, radius, radius, powerListPaint)
                    }

                    if (previousShader != null && previousBitmap != null && fade < 1f) {
                        drawArtworkLayer(
                            shader = previousShader,
                            bitmap = previousBitmap,
                            alpha = ((1f - fade) * 255f).roundToInt()
                        )
                    }
                    if (drawableShader != null && drawableBitmap != null) {
                        drawArtworkLayer(
                            shader = drawableShader,
                            bitmap = drawableBitmap,
                            alpha = if (previousShader == null) 255 else (fade * 255f).roundToInt()
                        )
                    }
                    powerListPaint.shader = null
                    powerListPaint.alpha = 255
                }
            }

            drawPowerListText(
                canvas = canvas,
                text = titleText.display,
                rect = rects.title,
                color = titleColor,
                density = density.density * density.fontScale,
                bold = true,
                typefaceOverride = titleText.typeface,
                alreadyEllipsized = true
            )
            drawPowerListText(
                canvas = canvas,
                text = subtitleText.display,
                rect = rects.subtitle,
                color = subtitleColor,
                density = density.density * density.fontScale,
                bold = false,
                typefaceOverride = subtitleText.typeface,
                alreadyEllipsized = true
            )
            drawPowerListText(
                canvas = canvas,
                text = metaText.display,
                rect = rects.meta,
                color = metaColor,
                density = density.density * density.fontScale,
                bold = false,
                leftInsetPx = if (hasCollectionMetaIcon) 16f * density.density else 0f,
                typefaceOverride = metaText.typeface,
                alreadyEllipsized = true
            )
            canvas.restore()
        }

        if (
            hasCollectionMetaIcon &&
            rects.meta.alpha > 0f &&
            rects.meta.width > 0 &&
            rects.meta.height > 0
        ) {
            val iconSizePx = minOf(
                rects.meta.height,
                with(density) { 12.dp.roundToPx() }
            ).coerceAtLeast(1)
            Image(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                colorFilter = ColorFilter.tint(metaColor),
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            x = rects.meta.left,
                            y = rects.meta.top + (rects.meta.height - iconSizePx) / 2
                        )
                    }
                    .requiredSize(with(density) { iconSizePx.toDp() })
                    .graphicsLayer {
                        alpha = rects.meta.alpha.coerceIn(0f, 1f)
                    }
            )
        }

        copyTextBounds?.let { bounds ->
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(bounds.left, bounds.top) }
                    .requiredSize(
                        width = with(density) { bounds.width.coerceAtLeast(1).toDp() },
                        height = with(density) { bounds.height.coerceAtLeast(1).toDp() }
                    )
                    .combinedClickable(
                        onClick = { onClick(clickedCoverTarget()) },
                        onLongClick = { copySongInfoToClipboard(context, song) }
                    )
            )
        }

        if (useCoilArtwork && !hideCover && !shouldHideForSharedCover && song.coverKey.isNotBlank()) {
            PowerListCoilCover(
                coverKey = song.coverKey,
                targetSide = coverSize,
                modeLabel = mode.name,
                radiusDp = rects.coverRadiusDp,
                freezeDuringInteraction = interactionActive,
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(rects.cover.left, rects.cover.top)
                    }
                    .requiredSize(
                        width = with(density) { rects.cover.width.coerceAtLeast(1).toDp() },
                        height = with(density) { rects.cover.height.coerceAtLeast(1).toDp() }
                    )
            )
        }

        if (!useCoilArtwork && drawableBitmap == null &&
            shouldShowDefaultAlbumArtwork(song.coverKey, coverSize, coverSize) &&
            !hideCover && !shouldHideForSharedCover
        ) {
            DefaultAlbumArtwork(
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(rects.cover.left, rects.cover.top)
                    }
                    .requiredSize(
                        width = with(density) { rects.cover.width.coerceAtLeast(1).toDp() },
                        height = with(density) { rects.cover.height.coerceAtLeast(1).toDp() }
                    )
                    .clip(RoundedCornerShape(rects.coverRadiusDp.dp)),
                contentDescription = title,
                contentScale = ContentScale.Crop
            )
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
private fun PowerListCoilCover(
    coverKey: String,
    targetSide: Int,
    modeLabel: String,
    radiusDp: Float,
    retainedSide: Int? = null,
    freezeDuringInteraction: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { PowerListCoilArtwork.imageLoader(context) }
    val decodeSide = remember(targetSide, modeLabel) {
        powerListDecodeSideForMode(
            requestedSide = targetSide.coerceAtLeast(1),
            modeLabel = modeLabel
        )
    }
    val defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled
    // A physical PowerList holder survives row movement. Keep its requested decode tier stable
    // while scrolling/pinching; otherwise a zoom frame changes the Coil model, clears the
    // painter for one frame, and then fades the same cover back in.
    val stableDecodeSide = remember(coverKey) { mutableIntStateOf(decodeSide) }
    val previousDecodeSide = remember(coverKey) { mutableIntStateOf(decodeSide) }
    LaunchedEffect(coverKey, decodeSide, freezeDuringInteraction) {
        if (!freezeDuringInteraction && stableDecodeSide.intValue != decodeSide) {
            previousDecodeSide.intValue = stableDecodeSide.intValue
            stableDecodeSide.intValue = decodeSide
        }
    }
    val activeDecodeSide = stableDecodeSide.intValue
    val model = remember(coverKey, activeDecodeSide, defaultArtworkEnabled) {
        PowerListCoilArtworkModel(
            coverKey = coverKey,
            targetSide = activeDecodeSide,
            // modeLabel is layout metadata, not artwork identity. Keeping it stable prevents a
            // 3-column/4-column recomposition from restarting the same image request.
            modeLabel = "LIST_STABLE",
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled
        )
    }
    val previousMemoryCacheKey = remember(
        coverKey,
        activeDecodeSide,
        previousDecodeSide.intValue,
        defaultArtworkEnabled
    ) {
        previousDecodeSide.intValue
            .takeIf { it > 0 && it != activeDecodeSide }
            ?.let { side ->
            PowerListCoilArtworkModel(
                coverKey = coverKey,
                targetSide = side,
                modeLabel = "LIST_STABLE",
                defaultArtworkEnabled = defaultArtworkEnabled
            ).cacheKey
        }
    }
    val retainedRequest = remember(context, model, previousMemoryCacheKey) {
        ImageRequest.Builder(context)
            .data(model)
            .size(model.side, model.side)
            .memoryCacheKey(model.cacheKey)
            .diskCacheKey(model.cacheKey)
            .apply {
                if (!previousMemoryCacheKey.isNullOrBlank()) {
                    placeholderMemoryCacheKey(previousMemoryCacheKey)
                }
            }
            .allowHardware(true)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .networkCachePolicy(coil.request.CachePolicy.DISABLED)
            // PowerList owns the geometry transition. A Coil crossfade wraps the result in a
            // transition drawable, which leaves the bitmap-only Canvas empty at pinch boundaries.
            .crossfade(0)
            .build()
    }
    val painter = rememberAsyncImagePainter(
        model = retainedRequest,
        imageLoader = imageLoader
    )
    // The settled and transition renderers are separate compositions. Seed every new holder from
    // the already-decoded source tier synchronously, so entering or leaving pinch never exposes
    // Coil's transient Empty/Loading state, including for non-interpolated items.
    val cachedBitmap = remember(coverKey, activeDecodeSide, retainedSide, defaultArtworkEnabled) {
        val retainedDecodeSide = retainedSide?.coerceAtLeast(1)
        retainedDecodeSide
            ?.let { PowerListCoilArtwork.peekBitmap(context, coverKey, it, it) }
            ?: PowerListCoilArtwork.peekBitmap(
                context = context,
                key = coverKey,
                width = activeDecodeSide,
                height = activeDecodeSide
            )
    }
    val lastAcceptedBitmap = remember(coverKey) { mutableStateOf(cachedBitmap) }
    val painterState = painter.state
    val currentBitmap = (painterState as? AsyncImagePainter.State.Success)
        ?.result
        ?.drawable
        ?.let { it as? android.graphics.drawable.BitmapDrawable }
        ?.bitmap
        ?.takeIf { !it.isRecycled }
    LaunchedEffect(painterState) {
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            lastAcceptedBitmap.value = currentBitmap
        }
    }
    val retainedSlotBitmap = lastAcceptedBitmap.value?.takeIf { !it.isRecycled }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radiusDp.dp))
            .fillMaxSize()
    ) {
        // Coil remains the request/cache owner. The scrolling surface draws only the last
        // accepted bitmap, so a row-window rebind cannot expose Coil's transient Empty/Loading
        // painter for one frame.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = currentBitmap ?: retainedSlotBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                powerListBitmapMatrix.reset()
                configureCenterCropMatrix(
                    matrix = powerListBitmapMatrix,
                    bitmap = bitmap,
                    left = 0f,
                    top = 0f,
                    width = size.width,
                    height = size.height
                )
                powerListPaint.shader = null
                powerListPaint.alpha = 255
                powerListPaint.isFilterBitmap = true
                drawContext.canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    powerListBitmapMatrix,
                    powerListPaint
                )
            }
        }
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
@Suppress("UNUSED_PARAMETER")
private fun rememberPowerListBitmap(
    key: String,
    albumAliasKey: String = "",
    externalArtworkPath: String = "",
    targetWidth: Int,
    targetHeight: Int,
    deferLoad: Boolean = false,
    index: Int = -1,
    modeLabel: String = "",
    priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST
): PowerListBitmapState {
    val id = remember(key) { FileArtworkId.fromCoverKey(key) }
    val decodeSide = remember(targetWidth, targetHeight, modeLabel) {
        powerListDecodeSideForMode(
            requestedSide = max(targetWidth, targetHeight).coerceAtLeast(1),
            modeLabel = modeLabel
        )
    }
    val decodeWidth = decodeSide
    val decodeHeight = decodeSide
    val bucket = remember(decodeWidth, decodeHeight) {
        SizeSlotCache.computeBucket(decodeWidth, decodeHeight)
    }
    // Keep this state attached to the physical PowerList slot, not to the song identity. This is
    // the Compose equivalent of Poweramp's AAImageView retaining its current wrapper while a new
    // source record is being resolved. Re-keying this state by id cleared the drawable at every
    // row reuse and made a fast fling look like a decode failure.
    val bitmapState = remember(decodeWidth, decodeHeight, modeLabel) {
        val initialBitmap = BitmapProvider.peekThumbnail(
            key = id.value,
            targetWidth = decodeWidth,
            targetHeight = decodeHeight,
            providerAliasKey = albumAliasKey
        )?.takeIf { !it.isRecycled }?.also {
            powerListArtLog(
                "AA_CACHE_PEEK index=$index mode=$modeLabel bucket=$bucket bitmap=${it.width}x${it.height} id=${id.value.tailForLog()}"
            )
        }
        val initialHandle = initialBitmap?.let {
            BitmapProvider.acquireLoaded(
                key = id.value,
                bitmap = it,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                surface = ArtworkSurface.List,
                providerAliasKey = albumAliasKey
            )
        }
        mutableStateOf(
            PowerListBitmapState(
                bitmap = initialHandle?.bitmap ?: initialBitmap,
                // A provider failure is not a holder state. The source record may become
                // available on the next probe, so only a callback carrying terminalNoArt may
                // clear the retained wrapper.
                terminalNoArt = false,
                handle = initialHandle,
                sourceKey = id.value
            )
        )
    }
    // A physical PowerList slot can be rebound before its provider callback returns. Keep a
    // monotonically increasing bind generation, like Poweramp's AAImageView position/id check,
    // so an old callback can warm the provider cache but can never mutate the new slot binding.
    val bindGeneration = remember { AtomicLong(0L) }
    val artworkFade = remember { Animatable(1f) }
    val currentFadeToken = bitmapState.value.fadeToken

    LaunchedEffect(currentFadeToken) {
        if (bitmapState.value.previousBitmap == null) {
            artworkFade.snapTo(1f)
            return@LaunchedEffect
        }
        artworkFade.snapTo(0f)
        artworkFade.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = POWER_LIST_ART_FADE_MS)
        )
        if (bitmapState.value.fadeToken == currentFadeToken) {
            bitmapState.value = bitmapState.value.copy(previousBitmap = null, fadeProgress = 1f)
        }
    }

    DisposableEffect(decodeWidth, decodeHeight, modeLabel) {
        onDispose {
            // The source wrapper is owned by this physical holder until a rebind or detach. The
            // provider cache itself remains alive for other holders and future scroll frames.
            bitmapState.value.handle?.release()
        }
    }

    DisposableEffect(
        id.value,
        albumAliasKey,
        externalArtworkPath,
        decodeWidth,
        decodeHeight,
        deferLoad,
        modeLabel,
        priority
    ) {
        val boundKey = id.value
        // Attach an already decoded wrapper immediately. When there is no cached wrapper, keep
        // the previous one until this source finishes, matching Poweramp's holder behavior.
        if (bitmapState.value.sourceKey != boundKey) {
            val cachedBitmap = BitmapProvider.peekThumbnail(
                key = boundKey,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                providerAliasKey = albumAliasKey
            )?.takeIf { !it.isRecycled }
            if (cachedBitmap != null) {
                val nextHandle = BitmapProvider.acquireLoaded(
                    key = boundKey,
                    bitmap = cachedBitmap,
                    targetWidth = decodeWidth,
                    targetHeight = decodeHeight,
                    surface = ArtworkSurface.List,
                    providerAliasKey = albumAliasKey
                )
                val previousState = bitmapState.value
                val oldHandle = previousState.handle
                val nextBitmap = nextHandle?.bitmap ?: cachedBitmap
                val retainedBitmap = previousState.bitmap
                    ?.takeIf {
                        previousState.sourceKey != boundKey &&
                            it !== nextBitmap &&
                            !it.isRecycled
                    }
                val nextFadeToken = if (retainedBitmap != null) {
                    previousState.fadeToken + 1L
                } else {
                    0L
                }
                bitmapState.value = PowerListBitmapState(
                    bitmap = nextBitmap,
                    terminalNoArt = false,
                    handle = nextHandle,
                    sourceKey = boundKey,
                    previousBitmap = retainedBitmap,
                    fadeToken = nextFadeToken,
                    fadeProgress = if (retainedBitmap == null) 1f else 0f
                )
                if (oldHandle !== nextHandle) oldHandle?.release()
            }
        }

        // Do not key this effect by the callback result. A successful callback must update the
        // current holder in place; rebuilding the effect immediately would detach/cancel the
        // source request and is unlike Poweramp's stable bitmap wrapper lifecycle.
        val cachedBitmapIsReady = bitmapState.value.sourceKey == boundKey && bitmapState.value.bitmap?.let { bitmap ->
            !bitmap.isRecycled && isPowerListBitmapAcceptable(bitmap, decodeWidth, decodeHeight)
        } == true
        if (cachedBitmapIsReady || deferLoad) {
            if (deferLoad && !cachedBitmapIsReady) {
                powerListArtLog(
                    "AA_REQUEST_DEFER_NO_ASYNC index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} id=${id.value.tailForLog()}"
                )
            }
            onDispose { }
        } else {
            val seq = powerListArtSeq.incrementAndGet()
            val callbackGeneration = bindGeneration.incrementAndGet()
            powerListArtLog(
                "AA_REQUEST_LOAD seq=$seq index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} id=${id.value.tailForLog()}"
            )
            lateinit var request: BitmapRequest
            request = BitmapProvider.loadViewportThumbnail(
                key = id.value,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                priority = priority,
                providerAliasKey = albumAliasKey,
                externalArtworkPath = externalArtworkPath
            ) { loaded ->
                if (bindGeneration.get() != callbackGeneration) {
                    powerListArtLog(
                        "AA_REQUEST_DROP_REBOUND seq=$seq index=$index mode=$modeLabel id=${id.value.tailForLog()}"
                    )
                    return@loadViewportThumbnail
                }
                if (loaded != null && !loaded.isRecycled) {
                    powerListArtLog(
                        "AA_REQUEST_CALLBACK seq=$seq index=$index mode=$modeLabel bitmap=${loaded.width}x${loaded.height} id=${id.value.tailForLog()}"
                    )
                    val nextHandle = BitmapProvider.acquireLoaded(
                        key = id.value,
                        bitmap = loaded,
                        targetWidth = decodeWidth,
                        targetHeight = decodeHeight,
                        surface = ArtworkSurface.List,
                        providerAliasKey = albumAliasKey
                    )
                    val previousState = bitmapState.value
                    val oldHandle = previousState.handle
                    val nextBitmap = nextHandle?.bitmap ?: loaded
                    val retainedBitmap = previousState.bitmap
                        ?.takeIf {
                            previousState.sourceKey != boundKey &&
                                it !== nextBitmap &&
                                !it.isRecycled
                        }
                    val nextFadeToken = if (retainedBitmap != null) {
                        previousState.fadeToken + 1L
                    } else {
                        0L
                    }
                    bitmapState.value = PowerListBitmapState(
                        bitmap = nextBitmap,
                        terminalNoArt = false,
                        handle = nextHandle,
                        sourceKey = boundKey,
                        previousBitmap = retainedBitmap,
                        fadeToken = nextFadeToken,
                        fadeProgress = if (retainedBitmap == null) 1f else 0f
                    )
                    if (oldHandle !== nextHandle) oldHandle?.release()
                } else {
                    if (request.terminalNoArt) {
                        powerListArtLog(
                            "AA_REQUEST_CALLBACK_NO_ART seq=$seq index=$index mode=$modeLabel id=${id.value.tailForLog()}"
                        )
                        val oldHandle = bitmapState.value.handle
                        bitmapState.value = PowerListBitmapState(
                            bitmap = null,
                            terminalNoArt = true,
                            sourceKey = boundKey,
                            fadeToken = 0L,
                            fadeProgress = 1f
                        )
                        oldHandle?.release()
                    } else {
                        powerListArtLog(
                            "AA_REQUEST_CALLBACK_TRANSIENT seq=$seq index=$index mode=$modeLabel id=${id.value.tailForLog()}"
                        )
                        // Keep the last accepted wrapper. A transient open/decode failure must
                        // not turn into a 30-minute no-art sentinel or a placeholder flash.
                        bitmapState.value = bitmapState.value.copy(terminalNoArt = false)
                    }
                }
            }
            onDispose {
                // Invalidate the callback before cancelling. The provider may already have
                // posted a result to the main looper, so cancellation alone is not a binding
                // identity check.
                bindGeneration.incrementAndGet()
                powerListArtLog(
                    "AA_REQUEST_DETACH seq=$seq index=$index mode=$modeLabel cancelled=${request.isCancelled} id=${id.value.tailForLog()}"
                )
                // A fast fling can detach an entire row window in one frame. Keep completed
                // source records in BitmapProvider's cache, but do not let detached list cells
                // keep queued work alive; otherwise a single serial decoder spends the fling
                // resolving covers that are already far outside the viewport.
                // Let an already-started source probe finish and warm the shared bitmap cache.
                // Only work that is still queued may be discarded by BitmapProvider. Cancelling
                // the active decode here makes a fast fling repeatedly restart the same serial
                // provider lane and leaves the next holder with a transient blank cover.
                BitmapProvider.cancel(request, keepDecoding = true)
            }
        }
    }

    return bitmapState.value.copy(fadeProgress = artworkFade.value)
}

// PowerList visible album-art decode cap. Keep large grid/hero covers crisp without touching scanner metadata paths.
private const val POWER_LIST_COVER_DECODE_MAX = 1024
private const val POWER_LIST_LIST_SMALL_DECODE = 192
private const val POWER_LIST_LIST_NORMAL_DECODE = 384
private const val POWER_LIST_LIST_ZOOMED_DECODE = 512
private const val POWER_LIST_GRID_4_DECODE = 384
private const val POWER_LIST_GRID_3_DECODE = 512
private const val POWER_LIST_GRID_2_DECODE = 784
private const val POWER_LIST_MAX_RENDER_ITEMS = 48
private const val POWER_LIST_ART_FADE_MS = 200

// Keep artwork ownership in BitmapProvider/ArtworkHandle. Compose only keeps the last accepted
// physical-cell bitmap while a replacement request is in flight, matching Poweramp's view holder.
// Experimental A/B branch: list/grid covers are painted by Coil while BitmapProvider remains the
// RawSMusic-specific decoder/cache backend. Flip to false to restore the legacy artwork record path.
private const val POWER_LIST_USE_COIL_ARTWORK = true
private const val POWER_LIST_TRACE_ART = false
private const val POWER_LIST_TRACE_FRAMES = false
private const val POWER_LIST_TRACE_FRAME_GAP_MS = 24L
private const val POWER_LIST_TRACE_TAG = "RawPowerList"
private val powerListArtSeq = AtomicLong(0L)

private data class PowerListBitmapState(
    val bitmap: Bitmap?,
    val terminalNoArt: Boolean,
    val handle: ArtworkHandle? = null,
    val sourceKey: String = "",
    val previousBitmap: Bitmap? = null,
    val fadeToken: Long = 0L,
    val fadeProgress: Float = 1f
)

private fun powerListDecodeSideForMode(requestedSide: Int, modeLabel: String): Int {
    val fixedSide = when (modeLabel) {
        ComposePowerListDisplayMode.LIST_SMALL.name -> POWER_LIST_LIST_SMALL_DECODE
        ComposePowerListDisplayMode.LIST_NORMAL.name -> POWER_LIST_LIST_NORMAL_DECODE
        ComposePowerListDisplayMode.LIST_ZOOMED.name -> POWER_LIST_LIST_ZOOMED_DECODE
        ComposePowerListDisplayMode.GRID_4.name -> POWER_LIST_GRID_4_DECODE
        ComposePowerListDisplayMode.GRID_3.name -> POWER_LIST_GRID_3_DECODE
        ComposePowerListDisplayMode.GRID_2.name -> POWER_LIST_GRID_2_DECODE
        else -> requestedSide
    }
    return fixedSide
        .coerceAtLeast(1)
        .coerceAtMost(POWER_LIST_COVER_DECODE_MAX)
}

private fun isPowerListBitmapAcceptable(
    bitmap: Bitmap,
    targetWidth: Int,
    targetHeight: Int
): Boolean {
    if (bitmap.isRecycled) return false
    val requestedSide = max(targetWidth, targetHeight).coerceAtLeast(1)
    // A cache hit is only complete when both dimensions cover the cell. Accepting the generic
    // 384/512 low tier for a 784px grid cell made the holder stop requesting the proper source,
    // so Canvas then enlarged a low-resolution bitmap and the outer lanes looked soft.
    return minOf(bitmap.width, bitmap.height) >= requestedSide
}

private fun replacePowerListHandle(
    state: MutableState<ArtworkHandle?>,
    next: ArtworkHandle?
) {
    val previous = state.value
    if (previous === next) return

    // Never keep a closed handle in Compose state.  A closed ArtworkHandle still exposes a raw
    // Bitmap object, so the old implementation could report DISPLAY_READY while the actual owner
    // had already been detached by a previous slot dispose/rebind.  The wrapper check is
    // wrapper-valid, not merely bitmap-object-valid.
    if (previous != null && !previous.isValid) {
        state.value = null
        previous.release()
        if (next == null) return
    }

    val current = state.value
    if (current === next) return
    if (current != null && current.isValid && next != null && next.isValid && current.bitmap === next.bitmap) {
        // Re-acquiring the same provider bitmap returns a new handle object. Do not swap the
        // Compose state just because the reference wrapper changed; keep the same bitmap owner
        // attached until the bound artwork identity changes. Swapping here produced repeated
        // CACHE_HIT_LOCAL_EFFECT -> DISPLAY_READY loops during 3/4-column scroll.
        next.release()
        return
    }
    state.value = next
    current?.release()
}

private fun powerListArtLog(message: String) {
    if (POWER_LIST_TRACE_ART) Log.w("RawArt", "POWER_LIST $message")
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
    val registry = LocalSharedCoverRegistry.current
    val spec = LocalSharedTransitionSpec.current
    if (!spec.active || !spec.shouldTrackScene(sceneId)) return false
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
        val spec = LocalSharedTransitionSpec.current
        val shouldTrack = spec.shouldTrackScene(sceneId)

        DisposableEffect(sceneId, elementId, shouldTrack) {
            if (!shouldTrack) registry.unregister(sceneId, elementId)
            onDispose {
                registry.unregister(sceneId, elementId)
            }
        }

        val lastSnapshot = remember { arrayOfNulls<SharedCoverSnapshot>(1) }
        onGloballyPositioned { coordinates ->
            if (!shouldTrack) return@onGloballyPositioned
            val pos = coordinates.positionInWindow()
            val snapshot = SharedCoverSnapshot(
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
            val previous = lastSnapshot[0]
            if (previous == null ||
                previous.coverKey != snapshot.coverKey ||
                previous.radiusDp != snapshot.radiusDp ||
                !rectNearlyEquals(previous.boundsInWindow, snapshot.boundsInWindow)
            ) {
                lastSnapshot[0] = snapshot
                registry.register(sceneId = sceneId, elementId = elementId, snapshot = snapshot)
            }
        }
    }
}

private fun rectNearlyEquals(first: Rect, second: Rect, tolerance: Float = 0.5f): Boolean {
    return abs(first.left - second.left) <= tolerance &&
        abs(first.top - second.top) <= tolerance &&
        abs(first.right - second.right) <= tolerance &&
        abs(first.bottom - second.bottom) <= tolerance
}

private val powerListPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val powerListBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val powerListTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
private val powerListBitmapMatrix = Matrix()

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
    bold: Boolean,
    leftInsetPx: Float = 0f,
    typefaceOverride: Typeface? = null,
    alreadyEllipsized: Boolean = false
) {
    if (text.isBlank() || rect.alpha <= 0f || rect.width <= 0 || rect.height <= 0) return
    val fontSizeSp = rect.fontSizeSp.takeIf { it > 0f } ?: 14f
    powerListTextPaint.color = color.copy(alpha = color.alpha * rect.alpha.coerceIn(0f, 1f)).toArgb()
    powerListTextPaint.textSize = fontSizeSp * density
    powerListTextPaint.typeface = typefaceOverride ?: run {
        val configuredTypeface = FontManager.typeface
        when {
            configuredTypeface == null -> if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            bold -> Typeface.create(configuredTypeface, Typeface.BOLD)
            else -> configuredTypeface
        }
    }
    val availableWidth = (rect.width.toFloat() - leftInsetPx).coerceAtLeast(1f)
    val display = if (alreadyEllipsized) {
        text
    } else {
        TextUtils.ellipsize(
            text,
            powerListTextPaint,
            availableWidth,
            TextUtils.TruncateAt.END
        )
    }
    val fontMetrics = powerListTextPaint.fontMetrics
    val baseline = rect.top + (rect.height - fontMetrics.ascent - fontMetrics.descent) * 0.5f
    canvas.drawText(display.toString(), rect.left.toFloat() + leftInsetPx, baseline, powerListTextPaint)
}

private data class PreparedPowerListText(
    val display: String,
    val typeface: Typeface
)

@Composable
private fun rememberPowerListPreparedText(
    text: String,
    rect: ComposeItemRect,
    density: Float,
    bold: Boolean,
    leftInsetPx: Float = 0f,
    configuredTypeface: Typeface?
): PreparedPowerListText {
    return remember(
        text,
        rect.width,
        rect.fontSizeSp,
        density,
        bold,
        leftInsetPx,
        configuredTypeface
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = (rect.fontSizeSp.takeIf { it > 0f } ?: 14f) * density
            typeface = when {
                configuredTypeface == null -> if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                bold -> Typeface.create(configuredTypeface, Typeface.BOLD)
                else -> configuredTypeface
            }
        }
        val availableWidth = (rect.width.toFloat() - leftInsetPx).coerceAtLeast(1f)
        PreparedPowerListText(
            display = TextUtils.ellipsize(
                text,
                paint,
                availableWidth,
                TextUtils.TruncateAt.END
            ).toString(),
            typeface = paint.typeface ?: Typeface.DEFAULT
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerTransitionVisualFast(
    song: AudioFile,
    index: Int,
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
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
            coverKey = song.coverKey
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
            sourceMode = sourceMode,
            targetMode = targetMode,
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
    sourceMode: ComposePowerListDisplayMode,
    targetMode: ComposePowerListDisplayMode,
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
    val sourceCoverSize = maxOf(source.width, source.height).coerceAtLeast(1)
    val targetCoverSize = maxOf(target.width, target.height).coerceAtLeast(1)
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
                        coverKey = song.coverKey,
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
        if (POWER_LIST_USE_COIL_ARTWORK) {
            PowerListCoilCover(
                coverKey = song.coverKey,
                targetSide = targetCoverSize,
                modeLabel = "TRANSITION_STABLE",
                radiusDp = 0f,
                retainedSide = sourceCoverSize,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val coverSize = maxOf(sourceCoverSize, targetCoverSize)
            val artworkState = rememberPowerListBitmap(
                key = song.coverKey,
                albumAliasKey = "",
                externalArtworkPath = song.albumArtPath,
                targetWidth = coverSize,
                targetHeight = coverSize,
                index = index,
                modeLabel = "TRANSITION"
            )
            PowerListBitmapCanvas(
                bitmap = artworkState.bitmap,
                placeholderColor = Color.Transparent,
                modifier = Modifier.fillMaxSize()
            )
        }
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
    Canvas(modifier = Modifier.fillMaxSize()) {
        val progress = progressProvider()
        val rect = transitionLocalRect(source, target, progress).copy(
            fontSizeSp = lerpFloatLocal(sourceFontSize, targetFontSize, progress)
        )
        drawPowerListText(
            canvas = drawContext.canvas.nativeCanvas,
            text = text,
            rect = rect,
            color = color,
            density = density.density,
            bold = fontWeight == FontWeight.Bold
        )
    }
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
            val normalizedSampleRate = com.rawsmusic.core.common.utils.SampleRateNormalizer.formatKhz(
                sampleRate = sampleRate,
                codecName = encodingFormat,
                formatName = format,
                filePath = path
            )
            if (normalizedSampleRate.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append(normalizedSampleRate)
            }
        }
        if (bitsPerSample > 0) {
            if (isNotBlank()) append(" · ")
            append(bitsPerSample)
            append("bit")
        }
        val bitrateText = com.rawsmusic.core.common.utils.BitrateNormalizer
            .formatKbps(
                rawBitrate = bitRate,
                durationMs = duration,
                fileSizeBytes = fileSize,
                codecName = encodingFormat,
                formatName = format,
                filePath = path
            )
            .takeIf { it != "未知" }
            ?.replace(" ", "")
        if (!bitrateText.isNullOrBlank()) {
            if (isNotBlank()) append(" · ")
            append(bitrateText)
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


private fun capPowerListRange(
    range: IntRange,
    maxItems: Int,
    columns: Int
): IntRange {
    if (range.isEmpty() || maxItems <= 0) return IntRange.EMPTY
    val safeColumns = columns.coerceAtLeast(1)
    val firstRow = range.first / safeColumns
    val rowCount = ((maxItems + safeColumns - 1) / safeColumns).coerceAtLeast(1)
    val cappedLast = (firstRow + rowCount) * safeColumns - 1
    return range.first..cappedLast.coerceAtMost(range.last)
}

private fun powerListMaxRenderItems(
    columns: Int,
    rowStridePx: Int,
    viewportHeightPx: Int
): Int {
    val safeColumns = columns.coerceAtLeast(1)
    val safeStride = rowStridePx.coerceAtLeast(1)
    val visibleRows = ((viewportHeightPx.coerceAtLeast(1) + safeStride - 1) / safeStride)
        .coerceAtLeast(1)
    // Keep one row ahead of the viewport. This is the same small prefetch window used by the
    // reference FastLayout: fractional scrolling moves the existing children, while only a row
    // crossing the boundary needs a new holder. The cap is intentionally row-aligned so a dense
    // grid never creates a partially rendered edge row.
    val bufferedRows = (visibleRows + 1).coerceAtMost(12)
    return (bufferedRows * safeColumns).coerceIn(safeColumns, POWER_LIST_MAX_RENDER_ITEMS)
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

@Composable
private fun ComposePowerListSectionHeaders(
    headers: List<PowerListSectionHeader>,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int,
    boundaryScale: Float,
    content: @Composable (PowerListSectionHeader) -> Unit
) {
    val density = LocalDensity.current
    headers.forEachIndexed { index, header ->
        val rawBounds = geometry.headerBounds.getOrNull(index) ?: return@forEachIndexed
        if (rawBounds.width <= 0 || rawBounds.height <= 0) return@forEachIndexed
        val top = rawBounds.top - scrollYPx
        val bottom = rawBounds.bottom - scrollYPx
        if (bottom <= 0 || top >= viewportHeightPx) return@forEachIndexed
        key("power-list-header-${header.stableKey}") {
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(rawBounds.left, top) }
                    .requiredSize(
                        width = with(density) { rawBounds.width.toDp() },
                        height = with(density) { rawBounds.height.toDp() }
                    )
                    .graphicsLayer {
                        scaleX = boundaryScale
                        scaleY = boundaryScale
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(header)
            }
        }
    }
}

@Composable
private fun ComposePowerListTransitionSectionHeaders(
    headers: List<PowerListSectionHeader>,
    sourceGeometry: ComposePowerListGeometry,
    targetGeometry: ComposePowerListGeometry,
    sourceScrollYPx: Int,
    targetScrollYPx: Int,
    viewportHeightPx: Int,
    progressProvider: () -> Float,
    content: @Composable (PowerListSectionHeader) -> Unit
) {
    val density = LocalDensity.current
    headers.forEachIndexed { index, header ->
        val source = sourceGeometry.headerBounds.getOrNull(index) ?: return@forEachIndexed
        val target = targetGeometry.headerBounds.getOrNull(index) ?: return@forEachIndexed
        if (source.width <= 0 || source.height <= 0 || target.width <= 0 || target.height <= 0) {
            return@forEachIndexed
        }
        key("power-list-transition-header-${header.stableKey}") {
            Box(
                modifier = Modifier
                    .offset {
                        val progress = progressProvider()
                        androidx.compose.ui.unit.IntOffset(
                            x = lerpIntLocal(source.left, target.left, progress),
                            y = lerpIntLocal(source.top - sourceScrollYPx, target.top - targetScrollYPx, progress)
                        )
                    }
                    .requiredSize(
                        width = with(density) { max(source.width, target.width).toDp() },
                        height = with(density) { max(source.height, target.height).toDp() }
                    )
                    .graphicsLayer {
                        val progress = progressProvider()
                        val top = lerpIntLocal(source.top - sourceScrollYPx, target.top - targetScrollYPx, progress)
                        alpha = if (top < viewportHeightPx && top + max(source.height, target.height) > 0) 1f else 0f
                    }
            ) {
                content(header)
            }
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
    val paddingTopPx: Int = 0,
    val itemBounds: List<IntRect> = emptyList(),
    val headerBounds: List<IntRect> = emptyList(),
    val contentHeightPx: Int = 0
)

private fun listGeometryFor(
    metrics: ComposePowerListMetrics,
    bottomPaddingPx: Int,
    topPaddingPx: Int = 0,
    itemCount: Int = 0,
    sectionHeaders: List<PowerListSectionHeader> = emptyList(),
    sectionHeaderHeightPx: Int = 0
): ComposePowerListGeometry {
    val columns = metrics.columns.coerceAtLeast(1)
    val availableWidth = metrics.cellWidthPx.coerceAtLeast(1) * columns
    val cellWidth = if (columns <= 1) availableWidth else availableWidth / columns
    val itemHeight = metrics.cellHeightPx.coerceAtLeast(1)
    val validHeaders = sectionHeaders
        .mapIndexedNotNull { originalIndex, header ->
            header.takeIf { it.beforeItemIndex in 0..itemCount }?.let { originalIndex to it }
        }
        .sortedWith(compareBy<Pair<Int, PowerListSectionHeader>> { it.second.beforeItemIndex }.thenBy { it.first })
    val headerBoundsByOriginalIndex = MutableList(sectionHeaders.size) { IntRect.Zero }
    val itemBounds = MutableList(itemCount.coerceAtLeast(0)) { IntRect.Zero }
    var y = topPaddingPx.coerceAtLeast(0)
    var column = 0
    var headerCursor = 0

    fun finishPartialRow() {
        if (column != 0) {
            y += itemHeight
            column = 0
        }
    }

    for (itemIndex in 0..itemCount) {
        while (headerCursor < validHeaders.size && validHeaders[headerCursor].second.beforeItemIndex == itemIndex) {
            finishPartialRow()
            val originalIndex = validHeaders[headerCursor].first
            val headerHeight = sectionHeaderHeightPx.coerceAtLeast(1)
            headerBoundsByOriginalIndex[originalIndex] = IntRect(0, y, availableWidth, y + headerHeight)
            y += headerHeight
            headerCursor++
        }
        if (itemIndex == itemCount) break
        val left = column * cellWidth
        itemBounds[itemIndex] = IntRect(left, y, left + cellWidth, y + itemHeight)
        column++
        if (column >= columns) {
            column = 0
            y += itemHeight
        }
    }
    finishPartialRow()
    val contentHeight = y + bottomPaddingPx.coerceAtLeast(0)

    return ComposePowerListGeometry(
        columns = columns,
        availableWidthPx = availableWidth,
        cellWidthPx = cellWidth,
        itemHeightPx = itemHeight,
        rowStridePx = itemHeight,
        rowSpacingPx = 0,
        bottomPaddingPx = bottomPaddingPx.coerceAtLeast(0),
        paddingTopPx = topPaddingPx.coerceAtLeast(0),
        itemBounds = itemBounds,
        headerBounds = headerBoundsByOriginalIndex,
        contentHeightPx = contentHeight
    )
}

private fun positionFor(
    index: Int,
    geometry: ComposePowerListGeometry,
    mode: ComposePowerListDisplayMode,
    scrollYPx: Int
): ComposeItemPosition {
    val raw = geometry.itemBounds.getOrNull(index) ?: IntRect.Zero
    val left = geometry.paddingLeftPx + raw.left
    val top = raw.top - scrollYPx
    val right = geometry.paddingLeftPx + raw.right
    val bottom = raw.bottom - scrollYPx
    return ComposeItemPosition(
        bounds = androidx.compose.ui.unit.IntRect(left, top, right, bottom),
        alpha = 1f,
        scaleX = 1f,
        scaleY = 1f,
        sceneId = mode.listLevel?.let { sceneIdForZoomIndex(it) } ?: PowerListSceneItem.SCENE_GRID
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
    topPaddingPx: Int = 0,
    sectionHeaders: List<PowerListSectionHeader> = emptyList(),
    sectionHeaderHeightPx: Int = 0
): Int {
    if (itemCount <= 0) return 0
    val geometry = listGeometryFor(
        metrics = metrics,
        bottomPaddingPx = bottomPaddingPx,
        topPaddingPx = topPaddingPx,
        itemCount = itemCount,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeightPx = sectionHeaderHeightPx
    )
    return maxScrollForGeometry(itemCount, geometry, viewportHeightPx)
}

private fun maxScrollForGeometry(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    viewportHeightPx: Int
): Int {
    if (itemCount <= 0) return 0
    return (geometry.contentHeightPx - viewportHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
}

private fun scrollYForIndex(
    index: Int,
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    viewportHeightPx: Int
): Int {
    if (itemCount <= 0) return 0
    val itemBounds = geometry.itemBounds[index.coerceIn(0, itemCount - 1)]
    val desiredTop = (viewportHeightPx / 2f - itemBounds.height / 2f).roundToInt().coerceAtLeast(0)
    val raw = itemBounds.top - desiredTop
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
    val sourceCenterY = sourceScrollY + viewportHeightPx.coerceAtLeast(0) / 2
    // FastLayout resolves the anchor from ordered child bounds. Do not scan the whole library
    // during every transition frame; a large library otherwise turns the first scene switch into
    // an O(n) main-thread pass before the animation can draw.
    val anchorPosition = nearestItemIndexForCenter(
        bounds = sourceGeometry.itemBounds,
        centerY = sourceCenterY,
        itemCount = itemCount
    )
    val sourceAnchor = sourceGeometry.itemBounds[anchorPosition]
    val targetAnchor = targetGeometry.itemBounds.getOrNull(anchorPosition) ?: sourceAnchor
    val sourceOffsetFraction = if (sourceAnchor.height > 0) {
        (sourceCenterY - sourceAnchor.top).toFloat() / sourceAnchor.height
    } else {
        0.5f
    }
    val targetCenterY = targetAnchor.top + (targetAnchor.height * sourceOffsetFraction).roundToInt()
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
    return boundsIntersectingRangeForScroll(
        itemCount = itemCount,
        geometry = geometry,
        scrollYPx = scrollYPx,
        viewportHeightPx = viewportHeightPx
    )
}

private fun boundsIntersectingRangeForScroll(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int
): IntRange {
    if (itemCount <= 0) return IntRange.EMPTY
    val viewportTop = 0
    val viewportBottom = viewportHeightPx.coerceAtLeast(0)
    if (viewportBottom <= viewportTop) return IntRange.EMPTY
    val bounds = geometry.itemBounds
    val count = itemCount.coerceAtMost(bounds.size)
    if (count <= 0) return IntRange.EMPTY

    // Item bounds are monotonically ordered by top (grid rows share the same top), so the
    // viewport window can be located without walking every song in the library.
    val worldTop = scrollYPx + viewportTop
    val worldBottom = scrollYPx + viewportBottom
    val first = lowerBound(count) { index -> bounds[index].bottom > worldTop }
    val lastExclusive = lowerBound(count) { index -> bounds[index].top >= worldBottom }
    return if (first >= lastExclusive) IntRange.EMPTY else first..(lastExclusive - 1)
}

private inline fun lowerBound(
    size: Int,
    predicate: (Int) -> Boolean
): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (predicate(middle)) {
            high = middle
        } else {
            low = middle + 1
        }
    }
    return low
}

private fun nearestItemIndexForCenter(
    bounds: List<IntRect>,
    centerY: Int,
    itemCount: Int
): Int {
    val count = itemCount.coerceAtMost(bounds.size)
    if (count <= 0) return 0
    val insertion = lowerBound(count) { index -> bounds[index].center.y >= centerY }
    val left = (insertion - 1).coerceAtLeast(0)
    val right = insertion.coerceAtMost(count - 1)
    return if (
        abs(bounds[left].center.y - centerY) <= abs(bounds[right].center.y - centerY)
    ) {
        left
    } else {
        right
    }
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
