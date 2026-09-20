package com.rawsmusic.core.ui.widget.virtuallist

import com.rawsmusic.core.ui.scene.NavScene

import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.perf.TransitionPerfEvent
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.core.ui.scene.BottomChromeLayoutClass
import com.rawsmusic.core.ui.scene.LocalBottomChromeEnvironment
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalBottomChromeScrollState
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryLayoutFrame
import com.rawsmusic.core.ui.scene.LocalExternalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.scene.LocalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.LocalReferenceRetainedLibraryPopulation
import com.rawsmusic.core.ui.scene.LocalRetainedSceneMotionActive
import com.rawsmusic.core.ui.scene.LocalRetainedScenePreflightOwnership
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.scene.virtualListSceneTransitionItem
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.core.ui.widget.player.copySongInfoToClipboard
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.core.ui.widget.text.resolveNativeTextVerticalLayout
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val LocalSelectionCheckAlphaProvider = staticCompositionLocalOf<() -> Float> { { 0f } }

@Composable
fun rememberComposeVirtualListState(namespace: String = "default"): ComposeVirtualListState {
    val context = LocalContext.current
    return remember(context, namespace) {
        ComposeVirtualListState.fromContext(context, namespace)
    }
}

internal fun resolveSettledScrollForGeometry(
    rawScrollY: Int,
    maxScrollY: Int,
    preserveRetainedScroll: Boolean,
): Int {
    val nonNegative = rawScrollY.coerceAtLeast(0)
    return if (preserveRetainedScroll) {
        nonNegative
    } else {
        nonNegative.coerceAtMost(maxScrollY.coerceAtLeast(0))
    }
}

internal fun virtualListGeometrySignature(
    itemCount: Int,
    columns: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    sectionHeaderHeightPx: Int,
    sectionHeaders: List<VirtualListSectionHeader>,
    customItemHeightsPx: IntArray?,
): Long {
    // Only inputs which can change the logical content extent belong here. maxScroll itself is
    // deliberately excluded: while a parked provider is being rebound it can transiently report
    // zero even though the underlying layout is unchanged.
    var hash = 0x6A09E667F3BCC909L
    fun mix(value: Int) {
        hash = (hash xor value.toLong()) * 0x100000001B3L
    }

    mix(itemCount)
    mix(columns)
    mix(cellWidthPx)
    mix(cellHeightPx)
    mix(topPaddingPx)
    mix(bottomPaddingPx)
    mix(sectionHeaderHeightPx)
    mix(sectionHeaders.size)
    sectionHeaders.forEach { header -> mix(header.beforeItemIndex) }
    if (customItemHeightsPx == null) {
        mix(-1)
    } else {
        mix(customItemHeightsPx.size)
        customItemHeightsPx.forEach(::mix)
    }
    return hash
}

