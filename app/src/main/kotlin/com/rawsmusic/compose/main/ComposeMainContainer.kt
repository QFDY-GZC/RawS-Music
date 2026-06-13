package com.rawsmusic.compose.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 导航场景枚举
 * 对应原版 NavScene
 */
enum class NavScene(val label: String) {
    HOME("首页"),
    SONGS("歌曲"),
    FOLDERS("文件夹"),
    ALBUMS("专辑"),
    ARTISTS("艺术家"),
    PLAYLISTS("播放列表"),
    QUEUE("队列"),
    RECENTLY_ADDED("最近添加"),
    ALBUM_DETAIL("专辑详情"),
    ARTIST_DETAIL("艺术家详情"),
    PLAYLIST_DETAIL("播放列表详情"),
    FOLDER_HIERARCHY("文件夹层级"),
    WEBDAV("WebDAV")
}

/**
 * 纯 Compose 版本的主容器
 * 替代原版 UnifiedMainContainer (~3143行)
 *
 * 管理内容页面：HOME / SONGS / ALBUMS / ARTISTS / ...
 * 支持页面导航、拖拽返回、歌曲列表集成
 */
@Composable
fun ComposeMainContainer(
    onNavigateToPlayer: () -> Unit = {},
    onSongClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 导航状态
    var currentScene by remember { mutableStateOf(NavScene.HOME) }
    val backStack = remember { mutableListOf(NavScene.HOME) }

    // 导航函数
    fun navigateTo(scene: NavScene) {
        if (scene != currentScene) {
            backStack.add(scene)
            currentScene = scene
        }
    }

    fun navigateBack(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
            currentScene = backStack.last()
            return true
        }
        return false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
    ) {
        AnimatedContent(
            targetState = currentScene,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                        slideOutHorizontally { -it } + fadeOut()
            }
        ) { scene ->
            when (scene) {
                NavScene.HOME -> {
                    HomePage(
                        onNavigate = { navigateTo(it) }
                    )
                }
                NavScene.SONGS -> {
                    SongsPage(
                        onSongClick = onSongClick,
                        onNavigateToPlayer = onNavigateToPlayer
                    )
                }
                NavScene.ALBUMS -> {
                    AlbumsPage()
                }
                NavScene.ARTISTS -> {
                    ArtistsPage()
                }
                NavScene.FOLDERS -> {
                    FoldersPage()
                }
                NavScene.PLAYLISTS -> {
                    PlaylistsPage()
                }
                NavScene.QUEUE -> {
                    QueuePage()
                }
                NavScene.RECENTLY_ADDED -> {
                    RecentlyAddedPage()
                }
                else -> {
                    // 详情页面
                    DetailPage(scene.label)
                }
            }
        }
    }
}

/**
 * 首页
 */
@Composable
private fun HomePage(
    onNavigate: (NavScene) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RawSMusic",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 导航磁贴
        NavScene.entries
            .filter { it != NavScene.HOME && !it.name.contains("DETAIL") && !it.name.contains("HIERARCHY") }
            .forEach { scene ->
                NavTile(
                    scene = scene,
                    onClick = { onNavigate(scene) }
                )
            }
    }
}

/**
 * 导航磁贴
 */
@Composable
private fun NavTile(
    scene: NavScene,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .clickable { onClick() }
            .padding(20.dp)
    ) {
        Text(
            text = scene.label,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 歌曲页面
 */
@Composable
private fun SongsPage(
    onSongClick: (Int) -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "歌曲列表\n(集成 ComposePowerList)",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 专辑页面
 */
@Composable
private fun AlbumsPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "专辑页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 艺术家页面
 */
@Composable
private fun ArtistsPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "艺术家页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 文件夹页面
 */
@Composable
private fun FoldersPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "文件夹页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 播放列表页面
 */
@Composable
private fun PlaylistsPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "播放列表页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 队列页面
 */
@Composable
private fun QueuePage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "队列页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 最近添加页面
 */
@Composable
private fun RecentlyAddedPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "最近添加页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}

/**
 * 详情页面
 */
@Composable
private fun DetailPage(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$title 页面",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp
        )
    }
}
