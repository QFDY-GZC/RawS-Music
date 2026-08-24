package com.rawsmusic.core.ui.scene

import android.os.Build
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.rawsmusic.core.ui.R
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselBackdrop
import com.rawsmusic.core.ui.scene.pages.rememberHomeArtworkCarouselState
import com.rawsmusic.core.ui.scene.pages.HomeHeaderSettingsSection
import com.rawsmusic.core.ui.scene.pages.rememberHomeHeaderOptionsState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.flow.ProvideRawFlowMode
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowModeState
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTab
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTabs
import com.rawsmusic.core.ui.widget.bottombar.NormalBottomChrome
import com.rawsmusic.core.ui.widget.player.rememberBottomAccentColor
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.module.data.prefs.BottomBarStyle
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.core.ui.systemui.rawNavigationBarsPadding
import com.rawsmusic.core.ui.systemui.rawReducedNavigationBottomPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.exp
import kotlin.math.roundToInt

internal val LocalAppHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * App 主布局。
 *
 * 替代旧 XML 主界面壳。
 * 组合：主内容 + LiquidBottomTabs 液态玻璃底部导航栏。
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
) {
    NavigationPersistenceEffect(navState)

    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White

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
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val context = LocalContext.current
    val floatingMiniFlingBounds = remember(context) {
        ViewConfiguration.get(context).let { config ->
            config.scaledMinimumFlingVelocity.toFloat() to config.scaledMaximumFlingVelocity.toFloat()
        }
    }
    val floatingMiniBottomPadding = rawReducedNavigationBottomPadding(reduceBy = 12.dp)
    val floatingMiniSheetTravelPx = with(density) {
        (windowInfo.containerSize.height.toFloat() - (122.dp + floatingMiniBottomPadding).toPx())
            .coerceAtLeast(1f)
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

    val backdrop = rememberLayerBackdrop()
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
    val homeCarouselCurrentSong = homeCarouselSongs
        .getOrNull(navData.homeCarouselCurrentIndex)
        ?: navData.currentSong
    val homeCarouselState = rememberHomeArtworkCarouselState(
        songs = homeCarouselSongs,
        currentSong = homeCarouselCurrentSong,
        reportedQueueIndex = navData.homeCarouselCurrentIndex,
    )
    val homeHeaderOptions = rememberHomeHeaderOptionsState()
    val sceneTransitionFrame = remember {
        SceneTransitionFrameState(navState.currentScene)
    }
    val homeCarouselBackdropTransitionActive by remember {
        derivedStateOf {
            homeCarouselState.interactionActive ||
                homeCarouselState.hostTransitionActive ||
                kotlin.math.abs(homeCarouselState.progress) > 0.001f
        }
    }
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
    val rawFlowLayerActive by remember {
        derivedStateOf {
            rawFlowSceneActive || rawFlowPreviousSceneActive || rawFlowTransitionSceneActive
        }
    }
    val rawFlowMotionActive by remember {
        derivedStateOf {
            navData.uiForeground &&
                !playerSheetOwnsBottomChrome &&
                (
                    ((rawFlowSceneActive || navState.currentScene == NavScene.HOME) &&
                        !navState.isTransitioning &&
                        !navState.isDraggingBack &&
                        !navState.isAnimatingBack) ||
                        rawFlowReturningToFlowScene
                    )
        }
    }
    val bottomChromeScrollState = remember { BottomChromeScrollState() }
    var miniPlayerGestureBounds by remember { mutableStateOf<Rect?>(null) }
    var bottomNavigationGestureBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(bottomChromeScrollState, density) {
        // Match the larger navigation displacement so both stacked chrome layers share one
        // normalized gesture range. The actual layers still use their own visual travel.
        bottomChromeScrollState.updateScrollRangePx(with(density) { 96.dp.toPx() })
        bottomChromeScrollState.updateMiniPlayerScrollRangePx(with(density) { 64.dp.toPx() })
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
            val playerSheetVisible =
                (playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f) > 0.001f
            val sceneTransitionActive =
                navState.isTransitioning || navState.isDraggingBack || navState.isAnimatingBack
            !playerSheetVisible && !sceneTransitionActive
        }
            .distinctUntilChanged()
            .collect { listMarqueeSceneVisible = it }
    }
    LaunchedEffect(navState.currentScene) {
        bottomChromeScrollState.reset()
    }
    LaunchedEffect(navState.currentScene, bottomNavigationEnabled, bottomBarStyle) {
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

    val sideRailEnabled =
        !bottomNavigationEnabled &&
            navState.currentScene == NavScene.HOME &&
            !navState.isTransitioning &&
            !navState.isDraggingBack &&
            !navState.isAnimatingBack

    CompositionLocalProvider(LocalBottomChromeScrollState provides bottomChromeScrollState) {
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
                                .background(MiuixTheme.colorScheme.background)
                                .layerBackdrop(backdrop)
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
                                currentSong = homeCarouselCurrentSong,
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
                                    listMarqueeSceneVisible
                            ) {
                                ComposeNavHost(
                                state = navState,
                                callbacks = navCallbacks,
                                data = navData,
                                modifier = Modifier.fillMaxSize(),
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
                                renderHomeBackdrop = false,
                                homeFullCoverActive = homeFullCoverActive,
                                homeFullCoverCenterReflectionAlpha = homeCenterReflectionAlpha,
                                homeFullCoverCenterReflectionArtworkKey = homeCenterReflectionArtworkKey,
                                onHomeCarouselCurrentArtworkLongPress = onCurrentArtworkLongPress,
                                onHomeCarouselCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                                sceneGestureExclusionBounds = miniPlayerGestureBounds,
                                onSceneTransitionActiveChanged = { _ ->
                                    // Kept for callers that still observe the boolean callback.
                                },
                                onSceneTransitionFrameChanged = { frame ->
                                    sceneTransitionFrame.update(frame)
                                },
                                )
                            }

                            // 设置页由独立 Activity 承载；若旧路径误把主导航切到设置场景，也不显示底部栏。
                            if (!isSettingsScene && !isIndependentSourceScene) {
                                val showBottomChrome = !navData.bottomChromeHidden
                                // Read the shared progress inside placement lambdas. The list can
                                // update it every frame without recomposing ComposeNavHost or
                                // recreating any artwork/list item.
                                val miniPlayerScrollModifier = Modifier.offset {
                                    val progress = bottomChromeScrollState.renderVisibilityProgress
                                    val offsetDp = if (!bottomNavigationEnabled) {
                                        (-4).dp
                                    } else {
                                        (-68f + 64f * progress).dp
                                    }
                                    IntOffset(0, offsetDp.roundToPx())
                                }
                                val bottomTabsScrollModifier = Modifier.offset {
                                    val progress = bottomChromeScrollState.renderVisibilityProgress
                                    IntOffset(0, (-4f + 96f * progress).dp.roundToPx())
                                }
                                val normalBottomChromeScrollModifier = Modifier.offset {
                                    IntOffset(
                                        0,
                                        bottomChromeScrollState.renderChromeOffsetPx.roundToInt(),
                                    )
                                }
                                // MiniPlayer：在导航栏上方，所有页面可见
                                val emptyPlayerTitle = stringResource(R.string.player_no_music)
                                val hasSong = navData.miniPlayerTitle.isNotBlank() &&
                                    navData.miniPlayerTitle != emptyPlayerTitle
                                LaunchedEffect(hasSong, showBottomChrome) {
                                    if (!hasSong || !showBottomChrome) {
                                        miniPlayerGestureBounds = null
                                        updateBottomChromeGeometry()
                                        onMiniPlayerGestureBoundsChanged(null)
                                    }
                                }
                                LaunchedEffect(bottomNavigationEnabled, showBottomChrome) {
                                    if (!bottomNavigationEnabled || !showBottomChrome) {
                                        bottomNavigationGestureBounds = null
                                        updateBottomChromeGeometry()
                                        onBottomNavigationGestureBoundsChanged(null)
                                    }
                                }
                                val miniCoverPath = navData.currentSong.resolvePlaybackArtworkKey(
                                    navData.miniPlayerCoverPath
                                )
                                val miniPlayerAccent = rememberBottomAccentColor(miniCoverPath)

                                fun selectBottomTab(index: Int) {
                                    tabScenes.getOrNull(index)?.let { targetScene ->
                                        if (targetScene == NavScene.SETTINGS) {
                                            onSettingsClick?.invoke() ?: navState.navigateToSettings()
                                        } else if (targetScene == NavScene.AUDIO_EFFECTS) {
                                            onAudioEffectsClick?.invoke()
                                                ?: navState.navigateFromBottomNavigation(targetScene)
                                        } else if (navState.currentScene.bottomNavigationRoot() != targetScene) {
                                            navState.navigateFromBottomNavigation(targetScene)
                                        }
                                    }
                                }

                                // The floating mini-player should enter the same persistent PLAYER
                                // sheet as NORMAL chrome. Keep horizontal track switching in
                                // ComposeMiniPlayer; this parent only wins a vertical upward drag
                                // and feeds the exact same PlayerSceneController progress callbacks,
                                // so shared artwork/background/content use the existing animation.
                                var floatingExpandProgress by remember { mutableStateOf(0f) }
                                var floatingExpandActive by remember { mutableStateOf(false) }
                                var floatingDragTravelPx by remember { mutableFloatStateOf(floatingMiniSheetTravelPx) }
                                val floatingMiniExpandModifier = Modifier.pointerInput(
                                    floatingMiniSheetTravelPx,
                                    windowInfo.containerSize.width,
                                ) {
                                    val tracker = VelocityTracker()
                                    detectVerticalDragGestures(
                                        onDragStart = { position ->
                                            floatingExpandProgress = playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f
                                            // Match the live collapsed geometry. With stacked
                                            // navigation the floating bar is physically higher
                                            // than the old synthetic 122dp peek, so its drag
                                            // distance must start from the bar's real top edge.
                                            floatingDragTravelPx = (miniPlayerGestureBounds?.top
                                                ?: floatingMiniSheetTravelPx).coerceAtLeast(1f)
                                            floatingExpandActive = false
                                            tracker.resetTracking()
                                            tracker.addPosition(android.os.SystemClock.uptimeMillis(), position)
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            if (!floatingExpandActive && dragAmount < 0f) {
                                                // MAIN has no lower anchor to drag toward. Ignore a
                                                // downward-first stream; upward motion claims the
                                                // gesture and starts the normal shared-sheet handoff.
                                                floatingExpandActive = true
                                                navCallbacks.onMiniPlayerExpandDragStart()
                                            }
                                            if (floatingExpandActive) {
                                                floatingExpandProgress = (
                                                    floatingExpandProgress - dragAmount / floatingDragTravelPx
                                                ).coerceIn(0f, 1f)
                                                navCallbacks.onMiniPlayerExpandDragProgress(floatingExpandProgress)
                                                change.consume()
                                            }
                                        },
                                        onDragEnd = {
                                            if (floatingExpandActive) {
                                                val minVelocity = floatingMiniFlingBounds.first
                                                val maxVelocity = floatingMiniFlingBounds.second
                                                val rawVelocityY = tracker.calculateVelocity().y
                                                val velocityY = when {
                                                    kotlin.math.abs(rawVelocityY) < minVelocity -> 0f
                                                    rawVelocityY > maxVelocity -> maxVelocity
                                                    rawVelocityY < -maxVelocity -> -maxVelocity
                                                    else -> rawVelocityY
                                                }
                                                val shouldOpen = when {
                                                    velocityY < 0f -> true
                                                    velocityY > 0f -> false
                                                    else -> floatingExpandProgress >= 0.5f
                                                }
                                                val parentWidthPx = windowInfo.containerSize.width.toFloat().coerceAtLeast(1f)
                                                navCallbacks.onMiniPlayerExpandDragEnd(shouldOpen, velocityY / parentWidthPx)
                                            }
                                            floatingExpandActive = false
                                        },
                                        onDragCancel = {
                                            if (floatingExpandActive) {
                                                navCallbacks.onMiniPlayerExpandDragEnd(
                                                    floatingExpandProgress >= 0.5f,
                                                    0f,
                                                )
                                            }
                                            floatingExpandActive = false
                                        },
                                    )
                                }

                                if (bottomBarStyle == BottomBarStyle.NORMAL && bottomNavigationEnabled) {
                                    if (hasSong && showBottomChrome) {
                                        NormalBottomChrome(
                                            title = navData.miniPlayerTitle,
                                            artist = navData.miniPlayerArtist,
                                            lyricText = navData.miniPlayerLyric,
                                            lyricTranslation = navData.miniPlayerLyricTranslation,
                                            isPlaying = navData.miniPlayerIsPlaying,
                                            progress = navData.miniPlayerProgress,
                                            coverPath = miniCoverPath,
                                            currentSong = navData.currentSong,
                                            previousSong = navData.miniPlayerPreviousSong,
                                            nextSong = navData.miniPlayerNextSong,
                                            queueCurrentIndex = navData.queueCurrentIndex,
                                            queueSize = navData.queueSongs.size,
                                            accentColor = miniPlayerAccent,
                                            backdrop = backdrop,
                                            tabScenes = tabScenes,
                                            selectedTabIndex = selectedTabIndex,
                                            onTabSelected = ::selectBottomTab,
                                            onOpenPlayer = navCallbacks.onNavigateToPlayer,
                                            onPlayPause = navCallbacks.onMiniPlayerPlayPause,
                                            onSkipPrevious = navCallbacks.onMiniPlayerPrevious,
                                            onSkipNext = navCallbacks.onMiniPlayerNext,
                                            onExpandDragStart = navCallbacks.onMiniPlayerExpandDragStart,
                                            onExpandDragProgress = navCallbacks.onMiniPlayerExpandDragProgress,
                                            onExpandDragEnd = navCallbacks.onMiniPlayerExpandDragEnd,
                                            drivePlayerScene = true,
                                            playerSceneProgressState = playerSceneProgressState,
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
                                                .then(normalBottomChromeScrollModifier),
                                        )
                                    }
                                } else {
                                    AnimatedVisibility(
                                        visible = hasSong && showBottomChrome,
                                        enter = fadeIn() + slideInVertically { it },
                                        exit = fadeOut() + slideOutVertically { it },
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    ) {
                                        ComposeMiniPlayer(
                                            title = navData.miniPlayerTitle,
                                            artist = navData.miniPlayerArtist,
                                            lyricText = navData.miniPlayerLyric,
                                            lyricTranslation = navData.miniPlayerLyricTranslation,
                                            isPlaying = navData.miniPlayerIsPlaying,
                                            progress = navData.miniPlayerProgress,
                                            coverPath = miniCoverPath,
                                            currentSong = navData.currentSong,
                                            previousSong = navData.miniPlayerPreviousSong,
                                            nextSong = navData.miniPlayerNextSong,
                                            queueCurrentIndex = navData.queueCurrentIndex,
                                            queueSize = navData.queueSongs.size,
                                            animateArtwork = false,
                                            backdrop = backdrop,
                                            onPlayPause = navCallbacks.onMiniPlayerPlayPause,
                                            onSkipPrevious = navCallbacks.onMiniPlayerPrevious,
                                            onSkipNext = navCallbacks.onMiniPlayerNext,
                                            onSwitchProgress = { _, _ -> Unit },
                                            onClick = navCallbacks.onNavigateToPlayer,
                                            onCoverBoundsChanged = navCallbacks.onMiniPlayerCoverBoundsChanged,
                                            onCoverTargetChanged = navCallbacks.onMiniPlayerCoverTargetChanged,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 24.dp)
                                                .then(miniPlayerScrollModifier)
                                                .rawNavigationBarsPadding(reduceBy = 12.dp)
                                                // Keep the gesture owner on the *placed* floating
                                                // mini-player. When stacked navigation is visible
                                                // the bar is shifted upward by ~68dp; attaching the
                                                // pointerInput to AnimatedVisibility leaves the hit
                                                // region at the unshifted bottom slot, so a finger
                                                // on the visible bar can never start the upward drag.
                                                .then(floatingMiniExpandModifier)
                                                .graphicsLayer {
                                                    // Use the same content handoff curve as the
                                                    // NORMAL mini-player while the floating bar
                                                    // drives MAIN -> PLAYER interactively.
                                                    alpha = exp(-300f * (playerSceneProgressState?.value ?: 0f).coerceIn(0f, 1f))
                                                }
                                                .onGloballyPositioned { coordinates ->
                                                    val bounds = coordinates.boundsInRoot()
                                                    miniPlayerGestureBounds = bounds
                                                    updateBottomChromeGeometry()
                                                    onMiniPlayerGestureBoundsChanged(bounds)
                                                },
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = showBottomChrome && bottomNavigationEnabled,
                                        enter = fadeIn() + slideInVertically { it },
                                        exit = fadeOut() + slideOutVertically { it },
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    ) {
                                        key(tabScenes.joinToString(separator = "|") { it.tag }) {
                                            LiquidBottomTabs(
                                                selectedTabIndex = selectedTabIndex,
                                                onTabSelected = ::selectBottomTab,
                                                backdrop = backdrop,
                                                tabsCount = tabScenes.size,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 24.dp)
                                                    .then(bottomTabsScrollModifier)
                                                    .rawNavigationBarsPadding(reduceBy = 12.dp)
                                                    .onGloballyPositioned { coordinates ->
                                                        val bounds = coordinates.boundsInRoot()
                                                        bottomNavigationGestureBounds = bounds
                                                        updateBottomChromeGeometry()
                                                        onBottomNavigationGestureBoundsChanged(bounds)
                                                    },
                                            ) {
                                                tabScenes.forEach { scene ->
                                                    LiquidBottomTab(
                                                        onClick = { selectBottomTab(tabScenes.indexOf(scene)) },
                                                    ) {
                                                        BottomNavigationEntryIcon(
                                                            scene = scene,
                                                            tint = contentColor,
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                        BasicText(
                                                            scene.bottomNavigationLabel(),
                                                            style = TextStyle(contentColor, 10.sp, FontWeight.SemiBold),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
        NavScene.SONGS,
        NavScene.FOLDERS,
        NavScene.FOLDER_HIERARCHY,
        NavScene.ALBUMS,
        NavScene.ALBUM_DETAIL,
        NavScene.ARTISTS,
        NavScene.ARTIST_DETAIL,
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