@Composable
fun ComposeVirtualList(
    songs: List<AudioFile>,
    state: ComposeVirtualListState,
    modifier: Modifier = Modifier,
    playingSongId: Long = -1L,
    currentPlayingIndex: Int = -1,
    selectedPositions: Set<Int> = emptySet(),
    revealIndexRequest: Int = -1,
    hidePlayingCover: Boolean = false,
    contentTopPadding: Dp = 0.dp,
    presentationTopClipInset: Dp? = null,
    persistentHeaderHeight: Dp = 0.dp,
    persistentHeaderVisibilityHeight: Dp = persistentHeaderHeight,
    persistentHeaderSceneItemVisibilityHeight: Dp = persistentHeaderVisibilityHeight,
    persistentHeaderSceneItemId: String = "",
    virtualListEdgeEnabled: Boolean = false,
    persistentHeaderContent: @Composable (headerVisible: Boolean, sceneItemVisible: Boolean) -> Unit = { _, _ -> },
    contentBottomPadding: Dp? = null,
    sectionHeaders: List<VirtualListSectionHeader> = emptyList(),
    sectionHeaderHeight: Dp = 54.dp,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit = {},
    pinchEnabled: Boolean = true,
    sharedCoverSceneId: String = "",
    sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit = { _, _, _ -> },
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onScrollActiveChanged: (Boolean) -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    physicalPresentationExternalTransform: RetainedSceneItemTransform? = null,
    physicalPresentationVisibleProvider: () -> Boolean = { true },
    onOuterPopulationPublished: (() -> Unit)? = null,
) {
    val providerRegistry = LocalReferenceLibraryProviderRegistry.current
    val providerScene = LocalReferenceLibraryProviderScene.current
    val providerIdentity = LocalReferenceLibraryProviderIdentity.current
    val bodyRender = LocalReferenceLibraryBodyRender.current
    val currentPhysicalProviderIdentity: Any? =
        if (bodyRender) providerRegistry?.get(providerScene)?.physicalPoolIdentity ?: providerScene else null
    val providerPublicationOnly = LocalReferenceLibraryProviderPublicationOnly.current
    val retainedPublishedProvider = LocalReferenceRetainedVirtualListProvider.current
    val publishedCustomProvider = LocalVirtualListCustomProvider.current
    // A page transition is not the list's pinch/mode-change session. Freeze its provider inputs
    // independently, including the publication-only controller, so a repository emission cannot
    // replace the CURRENT/RETAINED union halfway through GenericPivot. Internal folder navigation
    // must still publish its new provider and is deliberately excluded here.
    val outerOwnsInputs = !LocalVirtualListProviderNavigationFrame.current.active &&
        (LocalRetainedSceneMotionActive.current || LocalRetainedScenePreflightOwnership.current)
    val songInputs = remember(state, providerScene, providerIdentity) {
        VirtualListTransitionInputs<List<AudioFile>>()
    }
    val sectionInputs = remember(state, providerScene, providerIdentity) {
        VirtualListTransitionInputs<List<VirtualListSectionHeader>>()
    }
    val songs = songInputs.resolve(outerOwnsInputs, songs)
    val sectionHeaders = sectionInputs.resolve(outerOwnsInputs, sectionHeaders)
    val resolvedPresentationTopClipInset =
        presentationTopClipInset ?: LocalVirtualListPresentationTopClipInset.current
    if (providerRegistry != null && providerScene != null && !bodyRender) {
        // The provider identity belongs to the persistent registry. Recreating it with the page
        // composition made every HOME -> category visit look like a brand-new reference player provider object,
        // which in turn allocated a new resident holder bank and invalidated endpoint prewarm.
        val published = providerRegistry.getOrCreate(providerScene)
        published.providerNavigationActive = LocalVirtualListProviderNavigationFrame.current.active
        val structuralPublicationChanged =
            published.providerIdentity != providerIdentity ||
                published.songs !== songs ||
                published.state !== state ||
                published.contentTopPadding != contentTopPadding ||
                published.presentationTopClipInset != resolvedPresentationTopClipInset ||
                published.persistentHeaderHeight != persistentHeaderHeight ||
                published.persistentHeaderVisibilityHeight != persistentHeaderVisibilityHeight ||
                published.persistentHeaderSceneItemVisibilityHeight != persistentHeaderSceneItemVisibilityHeight ||
                published.contentBottomPadding != contentBottomPadding ||
                published.sectionHeaders !== sectionHeaders ||
                published.sectionHeaderHeight != sectionHeaderHeight ||
                published.customProvider !== publishedCustomProvider
        val behaviorPublicationChanged =
            published.playingSongId != playingSongId ||
                published.currentPlayingIndex != currentPlayingIndex ||
                published.selectedPositions !== selectedPositions ||
                published.revealIndexRequest != revealIndexRequest ||
                published.hidePlayingCover != hidePlayingCover ||
                published.persistentHeaderSceneItemId != persistentHeaderSceneItemId ||
                published.virtualListEdgeEnabled != virtualListEdgeEnabled ||
                published.pinchEnabled != pinchEnabled ||
                published.sharedCoverSceneId != sharedCoverSceneId ||
                published.persistentHeaderContent !== persistentHeaderContent ||
                published.sectionHeaderContent !== sectionHeaderContent ||
                published.sharedCoverElementIdProvider !== sharedCoverElementIdProvider ||
                published.onPlayingCoverBoundsChanged !== onPlayingCoverBoundsChanged ||
                published.onPlayingCoverTargetChanged !== onPlayingCoverTargetChanged ||
                published.onSharedCoverTargetChanged !== onSharedCoverTargetChanged ||
                published.onRevealCoverTargetResolved !== onRevealCoverTargetResolved ||
                published.onScrollActiveChanged !== onScrollActiveChanged ||
                published.onSongClick !== onSongClick ||
                published.onSongLongClick !== onSongLongClick
        // Publish synchronously, before the one body call below. This is provider binding, not UI
        // state: Reference transitionOwner()/holder bind callback also switches the provider/identity before layout/draw. Keeping
        // these fields as ordinary vars avoids a second Compose state clock around the adapter.
        published.rebindProviderIdentity(providerIdentity)
        published.songs = songs
        published.state = state
        published.playingSongId = playingSongId
        published.currentPlayingIndex = currentPlayingIndex
        published.selectedPositions = selectedPositions
        published.revealIndexRequest = revealIndexRequest
        published.hidePlayingCover = hidePlayingCover
        published.contentTopPadding = contentTopPadding
        published.presentationTopClipInset = resolvedPresentationTopClipInset
        published.persistentHeaderHeight = persistentHeaderHeight
        published.persistentHeaderVisibilityHeight = persistentHeaderVisibilityHeight
        published.persistentHeaderSceneItemVisibilityHeight = persistentHeaderSceneItemVisibilityHeight
        published.persistentHeaderSceneItemId = persistentHeaderSceneItemId
        published.virtualListEdgeEnabled = virtualListEdgeEnabled
        published.contentBottomPadding = contentBottomPadding
        published.sectionHeaders = sectionHeaders
        published.sectionHeaderHeight = sectionHeaderHeight
        published.pinchEnabled = pinchEnabled
        published.sharedCoverSceneId = sharedCoverSceneId
        published.customProvider = publishedCustomProvider
        published.persistentHeaderContent = persistentHeaderContent
        published.sectionHeaderContent = sectionHeaderContent
        published.sharedCoverElementIdProvider = sharedCoverElementIdProvider
        published.onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged
        published.onPlayingCoverTargetChanged = onPlayingCoverTargetChanged
        published.onSharedCoverTargetChanged = onSharedCoverTargetChanged
        published.onRevealCoverTargetResolved = onRevealCoverTargetResolved
        published.onScrollActiveChanged = onScrollActiveChanged
        published.onSongClick = onSongClick
        published.onSongLongClick = onSongLongClick
        if (structuralPublicationChanged) published.markStructuralPublicationChanged()
        if (behaviorPublicationChanged) published.markBehaviorRebound()
        providerRegistry.register(published)

        // VirtualList owns one physical body outside the category/page controllers.  Both CURRENT
        // and NEXT controllers only publish provider state; the fixed host call site below consumes
        // those two provider records and owns the one physical holder/LayoutRes population.  Keeping
        // the body here used to recreate/move the physical list whenever the authoritative page
        // controller changed at GenericPivot's endpoint.
        return
    }

    val libraryLayoutFrame = LocalReferenceLibraryLayoutFrame.current
    val localProviderNavigationFrame = LocalVirtualListProviderNavigationFrame.current
    val localProviderNavigationActive =
        usesLocalProviderNavigation(
            localActive = localProviderNavigationFrame.active,
            hasRetainedProvider = localProviderNavigationFrame.retainedProvider != null,
            outerActive = libraryLayoutFrame.active,
        )
    val outerRetainedProvider = if (localProviderNavigationActive) {
        localProviderNavigationFrame.retainedProvider
    } else if (bodyRender) {
        retainedPublishedProvider
    } else {
        null
    }
    val providerNavigationActive =
        localProviderNavigationActive || (libraryLayoutFrame.active && outerRetainedProvider != null)
    val providerCurrentTransform = if (localProviderNavigationActive) {
        localProviderNavigationFrame.currentTransform
    } else {
        libraryLayoutFrame.currentTransform
    }
    val providerRetainedTransform = if (localProviderNavigationActive) {
        localProviderNavigationFrame.retainedTransform
    } else {
        libraryLayoutFrame.retainedTransform
    }
    val outerDualLayoutRenderActive = providerNavigationActive && outerRetainedProvider != null
    // MAIN <-> PLAYER owns a long-lived transform outside route navigation. Ordinary song rows are
    // submitted by ReferenceLibraryVirtualListHost's persistent RenderNode, but HOME/custom holders
    // and section-header compatibility content still live in this provider viewport. Apply the same
    // external population transform to that viewport so those holders cannot remain fully visible
    // behind PLAYER and then pop into the returning list one frame later.
    val externalPopulationTransform =
        LocalExternalRetainedSceneItemTransform.current.takeUnless { providerNavigationActive }
    val density = LocalDensity.current
    val bottomChromeInsets = LocalBottomChromeInsets.current
    val bottomChromeEnvironment = LocalBottomChromeEnvironment.current
    val bottomChromeScrollState = LocalBottomChromeScrollState.current
    val externalSceneFrozen = LocalSceneBackgroundFrozen.current
    val retainedSceneMotionActive = LocalRetainedSceneMotionActive.current || localProviderNavigationActive
    val retainedScenePreflightOwnership = LocalRetainedScenePreflightOwnership.current
    val retainedSceneItemTransform = LocalRetainedSceneItemTransform.current
    val customProvider = publishedCustomProvider
    // GenericPivot owns a retained provider/layout population. A simultaneous playback bind must not
    // fan a new playing-row identity through 28-32 attached holders while those RenderNodes move.
    // Keep the settled playing identity until the retained layout is released, then publish once.
    val retainedPlayingId = remember { longArrayOf(playingSongId) }
    val retainedPlayingIndex = remember { intArrayOf(currentPlayingIndex) }
    val retainedHideCover = remember { booleanArrayOf(hidePlayingCover) }
    if (!retainedSceneMotionActive) {
        retainedPlayingId[0] = playingSongId
        retainedPlayingIndex[0] = currentPlayingIndex
        retainedHideCover[0] = hidePlayingCover
    }
    val scenePlayingSongId = if (retainedSceneMotionActive) retainedPlayingId[0] else playingSongId
    val scenePlayingIndex = if (retainedSceneMotionActive) retainedPlayingIndex[0] else currentPlayingIndex
    val sceneHidePlayingCover = if (retainedSceneMotionActive) retainedHideCover[0] else hidePlayingCover
    val transitionSessionCounter = remember { intArrayOf(0) }
    var transitionSession by remember { mutableStateOf<ComposeVirtualListTransitionSession?>(null) }
    // One physical transition ring spans chained pinch owners. Recreating this pool when a rebound
    // is interrupted would remap artwork cells even though the endpoint holders are already visible.
    val transitionSlotPool = remember { ComposeSlotPool() }
    // One keyed physical holder pool survives settled rows, internal zoom and outer provider/layout
    // transitions. Reference moves the same ArtworkItemNode between current/retained layout states; it does
    // not create a second holder population just because the layout owner changed.
    // The provider registry and the physical holder runtime must have the same lifetime.  HOME and
    // root library pages are different provider/controller compositions, but Reference keeps one
    // VirtualList/ArtworkItemNode pool across those switches.  Use the host-owned runtime when available;
    // standalone/detail VirtualLists keep their local runtime exactly as before.
    val hostPhysicalRuntime = LocalVirtualListPersistentRuntime.current
    val localPhysicalSlotPool = rememberVirtualListPhysicalSlotPool()
    val localPhysicalHolderOwner = remember { VirtualListPhysicalHolderOwner() }
    val localPhysicalLayoutSlots = remember { VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>() }
    val physicalSlotPool = hostPhysicalRuntime?.physicalSlotPool ?: localPhysicalSlotPool
    val physicalHolderOwner = hostPhysicalRuntime?.physicalHolderOwner ?: localPhysicalHolderOwner
    val physicalLayoutSlots = hostPhysicalRuntime?.physicalLayoutSlots ?: localPhysicalLayoutSlots
    val outerTransitionNodeRuntime = hostPhysicalRuntime?.settledNodeRuntime ?: remember { VirtualListSettledNodeRuntime() }
    val physicalHolderCompositionContext = rememberCompositionContext()
    val virtualListPerfSession = remember { longArrayOf(0L) }
    val virtualListPerfMarkedPairGeneration = remember { intArrayOf(Int.MIN_VALUE) }
    val virtualListRefreshRateHz = LocalView.current.display?.refreshRate ?: 60f
    var listRootBounds by remember { mutableStateOf<RectF?>(null) }
    if (
        state.isTransitioning &&
        transitionSession?.matches(
            source = state.sourceMode,
            target = state.targetMode,
            requestedPairGeneration = state.currentTransitionPairGeneration,
        ) != true
    ) {
        transitionSessionCounter[0] += 1
        val previousSession = transitionSession
        val chainedSourceScrollYPx = when {
            previousSession?.targetMode == state.sourceMode ->
                previousSession.resolvedTargetScrollYPx ?: previousSession.sourceScrollYPx
            previousSession?.sourceMode == state.sourceMode -> previousSession.sourceScrollYPx
            else -> state.viewportScrollY.roundToInt()
        }
        transitionSession = ComposeVirtualListTransitionSession(
            id = transitionSessionCounter[0],
            pairGeneration = state.currentTransitionPairGeneration,
            sourceMode = state.sourceMode,
            targetMode = state.targetMode,
            sourceScrollYPx = chainedSourceScrollYPx,
            fallbackListLevel = state.currentLevel,
            // Reference moves one retained layout-owner population for the complete transition.
            // Freeze the logical row population as well: repository/list publication may continue,
            // but it cannot replace the complete visible artwork set halfway through one pinch.
            // Reference keeps the provider population behind the retained layout owner; it does
            // not copy the complete adapter data set when a transition starts. Upstream list
            // publications are immutable snapshots in Raw as well, so retaining this list reference
            // freezes the session without an O(itemCount) main-thread copy.
            songs = songs,
            sectionHeaders = sectionHeaders,
        )
        if (virtualListPerfSession[0] == 0L) {
            virtualListPerfSession[0] = TransitionPerfTrace.start(
                newLabel = "virtuallist ${state.sourceMode}->${state.targetMode} songs=${songs.size}",
                refreshRateHz = virtualListRefreshRateHz,
            )
        }
    }

    val selectionModeActive = selectedPositions.isNotEmpty()
    val selectionCheckAlpha = remember { Animatable(0f) }
    LaunchedEffect(selectionModeActive) {
        selectionCheckAlpha.animateTo(
            targetValue = if (selectionModeActive) 1f else 0f,
            animationSpec = tween(durationMillis = 250),
        )
    }
    val selectionCheckAlphaProvider = remember(selectionCheckAlpha) {
        { selectionCheckAlpha.value }
    }

    CompositionLocalProvider(
        LocalSelectionCheckAlphaProvider provides selectionCheckAlphaProvider,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    listRootBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    hostPhysicalRuntime?.settledNodeRuntime?.updatePresentationViewportBoundsInRoot(
                        left = bounds.left,
                        top = bounds.top,
                        width = bounds.width,
                        height = bounds.height,
                    )
                }
                .then(
                    if (pinchEnabled) {
                        Modifier.virtualListPointerInput(state, density.density)
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
            TransitionPerfTrace.measure(TransitionPerfStage.VIRTUALLIST_METRICS) {
                computeVirtualListMetrics(
                    widthPx = widthPx,
                    density = density.density,
                    scaledDensity = density.density * density.fontScale,
                    mode = state.renderMode,
                    params = state.currentParams
                )
            }
        }

        val customItemHeightsPx = remember(songs, customProvider, metrics.cellHeightPx, density.density) {
            val provider = customProvider ?: return@remember null
            var hasCustom = false
            IntArray(songs.size) { index ->
                val song = songs[index]
                val custom = if (provider.handles(song, index)) provider.itemHeight(song, index) else null
                if (custom != null) {
                    hasCustom = true
                    with(density) { custom.roundToPx() }.coerceAtLeast(1)
                } else {
                    metrics.cellHeightPx.coerceAtLeast(1)
                }
            }.takeIf { hasCustom }
        }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val requestedBottomPaddingPx = with(density) {
            (contentBottomPadding ?: bottomChromeInsets.contentBottom).roundToPx()
        }
        // Resolve padding per layout engine, not from the global "isTransitioning" flag. Source and
        // target must keep the exact geometry they have when settled; changing LIST_SMALL padding at
        // gesture start/end was enough to remap the complete visible holder population.
        val breathingRoomPx = with(density) { 8.dp.roundToPx() }
        val chromeTopInRootPx = bottomChromeScrollState
            ?.topInRootPx
            ?.takeIf { it > 0f }
        val actualBottomChromeOverlapPx = if (chromeTopInRootPx != null) {
            ((listRootBounds?.bottom ?: chromeTopInRootPx) - chromeTopInRootPx)
                .coerceAtLeast(0f)
                .roundToInt()
        } else {
            0
        }
        fun bottomPaddingForMode(mode: ComposeVirtualListDisplayMode): Int {
            return if (mode == ComposeVirtualListDisplayMode.LIST_SMALL) {
                minOf(
                    requestedBottomPaddingPx,
                    actualBottomChromeOverlapPx + breathingRoomPx,
                )
            } else {
                requestedBottomPaddingPx
            }
        }
        val bottomPaddingPx = bottomPaddingForMode(state.currentMode)
        val staticTopPaddingPx = with(density) { contentTopPadding.roundToPx() }
        val persistentHeaderHeightPx = with(density) { persistentHeaderHeight.roundToPx() }
        val persistentHeaderVisibilityHeightPx = with(density) {
            persistentHeaderVisibilityHeight.roundToPx()
        }
        val persistentHeaderSceneItemVisibilityHeightPx = with(density) {
            persistentHeaderSceneItemVisibilityHeight.roundToPx()
        }
        val contentTopPaddingPx = staticTopPaddingPx + persistentHeaderHeightPx
        if (hostPhysicalRuntime != null && LocalVirtualListPresentationHostExternal.current) {
            SideEffect {
                // HAZE/FLOATING keep this at zero so the fixed presentation host can feed blur and
                // reveal content through the top-menu lane. TRANSPARENT publishes a real top inset:
                // the retained Android/RenderNode host must own that clip because a Compose parent
                // draw mask does not contain the permanent presentation node.
                hostPhysicalRuntime.settledNodeRuntime.updatePresentationViewportClipTopInset(
                    with(density) { resolvedPresentationTopClipInset.toPx() }
                )
            }
        }
        // Collection/details use VirtualList's own vertical edge owner. reference player 1026's
        // The edge-layout policy uses a 44dp max overshoot with a 30dp holder-shift extent; this is not
        // ArtworkPager/maxStretchOvershoot and is independent from hero height or body fill level.
        val detailVirtualListEdgeEnabled = virtualListEdgeEnabled &&
            persistentHeaderHeightPx > 0 && songs.isNotEmpty()
        val virtualListMaxOvershootPx = with(density) { VIRTUAL_LIST_MAX_OVERSHOOT_DP.dp.toPx() }
        LaunchedEffect(detailVirtualListEdgeEnabled) {
            if (!detailVirtualListEdgeEnabled && state.isVirtualListEdgeActive) {
                state.clearVirtualListEdge()
            }
        }
        val sectionHeaderHeightPx = with(density) { sectionHeaderHeight.roundToPx() }
        val naturalMaxScrollY = maxScrollForContent(
            itemCount = songs.size,
            metrics = metrics,
            viewportHeightPx = heightPx,
            bottomPaddingPx = bottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            itemHeightsPx = customItemHeightsPx,
        )
        // Reference keeps the category header inside the VirtualList layout owner. Do not invent a
        // hero-height scroll runway when the detail body is under-filled: real scroll stops when real
        // content is exhausted, then the local boundary owner handles any continued drag.
        val maxScrollY = maxScrollForPersistentHeaderContent(naturalMaxScrollY).toFloat()
        val geometry = remember(metrics, bottomPaddingPx, contentTopPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx, customItemHeightsPx) {
            listGeometryFor(
                metrics = metrics,
                bottomPaddingPx = bottomPaddingPx,
                topPaddingPx = contentTopPaddingPx,
                itemCount = songs.size,
                sectionHeaders = sectionHeaders,
                sectionHeaderHeightPx = sectionHeaderHeightPx,
                itemHeightsPx = customItemHeightsPx,
            )
        }
        val geometrySignature = remember(
            songs.size,
            metrics.columns,
            metrics.cellWidthPx,
            metrics.cellHeightPx,
            contentTopPaddingPx,
            bottomPaddingPx,
            sectionHeaderHeightPx,
            sectionHeaders,
            customItemHeightsPx,
        ) {
            virtualListGeometrySignature(
                itemCount = songs.size,
                columns = metrics.columns,
                cellWidthPx = metrics.cellWidthPx,
                cellHeightPx = metrics.cellHeightPx,
                topPaddingPx = contentTopPaddingPx,
                bottomPaddingPx = bottomPaddingPx,
                sectionHeaderHeightPx = sectionHeaderHeightPx,
                sectionHeaders = sectionHeaders,
                customItemHeightsPx = customItemHeightsPx,
            )
        }
        val transitionSnapshot = rememberComposeVirtualListTransitionSnapshot(
            session = transitionSession,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density.density,
            fontScale = density.fontScale,
            contentTopPaddingPx = contentTopPaddingPx,
            sourceBottomPaddingPx = transitionSession?.let { bottomPaddingForMode(it.sourceMode) } ?: bottomPaddingPx,
            targetBottomPaddingPx = transitionSession?.let { bottomPaddingForMode(it.targetMode) } ?: bottomPaddingPx,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            slotPool = transitionSlotPool,
        )
        val transitionOverlayVisible = transitionSnapshot != null
        val persistentViewportClipEnabled = shouldClipVirtualListViewport(
            transitionOverlayVisible = transitionOverlayVisible || state.isBoundaryElasticActive,
            externalSceneFrozen = externalSceneFrozen,
        )
        if (hostPhysicalRuntime != null && LocalVirtualListPresentationHostExternal.current) {
            SideEffect {
                hostPhysicalRuntime.settledNodeRuntime.updatePresentationViewportClipEnabled(
                    persistentViewportClipEnabled
                )
            }
        }
        val outerDualPhysicalRenderActive = outerDualLayoutRenderActive && transitionSnapshot == null
        // HOME's heterogeneous holders must keep one canonical Compose call site across
        // OUTER -> SETTLED. Moving their movableContent from the dual-layout renderer back into
        // the settled fallback after GenericPivot completes is a real subtree relocation and is
        // visible as the post-transition HOME flash. Keep the same physical population owner alive
        // after the retained role disappears; ordinary rows remain owned by the Android/Node path.
        val persistentSettledCustomPhysicalOwner =
            hostPhysicalRuntime != null &&
                LocalVirtualListPresentationHostExternal.current &&
                !providerNavigationActive &&
                transitionSnapshot == null &&
                customProvider != null
        val canonicalPhysicalPopulationActive =
            outerDualPhysicalRenderActive || persistentSettledCustomPhysicalOwner
        // The external presentation host is the only visual owner during a HOME/category handoff.
        // reference player keeps the old VirtualList holder population attached until the next provider and
        // its retained LayoutRes are bound.  Raw previously waited for outerRetainedProvider to be
        // non-null before taking that ownership, so the first apply pass fell back to ordinary
        // Compose rows and closed the physical slot frame.  That creates the characteristic start
        // flash and can recycle the source artwork before the outer union is ready.
        //
        // Keep the source population authoritative during both the preflight and the active handoff.
        // Custom HOME holders are still allowed through the compatibility path because the retained
        // Canvas presentation node can only own ordinary song holders; ordinary rows stay exclusively
        // in the permanent host until publishOuterFrame() atomically replaces it.
        val libraryPresentationHandoffActive = hostPhysicalRuntime != null &&
            LocalVirtualListPresentationHostExternal.current &&
            (retainedSceneMotionActive || externalSceneFrozen ||
                retainedScenePreflightOwnership || outerDualPhysicalRenderActive)
        val libraryPhysicalFrameMustStayOpen = libraryPresentationHandoffActive || outerDualPhysicalRenderActive
        LaunchedEffect(state.isTransitioning) {
            if (!state.isTransitioning && virtualListPerfSession[0] != 0L) {
                TransitionPerfTrace.stop(virtualListPerfSession[0], "virtuallist_complete")
                virtualListPerfSession[0] = 0L
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (virtualListPerfSession[0] != 0L) {
                    TransitionPerfTrace.stop(virtualListPerfSession[0], "virtuallist_dispose")
                    virtualListPerfSession[0] = 0L
                }
            }
        }
        val scrollBucketHeight = geometry.rowStridePx.coerceAtLeast(1)
        // The visible library host keeps this Compose call site alive while switching providers.
        // Keying only by row height therefore leaked the previous page's scroll bucket into the
        // destination's first settled frame. The effect below corrected it one frame later, which
        // appeared as a deterministic post-entry flash whenever the destination was not at the top.
        // Seed the retained bucket synchronously from the destination's authoritative scroll and
        // give every provider/state its own value, matching the retained LayoutRes used by OUTER.
        val initialScrollIndex = state.initialScrollToIndexRequest()
        val initialScrollCanResolve =
            initialScrollIndex in songs.indices && heightPx > 0 && geometry.rowStridePx > 0
        val seededInitialScrollY = if (initialScrollCanResolve) {
            scrollYForIndex(
                index = initialScrollIndex,
                itemCount = songs.size,
                geometry = geometry,
                viewportHeightPx = heightPx,
            ).coerceIn(0, maxScrollY.roundToInt())
        } else {
            null
        }
        val rememberedScrollY = seededInitialScrollY
            ?: state.viewportScrollY.roundToInt().coerceAtLeast(0)
        val geometryTemporarilyUnready = state.shouldPreserveRetainedScrollForZeroGeometry(
            rawScrollY = rememberedScrollY,
            maxScrollY = maxScrollY.roundToInt(),
            geometrySignature = geometrySignature,
            viewportHeightPx = heightPx,
        )
        val initialSettledRawScrollY = resolveSettledScrollForGeometry(
            rawScrollY = rememberedScrollY,
            maxScrollY = maxScrollY.roundToInt(),
            preserveRetainedScroll = geometryTemporarilyUnready,
        )
        val initialSettledBaseScrollY =
            (initialSettledRawScrollY / scrollBucketHeight) * scrollBucketHeight
        val settledBaseScrollYState = remember(
            state,
            providerScene,
            scrollBucketHeight,
            state.viewportResetSerial,
            initialSettledBaseScrollY,
        ) {
            mutableStateOf(initialSettledBaseScrollY)
        }
        // Publish the matching remainder before drawing the first retained frame.
        // A newly bound row stride must never use the previous layout's remainder.
        SideEffect {
            if (seededInitialScrollY != null) {
                // Commit the one-shot initial anchor in the same successful composition that
                // publishes its settled LayoutRes. No visible index-0 correction frame exists.
                state.viewportScrollY = initialSettledRawScrollY.toFloat()
                state.consumeInitialScrollToIndexRequest(initialScrollIndex)
            } else if (initialScrollIndex < 0) {
                state.markInitialViewportBound()
            }
            if (!geometryTemporarilyUnready) {
                state.recordSettledGeometry(
                    geometrySignature = geometrySignature,
                    viewportHeightPx = heightPx,
                    maxScrollY = maxScrollY.roundToInt(),
                )
            }
            state.viewportScrollOwner.updateRenderRemainder(
                initialSettledRawScrollY - initialSettledBaseScrollY
            )
        }
        fun updateSettledBaseScroll(rawScrollY: Int) {
            val clampedScrollY = rawScrollY.coerceIn(0, maxScrollY.roundToInt())
            val nextBase = (clampedScrollY / scrollBucketHeight) * scrollBucketHeight
            if (settledBaseScrollYState.value != nextBase) {
                settledBaseScrollYState.value = nextBase
            }
            val nextRemainder = clampedScrollY - nextBase
            state.viewportScrollOwner.updateRenderRemainder(nextRemainder)
        }
        LaunchedEffect(
            state.isTransitioning,
            transitionSnapshot?.sessionId,
            state.currentMode,
            maxScrollY,
            scrollBucketHeight,
        ) {
            if (state.isTransitioning) return@LaunchedEffect
            if (geometryTemporarilyUnready) {
                // No one-frame timeout: under load a parked provider can need multiple vsyncs to
                // republish its extent. The matching last-settled signature proves this is the same
                // layout, so retain its exact scroll until authoritative geometry restarts the
                // effect. Genuine content/viewport changes have a different signature and clamp.
                return@LaunchedEffect
            }
            val snapshot = transitionSnapshot
            if (snapshot != null) {
                val endpoint = snapshot.endpointScrollFor(state.currentMode)
                    .coerceIn(0, maxScrollY.roundToInt())
                state.viewportScrollY = endpoint.toFloat()
                updateSettledBaseScroll(endpoint)
                // Keep the immutable transition holders over the canonical settled target for one
                // real frame. This is the Compose equivalent of Reference handing one retained
                // layout-engine population to the committed layout without an intermediate remap.
                withFrameNanos { }
                if (!state.isTransitioning && transitionSession?.id == snapshot.sessionId) {
                    transitionSession = null
                }
            } else {
                val clamped = state.viewportScrollY.roundToInt().coerceIn(0, maxScrollY.roundToInt())
                state.viewportScrollY = clamped.toFloat()
                updateSettledBaseScroll(clamped)
            }
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
                    coverKey = revealSong?.coverKey.orEmpty(),
                    artworkRadiusType = revealSong?.virtualListArtworkRadiusType()
                        ?: VirtualListArtworkRadiusType.TRACK,
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

            state.viewportScrollY = targetScrollY.toFloat()
            updateSettledBaseScroll(targetScrollY)

            state.consumeScrollToIndexRequest(serial)
        }
        val underfilledPersistentHeaderLane = usesUnderfilledPersistentHeaderLane(
            naturalMaxScrollPx = naturalMaxScrollY,
            persistentHeaderHeightPx = persistentHeaderHeightPx,
            itemCount = songs.size,
        )
        val scrollableState = rememberScrollableState { delta ->
            if (state.isTransitioning || state.isPinching || state.isBoundaryElasticActive) {
                delta
            } else {
                var remaining = delta
                var consumed = 0f

                // Once VirtualList owns an edge, reversing first drains that edge transaction. Only
                // the remainder after overshoot reaches zero is allowed to move logical scrollY.
                if (detailVirtualListEdgeEnabled && state.isVirtualListEdgeActive) {
                    val edgeConsumed = state.consumeVirtualListEdgeDelta(
                        deltaPx = remaining,
                        maxOvershootPx = virtualListMaxOvershootPx,
                    )
                    consumed += edgeConsumed
                    remaining -= edgeConsumed
                    if (kotlin.math.abs(remaining) < 0.001f) {
                        return@rememberScrollableState consumed
                    }
                }

                val old = state.viewportScrollY
                val newValue = (old - remaining).coerceIn(0f, maxScrollY)
                if (newValue != old) {
                    if (bottomChromeEnvironment.layoutClass == BottomChromeLayoutClass.Compact) {
                        bottomChromeScrollState?.onContentScroll(newValue - old)
                    }
                }
                state.viewportScrollY = newValue
                updateSettledBaseScroll(newValue.toInt())

                val geometryConsumed = old - newValue
                consumed += geometryConsumed
                remaining -= geometryConsumed

                // reference player applies the same VirtualList edge owner to every collection detail, not
                // only to under-filled bodies. Logical geometry stops at 0/maxScrollY; any remaining
                // same-axis delta becomes signed overshoot and remains list-owned until UP/CANCEL.
                if (detailVirtualListEdgeEnabled && kotlin.math.abs(remaining) >= 0.001f) {
                    val atTop = newValue <= 0.5f && remaining > 0f
                    val atBottom = newValue >= maxScrollY - 0.5f && remaining < 0f
                    if (atTop || atBottom) {
                        consumed += state.consumeVirtualListEdgeDelta(
                            deltaPx = remaining,
                            maxOvershootPx = virtualListMaxOvershootPx,
                        )
                    }
                }
                consumed
            }
        }
        // Transition ownership handoff: a fast VirtualList fling must not keep mutating scrollY /
        // holder bindings after HOME/category GenericPivot has taken ownership. Acquiring the
        // scroll mutex at PreventUserInput priority cancels the running fling without snapping the
        // already-rendered scroll position. The source scene gets this signal one preparation frame
        // before forward/back animation, and immediately on predictive-back capture.
        val retainedScrollOwnership = retainedSceneMotionActive || retainedScenePreflightOwnership
        LaunchedEffect(retainedScrollOwnership) {
            if (retainedScrollOwnership) {
                val wasScrolling = scrollableState.isScrollInProgress
                TransitionPerfTrace.mark(
                    "scroll_handoff_begin",
                    "scrolling=$wasScrolling scrollY=${"%.1f".format(state.viewportScrollY)} mode=${state.currentMode}",
                )
                scrollableState.scroll(MutatePriority.PreventUserInput) { }
                state.clearVirtualListEdge()
                val stillScrolling = scrollableState.isScrollInProgress
                TransitionPerfTrace.mark(
                    "scroll_handoff_end",
                    "before=$wasScrolling after=$stillScrolling scrollY=${"%.1f".format(state.viewportScrollY)}",
                )
                if (wasScrolling) {
                    TransitionPerfTrace.count(TransitionPerfEvent.SCROLL_HANDOFF_CANCELLED)
                }
            }
        }
        val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
        val virtualListFlingBehavior = remember(
            defaultFlingBehavior,
            maxScrollY,
            persistentHeaderHeightPx,
            songs.size,
            metrics.columns,
            underfilledPersistentHeaderLane,
            detailVirtualListEdgeEnabled,
            virtualListMaxOvershootPx,
        ) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    if (detailVirtualListEdgeEnabled && state.isVirtualListEdgeActive) {
                        state.animateVirtualListEdgeBack()
                        return 0f
                    }
                    val scrollScope = this
                    val remainingVelocity = with(defaultFlingBehavior) {
                        scrollScope.performFling(initialVelocity)
                    }
                    if (detailVirtualListEdgeEnabled && state.isVirtualListEdgeActive) {
                        // Fling may reach a real edge after it starts; the edge-scroll policy settles that same signed
                        // VirtualList overshoot rather than handing residual velocity to another owner.
                        state.animateVirtualListEdgeBack()
                        return 0f
                    }
                    return if (
                        shouldConsumeTerminalPersistentHeaderFling(
                            initialVelocityPxPerSecond = initialVelocity,
                            currentScrollPx = state.viewportScrollY,
                            maxScrollPx = maxScrollY,
                            persistentHeaderHeightPx = persistentHeaderHeightPx,
                            itemCount = songs.size,
                            columns = metrics.columns,
                        )
                    ) {
                        // Reference corrects an under-filled terminal layout inside the list engine
                        // before the residual gesture reaches an outer/stretch owner. Returning no
                        // velocity here is the Compose equivalent: the one-row anchor stays put
                        // instead of springing down after the user's upward swipe.
                        0f
                    } else {
                        remainingVelocity
                    }
                }
            }
        }

        // Scrollable normally enters performFling even for a low-velocity release, but keep the
        // VirtualList edge owner self-contained if a gesture is cancelled before fling dispatch. The
        // edge must never remain parked after its pointer transaction ends.
        LaunchedEffect(
            scrollableState.isScrollInProgress,
            state.isVirtualListEdgeActive,
            detailVirtualListEdgeEnabled,
        ) {
            if (
                detailVirtualListEdgeEnabled &&
                !scrollableState.isScrollInProgress &&
                state.isVirtualListEdgeActive
            ) {
                state.animateVirtualListEdgeBack()
            }
        }

        val currentScrollActiveChanged by rememberUpdatedState(onScrollActiveChanged)
        LaunchedEffect(
            scrollableState.isScrollInProgress,
            state.isTransitioning,
            state.isPinching,
            state.isBoundaryElasticActive,
            state.isVirtualListEdgeActive
        ) {
            state.updateListScrollInProgress(scrollableState.isScrollInProgress)
            if (bottomChromeEnvironment.layoutClass == BottomChromeLayoutClass.Compact) {
                bottomChromeScrollState?.onScrollActivityChanged(scrollableState.isScrollInProgress)
            }
            currentScrollActiveChanged(
                scrollableState.isScrollInProgress ||
                    state.isTransitioning ||
                    state.isPinching ||
                    state.isBoundaryElasticActive ||
                    state.isVirtualListEdgeActive
            )
        }
        DisposableEffect(Unit) {
            onDispose {
                state.updateListScrollInProgress(false)
                currentScrollActiveChanged(false)
            }
        }
        // Temporary diagnostics only. This observes frame gaps while scrolling/transitioning;
        // it does not drive layout, animation, or artwork state.
        if (VIRTUAL_LIST_TRACE_FRAMES) {
            LaunchedEffect(scrollableState.isScrollInProgress, state.isTransitioning) {
                if (!scrollableState.isScrollInProgress && !state.isTransitioning) {
                    return@LaunchedEffect
                }
                var previousFrameNs = withFrameNanos { it }
                while (true) {
                    val frameNs = withFrameNanos { it }
                    val gapMs = (frameNs - previousFrameNs) / 1_000_000L
                    if (gapMs >= VIRTUAL_LIST_TRACE_FRAME_GAP_MS) {
                        Log.w(
                            VIRTUAL_LIST_TRACE_TAG,
                            "VIRTUAL_LIST_TRACE frame_gap_ms=$gapMs " +
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
        val physicalLayoutFrameId = physicalLayoutSlots.beginFrame()
        // One VirtualList transaction owns both provider roles.  Endpoint publishers below write
        // directly into the persistent holder-style runtime records using this same generation id.
        outerTransitionNodeRuntime.beginPowerItemFrame(physicalLayoutFrameId)
        if (providerNavigationActive && outerRetainedProvider != null) {
            ComposeReferenceRetainedProviderPopulation(
                provider = outerRetainedProvider,
                transform = providerRetainedTransform,
                widthPx = widthPx,
                heightPx = heightPx,
                physicalSlotPool = physicalSlotPool,
                physicalHolderOwner = physicalHolderOwner,
                physicalLayoutSlots = physicalLayoutSlots,
                physicalLayoutFrameId = physicalLayoutFrameId,
                ordinaryPublicationOnly = outerDualPhysicalRenderActive,
            )
        }

        val physicalPresentationOwnsExternalTransform =
            LocalVirtualListPresentationHostExternal.current &&
                ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW &&
                hostPhysicalRuntime != null &&
                !providerNavigationActive
        val providerPopulationRootModifier = Modifier
            .fillMaxSize()
                // Raw Compose pays a large command-issue tax when 20-30 holder RenderNodes all receive
                // the same HOME/category affine every frame. Keep one persistent provider-population
                // RenderNode and leave item-local layers to list elasticity only.
                .referenceProviderPopulationTransform(
                    when {
                        providerNavigationActive && !outerDualPhysicalRenderActive -> providerCurrentTransform
                        !providerNavigationActive && !physicalPresentationOwnsExternalTransform ->
                            externalPopulationTransform
                        else -> null
                    }
                )
                .then(
                    if (physicalPresentationOwnsExternalTransform) {
                        // Android VirtualList owns the exact viewport clip + parent affine. Wrapping
                        // that View in a second Compose graphicsLayer applied the same PLAYER
                        // transform twice and kept a second RenderNode property owner on every
                        // sheet frame.
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                    // VirtualList edge deformation is holder-local (LayoutRes), never a whole-list
                    // scale/translation. This root keeps only viewport clip ownership.
                    val externalScale = externalPopulationTransform
                        ?.scaleProvider
                        ?.invoke()
                        ?.coerceIn(0.5f, 1.5f)
                        ?: 1f
                    val externalAlpha = externalPopulationTransform
                        ?.alphaProvider
                        ?.invoke()
                        ?.coerceIn(0f, 1f)
                        ?: 1f
                    val externalPlayerTransformActive = externalPopulationTransform != null &&
                        (kotlin.math.abs(externalScale - 1f) > 0.0001f || externalAlpha < 0.9999f)
                    clip = persistentViewportClipEnabled && !externalPlayerTransformActive
                        }
                    }
                )
                .scrollable(
                    orientation = Orientation.Vertical,
                    state = scrollableState,
                    flingBehavior = virtualListFlingBehavior,
                    enabled = !retainedScrollOwnership,
                )
        Box(
            modifier = providerPopulationRootModifier
        ) {
            if (
                LocalVirtualListPresentationHostExternal.current &&
                ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW &&
                hostPhysicalRuntime != null
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        RawVirtualListPresentationView(context).also { host ->
                            host.bind(
                                nextRuntime = outerTransitionNodeRuntime,
                                nextExternalTransform = physicalPresentationExternalTransform,
                                nextPresentationVisibleProvider = physicalPresentationVisibleProvider,
                                nextParentCompositionContext = physicalHolderCompositionContext,
                            )
                        }
                    },
                    update = { host ->
                        host.bind(
                            nextRuntime = outerTransitionNodeRuntime,
                            nextExternalTransform = physicalPresentationExternalTransform,
                            nextPresentationVisibleProvider = physicalPresentationVisibleProvider,
                            nextParentCompositionContext = physicalHolderCompositionContext,
                        )
                    },
                    onRelease = RawVirtualListPresentationView::unbind,
                )
            }
            // One permanent visual owner for settled + GenericPivot holder RenderNodes. Publisher
            // nodes below only bind LayoutRes/input state; this host remains in the same viewport
            // slot so transition start/end cannot expose the background for one frame.
            if (!LocalVirtualListPresentationHostExternal.current) {
                // Standalone/non-library VirtualLists still own a local presentation node. HOME/root
                // library pages publish into the one host fixed at ReferenceLibraryVirtualListHost.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .virtualListPresentationHost(outerTransitionNodeRuntime)
                )
            }

            val transitionSettledScroll = transitionSnapshot?.endpointScrollFor(state.currentMode)
            // While the immutable transition owner is still covering the viewport, keep the
            // canonical holder tree bound to the same frozen logical population. This prevents a
            // repository/list publication from exposing an entirely different index set during the
            // one-frame endpoint handoff. Live data resumes only after the transition owner releases.
            val settledSongs = transitionSnapshot?.songs ?: songs
            val settledSectionHeaders = transitionSnapshot?.sectionHeaders ?: sectionHeaders
            // Under-filled collection details are tiny enough to bypass the row-banded retained
            // scroll optimization. Keep header and rows on the same exact viewport offset so the
            // whole attached layout reaches its real content edge together before the boundary
            // owner takes over.
            // Exact per-pixel holder layout is only for genuinely under-filled collection details.
            // HOME also has custom/variable holder heights, but that does not require every holder
            // subtree to consume snapshot scroll state on every touch pixel. Keeping HOME on this
            // lane makes a 120 Hz scroll look frame-perfect while still feeling sticky because the
            // whole heterogeneous Compose population is relaid/repositioned for every input sample.
            // VirtualList keeps those holders attached and moves the fractional scroll once at the
            // viewport, so variable-height HOME uses the normal bucket + parent remainder path.
            val exactShortPersistentHeaderLane = usesExactPersistentHeaderScrollLane(
                persistentHeaderHeightPx = persistentHeaderHeightPx,
                itemCount = songs.size,
                naturalMaxScrollPx = naturalMaxScrollY,
            )
            // Only the rare exact-height/short-detail compatibility lane observes exact scroll in
            // this parent composition. Ordinary settled LIST/GRID keeps exact motion plain and lets
            // the Node/range observers consume it imperatively.
            val exactShortScrollState = rememberVirtualListExactScrollPx(
                owner = state.viewportScrollOwner,
                enabled = exactShortPersistentHeaderLane && transitionSettledScroll == null,
            )
            val settledBaseScrollY = when {
                transitionSettledScroll != null -> {
                    val raw = transitionSettledScroll.coerceIn(0, maxScrollY.roundToInt())
                    if (exactShortPersistentHeaderLane) raw
                    else (raw / scrollBucketHeight) * scrollBucketHeight
                }
                exactShortPersistentHeaderLane -> resolveSettledScrollForGeometry(
                    rawScrollY = exactShortScrollState.value.roundToInt(),
                    maxScrollY = maxScrollY.roundToInt(),
                    // A short persistent-header detail can reattach one composition before its
                    // geometry republishes a non-zero extent. Keep the old exact coordinate until
                    // authoritative geometry is available instead of exposing an index-0 frame.
                    preserveRetainedScroll = geometryTemporarilyUnready,
                )
                else -> settledBaseScrollYState.value
            }

            // One holder population owns the viewport at a time. During an internal list/grid zoom
            // the transition population replaces the settled tree; keeping an alpha-zero duplicate
            // doubles attached cells and artwork observers, which Reference's retained View pool does not.
            // After an internal zoom reaches its endpoint, publish the canonical settled ViewGroup
            // underneath the still-attached transition overlay for one real frame. Waiting until
            // transitionSnapshot becomes null removed the overlay first and only published settled
            // holders on the following composition, producing the whole-list endpoint flash.
            if (transitionSnapshot == null || !state.isTransitioning) {
            ComposeVirtualListSettledContent(
                songs = settledSongs,
                state = state,
                metrics = metrics,
                scrollYPx = settledBaseScrollY,
                scrollRemainderProvider = {
                    when {
                        exactShortPersistentHeaderLane -> 0
                        transitionSettledScroll != null ->
                            transitionSettledScroll.coerceIn(0, maxScrollY.roundToInt()) - settledBaseScrollY
                        else -> state.viewportScrollY.roundToInt() - settledBaseScrollY
                    }
                },
                nodeScrollRemainderProvider = {
                    when {
                        exactShortPersistentHeaderLane -> 0
                        transitionSettledScroll != null ->
                            transitionSettledScroll.coerceIn(0, maxScrollY.roundToInt()) - settledBaseScrollY
                        else -> state.viewportScrollY.roundToInt() - settledBaseScrollY
                    }
                },
                // Exact-height providers (notably HOME) already publish every pixel through
                // exactShortScrollState above. Attaching the imperative RenderNode invalidation lane
                // at the same time replays the same scroll twice and produces the visible upward
                // jitter/drift. Keep the old single-owner contract there. The sole exception is a
                // real collection-detail VirtualList edge: its overshoot changes RenderNode properties
                // without changing exact scrollY, so that exact detail lane needs the invalidator.
                nodeScrollInvalidationOwner = if (
                    transitionSettledScroll == null &&
                    (!exactShortPersistentHeaderLane || detailVirtualListEdgeEnabled)
                ) {
                    state.viewportScrollOwner
                } else {
                    null
                },
                exactScrollObservationOwner = if (
                    !exactShortPersistentHeaderLane && transitionSettledScroll == null
                ) state.viewportScrollOwner else null,
                viewportHeightPx = heightPx,
                playingSongId = scenePlayingSongId,
                currentPlayingIndex = scenePlayingIndex,
                selectedPositions = selectedPositions,
                hidePlayingCover = sceneHidePlayingCover,
                boundaryScaleProvider = { state.boundaryElasticScale },
                exactPixelScrollLane = exactShortPersistentHeaderLane,
                // Normal scrolling keeps one recycling row on each side. GenericPivot/scene motion
                // must expose only the strict viewport population: clip is intentionally released so
                // the partially visible top row can become whole while scaling, but the fully hidden
                // recycling row above it is not a Reference transition participant.
                // Outer HOME/category motion keeps the already-attached ring intact. Switching to a
                // strict window at transition start cancelled/rebound dozens of cold artwork holders
                // and produced the first-frame request/cancel wave seen in the device trace.
                strictSceneHolderWindow = if (providerNavigationActive) {
                    false
                } else {
                    retainedSceneMotionActive || externalSceneFrozen
                },
                interactionActive = retainedSceneMotionActive || externalSceneFrozen || state.isTransitioning ||
                    scrollableState.isScrollInProgress || state.isPinching ||
                    state.isBoundaryElasticActive || state.isVirtualListEdgeActive,
                // Reference does not disable ArtworkImageNode's first-wrapper fade merely because the list
                // is scrolling. Suppress only when an outer/zoom geometry owner is active; ordinary
                // fling/drag may still accept a fresh async wrapper through the 200ms image-local fade.
                // baseline implementation ArtworkImageNode keeps its local wrapper animation independent from
                // VirtualList GenericPivot/scroll/zoom geometry ownership. Visible holders may
                // accept an async wrapper and run the local 200 ms artwork fade while the parent moves.
                artworkAnimationsEnabled = true,
                settledMarqueeVisible = true,
                topPaddingPx = contentTopPaddingPx,
                itemHeightsPx = customItemHeightsPx,
                sectionHeaders = settledSectionHeaders,
                sectionHeaderHeightPx = sectionHeaderHeightPx,
                sectionHeaderContent = sectionHeaderContent,
                sharedCoverSceneId = sharedCoverSceneId,
                sharedCoverElementIdProvider = sharedCoverElementIdProvider,
                onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                onSharedCoverTargetChanged = onSharedCoverTargetChanged,
                onSongClick = onSongClick,
                onSongLongClick = onSongLongClick,
                physicalSlotPool = physicalSlotPool,
                physicalHolderOwner = physicalHolderOwner,
                physicalLayoutSlots = physicalLayoutSlots,
                physicalLayoutFrameId = physicalLayoutFrameId,
                physicalLayoutRole = VirtualListPhysicalLayoutRole.CURRENT,
                physicalKeyPrefix = currentPhysicalProviderIdentity,
                appendPhysicalFrame = providerNavigationActive && outerRetainedProvider != null,
                 deferPhysicalCleanup = libraryPhysicalFrameMustStayOpen,
                 ordinaryPublicationOnly = libraryPhysicalFrameMustStayOpen,
                suppressHolderRenderingForOuterDual = canonicalPhysicalPopulationActive,
                sceneTransform = if (providerNavigationActive) null else retainedSceneItemTransform,
                settledNodeRuntimeOverride = outerTransitionNodeRuntime,
                modifier = Modifier
                    .fillMaxSize()
                    .referenceProviderPopulationTransform(
                        if (outerDualPhysicalRenderActive) providerCurrentTransform else null
                    )
            )
            }

            if (canonicalPhysicalPopulationActive) {
                // Both provider roles have now published endpoint records.  Close the one VirtualList
                // transaction, then bind CURRENT/RETAINED LayoutRes directly inside the persistent
                // runtime. Ordinary and heterogeneous holders are both real children of the fixed
                // Android VirtualList host; there is no transition Compose renderer anymore.
                physicalLayoutSlots.endFrame(physicalLayoutFrameId)
                outerTransitionNodeRuntime.endPowerItemFrame(physicalLayoutFrameId)
                physicalSlotPool.appendFrame(emptyList(), closeFrame = true)
                if (outerDualPhysicalRenderActive) {
                    outerTransitionNodeRuntime.publishPowerItemOuterFrame(
                        currentFractionProvider = {
                            virtualListOuterCurrentLayoutFraction(
                                providerCurrentTransform,
                                providerRetainedTransform,
                            )
                        },
                        currentTransform = providerCurrentTransform,
                        retainedTransform = providerRetainedTransform,
                        onPopulationPublished = onOuterPopulationPublished,
                    )
                    val populationPublished = localProviderNavigationFrame.onPopulationPublished
                        .takeIf { localProviderNavigationActive }
                    if (populationPublished != null) SideEffect(populationPublished)
                }
            }

            transitionSnapshot?.let { snapshot ->
                val endpointProgress = if (state.currentMode == snapshot.targetMode) 1f else 0f
                val progressProvider = remember(state, snapshot.sessionId, endpointProgress) {
                    {
                        if (state.isTransitioning) {
                            state.transitionProgress
                        } else {
                            endpointProgress
                        }
                    }
                }
                val elasticScaleProvider = remember(state, snapshot.sessionId) {
                    { if (state.isTransitioning) state.transitionScaleFactor else 1f }
                }
                val physicalInternalZoom =
                    hostPhysicalRuntime != null &&
                        LocalVirtualListPresentationHostExternal.current &&
                        ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW &&
                        customProvider == null
                if (physicalInternalZoom) {
                    ComposePhysicalVirtualListZoomLayer(
                        snapshot = snapshot,
                        progressProvider = progressProvider,
                        elasticScaleProvider = elasticScaleProvider,
                        playingSongId = scenePlayingSongId,
                        currentPlayingIndex = scenePlayingIndex,
                        hidePlayingCover = sceneHidePlayingCover,
                        sectionHeaderContent = sectionHeaderContent,
                        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                        onSongClick = onSongClick,
                        onSongLongClick = onSongLongClick,
                        sharedCoverSceneId = sharedCoverSceneId,
                        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
                        physicalSlotPool = physicalSlotPool,
                        physicalKeyPrefix = currentPhysicalProviderIdentity,
                        runtime = outerTransitionNodeRuntime,
                        onPopulationPrepared = {
                            state.markTransitionPopulationPrepared(snapshot.pairGeneration)
                            if (virtualListPerfMarkedPairGeneration[0] != snapshot.pairGeneration) {
                                virtualListPerfMarkedPairGeneration[0] = snapshot.pairGeneration
                                TransitionPerfTrace.markAnimateStart(
                                    "kind=virtuallist pair=${snapshot.sourceMode}->${snapshot.targetMode}",
                                )
                            }
                        },
                        contentBindAllowedProvider = {
                            !state.isTransitionPopulationPrepared(snapshot.pairGeneration)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SideEffect {
                        state.markTransitionPopulationPrepared(snapshot.pairGeneration)
                        if (virtualListPerfMarkedPairGeneration[0] != snapshot.pairGeneration) {
                            virtualListPerfMarkedPairGeneration[0] = snapshot.pairGeneration
                            TransitionPerfTrace.markAnimateStart(
                                "kind=virtuallist pair=${snapshot.sourceMode}->${snapshot.targetMode}",
                            )
                        }
                    }
                    ComposeVirtualListTransitionLayer(
                        snapshot = snapshot,
                        progressProvider = progressProvider,
                        elasticScaleProvider = elasticScaleProvider,
                        playingSongId = scenePlayingSongId,
                        currentPlayingIndex = scenePlayingIndex,
                        selectedPositions = selectedPositions,
                        hidePlayingCover = sceneHidePlayingCover,
                        sectionHeaderContent = sectionHeaderContent,
                        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
                        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
                        onSharedCoverTargetChanged = onSharedCoverTargetChanged,
                        onSongClick = onSongClick,
                        onSongLongClick = onSongLongClick,
                        physicalSlotPool = physicalSlotPool,
                        physicalKeyPrefix = currentPhysicalProviderIdentity,
                        physicalHolderOwner = physicalHolderOwner,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (persistentHeaderHeightPx > 0) {
                // Keep the exact scroll read inside the header subtree. During Step 4B the root no
                // longer carries CURRENT GenericPivot because ordinary holders resolve dual LayoutRes
                // themselves, so this still-special header keeps the old role-local transform here.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .referenceProviderPopulationTransform(
                            if (outerDualPhysicalRenderActive) providerCurrentTransform else null
                        )
                ) {
                    ComposeVirtualListPersistentHeader(
                        state = state,
                        transitionSnapshot = transitionSnapshot,
                        staticTopPaddingPx = staticTopPaddingPx,
                        persistentHeaderHeight = persistentHeaderHeight,
                        persistentHeaderVisibilityHeightPx = persistentHeaderVisibilityHeightPx,
                        persistentHeaderSceneItemVisibilityHeightPx = persistentHeaderSceneItemVisibilityHeightPx,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        sharedCoverSceneId = sharedCoverSceneId,
                        persistentHeaderSceneItemId = persistentHeaderSceneItemId,
                        persistentHeaderContent = persistentHeaderContent
                    )
                }
            }
        }
        // Step 4B closes the frame before the union renderer snapshots it. All other paths keep the
        // v35 end-of-owner cleanup point.
        if (!canonicalPhysicalPopulationActive) {
            physicalLayoutSlots.endFrame(physicalLayoutFrameId)
            outerTransitionNodeRuntime.endPowerItemFrame(physicalLayoutFrameId)
        }
    }
    }
}


/**
 * Render the physical body owned by the persistent HOME/category host.
 *
 * Root library pages publish [ReferenceRegisteredVirtualList] from their chrome composition, but
 * they must not own a second ComposeVirtualList body. Keeping this call site outside the page
 * renderer is the important VirtualList boundary: provider changes update the same current/retained
 * layout engine and physical holder pool instead of replacing the list subtree at the endpoint.
 */
@Composable
internal fun ComposeReferenceLibraryProviderBody(
    provider: ReferenceRegisteredVirtualList,
    retainedProvider: ReferenceRegisteredVirtualList?,
    physicalPresentationExternalTransform: RetainedSceneItemTransform? = null,
    physicalPresentationVisibleProvider: () -> Boolean = { true },
    onOuterPopulationPublished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state = provider.state ?: return
    // The provider object is intentionally retained across scene lifetime. Structural revision
    // invalidates prepared physical populations; behavior revision only refreshes the fixed body
    // with new callbacks/playing/content state and is deliberately excluded from prewarm identity.
    provider.revision
    provider.behaviorRevision
    CompositionLocalProvider(
        LocalReferenceLibraryBodyRender provides true,
        LocalReferenceLibraryProviderScene provides provider.scene,
        LocalReferenceLibraryProviderPublicationOnly provides false,
        LocalReferenceRetainedVirtualListProvider provides retainedProvider,
        LocalVirtualListCustomProvider provides provider.customProvider,
    ) {
        ComposeVirtualList(
            songs = provider.songs,
            state = state,
            modifier = modifier,
            playingSongId = provider.playingSongId,
            currentPlayingIndex = provider.currentPlayingIndex,
            selectedPositions = provider.selectedPositions,
            revealIndexRequest = provider.revealIndexRequest,
            hidePlayingCover = provider.hidePlayingCover,
            contentTopPadding = provider.contentTopPadding,
            presentationTopClipInset = provider.presentationTopClipInset,
            persistentHeaderHeight = provider.persistentHeaderHeight,
            persistentHeaderVisibilityHeight = provider.persistentHeaderVisibilityHeight,
            persistentHeaderSceneItemVisibilityHeight = provider.persistentHeaderSceneItemVisibilityHeight,
            persistentHeaderSceneItemId = provider.persistentHeaderSceneItemId,
            virtualListEdgeEnabled = provider.virtualListEdgeEnabled,
            persistentHeaderContent = provider.persistentHeaderContent,
            contentBottomPadding = provider.contentBottomPadding,
            sectionHeaders = provider.sectionHeaders,
            sectionHeaderHeight = provider.sectionHeaderHeight,
            sectionHeaderContent = provider.sectionHeaderContent,
            pinchEnabled = provider.pinchEnabled,
            sharedCoverSceneId = provider.sharedCoverSceneId,
            sharedCoverElementIdProvider = provider.sharedCoverElementIdProvider,
            onPlayingCoverBoundsChanged = provider.onPlayingCoverBoundsChanged,
            onPlayingCoverTargetChanged = provider.onPlayingCoverTargetChanged,
            onSharedCoverTargetChanged = provider.onSharedCoverTargetChanged,
            onRevealCoverTargetResolved = provider.onRevealCoverTargetResolved,
            onScrollActiveChanged = provider.onScrollActiveChanged,
            onSongClick = provider.onSongClick,
            onSongLongClick = provider.onSongLongClick,
            physicalPresentationExternalTransform = physicalPresentationExternalTransform,
            physicalPresentationVisibleProvider = physicalPresentationVisibleProvider,
            onOuterPopulationPublished = onOuterPopulationPublished,
        )
    }
}


/** Apply the shared HOME/category affine once per provider population instead of once per holder. */
private fun Modifier.referenceProviderPopulationTransform(
    transform: RetainedSceneItemTransform?,
): Modifier = if (transform == null) {
    this
} else {
    graphicsLayer {
        val scale = transform.scaleProvider().coerceIn(0.5f, 1.5f)
        alpha = transform.alphaProvider().coerceIn(0f, 1f)
        scaleX = scale
        scaleY = scale
        // Top-left origin plus P*(1-s) is the exact affine for the page-space pivot supplied by
        // GenericPivot.
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = transform.pivotX * (1f - scale)
        translationY = transform.pivotY * (1f - scale)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/**
 * Retained provider/layout population for outer HOME/category GenericPivot.
 *
 * This is deliberately a population inside the current ComposeVirtualList root, not a second
 * ComposeVirtualList or a retained page composition. Both providers resolve physical slots from the
 * same [VirtualListPhysicalSlotPool], matching one VirtualList with current/retained `r` layouts.
 */
@Composable
private fun ComposeReferenceRetainedProviderPopulation(
    provider: ReferenceRegisteredVirtualList,
    transform: RetainedSceneItemTransform?,
    widthPx: Int,
    heightPx: Int,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalHolderOwner: VirtualListPhysicalHolderOwner,
    physicalLayoutSlots: VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>,
    physicalLayoutFrameId: Int,
    ordinaryPublicationOnly: Boolean,
) {
    val retainedState = provider.state ?: return
    val density = LocalDensity.current
    val bottomInsets = LocalBottomChromeInsets.current
    val customProvider = provider.customProvider
    val metrics = remember(
        widthPx,
        density.density,
        density.fontScale,
        retainedState.renderMode,
        retainedState.currentParams,
    ) {
        computeVirtualListMetrics(
            widthPx = widthPx,
            density = density.density,
            scaledDensity = density.density * density.fontScale,
            mode = retainedState.renderMode,
            params = retainedState.currentParams,
        )
    }
    val itemHeightsPx = remember(provider.songs, customProvider, metrics.cellHeightPx, density.density) {
        val custom = customProvider ?: return@remember null
        var hasCustom = false
        IntArray(provider.songs.size) { index ->
            val song = provider.songs[index]
            val height = if (custom.handles(song, index)) custom.itemHeight(song, index) else null
            if (height != null) {
                hasCustom = true
                with(density) { height.roundToPx() }.coerceAtLeast(1)
            } else {
                metrics.cellHeightPx.coerceAtLeast(1)
            }
        }.takeIf { hasCustom }
    }
    val bottomPaddingPx = with(density) {
        (provider.contentBottomPadding ?: bottomInsets.contentBottom).roundToPx()
    }
    val persistentHeaderHeightPx = with(density) { provider.persistentHeaderHeight.roundToPx() }
    val topPaddingPx = with(density) { provider.contentTopPadding.roundToPx() } + persistentHeaderHeightPx
    val sectionHeaderHeightPx = with(density) { provider.sectionHeaderHeight.roundToPx() }
    val naturalMaxScroll = maxScrollForContent(
        itemCount = provider.songs.size,
        metrics = metrics,
        viewportHeightPx = heightPx,
        bottomPaddingPx = bottomPaddingPx,
        topPaddingPx = topPaddingPx,
        sectionHeaders = provider.sectionHeaders,
        sectionHeaderHeightPx = sectionHeaderHeightPx,
        itemHeightsPx = itemHeightsPx,
    )
    val maxScrollY = maxScrollForPersistentHeaderContent(naturalMaxScroll).coerceAtLeast(0)
    val exactLane = itemHeightsPx != null || usesExactPersistentHeaderScrollLane(
        persistentHeaderHeightPx = persistentHeaderHeightPx,
        itemCount = provider.songs.size,
        naturalMaxScrollPx = naturalMaxScroll,
    )
    val rowStride = metrics.cellHeightPx.coerceAtLeast(1)
    val rememberedRetainedScroll = retainedState.viewportScrollY.roundToInt().coerceAtLeast(0)
    val rawScroll = if (rememberedRetainedScroll > 0 && maxScrollY <= 0) {
        // Scene navigation promotes an already-existing LayoutRes. A one-frame zero content extent
        // must not redraw that retained provider at the top while its real geometry is being
        // republished; the settled owner performs the authoritative delayed clamp if zero is real.
        rememberedRetainedScroll
    } else {
        rememberedRetainedScroll.coerceIn(0, maxScrollY)
    }
    val baseScroll = if (exactLane) rawScroll else (rawScroll / rowStride) * rowStride
    val remainder = rawScroll - baseScroll
    val noopBounds = remember { { _: RectF? -> } }
    val noopTarget = remember { { _: CoverTransitionTarget? -> } }
    val noopClick = remember { { _: AudioFile, _: Int -> } }

    CompositionLocalProvider(
        LocalVirtualListCustomProvider provides customProvider,
        LocalReferenceRetainedLibraryPopulation provides true,
    ) {
        ComposeVirtualListSettledContent(
            songs = provider.songs,
            state = retainedState,
            metrics = metrics,
            scrollYPx = baseScroll,
            scrollRemainderProvider = { if (exactLane) 0 else remainder },
            viewportHeightPx = heightPx,
            playingSongId = provider.playingSongId,
            currentPlayingIndex = provider.currentPlayingIndex,
            selectedPositions = provider.selectedPositions,
            hidePlayingCover = provider.hidePlayingCover,
            boundaryScaleProvider = { 1f },
            exactPixelScrollLane = exactLane,
            // Keep the physical retained ring stable across promotion; provider-population clipping
            // owns the viewport, so there is no need to tear down buffer holders at scene start.
            strictSceneHolderWindow = false,
            interactionActive = true,
            // baseline implementation ArtworkImageNode keeps wrapper reveal/replacement independent from the
            // GenericPivot parent transform. A retained-only holder may become visible while its
            // request completes, so never globally disable its local 200ms image clock.
            artworkAnimationsEnabled = true,
            settledMarqueeVisible = true,
            topPaddingPx = topPaddingPx,
            itemHeightsPx = itemHeightsPx,
            sectionHeaders = provider.sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            sectionHeaderContent = provider.sectionHeaderContent,
            sharedCoverSceneId = provider.sharedCoverSceneId,
            sharedCoverElementIdProvider = provider.sharedCoverElementIdProvider,
            onPlayingCoverBoundsChanged = noopBounds,
            onPlayingCoverTargetChanged = noopTarget,
            onSharedCoverTargetChanged = { _, _, _ -> },
            onSongClick = noopClick,
            onSongLongClick = { _, _ -> },
            physicalSlotPool = physicalSlotPool,
            physicalHolderOwner = physicalHolderOwner,
            physicalLayoutSlots = physicalLayoutSlots,
            physicalLayoutFrameId = physicalLayoutFrameId,
            physicalLayoutRole = VirtualListPhysicalLayoutRole.RETAINED,
            physicalKeyPrefix = provider.physicalPoolIdentity,
            appendPhysicalFrame = false,
            deferPhysicalCleanup = true,
            ordinaryPublicationOnly = ordinaryPublicationOnly,
            suppressHolderRenderingForOuterDual = ordinaryPublicationOnly,
            sceneTransform = null,
            modifier = Modifier
                .fillMaxSize()
                .referenceProviderPopulationTransform(transform),
        )

        // Header->Item transitions are still one VirtualList transition in the baseline implementation.  The
        // destination detail hero/header belongs to the retained LayoutRes just like its body rows;
        // it must therefore exist during the transition rather than appearing only when the target
        // page becomes CURRENT at the endpoint.  Keeping it here also publishes the destination
        // shared-cover anchor while awaitSharedLayouts() is waiting, so FolderSharedForward does not
        // silently downgrade to Generic on every collection-detail entry.
        if (persistentHeaderHeightPx > 0) {
            val staticTopPaddingPx = with(density) { provider.contentTopPadding.roundToPx() }
            val persistentHeaderVisibilityHeightPx = with(density) {
                provider.persistentHeaderVisibilityHeight.roundToPx()
            }
            val persistentHeaderSceneItemVisibilityHeightPx = with(density) {
                provider.persistentHeaderSceneItemVisibilityHeight.roundToPx()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .referenceProviderPopulationTransform(transform),
            ) {
                ComposeVirtualListPersistentHeader(
                    state = retainedState,
                    transitionSnapshot = null,
                    staticTopPaddingPx = staticTopPaddingPx,
                    persistentHeaderHeight = provider.persistentHeaderHeight,
                    persistentHeaderVisibilityHeightPx = persistentHeaderVisibilityHeightPx,
                    persistentHeaderSceneItemVisibilityHeightPx = persistentHeaderSceneItemVisibilityHeightPx,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    sharedCoverSceneId = provider.sharedCoverSceneId,
                    persistentHeaderSceneItemId = provider.persistentHeaderSceneItemId,
                    persistentHeaderContent = provider.persistentHeaderContent,
                )
            }
        }
    }
}


/**
 * Prepare the destination persistent header without publishing it as the visible presentation.
 *
 * Collection detail pages publish only provider metadata during the hidden scene preflight.  Their
 * hero/header is the shared-item target, so omitting it means no target anchor exists until after
 * the route commit.  This lightweight preparation composes exactly the header geometry in the
 * alpha-zero preparation slot; body rows are warmed separately by
 * [ComposeReferencePreparedOrdinaryPopulation].
 */
@Composable
internal fun ComposeReferencePreparedPersistentHeader(
    provider: ReferenceRegisteredVirtualList,
    modifier: Modifier = Modifier,
) {
    val state = provider.state ?: return
    if (provider.persistentHeaderHeight <= 0.dp) return
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        if (widthPx <= 0 || heightPx <= 0) return@BoxWithConstraints

        val staticTopPaddingPx = with(density) { provider.contentTopPadding.roundToPx() }
        val persistentHeaderVisibilityHeightPx = with(density) {
            provider.persistentHeaderVisibilityHeight.roundToPx()
        }
        val persistentHeaderSceneItemVisibilityHeightPx = with(density) {
            provider.persistentHeaderSceneItemVisibilityHeight.roundToPx()
        }
        CompositionLocalProvider(LocalReferenceRetainedLibraryPopulation provides true) {
            ComposeVirtualListPersistentHeader(
                state = state,
                transitionSnapshot = null,
                staticTopPaddingPx = staticTopPaddingPx,
                persistentHeaderHeight = provider.persistentHeaderHeight,
                persistentHeaderVisibilityHeightPx = persistentHeaderVisibilityHeightPx,
                persistentHeaderSceneItemVisibilityHeightPx = persistentHeaderSceneItemVisibilityHeightPx,
                widthPx = widthPx,
                heightPx = heightPx,
                sharedCoverSceneId = provider.sharedCoverSceneId,
                persistentHeaderSceneItemId = provider.persistentHeaderSceneItemId,
                persistentHeaderContent = provider.persistentHeaderContent,
            )
        }
    }
}


/**
 * Precompose only the visible heterogeneous destination holders during SceneTransitionEngine's
 * alpha-zero preparation pass.
 *
 * Reference has the target ArtworkItemNode objects bound before GenericPivot starts. Raw's old
 * publication-only preflight registered provider metadata but first composed HOME's rich custom
 * holders on the first drag frame. Those holders contain hero/search/carousel/cards and can consume
 * a complete 120 Hz budget even though the animation itself is only a RenderNode property update.
 *
 * This warm population uses the *same* persistent physical-holder owner and stable custom keys as
 * the later retained/current population. movableContentOf therefore moves the already-composed
 * holder nodes into the live transition instead of recreating them. It never publishes settled or
 * outer Node presentation state and never closes the visible source provider's slot frame.
 */
@Composable
internal fun ComposeReferencePreparedCustomPopulation(
    provider: ReferenceRegisteredVirtualList,
    runtime: VirtualListPersistentRuntime,
    modifier: Modifier = Modifier,
) {
    val customProvider = provider.customProvider ?: return
    val state = provider.state ?: return
    val density = LocalDensity.current
    val bottomInsets = LocalBottomChromeInsets.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        if (widthPx <= 0 || heightPx <= 0 || provider.songs.isEmpty()) return@BoxWithConstraints

        val metrics = remember(
            widthPx,
            density.density,
            density.fontScale,
            state.renderMode,
            state.currentParams,
        ) {
            computeVirtualListMetrics(
                widthPx = widthPx,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
                mode = state.renderMode,
                params = state.currentParams,
            )
        }
        val itemHeightsPx = remember(provider.songs, customProvider, metrics.cellHeightPx, density.density) {
            IntArray(provider.songs.size) { index ->
                val song = provider.songs[index]
                val customHeight = if (customProvider.handles(song, index)) {
                    customProvider.itemHeight(song, index)
                } else {
                    null
                }
                customHeight?.let { with(density) { it.roundToPx() }.coerceAtLeast(1) }
                    ?: metrics.cellHeightPx.coerceAtLeast(1)
            }
        }
        val bottomPaddingPx = with(density) {
            (provider.contentBottomPadding ?: bottomInsets.contentBottom).roundToPx()
        }
        val persistentHeaderHeightPx = with(density) { provider.persistentHeaderHeight.roundToPx() }
        val topPaddingPx = with(density) { provider.contentTopPadding.roundToPx() } + persistentHeaderHeightPx
        val sectionHeaderHeightPx = with(density) { provider.sectionHeaderHeight.roundToPx() }
        val geometry = remember(
            metrics,
            bottomPaddingPx,
            topPaddingPx,
            provider.songs.size,
            provider.sectionHeaders,
            sectionHeaderHeightPx,
            itemHeightsPx,
        ) {
            listGeometryFor(
                metrics = metrics,
                bottomPaddingPx = bottomPaddingPx,
                topPaddingPx = topPaddingPx,
                itemCount = provider.songs.size,
                sectionHeaders = provider.sectionHeaders,
                sectionHeaderHeightPx = sectionHeaderHeightPx,
                itemHeightsPx = itemHeightsPx,
            )
        }
        val naturalMaxScroll = maxScrollForContent(
            itemCount = provider.songs.size,
            metrics = metrics,
            viewportHeightPx = heightPx,
            bottomPaddingPx = bottomPaddingPx,
            topPaddingPx = topPaddingPx,
            sectionHeaders = provider.sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            itemHeightsPx = itemHeightsPx,
        )
        val rawScroll = state.viewportScrollY.roundToInt()
            .coerceIn(0, maxScrollForPersistentHeaderContent(naturalMaxScroll).coerceAtLeast(0))
        val visibleRange = visibleRangeForScroll(
            itemCount = provider.songs.size,
            mode = state.currentMode,
            geometry = geometry,
            scrollYPx = rawScroll,
            viewportHeightPx = heightPx,
        )
        if (visibleRange.isEmpty()) return@BoxWithConstraints

        val warmIndices: List<Int> = remember(visibleRange, provider.songs, customProvider) {
            buildList<Int> {
                for (index in visibleRange.first..visibleRange.last) {
                    val song = provider.songs.getOrNull(index) ?: continue
                    if (customProvider.handles(song, index)) add(index)
                }
            }
        }
        if (warmIndices.isEmpty()) return@BoxWithConstraints
        val warmKeys: List<Any> = remember(warmIndices, provider.songs, provider) {
            warmIndices.mapNotNull { index: Int ->
                provider.songs.getOrNull(index)?.let { song: AudioFile ->
                    virtualListPhysicalRenderKey(provider, song, index, customHandled = true)
                }
            }
        }
        runtime.physicalSlotPool.reserveKeys(warmKeys)
        DisposableEffect(runtime, warmKeys) {
            onDispose { runtime.physicalSlotPool.releaseReservedKeys(warmKeys) }
        }
        val warmPublications = buildList {
            for (index in warmIndices) {
                val song = provider.songs.getOrNull(index) ?: continue
                val key = virtualListPhysicalRenderKey(provider, song, index, customHandled = true)
                val slotId = runtime.physicalSlotPool.slotIdForKey(key)
                if (slotId < 0) continue
                val position = positionFor(
                    index = index,
                    geometry = geometry,
                    mode = state.currentMode,
                    scrollYPx = rawScroll,
                )
                if (position.isEmpty()) continue
                add(
                    VirtualListPowerCustomRolePublication(
                        slotId = slotId,
                        binding = VirtualListPowerCustomBinding(
                            song = song,
                            index = index,
                            provider = customProvider,
                        ),
                        layout = VirtualListPhysicalLayoutRes(
                            index = index,
                            mode = state.currentMode,
                            params = state.currentParams,
                            position = position,
                            drawContentEnabled = true,
                            artworkVisible = true,
                            renderRemainderPx = 0,
                        ),
                    )
                )
            }
        }
        val prewarmKey = VirtualListPrewarmKey(
            structuralRevision = provider.revision,
            rangeStart = visibleRange.first,
            rangeEnd = visibleRange.last,
            modeOrdinal = state.currentMode.ordinal,
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            scrollYPx = rawScroll,
            custom = true,
        )
        SideEffect {
            runtime.settledNodeRuntime.prewarmDetachedCustomPopulation(
                publications = warmPublications,
                prewarmKey = prewarmKey,
            )
        }
    }
}

/**
 * Pre-bind ordinary destination holders during the hidden provider-preflight frames.
 *
 * Unlike [ComposeReferencePreparedCustomPopulation], this path never composes item UI. It reserves
 * the same scene-independent physical slots used by the live retained population and asks the
 * host-owned Node runtime to prepare text/artwork/display lists without publishing a presentation.
 * The visible source therefore remains untouched while the first GenericPivot no longer pays the
 * cold ordinary-holder construction cost on its animated frames.
 */
@Composable
internal fun ComposeReferencePreparedOrdinaryPopulation(
    provider: ReferenceRegisteredVirtualList,
    runtime: VirtualListPersistentRuntime,
    onMaterialized: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state = provider.state ?: return
    val density = LocalDensity.current
    val context = LocalContext.current
    val bottomInsets = LocalBottomChromeInsets.current
    val customProvider = provider.customProvider

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        if (widthPx <= 0 || heightPx <= 0 || provider.songs.isEmpty()) return@BoxWithConstraints

        val metrics = remember(
            widthPx,
            density.density,
            density.fontScale,
            state.renderMode,
            state.currentParams,
        ) {
            computeVirtualListMetrics(
                widthPx = widthPx,
                density = density.density,
                scaledDensity = density.density * density.fontScale,
                mode = state.renderMode,
                params = state.currentParams,
            )
        }
        val itemHeightsPx = remember(provider.songs, customProvider, metrics.cellHeightPx, density.density) {
            customProvider?.let { custom ->
                IntArray(provider.songs.size) { index ->
                    val song = provider.songs[index]
                    val customHeight = if (custom.handles(song, index)) {
                        custom.itemHeight(song, index)
                    } else {
                        null
                    }
                    customHeight
                        ?.let { with(density) { it.roundToPx() }.coerceAtLeast(1) }
                        ?: metrics.cellHeightPx.coerceAtLeast(1)
                }
            }
        }
        val bottomPaddingPx = with(density) {
            (provider.contentBottomPadding ?: bottomInsets.contentBottom).roundToPx()
        }
        val persistentHeaderHeightPx = with(density) { provider.persistentHeaderHeight.roundToPx() }
        val topPaddingPx = with(density) { provider.contentTopPadding.roundToPx() } + persistentHeaderHeightPx
        val sectionHeaderHeightPx = with(density) { provider.sectionHeaderHeight.roundToPx() }
        val geometry = remember(
            metrics,
            bottomPaddingPx,
            topPaddingPx,
            provider.songs.size,
            provider.sectionHeaders,
            sectionHeaderHeightPx,
            itemHeightsPx,
        ) {
            listGeometryFor(
                metrics = metrics,
                bottomPaddingPx = bottomPaddingPx,
                topPaddingPx = topPaddingPx,
                itemCount = provider.songs.size,
                sectionHeaders = provider.sectionHeaders,
                sectionHeaderHeightPx = sectionHeaderHeightPx,
                itemHeightsPx = itemHeightsPx,
            )
        }
        val naturalMaxScroll = maxScrollForContent(
            itemCount = provider.songs.size,
            metrics = metrics,
            viewportHeightPx = heightPx,
            bottomPaddingPx = bottomPaddingPx,
            topPaddingPx = topPaddingPx,
            sectionHeaders = provider.sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            itemHeightsPx = itemHeightsPx,
        )
        val rawScroll = state.viewportScrollY.roundToInt()
            .coerceIn(0, maxScrollForPersistentHeaderContent(naturalMaxScroll).coerceAtLeast(0))
        val visibleRange = visibleRangeForScroll(
            itemCount = provider.songs.size,
            mode = state.currentMode,
            geometry = geometry,
            scrollYPx = rawScroll,
            viewportHeightPx = heightPx,
        )
        if (visibleRange.isEmpty()) return@BoxWithConstraints
        val warmRange = expandRangeByRows(
            range = visibleRange,
            itemCount = provider.songs.size,
            columns = geometry.columns,
            rowsBefore = 1,
            rowsAfter = 1,
        )
        if (warmRange.isEmpty()) return@BoxWithConstraints

        val warmIndices = remember(warmRange, provider.songs, customProvider) {
            buildList {
                for (index in warmRange.first..warmRange.last) {
                    val song = provider.songs.getOrNull(index) ?: continue
                    if (customProvider?.handles?.invoke(song, index) == true) continue
                    add(index)
                }
            }
        }
        if (warmIndices.isEmpty()) return@BoxWithConstraints
        val warmKeys = remember(warmIndices, provider.songs, provider) {
            warmIndices.mapNotNull { index ->
                provider.songs.getOrNull(index)?.let { song ->
                    virtualListPhysicalRenderKey(provider, song, index, customHandled = false)
                }
            }
        }
        runtime.physicalSlotPool.reserveKeys(warmKeys)
        DisposableEffect(runtime, warmKeys) {
            onDispose { runtime.physicalSlotPool.releaseReservedKeys(warmKeys) }
        }

        val nodeItems = remember(
            warmIndices,
            visibleRange,
            provider.songs,
            provider.playingSongId,
            provider.currentPlayingIndex,
            provider.hidePlayingCover,
            state.currentMode,
            state.currentParams,
            geometry,
            rawScroll,
            density.density,
        ) {
            buildList {
                for (index in warmIndices) {
                    val song = provider.songs.getOrNull(index) ?: continue
                    val slotId = runtime.physicalSlotPool.slotIdForKey(
                        virtualListPhysicalRenderKey(provider, song, index, customHandled = false)
                    )
                    if (slotId < 0) continue
                    val position = positionFor(
                        index = index,
                        geometry = geometry,
                        mode = state.currentMode,
                        scrollYPx = rawScroll,
                    )
                    if (position.isEmpty()) continue
                    val rects = composeArtworkItemSceneRects(
                        position,
                        state.currentMode,
                        state.currentParams,
                        density.density,
                        song.virtualListArtworkRadiusType(),
                    )
                    val artworkVisible = index in visibleRange
                    val isPlaying = if (provider.playingSongId > 0L) {
                        song.id == provider.playingSongId
                    } else {
                        index == provider.currentPlayingIndex
                    }
                    add(
                        VirtualListSettledNodeItem(
                            slotId = slotId,
                            song = song,
                            index = index,
                            position = position,
                            rects = rects,
                            deferBitmapLoad = !artworkVisible,
                            // Warm rows are not published, so allow child recording for the one-row
                            // retained ring as well. Live draw eligibility is rebound at transition.
                            drawContentEnabled = true,
                            isPlaying = isPlaying,
                            hideCover = provider.hidePlayingCover && isPlaying,
                            interactionActive = true,
                            artworkAnimationsEnabled = false,
                            artworkPriority = if (artworkVisible) {
                                BitmapRequest.Priority.LOADING_LIST
                            } else {
                                BitmapRequest.Priority.LOADING_PREFETCH
                            },
                            coverDecodeSide = virtualListDecodeSideForMode(
                                max(rects.cover.width, rects.cover.height).coerceAtLeast(1)
                            ),
                            hasCollectionMetaIcon = song.hasVirtualListCollectionMetaIcon(),
                            hasFolderMetaIcon = song.encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING,
                            isGrid = state.currentMode.isGrid,
                        )
                    )
                }
            }
        }
        val warmFrame = VirtualListSettledNodeFrame(
            items = nodeItems,
            density = density.density,
            textDensity = density.density * density.fontScale,
            dark = ThemeManager.isDarkMode(context),
            configuredTypeface = FontManager.typeface,
            resources = context.resources,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
            // Prepare the same unellipsized/marquee-capable text layout the live holder will use.
            // A false value here changes PreparedSignature.animateText and forces all three text
            // lanes to be shaped again on the first animated frame, defeating the cold prewarm.
            marqueeAllowed = true,
            scrollRemainderProvider = { 0 },
            scrollInvalidationOwner = null,
            onPlayingCoverBoundsChanged = {},
            onPlayingCoverTargetChanged = {},
            onSongClick = { _, _ -> },
            onSongLongClick = { _, _ -> },
            onCopySongInfo = {},
        )
        val prewarmKey = VirtualListPrewarmKey(
            structuralRevision = provider.revision,
            rangeStart = warmRange.first,
            rangeEnd = warmRange.last,
            modeOrdinal = state.currentMode.ordinal,
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            scrollYPx = rawScroll,
            custom = false,
        )
        SideEffect {
            runtime.settledNodeRuntime.prewarmDetachedFrame(
                frame = warmFrame,
                prewarmKey = prewarmKey,
                onMaterialized = onMaterialized,
            )
        }
    }
}

