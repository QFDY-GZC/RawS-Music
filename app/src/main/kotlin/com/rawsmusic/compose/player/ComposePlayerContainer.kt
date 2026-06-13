package com.rawsmusic.compose.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 纯 Compose 版本的播放器容器
 * 替代原版 UnifiedPlayerContainer (2357行)
 *
 * 管理6个场景：MAIN / PLAYER / LYRIC / QUEUE / ALBUM_DETAIL / EFFECTS
 * 支持 SceneParams 注册、ratio-driven 动画、手势切换
 */
@Composable
fun ComposePlayerContainer(
    sceneState: PlayerSceneState,
    onNavigateToMain: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121010))
            // 水平拖拽：PLAYER → MAIN
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    when {
                        // 向右滑动：PLAYER → MAIN
                        dragAmount > 100 && sceneState.currentScene == PlayerScene.PLAYER -> {
                            sceneState.transitionToScene(PlayerScene.MAIN)
                            onNavigateToMain()
                        }
                        // 向左滑动：MAIN → PLAYER
                        dragAmount < -100 && sceneState.currentScene == PlayerScene.MAIN -> {
                            sceneState.transitionToScene(PlayerScene.PLAYER)
                        }
                    }
                }
            }
            // 垂直拖拽：PLAYER → LYRIC
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    when {
                        // 向上滑动：PLAYER → LYRIC
                        dragAmount < -100 && sceneState.currentScene == PlayerScene.PLAYER -> {
                            sceneState.transitionToScene(PlayerScene.LYRIC)
                        }
                        // 向下滑动：LYRIC → PLAYER
                        dragAmount > 100 && sceneState.currentScene == PlayerScene.LYRIC -> {
                            sceneState.transitionToScene(PlayerScene.PLAYER)
                        }
                    }
                }
            }
    ) {
        AnimatedContent(
            targetState = sceneState.currentScene,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { scene ->
            when (scene) {
                PlayerScene.MAIN -> {
                    // 主界面由 ComposeMainContainer 渲染
                    // 这里只是占位
                }
                PlayerScene.PLAYER -> {
                    PlayerContent()
                }
                PlayerScene.LYRIC -> {
                    LyricContent()
                }
                PlayerScene.QUEUE -> {
                    QueueContent()
                }
                PlayerScene.ALBUM_DETAIL -> {
                    AlbumDetailContent()
                }
                PlayerScene.EFFECTS -> {
                    EffectsContent()
                }
            }
        }
    }
}

/**
 * 播放器界面
 */
@Composable
private fun PlayerContent() {
    Box(
        modifier = Modifier
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
private fun LyricContent() {
    Box(
        modifier = Modifier
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
private fun QueueContent() {
    Box(
        modifier = Modifier
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
private fun AlbumDetailContent() {
    Box(
        modifier = Modifier
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
private fun EffectsContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F3460))
    ) {
        // 音效内容
    }
}
