package com.rawsmusic.core.ui.scene

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.rawsmusic.core.ui.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselBackdrop
import com.rawsmusic.core.ui.scene.pages.rememberHomeArtworkCarouselState
import com.rawsmusic.core.ui.scene.pages.HomeFloatingHeaderController
import com.rawsmusic.core.ui.scene.pages.HomeFloatingHeaderHost
import com.rawsmusic.core.ui.scene.pages.HomeHeaderSettingsSection
import com.rawsmusic.core.ui.scene.pages.LocalHomeFloatingHeaderController
import com.rawsmusic.core.ui.scene.pages.rememberHomeHeaderOptionsState
import com.rawsmusic.core.ui.scene.pages.rememberHomeCardLayoutState
import com.rawsmusic.core.ui.scene.pages.LibraryFloatingToolbarController
import com.rawsmusic.core.ui.scene.pages.LibraryFloatingToolbarHost
import com.rawsmusic.core.ui.scene.pages.LocalLibraryFloatingToolbarController
import com.rawsmusic.core.ui.scene.pages.SelectionTransitionEasing
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.index.AlphabetIndexOverlayRegistry
import com.rawsmusic.core.ui.widget.index.LocalAlphabetIndexOverlayRegistry
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.flow.RawBackgroundStyle
import com.rawsmusic.core.ui.widget.flow.RawFlowTuningState
import com.rawsmusic.core.ui.widget.flow.ProvideRawFlowMode
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowModeState
import com.rawsmusic.core.ui.widget.bottombar.GlassBottomChrome
import com.rawsmusic.core.ui.widget.player.rememberBottomAccentColor
import com.rawsmusic.core.ui.widget.player.MiniPlayerQueueDialog
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.module.data.prefs.BottomBarStyle
import com.rawsmusic.module.data.prefs.BottomChromeScrollBehavior
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalAppHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * App 主布局。
 *
 * 替代旧 XML 主界面壳。
 * 组合：主内容 + glass bottom navigation-style Bottom Chrome 液态玻璃底部导航栏。
 */