/**
 * Step 4B ordinary HOME/category holder union.
 *
 * CURRENT and RETAINED providers publish raw LayoutRes into [physicalLayoutSlots]. Custom HOME/header
 * holder types stay in their legacy provider populations; every ordinary song key is rendered here
 * exactly once from the scene-independent physical key. Shared keys consume both LayoutRes slots,
 * while one-sided keys keep the old role-local GenericPivot transform.
 */
@Composable
private fun ComposeVirtualListOuterDualPhysicalPopulation(
    currentSongs: List<AudioFile>,
    currentPlayingSongId: Long,
    currentPlayingIndex: Int,
    currentSelectedPositions: Set<Int>,
    currentHidePlayingCover: Boolean,
    currentCustomProvider: VirtualListCustomProvider?,
    currentPhysicalKeyPrefix: Any?,
    currentSharedCoverSceneId: String,
    currentSharedCoverElementIdProvider: (AudioFile, Int) -> String,
    retainedProvider: ReferenceRegisteredVirtualList?,
    currentTransform: RetainedSceneItemTransform?,
    retainedTransform: RetainedSceneItemTransform?,
    physicalLayoutSlots: VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalHolderOwner: VirtualListPhysicalHolderOwner,
    settledNodeRuntime: VirtualListSettledNodeRuntime,
    currentScrollOwner: VirtualListScrollPositionOwner,
    currentLayoutScrollY: Int,
    onPopulationPublished: (() -> Unit)?,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    ordinaryOwnedByRuntime: Boolean = false,
) {
    data class Entry(
        val key: Any,
        val currentLayout: VirtualListPhysicalLayoutRes?,
        val retainedLayout: VirtualListPhysicalLayoutRes?,
        val currentSong: AudioFile?,
        val retainedSong: AudioFile?,
        val currentCustomProvider: VirtualListCustomProvider?,
        val retainedCustomProvider: VirtualListCustomProvider?,
    )

    data class ResolvedRole(
        val song: AudioFile,
        val customProvider: VirtualListCustomProvider?,
    )

    data class ResolvedEntry(
        val entry: Entry,
        val slotId: Int,
        val song: AudioFile,
        val index: Int,
        val useCurrentRole: Boolean,
        val isPlaying: Boolean,
        val hideCover: Boolean,
        val selected: Set<Int>,
        val nodeEligible: Boolean,
    )

    val context = LocalContext.current
    val density = LocalDensity.current
    val sharedTransitionSpec = LocalSharedTransitionSpec.current
    val sharedCoverRegistry = LocalSharedCoverRegistry.current
    val ownedSharedElementId = if (sharedTransitionSpec.active) {
        val candidate = sharedTransitionSpec.ownedElementId
            .takeIf { it.isNotBlank() }
            ?: sharedCoverRegistry.getPreparedPair(sharedTransitionSpec.transitionKey)
                ?.first
                ?.elementId
                .orEmpty()
        candidate.takeIf(sharedCoverRegistry::isPhysicalPromotedElement).orEmpty()
    } else {
        ""
    }
    val entries = buildList {
        physicalLayoutSlots.snapshots().forEach { snapshot ->
            val currentRole = snapshot.current?.let { layout ->
                val song = currentSongs.getOrNull(layout.index) ?: return@let null
                val custom = currentCustomProvider?.takeIf { it.handles(song, layout.index) }
                val expectedKey = virtualListPhysicalRenderKey(
                    prefix = currentPhysicalKeyPrefix,
                    song = song,
                    index = layout.index,
                    customHandled = custom != null,
                )
                if (expectedKey == snapshot.key) ResolvedRole(song, custom) else null
            }
            val retainedRole = snapshot.retained?.let { layout ->
                val retained = retainedProvider ?: return@let null
                val song = retained.songs.getOrNull(layout.index) ?: return@let null
                val custom = retained.customProvider?.takeIf { it.handles(song, layout.index) }
                val expectedKey = virtualListPhysicalRenderKey(
                    prefix = retained,
                    song = song,
                    index = layout.index,
                    customHandled = custom != null,
                )
                if (expectedKey == snapshot.key) ResolvedRole(song, custom) else null
            }
            if (currentRole == null && retainedRole == null) return@forEach
            add(
                Entry(
                    key = snapshot.key,
                    currentLayout = snapshot.current,
                    retainedLayout = snapshot.retained,
                    currentSong = currentRole?.song,
                    retainedSong = retainedRole?.song,
                    currentCustomProvider = currentRole?.customProvider,
                    retainedCustomProvider = retainedRole?.customProvider,
                )
            )
        }
    }

    // CURRENT and RETAINED publish the same physical keys used by their real holders. Ordinary rows
    // use scene-independent keys, while heterogeneous HOME holders keep provider-specific keys.
    // Append the complete attached population and close once after every role has published.
    physicalSlotPool.appendFrame(entries.map { it.key }, closeFrame = true)
    val currentFractionProvider = remember(
        currentTransform,
        retainedTransform,
        retainedProvider,
    ) {
        {
            if (retainedProvider == null) 1f
            else virtualListOuterCurrentLayoutFraction(currentTransform, retainedTransform)
        }
    }
    val noSelection = currentSelectedPositions.isEmpty() &&
        (retainedProvider?.selectedPositions?.isEmpty() != false)
    val resolvedEntries = buildList {
        entries.forEach { entry ->
            val currentLayout = entry.currentLayout
            val retainedLayout = entry.retainedLayout
            val useCurrentRole = currentLayout != null
            val song = entry.currentSong ?: entry.retainedSong ?: return@forEach
            val index = currentLayout?.index ?: retainedLayout?.index ?: return@forEach
            val slotId = physicalSlotPool.slotIdForKey(entry.key)
            if (slotId < 0) return@forEach
            val playingSongId = if (useCurrentRole) currentPlayingSongId else retainedProvider?.playingSongId ?: 0L
            val playingIndex = if (useCurrentRole) currentPlayingIndex else retainedProvider?.currentPlayingIndex ?: -1
            val selected = if (useCurrentRole) currentSelectedPositions else retainedProvider?.selectedPositions.orEmpty()
            val isPlaying = if (playingSongId > 0L) song.id == playingSongId else index == playingIndex
            val roleSharedElementId = if (useCurrentRole) {
                currentSharedCoverElementIdProvider(song, index)
            } else {
                retainedProvider?.sharedCoverElementIdProvider?.invoke(song, index).orEmpty()
            }
            val hideCover =
                ((if (useCurrentRole) currentHidePlayingCover else retainedProvider?.hidePlayingCover == true) && isPlaying) ||
                    (ownedSharedElementId.isNotBlank() &&
                        roleSharedElementId == ownedSharedElementId)
            val kind = virtualListOuterDualLayoutKind(currentLayout, retainedLayout)
            // A shared holder can stay entirely inside the retained physical Node when its visual
            // topology is identical at both endpoints. LIST<->GRID child-layout morphs keep the old
            // compatibility renderer until their endpoint child RenderNodes can be interpolated
            // without approximating Reference. One-sided GenericPivot holders are always eligible.
            val compatibleSharedTopology = currentLayout != null && retainedLayout != null &&
                currentLayout.mode == retainedLayout.mode && currentLayout.params == retainedLayout.params
            val customHolder = entry.currentCustomProvider != null || entry.retainedCustomProvider != null
            if (ordinaryOwnedByRuntime && !customHolder) return@forEach
            // In settled mode the ordinary Android/Node population is already authoritative. This
            // canonical Compose lane exists solely so heterogeneous HOME holders never move back to
            // the old settled fallback call site after GenericPivot.
            if (retainedProvider == null && !customHolder) return@forEach
            // A shared-cover transition owns exactly one artwork element, not the complete list.
            // VirtualList keeps every other attached holder in the same retained LayoutRes/RenderNode
            // population while the shared item overlay temporarily owns that cover.  The old global
            // `!sharedTransitionSpec.active` gate pushed *all* ordinary rows back through the Compose
            // compatibility renderer for collection <-> detail motion.  Besides doubling work at the
            // hottest point of the transition, that renderer used the overscan row outside the native
            // population clip, which is the source of the extra/cropped top item seen in motion.
            //
            // The reference ItemToHeader/HeaderToItem path has exactly one concrete item-holder owner. Once
            // Shared-holder migration detaches the source physical child; on return the destination slot stays
            // artwork-empty until P(..., true) reattaches the same View. Mirror that ownership here:
            // the ordinary CURRENT/RETAINED list artwork for the shared element is hidden for the
            // entire transaction, including physical promotion. restoreSharedHolderPromotion()
            // publishes ordinary ownership before the host reattaches the promoted child, and the
            // transition spec is then retired, so there is no endpoint gap or duplicate cover.
            val nodeEligible = retainedProvider != null && VirtualListOuterTransitionNodePolicy.eligible(
                kind = kind,
                sameVisualTopology = compatibleSharedTopology,
                selectionActive = !noSelection,
                customHolder = customHolder,
            )
            add(
                ResolvedEntry(
                    entry = entry,
                    slotId = slotId,
                    song = song,
                    index = index,
                    useCurrentRole = useCurrentRole,
                    isPlaying = isPlaying,
                    hideCover = hideCover,
                    selected = selected,
                    nodeEligible = nodeEligible,
                )
            )
        }
    }

    val nodeResolved = resolvedEntries.filter { it.nodeEligible }
    SideEffect {
        TransitionPerfTrace.count(TransitionPerfEvent.NATIVE_ITEMS_PUBLISHED, nodeResolved.size.toLong())
        TransitionPerfTrace.count(TransitionPerfEvent.COMPAT_ITEMS_PUBLISHED, (resolvedEntries.size - nodeResolved.size).toLong())
    }
    if (nodeResolved.isEmpty() && retainedProvider != null) {
        // Empty/custom-only providers have no Android holder frame to acknowledge. Their layout
        // boundary is still a valid prepared population; do not strand navigation waiting for rows.
        Box(Modifier.fillMaxSize().onGloballyPositioned { onPopulationPublished?.invoke() })
    }
    if (nodeResolved.isNotEmpty()) {
        val unorderedNodeItems = nodeResolved.map { resolved ->
            val current = resolved.entry.currentLayout
            val retained = resolved.entry.retainedLayout
            val contentLayout = current ?: checkNotNull(retained)
            val position = contentLayout.exactViewportPosition()
            val rects = composeArtworkItemSceneRects(
                position,
                contentLayout.mode,
                contentLayout.params,
                density.density,
                resolved.song.virtualListArtworkRadiusType(),
            )
            val artworkVisible = current?.artworkVisible == true || retained?.artworkVisible == true
            val holderItem = VirtualListSettledNodeItem(
                slotId = resolved.slotId,
                song = resolved.song,
                index = resolved.index,
                position = position,
                rects = rects,
                deferBitmapLoad = !artworkVisible,
                drawContentEnabled = current?.drawContentEnabled == true || retained?.drawContentEnabled == true,
                isPlaying = resolved.isPlaying,
                hideCover = resolved.hideCover,
                interactionActive = false,
                artworkAnimationsEnabled = true,
                artworkPriority = if (artworkVisible) {
                    BitmapRequest.Priority.LOADING_LIST
                } else {
                    BitmapRequest.Priority.LOADING_PREFETCH
                },
                coverDecodeSide = virtualListDecodeSideForMode(
                    max(rects.cover.width, rects.cover.height).coerceAtLeast(1)
                ),
                hasCollectionMetaIcon = resolved.song.hasVirtualListCollectionMetaIcon(),
                hasFolderMetaIcon = resolved.song.encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING,
                isGrid = contentLayout.mode.isGrid,
                sharedCoverElementId = if (resolved.useCurrentRole) {
                    currentSharedCoverElementIdProvider(resolved.song, resolved.index)
                } else {
                    retainedProvider?.sharedCoverElementIdProvider?.invoke(resolved.song, resolved.index).orEmpty()
                },
            )
            val motionKind = when (virtualListOuterDualLayoutKind(current, retained)) {
                VirtualListOuterDualLayoutKind.SHARED -> VirtualListOuterTransitionKind.SHARED
                VirtualListOuterDualLayoutKind.CURRENT_ONLY -> VirtualListOuterTransitionKind.CURRENT_ONLY
                VirtualListOuterDualLayoutKind.RETAINED_ONLY -> VirtualListOuterTransitionKind.RETAINED_ONLY
                null -> error("unreachable outer transition layout kind")
            }
            VirtualListOuterTransitionNodeItem(
                holderItem = holderItem,
                kind = motionKind,
                currentPosition = current?.exactViewportPosition(),
                retainedPosition = retained?.exactViewportPosition(),
                currentRects = current?.let { layout ->
                    composeArtworkItemSceneRects(
                        layout.exactViewportPosition(),
                        layout.mode,
                        layout.params,
                        density.density,
                        resolved.song.virtualListArtworkRadiusType(),
                    )
                },
                retainedRects = retained?.let { layout ->
                    composeArtworkItemSceneRects(
                        layout.exactViewportPosition(),
                        layout.mode,
                        layout.params,
                        density.density,
                        resolved.song.virtualListArtworkRadiusType(),
                    )
                },
            )
        }
        // Reference's layout manager walks the already ordered physical holder population once per
        // frame. Preserve the previous z contract here, but pay the bucketing cost only when the
        // population changes rather than doing three full holder scans on every vsync.
        val nodeItems = buildList(unorderedNodeItems.size) {
            unorderedNodeItems.forEach { if (it.kind == VirtualListOuterTransitionKind.RETAINED_ONLY) add(it) }
            unorderedNodeItems.forEach { if (it.kind == VirtualListOuterTransitionKind.SHARED) add(it) }
            unorderedNodeItems.forEach { if (it.kind == VirtualListOuterTransitionKind.CURRENT_ONLY) add(it) }
        }
        val holderFrame = VirtualListSettledNodeFrame(
            items = nodeItems.map { it.holderItem },
            density = density.density,
            textDensity = density.density * density.fontScale,
            dark = ThemeManager.isDarkMode(context),
            configuredTypeface = FontManager.typeface,
            resources = context.resources,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
            marqueeAllowed = true,
            scrollRemainderProvider = { 0 },
            scrollInvalidationOwner = null,
            onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
            onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
            onSongClick = { _, _ -> },
            onSongLongClick = { _, _ -> },
            onCopySongInfo = {},
        )
        val nodeFrame = VirtualListOuterTransitionNodeFrame(
            holderFrame = holderFrame,
            items = nodeItems,
            currentFractionProvider = currentFractionProvider,
            currentTransform = currentTransform,
            retainedTransform = retainedTransform,
            contentBindAllowedProvider = { false },
            onPopulationPublished = onPopulationPublished,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .virtualListOuterTransitionNode(
                    frame = nodeFrame,
                    runtime = settledNodeRuntime,
                )
                .onGloballyPositioned { coordinates ->
                    // Native OUTER holders also publish canonical target geometry for shared motion.
                    val origin = coordinates.positionInWindow()
                    for (motion in nodeItems) {
                        val position = motion.currentPosition ?: continue
                        val item = motion.holderItem
                        if (!item.sharedCoverElementId.startsWith("cover:")) continue
                        val cover = item.rects.cover
                        val left = origin.x + position.bounds.left + cover.left
                        val top = origin.y + position.bounds.top + cover.top
                        onSharedCoverTargetChanged(item.song, item.index, CoverTransitionTarget(
                            bounds = RectF(left, top, left + cover.width, top + cover.height),
                            radiusDp = item.rects.coverRadiusDp,
                            source = CoverTransitionTarget.Source.ListCover,
                            songId = item.song.id,
                            coverKey = item.song.coverKey,
                            index = item.index,
                        ))
                    }
                }
        )
    }

    val noopBounds = remember { { _: RectF? -> } }
    val noopTarget = remember { { _: CoverTransitionTarget? -> } }
    val noopClick = remember { { _: CoverTransitionTarget? -> } }
    val noopLongClick = remember { {} }

    // Settled HOME keeps its heterogeneous/custom holders in this canonical call site so the
    // GenericPivot endpoint never relocates their movableContent.  Unlike the ordinary Android/Node
    // population, however, this Compose lane does not receive VirtualListScrollPositionOwner draw
    // invalidations automatically.  If it consumes LayoutRes.exactViewportPosition() directly, the
    // remainder is only as fresh as the last composition and the HOME cards visibly move in chunks
    // while Choreographer still reports full-rate frames.  Subscribe once at the population root and
    // apply the live remainder as one graphics-layer translation.  During CURRENT/RETAINED motion the
    // LayoutRes endpoints remain authoritative, so this settled-only parent translation is disabled.
    val settledCustomExactScroll = if (
        retainedProvider == null && currentCustomProvider != null
    ) {
        rememberVirtualListExactScrollPx(currentScrollOwner)
    } else {
        null
    }
    val settledParentOwnsRemainder = settledCustomExactScroll != null

    // Only geometry modes which still require child-layout morphing stay on the old per-item Compose
    // compatibility renderer. Dense HOME/category GenericPivot now reaches this branch only for a
    // genuinely different visual topology, not merely because a holder is retained/current-only.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Compare with the base that positioned THESE children, not the mutable modulo
                // remainder. At a bucket boundary the latter resets before Compose commits the
                // next child layout and makes the old population jump back by a whole row.
                translationY = settledCustomExactScroll?.let {
                    virtualListLayoutScrollTranslation(it.value, currentLayoutScrollY)
                } ?: 0f
            }
    )
}

