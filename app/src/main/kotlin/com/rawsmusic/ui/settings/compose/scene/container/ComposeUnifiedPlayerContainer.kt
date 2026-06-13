package com.rawsmusic.ui.settings.compose.scene.container

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.rawsmusic.ui.settings.compose.scene.demo.ComposeUnifiedMainContainer
import com.rawsmusic.ui.settings.compose.scene.state.SceneParams
import com.rawsmusic.ui.settings.compose.scene.state.SceneParamsInterpolator
import com.rawsmusic.ui.settings.compose.scene.state.SceneParamsRegistry
import com.rawsmusic.ui.settings.compose.scene.state.rememberSceneParamsRegistry
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    // SceneParams 注册表
    val sceneParamsRegistry = rememberSceneParamsRegistry()

    // 当前场景
    var currentScene by remember { mutableStateOf(PlayerScene.MAIN) }

    // 过渡状态
    var isTransitioning by remember { mutableStateOf(false) }
    val transitionProgress = remember { Animatable(0f) }
    var targetScene by remember { mutableStateOf(PlayerScene.MAIN) }
    var sourceScene by remember { mutableStateOf(PlayerScene.MAIN) }

    // 场景切换函数
    fun transitionToScene(scene: PlayerScene) {
        if (scene == currentScene || isTransitioning) return

        sourceScene = currentScene
        targetScene = scene
        isTransitioning = true

        scope.launch {
            // 动画驱动过渡
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300)
            ) {
                // 每帧更新
            }

            // 动画完成
            currentScene = scene
            isTransitioning = false
            transitionProgress.snapTo(1f)
        }
    }

    // 注册默认场景参数
    remember {
        // 封面：MAIN 场景缩小透明，PLAYER 场景正常
        sceneParamsRegistry.registerAll("cover", mapOf(
            PlayerScene.MAIN to SceneParams.SCALED_DOWN,
            PlayerScene.PLAYER to SceneParams.DEFAULT,
            PlayerScene.LYRIC to SceneParams.SCALED_DOWN
        ))

        // 歌词：MAIN 场景不可见，LYRIC 场景正常
        sceneParamsRegistry.registerAll("lyrics", mapOf(
            PlayerScene.MAIN to SceneParams.INVISIBLE,
            PlayerScene.PLAYER to SceneParams.INVISIBLE,
            PlayerScene.LYRIC to SceneParams.DEFAULT
        ))

        // 队列：MAIN 场景不可见，QUEUE 场景正常
        sceneParamsRegistry.registerAll("queue", mapOf(
            PlayerScene.MAIN to SceneParams.INVISIBLE,
            PlayerScene.PLAYER to SceneParams.INVISIBLE,
            PlayerScene.QUEUE to SceneParams.DEFAULT
        ))

        true
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
                        onBack = { transitionToScene(PlayerScene.MAIN) },
                        registry = sceneParamsRegistry,
                        currentScene = currentScene,
                        transitionProgress = transitionProgress.value
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
 * 获取插值后的场景参数
 *
 * @param registry 场景参数注册表
 * @param viewId View ID
 * @param currentScene 当前场景
 * @param targetScene 目标场景
 * @param progress 过渡进度 (0..1)
 * @return 插值后的场景参数
 */
fun getInterpolatedSceneParams(
    registry: SceneParamsRegistry,
    viewId: String,
    currentScene: PlayerScene,
    targetScene: PlayerScene,
    progress: Float
): SceneParams {
    val fromParams = registry.get(viewId, currentScene)
    val toParams = registry.get(viewId, targetScene)
    return SceneParamsInterpolator.lerp(fromParams, toParams, progress)
}

/**
 * 播放器界面
 */
@Composable
private fun PlayerScreen(
    onBack: () -> Unit,
    registry: SceneParamsRegistry,
    currentScene: PlayerScene,
    transitionProgress: Float,
    modifier: Modifier = Modifier
) {
    // 获取封面的场景参数
    val coverParams = getInterpolatedSceneParams(
        registry = registry,
        viewId = "cover",
        currentScene = currentScene,
        targetScene = PlayerScene.PLAYER,
        progress = transitionProgress
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        // 播放器内容
        // 封面、控制按钮等
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
