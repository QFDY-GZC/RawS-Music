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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalBottomChromeScrollState
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkDisplayResolver
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.RawArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.shouldShowDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.FileArtworkId
import com.rawsmusic.core.ui.widget.bitmaps.PowerListArtworkRecords
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtwork
import com.rawsmusic.core.ui.widget.bitmaps.PowerListCoilArtworkModel
import com.rawsmusic.core.ui.widget.bitmaps.SizeSlotCache
import com.rawsmusic.core.ui.widget.player.copySongInfoToClipboard
import com.rawsmusic.module.data.prefs.FontManager
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
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
                    coverKey = revealSong?.coverKey.orEmpty()
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
        // Dense grids may keep loading artwork during a slow drag, but a continuous fling must
        // not repeatedly start cold file work. Each scroll delta closes the admission gate; a
        // short quiet window reopens it even if the finger is still resting on the screen.
        var artworkScrollSerial by remember { mutableIntStateOf(0) }
        var gridColdArtworkAllowed by remember { mutableStateOf(true) }
        LaunchedEffect(artworkScrollSerial) {
            val serial = artworkScrollSerial
            if (serial <= 0) return@LaunchedEffect
            delay(RawArtworkPolicy.VIEWPORT_SETTLE_MS)
            if (artworkScrollSerial == serial) {
                gridColdArtworkAllowed = true
            }
        }
        val scrollableState = rememberScrollableState { delta ->
            if (state.isTransitioning || state.isPinching || state.isBoundaryElasticActive) {
                delta
            } else {
                val old = state.viewportScrollY
                val newValue = (old - delta).coerceIn(0f, maxScrollY)
                if (newValue != old) {
                    gridColdArtworkAllowed = false
                    artworkScrollSerial++
                    bottomChromeScrollState?.onContentScroll(newValue - old)
                }
                state.viewportScrollY = newValue
                updateSettledBaseScroll(newValue.toInt())
                old - newValue
            }
        }
        // Alphabet/locator jumps update viewportScrollY without driving Compose's scrollableState.
        // Treat them as an active navigation gesture for a short settle window so 4-column grids
        // and list modes do not start decoding every intermediate target while the user is
        // dragging the side index.  Once no new jump arrives, the final visible window is armed
        // and the current cells request artwork immediately.
        var externalScrollGateSerial by remember { mutableIntStateOf(0) }
        LaunchedEffect(state.scrollToIndexRequestSerial) {
            val serial = state.scrollToIndexRequestSerial
            if (serial <= 0) return@LaunchedEffect
            externalScrollGateSerial = serial
            delay(POWER_LIST_EXTERNAL_SCROLL_SETTLE_DELAY_MS)
            if (externalScrollGateSerial == serial) {
                externalScrollGateSerial = 0
            }
        }
        // scrollToIndexRequestIndex is a sticky target marker, not an active gesture flag.  Treating
        // it as active keeps artwork decode suspended across cold start / alphabet jumps and leaves
        // the first visible grid empty for seconds.  The serial gate above is the real short-lived
        // activity window; once it clears, the final viewport may arm immediately.
        val externalScrollActive = externalScrollGateSerial != 0

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
                    interactionActive = scrollableState.isScrollInProgress || externalScrollActive || state.isPinching || state.isBoundaryElasticActive,
                    gridColdArtworkAllowed = gridColdArtworkAllowed && !externalScrollActive,
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
    interactionActive: Boolean,
    gridColdArtworkAllowed: Boolean,
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
        state = state,
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
        interactionActive = interactionActive,
        gridColdArtworkAllowed = gridColdArtworkAllowed,
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
    state: ComposePowerListState,
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
    interactionActive: Boolean,
    gridColdArtworkAllowed: Boolean,
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
    // Project-style album-art binding is split in two:
    // 1) renderRange keeps every actually visible item composed so covers never blink out at
    //    the bottom edge during a drag;
    // 2) the provider-visible art window now follows renderRange.  The previous bottom-obscured
    //    window deferred lower-edge cells, causing DISPLAY_EMPTY_NEW_ID until another scroll pass.
    val rawRenderRange = visibleRangeForScroll(
        itemCount = songs.size,
        mode = mode,
        geometry = geometry,
        scrollYPx = rangeScrollYPx.coerceAtLeast(0),
        viewportHeightPx = viewportHeightPx
    )
    val renderRange = capPowerListRange(rawRenderRange, POWER_LIST_MAX_RENDER_ITEMS)
    // Project-style visible binding: every currently rendered visible artwork cell
    // is allowed to ask the provider immediately.  Step25 restored the older bottom-obscured
    // artwork window, but that splits drawing from request admission: cells that are still visible
    // in renderRange but temporarily outside activeArtRange repeatedly paint placeholder, then
    // bitmap, then placeholder as the window advances.  That is the high-frequency shimmer/stutter
    // seen in dense grids.  The design does not defer a visible artwork view because it is near the
    // bottom edge; it binds each visible holder immediately.  Keep off-screen prefetch empty,
    // but make the visible artwork window equal to the rendered visible window.
    val activeArtRange = renderRange
    val decodeSuspendedForScroll = false
    val powerListCacheRevision = BitmapProvider.powerListCacheRevision
    val activeArtRevision = remember(
        activeArtRange.first,
        activeArtRange.last,
        powerListCacheRevision,
        mode,
        params,
        metrics
    ) {
        var hash = 17
        hash = hash * 31 + activeArtRange.first
        hash = hash * 31 + activeArtRange.last
        hash = hash * 31 + powerListCacheRevision.hashCode()
        hash = hash * 31 + mode.ordinal
        hash = hash * 31 + params.hashCode()
        hash
    }
    // Visible artwork requests are armed immediately.  The old settle gate made covers load only
    // after scrolling stopped; it also caused alpha restarts as the gate toggled.  We still keep
    // offscreen prewarm disabled, so this does not decode never-visible rows.
    val artworkSettled = !activeArtRange.isEmpty()
    val range = if (renderRange.isEmpty() || abs(boundaryScale - 1f) < 0.001f) {
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
    // Project-style settled holder pool. The previous step26 range-relative slot
    // (`index - range.first`) recreates every visible cell whenever the first visible row changes.
    // The new trace confirms that this turns dense-grid flings into callback-lost request waves:
    // many requests finish with waiters=0 and then immediately re-request the same 384px cache.
    // ComposeSlotPool is the public RawS-Music smooth baseline: the physical slot survives row
    // shifts, while the artwork cell state below atomically rebinds FileArtworkId/current wrapper.
    val settledSlotPool = remember(mode) { ComposeSlotPool() }
    settledSlotPool.beginFrame(
        firstVisibleIndex = range.first.coerceAtLeast(0),
        visibleCount = if (range.isEmpty()) 0 else range.last - range.first + 1
    )
    // Off-screen/covered art prefetch stays disabled.  Only item binding may load art.
    val prefetchRange = IntRange.EMPTY
    SideEffect {
        state.updateVisibleRangeForNavigation(renderRange)
    }
    // The provider must not follow the exact clipped render range. During a fling that range gains
    // and loses a partial row every few frames (24 <-> 28 keys in a 4-column grid), which advances
    // the provider generation, purges waiters, and requeues already-cached thumbnails. Keep drawing
    // exact, but admit artwork through a row-banded window that only shifts after several rows.
    val providerViewportRange = remember(
        songs.size,
        geometry,
        rangeScrollYPx,
        viewportHeightPx,
        mode
    ) {
        stableArtworkViewportRange(
            itemCount = songs.size,
            geometry = geometry,
            scrollYPx = rangeScrollYPx,
            viewportHeightPx = viewportHeightPx
        )
    }
    val viewportCacheKeys = if (POWER_LIST_USE_COIL_ARTWORK) {
        emptySet<String>()
    } else {
        remember(providerViewportRange, mode, params, geometry, densityValue, songs) {
            powerListViewportCacheKeys(
                songs = songs,
                range = providerViewportRange,
                mode = mode,
                params = params,
                geometry = geometry,
                // Cache-key generation only needs the cell size/bucket, not the current pixel scroll.
                // Feeding rangeScrollYPx here made the provider viewport churn on every fling frame,
                // purging waiters and restarting row effects. The design arms a stable visible window
                // before binding views; we mirror that by keeping keys stable until the visible index
                // range or layout bucket actually changes.
                scrollYPx = 0,
                density = densityValue
            )
        }
    }
    // Pre-arm the provider before child rows bind. Keep this as an immediate parent-side
    // provider update rather than a LaunchedEffect/SideEffect: rows below may attach requests in
    // the same composition pass, and the artwork provider knows the visible window before each
    // artwork view binds.  updatePowerListViewport() is internally cheap when the key set is
    // unchanged, so calling it here avoids the cold-start probe -> detach -> callback-lost race.
    val artworkViewportReady = !providerViewportRange.isEmpty()
    // Public RawS-Music / project-style pacing: do not make artwork-provider behavior depend on
    // the number of grid columns.  The previous dense-grid gate only affected 3/4-column modes and
    // made their visible cells compete with a different provider policy than every other layout,
    // which matches the remaining symptom: only dense grids flicker or hitch.  Visible cells are
    // still gated by viewportCacheKeys/row binding; the provider should decide queue priority from
    // request state, not from a hard-coded column threshold in the UI layer.
    if (POWER_LIST_USE_COIL_ARTWORK) {
        // Coil A/B branch owns list/grid request lifecycle. Keep the legacy provider viewport cold
        // so it cannot purge waiters, emit viewport traces, or enqueue a parallel request wave.
        BitmapProvider.updatePowerListViewport(emptySet(), active = false, suspendDecoding = true, allowIndexer = false)
    } else {
        BitmapProvider.updatePowerListViewport(
            viewportCacheKeys,
            active = artworkViewportReady,
            suspendDecoding = false,
            allowIndexer = artworkViewportReady
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            BitmapProvider.updatePowerListViewport(emptySet(), active = false)
        }
    }
    LaunchedEffect(
        prefetchRange.first,
        prefetchRange.last,
        activeArtRange.first,
        activeArtRange.last,
        mode,
        params,
        metrics,
        rangeScrollYPx,
        interactionActive
    ) {
        if (interactionActive || prefetchRange.isEmpty()) return@LaunchedEffect
        // Project-style viewport loading: keep off-screen work behind a short idle debounce.
        // Fast flings keep cancelling this effect, so we do not leave a long decode queue for
        // every item the user merely passed over.
        delay(POWER_LIST_PREFETCH_IDLE_DELAY_MS)
        prewarmSettledBitmaps(
            songs = songs,
            range = prefetchRange,
            visibleRange = activeArtRange,
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
            val compositionSlot = "settled-slot-${mode.name}-${settledSlotPool.slotIdFor(index)}"
            // This key must wrap the loop item itself, not only a child inside it. Otherwise
            // Compose matches the loop position first and disposes/recreates the artwork state whenever
            // a grid row crosses the viewport edge, despite the ring slot being stable.
            key(compositionSlot) {
            ComposePowerListTransitionItem(
                // Project-style physical holder slot. The holder persists across row-boundary
                // movement; artwork identity is bound inside the holder, not by tearing down the
                // whole cell when range.first advances. This prevents request detach/callback-lost
                // bursts in 3/4-column grids while keeping cross-id artwork leakage guarded by the
                // artwork cell's FileArtworkId/no-art gate.
                compositionSlot = compositionSlot,
                song = song,
                index = index,
                revealBaseIndex = range.first,
                mode = mode,
                params = params,
                position = position,
                // RenderRange keeps the visual cell alive; activeArtRange decides which visible
                // cells may start list artwork work right now. This separates drawing from decode
                // admission and prevents dense grids from launching an entire 64-item request wave.
                deferBitmapLoad = index !in activeArtRange || !artworkViewportReady,
                artBindRevision = activeArtRevision,
                scrollingArtworkUpdates = mode.isGrid && !gridColdArtworkAllowed,
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
    artBindRevision: Int = 0,
    scrollingArtworkUpdates: Boolean = false,
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
                artBindRevision = artBindRevision,
                scrollingArtworkUpdates = scrollingArtworkUpdates,
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
    artBindRevision: Int = 0,
    scrollingArtworkUpdates: Boolean = false,
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
    // Cached wrappers bind immediately in every mode. Cold source decoding is different: opening
    // several audio files while a dense grid is moving creates visible CPU/IO contention, so 3/4
    // column grids defer only those cache misses until the drag settles. This keeps scroll frames
    // stable without hiding already-prepared artwork.
    val effectiveDeferBitmapLoad = deferBitmapLoad
    val useCoilArtwork = POWER_LIST_USE_COIL_ARTWORK
    val bitmap = if (useCoilArtwork) {
        null
    } else {
        rememberPowerListBitmap(
            key = song.coverKey,
            albumAliasKey = "",
            targetWidth = coverSize,
            targetHeight = coverSize,
            // Visible artwork always joins the serial list queue. The provider already keeps this
            // queue ordered, so gating until a full gesture stop only turns a smooth stream of covers
            // into a simultaneous post-scroll burst.
            deferLoad = effectiveDeferBitmapLoad,
            artBindRevision = artBindRevision,
            index = index,
            modeLabel = mode.name
        )
    }
    val coverAlpha = remember { Animatable(1f) }

    // Equivalent artwork view cell state.
    // Keep this deliberately tiny: bound identity + current wrapper + optional previous wrapper for
    // same-id quality upgrades.  Do not let scroll state, viewport generation, or previously seen
    // sets participate in artwork visibility.  The important behavior is atomic wrapper
    // replacement: when the newly bound identity already has a provider record, draw it immediately;
    // only a real record miss may fall back to the placeholder.
    var aaBoundArtworkKey by remember { mutableStateOf("") }
    var aaCurrentHandle by remember { mutableStateOf<ArtworkHandle?>(null) }
    var aaPreviousHandle by remember { mutableStateOf<ArtworkHandle?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            aaPreviousHandle?.release()
            aaPreviousHandle = null
            aaCurrentHandle?.release()
            aaCurrentHandle = null
            aaBoundArtworkKey = ""
        }
    }

    val artworkIdentityKey = song.coverKey
    if (!useCoilArtwork) LaunchedEffect(bitmap, artworkIdentityKey, coverSize, mode.name) {
        val boundKey = artworkIdentityKey
        val currentBitmap = bitmap?.takeIf { !it.isRecycled }
        val existingHandle = aaCurrentHandle
        val previousHandle = existingHandle?.takeIf { it.isValid }
        val previousBitmap = previousHandle?.bitmap?.takeIf { !it.isRecycled }
        val sameIdentity = aaBoundArtworkKey.isNotBlank() && aaBoundArtworkKey == boundKey

        if (currentBitmap == null) {
            if (sameIdentity && previousBitmap != null) {
                // Same artwork identity is still waiting for a better wrapper or for a temporary request
                // callback.  Keep the accepted wrapper attached, matching the temporary
                // detach/rebind behavior, and never pulse alpha back to zero.
                aaPreviousHandle?.release()
                aaPreviousHandle = null
                coverAlpha.snapTo(1f)
                powerListArtLog(
                    "AA_CELL_KEEP_CURRENT index=$index mode=$mode size=$coverSize key=${boundKey.tailForLog()}"
                )
            } else {
                // New identity genuinely has no wrapper after the synchronous record probe.  Only
                // now do we clear the old artwork.  This is the important difference from step17:
                // old -> new is atomic when the new record exists; old -> placeholder happens only
                // on record miss, not before the record lookup.
                aaPreviousHandle?.release()
                aaPreviousHandle = null
                aaCurrentHandle?.release()
                aaCurrentHandle = null
                aaBoundArtworkKey = boundKey
                coverAlpha.snapTo(1f)
                powerListArtLog(
                    "AA_CELL_MISS_PLACEHOLDER index=$index mode=$mode size=$coverSize defer=$effectiveDeferBitmapLoad key=${boundKey.tailForLog()}"
                )
            }
            return@LaunchedEffect
        }

        val alreadyCurrent = sameIdentity && previousBitmap === currentBitmap && previousHandle != null
        if (alreadyCurrent) {
            aaPreviousHandle?.release()
            aaPreviousHandle = null
            coverAlpha.snapTo(1f)
            powerListArtLog(
                "AA_CELL_READY_KEEP index=$index mode=$mode size=$coverSize bitmap=${currentBitmap.width}x${currentBitmap.height} key=${boundKey.tailForLog()}"
            )
            return@LaunchedEffect
        }

        val nextHandle = BitmapProvider.acquireLoaded(
            key = boundKey,
            bitmap = currentBitmap,
            targetWidth = coverSize,
            targetHeight = coverSize,
            surface = ArtworkSurface.List
        )
        if (nextHandle == null || !nextHandle.isValid) {
            nextHandle?.release()
            if (!sameIdentity) {
                aaPreviousHandle?.release()
                aaPreviousHandle = null
                aaCurrentHandle?.release()
                aaCurrentHandle = null
                aaBoundArtworkKey = boundKey
                coverAlpha.snapTo(1f)
                powerListArtLog(
                    "AA_CELL_HANDLE_MISS_PLACEHOLDER index=$index mode=$mode size=$coverSize key=${boundKey.tailForLog()}"
                )
            }
            return@LaunchedEffect
        }

        val sameIdQualityUpgrade = sameIdentity &&
            previousHandle != null &&
            previousBitmap != null &&
            previousBitmap !== currentBitmap &&
            !mode.isGrid

        aaBoundArtworkKey = boundKey
        if (sameIdQualityUpgrade) {
            aaPreviousHandle?.release()
            aaPreviousHandle = previousHandle
            aaCurrentHandle = nextHandle
            coverAlpha.snapTo(0f)
            powerListArtLog(
                "AA_CELL_UPGRADE_FADE index=$index mode=$mode size=$coverSize bitmap=${currentBitmap.width}x${currentBitmap.height} key=${boundKey.tailForLog()}"
            )
            val duration = ArtworkDisplayResolver.listFadeDurationMillis(
                modeLabel = mode.name,
                firstBitmap = false,
                cacheHitAlreadyVisible = false
            ).coerceAtMost(RawArtworkPolicy.VIEW_FADE_MS)
            if (duration > 0) {
                coverAlpha.animateTo(1f, tween(durationMillis = duration))
            } else {
                coverAlpha.snapTo(1f)
            }
            aaPreviousHandle?.release()
            aaPreviousHandle = null
        } else {
            // Cache hits during a drag stay direct.  A cold grid cover accepted after the drag
            // settles fades over the placeholder once, in visible order; this preserves motion
            // smoothness while avoiding a whole-screen re-fade of artwork that was already drawn.
            aaPreviousHandle?.release()
            aaPreviousHandle = null
            if (existingHandle !== nextHandle) {
                existingHandle?.release()
            }
            // Keep loading while the grid moves, but do not start a 200ms reveal that a following
            // slot rebind will cancel halfway through. During continuous motion a ready bitmap is
            // shown immediately; once the scroll has been quiet long enough, new covers reveal.
            val revealGridArtwork = mode.isGrid &&
                !scrollingArtworkUpdates &&
                (!sameIdentity || previousBitmap == null)
            if (revealGridArtwork) {
                val relativeIndex = (index - revealBaseIndex).coerceAtLeast(0)
                val row = relativeIndex / mode.columns.coerceAtLeast(1)
                val column = relativeIndex % mode.columns.coerceAtLeast(1)
                val stagger = ArtworkDisplayResolver.listRevealStaggerMillis(mode.name)
                val revealDelay = (row * stagger * 2 + column * stagger)
                    .coerceAtMost(POWER_LIST_GRID_REVEAL_MAX_DELAY_MS)
                // Set alpha before publishing the new wrapper. Publishing first leaves one
                // composition frame at alpha=1, which appears as a flash before the fade starts.
                coverAlpha.snapTo(0f)
                aaCurrentHandle = nextHandle
                if (revealDelay > 0) delay(revealDelay.toLong())
                coverAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = RawArtworkPolicy.VIEW_FADE_MS)
                )
            } else {
                aaCurrentHandle = nextHandle
                coverAlpha.snapTo(1f)
            }
            powerListArtLog(
                "AA_CELL_READY_DIRECT index=$index mode=$mode size=$coverSize bitmap=${currentBitmap.width}x${currentBitmap.height} key=${boundKey.tailForLog()}"
            )
        }
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
            coverKey = song.coverKey
        )
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                rootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .then(if (isPlaying) Modifier.trackDrawnCoverBounds(rects.cover, onCoverBoundsChanged) else Modifier)
            .then(if (isPlaying) Modifier.trackDrawnCoverTarget(rects.cover, rects.coverRadiusDp, song.id, song.coverKey, -1, onCoverTargetChanged) else Modifier)
            .trackSharedCoverSlot(
                sceneId = sharedCoverSceneId,
                elementId = sharedCoverElementId,
                cover = rects.cover,
                radiusDp = rects.coverRadiusDp,
                coverKey = song.coverKey
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
            // Equivalent draw gate: once the physical cell is rebound to a new
            // artwork identity, the old wrapper must not be drawn for that new item.  Step18
            // correctly made record hits old->new atomic, but the Canvas fallback still used
            // aaCurrentHandle even before the LaunchedEffect cleared it on a record miss/no-art
            // item.  That produced one-frame flashes where no-art songs briefly showed the
            // previous row's cover.  Only use the retained artwork wrapper when it belongs to the
            // currently bound song; otherwise draw the new record bitmap if it was synchronously
            // supplied, or the stable placeholder on a genuine miss.
            val drawKey = artworkIdentityKey
            val retainedCurrent = aaCurrentHandle
                ?.takeIf { aaBoundArtworkKey == drawKey && it.isValid }
                ?.bitmap
                ?.takeIf { !it.isRecycled }
            val retainedPrevious = aaPreviousHandle
                ?.takeIf { aaBoundArtworkKey == drawKey && it.isValid }
                ?.bitmap
                ?.takeIf { !it.isRecycled }
            val drawableBitmap = if (useCoilArtwork) null else bitmap?.takeIf { !it.isRecycled } ?: retainedCurrent
            val underlayBitmap = retainedPrevious
                ?.takeIf { drawableBitmap != null && it !== drawableBitmap }
            if (!hideCover && !shouldHideForSharedCover && drawableBitmap != null) {
                if (underlayBitmap != null) {
                    powerListBitmapMatrix.reset()
                    configureCenterCropMatrix(
                        matrix = powerListBitmapMatrix,
                        bitmap = underlayBitmap,
                        left = coverLeft,
                        top = coverTop,
                        width = cover.width.toFloat(),
                        height = cover.height.toFloat()
                    )
                    powerListBitmapPaint.alpha = 255
                    canvas.drawBitmap(underlayBitmap, powerListBitmapMatrix, powerListBitmapPaint)
                }
                powerListBitmapMatrix.reset()
                configureCenterCropMatrix(
                    matrix = powerListBitmapMatrix,
                    bitmap = drawableBitmap,
                    left = coverLeft,
                    top = coverTop,
                    width = cover.width.toFloat(),
                    height = cover.height.toFloat()
                )
                val alpha = coverAlpha.value.coerceIn(0f, 1f)
                powerListBitmapPaint.alpha = (alpha * 255f).roundToInt()
                canvas.drawBitmap(drawableBitmap, powerListBitmapMatrix, powerListBitmapPaint)
                powerListBitmapPaint.alpha = 255
            }
            canvas.restore()

            drawPowerListText(
                canvas = canvas,
                text = title,
                rect = rects.title,
                color = titleColor,
                density = density.density * density.fontScale,
                bold = true
            )
            drawPowerListText(
                canvas = canvas,
                text = subtitle,
                rect = rects.subtitle,
                color = subtitleColor,
                density = density.density * density.fontScale,
                bold = false
            )
            drawPowerListText(
                canvas = canvas,
                text = meta,
                rect = rects.meta,
                color = metaColor,
                density = density.density * density.fontScale,
                bold = false,
                leftInsetPx = if (hasCollectionMetaIcon) 16f * density.density else 0f
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

        if (!useCoilArtwork && shouldShowDefaultAlbumArtwork(song.coverKey, coverSize, coverSize) &&
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
    val model = remember(coverKey, decodeSide, modeLabel, defaultArtworkEnabled) {
        PowerListCoilArtworkModel(
            coverKey = coverKey,
            targetSide = decodeSide,
            modeLabel = modeLabel,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled
        )
    }
    val request = remember(context, model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(model.side, model.side)
            .memoryCacheKey(model.cacheKey)
            .diskCacheKey(model.cacheKey)
            .allowHardware(BitmapProvider.useHardwareBitmap)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .networkCachePolicy(coil.request.CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }
    val fallbackBitmap = remember(model.cacheKey) {
        BitmapProvider.peekPowerListFallbackThumbnail(
            key = model.id.value,
            targetWidth = model.side,
            targetHeight = model.side
        )
    }?.takeIf { !it.isRecycled }
    val painter = rememberAsyncImagePainter(
        model = request,
        imageLoader = imageLoader
    )
    val imageAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (painter.state is coil.compose.AsyncImagePainter.State.Success) 1f else 0f,
        animationSpec = tween(durationMillis = RawArtworkPolicy.VIEW_FADE_MS),
        label = "powerlist_coil_artwork_fade"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radiusDp.dp))
            .fillMaxSize()
    ) {
        if (fallbackBitmap != null) {
            PowerListBitmapCanvas(
                bitmap = fallbackBitmap,
                placeholderColor = Color.Transparent,
                modifier = Modifier.fillMaxSize()
            )
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = imageAlpha }
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
@Suppress("UNUSED_PARAMETER")
private fun rememberPowerListBitmap(
    key: String,
    albumAliasKey: String = "",
    targetWidth: Int,
    targetHeight: Int,
    deferLoad: Boolean = false,
    artBindRevision: Int = 0,
    index: Int = -1,
    modeLabel: String = ""
): Bitmap? {
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
    val recentFailure = key.isNotBlank() && BitmapProvider.hasRecentThumbnailFailure(
        key = id.value,
        targetWidth = decodeWidth,
        targetHeight = decodeHeight,
        providerAliasKey = ""
    )
    val handleState = remember(id.value, decodeWidth, decodeHeight) {
        mutableStateOf(
            PowerListArtworkRecords.acquire(
                id = id,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                surface = ArtworkSurface.List,
                providerAliasKey = albumAliasKey
            )?.also {
                powerListArtLog(
                    "AA_RECORD_HIT index=$index mode=$modeLabel bucket=$bucket bitmap=${it.bitmap.width}x${it.bitmap.height} id=${id.value.tailForLog()}"
                )
            }
        )
    }

    DisposableEffect(id.value, decodeWidth, decodeHeight) {
        onDispose {
            replacePowerListHandle(handleState, null)
        }
    }

    LaunchedEffect(id.value, decodeWidth, decodeHeight, recentFailure, deferLoad, modeLabel, artBindRevision) {
        if (id.isBlank) {
            powerListArtLog("AA_SKIP_BLANK index=$index mode=$modeLabel")
            replacePowerListHandle(handleState, null)
            return@LaunchedEffect
        }
        if (recentFailure) {
            powerListArtLog("AA_NOT_FOUND_CLEAR index=$index mode=$modeLabel id=${id.value.tailForLog()}")
            PowerListArtworkRecords.markNotFound(id)
            replacePowerListHandle(handleState, null)
            return@LaunchedEffect
        }
        powerListArtLog(
            "AA_VISIBLE index=$index mode=$modeLabel target=${targetWidth}x${targetHeight} decode=${decodeWidth}x${decodeHeight} defer=$deferLoad id=${id.value.tailForLog()}"
        )
        PowerListArtworkRecords.acquire(
            id = id,
            targetWidth = decodeWidth,
            targetHeight = decodeHeight,
            surface = ArtworkSurface.List,
            providerAliasKey = albumAliasKey
        )?.let { cached ->
            powerListArtLog(
                "AA_RECORD_EFFECT index=$index mode=$modeLabel bucket=$bucket bitmap=${cached.bitmap.width}x${cached.bitmap.height} id=${id.value.tailForLog()}"
            )
            replacePowerListHandle(handleState, cached)
            return@LaunchedEffect
        }
        if (deferLoad) {
            powerListArtLog(
                "AA_REQUEST_DEFER index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} id=${id.value.tailForLog()}"
            )
            return@LaunchedEffect
        }
    }

    val hasFinalBitmap = handleState.value?.let { handle ->
        handle.isValid && isPowerListBitmapAcceptable(handle.bitmap, decodeWidth, decodeHeight, modeLabel)
    } == true

    DisposableEffect(id.value, decodeWidth, decodeHeight, recentFailure, hasFinalBitmap, deferLoad, modeLabel) {
        if (id.isBlank || recentFailure || hasFinalBitmap || deferLoad) {
            if (deferLoad && !hasFinalBitmap && !id.isBlank && !recentFailure) {
                powerListArtLog(
                    "AA_REQUEST_DEFER_NO_ASYNC index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} id=${id.value.tailForLog()}"
                )
            }
            onDispose { }
        } else {
            val seq = powerListArtSeq.incrementAndGet()
            powerListArtLog(
                "AA_REQUEST_LOAD seq=$seq index=$index mode=$modeLabel decode=${decodeWidth}x${decodeHeight} id=${id.value.tailForLog()}"
            )
            val request = BitmapProvider.loadViewportThumbnail(
                key = id.value,
                targetWidth = decodeWidth,
                targetHeight = decodeHeight,
                priority = BitmapRequest.Priority.LOADING_LIST,
                providerAliasKey = albumAliasKey
            ) { loaded ->
                if (loaded != null && !loaded.isRecycled) {
                    powerListArtLog(
                        "AA_REQUEST_CALLBACK seq=$seq index=$index mode=$modeLabel bitmap=${loaded.width}x${loaded.height} id=${id.value.tailForLog()}"
                    )
                    val handle = PowerListArtworkRecords.publishBitmap(
                        id = id,
                        bitmap = loaded,
                        targetWidth = decodeWidth,
                        targetHeight = decodeHeight,
                        high = false
                    )
                    replacePowerListHandle(handleState, handle)
                } else {
                    powerListArtLog(
                        "AA_REQUEST_CALLBACK_NOT_FOUND seq=$seq index=$index mode=$modeLabel id=${id.value.tailForLog()}"
                    )
                    PowerListArtworkRecords.markNotFound(id)
                    replacePowerListHandle(handleState, null)
                }
            }
            onDispose {
                powerListArtLog(
                    "AA_REQUEST_DETACH seq=$seq index=$index mode=$modeLabel cancelled=${request.isCancelled} id=${id.value.tailForLog()}"
                )
                BitmapProvider.cancel(request, keepDecoding = true)
            }
        }
    }

    return handleState.value?.takeIf { it.isValid }?.bitmap?.takeIf { !it.isRecycled }
}

private fun powerListAlbumAliasKey(song: AudioFile): String {
    // The provider shares artwork through a media-database album identity. MediaStore's positive
    // albumId gives us the same safe equivalence without guessing from loose-file names. Do not
    // fall back to album text here: compilations and missing tags can otherwise show wrong art.
    return song.albumId.takeIf { it > 0L }?.let { "album-id:$it" }.orEmpty()
}

private fun isPowerListAlbumAliasAlbumUsable(album: String): Boolean {
    if (album.isBlank()) return false
    val normalized = album.trim().lowercase()
    return normalized != "unknown album" &&
        normalized != "unknown" &&
        normalized != "<unknown>" &&
        normalized != "untitled" &&
        normalized != "no album"
}

// PowerList visible album-art decode cap. Keep large grid/hero covers crisp without touching scanner metadata paths.
private const val POWER_LIST_COVER_DECODE_MAX = 1024
private const val POWER_LIST_LIST_SMALL_DECODE = 192
private const val POWER_LIST_LIST_NORMAL_DECODE = 384
private const val POWER_LIST_LIST_ZOOMED_DECODE = 512
private const val POWER_LIST_GRID_4_DECODE = 384
private const val POWER_LIST_GRID_3_DECODE = 512
private const val POWER_LIST_GRID_2_DECODE = 784
private const val POWER_LIST_MAX_RENDER_ITEMS = 64
private const val POWER_LIST_MAX_ART_ITEMS = POWER_LIST_MAX_RENDER_ITEMS
private const val POWER_LIST_GRID_REVEAL_MAX_DELAY_MS = 180
private const val POWER_LIST_VIEWPORT_BAND_ROWS = 3
private const val POWER_LIST_VIEWPORT_LEADING_ROWS = 1
private const val POWER_LIST_VIEWPORT_TRAILING_ROWS = 2
// 1024px covers are much heavier than the old 256px cap. Match design spec: visible first,
// then only a tiny idle prefetch window, not every item passed during a fling.
private const val POWER_LIST_PREFETCH_MAX_ITEMS = 0

// Keep only the project-style provider/record state for PowerList artwork.
// Older Compose-side reveal/stale-retain/memory-owner helpers were intentionally removed here:
// step14+ uses PowerListArtworkRecords as the single wrapper store, and extra UI caches can
// reintroduce stale/no-art flashes that the design avoids by binding the artwork view directly to P.
private val POWER_LIST_PREFETCH_IDLE_DELAY_MS = RawArtworkPolicy.OFFSCREEN_PREWARM_IDLE_MS
private const val POWER_LIST_EXTERNAL_SCROLL_SETTLE_DELAY_MS = 180L
// Experimental A/B branch: list/grid covers are painted by Coil while BitmapProvider remains the
// RawSMusic-specific decoder/cache backend. Flip to false to restore the legacy artwork record path.
private const val POWER_LIST_USE_COIL_ARTWORK = true
private const val POWER_LIST_TRACE_ART = false
private val powerListArtSeq = AtomicLong(0L)

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
    targetHeight: Int,
    modeLabel: String
): Boolean {
    if (bitmap.isRecycled) return false
    val requestedSide = max(targetWidth, targetHeight).coerceAtLeast(1)
    if (requestedSide <= 160) return true
    val ratio = when (modeLabel) {
        ComposePowerListDisplayMode.GRID_4.name,
        ComposePowerListDisplayMode.GRID_3.name,
        ComposePowerListDisplayMode.GRID_2.name -> 0.86f
        "TRANSITION" -> 0.72f
        else -> 0.64f
    }
    val minSide = (requestedSide * ratio).roundToInt().coerceAtLeast(1)
    return bitmap.width >= minSide && bitmap.height >= minSide
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
        // Compose state just because the ref wrapper changed; the design keeps the same P wrapper
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

        onGloballyPositioned { coordinates ->
            if (!shouldTrack) return@onGloballyPositioned
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
    bold: Boolean,
    leftInsetPx: Float = 0f
) {
    if (text.isBlank() || rect.alpha <= 0f || rect.width <= 0 || rect.height <= 0) return
    val fontSizeSp = rect.fontSizeSp.takeIf { it > 0f } ?: 14f
    powerListTextPaint.color = color.copy(alpha = color.alpha * rect.alpha.coerceIn(0f, 1f)).toArgb()
    powerListTextPaint.textSize = fontSizeSp * density
    val configuredTypeface = FontManager.typeface
    powerListTextPaint.typeface = when {
        configuredTypeface == null -> if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        bold -> Typeface.create(configuredTypeface, Typeface.BOLD)
        else -> configuredTypeface
    }
    val availableWidth = (rect.width.toFloat() - leftInsetPx).coerceAtLeast(1f)
    val display = TextUtils.ellipsize(
        text,
        powerListTextPaint,
        availableWidth,
        TextUtils.TruncateAt.END
    )
    val fontMetrics = powerListTextPaint.fontMetrics
    val baseline = rect.top + (rect.height - fontMetrics.ascent - fontMetrics.descent) * 0.5f
    canvas.drawText(display.toString(), rect.left.toFloat() + leftInsetPx, baseline, powerListTextPaint)
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
                targetSide = coverSize,
                modeLabel = "TRANSITION",
                radiusDp = 0f,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val bitmap = rememberPowerListBitmap(
                key = song.coverKey,
                albumAliasKey = "",
                targetWidth = coverSize,
                targetHeight = coverSize,
                index = index,
                modeLabel = "TRANSITION"
            )
            PowerListBitmapCanvas(
                bitmap = bitmap,
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


private fun capPowerListRange(range: IntRange, maxItems: Int): IntRange {
    if (range.isEmpty() || maxItems <= 0) return IntRange.EMPTY
    val cappedLast = (range.first + maxItems - 1).coerceAtMost(range.last)
    return range.first..cappedLast
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
        val targetKey = item.song.coverKey
        if (targetKey.isNotBlank()) {
            if (BitmapProvider.peekThumbnail(targetKey, targetSize, targetSize) == null) {
                BitmapProvider.loadPrefetchThumbnail(targetKey, targetSize, targetSize)
            }
            if (sourceSize != targetSize && BitmapProvider.peekThumbnail(targetKey, sourceSize, sourceSize) == null) {
                BitmapProvider.loadPrefetchThumbnail(targetKey, sourceSize, sourceSize)
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

private fun powerListViewportCacheKeys(
    songs: List<AudioFile>,
    range: IntRange,
    mode: ComposePowerListDisplayMode,
    params: ListZoomParams,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    density: Float
): Set<String> {
    if (range.isEmpty()) return emptySet()
    val keys = LinkedHashSet<String>()
    for (index in range.first..range.last) {
        val song = songs.getOrNull(index) ?: continue
        val key = song.coverKey
        if (key.isBlank()) continue
        val position = positionFor(
            index = index,
            geometry = geometry,
            mode = mode,
            scrollYPx = scrollYPx
        )
        val rects = composeAAItemSceneRects(position, mode, params, density)
        val size = powerListDecodeSideForMode(
            requestedSide = max(rects.cover.width, rects.cover.height).coerceAtLeast(1),
            modeLabel = mode.name
        )
        keys += BitmapProvider.thumbnailCacheKey(
            key = key,
            targetWidth = size,
            targetHeight = size,
            providerAliasKey = ""
        )
    }
    return keys
}

/**
 * Rendering follows the exact visible intersection. Provider admission advances in row bands so
 * a partial row entering/leaving during a fling does not purge and restart the same requests.
 */
private fun stableArtworkViewportRange(
    itemCount: Int,
    geometry: ComposePowerListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int
): IntRange {
    if (itemCount <= 0 || viewportHeightPx <= 0) return IntRange.EMPTY
    val columns = geometry.columns.coerceAtLeast(1)
    val rowStride = geometry.rowStridePx.coerceAtLeast(1)
    val rowCount = (itemCount + columns - 1) / columns
    val firstVisibleRow = (scrollYPx.coerceAtLeast(0) / rowStride)
        .coerceIn(0, (rowCount - 1).coerceAtLeast(0))
    val visibleRows = ((viewportHeightPx + rowStride - 1) / rowStride).coerceAtLeast(1)
    val bandStartRow = (firstVisibleRow / POWER_LIST_VIEWPORT_BAND_ROWS) * POWER_LIST_VIEWPORT_BAND_ROWS
    val firstRow = (bandStartRow - POWER_LIST_VIEWPORT_LEADING_ROWS).coerceAtLeast(0)
    val lastRow = (bandStartRow + visibleRows + POWER_LIST_VIEWPORT_TRAILING_ROWS - 1)
        .coerceAtMost(rowCount - 1)
    val first = (firstRow * columns).coerceIn(0, itemCount - 1)
    val last = (lastRow * columns + columns - 1).coerceIn(first, itemCount - 1)
    return first..last
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
        val key = song.coverKey
        if (key.isBlank()) continue
        val position = positionFor(
            index = index,
            geometry = geometry,
            mode = mode,
            scrollYPx = scrollYPx
        )
        val rects = composeAAItemSceneRects(position, mode, params, density)
        val size = powerListDecodeSideForMode(
            requestedSide = max(rects.cover.width, rects.cover.height).coerceAtLeast(1),
            modeLabel = mode.name
        )
        if (BitmapProvider.peekThumbnail(key, size, size) == null) {
            BitmapProvider.loadPrefetchThumbnail(key, size, size)
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
    val desiredTop = (viewportHeightPx / 2f - geometry.itemHeightPx / 2f).roundToInt().coerceAtLeast(0)
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