@Composable
fun AppMainLayout(
    navState: NavigationState,
    navCallbacks: NavCallbacks,
    navData: NavData,
    externalPageRenderer: ExternalPageRenderer? = null,
    onNavigateToPlayer: () -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
    onAudioEffectsClick: (() -> Unit)? = null,
    onSideRailDestination: (AppSideRailDestination) -> Unit = {},
    onOpenSideRail: () -> Unit = {},
    sideRailOpenRequestToken: Long = 0L,
    sideRailCloseRequestToken: Long = 0L,
    onSideRailExpandedChanged: (Boolean) -> Unit = {},
    onHomeFullCoverActiveChange: (Boolean) -> Unit = {},
    onHomeFullCoverLaunchRequest: (Rect) -> Boolean = { false },
    onMiniPlayerGestureBoundsChanged: (Rect?) -> Unit = {},
    onBottomNavigationGestureBoundsChanged: (Rect?) -> Unit = {},
    playerSceneProgressState: State<Float>? = null,
    suppressMiniPlayerArtwork: Boolean = false,
) {
    NavigationPersistenceEffect(navState)

    // Reference keeps one marquee clock for the visible text clients and removes its frame
    // callback when no client needs motion. Do the same at the app root so list rows, the mini
    // player and the player title share one phase instead of starting independent loops.
    val marqueeClockActive =
            LongTextMotionState.enabled &&
            LongTextMotionState.enabledEverywhere &&
            LongTextMotionState.hasActiveMarqueeUsers
    LaunchedEffect(marqueeClockActive) {
        if (!marqueeClockActive) {
            LongTextMotionState.pauseMarqueeClock()
            return@LaunchedEffect
        }
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            LongTextMotionState.advanceMarqueeClock(frameNanos)
        }
    }

    val bottomNavigationEnabled by PersonalizationPreferences.bottomNavigationEnabled.collectAsState()
    val bottomBarStyle by PersonalizationPreferences.bottomBarStyle.collectAsState()
    val bottomBarMaterial by PersonalizationPreferences.bottomBarMaterial.collectAsState()
    val bottomChromeScrollBehavior by PersonalizationPreferences.bottomChromeScrollBehavior.collectAsState()
    val bottomNavigationStyle by PersonalizationPreferences.bottomNavigationStyle.collectAsState()
    val miniPlayerStyle by PersonalizationPreferences.miniPlayerStyle.collectAsState()
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val playerLibraryTransform = remember(
        playerSceneProgressState,
        windowInfo.containerSize.width,
        windowInfo.containerSize.height,
    ) {
        playerSceneProgressState?.let { progressState ->
            RetainedSceneItemTransform(
                pivotX = windowInfo.containerSize.width * 0.5f,
                pivotY = windowInfo.containerSize.height * 0.5f,
                scaleProvider = {
                    1f - 0.5f * progressState.value.coerceIn(0f, 1f)
                },
                alphaProvider = {
                    1f - progressState.value.coerceIn(0f, 1f)
                },
            )
        }
    }
    // retained-view implementation keeps one index scroller beside the retained VirtualList. While PLAYER owns the
    // screen that indexer stays hidden; PLAYER -> MAIN does not reveal it during the shared move and
    // then restart another fade. Reveal it only after the sheet reaches the MAIN endpoint, using the
    // same 250 ms index scroller alpha duration recovered from build 1026.
    //
    // RawAlphabetIndex samples this provider from graphicsLayer, so the alpha clock never
    // recomposes the library page/controller.
    val playerAlphabetIndexAlpha = remember { Animatable(1f) }
    val playerAlphabetIndexAlphaProvider = remember(playerAlphabetIndexAlpha) {
        { playerAlphabetIndexAlpha.value }
    }
    LaunchedEffect(playerSceneProgressState) {
        var initialized = false
        snapshotFlow {
            (playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f) != 0f
        }
            .distinctUntilChanged()
            .collectLatest { playerVisible ->
                if (!initialized) {
                    playerAlphabetIndexAlpha.snapTo(if (playerVisible) 0f else 1f)
                    initialized = true
                } else if (playerVisible) {
                    playerAlphabetIndexAlpha.snapTo(0f)
                } else {
                    // The indexer stays at zero for the complete PLAYER-owned interval. Start its
                    // independent alpha only after the sheet clock reaches the exact MAIN endpoint;
                    // a threshold release can race the scene/indexer mode commit and flash once.
                    withFrameNanos { }
                    playerAlphabetIndexAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 250),
                    )
                }
            }
    }
    val configuredTabTags by PersonalizationPreferences.bottomNavigationSceneTags.collectAsState()
    val tabScenes = remember(configuredTabTags) {
        resolveBottomNavigationScenes(configuredTabTags)
    }

    val selectedTabIndex by remember(tabScenes) {
        derivedStateOf {
            // 内容场景在返回动画结束前保持不变，但底栏应在手势/返回动画开始时
            // 就切到正在显露的目标入口，避免页面已经回到主界面后指示器才追上。
            resolveBottomNavigationSelectedIndex(
                tabScenes = tabScenes,
                currentScene = navState.currentScene,
                backPreviewScene = navState.backPreviewScene,
                backStack = navState.backStack,
            )
        }
    }
    val showHomeSettingsShortcut by remember(tabScenes, bottomNavigationEnabled) {
        derivedStateOf { !bottomNavigationEnabled || NavScene.SETTINGS !in tabScenes }
    }
    val isSettingsScene by remember {
        derivedStateOf { navState.currentScene.isSettingsScene() }
    }
    val isIndependentSourceScene by remember {
        derivedStateOf {
            navState.currentScene == NavScene.SOURCE_IMPORT ||
                navState.backPreviewScene == NavScene.SOURCE_IMPORT ||
                (navState.isTransitioning &&
                    (navState.transitionFromScene == NavScene.SOURCE_IMPORT ||
                        navState.transitionToScene == NavScene.SOURCE_IMPORT))
        }
    }

    // Record the persistent background and the real scene into separate textures. The bottom
    // chrome is a later sibling, so it samples both layers without recursively sampling itself.
    val backgroundBackdrop = rememberLayerBackdrop()
    val sceneBackdrop = rememberLayerBackdrop()
    val backdrop = rememberCombinedBackdrop(backgroundBackdrop, sceneBackdrop)
    val homeFloatingHeaderController = remember { HomeFloatingHeaderController() }
    val libraryFloatingToolbarController = remember { LibraryFloatingToolbarController() }
    val alphabetIndexOverlayRegistry = remember { AlphabetIndexOverlayRegistry() }
    // Vendor RenderNodes can lose their recorded texture while the process remains
    // alive in the background. Rebind every source/effect pair on foreground entry.
    val appHazeState = remember(navData.uiForeground) { HazeState() }
    val rawFlowModeState = rememberRawFlowModeState()
    val homeCarouselSongs = remember(
        navData.homeCarouselSongs,
        navData.currentSong,
        navData.homeCarouselCurrentIndex,
    ) {
        navData.homeCarouselSongs.ifEmpty { listOfNotNull(navData.currentSong) }
    }
    // Playback identity is authoritative. visibleQueue.currentIndex may advance before the
    // decoder/currentSong commit; treating that projected cursor as currentSong reintroduces the
    // exact target -> previous -> target background flash the carousel state machine is designed
    // to avoid. Queue index remains a navigation/preview coordinate only.
    val homeCarouselCurrentSong = navData.currentSong
    val homeCarouselState = rememberHomeArtworkCarouselState(
        songs = homeCarouselSongs,
        currentSong = homeCarouselCurrentSong,
        reportedQueueIndex = navData.homeCarouselCurrentIndex,
    )
    val homeHeaderOptions = rememberHomeHeaderOptionsState()
    val homeCardLayoutState = rememberHomeCardLayoutState()
    val sceneTransitionFrame = remember {
        SceneTransitionFrameState(navState.currentScene)
    }
    // Carousel drag/settle is a visual preview. The scene background stays owned by committed
    // playback state and must not switch to a second preview renderer while the finger is down;
    // that old two-clock ownership is what produced target -> previous -> target flashes.
    val homeCarouselBackdropTransitionActive = false
    val rawFlowSceneActive by remember {
        derivedStateOf { navState.currentScene.supportsRawFlowBackground() }
    }
    val rawFlowPreviousSceneActive by remember {
        derivedStateOf { navState.getPreviousScene()?.supportsRawFlowBackground() == true }
    }
    val rawFlowTransitionSceneActive by remember {
        derivedStateOf {
            navState.isTransitioning &&
                (navState.transitionFromScene.supportsRawFlowBackground() ||
                    navState.transitionToScene.supportsRawFlowBackground())
        }
    }
    val rawFlowReturningToFlowScene by remember {
        derivedStateOf {
            rawFlowPreviousSceneActive &&
                (navState.isDraggingBack || navState.isAnimatingBack)
        }
    }
    // This edge changes only when the player sheet takes ownership of the bottom
    // chrome. Keeping it as a binary state prevents the hidden list scene from
    // driving its decorative background while the player is on top.
    var playerSheetOwnsBottomChrome by remember { mutableStateOf(false) }
    var showMiniPlayerQueueDialog by remember { mutableStateOf(false) }
    LaunchedEffect(playerSheetOwnsBottomChrome) {
        if (playerSheetOwnsBottomChrome) showMiniPlayerQueueDialog = false
    }

    val rawFlowLayerActive by remember {
        derivedStateOf {
            rawFlowSceneActive || rawFlowPreviousSceneActive || rawFlowTransitionSceneActive
        }
    }
    var playerLibraryPixelsVisible by remember { mutableStateOf(true) }
    val rawFlowMotionActive by remember {
        derivedStateOf {
            navData.uiForeground &&
                playerLibraryPixelsVisible &&
                (rawFlowLayerActive || navState.currentScene == NavScene.HOME || rawFlowReturningToFlowScene)
        }
    }
    val bottomChromeScrollState = remember { BottomChromeScrollState() }
    LaunchedEffect(bottomChromeScrollBehavior) {
        bottomChromeScrollState.setScrollMorphEnabled(
            bottomChromeScrollBehavior == BottomChromeScrollBehavior.DEFAULT
        )
    }
    var miniPlayerGestureBounds by remember { mutableStateOf<Rect?>(null) }
    var bottomNavigationGestureBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(bottomChromeScrollState, density) {
        // UIKit onScrollDown exposes one tab/accessory environment transition. Keep input
        // sensitivity separate from the single physical chrome travel, but never give the
        // MiniPlayer and navigation independent normalized owners.
        bottomChromeScrollState.updateGestureRangePx(with(density) { 72.dp.toPx() })
        bottomChromeScrollState.updateVisualTravelPx(with(density) { 28.dp.toPx() })
    }
    // Do NOT read playerSceneProgressState.value in the AppMainLayout composition body. Doing so
    // recomposes the entire MAIN navigation tree on every pointer frame and makes the sheet feel
    // one frame behind the finger. Observe only the binary ownership edge with snapshotFlow; this
    // state changes once when p leaves 0 and once when it returns to 0, while the actual p value is
    // still consumed inside graphicsLayer lambdas in the player/mini-player subtree.
    var listMarqueeSceneVisible by remember { mutableStateOf(true) }
    LaunchedEffect(playerSceneProgressState, bottomChromeScrollState) {
        snapshotFlow {
            (playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f) > 0f
        }
            .distinctUntilChanged()
            .collect { ownsSheet ->
                playerSheetOwnsBottomChrome = ownsSheet
                bottomChromeScrollState.setInteractionLocked(ownsSheet)
            }
    }
    LaunchedEffect(playerSceneProgressState) {
        snapshotFlow {
            (playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f) < 1f
        }
            .distinctUntilChanged()
            .collect { libraryPixelsVisible ->
                // retained-view implementation stops a holder's marquee only when its alpha reaches zero. Do not use
                // gesture direction as the owner: a partially reversed PLAYER gesture still leaves
                // library pixels on screen and must keep FLOW/marquee moving. At the exact PLAYER
                // endpoint the library presentation is completely hidden, so both clocks may stop.
                playerLibraryPixelsVisible = libraryPixelsVisible
                listMarqueeSceneVisible = libraryPixelsVisible
            }
    }
    LaunchedEffect(navState.currentScene) {
        bottomChromeScrollState.reset()
    }
    LaunchedEffect(
        navState.currentScene,
        bottomNavigationEnabled,
        miniPlayerStyle.expandedHeightDp,
        miniPlayerStyle.compactHeightDp,
        miniPlayerStyle.leadingMarginDp,
        miniPlayerStyle.trailingMarginDp,
        bottomNavigationStyle.heightDp,
        bottomNavigationStyle.bottomLiftDp,
        bottomNavigationStyle.leadingMarginDp,
        bottomNavigationStyle.trailingMarginDp,
    ) {
        bottomChromeScrollState.resetGeometryAnchor()
    }

    fun updateBottomChromeGeometry() {
        val chromeTop = listOfNotNull(
            miniPlayerGestureBounds?.top,
            bottomNavigationGestureBounds?.top,
        ).minOrNull()
        // AnimatedVisibility clears the bounds while chrome is leaving. Keep the
        // last valid anchor so floating actions follow the transition instead of
        // snapping back to the origin for one frame.
        chromeTop?.let(bottomChromeScrollState::updateTopInRootPx)
    }

    val emptyPlayerTitle = stringResource(R.string.player_no_music)
    val hasSong = navData.miniPlayerTitle.isNotBlank() &&
        navData.miniPlayerTitle != emptyPlayerTitle
    val bottomChromeHostVisible = !isSettingsScene && !isIndependentSourceScene
    val bottomChromeVisibilityAlpha = remember {
        Animatable(if (navData.bottomChromeHidden) 0f else 1f)
    }
    LaunchedEffect(bottomChromeHostVisible, navData.bottomChromeHidden) {
        if (!bottomChromeHostVisible) {
            bottomChromeVisibilityAlpha.snapTo(0f)
        } else {
            bottomChromeVisibilityAlpha.animateTo(
                targetValue = if (navData.bottomChromeHidden) 0f else 1f,
                animationSpec = tween(
                    durationMillis = if (navData.bottomChromeHidden) 500 else 200,
                    easing = SelectionTransitionEasing,
                ),
            )
        }
    }
    val bottomChromeInteractionEnabled = bottomChromeHostVisible && !navData.bottomChromeHidden
    // UIKit positions the minimized tab/accessory row from the full stable safe area. The old
    // Raw bottom bar deliberately subtracted 12dp, which leaves this iOS-style inline row visibly
    // too low (especially obvious on shorter compact phones).
    // Keep the vertical-position control in the geometry owner rather than
    // visually offsetting the glass surfaces. This lifts navigation,
    // MiniPlayer, gesture bounds and page content inset together, so the bar
    // can be moved away from an overly low screen edge without desynchronizing
    // touch/scroll geometry.
    val bottomChromeSystemBottom =
        rawStableNavigationBottomPadding() + bottomNavigationStyle.bottomLiftDp.dp
    val bottomChromeLayoutClass = if (with(density) { windowInfo.containerSize.width.toDp() } >= 600.dp) {
        BottomChromeLayoutClass.Regular
    } else {
        BottomChromeLayoutClass.Compact
    }
    val bottomChromeEnvironment = BottomChromeEnvironment(
        // Keep the physical chrome mounted while selection owns the bottom edge. retained-view implementation fades
        // the already-attached mini player/navigation out under SelectionMenu instead of removing
        // the holder tree on the first selection frame. The draw alpha below owns that handoff.
        active = bottomChromeHostVisible && (hasSong || bottomNavigationEnabled),
        layoutClass = bottomChromeLayoutClass,
        hasMiniPlayer = hasSong,
        hasNavigation = bottomNavigationEnabled,
        stableMode = bottomChromeScrollState.stableMode,
        transitionProgress = if (bottomChromeScrollState.stableMode == BottomChromeStableMode.Minimized) 1f else 0f,
        accessoryHeight = miniPlayerStyle.expandedHeightDp.dp,
        compactAccessoryHeight = miniPlayerStyle.compactHeightDp.dp,
        navigationHeight = bottomNavigationStyle.heightDp.dp,
        systemBottom = bottomChromeSystemBottom,
    )

    LaunchedEffect(hasSong, bottomChromeInteractionEnabled) {
        if (!hasSong || !bottomChromeInteractionEnabled) {
            miniPlayerGestureBounds = null
            updateBottomChromeGeometry()
            onMiniPlayerGestureBoundsChanged(null)
        }
    }
    LaunchedEffect(bottomNavigationEnabled, bottomChromeInteractionEnabled) {
        if (!bottomNavigationEnabled || !bottomChromeInteractionEnabled) {
            bottomNavigationGestureBounds = null
            updateBottomChromeGeometry()
            onBottomNavigationGestureBoundsChanged(null)
        }
    }

    val sideRailEnabled =
        !bottomNavigationEnabled &&
            navState.currentScene == NavScene.HOME &&
            !navState.isTransitioning &&
            !navState.isDraggingBack &&
            !navState.isAnimatingBack

    CompositionLocalProvider(
        LocalBottomChromeScrollState provides bottomChromeScrollState,
        LocalBottomChromeEnvironment provides bottomChromeEnvironment,
        LocalBottomChromeInsets provides bottomChromeEnvironment.insets,
    ) {
        ProvideRawFlowMode(rawFlowModeState) {
            CompositionLocalProvider(
                LocalAppBackdrop provides backdrop,
                LocalAppHazeState provides appHazeState,
            ) {
                AppSideRailHost(
                    enabled = sideRailEnabled,
                    onDestinationClick = onSideRailDestination,
                    openRequestToken = sideRailOpenRequestToken,
                    closeRequestToken = sideRailCloseRequestToken,
                    onExpandedChanged = onSideRailExpandedChanged,
                    homeSettingsContent = {
                        HomeHeaderSettingsSection(options = homeHeaderOptions)
                    },
                    background = {
                        // Keep one background renderer mounted below every scene so
                        // both layers alive here as well; only their alpha changes during a
                        // transition, so returning never exposes the theme background for a
                        // single frame.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(if (RawFlowTuningState.style == RawBackgroundStyle.STATIC) Color.Black else MiuixTheme.colorScheme.background)
                                .layerBackdrop(backgroundBackdrop)
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.hazeSource(appHazeState)
                                    } else {
                                        Modifier
                                    }
                                ),
                        ) {
                            PersistentSceneBackgroundLayers(
                                navState = navState,
                                transitionFrame = sceneTransitionFrame,
                                rawFlowMode = rawFlowModeState.value,
                                homeCarouselSongs = homeCarouselSongs,
                                currentSong = navData.currentSong,
                                homeCarouselState = homeCarouselState,
                                homeCarouselBackdropTransitionActive =
                                    homeCarouselBackdropTransitionActive,
                                rawFlowLayerActive = rawFlowLayerActive,
                                rawFlowMotionActive = rawFlowMotionActive,
                                uiForeground = navData.uiForeground,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                ) {
                    HomeFullCoverTransitionHost(
                        songs = homeCarouselSongs,
                        currentSong = homeCarouselCurrentSong,
                        queueCurrentIndex = navData.homeCarouselCurrentIndex,
                        onSelectSong = navCallbacks.onHomeCarouselSongClick,
                        onActiveChange = onHomeFullCoverActiveChange,
                        onExternalOpen = onHomeFullCoverLaunchRequest,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                            foregroundModifier,
                            homeFullCoverActive,
                            homeCenterReflectionAlpha,
                            homeCenterReflectionArtworkKey,
                            onCurrentArtworkLongPress,
                            onCurrentArtworkBoundsChanged,
                        ->
                        Box(modifier = foregroundModifier) {
                            CompositionLocalProvider(
                                LongTextMotionState.LocalListMarqueeVisibility provides
                                    listMarqueeSceneVisible,
                                LocalExternalRetainedSceneItemTransform provides playerLibraryTransform,
                                LocalExternalAlphabetIndexAlphaProvider provides
                                    playerAlphabetIndexAlphaProvider,
                                LocalAlphabetIndexOverlayRegistry provides
                                    alphabetIndexOverlayRegistry,
                                LocalLibraryFloatingToolbarController provides
                                    libraryFloatingToolbarController,
                                LocalHomeFloatingHeaderController provides
                                    homeFloatingHeaderController,
                            ) {
                                ComposeNavHost(
                                state = navState,
                                callbacks = navCallbacks,
                                data = navData,
                                 modifier = Modifier
                                     .fillMaxSize()
                                     .layerBackdrop(sceneBackdrop),
                                externalPageRenderer = externalPageRenderer,
                                showHomeSettingsShortcut = showHomeSettingsShortcut,
                                onSettingsClick = onSettingsClick ?: { navState.navigateToSettings() },
                                onHomeHeaderMenuAction = if (!bottomNavigationEnabled) {
                                    { onOpenSideRail() }
                                } else {
                                    null
                                },
                                homeCarouselState = homeCarouselState,
                                homeHeaderOptions = homeHeaderOptions,
                                homeCardLayoutState = homeCardLayoutState,
                                renderHomeBackdrop = false,
                                homeFullCoverActive = homeFullCoverActive,
                                homeFullCoverCenterReflectionAlpha = homeCenterReflectionAlpha,
                                homeFullCoverCenterReflectionArtworkKey = homeCenterReflectionArtworkKey,
                                onHomeCarouselCurrentArtworkLongPress = onCurrentArtworkLongPress,
                                onHomeCarouselCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                                sceneGestureExclusionBounds = miniPlayerGestureBounds,
                                // The full player sheet and SceneTransitionHost are stacked siblings.
                                // While the sheet owns the screen, only PlayerSceneController may
                                // interpret horizontal back/track gestures; otherwise one edge swipe
                                // can start both player and library transitions on the same pointer.
                                sceneGesturesEnabled = !playerSheetOwnsBottomChrome,
                                onSceneTransitionActiveChanged = { _ ->
                                    // Kept for callers that still observe the boolean callback.
                                },
                                onSceneTransitionFrameChanged = { frame ->
                                    sceneTransitionFrame.update(frame)
                                },
                                )
                            }

                            // Keep the alphabet scroller as a long-lived sibling of the retained
                            // library presentation. Pages only publish data/callbacks into the
                            // registry; preflight/incoming controllers therefore cannot steal or
                            // clear the visible scene's one scroller slot.
                            val alphabetIndexEntry = if (sceneTransitionFrame.active) {
                                alphabetIndexOverlayRegistry.entryFor(sceneTransitionFrame.fromScene.name)
                                    ?: alphabetIndexOverlayRegistry.entryFor(navState.currentScene.name)
                                    ?: alphabetIndexOverlayRegistry.entryFor(sceneTransitionFrame.toScene.name)
                            } else {
                                alphabetIndexOverlayRegistry.entryFor(navState.currentScene.name)
                            }
                            alphabetIndexEntry?.let { entry ->
                                CompositionLocalProvider(
                                    LocalExternalAlphabetIndexAlphaProvider provides
                                        playerAlphabetIndexAlphaProvider,
                                ) {
                                    RawAlphabetIndex(
                                        data = entry.data,
                                        modifier = entry.modifier,
                                        enabled = entry.enabled,
                                        minCellHeightDp = entry.minCellHeightDp,
                                        scrollActiveProvider = entry.scrollActiveProvider,
                                        onTopSelect = entry.onTopSelect,
                                        onSelect = entry.onSelect,
                                        allowSceneOverlay = false,
                                    )
                                }
                            }

                            // Keep the floating library toolbar outside sceneBackdrop/ComposeNavHost.
                            // It is a later sibling of the retained scene, so PivotTransition cannot
                            // clip/recompose it and liquid glass can safely sample the combined
                            // background + scene textures without self-referencing the source tree.
                            LibraryFloatingToolbarHost(
                                controller = libraryFloatingToolbarController,
                                currentScene = navState.currentScene,
                                transitionFrame = sceneTransitionFrame,
                                hazeState = appHazeState,
                                // This host is drawn after sceneBackdrop, so sampling the combined
                                // source is safe and matches physical glass: list rows, artwork and
                                // the persistent RawFlow background behind the toolbar all bend.
                                backdrop = backdrop,
                            )

                            // Render HOME SymbolButton-like material after sceneBackdrop. This is
                            // the same safe ownership boundary as the floating library toolbar: the
                            // circles can sample the complete recorded HOME scene without becoming
                            // part of the texture they sample.
                            HomeFloatingHeaderHost(
                                controller = homeFloatingHeaderController,
                                currentScene = navState.currentScene,
                                transitionFrame = sceneTransitionFrame,
                                // The floating buttons are later siblings of the retained HOME
                                // scene. Use the combined source so high-frequency carousel/text
                                // detail makes the lens displacement visible instead of sampling
                                // only the low-frequency RawFlow gradient.
                                backdrop = backdrop,
                                options = homeHeaderOptions,
                            )

                            // glass bottom navigation-style bottom chrome has one geometry owner. MiniPlayer
                            // accessory and navigation are sibling surfaces; NORMAL/FLOATING only
                            // select the renderer and never change bounds or motion ownership.
                            if (bottomChromeEnvironment.active && !ArtistArtworkViewerRuntime.visible) {
                                val miniPlayerSong = navData.miniPlayerSong ?: navData.currentSong
                                val miniCoverPath = miniPlayerSong.resolvePlaybackArtworkKey(
                                    navData.miniPlayerCoverPath
                                )
                                val miniPlayerAccent = rememberBottomAccentColor(miniCoverPath)

                                fun selectBottomScene(targetScene: NavScene) {
                                    if (targetScene == NavScene.SETTINGS) {
                                        onSettingsClick?.invoke() ?: navState.navigateToSettings()
                                    } else if (targetScene == NavScene.AUDIO_EFFECTS) {
                                        onAudioEffectsClick?.invoke()
                                            ?: navState.navigateFromBottomNavigation(targetScene)
                                    } else if (navState.currentScene.bottomNavigationRoot() != targetScene) {
                                        navState.navigateFromBottomNavigation(targetScene)
                                    }
                                }

                                fun selectBottomTab(index: Int) {
                                    tabScenes.getOrNull(index)?.let(::selectBottomScene)
                                }

                                GlassBottomChrome(
                                    environment = bottomChromeEnvironment,
                                    scrollState = bottomChromeScrollState,
                                    bottomBarStyle = bottomBarStyle,
                                    bottomBarMaterial = bottomBarMaterial,
                                    // Bottom chrome is rendered after the recorded scene and is not
                                    // part of either source layer. Sampling the combined backdrop
                                    // therefore cannot recurse and gives real behind-content
                                    // refraction (rows/artwork/text + persistent background).
                                    backdrop = backdrop,
                                    title = navData.miniPlayerTitle,
                                    artist = navData.miniPlayerArtist,
                                    lyricText = navData.miniPlayerLyric,
                                    lyricTranslation = navData.miniPlayerLyricTranslation,
                                    lyricSong = navData.lyricSong,
                                    isPlaying = navData.miniPlayerIsPlaying,
                                    progress = navData.miniPlayerProgress,
                                    playbackPositionMs = navData.playbackPositionMs,
                                    playbackDurationMs = navData.playbackDurationMs,
                                    coverPath = miniCoverPath,
                                    currentSong = miniPlayerSong,
                                    previousSong = navData.miniPlayerPreviousSong,
                                    nextSong = navData.miniPlayerNextSong,
                                    queueCurrentIndex = navData.queueCurrentIndex,
                                    queueSize = navData.queueSongs.size,
                                    accentColor = miniPlayerAccent,
                                    tabScenes = if (bottomNavigationEnabled) tabScenes else emptyList(),
                                    selectedTabIndex = selectedTabIndex,
                                    onTabSelected = ::selectBottomTab,
                                    onSearchSelected = { selectBottomScene(NavScene.SEARCH) },
                                    isSearchSelected = navState.currentScene.bottomNavigationRoot() == NavScene.SEARCH,
                                    onOpenPlayer = navCallbacks.onNavigateToPlayer,
                                    onPlayPause = navCallbacks.onMiniPlayerPlayPause,
                                    onSkipPrevious = navCallbacks.onMiniPlayerPrevious,
                                    onSkipNext = navCallbacks.onMiniPlayerNext,
                                    onOpenQueue = { showMiniPlayerQueueDialog = true },
                                    onExpandDragStart = navCallbacks.onMiniPlayerExpandDragStart,
                                    onExpandDragProgress = navCallbacks.onMiniPlayerExpandDragProgress,
                                    onExpandDragEnd = navCallbacks.onMiniPlayerExpandDragEnd,
                                    playerSceneProgressState = playerSceneProgressState,
                                    artworkVisible = !suppressMiniPlayerArtwork,
                                    onCoverBoundsChanged = navCallbacks.onMiniPlayerCoverBoundsChanged,
                                    onCoverTargetChanged = navCallbacks.onMiniPlayerCoverTargetChanged,
                                    onMiniPlayerBoundsChanged = { bounds ->
                                        miniPlayerGestureBounds = bounds
                                        updateBottomChromeGeometry()
                                        onMiniPlayerGestureBoundsChanged(bounds)
                                    },
                                    onNavigationBoundsChanged = { bounds ->
                                        bottomNavigationGestureBounds = bounds
                                        updateBottomChromeGeometry()
                                        onBottomNavigationGestureBoundsChanged(bounds)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        // SelectionMenu enters for 500ms and exits for 200ms. Feed
                                        // the exact inverse clock into the retained bottom chrome so
                                        // it fades away progressively under the menu and begins its
                                        // return as soon as the menu starts leaving.
                                        .graphicsLayer {
                                            alpha = bottomChromeVisibilityAlpha.value.coerceIn(0f, 1f)
                                        },
                                )

                            MiniPlayerQueueDialog(
                                show = showMiniPlayerQueueDialog,
                                songs = navData.queueSongs,
                                currentIndex = navData.queueCurrentIndex,
                                currentSong = miniPlayerSong,
                                currentCoverPath = navData.miniPlayerCoverPath,
                                onSongClick = navCallbacks.onQueueSongClick,
                                onDismissRequest = { showMiniPlayerQueueDialog = false },
                            )
                            }
                            AppNoticeHost(
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            }
                        }
                    }
                }
            }
        }
    }

private const val MAIN_RAW_FLOW_FRAME_INTERVAL_MS = 16L

private fun NavScene.supportsRawFlowBackground(): Boolean {
    return when (this) {
        NavScene.LIBRARY_ANALYSIS_DETAIL,
        NavScene.SONG_STATS,
        NavScene.SONGS,
        NavScene.FOLDERS,
        NavScene.FOLDER_HIERARCHY,
        NavScene.FOLDER_DETAIL,
        NavScene.ALBUMS,
        NavScene.ALBUM_DETAIL,
        NavScene.ARTISTS,
        NavScene.ARTIST_DETAIL,
        NavScene.ARTIST_BIOGRAPHY,
        NavScene.PLAYLISTS,
        NavScene.PLAYLIST_LIST,
        NavScene.PLAYLIST_DETAIL_PAGE,
        NavScene.PLAYLIST_DETAIL,
        NavScene.QUEUE,
        NavScene.RECENTLY_ADDED,
        NavScene.DAILY_20,
        NavScene.GENRE,
        NavScene.YEAR,
        NavScene.COMPOSER,
        NavScene.GENRE_DETAIL,
        NavScene.YEAR_DETAIL,
        NavScene.COMPOSER_DETAIL,
        NavScene.SEARCH,
        NavScene.SOURCE_IMPORT -> true
        else -> false
    }
}

private fun NavScene.isSettingsScene(): Boolean {
    return when (this) {
        NavScene.SETTINGS,
        NavScene.APPEARANCE,
        NavScene.ALBUM_ART_SETTINGS,
        NavScene.GLOBAL_FONT_SETTINGS,
        NavScene.LYRIC_FONT_SETTINGS,
        NavScene.LYRIC_MANAGEMENT,
        NavScene.PLAYER_INTERFACE,
        NavScene.STATUS_BAR_LYRIC,
        NavScene.WEBDAV_BACKUP,
        NavScene.SCAN_SETTINGS,
        NavScene.ABOUT -> true
        else -> false
    }
}
