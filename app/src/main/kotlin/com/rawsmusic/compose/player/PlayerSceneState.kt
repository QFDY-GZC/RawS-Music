package com.rawsmusic.compose.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放器场景状态管理
 * 替代原版 UnifiedPlayerContainer 的场景状态
 *
 * 管理6个场景：MAIN / PLAYER / LYRIC / QUEUE / ALBUM_DETAIL / EFFECTS
 * 使用 ratio-driven 动画驱动场景切换
 */
@Stable
class PlayerSceneState(
    private val scope: CoroutineScope
) {
    // ==================== 场景状态 ====================

    /** 当前场景 */
    var currentScene by mutableStateOf(PlayerScene.MAIN)
        private set

    /** 目标场景 */
    var targetScene by mutableStateOf(PlayerScene.MAIN)
        private set

    /** 源场景 */
    var sourceScene by mutableStateOf(PlayerScene.MAIN)
        private set

    /** 是否正在过渡 */
    var isTransitioning by mutableStateOf(false)
        private set

    /** 过渡进度 (0..1) */
    var transitionProgress by mutableFloatStateOf(1f)
        private set

    /** 过渡方向 (true=正向, false=反向) */
    var transitionForward by mutableStateOf(true)
        private set

    // ==================== 动画控制器 ====================

    /** 场景动画控制器 */
    private val sceneAnimatable = Animatable(0f)

    // ==================== 回调 ====================

    /** 场景变化回调 */
    var onSceneChanged: ((PlayerScene) -> Unit)? = null

    /** 过渡进度回调 */
    var onTransitionProgress: ((Float) -> Unit)? = null

    // ==================== 方法 ====================

    /**
     * 过渡到目标场景
     * 对应原版 UnifiedPlayerContainer.transitionToScene()
     *
     * @param scene 目标场景
     * @param duration 动画时长 (ms)
     */
    fun transitionToScene(scene: PlayerScene, duration: Long = 350) {
        if (scene == currentScene || isTransitioning) return

        sourceScene = currentScene
        targetScene = scene
        transitionForward = scene.ordinal > currentScene.ordinal
        isTransitioning = true

        scope.launch {
            sceneAnimatable.snapTo(0f)
            sceneAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                transitionProgress = value
                onTransitionProgress?.invoke(value)
            }

            // 动画完成
            currentScene = scene
            isTransitioning = false
            transitionProgress = 1f
            onSceneChanged?.invoke(scene)
        }
    }

    /**
     * 从当前进度继续过渡
     * 对应原版 UnifiedPlayerContainer.transitionFromCurrentRatio()
     */
    fun transitionFromCurrentRatio(scene: PlayerScene, duration: Long = 350) {
        if (scene == currentScene || !isTransitioning) return

        targetScene = scene
        transitionForward = scene.ordinal > sourceScene.ordinal

        scope.launch {
            val start = transitionProgress
            sceneAnimatable.snapTo(start)
            sceneAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ((1f - start) * duration).toInt().coerceAtLeast(50),
                    easing = LinearEasing
                )
            ) {
                transitionProgress = value
                onTransitionProgress?.invoke(value)
            }

            currentScene = scene
            isTransitioning = false
            transitionProgress = 1f
            onSceneChanged?.invoke(scene)
        }
    }

    /**
     * 静默切换场景（无动画）
     * 对应原版 UnifiedPlayerContainer.switchToSceneSilent()
     */
    fun switchToSceneSilent(scene: PlayerScene) {
        if (scene == currentScene) return

        currentScene = scene
        sourceScene = scene
        targetScene = scene
        transitionProgress = 1f
        isTransitioning = false
        onSceneChanged?.invoke(scene)
    }

    /**
     * 更新过渡进度（用于手势驱动）
     */
    fun updateProgress(progress: Float) {
        transitionProgress = progress.coerceIn(0f, 1f)
        onTransitionProgress?.invoke(transitionProgress)
    }

    /**
     * 获取当前场景的 alpha
     * 用于 graphicsLayer 控制可见性
     */
    fun getSceneAlpha(scene: PlayerScene): Float {
        if (!isTransitioning) {
            return if (scene == currentScene) 1f else 0f
        }

        return when {
            scene == sourceScene -> 1f - transitionProgress
            scene == targetScene -> transitionProgress
            else -> 0f
        }
    }

    /**
     * 获取当前场景的缩放
     * 用于 graphicsLayer 控制缩放动画
     */
    fun getSceneScale(scene: PlayerScene): Float {
        if (!isTransitioning) return 1f

        return when (scene) {
            sourceScene -> {
                if (transitionForward) 1f - transitionProgress * 0.5f
                else 1f + transitionProgress * 0.5f
            }
            targetScene -> {
                if (transitionForward) 0.5f + transitionProgress * 0.5f
                else 1.5f - transitionProgress * 0.5f
            }
            else -> 1f
        }
    }

    /**
     * 取消当前过渡
     */
    fun cancelTransition() {
        scope.launch {
            sceneAnimatable.stop()
            if (isTransitioning) {
                // 回退到源场景
                currentScene = sourceScene
                targetScene = sourceScene
                transitionProgress = 1f
                isTransitioning = false
            }
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        scope.launch {
            sceneAnimatable.stop()
        }
        currentScene = PlayerScene.MAIN
        sourceScene = PlayerScene.MAIN
        targetScene = PlayerScene.MAIN
        transitionProgress = 1f
        isTransitioning = false
        transitionForward = true
    }
}

/**
 * 播放器场景枚举
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
 * 记住 PlayerSceneState
 */
@Composable
fun rememberPlayerSceneState(): PlayerSceneState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerSceneState(scope) }
}