/**
 * Scroll-sensitive chrome is isolated from the row renderer. The child still follows exact pixel
 * movement, but changing its snapshot state no longer recomposes the visible grid/list cells.
 */
@Composable
private fun ComposeVirtualListPersistentHeader(
    state: ComposeVirtualListState,
    transitionSnapshot: ComposeVirtualListTransitionSnapshot?,
    staticTopPaddingPx: Int,
    persistentHeaderHeight: Dp,
    persistentHeaderVisibilityHeightPx: Int,
    persistentHeaderSceneItemVisibilityHeightPx: Int,
    widthPx: Int,
    heightPx: Int,
    sharedCoverSceneId: String,
    persistentHeaderSceneItemId: String,
    persistentHeaderContent: @Composable (headerVisible: Boolean, sceneItemVisible: Boolean) -> Unit
) {
    val density = LocalDensity.current
    val persistentHeaderHeightPx = with(density) { persistentHeaderHeight.roundToPx() }
    // Exact motion is projected only into this small header subtree. The surrounding VirtualList does
    // not observe per-pixel scroll state. Transition ownership continues to use its frozen model.
    val exactHeaderScrollState = rememberVirtualListExactScrollPx(
        owner = state.viewportScrollOwner,
        enabled = transitionSnapshot == null,
    )
    val headerScrollYPx = when {
        state.isTransitioning && transitionSnapshot != null -> lerpIntLocal(
            transitionSnapshot.scrollModel.sourceScrollYPx,
            transitionSnapshot.scrollModel.targetScrollYPx,
            state.transitionProgress
        )
        transitionSnapshot != null -> transitionSnapshot.endpointScrollFor(state.currentMode)
        else -> exactHeaderScrollState.value.roundToInt()
    }
    val headerTopPx = staticTopPaddingPx - headerScrollYPx
    val headerVisible = headerTopPx < heightPx &&
        headerTopPx + persistentHeaderVisibilityHeightPx > 0
    val sceneItemVisible = headerTopPx < heightPx &&
        headerTopPx + persistentHeaderSceneItemVisibilityHeightPx > 0
    val sharedRegistry = LocalSharedCoverRegistry.current
    val physicalHeaderOwner = remember(sharedCoverSceneId, persistentHeaderSceneItemId) { Any() }
    DisposableEffect(sharedRegistry, sharedCoverSceneId, persistentHeaderSceneItemId, physicalHeaderOwner) {
        onDispose {
            if (sharedCoverSceneId.isNotBlank() && persistentHeaderSceneItemId.isNotBlank()) {
                sharedRegistry.unregisterPhysicalLayoutEndpoint(
                    sceneId = sharedCoverSceneId,
                    elementId = persistentHeaderSceneItemId,
                    owner = physicalHeaderOwner,
                )
            }
        }
    }
    val physicalHeaderBounds = if (
        sharedCoverSceneId.isNotBlank() && persistentHeaderSceneItemId.startsWith("cover:")
    ) {
        Rect(
            left = 0f,
            top = headerTopPx.toFloat(),
            right = widthPx.toFloat(),
            bottom = (headerTopPx + persistentHeaderSceneItemVisibilityHeightPx).toFloat(),
        )
    } else {
        null
    }
    if (physicalHeaderBounds != null) {
        // NEXT header LayoutRes is published synchronously as part of provider
        // binding. Do not defer this through Compose SideEffect/onGloballyPositioned: those are
        // post-commit callbacks and make first-entry ItemToHeader fall back before the target
        // exists. Raw already computed the exact viewport-local LayoutRes above, and the promoted
        // holder consumes physicalViewportBounds for geometry; window bounds are refreshed below.
        sharedRegistry.registerPhysicalLayoutEndpoint(
            sceneId = sharedCoverSceneId,
            elementId = persistentHeaderSceneItemId,
            owner = physicalHeaderOwner,
            snapshot = SharedCoverSnapshot(
                sceneId = sharedCoverSceneId,
                elementId = persistentHeaderSceneItemId,
                boundsInWindow = physicalHeaderBounds,
                coverKey = "",
                radiusDp = COLLECTION_HEADER_RADIUS_DP,
                sharedEligible = sceneItemVisible,
                physicalViewportBounds = physicalHeaderBounds,
            ),
        )
    }
    val physicalHeaderRegistrationModifier = if (physicalHeaderBounds != null) {
        Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            sharedRegistry.registerPhysicalLayoutEndpoint(
                sceneId = sharedCoverSceneId,
                elementId = persistentHeaderSceneItemId,
                owner = physicalHeaderOwner,
                snapshot = SharedCoverSnapshot(
                    sceneId = sharedCoverSceneId,
                    elementId = persistentHeaderSceneItemId,
                    // reference player keeps LayoutRes geometry viewport-local while the shared transition
                    // also knows the concrete View's window bounds. These are two coordinate
                    // spaces. Reusing the local LayoutRes as boundsInWindow made HeaderToItem
                    // subtract the host offset twice and produced a terminal jump/flash.
                    boundsInWindow = Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + widthPx,
                        bottom = position.y + persistentHeaderSceneItemVisibilityHeightPx,
                    ),
                    coverKey = "",
                    radiusDp = COLLECTION_HEADER_RADIUS_DP,
                    sharedEligible = sceneItemVisible,
                    physicalViewportBounds = physicalHeaderBounds,
                ),
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, headerTopPx) }
            .requiredSize(
                width = with(density) { widthPx.toDp() },
                height = persistentHeaderHeight
            )
            .then(physicalHeaderRegistrationModifier)
            .graphicsLayer {
                // Scene GenericPivot is already owned by the persistent VirtualList root.  Keep only
                // the header's independent list/grid scale plus VirtualList's holder-local vertical
                // edge deformation. scene_header is a normal attached LayoutRes in Reference, so
                // its match-parent artwork view stretches naturally with this holder.
                val localScale = if (state.isTransitioning) {
                    state.transitionScaleFactor
                } else {
                    state.boundaryElasticScale
                }.coerceIn(0.9105f, 1.0895f)
                val edgeOvershoot = state.virtualListEdgeOvershootPx
                val maxOvershoot = VIRTUAL_LIST_MAX_OVERSHOOT_DP * density.density
                val edgeFraction = virtualListHolderEdgeFraction(
                    holderTopPx = headerTopPx.toFloat(),
                    holderBottomPx = (headerTopPx + persistentHeaderHeightPx).toFloat(),
                    viewportHeightPx = heightPx,
                )
                val edgeScaleY = virtualListHolderScaleY(
                    edgeOvershoot,
                    maxOvershoot,
                    edgeFraction,
                )
                val edgeTranslationY = virtualListHolderTranslationY(
                    overshootPx = edgeOvershoot,
                    maxOvershootPx = maxOvershoot,
                    holderShiftPx = VIRTUAL_LIST_HOLDER_SHIFT_DP * density.density,
                    holderFraction = edgeFraction,
                )
                translationX = 0f
                translationY = edgeTranslationY
                scaleX = localScale
                scaleY = localScale * edgeScaleY
                alpha = 1f
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
    ) {
        persistentHeaderContent(headerVisible, sceneItemVisible)
    }
}


private data class VirtualListPhysicalHolderRenderSpec(
    val slotId: Int,
    val song: AudioFile,
    val index: Int,
    val mode: ComposeVirtualListDisplayMode,
    val params: ListZoomParams,
    val position: ComposeItemPosition,
    val layoutPosition: ComposeItemPosition,
    val deferBitmapLoad: Boolean,
    val drawContentEnabled: Boolean,
    val isPlaying: Boolean,
    val isSelected: Boolean,
    val selectionActive: Boolean,
    val boundaryScaleProvider: () -> Float,
    val virtualListEdgeOvershootProvider: () -> Float = { 0f },
    val viewportHeightPx: Int = 0,
    val sceneTransform: RetainedSceneItemTransform?,
    val hideCover: Boolean,
    val interactionActive: Boolean,
    val artworkAnimationsEnabled: Boolean,
    val artworkPriority: BitmapRequest.Priority,
    val marqueeVisible: Boolean,
    val sharedCoverSceneId: String,
    val sharedCoverElementId: String,
    val onCoverBoundsChanged: (RectF?) -> Unit,
    val onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    val onClick: (CoverTransitionTarget?) -> Unit,
    val onLongClick: () -> Unit,
    val customProvider: VirtualListCustomProvider?,
)

@Composable
private fun RenderSettledVirtualListPhysicalHolder(spec: VirtualListPhysicalHolderRenderSpec) {
    CompositionLocalProvider(LocalVirtualListCustomProvider provides spec.customProvider) {
        ComposeVirtualListTransitionItem(
            compositionSlot = "physical-holder-${spec.slotId}",
            physicalSlotId = spec.slotId,
            song = spec.song,
            index = spec.index,
            mode = spec.mode,
            params = spec.params,
            position = spec.position,
            layoutPosition = spec.layoutPosition,
            deferBitmapLoad = spec.deferBitmapLoad,
            drawContentEnabled = spec.drawContentEnabled,
            isPlaying = spec.isPlaying,
            isSelected = spec.isSelected,
            selectionActive = spec.selectionActive,
            boundaryScaleProvider = spec.boundaryScaleProvider,
            virtualListEdgeOvershootProvider = spec.virtualListEdgeOvershootProvider,
            viewportHeightPx = spec.viewportHeightPx,
            sceneTransform = spec.sceneTransform,
            hideCover = spec.hideCover,
            interactionActive = spec.interactionActive,
            artworkAnimationsEnabled = spec.artworkAnimationsEnabled,
            artworkPriority = spec.artworkPriority,
            marqueeVisible = spec.marqueeVisible,
            sharedCoverSceneId = spec.sharedCoverSceneId,
            sharedCoverElementId = spec.sharedCoverElementId,
            onCoverBoundsChanged = spec.onCoverBoundsChanged,
            onCoverTargetChanged = spec.onCoverTargetChanged,
            onClick = spec.onClick,
            onLongClick = spec.onLongClick,
        )
    }
}

internal sealed interface VirtualListPhysicalHolderSpec

private data class VirtualListSettledHolderSpec(
    val renderSpec: VirtualListPhysicalHolderRenderSpec,
) : VirtualListPhysicalHolderSpec

private data class VirtualListZoomHolderSpec(
    val slotId: Int,
    val snapshot: ComposeVirtualListTransitionSnapshot,
    val item: ComposeTransitionItem,
    val progressProvider: () -> Float,
    val elasticScaleProvider: () -> Float,
    val isPlaying: Boolean,
    val isSelected: Boolean,
    val selectionActive: Boolean,
    val onCoverBoundsChanged: (RectF?) -> Unit,
    val onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    val onClick: (CoverTransitionTarget?) -> Unit,
    val onLongClick: () -> Unit,
) : VirtualListPhysicalHolderSpec

