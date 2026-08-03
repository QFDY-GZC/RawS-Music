package com.rawsmusic.core.ui.scene

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselBackdrop
import com.rawsmusic.core.ui.scene.pages.rememberHomeArtworkCarouselState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.flow.ProvideRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowModeState
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTab
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTabs
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.core.ui.systemui.rawNavigationBarsPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    onHomeFullCoverActiveChange: (Boolean) -> Unit = {},
    onHomeFullCoverLaunchRequest: (Rect) -> Boolean = { false },
) {
    NavigationPersistenceEffect(navState)

    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White

    val bottomNavigationEnabled by PersonalizationPreferences.bottomNavigationEnabled.collectAsState()
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
    val homeCarouselSongs = remember(navData.queueSongs, navData.currentSong) {
        navData.queueSongs.ifEmpty { listOfNotNull(navData.currentSong) }
    }
    val homeCarouselState = rememberHomeArtworkCarouselState(
        songs = homeCarouselSongs,
        currentSong = navData.currentSong,
        reportedQueueIndex = navData.queueCurrentIndex,
    )
    var sceneTransitionActive by remember { mutableStateOf(false) }
    val homeBackdropActive by remember {
        derivedStateOf {
            !sceneTransitionActive && (
                navState.currentScene == NavScene.HOME ||
                    navState.backPreviewScene == NavScene.HOME ||
                    (navState.isTransitioning &&
                    (navState.transitionFromScene == NavScene.HOME ||
                        navState.transitionToScene == NavScene.HOME))
            )
        }
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
    val rawFlowLayerActive by remember {
        derivedStateOf {
            rawFlowSceneActive || rawFlowPreviousSceneActive || rawFlowTransitionSceneActive
        }
    }
    val rawFlowMotionActive by remember {
        derivedStateOf {
            navData.uiForeground &&
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
    LaunchedEffect(navState.currentScene) {
        bottomChromeScrollState.reset()
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
                    background = {
                        // One fixed background is shared by the rail and every sliding page. The
                        // previous rail-owned solid background caused a visible seam against flow.
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
                            when {
                                homeBackdropActive && homeCarouselBackdropTransitionActive ->
                                    HomeArtworkCarouselBackdrop(
                                    songs = homeCarouselSongs,
                                    currentSong = navData.currentSong,
                                    state = homeCarouselState,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                homeBackdropActive || rawFlowLayerActive -> RawFlowBackground(
                                    mode = rawFlowModeState.value,
                                    sourceCoverKey = navData.currentSong
                                        .resolvePlaybackArtworkKey(null),
                                    modifier = Modifier.fillMaxSize(),
                                    active = navData.uiForeground,
                                    motionEnabled = rawFlowMotionActive,
                                    frameIntervalMs = MAIN_RAW_FLOW_FRAME_INTERVAL_MS,
                                )
                            }
                        }
                    },
                ) {
                    HomeFullCoverTransitionHost(
                        songs = homeCarouselSongs,
                        currentSong = navData.currentSong,
                        queueCurrentIndex = navData.queueCurrentIndex,
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
                                ComposeNavHost(
                                state = navState,
                                callbacks = navCallbacks,
                                data = navData,
                                modifier = Modifier.fillMaxSize(),
                                externalPageRenderer = externalPageRenderer,
                                showHomeSettingsShortcut = showHomeSettingsShortcut,
                                onSettingsClick = onSettingsClick ?: { navState.navigateToSettings() },
                                homeCarouselState = homeCarouselState,
                                renderHomeBackdrop = false,
                                homeFullCoverActive = homeFullCoverActive,
                                homeFullCoverCenterReflectionAlpha = homeCenterReflectionAlpha,
                                homeFullCoverCenterReflectionArtworkKey = homeCenterReflectionArtworkKey,
                                onHomeCarouselCurrentArtworkLongPress = onCurrentArtworkLongPress,
                                onHomeCarouselCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                                sceneGestureExclusionBounds = miniPlayerGestureBounds,
                                onSceneTransitionActiveChanged = { active ->
                                    sceneTransitionActive = active
                                },
                            )

                            // 设置页由独立 Activity 承载；若旧路径误把主导航切到设置场景，也不显示底部栏。
                            if (!isSettingsScene && !isIndependentSourceScene) {
                                val showBottomChrome = !navData.bottomChromeHidden
                                val chromeHidden = bottomChromeScrollState.hidden
                                val miniPlayerOffsetY by animateDpAsState(
                                    targetValue = if (chromeHidden || !bottomNavigationEnabled) (-4).dp else (-68).dp,
                                    animationSpec = tween(durationMillis = 240),
                                    label = "mini-player-chrome-offset"
                                )
                                val bottomTabsOffsetY by animateDpAsState(
                                    targetValue = if (chromeHidden) 92.dp else (-4).dp,
                                    animationSpec = tween(durationMillis = 240),
                                    label = "bottom-tabs-scroll-offset"
                                )
                                // MiniPlayer：在导航栏上方，所有页面可见
                                val hasSong = navData.miniPlayerTitle.isNotBlank() &&
                                    navData.miniPlayerTitle != "暂无音乐播放"
                                LaunchedEffect(hasSong, showBottomChrome) {
                                    if (!hasSong || !showBottomChrome) {
                                        miniPlayerGestureBounds = null
                                    }
                                }
                                AnimatedVisibility(
                                    visible = hasSong && showBottomChrome,
                                    enter = fadeIn() + slideInVertically { it },
                                    exit = fadeOut() + slideOutVertically { it },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                ) {
                                    val miniCoverPath = navData.currentSong.resolvePlaybackArtworkKey(
                                        navData.miniPlayerCoverPath
                                    )

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
                                        // The mini-player and home artwork carousel render the same queue, but
                                        // they own independent transition timelines. Feeding this progress into
                                        // the home carousel starts a second transition after a carousel gesture
                                        // commits, producing the visible target -> old -> target flash.
                                        onSwitchProgress = { _, _ -> Unit },
                                        onClick = navCallbacks.onNavigateToPlayer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp)
                                            .offset(y = miniPlayerOffsetY)
                                            .rawNavigationBarsPadding(reduceBy = 12.dp)
                                            .onGloballyPositioned { coordinates ->
                                                miniPlayerGestureBounds = coordinates.boundsInRoot()
                                            }
                                    )
                                }

                                AnimatedVisibility(
                                    visible = showBottomChrome && bottomNavigationEnabled,
                                    enter = fadeIn() + slideInVertically { it },
                                    exit = fadeOut() + slideOutVertically { it },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                ) {
                                    key(tabScenes.joinToString(separator = "|") { it.tag }) {
                                        LiquidBottomTabs(
                                            selectedTabIndex = selectedTabIndex,
                                            onTabSelected = { index ->
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
                                            },
                                            backdrop = backdrop,
                                            tabsCount = tabScenes.size,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 24.dp)
                                                .offset(y = bottomTabsOffsetY)
                                                .rawNavigationBarsPadding(reduceBy = 12.dp)
                                        ) {
                                            tabScenes.forEach { scene ->
                                                LiquidBottomTab(
                                                    onClick = {
                                            if (scene == NavScene.SETTINGS) {
                                                onSettingsClick?.invoke() ?: navState.navigateToSettings()
                                            } else if (scene == NavScene.AUDIO_EFFECTS) {
                                                onAudioEffectsClick?.invoke()
                                                    ?: navState.navigateFromBottomNavigation(scene)
                                            } else if (navState.currentScene.bottomNavigationRoot() != scene) {
                                                navState.navigateFromBottomNavigation(scene)
                                            }
                                                    }
                                                ) {
                                                    BottomNavigationEntryIcon(
                                                        scene = scene,
                                                        tint = contentColor,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                    BasicText(
                                                        scene.bottomNavigationLabel(),
                                                        style = TextStyle(contentColor, 10.sp, FontWeight.SemiBold)
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
