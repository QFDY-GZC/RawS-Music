package com.rawsmusic.core.ui.scene

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
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
    onSettingsClick: (() -> Unit)? = null
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
            val rootScene = navState.currentScene.bottomNavigationRoot()
            val exactIndex = tabScenes.indexOf(rootScene)
            if (exactIndex >= 0) {
                exactIndex
            } else {
                tabScenes.indexOf(NavScene.HOME).coerceAtLeast(0)
            }
        }
    }
    val showHomeSettingsShortcut by remember(tabScenes, bottomNavigationEnabled) {
        derivedStateOf { !bottomNavigationEnabled || NavScene.SETTINGS !in tabScenes }
    }
    val isSettingsScene by remember {
        derivedStateOf { navState.currentScene.isSettingsScene() }
    }

    val backdrop = rememberLayerBackdrop()
    // Vendor RenderNodes can lose their recorded texture while the process remains
    // alive in the background. Rebind every source/effect pair on foreground entry.
    val appHazeState = remember(navData.uiForeground) { HazeState() }
    val rawFlowModeState = rememberRawFlowModeState()
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
                    (rawFlowSceneActive && !navState.isTransitioning && !navState.isDraggingBack && !navState.isAnimatingBack) ||
                        rawFlowReturningToFlowScene
                    )
        }
    }
    val bottomChromeScrollState = remember { BottomChromeScrollState() }
    LaunchedEffect(navState.currentScene) {
        bottomChromeScrollState.reset()
    }

    CompositionLocalProvider(LocalBottomChromeScrollState provides bottomChromeScrollState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 主内容（背景 + 导航）一起进入 layerBackdrop，保证底栏液态玻璃能采到流光背景。
        ProvideRawFlowMode(rawFlowModeState) {
            CompositionLocalProvider(
                LocalAppBackdrop provides backdrop,
                LocalAppHazeState provides appHazeState
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                ) {
                    if (rawFlowLayerActive) {
                        RawFlowBackground(
                            mode = rawFlowModeState.value,
                            sourceCoverKey = navData.currentSong?.coverKey,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.hazeSource(appHazeState)
                                    } else {
                                        Modifier
                                    }
                                ),
                            active = navData.uiForeground,
                            motionEnabled = rawFlowMotionActive,
                            frameIntervalMs = MAIN_RAW_FLOW_FRAME_INTERVAL_MS
                        )
                    }

                    ComposeNavHost(
                        state = navState,
                        callbacks = navCallbacks,
                        data = navData,
                        modifier = Modifier.fillMaxSize(),
                        externalPageRenderer = externalPageRenderer,
                        showHomeSettingsShortcut = showHomeSettingsShortcut,
                        onSettingsClick = onSettingsClick ?: { navState.navigateToSettings() },
                    )
                }
            }
        }

        // 设置页由独立 Activity 承载；若旧路径误把主导航切到设置场景，也不显示底部栏。
        if (!isSettingsScene) {
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
            val hasSong = navData.miniPlayerTitle.isNotBlank() && navData.miniPlayerTitle != "暂无音乐播放"
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
                    animateArtwork = false,
                    backdrop = backdrop,
                    onPlayPause = navCallbacks.onMiniPlayerPlayPause,
                    onSkipPrevious = navCallbacks.onMiniPlayerPrevious,
                    onSkipNext = navCallbacks.onMiniPlayerNext,
                    onClick = navCallbacks.onNavigateToPlayer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .offset(y = miniPlayerOffsetY)
                        .rawNavigationBarsPadding(reduceBy = 12.dp)
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
                        selectedTabIndex = { selectedTabIndex },
                        onTabSelected = { index ->
                            tabScenes.getOrNull(index)?.let { targetScene ->
                                if (targetScene == NavScene.SETTINGS) {
                                    onSettingsClick?.invoke() ?: navState.navigateToSettings()
                                } else if (navState.currentScene.bottomNavigationRoot() != targetScene) {
                                    navState.navigateTo(targetScene)
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
                                    } else if (navState.currentScene.bottomNavigationRoot() != scene) {
                                        navState.navigateTo(scene)
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

private const val MAIN_RAW_FLOW_FRAME_INTERVAL_MS = 25L

private fun NavScene.supportsRawFlowBackground(): Boolean {
    return when (this) {
        NavScene.HOME,
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
        NavScene.SEARCH -> true
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