@Composable
private fun RenderVirtualListPhysicalHolder(spec: VirtualListPhysicalHolderSpec) {
    // Preparation and a single CURRENT/RETAINED role are the same concrete holder tree.
    // A movableContent slot preserves identity only if its internal Compose call site also stays
    // fixed; putting these calls in separate when branches discarded the warmed HOME cards.
    val singleRole = when (spec) {
        is VirtualListSettledHolderSpec -> spec.renderSpec
        else -> null
    }
    if (singleRole != null) {
        RenderSettledVirtualListPhysicalHolder(singleRole)
        return
    }
    when (spec) {
        is VirtualListSettledHolderSpec -> Unit // handled at the canonical call site above
        is VirtualListZoomHolderSpec -> {
            val snapshot = spec.snapshot
            val item = spec.item
            val zoomInTransition = virtualListModeOrder(snapshot.targetMode) > virtualListModeOrder(snapshot.sourceMode)
            val compositionSlot = "physical-holder-${spec.slotId}"
            when (item.kind) {
                ComposeTransitionItemKind.SHARED -> ComposeVirtualListInterpolatedItem(
                    compositionSlot = compositionSlot,
                    song = item.song,
                    index = item.index,
                    sourceMode = snapshot.sourceMode,
                    targetMode = snapshot.targetMode,
                    sourcePosition = item.sourcePosition,
                    targetPosition = item.targetPosition,
                    sourceRects = item.sourceRects,
                    targetRects = item.targetRects,
                    progressProvider = spec.progressProvider,
                    elasticScaleProvider = spec.elasticScaleProvider,
                    isPlaying = spec.isPlaying,
                    isSelected = spec.isSelected,
                    selectionActive = spec.selectionActive,
                    onCoverBoundsChanged = spec.onCoverBoundsChanged,
                    onCoverTargetChanged = spec.onCoverTargetChanged,
                    onClick = spec.onClick,
                    onLongClick = spec.onLongClick,
                )
                ComposeTransitionItemKind.SOURCE_ONLY -> ComposeVirtualListOneSlotItem(
                    compositionSlot = compositionSlot,
                    song = item.song,
                    index = item.index,
                    mode = snapshot.sourceMode,
                    params = snapshot.sourceParams,
                    basePosition = item.sourcePosition,
                    fadeOut = true,
                    pivotX = snapshot.viewportWidthPx / 2f,
                    pivotY = snapshot.viewportHeightPx / 2f,
                    oneSlotScaleBase = if (zoomInTransition) 1.5f else 0.5f,
                    progressProvider = spec.progressProvider,
                    elasticScaleProvider = spec.elasticScaleProvider,
                    isPlaying = spec.isPlaying,
                    isSelected = spec.isSelected,
                    selectionActive = spec.selectionActive,
                    onCoverBoundsChanged = spec.onCoverBoundsChanged,
                    onCoverTargetChanged = spec.onCoverTargetChanged,
                    onClick = spec.onClick,
                    onLongClick = spec.onLongClick,
                )
                ComposeTransitionItemKind.TARGET_ONLY -> ComposeVirtualListOneSlotItem(
                    compositionSlot = compositionSlot,
                    song = item.song,
                    index = item.index,
                    mode = snapshot.targetMode,
                    params = snapshot.targetParams,
                    basePosition = item.targetPosition,
                    fadeOut = false,
                    pivotX = snapshot.viewportWidthPx / 2f,
                    pivotY = snapshot.viewportHeightPx / 2f,
                    oneSlotScaleBase = if (zoomInTransition) 0.5f else 1.5f,
                    progressProvider = spec.progressProvider,
                    elasticScaleProvider = spec.elasticScaleProvider,
                    isPlaying = spec.isPlaying,
                    isSelected = spec.isSelected,
                    selectionActive = spec.selectionActive,
                    onCoverBoundsChanged = spec.onCoverBoundsChanged,
                    onCoverTargetChanged = spec.onCoverTargetChanged,
                    onClick = spec.onClick,
                    onLongClick = spec.onLongClick,
                )
            }
        }
    }
}

@Stable
internal class VirtualListPhysicalHolderOwner {
    private val holders = HashMap<Int, @Composable (VirtualListPhysicalHolderSpec) -> Unit>()

    @Composable
    fun Render(slotId: Int, spec: VirtualListPhysicalHolderSpec) {
        val holder = holders.getOrPut(slotId) {
            movableContentOf<VirtualListPhysicalHolderSpec> { holderSpec ->
                RenderVirtualListPhysicalHolder(holderSpec)
            }
        }
        holder(spec)
    }

    fun clear() {
        holders.clear()
    }
}


/**
 * Build the ordinary endpoint binding for one provider/layout role.
 *
 * This function intentionally contains no Compose state and no transition progress.  It is the
 * adapter -> physical-holder binding step: identity/content/endpoint geometry are frozen into the persistent
 * runtime before GenericPivot starts.  Heterogeneous provider rows are published through their own
 * physical View-holder lane and therefore are excluded here.
 */
private fun buildOrdinaryPowerItemRolePopulation(
    songs: List<AudioFile>,
    range: IntRange,
    geometry: ComposeVirtualListGeometry,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    layoutScrollYPx: Int,
    artworkVisibleRange: IntRange,
    strictVisualRange: IntRange,
    playingSongId: Long,
    currentPlayingIndex: Int,
    hidePlayingCover: Boolean,
    interactionActive: Boolean,
    artworkAnimationsEnabled: Boolean,
    customProvider: VirtualListCustomProvider?,
    density: Float,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalKeyPrefix: Any?,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    sharedCoverSuppressed: (String) -> Boolean = { false },
): List<VirtualListSettledNodeItem> {
    if (range.isEmpty()) return emptyList()
    return buildList {
        for (index in range.first..range.last) {
            val song = songs.getOrNull(index) ?: continue
            if (customProvider?.handles?.invoke(song, index) == true) continue
            val slotId = physicalSlotPool.slotIdForKey(
                virtualListPhysicalRenderKey(physicalKeyPrefix, song, index, customHandled = false)
            )
            if (slotId < 0) continue
            val position = positionFor(
                index = index,
                geometry = geometry,
                mode = mode,
                scrollYPx = layoutScrollYPx,
            )
            if (position.isEmpty()) continue
            val isPlaying = if (playingSongId > 0L) song.id == playingSongId else index == currentPlayingIndex
            val artworkVisible = index in artworkVisibleRange
            val sharedCoverElementId = sharedCoverElementIdProvider(song, index)
            val rects = composeArtworkItemSceneRects(
                position,
                mode,
                params,
                density,
                song.virtualListArtworkRadiusType(),
            )
            add(
                VirtualListSettledNodeItem(
                    slotId = slotId,
                    song = song,
                    index = index,
                    position = position,
                    rects = rects,
                    deferBitmapLoad = !artworkVisible,
                    drawContentEnabled = index in strictVisualRange,
                    isPlaying = isPlaying,
                    hideCover = (hidePlayingCover && isPlaying) || sharedCoverSuppressed(sharedCoverElementId),
                    interactionActive = interactionActive,
                    artworkAnimationsEnabled = artworkAnimationsEnabled,
                    artworkPriority = if (artworkVisible) {
                        BitmapRequest.Priority.LOADING_LIST
                    } else {
                        BitmapRequest.Priority.LOADING_PREFETCH
                    },
                    coverDecodeSide = virtualListDecodeSideForMode(
                        max(rects.cover.width, rects.cover.height).coerceAtLeast(1)
                    ),
                    hasCollectionMetaIcon = song.hasVirtualListCollectionMetaIcon(),
                    hasFolderMetaIcon = song.encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING,
                    isGrid = mode.isGrid,
                    sharedCoverElementId = sharedCoverElementId,
                )
            )
        }
    }
}

