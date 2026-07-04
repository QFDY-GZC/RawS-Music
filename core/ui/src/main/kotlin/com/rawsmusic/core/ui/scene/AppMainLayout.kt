package com.rawsmusic.core.ui.scene

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTab
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTabs
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
                NavScene.ABOUT -> 4
            }
        }
    }
    val isSettingsScene by remember {
        derivedStateOf { navState.currentScene.isSettingsScene() }
    }

    val backdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 主内容（导航），应用 layerBackdrop 以捕获背景供液态玻璃使用
        CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
            ComposeNavHost(
                state = navState,
                callbacks = navCallbacks,
                data = navData,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
                externalPageRenderer = externalPageRenderer
            )
        }

        // 设置页由独立 Activity 承载；若旧路径误把主导航切到设置场景，也不显示底部栏。
        if (!isSettingsScene) {
            val showBottomChrome = !navData.bottomChromeHidden
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
                    backdrop = backdrop,
                    onPlayPause = navCallbacks.onMiniPlayerPlayPause,
                    onSkipPrevious = navCallbacks.onMiniPlayerPrevious,
                    onSkipNext = navCallbacks.onMiniPlayerNext,
                    onClick = navCallbacks.onNavigateToPlayer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp)
                        .offset(y = (-76).dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
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
                        .offset(y = (-12).dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
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
