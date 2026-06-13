package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.rawsmusic.ui.settings.compose.scene.demo.ComposeUnifiedMainContainer
import com.rawsmusic.ui.settings.compose.scene.manager.SceneManager
import com.rawsmusic.ui.settings.compose.scene.manager.rememberSceneManager

/**
 * 纯 Compose 版本的容器
 * 替代原版 UnifiedPlayerContainer
 *
 * 管理场景切换、手势处理、动画效果
 */
@Composable
fun ComposeContainer(
    modifier: Modifier = Modifier
) {
    val sceneManager = rememberSceneManager()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    // 手势切换场景
                    when {
                        // 向右滑动：PLAYER -> MAIN
                        dragAmount > 100 && sceneManager.currentScene == PlayerScene.PLAYER -> {
                            sceneManager.transitionToScene(PlayerScene.MAIN)
                        }
                        // 向左滑动：MAIN -> PLAYER
                        dragAmount < -100 && sceneManager.currentScene == PlayerScene.MAIN -> {
                            sceneManager.transitionToScene(PlayerScene.PLAYER)
                        }
                    }
                }
            }
    ) {
        AnimatedContent(
            targetState = sceneManager.currentScene,
            transitionSpec = {
                slideInHorizontally(
                    animationSpec = tween(300)
                ) { it } + fadeIn(
                    animationSpec = tween(300)
                ) togetherWith slideOutHorizontally(
                    animationSpec = tween(300)
                ) { -it } + fadeOut(
                    animationSpec = tween(300)
                )
            }
        ) { scene ->
            when (scene) {
                PlayerScene.MAIN -> {
                    ComposeUnifiedMainContainer()
                }
                PlayerScene.PLAYER -> {
                    PlayerScreen(
                        onBack = { sceneManager.goBack() }
                    )
                }
                PlayerScene.LYRIC -> {
                    LyricScreen(
                        onBack = { sceneManager.goBack() }
                    )
                }
                PlayerScene.QUEUE -> {
                    QueueScreen(
                        onBack = { sceneManager.goBack() }
                    )
                }
                PlayerScene.ALBUM_DETAIL -> {
                    AlbumDetailScreen(
                        onBack = { sceneManager.goBack() }
                    )
                }
                PlayerScene.EFFECTS -> {
                    EffectsScreen(
                        onBack = { sceneManager.goBack() }
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
            .background(Color(0xFF1A1A2E))
    ) {
        // 播放器内容
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
            .background(Color(0xFF0F3460))
    ) {
        // 歌词内容
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
            .background(Color(0xFF16213E))
    ) {
        // 队列内容
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
            .background(Color(0xFF1A1A2E))
    ) {
        // 专辑详情内容
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
            .background(Color(0xFF0F3460))
    ) {
        // 音效内容
    }
}