/** Build the heterogeneous endpoint bindings for the same physical VirtualList holder pool. */
private fun buildCustomPowerItemRolePopulation(
    songs: List<AudioFile>,
    range: IntRange,
    geometry: ComposeVirtualListGeometry,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    layoutScrollYPx: Int,
    artworkVisibleRange: IntRange,
    strictVisualRange: IntRange,
    customProvider: VirtualListCustomProvider?,
    physicalKeyPrefix: Any?,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    renderRemainderPx: Int,
): List<VirtualListPowerCustomRolePublication> {
    val provider = customProvider ?: return emptyList()
    if (range.isEmpty()) return emptyList()
    return buildList {
        for (index in range.first..range.last) {
            val song = songs.getOrNull(index) ?: continue
            if (!provider.handles(song, index)) continue
            val key = virtualListPhysicalRenderKey(
                prefix = physicalKeyPrefix,
                song = song,
                index = index,
                customHandled = true,
            )
            val slotId = physicalSlotPool.slotIdForKey(key)
            if (slotId < 0) continue
            val position = positionFor(
                index = index,
                geometry = geometry,
                mode = mode,
                scrollYPx = layoutScrollYPx,
            )
            if (position.isEmpty()) continue
            add(
                VirtualListPowerCustomRolePublication(
                    slotId = slotId,
                    binding = VirtualListPowerCustomBinding(
                        song = song,
                        index = index,
                        provider = provider,
                    ),
                    layout = VirtualListPhysicalLayoutRes(
                        index = index,
                        mode = mode,
                        params = params,
                        position = position,
                        drawContentEnabled = index in strictVisualRange,
                        artworkVisible = index in artworkVisibleRange,
                        renderRemainderPx = renderRemainderPx,
                    ),
                )
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeVirtualListSettledContent(
    songs: List<AudioFile>,
    state: ComposeVirtualListState,
    metrics: ComposeVirtualListMetrics,
    scrollYPx: Int,
    scrollRemainderProvider: () -> Int,
    nodeScrollRemainderProvider: () -> Int = scrollRemainderProvider,
    nodeScrollInvalidationOwner: VirtualListScrollPositionOwner? = null,
    exactScrollObservationOwner: VirtualListScrollPositionOwner? = null,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScaleProvider: () -> Float,
    exactPixelScrollLane: Boolean,
    strictSceneHolderWindow: Boolean,
    interactionActive: Boolean,
    artworkAnimationsEnabled: Boolean,
    settledMarqueeVisible: Boolean,
    topPaddingPx: Int,
    itemHeightsPx: IntArray?,
    sectionHeaders: List<VirtualListSectionHeader>,
    sectionHeaderHeightPx: Int,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalHolderOwner: VirtualListPhysicalHolderOwner,
    physicalLayoutSlots: VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>? = null,
    physicalLayoutFrameId: Int = Int.MIN_VALUE,
    physicalLayoutRole: VirtualListPhysicalLayoutRole = VirtualListPhysicalLayoutRole.CURRENT,
    physicalKeyPrefix: Any? = null,
    appendPhysicalFrame: Boolean = false,
    deferPhysicalCleanup: Boolean = false,
    ordinaryPublicationOnly: Boolean = false,
    suppressHolderRenderingForOuterDual: Boolean = false,
    sceneTransform: RetainedSceneItemTransform? = null,
    settledNodeRuntimeOverride: VirtualListSettledNodeRuntime? = null,
    modifier: Modifier = Modifier
) {
    ComposeVirtualListViewportLayer(
        songs = songs,
        state = state,
        mode = state.currentMode,
        params = state.currentParams,
        metrics = metrics,
        scrollYPx = scrollYPx,
        scrollRemainderProvider = scrollRemainderProvider,
        nodeScrollRemainderProvider = nodeScrollRemainderProvider,
        nodeScrollInvalidationOwner = nodeScrollInvalidationOwner,
        exactScrollObservationOwner = exactScrollObservationOwner,
        viewportHeightPx = viewportHeightPx,
        playingSongId = playingSongId,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = selectedPositions,
        hidePlayingCover = hidePlayingCover,
        boundaryScaleProvider = boundaryScaleProvider,
        exactPixelScrollLane = exactPixelScrollLane,
        strictSceneHolderWindow = strictSceneHolderWindow,
        interactionActive = interactionActive,
        artworkAnimationsEnabled = artworkAnimationsEnabled,
        settledMarqueeVisible = settledMarqueeVisible,
        topPaddingPx = topPaddingPx,
        itemHeightsPx = itemHeightsPx,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeightPx = sectionHeaderHeightPx,
        sectionHeaderContent = sectionHeaderContent,
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onSharedCoverTargetChanged = onSharedCoverTargetChanged,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        physicalSlotPool = physicalSlotPool,
        physicalHolderOwner = physicalHolderOwner,
        physicalLayoutSlots = physicalLayoutSlots,
        physicalLayoutFrameId = physicalLayoutFrameId,
        physicalLayoutRole = physicalLayoutRole,
        physicalKeyPrefix = physicalKeyPrefix,
        appendPhysicalFrame = appendPhysicalFrame,
        deferPhysicalCleanup = deferPhysicalCleanup,
        ordinaryPublicationOnly = ordinaryPublicationOnly,
        suppressHolderRenderingForOuterDual = suppressHolderRenderingForOuterDual,
        sceneTransform = sceneTransform,
        settledNodeRuntimeOverride = settledNodeRuntimeOverride,
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeVirtualListViewportLayer(
    songs: List<AudioFile>,
    state: ComposeVirtualListState,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    metrics: ComposeVirtualListMetrics,
    scrollYPx: Int,
    scrollRemainderProvider: () -> Int,
    nodeScrollRemainderProvider: () -> Int,
    nodeScrollInvalidationOwner: VirtualListScrollPositionOwner?,
    exactScrollObservationOwner: VirtualListScrollPositionOwner?,
    viewportHeightPx: Int,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    boundaryScaleProvider: () -> Float,
    exactPixelScrollLane: Boolean,
    strictSceneHolderWindow: Boolean,
    interactionActive: Boolean,
    artworkAnimationsEnabled: Boolean,
    settledMarqueeVisible: Boolean,
    topPaddingPx: Int,
    itemHeightsPx: IntArray?,
    sectionHeaders: List<VirtualListSectionHeader>,
    sectionHeaderHeightPx: Int,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalHolderOwner: VirtualListPhysicalHolderOwner,
    physicalLayoutSlots: VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>? = null,
    physicalLayoutFrameId: Int = Int.MIN_VALUE,
    physicalLayoutRole: VirtualListPhysicalLayoutRole = VirtualListPhysicalLayoutRole.CURRENT,
    physicalKeyPrefix: Any? = null,
    appendPhysicalFrame: Boolean = false,
    deferPhysicalCleanup: Boolean = false,
    ordinaryPublicationOnly: Boolean = false,
    suppressHolderRenderingForOuterDual: Boolean = false,
    sceneTransform: RetainedSceneItemTransform? = null,
    settledNodeRuntimeOverride: VirtualListSettledNodeRuntime? = null,
    modifier: Modifier = Modifier
    ) {
    val geometry = remember(metrics, topPaddingPx, songs.size, sectionHeaders, sectionHeaderHeightPx, itemHeightsPx) {
        listGeometryFor(
            metrics = metrics,
            bottomPaddingPx = 0,
            topPaddingPx = topPaddingPx,
            itemCount = songs.size,
            sectionHeaders = sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
            itemHeightsPx = itemHeightsPx,
        )
    }
    // Retained layout contract: children are laid out only when a row enters or
    // leaves the viewport; the remaining pixel motion belongs to the parent container. Passing
    // exact scrollY into every item makes every Canvas/text/click target recompute on every touch
    // frame, which is the main source of the dense-grid hitch.
    val rowStridePx = geometry.rowStridePx.coerceAtLeast(1)
    val safeScrollYPx = scrollYPx.coerceAtLeast(0)
    // The retained large-list path deliberately buckets holder layout by whole rows and applies the
    // fractional remainder once at the viewport layer. Under-filled persistent-header details are
    // different: there are only a few holders, and Reference keeps the header and
    // attached body items in one continuously updated layout. Do not silently re-bucket the body
    // here after ComposeVirtualList selected its exact lane; doing so leaves the hero moving while
    // the lone/few body items appear pinned until a whole-row boundary is crossed.
    val layoutScrollYPx = persistentHeaderBodyLayoutScrollYPx(
        scrollYPx = safeScrollYPx,
        rowStridePx = rowStridePx,
        exactPixelLane = exactPixelScrollLane,
    )
    // Reference keeps a bounded attached holder ring. Ordinary Raw scrolling keeps one physical
    // recycling row for edge continuity. GenericPivot keeps that exact same physical ring attached;
    // only strict draw eligibility changes, so scene ownership never tears down a holder/request.
    val retainedRowsEachSide = virtualListRetainedRowsEachSide(strictSceneHolderWindow)
    val maxRenderItems = remember(
        geometry.columns,
        geometry.rowStridePx,
        viewportHeightPx,
        retainedRowsEachSide,
    ) {
        virtualListMaxRenderItems(
            columns = geometry.columns,
            rowStridePx = geometry.rowStridePx,
            viewportHeightPx = viewportHeightPx,
            retainedRowsEachSide = retainedRowsEachSide,
        )
    }
    // Artwork admission follows the exact visual viewport, not the row-bucketed layout viewport.
    // derivedStateOf only invalidates this subtree when the visible index range changes, so LIST
    // bottom rows can start decoding as soon as they actually enter view without recomposing on
    // every scroll pixel.
    // Observe exact scroll only inside the visibility derivation. The local projection changes every
    // pixel, but derivedStateOf invalidates this composition only when the visible index range itself
    // changes. Ordinary holder/layout composition therefore stays row-bucketed during a fling.
    val exactScrollState = rememberVirtualListExactScrollPx(
        owner = exactScrollObservationOwner ?: state.viewportScrollOwner,
        enabled = exactScrollObservationOwner != null,
    )
    val artworkVisibleRange by remember(
        songs.size,
        mode,
        geometry,
        layoutScrollYPx,
        viewportHeightPx,
        scrollRemainderProvider,
        exactScrollObservationOwner,
    ) {
        derivedStateOf {
            val exactVisualScrollYPx = if (exactScrollObservationOwner != null) {
                exactScrollState.value.roundToInt()
            } else {
                layoutScrollYPx + scrollRemainderProvider()
            }
            visibleRangeForScroll(
                itemCount = songs.size,
                mode = mode,
                geometry = geometry,
                scrollYPx = exactVisualScrollYPx,
                viewportHeightPx = viewportHeightPx,
            )
        }
    }
    // Anchor the physical ring to the exact visual viewport, not the row-bucketed layout viewport.
    // Before GenericPivot, layout uses whole rows plus a parent fractional translation; after scene
    // handoff the same fraction is folded into holder coordinates. If the ring is based on the bucket,
    // those mathematically-equivalent representations can still shift the ring by one row on the
    // handoff frame, cancelling requests and dropping the bottom partial row. Exact range is invariant.
    val strictVisualRange = artworkVisibleRange
    val renderRange = remember(
        strictVisualRange,
        songs.size,
        geometry.columns,
        retainedRowsEachSide,
        maxRenderItems,
    ) {
        capVirtualListRange(
            expandRangeByRows(
                range = strictVisualRange,
                itemCount = songs.size,
                columns = geometry.columns,
                rowsBefore = retainedRowsEachSide,
                rowsAfter = retainedRowsEachSide,
            ),
            maxItems = maxRenderItems,
            columns = geometry.columns
        )
    }
    // Keep the normal physical recycling row attached through GenericPivot. The scene owner below
    // suppresses drawing only for holders outside strictVisualRange; identities, decoded artwork, and
    // display-list state therefore survive the scroll->transition handoff without cancellation.
    val range = renderRange
    val tracedSceneRange = remember { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE) }
    if (strictSceneHolderWindow && TransitionPerfTrace.isActive()) {
        SideEffect {
            val strictFirst = strictVisualRange.first
            val strictLast = strictVisualRange.last
            val renderFirst = range.first
            val renderLast = range.last
            if (
                tracedSceneRange[0] != strictFirst || tracedSceneRange[1] != strictLast ||
                tracedSceneRange[2] != renderFirst || tracedSceneRange[3] != renderLast
            ) {
                tracedSceneRange[0] = strictFirst
                tracedSceneRange[1] = strictLast
                tracedSceneRange[2] = renderFirst
                tracedSceneRange[3] = renderLast
                val strictCount = if (strictVisualRange.isEmpty()) 0 else strictLast - strictFirst + 1
                val renderCount = if (range.isEmpty()) 0 else renderLast - renderFirst + 1
                TransitionPerfTrace.mark(
                    "holder_ring",
                    "strict=$strictFirst-$strictLast render=$renderFirst-$renderLast buffer=${(renderCount - strictCount).coerceAtLeast(0)} scroll=${"%.1f".format(state.viewportScrollY)}",
                )
            }
        }
    }
    // The keyed physical holder pool is shared with internal zoom and outer provider transitions.
    // VirtualList assigns the physical holder slot while each endpoint layout is published. Keep both
    // provider roles in the same open slot-pool frame; the runtime later joins those two LayoutRes
    // records by slot.  Do not defer ordinary allocation to a transition Composable/union pass.
    val holderCustomProvider = LocalVirtualListCustomProvider.current
    val attachedPhysicalKeys = remember(range, songs, physicalKeyPrefix, holderCustomProvider, ordinaryPublicationOnly) {
        if (range.isEmpty()) emptyList() else buildList {
            for (index in range.first..range.last) {
                val song = songs.getOrNull(index) ?: continue
                val customHandled = holderCustomProvider?.handles?.invoke(song, index) == true
                add(virtualListPhysicalRenderKey(physicalKeyPrefix, song, index, customHandled))
            }
        }
    }
    if (appendPhysicalFrame) {
        physicalSlotPool.appendFrame(attachedPhysicalKeys, closeFrame = !deferPhysicalCleanup)
    } else {
        physicalSlotPool.beginFrame(attachedPhysicalKeys, keepOpen = deferPhysicalCleanup)
    }

    // Endpoint binding is independent from the renderer which happens to be visible right now.
    // VirtualList binds holder + LayoutRes when a provider/layout population is prepared, then keeps that
    // record alive through GenericPivot.  Build the ordinary endpoint records here even when this
    // population is publication-only; the animation itself will consume the retained records from
    // VirtualListSettledNodeRuntime without reconstructing them in Compose.
    val context = LocalContext.current
    val density = LocalDensity.current
    val hostPersistentRuntime = LocalVirtualListPersistentRuntime.current
    val localSettledNodeRuntime = remember { VirtualListSettledNodeRuntime() }
    val settledNodeRuntime = settledNodeRuntimeOverride
        ?: hostPersistentRuntime?.settledNodeRuntime
        ?: localSettledNodeRuntime
    val settledSharedCoverRegistry = LocalSharedCoverRegistry.current
    val sharedTransitionActive = LocalSharedTransitionSpec.current.active
    val sharedPhysicalPromotionActive = settledSharedCoverRegistry.isPhysicalPromotionActive()
    val animateListText = LongTextMotionState.enabled && LongTextMotionState.enabledEverywhere
    val listMarqueeVisible = LongTextMotionState.LocalListMarqueeVisibility.current
    val powerRoleItems = remember(
        range,
        songs,
        geometry,
        mode,
        params,
        layoutScrollYPx,
        artworkVisibleRange,
        playingSongId,
        currentPlayingIndex,
        hidePlayingCover,
        interactionActive,
        artworkAnimationsEnabled,
        strictVisualRange,
        holderCustomProvider,
        density.density,
        physicalSlotPool,
        physicalKeyPrefix,
        sharedCoverElementIdProvider,
        sharedPhysicalPromotionActive,
    ) {
        buildOrdinaryPowerItemRolePopulation(
            songs = songs,
            range = range,
            geometry = geometry,
            mode = mode,
            params = params,
            layoutScrollYPx = layoutScrollYPx,
            artworkVisibleRange = artworkVisibleRange,
            strictVisualRange = strictVisualRange,
            playingSongId = playingSongId,
            currentPlayingIndex = currentPlayingIndex,
            hidePlayingCover = hidePlayingCover,
            interactionActive = interactionActive,
            artworkAnimationsEnabled = artworkAnimationsEnabled,
            customProvider = holderCustomProvider,
            density = density.density,
            physicalSlotPool = physicalSlotPool,
            physicalKeyPrefix = physicalKeyPrefix,
            sharedCoverElementIdProvider = sharedCoverElementIdProvider,
            sharedCoverSuppressed = settledSharedCoverRegistry::shouldSuppressPhysicalHero,
        )
    }
    val powerRoleFrame = VirtualListSettledNodeFrame(
        items = powerRoleItems,
        density = density.density,
        textDensity = density.density * density.fontScale,
        dark = ThemeManager.isDarkMode(context),
        configuredTypeface = FontManager.typeface,
        resources = context.resources,
        defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
        marqueeAllowed = animateListText && listMarqueeVisible && settledMarqueeVisible,
        inputEnabled = !sharedTransitionActive,
        scrollRemainderProvider = nodeScrollRemainderProvider,
        scrollInvalidationOwner = nodeScrollInvalidationOwner,
        viewportHeightPx = viewportHeightPx,
        virtualListEdgeOvershootProvider = { state.virtualListEdgeOvershootPx },
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        onCopySongInfo = { song -> copySongInfoToClipboard(context, song) },
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
        onSharedCoverClickSnapshot = { snapshot ->
            // Match the reference ItemToHeader ordering: click freezes the exact physical holder identity only.
            // The concrete holder is promoted later, after the detail/header provider has produced
            // a stable target LayoutRes. Detaching it here created a half-transaction whenever the
            // target missed its preparation window (small source cover stranded over a fading
            // detail page, then leaking back into the category).
            settledSharedCoverRegistry.freezeSnapshot(snapshot)
        },
        canStartSharedCoverTransition = {
            !settledSharedCoverRegistry.isPhysicalPromotionActive()
        },
    )
    if (physicalLayoutSlots != null) {
        // Ordinary settled scrolling must not read the exact remainder from Composition. VirtualList
        // keeps the row-bucketed LayoutRes bound and applies the live scroll offset directly to the
        // already-attached holder Views. Only an outer CURRENT/RETAINED endpoint needs to freeze the
        // exact remainder into its LayoutRes before GenericPivot starts.
        val frozenEndpointRemainderPx = if (
            appendPhysicalFrame || suppressHolderRenderingForOuterDual
        ) {
            nodeScrollRemainderProvider()
        } else {
            0
        }
        val rolePublications = powerRoleItems.map { item ->
            val layout = VirtualListPhysicalLayoutRes(
                index = item.index,
                mode = mode,
                params = params,
                position = item.position,
                drawContentEnabled = item.drawContentEnabled,
                artworkVisible = item.index in artworkVisibleRange,
                renderRemainderPx = frozenEndpointRemainderPx,
            )
            physicalLayoutSlots.publish(
                frameId = physicalLayoutFrameId,
                key = virtualListKey(item.song, item.index),
                role = physicalLayoutRole,
                layout = layout,
            )
            VirtualListPowerItemRolePublication(item = item, layout = layout)
        }
        settledNodeRuntime.publishPowerItemRole(
            frameId = physicalLayoutFrameId,
            role = physicalLayoutRole,
            frame = powerRoleFrame,
            publications = rolePublications,
        )
        val customRolePublications = buildCustomPowerItemRolePopulation(
            songs = songs,
            range = range,
            geometry = geometry,
            mode = mode,
            params = params,
            layoutScrollYPx = layoutScrollYPx,
            artworkVisibleRange = artworkVisibleRange,
            strictVisualRange = strictVisualRange,
            customProvider = holderCustomProvider,
            physicalKeyPrefix = physicalKeyPrefix,
            physicalSlotPool = physicalSlotPool,
            renderRemainderPx = frozenEndpointRemainderPx,
        )
        for (publication in customRolePublications) {
            physicalLayoutSlots.publish(
                frameId = physicalLayoutFrameId,
                key = virtualListPhysicalRenderKey(
                    prefix = physicalKeyPrefix,
                    song = publication.binding.song,
                    index = publication.binding.index,
                    customHandled = true,
                ),
                role = physicalLayoutRole,
                layout = publication.layout,
            )
        }
        settledNodeRuntime.publishPowerCustomRole(
            frameId = physicalLayoutFrameId,
            role = physicalLayoutRole,
            publications = customRolePublications,
        )
    }
    // GenericPivot owns a frozen source snapshot. Do not publish the temporary strict holder trim
    // as a new navigation/alphabet-index visible range; that would fan one transition-start write
    // back into unrelated chrome exactly when the scene takes ownership.
    if (!strictSceneHolderWindow && state.currentVisibleRange != renderRange) {
        SideEffect {
            state.updateVisibleRangeForNavigation(renderRange)
        }
    }
    // HOME/category GenericPivot is consumed once by the persistent ComposeVirtualList owner.
    // This viewport keeps only its independent fractional-scroll property.

    // Long-lived VirtualList plan / step 1: ordinary settled LIST/GRID uses one custom Compose Node.
    // Geometry ownership is intentionally unchanged. Any geometry-specialized state keeps the old
    // renderer until its dedicated migration phase so this change cannot alter an existing curve.
    val libraryMotionActive = LocalReferenceLibraryLayoutFrame.current.active ||
        LocalRetainedSceneMotionActive.current || LocalSceneBackgroundFrozen.current
    // Capture one composition-time ownership snapshot. The diagnostic effect runs asynchronously;
    // reading live state again inside it can mix a previous `reason=settled` with a newly-started
    // pinch and falsely look like an eligibility race even though the renderer decision was correct.
    val selectedCountSnapshot = selectedPositions.size
    val sceneTransformSnapshot = sceneTransform != null
    val strictSceneSnapshot = strictSceneHolderWindow
    val sharedTransitionSnapshot = sharedTransitionActive
    val sharedCoverSnapshot = sharedCoverSceneId.isNotBlank()
    val libraryMotionSnapshot = libraryMotionActive
    // HOME/category navigation already exposes a non-visual ownership preflight one preparation
    // frame before GenericPivot becomes active. The scroll path consumes this same signal to cancel
    // an in-flight fling without changing geometry. The settled Node must also yield ownership here;
    // otherwise the cold destination publication can briefly make all visual gates look settled and
    // remount the Node for one 120 Hz frame before `library_motion`/custom-provider ownership arrives.
    val libraryPreflightSnapshot = LocalRetainedScenePreflightOwnership.current
    val transitioningSnapshot = state.isTransitioning
    val pinchingSnapshot = state.isPinching
    val boundarySnapshot = state.isBoundaryElasticActive
    val virtualListEdgeSnapshot = state.isVirtualListEdgeActive
    val customProviderSnapshot = holderCustomProvider != null
    val settledNodeIneligibleReason = when {
        !VIRTUAL_LIST_SETTLED_NODE_RENDERER_ENABLED -> "disabled"
        ordinaryPublicationOnly -> "dual_layout_publication"
        selectedCountSnapshot > 0 -> "selection"
        sceneTransformSnapshot -> "scene_transform"
        strictSceneSnapshot -> "strict_scene_holder_window"
        sharedTransitionSnapshot -> "shared_transition"
        // A non-empty sharedCoverSceneId only means this provider can publish cover endpoints. The
        // settled Node already publishes those endpoints itself; it must yield only while an actual
        // shared transition is active (handled by sharedTransitionSnapshot above). Treating the
        // capability as active motion left Folders/category pages permanently on the compatibility
        // renderer and forced OUTER -> CLEAR -> NONE at every navigation endpoint.
        // With the permanent presentation host, HOME/category preflight is publication-only.
        // Do not replace the currently visible settled publisher with the compatibility Compose
        // renderer for one frame: that double-renders the same holders immediately when the back
        // gesture arms and is the remaining start-of-gesture full-screen flash. Reference keeps the
        // attached VirtualList/ArtworkItemNodes untouched while it prepares the destination provider.
        libraryPreflightSnapshot && hostPersistentRuntime == null -> "library_preflight"
        libraryMotionSnapshot -> "library_motion"
        transitioningSnapshot -> "internal_transition"
        pinchingSnapshot -> "pinch"
        boundarySnapshot -> "boundary_elastic"
        else -> "settled"
    }
    val settledNodeEligible = settledNodeIneligibleReason == "settled"
    val persistentAndroidVirtualListOwner =
        hostPersistentRuntime != null &&
            LocalVirtualListPresentationHostExternal.current &&
            ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW
    val physicalInternalZoomOwner =
        persistentAndroidVirtualListOwner &&
            !customProviderSnapshot &&
            (transitioningSnapshot || pinchingSnapshot)
    // A heterogeneous provider does not replace VirtualList itself; it only supplies a different
    // holder type for the slots it handles. Keep the retained Node publisher alive for ordinary
    // slots (or an empty settled frame when every visible HOME slot is custom) and overlay only the
    // handled Compose holders. Treating the mere presence of a custom provider as a whole-list
    // fallback caused OUTER -> CLEAR -> NONE at the HOME endpoint.
    val hybridCustomOverlay = settledNodeEligible && customProviderSnapshot

    // Diagnostics are ownership-edge only, never frame-driven. This lets device traces distinguish an
    // intentional geometry-owner fallback from an accidental Node remount without changing geometry,
    // holder identity, artwork admission, or transition progress. Every printed gate value comes from
    // the exact same composition snapshot that selected `settledNodeIneligibleReason`.
    LaunchedEffect(settledNodeIneligibleReason) {
        val renderWindow = if (range.isEmpty()) "empty" else "${range.first}-${range.last}"
        val strictWindow = if (strictVisualRange.isEmpty()) "empty" else "${strictVisualRange.first}-${strictVisualRange.last}"
        Log.i(
            "RawVirtualList",
            "VIRTUAL_LIST_NODE OWNER owner=${if (settledNodeEligible) "node" else "fallback"}" +
                " reason=$settledNodeIneligibleReason render=$renderWindow strict=$strictWindow" +
                " selected=$selectedCountSnapshot sceneTransform=$sceneTransformSnapshot" +
                " strictScene=$strictSceneSnapshot shared=$sharedTransitionSnapshot" +
                " sharedCover=$sharedCoverSnapshot libraryPreflight=$libraryPreflightSnapshot" +
                " libraryMotion=$libraryMotionSnapshot transitioning=$transitioningSnapshot" +
                " pinching=$pinchingSnapshot" +
                " boundary=$boundarySnapshot virtualListEdge=$virtualListEdgeSnapshot"
        )
    }

    val preservePresentationForLibraryHandoff = shouldPreserveVirtualListPresentationForLibraryHandoff(
        hasPersistentLibraryRuntime = hostPersistentRuntime != null,
        libraryPreflightActive = libraryPreflightSnapshot,
        libraryMotionActive = libraryMotionSnapshot,
        // At the GenericPivot endpoint the destination provider briefly reports its own internal
        // transition while replacing the source LayoutRes. Clearing the persistent node in that
        // exact frame produces OUTER -> CLEAR -> NONE and exposes/overlaps the Compose fallback.
        // Keep the last outer target pixels until the destination publishes SETTLED atomically.
        endpointProviderSwapActive = settledNodeIneligibleReason == "internal_transition",
    )
    if (
        !settledNodeEligible &&
        !ordinaryPublicationOnly &&
        !preservePresentationForLibraryHandoff &&
        !physicalInternalZoomOwner
    ) {
        // A compatibility renderer now owns the visible endpoint (selection,
        // internal zoom/boundary fallback, etc.). Retire the previous node presentation in the same
        // successful apply in which that renderer is mounted. Keeping it merely because the runtime
        // is persistent leaves the previous category painted underneath/over HOME after a back
        // transition. Preflight and active GenericPivot are excluded above, so their last valid
        // presentation still survives until the incoming provider has published its replacement.
        SideEffect {
            if (
                hostPersistentRuntime != null &&
                ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW
            ) {
                settledNodeRuntime.suspendViewGroupPresentationPreservingHolders()
            } else {
                settledNodeRuntime.clearPresentation()
            }
        }
    }

    // HOME/root-library scene motion is fully owned by the fixed Android VirtualList host. Endpoint
    // publication above has already updated CURRENT/RETAINED records; composing compatibility item
    // holders here would create a second renderer for the same physical holder population.
    if (
        persistentAndroidVirtualListOwner &&
        (ordinaryPublicationOnly || libraryMotionSnapshot || physicalInternalZoomOwner)
    ) return

    if (settledNodeEligible) {
        val nodeController = remember(settledNodeRuntime) {
            VirtualListSettledNodeController(
                coverTargetOwner = settledNodeRuntime.coverTargetOwner,
                artworkBitmapProvider = settledNodeRuntime::displayedArtworkBitmap,
            )
        }
        val nodeFrame = powerRoleFrame

        Box(modifier = modifier) {
            // Preserve the exact old fractional-scroll geometry for section headers. Only the song
            // draw owner moved into the Node; the geometry math and header owner did not.
            val sectionHeaderModifier = if (nodeScrollInvalidationOwner != null) {
                Modifier
                    .fillMaxSize()
                    .virtualListRenderRemainderTranslation(nodeScrollInvalidationOwner)
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = -scrollRemainderProvider().toFloat() }
            }
            Box(modifier = sectionHeaderModifier) {
                ComposeVirtualListSectionHeaders(
                    headers = sectionHeaders,
                    geometry = geometry,
                    scrollYPx = layoutScrollYPx,
                    viewportHeightPx = viewportHeightPx,
                    boundaryScaleProvider = boundaryScaleProvider,
                    content = sectionHeaderContent
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        val windowPosition = coordinates.positionInWindow()
                        nodeController.updateRoot(
                            left = bounds.left,
                            top = bounds.top,
                            leftInWindow = windowPosition.x,
                            topInWindow = windowPosition.y,
                        )
                    }
                    .virtualListSettledNode(
                        frame = nodeFrame,
                        controller = nodeController,
                        runtime = settledNodeRuntime,
                        retainRuntimeOnDetach = hostPersistentRuntime != null,
                    )
            )
        }
        if (!hybridCustomOverlay || persistentAndroidVirtualListOwner) return
    }

    // The persistent holder pool survives this compatibility endpoint, but its old presentation
    // has been retired above. This separates reusable holder identity from visual ownership: HOME
    // can draw its custom population without the previous category remaining on screen.

    // Geometry-specialized fallback. Interactive holders still need the translated hit-test/layout
    // surface, so keep a narrow compatibility snapshot here rather than turning the ordinary Node
    // path back into broad per-pixel Compose state.
    val fallbackRemainderState = if (nodeScrollInvalidationOwner != null) {
        rememberVirtualListRenderRemainderPx(nodeScrollInvalidationOwner)
    } else {
        null
    }
    Box(
        modifier = modifier.graphicsLayer {
            translationY = -(fallbackRemainderState?.value ?: scrollRemainderProvider()).toFloat()
        }
    ) {
        if (!hybridCustomOverlay) {
            ComposeVirtualListSectionHeaders(
                headers = sectionHeaders,
                geometry = geometry,
                scrollYPx = layoutScrollYPx,
                viewportHeightPx = viewportHeightPx,
                boundaryScaleProvider = boundaryScaleProvider,
                content = sectionHeaderContent
            )
        }
        if (range.isEmpty()) return@Box
        // Iterate logical attached rows and resolve their stable physical holder id by identity.
        // The slot survives provider/layout ownership changes as long as the same key remains attached.
        for (index in range.first..range.last) {
            val song = songs.getOrNull(index) ?: continue
            val position = positionFor(
                index = index,
                geometry = geometry,
                mode = mode,
                scrollYPx = layoutScrollYPx,
            )
            if (position.isEmpty()) continue
            val customHandled = holderCustomProvider?.handles?.invoke(song, index) == true
            val physicalKey = virtualListPhysicalRenderKey(physicalKeyPrefix, song, index, customHandled)
            physicalLayoutSlots?.publish(
                frameId = physicalLayoutFrameId,
                key = physicalKey,
                role = physicalLayoutRole,
                layout = VirtualListPhysicalLayoutRes(
                    index = index,
                    mode = mode,
                    params = params,
                    position = position,
                    drawContentEnabled = index in strictVisualRange,
                    artworkVisible = index in artworkVisibleRange,
                    renderRemainderPx = scrollRemainderProvider(),
                ),
            )
            if (suppressHolderRenderingForOuterDual) continue
            if (hybridCustomOverlay && !customHandled) continue
            if (ordinaryPublicationOnly && !customHandled) continue
            val physicalSlotId = physicalSlotPool.slotIdForKey(physicalKey)
            if (physicalSlotId < 0) continue
            val isPlaying = if (playingSongId > 0L) {
                song.id == playingSongId
            } else {
                index == currentPlayingIndex
            }
            val sharedCoverElementId = sharedCoverElementIdProvider(song, index)
            val hideCover = (hidePlayingCover && isPlaying) ||
                settledSharedCoverRegistry.shouldSuppressPhysicalHero(sharedCoverElementId)
            val artworkVisible = index in artworkVisibleRange
            val artworkPriority = if (artworkVisible) {
                BitmapRequest.Priority.LOADING_LIST
            } else {
                BitmapRequest.Priority.LOADING_PREFETCH
            }
            key("holder-call-$physicalSlotId") {
                val noopCoverBoundsChanged = remember { { _: RectF? -> } }
                val noopCoverTargetChanged = remember { { _: CoverTransitionTarget? -> } }
                val coverBoundsChanged = if (isPlaying) onPlayingCoverBoundsChanged else noopCoverBoundsChanged
                val coverTargetChanged = when {
                    sharedCoverSceneId.isNotBlank() -> { target: CoverTransitionTarget? ->
                        onSharedCoverTargetChanged(song, index, target)
                        if (isPlaying) onPlayingCoverTargetChanged(target)
                    }
                    isPlaying -> onPlayingCoverTargetChanged
                    else -> noopCoverTargetChanged
                }
                val itemClick = remember(
                    song.id,
                    song.path,
                    onSongClick,
                    onPlayingCoverBoundsChanged,
                    onPlayingCoverTargetChanged,
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
                physicalHolderOwner.Render(
                    slotId = physicalSlotId,
                    spec = VirtualListSettledHolderSpec(
                        VirtualListPhysicalHolderRenderSpec(
                            slotId = physicalSlotId,
                            song = song,
                            index = index,
                            mode = mode,
                            params = params,
                            position = position,
                            layoutPosition = position,
                            deferBitmapLoad = !artworkVisible,
                            drawContentEnabled = index in strictVisualRange,
                            isPlaying = isPlaying,
                            isSelected = index in selectedPositions,
                            selectionActive = selectedPositions.isNotEmpty(),
                            boundaryScaleProvider = boundaryScaleProvider,
                            virtualListEdgeOvershootProvider = { state.virtualListEdgeOvershootPx },
                            viewportHeightPx = viewportHeightPx,
                            sceneTransform = sceneTransform,
                            hideCover = hideCover,
                            interactionActive = interactionActive,
                            artworkAnimationsEnabled = artworkAnimationsEnabled,
                            artworkPriority = artworkPriority,
                            marqueeVisible = settledMarqueeVisible,
                            sharedCoverSceneId = sharedCoverSceneId,
                            sharedCoverElementId = sharedCoverElementId,
                            onCoverBoundsChanged = coverBoundsChanged,
                            onCoverTargetChanged = coverTargetChanged,
                            onClick = itemClick,
                            onLongClick = itemLongClick,
                            customProvider = holderCustomProvider,
                        )
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePhysicalVirtualListZoomLayer(
    snapshot: ComposeVirtualListTransitionSnapshot,
    progressProvider: () -> Float,
    elasticScaleProvider: () -> Float,
    playingSongId: Long,
    currentPlayingIndex: Int,
    hidePlayingCover: Boolean,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    sharedCoverSceneId: String,
    sharedCoverElementIdProvider: (AudioFile, Int) -> String,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalKeyPrefix: Any?,
    runtime: VirtualListSettledNodeRuntime,
    onPopulationPrepared: () -> Unit,
    contentBindAllowedProvider: () -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val transitionPhysicalKeys = remember(snapshot.sessionId, snapshot.transitionItems, physicalKeyPrefix) {
        snapshot.transitionItems.map {
            virtualListPhysicalRenderKey(physicalKeyPrefix, it.song, it.index, customHandled = false)
        }
    }
    // The internal zoom is another LayoutRes owner on the same VirtualList. Claim the exact union of
    // source/target-visible keys before binding the two endpoint records; existing keys retain their
    // physical slots and target-only keys are attached once before the first motion tick.
    physicalSlotPool.beginFrame(transitionPhysicalKeys)

    val zoomInTransition = virtualListModeOrder(snapshot.targetMode) > virtualListModeOrder(snapshot.sourceMode)
    val pivotX = snapshot.viewportWidthPx * 0.5f
    val pivotY = snapshot.viewportHeightPx * 0.5f
    val holderItems = ArrayList<VirtualListSettledNodeItem>(snapshot.transitionItems.size)
    val motionItems = ArrayList<VirtualListOuterTransitionNodeItem>(snapshot.transitionItems.size)
    for (item in snapshot.transitionItems) {
        val slotId = physicalSlotPool.slotIdForKey(
            virtualListPhysicalRenderKey(physicalKeyPrefix, item.song, item.index, customHandled = false)
        )
        if (slotId < 0) continue
        val sourceEndpoint = when (item.kind) {
            ComposeTransitionItemKind.TARGET_ONLY -> oneSlotRenderFrame(
                basePosition = item.targetPosition,
                scaleBase = if (zoomInTransition) 0.5f else 1.5f,
                transitionFraction = 1f,
                pivotX = pivotX,
                pivotY = pivotY,
            )
            else -> item.sourcePosition
        }
        val targetEndpoint = when (item.kind) {
            ComposeTransitionItemKind.SOURCE_ONLY -> oneSlotRenderFrame(
                basePosition = item.sourcePosition,
                scaleBase = if (zoomInTransition) 1.5f else 0.5f,
                transitionFraction = 1f,
                pivotX = pivotX,
                pivotY = pivotY,
            )
            else -> item.targetPosition
        }
        val sourceRects = if (item.kind == ComposeTransitionItemKind.TARGET_ONLY) {
            item.targetRects
        } else {
            item.sourceRects
        }
        val targetRects = if (item.kind == ComposeTransitionItemKind.SOURCE_ONLY) {
            item.sourceRects
        } else {
            item.targetRects
        }
        val isPlaying = if (playingSongId > 0L) item.song.id == playingSongId else item.index == currentPlayingIndex
        val decodeSide = virtualListDecodeSideForMode(
            maxOf(
                sourceRects.cover.width,
                sourceRects.cover.height,
                targetRects.cover.width,
                targetRects.cover.height,
            ).coerceAtLeast(1)
        )
        val holderItem = VirtualListSettledNodeItem(
            slotId = slotId,
            song = item.song,
            index = item.index,
            position = sourceEndpoint,
            rects = sourceRects,
            deferBitmapLoad = false,
            drawContentEnabled = true,
            isPlaying = isPlaying,
            hideCover = hidePlayingCover && isPlaying,
            interactionActive = true,
            artworkAnimationsEnabled = false,
            artworkPriority = BitmapRequest.Priority.LOADING_LIST,
            coverDecodeSide = decodeSide,
            hasCollectionMetaIcon = item.song.hasVirtualListCollectionMetaIcon(),
            hasFolderMetaIcon = item.song.encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING,
            isGrid = snapshot.sourceMode.isGrid,
            sharedCoverElementId = sharedCoverElementIdProvider(item.song, item.index),
        )
        holderItems += holderItem
        // Even SOURCE_ONLY/TARGET_ONLY are represented by two LayoutRes records. Their missing
        // endpoint is the exact old oneSlotRenderFrame(..., f=1) geometry with alpha=0, so simple
        // LayoutRes interpolation reproduces the compatibility curve without a second View tree.
        motionItems += VirtualListOuterTransitionNodeItem(
            holderItem = holderItem,
            kind = VirtualListOuterTransitionKind.SHARED,
            currentPosition = targetEndpoint,
            retainedPosition = sourceEndpoint,
            currentRects = targetRects,
            retainedRects = sourceRects,
        )
    }
    val marqueeAllowed = LongTextMotionState.enabled &&
        LongTextMotionState.enabledEverywhere &&
        LongTextMotionState.LocalListMarqueeVisibility.current
    val holderFrame = VirtualListSettledNodeFrame(
        items = holderItems,
        density = density.density,
        textDensity = density.density * density.fontScale,
        dark = ThemeManager.isDarkMode(context),
        configuredTypeface = FontManager.typeface,
        resources = context.resources,
        defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
        marqueeAllowed = marqueeAllowed,
        inputEnabled = false,
        scrollRemainderProvider = { 0 },
        scrollInvalidationOwner = null,
        viewportHeightPx = snapshot.viewportHeightPx,
        virtualListEdgeOvershootProvider = { 0f },
        onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
        onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
        onSongClick = onSongClick,
        onSongLongClick = onSongLongClick,
        onCopySongInfo = { song -> copySongInfoToClipboard(context, song) },
        sharedCoverSceneId = sharedCoverSceneId,
        sharedCoverElementIdProvider = sharedCoverElementIdProvider,
    )
    val physicalFrame = VirtualListOuterTransitionNodeFrame(
        holderFrame = holderFrame,
        items = motionItems,
        currentFractionProvider = progressProvider,
        currentTransform = null,
        retainedTransform = null,
        holderScaleProvider = elasticScaleProvider,
        contentBindAllowedProvider = contentBindAllowedProvider,
    )
    SideEffect {
        runtime.publishOuterFrame(physicalFrame)
        onPopulationPrepared()
    }

    Box(modifier = modifier) {
        ComposeVirtualListTransitionSectionHeaders(
            headers = snapshot.sectionHeaders,
            sourceGeometry = snapshot.sourceGeometry,
            targetGeometry = snapshot.targetGeometry,
            sourceScrollYPx = snapshot.scrollModel.sourceScrollYPx,
            targetScrollYPx = snapshot.scrollModel.targetScrollYPx,
            viewportHeightPx = snapshot.viewportHeightPx,
            progressProvider = progressProvider,
            elasticScaleProvider = elasticScaleProvider,
            content = sectionHeaderContent,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeVirtualListTransitionLayer(
    snapshot: ComposeVirtualListTransitionSnapshot,
    progressProvider: () -> Float,
    elasticScaleProvider: () -> Float,
    playingSongId: Long,
    currentPlayingIndex: Int,
    selectedPositions: Set<Int>,
    hidePlayingCover: Boolean,
    sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit,
    onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    physicalSlotPool: VirtualListPhysicalSlotPool,
    physicalKeyPrefix: Any?,
    physicalHolderOwner: VirtualListPhysicalHolderOwner,
    modifier: Modifier = Modifier
) {
    val songs = snapshot.songs
    val sourceGeometry = snapshot.sourceGeometry
    val targetGeometry = snapshot.targetGeometry
    val scrollModel = snapshot.scrollModel
    val heightPx = snapshot.viewportHeightPx
    val transitionPhysicalKeys = remember(snapshot.sessionId, snapshot.transitionItems, physicalKeyPrefix) {
        snapshot.transitionItems.map {
            virtualListPhysicalRenderKey(physicalKeyPrefix, it.song, it.index, customHandled = false)
        }
    }
    physicalSlotPool.beginFrame(transitionPhysicalKeys)

    Box(modifier = modifier) {
        ComposeVirtualListTransitionSectionHeaders(
            headers = snapshot.sectionHeaders,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            sourceScrollYPx = scrollModel.sourceScrollYPx,
            targetScrollYPx = scrollModel.targetScrollYPx,
            viewportHeightPx = heightPx,
            progressProvider = progressProvider,
            elasticScaleProvider = elasticScaleProvider,
            content = sectionHeaderContent
        )
        for (item in snapshot.transitionItems) {
            val song = item.song
            val index = item.index
            val physicalSlotId = physicalSlotPool.slotIdForKey(
                virtualListPhysicalRenderKey(physicalKeyPrefix, song, index, customHandled = false)
            )
            val isPlaying = if (playingSongId > 0L) {
                song.id == playingSongId
            } else {
                index == currentPlayingIndex
            }

            if (physicalSlotId >= 0) {
                physicalHolderOwner.Render(
                    slotId = physicalSlotId,
                    spec = VirtualListZoomHolderSpec(
                        slotId = physicalSlotId,
                        snapshot = snapshot,
                        item = item,
                        progressProvider = progressProvider,
                        elasticScaleProvider = elasticScaleProvider,
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
                        onLongClick = { onSongLongClick(song, index) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ComposeVirtualListTransitionItem(
    compositionSlot: Any,
    physicalSlotId: Int = -1,
    song: AudioFile,
    index: Int,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    position: ComposeItemPosition,
    layoutPosition: ComposeItemPosition = position,
    deferBitmapLoad: Boolean = false,
    drawContentEnabled: Boolean = true,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScaleProvider: () -> Float = { 1f },
    virtualListEdgeOvershootProvider: () -> Float = { 0f },
    viewportHeightPx: Int = 0,
    sceneTransform: RetainedSceneItemTransform? = null,
    hideCover: Boolean = false,
    interactionActive: Boolean = false,
    artworkAnimationsEnabled: Boolean = true,
    artworkPriority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
    marqueeVisible: Boolean = true,
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
        .then(
            if (!interactionActive || isPlaying) {
                Modifier.virtualListSceneTransitionItem(
                    sceneId = sharedCoverSceneId,
                    itemId = sharedCoverElementId
                )
            } else {
                Modifier
            }
        )


    key(compositionSlot) {
        Box(
            modifier = itemModifier
                .graphicsLayer {
                    // Scene GenericPivot is owned by the viewport layout layer above. This physical
                    // holder keeps only its item-local boundary/visibility properties. VirtualList
                    // edge deformation is the same per-holder LayoutRes mutation used by the Node
                    // renderer; never fold it back into a whole-viewport transform.
                    val boundaryScale = boundaryScaleProvider().coerceIn(0.9105f, 1.0895f)
                    val edgeOvershoot = virtualListEdgeOvershootProvider()
                    val maxEdgeOvershoot = VIRTUAL_LIST_MAX_OVERSHOOT_DP * density.density
                    val edgeFraction = virtualListHolderEdgeFraction(
                        holderTopPx = layoutPosition.bounds.top.toFloat(),
                        holderBottomPx = layoutPosition.bounds.bottom.toFloat(),
                        viewportHeightPx = viewportHeightPx,
                    )
                    val edgeScaleY = virtualListHolderScaleY(
                        edgeOvershoot,
                        maxEdgeOvershoot,
                        edgeFraction,
                    )
                    val edgeTranslationY = virtualListHolderTranslationY(
                        overshootPx = edgeOvershoot,
                        maxOvershootPx = maxEdgeOvershoot,
                        holderShiftPx = VIRTUAL_LIST_HOLDER_SHIFT_DP * density.density,
                        holderFraction = edgeFraction,
                    )
                    val sceneScale = sceneTransform?.scaleProvider?.invoke()?.coerceIn(0.5f, 1.5f) ?: 1f
                    if (sceneTransform != null) {
                        val centerX = (layoutPosition.bounds.left + layoutPosition.bounds.right) * 0.5f
                        val centerY = (layoutPosition.bounds.top + layoutPosition.bounds.bottom) * 0.5f
                        translationX = (centerX - sceneTransform.pivotX) * (sceneScale - 1f)
                        translationY = (centerY - sceneTransform.pivotY) * (sceneScale - 1f)
                    } else {
                        translationX = 0f
                        translationY = edgeTranslationY
                    }
                    scaleX = boundaryScale * sceneScale
                    scaleY = boundaryScale * sceneScale * edgeScaleY
                    alpha = if (drawContentEnabled) {
                        position.alpha.coerceIn(0f, 1f) *
                            contentAlpha.coerceIn(0f, 1f) *
                            (sceneTransform?.alphaProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f)
                    } else {
                        0f
                    }
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
        ) {
            ComposeVirtualListDrawnItem(
                song = song,
                index = index,
                mode = mode,
                params = params,
                position = position,
                deferBitmapLoad = deferBitmapLoad,
                isPlaying = isPlaying,
                isSelected = isSelected,
                selectionActive = selectionActive,
                boundaryScale = 1f,
                hideCover = hideCover,
                interactionActive = interactionActive,
                artworkAnimationsEnabled = artworkAnimationsEnabled,
                artworkPriority = artworkPriority,
                marqueeVisible = marqueeVisible,
                sharedCoverSceneId = sharedCoverSceneId,
                sharedCoverElementId = sharedCoverElementId,
                physicalSlotId = physicalSlotId,
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
private fun ComposeVirtualListInterpolatedCustomHolder(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    sourcePosition: ComposeItemPosition,
    targetPosition: ComposeItemPosition,
    progressProvider: () -> Float,
    customProvider: VirtualListCustomProvider,
) {
    val density = LocalDensity.current
    val stableHolderWidth = maxOf(sourcePosition.width, targetPosition.width).coerceAtLeast(1)
    val stableHolderHeight = maxOf(sourcePosition.height, targetPosition.height).coerceAtLeast(1)
    key(compositionSlot) {
        Box(
            modifier = Modifier
                .offset {
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    androidx.compose.ui.unit.IntOffset(frame.bounds.left, frame.bounds.top)
                }
                .requiredSize(
                    width = with(density) { stableHolderWidth.toDp() },
                    height = with(density) { stableHolderHeight.toDp() },
                )
                .graphicsLayer {
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    alpha = frame.alpha
                    scaleX = frame.scaleX
                    scaleY = frame.scaleY
                    transformOrigin = TransformOrigin(
                        pivotFractionX = virtualListTransitionElasticOriginFraction(frame.width, stableHolderWidth),
                        pivotFractionY = virtualListTransitionElasticOriginFraction(frame.height, stableHolderHeight),
                    )
                    clip = false
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            customProvider.content(song, index, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ComposeVirtualListInterpolatedItem(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    sourceMode: ComposeVirtualListDisplayMode,
    targetMode: ComposeVirtualListDisplayMode,
    sourcePosition: ComposeItemPosition,
    targetPosition: ComposeItemPosition,
    sourceRects: ComposeTransitionRects,
    targetRects: ComposeTransitionRects,
    progressProvider: () -> Float,
    elasticScaleProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    hideCover: Boolean = false,
    deferArtworkLoad: Boolean = false,
    boundaryScale: Float = 1f,
    marqueeVisible: Boolean = true,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit
) {
    TransitionPerfTrace.count(TransitionPerfEvent.TRANSITION_ITEM_COMPOSE)
    val density = LocalDensity.current
    // Keep the physical holder measured at one stable envelope for the whole pinch.  The old
    // implementation changed the Box width/height on every pointer frame and then changed the
    // cover's measured size again inside that Box, so a 3/4-column zoom forced two measure/layout
    // passes per attached holder per vsync. VirtualList keeps its holder View attached and mutates
    // LayoutRes/View properties instead.  Use the larger endpoint envelope and clip/draw the live
    // interpolated bounds from draw/layer phases; this preserves the exact visible geometry without
    // making Compose remeasure the holder tree at 120 Hz.
    val stableHolderWidth = maxOf(sourcePosition.width, targetPosition.width).coerceAtLeast(1)
    val stableHolderHeight = maxOf(sourcePosition.height, targetPosition.height).coerceAtLeast(1)
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
                    width = with(density) { stableHolderWidth.toDp() },
                    height = with(density) { stableHolderHeight.toDp() },
                )
                .drawWithContent {
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    val elasticScale = elasticScaleProvider().coerceIn(0.9105f, 1.0895f)
                    val overflowX = virtualListTransitionElasticClipOverflowPx(frame.width, elasticScale)
                    val overflowY = virtualListTransitionElasticClipOverflowPx(frame.height, elasticScale)
                    clipRect(
                        left = -overflowX,
                        top = -overflowY,
                        right = frame.width.coerceAtLeast(1).toFloat() + overflowX,
                        bottom = frame.height.coerceAtLeast(1).toFloat() + overflowY,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
                .graphicsLayer {
                    TransitionPerfTrace.count(TransitionPerfEvent.TRANSITION_LAYER_UPDATE)
                    val frame = dualSlotRenderFrame(sourcePosition, targetPosition, progressProvider())
                    val elasticScale = elasticScaleProvider().coerceIn(0.9105f, 1.0895f)
                    translationX = 0f
                    translationY = 0f
                    alpha = frame.alpha
                    scaleX = frame.scaleX * elasticScale
                    scaleY = frame.scaleY * elasticScale
                    transformOrigin = TransformOrigin(
                        pivotFractionX = virtualListTransitionElasticOriginFraction(frame.width, stableHolderWidth),
                        pivotFractionY = virtualListTransitionElasticOriginFraction(frame.height, stableHolderHeight),
                    )
                    clip = false
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
                hideCover = hideCover,
                deferArtworkLoad = deferArtworkLoad,
                marqueeVisible = marqueeVisible,
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
private fun ComposeVirtualListOneSlotItem(
    compositionSlot: Any,
    song: AudioFile,
    index: Int,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    basePosition: ComposeItemPosition,
    fadeOut: Boolean,
    pivotX: Float,
    pivotY: Float,
    oneSlotScaleBase: Float,
    progressProvider: () -> Float,
    elasticScaleProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    marqueeVisible: Boolean = true,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit
) {
    TransitionPerfTrace.count(TransitionPerfEvent.TRANSITION_ITEM_COMPOSE)
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
                        pivotY = pivotY,
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
                    TransitionPerfTrace.count(TransitionPerfEvent.TRANSITION_LAYER_UPDATE)
                    val p = progressProvider()
                    val f = if (fadeOut) p else 1f - p
                    val elasticScale = elasticScaleProvider().coerceIn(0.9105f, 1.0895f)
                    val frame = oneSlotRenderFrame(
                        basePosition = basePosition,
                        scaleBase = oneSlotScaleBase,
                        transitionFraction = f,
                        pivotX = pivotX,
                        pivotY = pivotY,
                    )
                    translationX = 0f
                    translationY = 0f
                    alpha = frame.alpha
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    scaleX = frame.scaleX * elasticScale
                    scaleY = frame.scaleY * elasticScale
                    clip = false
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
        ) {
            ComposeVirtualListDrawnItem(
                song = song,
                index = index,
                mode = mode,
                params = params,
                position = basePosition,
                // Reference transition holders remain real ArtworkImageNode consumers. The geometry
                // clock owns position/scale, but a still-bound target/source holder may resolve its
                // wrapper during the transition instead of remaining a cache-only ghost.
                deferBitmapLoad = false,
                isPlaying = isPlaying,
                isSelected = isSelected,
                selectionActive = selectionActive,
                boundaryScale = boundaryScale,
                marqueeVisible = marqueeVisible,
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
private fun ComposeVirtualListDrawnItem(
    song: AudioFile,
    index: Int,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    position: ComposeItemPosition,
    physicalSlotId: Int = -1,
    deferBitmapLoad: Boolean = false,
    isPlaying: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean = false,
    boundaryScale: Float = 1f,
    hideCover: Boolean = false,
    interactionActive: Boolean = false,
    artworkAnimationsEnabled: Boolean = true,
    artworkPriority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
    marqueeVisible: Boolean = true,
    sharedCoverSceneId: String = "",
    sharedCoverElementId: String = "",
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val customProvider = LocalVirtualListCustomProvider.current
    if (customProvider?.handles?.invoke(song, index) == true) {
        customProvider.content(song, index, modifier.fillMaxSize())
        return
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val sharedCoverRegistry = LocalSharedCoverRegistry.current
    val sharedTransitionSpec = LocalSharedTransitionSpec.current
    val dark = ThemeManager.isDarkMode(context)
    val colors = virtualListColors(dark, isPlaying, isSelected)
    val edgeScale = boundaryScale.coerceIn(0.9105f, 1.0895f)
    val artworkRadiusType = song.virtualListArtworkRadiusType()
    val rects = remember(position, mode, params, density.density, edgeScale, artworkRadiusType) {
        val base = composeArtworkItemSceneRects(position, mode, params, density.density, artworkRadiusType)
        if (edgeScale == 1f) {
            base
        } else {
            base.scaledAbout(
                scale = edgeScale,
                pivotX = position.width * 0.5f,
                pivotY = position.height * 0.5f
            )
        }
    }
    val coverSize = max(rects.cover.width, rects.cover.height).coerceAtLeast(1)
    val coverDecodeSide = remember(coverSize, mode) {
        virtualListDecodeSideForMode(requestedSide = coverSize)
    }
    val title = song.displayName
    val subtitle = song.subtitle()
    val meta = song.metaText()
    val hasFolderMetaIcon = song.encodingFormat == VIRTUAL_LIST_FOLDER_ENCODING
    val hasCollectionMetaIcon = song.hasVirtualListCollectionMetaIcon()
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
    val animateListText = LongTextMotionState.enabled && LongTextMotionState.enabledEverywhere
    val listMarqueeVisible = LongTextMotionState.LocalListMarqueeVisibility.current
    val titleText = rememberVirtualListPreparedText(
        text = title,
        rect = rects.title,
        density = density.density * density.fontScale,
        bold = true,
        animate = animateListText,
        configuredTypeface = configuredTypeface
    )
    val subtitleText = rememberVirtualListPreparedText(
        text = subtitle,
        rect = rects.subtitle,
        density = density.density * density.fontScale,
        bold = false,
        animate = animateListText,
        configuredTypeface = configuredTypeface
    )
    val metaText = rememberVirtualListPreparedText(
        text = meta,
        rect = rects.meta,
        density = density.density * density.fontScale,
        bold = false,
        leftInsetPx = if (hasCollectionMetaIcon) 16f * density.density else 0f,
        animate = animateListText,
        configuredTypeface = configuredTypeface
    )
    val hasMarqueeOverflow = titleText.overflowPx > 0.5f ||
        subtitleText.overflowPx > 0.5f ||
        metaText.overflowPx > 0.5f
    val listMarqueeEnabled =
        LocalUiFrameAnimationActive.current &&
            animateListText && listMarqueeVisible && marqueeVisible &&
            hasMarqueeOverflow
    DisposableEffect(listMarqueeEnabled) {
        if (listMarqueeEnabled) LongTextMotionState.acquireMarquee()
        onDispose {
            if (listMarqueeEnabled) LongTextMotionState.releaseMarquee()
        }
    }
    // Reference only stops MarqueeTextNode when the holder itself becomes alpha=0. Keep
    // visible holders moving during scene/zoom transitions, but do not read the shared frame clock
    // from composition: the Canvas draw observer can invalidate pixels without recomposing or
    // relaying out every visible grid cell.

    // The bounds are only read when this physical holder is clicked. Keeping them in a stable
    // slot avoids invalidating every list cell on every scroll-layout callback; the old
    // mutableState value made dense 3/4-column flings recompose the artwork and text together.
    val rootBounds = remember { arrayOfNulls<RectF>(1) }
    val rootWindowPosition = remember {
        arrayOfNulls<androidx.compose.ui.geometry.Offset>(1)
    }
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

    fun clickedSharedCoverSnapshot(): SharedCoverSnapshot? {
        if (
            sharedCoverSceneId.isBlank() ||
            !sharedCoverElementId.startsWith("cover:")
        ) {
            return null
        }
        val cover = rects.cover
        if (cover.width <= 0 || cover.height <= 0) return null
        val origin = rootWindowPosition[0]
        if (origin == null && physicalSlotId < 0) return null
        // The reference source endpoint is the concrete attached item holder. It does not need a
        // separate window-position callback before ItemToHeader can start: shared-holder promotion reads the
        // current View geometry at promotion time. Raw's Compose compatibility holder can be
        // clicked before onGloballyPositioned has populated [rootWindowPosition] for a newly
        // attached collection item. As long as the item has a real physical slot, publish a
        // slot-authoritative snapshot and let the promoted Android holder provide exact start
        // geometry. Coordinate-only snapshots remain disallowed below by the physicalSlotId gate.
        val left = (origin?.x ?: position.bounds.left.toFloat()) + cover.left
        val top = (origin?.y ?: position.bounds.top.toFloat()) + cover.top
        return SharedCoverSnapshot(
            sceneId = sharedCoverSceneId,
            elementId = sharedCoverElementId,
            boundsInWindow = Rect(
                left = left,
                top = top,
                right = left + cover.width,
                bottom = top + cover.height,
            ),
            coverKey = song.coverKey,
            radiusDp = rects.coverRadiusDp,
            itemIndex = index,
            physicalSlotId = physicalSlotId,
            itemViewportBounds = Rect(
                left = position.bounds.left.toFloat(),
                top = position.bounds.top.toFloat(),
                right = position.bounds.right.toFloat(),
                bottom = position.bounds.bottom.toFloat(),
            ),
        )
    }

    val rootBoundsModifier = if (trackRootBounds) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            rootWindowPosition[0] = coordinates.positionInWindow()
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
            .then(if (isPlaying || sharedCoverSceneId.isNotBlank()) Modifier.trackDrawnCoverTarget(rects.cover, rects.coverRadiusDp, song.id, song.coverKey, index, onCoverTargetChanged) else Modifier)
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
            .combinedClickable(
                onClick = {
                    if (!sharedTransitionSpec.active) {
                        // The reference ItemToHeader path starts from the concrete attached item holder.
                        // Raw can reach this Compose compatibility path before the Android holder
                        // click bridge wins. Only freeze when this item is still backed by a real
                        // physical slot; coordinate-only snapshots cannot be promoted into the shared holder.
                        clickedSharedCoverSnapshot()
                            ?.takeIf { it.physicalSlotId >= 0 }
                            ?.let(sharedCoverRegistry::freezeSnapshot)
                        onClick(clickedCoverTarget())
                    }
                },
                onLongClick = {
                    if (!sharedTransitionSpec.active) onLongClick()
                },
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val marqueeElapsedMs = if (listMarqueeEnabled) {
                LongTextMotionState.marqueeElapsedMs
            } else {
                0L
            }
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            if (colors.background.alpha > 0f) {
                virtualListPaint.color = colors.background.toArgb()
                virtualListPaint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, size.width, size.height, virtualListPaint)
            }

            virtualListPaint.shader = null
            // reference implementation artwork image view draws only its resolved artwork RectF. Do not paint a second
            // full-slot translucent plate under a KeepAspect bitmap: for non-square artwork that
            // plate leaks out above/below or left/right and makes the source look letterboxed.
            // Loading/no-art visuals are owned by the artwork node itself.

            drawVirtualListText(
                canvas = canvas,
                text = titleText.display,
                rect = rects.title,
                color = titleColor,
                density = density.density * density.fontScale,
                bold = true,
                typefaceOverride = titleText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = titleText.marqueeOffset(
                    elapsedMs = marqueeElapsedMs,
                    speedPxPerSecond = 42.5f * density.density,
                    enabled = listMarqueeEnabled
                )
            )
            drawVirtualListText(
                canvas = canvas,
                text = subtitleText.display,
                rect = rects.subtitle,
                color = subtitleColor,
                density = density.density * density.fontScale,
                bold = false,
                typefaceOverride = subtitleText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = subtitleText.marqueeOffset(
                    elapsedMs = marqueeElapsedMs,
                    speedPxPerSecond = 42.5f * density.density,
                    enabled = listMarqueeEnabled
                )
            )
            drawVirtualListText(
                canvas = canvas,
                text = metaText.display,
                rect = rects.meta,
                color = metaColor,
                density = density.density * density.fontScale,
                bold = false,
                leftInsetPx = if (hasCollectionMetaIcon) 16f * density.density else 0f,
                typefaceOverride = metaText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = metaText.marqueeOffset(
                    elapsedMs = marqueeElapsedMs,
                    speedPxPerSecond = 42.5f * density.density,
                    enabled = listMarqueeEnabled
                )
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
                with(density) { (if (hasFolderMetaIcon) 14.dp else 12.dp).roundToPx() }
            ).coerceAtLeast(1)
            Image(
                painter = painterResource(if (hasFolderMetaIcon) R.drawable.ic_folder else R.drawable.ic_music_note),
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

        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(rects.cover.left, rects.cover.top)
                }
                .requiredSize(
                    width = with(density) { rects.cover.width.coerceAtLeast(1).toDp() },
                    height = with(density) { rects.cover.height.coerceAtLeast(1).toDp() }
                )
                .virtualListArtwork(
                    key = song.coverKey,
                    externalArtworkPath = song.albumArtPath,
                    decodeSide = coverDecodeSide,
                    priority = artworkPriority,
                    // Provider/decode ownership stays live while a holder is attached, including
                    // scroll, zoom and HOME/category GenericPivot. Existing accepted pixels may remain
                    // frozen on the moving RenderNode, but a cold holder is still allowed to resolve.
                    deferLoad = deferBitmapLoad,
                    // Reference ArtworkItemNode/ArtworkImageNode requests every currently visible bound holder
                    // while the list is moving as well. The provider keeps heavy extraction async and
                    // serial; stale holder callbacks are rejected by identity/generation ownership.
                    // Never-visible/buffer rows remain protected by deferLoad above.
                    hidden = hideCover || shouldHideForSharedCover,
                    cornerRadiusPx = rects.coverRadiusDp * density.density,
                    animateChanges = artworkAnimationsEnabled,
                    defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
                    resources = context.resources,
                )
        )

        SelectionCheckOverlay(
            selected = isSelected,
            listMode = !mode.isGrid,
            itemHeightPx = position.height,
            cover = rects.cover,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SelectionCheckOverlay(
    selected: Boolean,
    listMode: Boolean,
    itemHeightPx: Int,
    cover: ComposeItemRect,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val selectionAlphaProvider = LocalSelectionCheckAlphaProvider.current
    // reference player's ItemTrackSelectBox is a fixed wrap-content multiple-choice indicator with 2dp
    // padding. Android's 24dp choice indicator therefore occupies about 28dp in both list and grid
    // scenes. It is overlaid on the holder; entering selection mode does not shift the artwork/text.
    val boxPx = with(density) { 28.dp.roundToPx() }
    val boxSize = with(density) { boxPx.toDp() }
    val leftPx = if (listMode) {
        with(density) { 16.dp.roundToPx() }
    } else {
        with(density) { 12.dp.roundToPx() }
    }
    val topPx = if (listMode) {
        ((itemHeightPx - boxPx) / 2).coerceAtLeast(0)
    } else {
        with(density) { 12.dp.roundToPx() }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Sample the list-owned clock during draw. Holder/spec replacement no
                        // longer restarts the alpha at either endpoint, so enter and exit remain one
                        // continuous 250ms trajectory across every attached list/grid item.
                        alpha = selectionAlphaProvider().coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = if (selected) Color(0xFF2F7DFF) else Color.Black.copy(alpha = 0.38f),
                            shape = RoundedCornerShape(7.dp)
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

// Reference ArtworkProvider chooses the low wrapper from the actual attached ArtworkImageNode/cover size.
// Do not hard-code a second 3/4/2-column size table here: it over-allocates dense grids (GRID_4
// was always 384px even when the physical cover was much smaller), shrinks the free-wrapper LRU to
// only a few dozen items, and makes an offscreen round trip needlessly decode again.  The provider
// still owns exact bucket/coalescing policy; this layer only asks for the physical pixels it draws.
private const val VIRTUAL_LIST_COVER_DECODE_MIN = 96
private const val VIRTUAL_LIST_COVER_DECODE_MAX = 1024
private const val VIRTUAL_LIST_MAX_RENDER_ITEMS = 128
private const val VIRTUAL_LIST_SETTLED_NODE_RENDERER_ENABLED = true
private const val VIRTUAL_LIST_TRACE_FRAMES = false
private const val VIRTUAL_LIST_TRACE_FRAME_GAP_MS = 24L
private const val VIRTUAL_LIST_TRACE_TAG = "RawVirtualList"
internal fun virtualListDecodeSideForMode(requestedSide: Int): Int =
    requestedSide
        .coerceAtLeast(VIRTUAL_LIST_COVER_DECODE_MIN)
        .coerceAtMost(VIRTUAL_LIST_COVER_DECODE_MAX)


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
    // reference player ItemToHeader moves the concrete item View itself. While that special View owns the
    // artwork, the ordinary list/header artwork lane is not a second pixel owner. No overlay-ready
    // latch or prepared-pair fallback participates in visibility.
    return registry.isPhysicalPromotedElement(elementId)
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
        val hostView = LocalView.current
        val shouldTrack = spec.shouldTrackScene(sceneId)
        val registrationOwner = remember(sceneId, elementId) { Any() }

        DisposableEffect(sceneId, elementId, shouldTrack, registrationOwner) {
            if (!shouldTrack) registry.unregister(sceneId, elementId, registrationOwner)
            onDispose {
                registry.unregister(sceneId, elementId, registrationOwner)
            }
        }

        // A physical Compose slot can be reused for another list item. Scope the cached
        // measurement to the logical holder so an equal-sized replacement is still registered.
        val lastSnapshot = remember(sceneId, elementId) {
            arrayOfNulls<SharedCoverSnapshot>(1)
        }
        onGloballyPositioned { coordinates ->
            if (!shouldTrack) return@onGloballyPositioned
            val pos = coordinates.positionInWindow()
            val left = pos.x + cover.left
            val right = left + cover.width
            // Retained scenes are kept measured two viewports to the side. Their layout is
            // useful for warm-up, but those translated coordinates must never become a shared
            // element endpoint. VirtualList only promotes a holder from the active viewport.
            if (right <= 0f || left >= hostView.width.toFloat()) {
                registry.unregister(sceneId, elementId, registrationOwner)
                lastSnapshot[0] = null
                return@onGloballyPositioned
            }
            val snapshot = SharedCoverSnapshot(
                sceneId = sceneId,
                elementId = elementId,
                boundsInWindow = Rect(
                    left = left,
                    top = pos.y + cover.top,
                    right = right,
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
                registry.register(
                    sceneId = sceneId,
                    elementId = elementId,
                    owner = registrationOwner,
                    snapshot = snapshot
                )
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

private val virtualListPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val virtualListTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

internal fun drawVirtualListText(
    canvas: android.graphics.Canvas,
    text: String,
    rect: ComposeItemRect,
    color: Color,
    density: Float,
    bold: Boolean,
    leftInsetPx: Float = 0f,
    typefaceOverride: Typeface? = null,
    alreadyEllipsized: Boolean = false,
    horizontalOffsetPx: Float = 0f,
    clipToRect: Boolean = true,
) {
    if (text.isBlank() || rect.alpha <= 0f || rect.width <= 0 || rect.height <= 0) return
    val fontSizeSp = rect.fontSizeSp.takeIf { it > 0f } ?: 14f
    virtualListTextPaint.color = color.copy(alpha = color.alpha * rect.alpha.coerceIn(0f, 1f)).toArgb()
    virtualListTextPaint.textSize = fontSizeSp * density
    virtualListTextPaint.typeface = typefaceOverride
        ?: FontManager.resolveTypeface(if (bold) 700 else 400)
    val availableWidth = (rect.width.toFloat() - leftInsetPx).coerceAtLeast(1f)
    val display = if (alreadyEllipsized) {
        text
    } else {
        TextUtils.ellipsize(
            text,
            virtualListTextPaint,
            availableWidth,
            TextUtils.TruncateAt.END
        )
    }
    val displayString = display.toString()
    val fontMetrics = virtualListTextPaint.fontMetrics
    val textInkBounds = AndroidRect().also { bounds ->
        if (displayString.isNotEmpty()) {
            virtualListTextPaint.getTextBounds(displayString, 0, displayString.length, bounds)
        }
    }
    val verticalLayout = resolveNativeTextVerticalLayout(
        containerTop = rect.top.toFloat(),
        containerBottom = rect.top + rect.height.toFloat(),
        canvasTop = 0f,
        canvasBottom = canvas.height.toFloat(),
        ascent = fontMetrics.ascent,
        descent = fontMetrics.descent,
        fontTop = fontMetrics.top,
        fontBottom = fontMetrics.bottom,
        inkTop = textInkBounds.top.toFloat(),
        inkBottom = textInkBounds.bottom.toFloat(),
    )
    val baseline = verticalLayout.baseline
    if (clipToRect) {
        canvas.save()
        canvas.clipRect(
            rect.left.toFloat() + leftInsetPx,
            verticalLayout.clipTop,
            rect.left + rect.width.toFloat(),
            verticalLayout.clipBottom
        )
    }
    canvas.drawText(
        displayString,
        rect.left.toFloat() + leftInsetPx - horizontalOffsetPx,
        baseline,
        virtualListTextPaint
    )
    if (clipToRect) canvas.restore()
}

internal data class PreparedVirtualListText(
    val display: String,
    val typeface: Typeface,
    val overflowPx: Float,
    /** Full unellipsized text extent used by retained marquee display lists. */
    val measuredWidthPx: Float,
)

private data class PreparedTransitionVirtualListText(
    val text: String,
    val typeface: Typeface,
    /** Measured pixel width at fontSizeSp=1 for the current density/fontScale. */
    val widthPerSpPx: Float,
)

private fun prepareTransitionVirtualListText(
    text: String,
    bold: Boolean,
    textDensity: Float,
    configuredTypeface: Typeface?,
): PreparedTransitionVirtualListText {
    // configuredTypeface remains part of the retained text signature so a global font change
    // invalidates prepared widths, while the concrete face is resolved with the semantic weight.
    val resolvedTypeface = FontManager.resolveTypeface(if (bold) 700 else 400)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = textDensity
        typeface = resolvedTypeface
    }
    return PreparedTransitionVirtualListText(
        text = text,
        typeface = resolvedTypeface,
        widthPerSpPx = paint.measureText(text),
    )
}

internal fun PreparedVirtualListText.marqueeOffset(
    elapsedMs: Long,
    speedPxPerSecond: Float,
    enabled: Boolean
): Float {
    return LongTextMotionState.marqueeOffset(
        elapsedMs = elapsedMs,
        overflowPx = overflowPx,
        speedPxPerSecond = speedPxPerSecond,
        enabled = enabled
    )
}

internal fun prepareVirtualListText(
    text: String,
    rect: ComposeItemRect,
    density: Float,
    bold: Boolean,
    leftInsetPx: Float = 0f,
    animate: Boolean,
    configuredTypeface: Typeface?
): PreparedVirtualListText {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = (rect.fontSizeSp.takeIf { it > 0f } ?: 14f) * density
        // Keep configuredTypeface in the caller's remember/signature key; resolve the actual
        // requested 400/700 face here so the global weight offset is not overwritten by BOLD/NORMAL.
        typeface = FontManager.resolveTypeface(if (bold) 700 else 400)
    }
    val availableWidth = (rect.width.toFloat() - leftInsetPx).coerceAtLeast(1f)
    val measuredWidth = paint.measureText(text)
    return PreparedVirtualListText(
        display = if (animate) {
            // When list marquee is enabled, never pre-ellipsize settled rows. The old
            // 10 px dead-zone left small overflows as a permanent ellipsis with no motion.
            text
        } else {
            TextUtils.ellipsize(
                text,
                paint,
                availableWidth,
                TextUtils.TruncateAt.END
            ).toString()
        },
        typeface = paint.typeface ?: Typeface.DEFAULT,
        overflowPx = (measuredWidth - availableWidth).coerceAtLeast(0f),
        measuredWidthPx = measuredWidth.coerceAtLeast(0f),
    )
}

@Composable
private fun rememberVirtualListPreparedText(
    text: String,
    rect: ComposeItemRect,
    density: Float,
    bold: Boolean,
    leftInsetPx: Float = 0f,
    animate: Boolean,
    configuredTypeface: Typeface?
): PreparedVirtualListText {
    return remember(
        text,
        rect.width,
        rect.fontSizeSp,
        density,
        bold,
        leftInsetPx,
        animate,
        configuredTypeface
    ) {
        prepareVirtualListText(
            text = text,
            rect = rect,
            density = density,
            bold = bold,
            leftInsetPx = leftInsetPx,
            animate = animate,
            configuredTypeface = configuredTypeface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposePowerTransitionVisualFast(
    song: AudioFile,
    index: Int,
    sourceMode: ComposeVirtualListDisplayMode,
    targetMode: ComposeVirtualListDisplayMode,
    source: ComposeTransitionRects,
    target: ComposeTransitionRects,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    isSelected: Boolean,
    hideCover: Boolean = false,
    deferArtworkLoad: Boolean = false,
    marqueeVisible: Boolean,
    onCoverBoundsChanged: (RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onClick: (CoverTransitionTarget?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dark = ThemeManager.isDarkMode(context)
    val colors = virtualListColors(dark, isPlaying, isSelected)
    Box(
        modifier = modifier
            // Keep the layout transition as the sole geometry owner. Do not publish/measure moving
            // window bounds on every frame just to build a shared-cover target for a tap during the
            // short zoom; a tap can safely fall back to a non-shared player transition.
            .combinedClickable(onClick = { onClick(null) }, onLongClick = onLongClick)
            .background(colors.background)
    ) {
        TransitionCover(
            song = song,
            targetMode = targetMode,
            source = source.cover,
            target = target.cover,
            sourceRadiusDp = source.coverRadiusDp,
            targetRadiusDp = target.coverRadiusDp,
            progressProvider = progressProvider,
            hidden = hideCover,
            deferLoad = deferArtworkLoad,
        )
        TransitionTexts(
            song = song,
            colors = colors,
            source = source,
            target = target,
            progressProvider = progressProvider,
            marqueeVisible = marqueeVisible,
        )
    }
}

@Composable
private fun TransitionCover(
    song: AudioFile,
    targetMode: ComposeVirtualListDisplayMode,
    source: ComposeItemRect,
    target: ComposeItemRect,
    sourceRadiusDp: Float,
    targetRadiusDp: Float,
    progressProvider: () -> Float,
    hidden: Boolean = false,
    deferLoad: Boolean = false,
) {
    val density = LocalDensity.current
    val sourceCoverSize = maxOf(source.width, source.height).coerceAtLeast(1)
    val targetCoverSize = maxOf(target.width, target.height).coerceAtLeast(1)
    val stableCoverWidth = maxOf(source.width, target.width).coerceAtLeast(1)
    val stableCoverHeight = maxOf(source.height, target.height).coerceAtLeast(1)
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
            .requiredSize(
                width = with(density) { stableCoverWidth.toDp() },
                height = with(density) { stableCoverHeight.toDp() },
            )
            .graphicsLayer {
                val p = progressProvider()
                val rect = transitionLocalRect(source, target, p)
                val sx = rect.width.coerceAtLeast(1).toFloat() / stableCoverWidth.toFloat()
                val sy = rect.height.coerceAtLeast(1).toFloat() / stableCoverHeight.toFloat()
                scaleX = sx
                scaleY = sy
                transformOrigin = TransformOrigin(0f, 0f)
                // Shape is defined in pre-scale local coordinates. Compensate for the layer scale
                // so the visible corner radius remains identical to the old dynamically measured
                // cover at every point of the pinch.
                val visibleRadiusDp = interpolateArtworkRadiusDp(sourceRadiusDp, targetRadiusDp, p)
                val radiusScale = minOf(sx, sy).coerceAtLeast(0.001f)
                shape = RoundedCornerShape((visibleRadiusDp / radiusScale).dp)
                clip = true
            }
    ) {
        val context = LocalContext.current
        val transitionDecodeSide = remember(sourceCoverSize, targetCoverSize, targetMode) {
            virtualListDecodeSideForMode(
                requestedSide = maxOf(sourceCoverSize, targetCoverSize)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .virtualListArtwork(
                    key = song.coverKey,
                    externalArtworkPath = song.albumArtPath,
                    decodeSide = transitionDecodeSide,
                    priority = BitmapRequest.Priority.LOADING_LIST,
                    // This Raw transition layer is a stand-in for Reference's retained ArtworkImageNode.
                    // If its wrapper is not resident yet it must remain a real provider consumer;
                    // cache-only ghosts are what made shared LIST/GRID items turn into empty squares.
                    deferLoad = deferLoad,
                    hidden = hidden,
                    cornerRadiusPx = 0f,
                    animateChanges = false,
                    defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
                    resources = context.resources,
                )
        )
    }
}

@Composable
private fun TransitionTexts(
    song: AudioFile,
    colors: VirtualListColors,
    source: ComposeTransitionRects,
    target: ComposeTransitionRects,
    progressProvider: () -> Float,
    marqueeVisible: Boolean,
) {
    val density = LocalDensity.current
    // Preserve the existing transition actor's font sizing; this round only restores marquee motion.
    val textDensity = density.density
    val configuredTypeface = FontManager.typeface
    val animateListText = LongTextMotionState.enabled && LongTextMotionState.enabledEverywhere
    val listMarqueeVisible = LongTextMotionState.LocalListMarqueeVisibility.current
    val titleText = remember(song.displayName, textDensity, configuredTypeface) {
        prepareTransitionVirtualListText(song.displayName, true, textDensity, configuredTypeface)
    }
    val subtitleText = remember(song.subtitle(), textDensity, configuredTypeface) {
        prepareTransitionVirtualListText(song.subtitle(), false, textDensity, configuredTypeface)
    }
    val metaText = remember(song.metaText(), textDensity, configuredTypeface) {
        prepareTransitionVirtualListText(song.metaText(), false, textDensity, configuredTypeface)
    }

    // Reference keeps MarqueeTextNode alive while its ArtworkItemNode alpha is non-zero. Internal
    // LIST/GRID zoom replaces Raw's settled row with this retained transition actor, so this actor
    // has to remain a marquee consumer as well; otherwise the clock may keep running globally while
    // the visibly interpolated title itself appears frozen for the whole pinch. Text width is
    // measured once at composition and scales linearly with fontSizeSp in draw; never call
    // TextPaint.measureText() for every holder on every pinch frame.
    val hasPotentialMarqueeOverflow = remember(titleText, subtitleText, metaText, source, target) {
        fun overflows(text: PreparedTransitionVirtualListText, rect: ComposeItemRect): Boolean {
            if (text.text.isBlank() || rect.alpha <= 0f || rect.width <= 1) return false
            val fontSizeSp = rect.fontSizeSp.takeIf { it > 0f } ?: 14f
            return text.widthPerSpPx * fontSizeSp - rect.width.toFloat() > 0.5f
        }
        overflows(titleText, source.title) || overflows(titleText, target.title) ||
            overflows(subtitleText, source.subtitle) || overflows(subtitleText, target.subtitle) ||
            overflows(metaText, source.meta) || overflows(metaText, target.meta)
    }
    val listMarqueeEnabled = LocalUiFrameAnimationActive.current &&
        animateListText && listMarqueeVisible && marqueeVisible && hasPotentialMarqueeOverflow
    DisposableEffect(listMarqueeEnabled) {
        if (listMarqueeEnabled) LongTextMotionState.acquireMarquee()
        onDispose {
            if (listMarqueeEnabled) LongTextMotionState.releaseMarquee()
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val progress = progressProvider()
        val marqueeElapsedMs = if (listMarqueeEnabled) LongTextMotionState.marqueeElapsedMs else 0L

        fun drawOne(
            text: PreparedTransitionVirtualListText,
            color: Color,
            sourceRect: ComposeItemRect,
            targetRect: ComposeItemRect,
            bold: Boolean,
        ) {
            if (text.text.isBlank()) return
            val sourceFontSize = sourceRect.fontSizeSp.takeIf { it > 0f } ?: 14f
            val targetFontSize = targetRect.fontSizeSp.takeIf { it > 0f } ?: 14f
            val rect = transitionLocalRect(sourceRect, targetRect, progress).copy(
                fontSizeSp = lerpFloatLocal(sourceFontSize, targetFontSize, progress)
            )
            val overflowPx = (
                text.widthPerSpPx * (rect.fontSizeSp.takeIf { it > 0f } ?: 14f) -
                    rect.width.toFloat().coerceAtLeast(1f)
                ).coerceAtLeast(0f)
            val horizontalOffsetPx = LongTextMotionState.marqueeOffset(
                elapsedMs = marqueeElapsedMs,
                overflowPx = overflowPx,
                speedPxPerSecond = 42.5f * density.density,
                enabled = listMarqueeEnabled && overflowPx > 0.5f,
            )
            drawVirtualListText(
                canvas = drawContext.canvas.nativeCanvas,
                text = text.text,
                rect = rect,
                color = color,
                density = textDensity,
                bold = bold,
                typefaceOverride = text.typeface,
                alreadyEllipsized = animateListText,
                horizontalOffsetPx = horizontalOffsetPx,
            )
        }

        drawOne(titleText, colors.title, source.title, target.title, bold = true)
        drawOne(subtitleText, colors.secondary, source.subtitle, target.subtitle, bold = false)
        drawOne(metaText, colors.meta, source.meta, target.meta, bold = false)
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

internal data class VirtualListColors(
    val background: Color,
    val title: Color,
    val secondary: Color,
    val meta: Color
)

internal fun virtualListColors(dark: Boolean, playing: Boolean, selected: Boolean): VirtualListColors {
    val staticForeground = usesReferenceStaticForeground()
    val baseTitle = when {
        staticForeground -> Color.White
        dark -> Color.White
        else -> Color(0xFF1D1B19)
    }
    // Reference static-artwork/artwork rows stay in the same white foreground family.  Playing/selection state is
    // communicated by weight/background, not by introducing a theme/gold foreground colour.
    val highlight = if (staticForeground) {
        Color.White
    } else if (dark) {
        Color(0xFFD7B98D)
    } else {
        Color(0xFFC28E5E)
    }
    val background = when {
        selected && staticForeground -> Color.White.copy(alpha = 0.12f)
        selected -> highlight.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    return VirtualListColors(
        background = background,
        title = if (playing) highlight else baseTitle,
        secondary = baseTitle.copy(alpha = 0.72f),
        meta = baseTitle.copy(alpha = 0.52f)
    )
}

internal fun AudioFile.subtitle(): String {
    return buildString {
        if (artist.isNotBlank()) append(artist)
        if (album.isNotBlank()) {
            if (isNotBlank()) append(" · ")
            append(album)
        }
    }.ifBlank { path.substringBeforeLast('/').substringAfterLast('/') }
}

internal fun AudioFile.metaText(): String {
    return formatVirtualListSongMeta(this)
}

private fun virtualListKey(song: AudioFile, index: Int): Any {
    return song.id.takeIf { it != 0L } ?: song.path.ifBlank { index.toString() }
}

/**
 * Canonical physical identity for one provider item.
 *
 * The reference implementation shares its holder bank only when CURRENT/NEXT reference the exact same provider
 * instance. A non-null [prefix] is therefore a provider identity object, not a route-name prefix.
 * Standalone/non-persistent lists keep their historical item-only key by passing null.
 */
private fun virtualListPhysicalRenderKey(
    prefix: Any?,
    song: AudioFile,
    index: Int,
    customHandled: Boolean,
): Any {
    val item = virtualListKey(song, index)
    return if (prefix == null) item else VirtualListProviderItemKey(prefix, item)
}


/**
 * Shared-layout interpolation weight for the canonical CURRENT role. GenericPivot's role alphas are
 * complementary in the baseline implementation-aligned HOME/category motions, so normalizing them preserves both
 * forward and predictive/back direction without teaching VirtualList about navigation direction.
 */
private fun virtualListOuterCurrentLayoutFraction(
    currentTransform: RetainedSceneItemTransform?,
    retainedTransform: RetainedSceneItemTransform?,
): Float = virtualListOuterCurrentLayoutFractionFromAlphas(
    currentAlpha = currentTransform?.alphaProvider?.invoke() ?: 1f,
    retainedAlpha = retainedTransform?.alphaProvider?.invoke() ?: 0f,
)

/**
 * Provider-specific compatibility render key. Ordinary rows/cells must never use this key after
 * Step 4: [virtualListKey] is their physical identity across settled, retained/current and transition
 * ownership. Scene prefix remains only for custom/heterogeneous holders whose visual type belongs
 * to that provider.
 */
private fun virtualListLegacyRenderKey(prefix: Any?, song: AudioFile, index: Int): Any {
    val item = virtualListKey(song, index)
    return if (prefix == null) item else VirtualListProviderItemKey(prefix, item)
}

private fun dualSlotRenderFrame(
    source: ComposeItemPosition,
    target: ComposeItemPosition,
    progress: Float
): ComposeItemPosition {
    val alphaProgress = progress.coerceIn(0f, 1f)
    return ComposeItemPosition(
        bounds = IntRect(
            left = lerpIntLocal(source.bounds.left, target.bounds.left, progress),
            top = lerpIntLocal(source.bounds.top, target.bounds.top, progress),
            right = lerpIntLocal(source.bounds.right, target.bounds.right, progress),
            bottom = lerpIntLocal(source.bounds.bottom, target.bounds.bottom, progress)
        ),
        alpha = lerpFloatLocal(source.alpha, target.alpha, alphaProgress),
        scaleX = lerpFloatUnclampedLocal(source.scaleX, target.scaleX, progress),
        scaleY = lerpFloatUnclampedLocal(source.scaleY, target.scaleY, progress),
        sceneId = if (alphaProgress < 0.5f) source.sceneId else target.sceneId
    )
}

private fun oneSlotScale(base: Float, transitionFraction: Float): Float {
    return ((base - 1f) * transitionFraction) + 1f
}

private fun oneSlotRenderFrame(
    basePosition: ComposeItemPosition,
    scaleBase: Float,
    transitionFraction: Float,
    pivotX: Float,
    pivotY: Float,
): ComposeItemPosition {
    val f = transitionFraction
    // The one-slot enter/exit geometry is a viewport-pivot layout transition. Endpoint elasticity is
    // a separate holder-local owner and is applied by the caller's graphicsLayer around the live
    // holder centre. Folding elasticity into this viewport-pivot scale shifts SOURCE_ONLY/TARGET_ONLY
    // artwork relative to shared holders at the LIST_ZOOMED <-> GRID_4 boundary.
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
        alpha = (1f - f).coerceIn(0f, 1f),
        scaleX = scale,
        scaleY = scale
    )
}

private fun transitionLocalRect(
    source: ComposeItemRect,
    target: ComposeItemRect,
    progress: Float
): ComposeItemRect {
    val alphaProgress = progress.coerceIn(0f, 1f)
    return ComposeItemRect(
        left = lerpIntLocal(source.left, target.left, progress),
        top = lerpIntLocal(source.top, target.top, progress),
        width = lerpIntLocal(source.width, target.width, progress).coerceAtLeast(1),
        height = lerpIntLocal(source.height, target.height, progress).coerceAtLeast(1),
        alpha = source.alpha + (target.alpha - source.alpha) * alphaProgress,
        fontSizeSp = source.fontSizeSp + (target.fontSizeSp - source.fontSizeSp) * alphaProgress
    )
}

private fun virtualListModeOrder(mode: ComposeVirtualListDisplayMode): Int = when (mode) {
    ComposeVirtualListDisplayMode.LIST_SMALL -> 0
    ComposeVirtualListDisplayMode.LIST_NORMAL -> 1
    ComposeVirtualListDisplayMode.LIST_ZOOMED -> 2
    ComposeVirtualListDisplayMode.GRID_4 -> 3
    ComposeVirtualListDisplayMode.GRID_3 -> 4
    ComposeVirtualListDisplayMode.GRID_2 -> 5
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
    sourceMode: ComposeVirtualListDisplayMode,
    targetMode: ComposeVirtualListDisplayMode,
    sourceParams: ListZoomParams,
    targetParams: ListZoomParams,
    sourceGeometry: ComposeVirtualListGeometry,
    targetGeometry: ComposeVirtualListGeometry,
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
        val key = virtualListKey(song, index)
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
        val key = virtualListKey(song, index)
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
        val artworkRadiusType = song.virtualListArtworkRadiusType()
        val sourceRects = composeArtworkItemSceneRects(baseSource, sourceMode, sourceParams, density, artworkRadiusType)
        val targetRects = composeArtworkItemSceneRects(baseTarget, targetMode, targetParams, density, artworkRadiusType)
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


private fun capVirtualListRange(
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

private fun virtualListMaxRenderItems(
    columns: Int,
    rowStridePx: Int,
    viewportHeightPx: Int,
    retainedRowsEachSide: Int = 1,
): Int {
    val safeColumns = columns.coerceAtLeast(1)
    val safeStride = rowStridePx.coerceAtLeast(1)
    val visibleRows = ((viewportHeightPx.coerceAtLeast(1) + safeStride - 1) / safeStride)
        .coerceAtLeast(1)
    // Reference's provider window follows attached holders, not just strictly visible rows. During
    // GenericPivot the bounded buffer can reach about half a viewport on either side; during plain
    // scrolling it stays at one row. All attached rows remain real artwork consumers.
    val bufferedRows = visibleRows + 2 * retainedRowsEachSide.coerceAtLeast(0)
    return (bufferedRows * safeColumns).coerceIn(safeColumns, VIRTUAL_LIST_MAX_RENDER_ITEMS)
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
private fun ComposeVirtualListSectionHeaders(
    headers: List<VirtualListSectionHeader>,
    geometry: ComposeVirtualListGeometry,
    scrollYPx: Int,
    viewportHeightPx: Int,
    boundaryScaleProvider: () -> Float,
    content: @Composable (VirtualListSectionHeader) -> Unit
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
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            rawBounds.left,
                            top
                        )
                    }
                    .requiredSize(
                        width = with(density) { rawBounds.width.toDp() },
                        height = with(density) { rawBounds.height.toDp() }
                    )
                    .graphicsLayer {
                        val boundaryScale = boundaryScaleProvider().coerceIn(0.9105f, 1.0895f)
                        translationX = 0f
                        translationY = 0f
                        scaleX = boundaryScale
                        scaleY = boundaryScale
                        alpha = 1f
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
            ) {
                content(header)
            }
        }
    }
}

@Composable
private fun ComposeVirtualListTransitionSectionHeaders(
    headers: List<VirtualListSectionHeader>,
    sourceGeometry: ComposeVirtualListGeometry,
    targetGeometry: ComposeVirtualListGeometry,
    sourceScrollYPx: Int,
    targetScrollYPx: Int,
    viewportHeightPx: Int,
    progressProvider: () -> Float,
    elasticScaleProvider: () -> Float,
    content: @Composable (VirtualListSectionHeader) -> Unit
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
                        // Keep edge headers attached through the transition. The scene/window clip is
                        // authoritative; an intermediate top/bottom alpha gate caused visible slicing.
                        val elasticScale = elasticScaleProvider().coerceIn(0.9105f, 1.0895f)
                        translationX = 0f
                        translationY = 0f
                        alpha = 1f
                        scaleX = elasticScale
                        scaleY = elasticScale
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(header)
            }
        }
    }
}

private data class ComposeVirtualListGeometry(
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

private class VirtualListItemBounds(
    override val size: Int,
    private val columns: Int,
    private val cellWidthPx: Int,
    private val itemHeightPx: Int,
    private val segmentStarts: IntArray,
    private val segmentTopPx: IntArray,
) : AbstractList<IntRect>() {
    override fun get(index: Int): IntRect {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        var low = 0
        var high = segmentStarts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segmentStarts[middle] <= index) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        val segment = (low - 1).coerceAtLeast(0)
        val offset = index - segmentStarts[segment]
        val column = offset % columns
        val row = offset / columns
        val left = column * cellWidthPx
        val top = segmentTopPx[segment] + row * itemHeightPx
        return IntRect(left, top, left + cellWidthPx, top + itemHeightPx)
    }
}

private fun listGeometryFor(
    metrics: ComposeVirtualListMetrics,
    bottomPaddingPx: Int,
    topPaddingPx: Int = 0,
    itemCount: Int = 0,
    sectionHeaders: List<VirtualListSectionHeader> = emptyList(),
    sectionHeaderHeightPx: Int = 0,
    itemHeightsPx: IntArray? = null,
): ComposeVirtualListGeometry {
    val safeItemCount = itemCount.coerceAtLeast(0)
    val columns = metrics.columns.coerceAtLeast(1)
    val availableWidth = metrics.cellWidthPx.coerceAtLeast(1) * columns
    val cellWidth = if (columns <= 1) availableWidth else availableWidth / columns
    val itemHeight = metrics.cellHeightPx.coerceAtLeast(1)
    val validHeaders = sectionHeaders
        .mapIndexedNotNull { originalIndex, header ->
            header.takeIf { it.beforeItemIndex in 0..safeItemCount }?.let { originalIndex to it }
        }
        .sortedWith(compareBy<Pair<Int, VirtualListSectionHeader>> { it.second.beforeItemIndex }.thenBy { it.first })
    val headerBoundsByOriginalIndex = MutableList(sectionHeaders.size) { IntRect.Zero }

    // Heterogeneous HOME holders still live in this same VirtualList owner.  Their heights are provider
    // metadata, exactly like different native ViewHolder layout resources.  They use one column and
    // a monotonic bounds table (HOME has only a few dozen holders), while ordinary library providers
    // stay on the O(section-count) uniform geometry below.
    if (itemHeightsPx != null && itemHeightsPx.size >= safeItemCount) {
        val bounds = ArrayList<IntRect>(safeItemCount)
        var yVariable = topPaddingPx.coerceAtLeast(0)
        var headerCursorVariable = 0
        val headerHeightVariable = sectionHeaderHeightPx.coerceAtLeast(1)
        for (index in 0 until safeItemCount) {
            while (
                headerCursorVariable < validHeaders.size &&
                validHeaders[headerCursorVariable].second.beforeItemIndex == index
            ) {
                val originalIndex = validHeaders[headerCursorVariable].first
                headerBoundsByOriginalIndex[originalIndex] = IntRect(0, yVariable, availableWidth, yVariable + headerHeightVariable)
                yVariable += headerHeightVariable
                headerCursorVariable++
            }
            val height = itemHeightsPx[index].coerceAtLeast(1)
            bounds += IntRect(0, yVariable, availableWidth, yVariable + height)
            yVariable += height
        }
        while (headerCursorVariable < validHeaders.size) {
            val originalIndex = validHeaders[headerCursorVariable].first
            headerBoundsByOriginalIndex[originalIndex] = IntRect(0, yVariable, availableWidth, yVariable + headerHeightVariable)
            yVariable += headerHeightVariable
            headerCursorVariable++
        }
        return ComposeVirtualListGeometry(
            columns = 1,
            availableWidthPx = availableWidth,
            cellWidthPx = availableWidth,
            itemHeightPx = itemHeightsPx.firstOrNull()?.coerceAtLeast(1) ?: itemHeight,
            rowStridePx = itemHeightsPx.minOrNull()?.coerceAtLeast(1) ?: itemHeight,
            rowSpacingPx = 0,
            bottomPaddingPx = bottomPaddingPx.coerceAtLeast(0),
            paddingTopPx = topPaddingPx.coerceAtLeast(0),
            itemBounds = bounds,
            headerBounds = headerBoundsByOriginalIndex,
            contentHeightPx = yVariable + bottomPaddingPx.coerceAtLeast(0),
        )
    }

    // Reference's FastLayout/VirtualList computes child geometry from the attached holder window.
    // Do the same asymptotically: keep only section breakpoints and derive an item's rect on demand.
    // The old MutableList(itemCount) made scene preparation O(itemCount) even when the viewport had
    // only a dozen attached holders, which is why two GRID_4 screens with different data counts
    // had very different HOME/category transition cost.
    val segmentStarts = ArrayList<Int>(validHeaders.size + 1)
    val segmentTops = ArrayList<Int>(validHeaders.size + 1)
    segmentStarts += 0
    segmentTops += topPaddingPx.coerceAtLeast(0)

    var y = topPaddingPx.coerceAtLeast(0)
    var segmentStart = 0
    var headerCursor = 0
    val headerHeight = sectionHeaderHeightPx.coerceAtLeast(1)
    while (headerCursor < validHeaders.size) {
        val beforeItemIndex = validHeaders[headerCursor].second.beforeItemIndex
        val segmentItemCount = (beforeItemIndex - segmentStart).coerceAtLeast(0)
        if (segmentItemCount > 0) {
            y += ((segmentItemCount + columns - 1) / columns) * itemHeight
        }
        segmentStart = beforeItemIndex

        while (
            headerCursor < validHeaders.size &&
            validHeaders[headerCursor].second.beforeItemIndex == beforeItemIndex
        ) {
            val originalIndex = validHeaders[headerCursor].first
            headerBoundsByOriginalIndex[originalIndex] = IntRect(0, y, availableWidth, y + headerHeight)
            y += headerHeight
            headerCursor++
        }
        if (beforeItemIndex < safeItemCount) {
            if (segmentStarts.last() == beforeItemIndex) {
                segmentTops[segmentTops.lastIndex] = y
            } else {
                segmentStarts += beforeItemIndex
                segmentTops += y
            }
        }
    }

    val remainingItems = (safeItemCount - segmentStart).coerceAtLeast(0)
    if (remainingItems > 0) {
        y += ((remainingItems + columns - 1) / columns) * itemHeight
    }
    val contentHeight = y + bottomPaddingPx.coerceAtLeast(0)
    val itemBounds = VirtualListItemBounds(
        size = safeItemCount,
        columns = columns,
        cellWidthPx = cellWidth,
        itemHeightPx = itemHeight,
        segmentStarts = segmentStarts.toIntArray(),
        segmentTopPx = segmentTops.toIntArray(),
    )

    return ComposeVirtualListGeometry(
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
    geometry: ComposeVirtualListGeometry,
    mode: ComposeVirtualListDisplayMode,
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
        sceneId = mode.listLevel?.let { sceneIdForZoomIndex(it) } ?: VirtualListSceneItem.SCENE_GRID
    )
}

private fun lerpIntLocal(from: Int, to: Int, fraction: Float): Int {
    return (from + (to - from) * fraction).roundToInt()
}

private fun lerpFloatLocal(from: Float, to: Float, fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return from + (to - from) * f
}

private fun lerpFloatUnclampedLocal(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

private fun maxScrollForContent(
    itemCount: Int,
    metrics: ComposeVirtualListMetrics,
    viewportHeightPx: Int,
    bottomPaddingPx: Int,
    topPaddingPx: Int = 0,
    sectionHeaders: List<VirtualListSectionHeader> = emptyList(),
    sectionHeaderHeightPx: Int = 0,
    itemHeightsPx: IntArray? = null,
): Int {
    if (itemCount <= 0) return 0
    val geometry = listGeometryFor(
        metrics = metrics,
        bottomPaddingPx = bottomPaddingPx,
        topPaddingPx = topPaddingPx,
        itemCount = itemCount,
        sectionHeaders = sectionHeaders,
        sectionHeaderHeightPx = sectionHeaderHeightPx,
        itemHeightsPx = itemHeightsPx,
    )
    return maxScrollForGeometry(itemCount, geometry, viewportHeightPx)
}

private fun maxScrollForGeometry(
    itemCount: Int,
    geometry: ComposeVirtualListGeometry,
    viewportHeightPx: Int
): Int {
    if (itemCount <= 0) return 0
    return (geometry.contentHeightPx - viewportHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
}

private fun scrollYForIndex(
    index: Int,
    itemCount: Int,
    geometry: ComposeVirtualListGeometry,
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
    geometry: ComposeVirtualListGeometry,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    scrollYPx: Int,
    density: Float,
    rootBounds: RectF?,
    songId: Long = -1L,
    coverKey: String = "",
    artworkRadiusType: VirtualListArtworkRadiusType = VirtualListArtworkRadiusType.TRACK,
): CoverTransitionTarget? {
    val root = rootBounds ?: return null
    val itemPosition = positionFor(
        index = index,
        geometry = geometry,
        mode = mode,
        scrollYPx = scrollYPx.coerceAtLeast(0)
    )
    if (itemPosition.isEmpty()) return null
    val rects = composeArtworkItemSceneRects(itemPosition, mode, params, density, artworkRadiusType)
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

private data class ComposeVirtualListTransitionSession(
    val id: Int,
    val pairGeneration: Int,
    val sourceMode: ComposeVirtualListDisplayMode,
    val targetMode: ComposeVirtualListDisplayMode,
    val sourceScrollYPx: Int,
    val fallbackListLevel: ListZoomIndex,
    val songs: List<AudioFile>,
    val sectionHeaders: List<VirtualListSectionHeader>,
) {
    // Filled when the immutable pair resolves its anchor geometry. A chained pinch uses this exact
    // endpoint as the next pair's source without publishing the canonical settled layout in between.
    var resolvedTargetScrollYPx: Int? = null

    fun matches(
        source: ComposeVirtualListDisplayMode,
        target: ComposeVirtualListDisplayMode,
        requestedPairGeneration: Int,
    ): Boolean = virtualListTransitionSessionMatches(
        existingSource = sourceMode,
        existingTarget = targetMode,
        existingPairGeneration = pairGeneration,
        requestedSource = source,
        requestedTarget = target,
        requestedPairGeneration = requestedPairGeneration,
    )
}

private data class ComposeVirtualListTransitionSnapshot(
    val sessionId: Int,
    val pairGeneration: Int,
    val songs: List<AudioFile>,
    val sectionHeaders: List<VirtualListSectionHeader>,
    val sourceMode: ComposeVirtualListDisplayMode,
    val targetMode: ComposeVirtualListDisplayMode,
    val sourceParams: ListZoomParams,
    val targetParams: ListZoomParams,
    val sourceGeometry: ComposeVirtualListGeometry,
    val targetGeometry: ComposeVirtualListGeometry,
    val scrollModel: TransitionScrollModel,
    val transitionItems: List<ComposeTransitionItem>,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
) {
    fun endpointScrollFor(mode: ComposeVirtualListDisplayMode): Int {
        return resolveVirtualListTransitionEndpointScroll(
            committedToTarget = mode == targetMode,
            sourceScrollYPx = scrollModel.sourceScrollYPx,
            targetScrollYPx = scrollModel.targetScrollYPx,
        )
    }
}

@Composable
private fun rememberComposeVirtualListTransitionSnapshot(
    session: ComposeVirtualListTransitionSession?,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    fontScale: Float,
    contentTopPaddingPx: Int,
    sourceBottomPaddingPx: Int,
    targetBottomPaddingPx: Int,
    sectionHeaderHeightPx: Int,
    slotPool: ComposeSlotPool,
): ComposeVirtualListTransitionSnapshot? {
    session ?: return null
    // One transition manager owns one immutable pair of layout engines. Deliberately key only by
    // session id: bottom chrome/padding, repository publication, font state, or viewport bookkeeping
    // that changes while the gesture is in flight must not regenerate a different holder map.
    return remember(session.id) {
        TransitionPerfTrace.measure(TransitionPerfStage.VIRTUALLIST_TRANSITION_SNAPSHOT) {
        val sourceParams = session.sourceMode.listLevel?.let { paramsFor(it) }
            ?: paramsFor(session.fallbackListLevel)
        val targetParams = session.targetMode.listLevel?.let { paramsFor(it) }
            ?: paramsFor(session.fallbackListLevel)
        val sourceMetrics = computeVirtualListMetrics(
            widthPx = widthPx,
            density = density,
            scaledDensity = density * fontScale,
            mode = session.sourceMode,
            params = sourceParams,
        )
        val targetMetrics = computeVirtualListMetrics(
            widthPx = widthPx,
            density = density,
            scaledDensity = density * fontScale,
            mode = session.targetMode,
            params = targetParams,
        )
        val sourceGeometry = listGeometryFor(
            metrics = sourceMetrics,
            bottomPaddingPx = sourceBottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            itemCount = session.songs.size,
            sectionHeaders = session.sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
        )
        val targetGeometry = listGeometryFor(
            metrics = targetMetrics,
            bottomPaddingPx = targetBottomPaddingPx,
            topPaddingPx = contentTopPaddingPx,
            itemCount = session.songs.size,
            sectionHeaders = session.sectionHeaders,
            sectionHeaderHeightPx = sectionHeaderHeightPx,
        )
        val scrollModel = transitionScrollModel(
            itemCount = session.songs.size,
            sourceMode = session.sourceMode,
            targetMode = session.targetMode,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            viewportHeightPx = heightPx,
            sourceScrollYPx = session.sourceScrollYPx,
        )
        session.resolvedTargetScrollYPx = scrollModel.targetScrollYPx
        val transitionItems = buildTransitionItems(
            songs = session.songs,
            sourceMode = session.sourceMode,
            targetMode = session.targetMode,
            sourceParams = sourceParams,
            targetParams = targetParams,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            scrollModel = scrollModel,
            density = density,
            slotPool = slotPool,
        )
        ComposeVirtualListTransitionSnapshot(
            sessionId = session.id,
            pairGeneration = session.pairGeneration,
            songs = session.songs,
            sectionHeaders = session.sectionHeaders,
            sourceMode = session.sourceMode,
            targetMode = session.targetMode,
            sourceParams = sourceParams,
            targetParams = targetParams,
            sourceGeometry = sourceGeometry,
            targetGeometry = targetGeometry,
            scrollModel = scrollModel,
            transitionItems = transitionItems,
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
        ).also {
            TransitionPerfTrace.count(
                TransitionPerfEvent.TRANSITION_ITEMS_SNAPSHOT,
                transitionItems.size.toLong(),
            )
        }
        }
    }
}

private fun transitionScrollModel(
    itemCount: Int,
    sourceMode: ComposeVirtualListDisplayMode,
    targetMode: ComposeVirtualListDisplayMode,
    sourceGeometry: ComposeVirtualListGeometry,
    targetGeometry: ComposeVirtualListGeometry,
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
    val sourceStrictRange = visibleRangeForScroll(
        itemCount = itemCount,
        mode = sourceMode,
        geometry = sourceGeometry,
        scrollYPx = sourceScrollY,
        viewportHeightPx = viewportHeightPx
    )
    val targetStrictRange = visibleRangeForScroll(
        itemCount = itemCount,
        mode = targetMode,
        geometry = targetGeometry,
        scrollYPx = targetScrollY,
        viewportHeightPx = viewportHeightPx
    )
    // Reference zoom transitions reuse the active holder population instead of materialising an
    // inverse-half-viewport worth of extra rows. Keep only the endpoint-visible ranges; top/bottom
    // continuity is handled by the unclipped transition host, not by expanding the item population.
    val sourceLayoutRange = sourceStrictRange
    val targetLayoutRange = targetStrictRange
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
    mode: ComposeVirtualListDisplayMode,
    geometry: ComposeVirtualListGeometry,
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
    geometry: ComposeVirtualListGeometry,
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
