package com.rawsmusic.core.ui.scene

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.widget.flow.ProvideRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowModeState
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTab
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTabs
import com.rawsmusic.core.ui.systemui.rawNavigationBarsPadding
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings

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
    val iconColorFilter = ColorFilter.tint(contentColor)

    // 5 个主 Tab 对应的 NavScene，音效固定在中间。
    val tabScenes = remember { listOf(NavScene.HOME, NavScene.SONGS, NavScene.AUDIO_EFFECTS, NavScene.SEARCH, NavScene.SETTINGS) }

    // 根据当前场景推导选中 Tab 索引
    val selectedTabIndex by remember {
        derivedStateOf {
            val scene = navState.currentScene
            when (scene) {
                NavScene.HOME -> 0
                NavScene.SONGS,
                NavScene.FOLDERS,
                NavScene.FOLDER_HIERARCHY,
                NavScene.ALBUMS,
                NavScene.ALBUM_DETAIL,
                NavScene.ARTISTS,
                NavScene.ARTIST_DETAIL,
                NavScene.PLAYLISTS,
                NavScene.PLAYLIST_DETAIL,
                NavScene.QUEUE,
                NavScene.RECENTLY_ADDED,
                NavScene.WEBDAV,
                NavScene.SONG_STATS,
                NavScene.LOG_VIEWER,
                NavScene.ANALYTICS,
                NavScene.PLAYLIST_LIST,
                NavScene.PLAYLIST_DETAIL_PAGE,
                NavScene.DAILY_20,
                NavScene.GENRE,
                NavScene.YEAR,
                NavScene.COMPOSER,
                NavScene.GENRE_DETAIL,
                NavScene.YEAR_DETAIL,
                NavScene.COMPOSER_DETAIL -> 1
                NavScene.AUDIO_EFFECTS,
                NavScene.BASS_TREBLE_BOOST,
                NavScene.COMPRESSOR,
                NavScene.PANORAMIC_360,
                NavScene.PEQ,
                NavScene.SPATIAL_SOUND,
                NavScene.SURROUND_360 -> 2
                NavScene.SEARCH -> 3
                NavScene.SETTINGS,
                NavScene.APPEARANCE,
                NavScene.AUDIO_SETTINGS,
                NavScene.ALBUM_ART_SETTINGS,
                NavScene.GLOBAL_FONT_SETTINGS,
                NavScene.LYRIC_FONT_SETTINGS,
                NavScene.LYRIC_MANAGEMENT,
                NavScene.PLAYER_INTERFACE,
                NavScene.STATUS_BAR_LYRIC,
                NavScene.USB_DAC_SETTINGS,
                NavScene.WEBDAV_BACKUP,
                NavScene.SCAN_SETTINGS,
                NavScene.TRANSITION_SETTINGS,
                NavScene.ABOUT -> 4
            }
        }
    }
    val isSettingsScene by remember {
        derivedStateOf { navState.currentScene.isSettingsScene() }
    }

    val backdrop = rememberLayerBackdrop()
    val rawFlowModeState = rememberRawFlowModeState()
    val rawFlowSceneActive by remember {
        derivedStateOf { navState.currentScene.supportsRawFlowBackground() }
    }
    val rawFlowMotionActive by remember {
        derivedStateOf {
            navData.uiForeground &&
                rawFlowSceneActive &&
                !navState.isTransitioning &&
                !navState.isDraggingBack &&
                !navState.isAnimatingBack
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
            CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                ) {
                    if (rawFlowSceneActive) {
                        RawFlowBackground(
                            mode = rawFlowModeState.value,
                            sourceCoverKey = navData.currentSong?.coverKey,
                            modifier = Modifier.fillMaxSize(),
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
                        externalPageRenderer = externalPageRenderer
                    )
                }
            }
        }

        // 设置页由独立 Activity 承载；若旧路径误把主导航切到设置场景，也不显示底部栏。
        if (!isSettingsScene) {
            val showBottomChrome = !navData.bottomChromeHidden
            val chromeHidden = bottomChromeScrollState.hidden
            val miniPlayerOffsetY by animateDpAsState(
                targetValue = if (chromeHidden) (-12).dp else (-76).dp,
                animationSpec = tween(durationMillis = 240),
                label = "mini-player-chrome-offset"
            )
            val bottomTabsOffsetY by animateDpAsState(
                targetValue = if (chromeHidden) 84.dp else (-12).dp,
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
                val miniCoverPath = navData.currentSong?.path?.takeIf { it.isLocalArtworkSource() }
                    ?: navData.currentSong?.albumArtPath?.takeIf { it.isNotBlank() }
                    ?: navData.miniPlayerCoverPath?.takeIf { it.isNotBlank() }

                ComposeMiniPlayer(
                    title = navData.miniPlayerTitle,
                    artist = navData.miniPlayerArtist,
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
                        .padding(horizontal = 36.dp)
                        .offset(y = miniPlayerOffsetY)
                        .rawNavigationBarsPadding(reduceBy = 12.dp)
                )
            }

            AnimatedVisibility(
                visible = showBottomChrome,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LiquidBottomTabs(
                    selectedTabIndex = { selectedTabIndex },
                    onTabSelected = { index ->
                        val targetScene = tabScenes[index]
                        if (targetScene == NavScene.SETTINGS && onSettingsClick != null) {
                            onSettingsClick()
                        } else if (navState.currentScene != targetScene) {
                            if (targetScene == NavScene.SETTINGS) {
                                navState.navigateToSettings()
                            } else {
                                navState.navigateTo(targetScene)
                            }
                        }
                    },
                    backdrop = backdrop,
                    tabsCount = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp)
                        .offset(y = bottomTabsOffsetY)
                        .rawNavigationBarsPadding(reduceBy = 12.dp)
                ) {
                // Tab 0: 主界面
                LiquidBottomTab({ navState.navigateTo(NavScene.HOME) }) {
                    Image(
                        painter = rememberVectorPainter(Icons.Default.Home),
                        contentDescription = "主页",
                        modifier = Modifier.size(24.dp),
                        colorFilter = iconColorFilter
                    )
                    BasicText(
                        "主界面",
                        style = TextStyle(contentColor, 10.sp, FontWeight.Medium)
                    )
                }

                // Tab 1: 音乐库
                LiquidBottomTab({ navState.navigateTo(NavScene.SONGS) }) {
                    Image(
                        painter = rememberVectorPainter(MiuixIcons.Regular.Music),
                        contentDescription = "音乐库",
                        modifier = Modifier.size(24.dp),
                        colorFilter = iconColorFilter
                    )
                    BasicText(
                        "音乐库",
                        style = TextStyle(contentColor, 10.sp, FontWeight.Medium)
                    )
                }

                // Tab 2: 音效
                LiquidBottomTab({ navState.navigateTo(NavScene.AUDIO_EFFECTS) }) {
                    Image(
                        painter = painterResource(R.drawable.ic_equalizer_bars),
                        contentDescription = "音效",
                        modifier = Modifier.size(24.dp),
                        colorFilter = iconColorFilter
                    )
                    BasicText(
                        "音效",
                        style = TextStyle(contentColor, 10.sp, FontWeight.Medium)
                    )
                }

                // Tab 3: 搜索
                LiquidBottomTab({ navState.navigateTo(NavScene.SEARCH) }) {
                    Image(
                        painter = rememberVectorPainter(MiuixIcons.Regular.Search),
                        contentDescription = "搜索",
                        modifier = Modifier.size(24.dp),
                        colorFilter = iconColorFilter
                    )
                    BasicText(
                        "搜索",
                        style = TextStyle(contentColor, 10.sp, FontWeight.Medium)
                    )
                }

                // Tab 4: 设置
                LiquidBottomTab({ onSettingsClick?.invoke() ?: navState.navigateToSettings() }) {
                    Image(
                        painter = rememberVectorPainter(MiuixIcons.Regular.Settings),
                        contentDescription = "设置",
                        modifier = Modifier.size(24.dp),
                        colorFilter = iconColorFilter
                    )
                    BasicText(
                        "设置",
                        style = TextStyle(contentColor, 10.sp, FontWeight.Medium)
                    )
                }
                }
            }
        }
    }
    }
}

private const val MAIN_RAW_FLOW_FRAME_INTERVAL_MS = 250L

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

private fun String.isLocalArtworkSource(): Boolean {
    return isNotBlank() &&
        !startsWith("http://", ignoreCase = true) &&
        !startsWith("https://", ignoreCase = true)
}
