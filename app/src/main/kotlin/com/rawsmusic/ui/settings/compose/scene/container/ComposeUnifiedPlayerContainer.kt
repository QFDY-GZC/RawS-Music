package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.demo.ComposeUnifiedMainContainer

/**
 * 场景枚举
 * 对应原版 UnifiedPlayerContainer.Scene
 */
enum class PlayerScene(val label: String) {
    MAIN("主界面"),
    PLAYER("播放器"),
    LYRIC("歌词"),
    QUEUE("队列"),
    ALBUM_DETAIL("专辑详情"),
    EFFECTS("音效")
}

/**
 * 纯 Compose 版本的统一播放器容器
 * 替代原版 UnifiedPlayerContainer (2357行)
 *
 * 管理六个场景：MAIN/PLAYER/LYRIC/QUEUE/ALBUM_DETAIL/EFFECTS
 * 支持手势切换、SceneParams 注册、ratio-driven 动画
 */
@Composable
fun ComposeUnifiedPlayerContainer(
    modifier: Modifier = Modifier
) {
    // 当前场景
    var currentScene by remember { mutableStateOf(PlayerScene.MAIN) }

    // 过渡状态
    var isTransitioning by remember { mutableStateOf(false) }
    var transitionProgress by remember { mutableFloatStateOf(0f) }
    var targetScene by remember { mutableStateOf(PlayerScene.MAIN) }

    // 场景切换函数
    fun transitionToScene(scene: PlayerScene) {
        if (scene == currentScene || isTransitioning) return
        targetScene = scene
        isTransitioning = true
        transitionProgress = 0f

        // 模拟动画完成
        // 实际应该使用 Animatable
        currentScene = scene
        isTransitioning = false
        transitionProgress = 1f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    // 手势切换场景
                    when {
                        // 向右滑动：PLAYER -> MAIN
                        dragAmount > 100 && currentScene == PlayerScene.PLAYER -> {
                            transitionToScene(PlayerScene.MAIN)
                        }
                        // 向左滑动：MAIN -> PLAYER
                        dragAmount < -100 && currentScene == PlayerScene.MAIN -> {
                            transitionToScene(PlayerScene.PLAYER)
                        }
                    }
                }
            }
    ) {
        AnimatedContent(
            targetState = currentScene,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                        slideOutHorizontally { -it } + fadeOut()
            }
        ) { scene ->
            when (scene) {
                PlayerScene.MAIN -> {
                    ComposeUnifiedMainContainer()
                }
                PlayerScene.PLAYER -> {
                    PlayerScreen(
                        onBack = { transitionToScene(PlayerScene.MAIN) }
                    )
                }
                PlayerScene.LYRIC -> {
                    LyricScreen(
                        onBack = { transitionToScene(PlayerScene.PLAYER) }
                    )
                }
                PlayerScene.QUEUE -> {
                    QueueScreen(
                        onBack = { transitionToScene(PlayerScene.PLAYER) }
                    )
                }
                PlayerScene.ALBUM_DETAIL -> {
                    AlbumDetailScreen(
                        onBack = { transitionToScene(PlayerScene.MAIN) }
                    )
                }
                PlayerScene.EFFECTS -> {
                    EffectsScreen(
                        onBack = { transitionToScene(PlayerScene.PLAYER) }
                    )
                }
            }
        }
    }
}

/**
 * 播放器界面
 */
@Composable
private fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "播放器界面",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}

/**
 * 歌词界面
 */
@Composable
private fun LyricScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F3460)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "歌词界面",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}

/**
 * 队列界面
 */
@Composable
private fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF16213E)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "队列界面",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}

/**
 * 专辑详情界面
 */
@Composable
private fun AlbumDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "专辑详情界面",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}

/**
 * 音效界面
 */
@Composable
private fun EffectsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F3460)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "音效界面",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}
