package com.rawsmusic.ui.settings.compose.scene.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.ui.settings.compose.scene.container.PlayerScene
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.ui.settings.compose.scene.state.DualEngineState
import com.rawsmusic.ui.settings.compose.scene.state.SceneParamsRegistry

/**
 * 场景管理器
 * 对应原版 UnifiedPlayerContainer 的场景管理
 *
 * 管理场景切换、参数注册、动画协调
 */
@Stable
class SceneManager(
    val listState: ComposeListState,
    val dualEngineState: DualEngineState,
    val sceneParamsRegistry: SceneParamsRegistry
) {
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
    var transitionProgress by mutableStateOf(0f)
        private set

    /** 场景切换回调 */
    var onSceneChanged: ((PlayerScene) -> Unit)? = null

    /** 过渡开始回调 */
    var onTransitionStart: ((PlayerScene, PlayerScene) -> Unit)? = null

    /** 过渡完成回调 */
    var onTransitionComplete: ((PlayerScene) -> Unit)? = null

    /**
     * 切换场景
     * 对应原版 UnifiedPlayerContainer.transitionToScene()
     *
     * @param scene 目标场景
     * @param animate 是否使用动画
     */
    fun transitionToScene(scene: PlayerScene, animate: Boolean = true) {
        if (scene == currentScene || isTransitioning) return

        sourceScene = currentScene
        targetScene = scene
        isTransitioning = true
        transitionProgress = 0f

        onTransitionStart?.invoke(sourceScene, targetScene)

        if (animate) {
            // 动画切换
            // 实际应该使用 Animatable
            currentScene = scene
            isTransitioning = false
            transitionProgress = 1f
            onTransitionComplete?.invoke(scene)
            onSceneChanged?.invoke(scene)
        } else {
            // 立即切换
            currentScene = scene
            isTransitioning = false
            transitionProgress = 1f
            onTransitionComplete?.invoke(scene)
            onSceneChanged?.invoke(scene)
        }
    }

    /**
     * 更新过渡进度
     *
     * @param progress 进度 (0..1)
     */
    fun updateTransitionProgress(progress: Float) {
        transitionProgress = progress.coerceIn(0f, 1f)
    }

    /**
     * 获取当前场景的参数
     *
     * @param viewId View ID
     * @return 场景参数
     */
    fun getSceneParams(viewId: String) = sceneParamsRegistry.get(viewId, currentScene)

    /**
     * 获取插值后的场景参数
     *
     * @param viewId View ID
     * @return 插值后的场景参数
     */
    fun getInterpolatedSceneParams(viewId: String) = sceneParamsRegistry.get(viewId, targetScene)

    /**
     * 注册场景参数
     *
     * @param viewId View ID
     * @param scene 场景
     * @param params 场景参数
     */
    fun registerSceneParams(viewId: String, scene: PlayerScene, params: com.rawsmusic.ui.settings.compose.scene.state.SceneParams) {
        sceneParamsRegistry.register(viewId, scene, params)
    }

    /**
     * 批量注册场景参数
     *
     * @param viewId View ID
     * @param params 场景参数映射
     */
    fun registerSceneParamsAll(viewId: String, params: Map<PlayerScene, com.rawsmusic.ui.settings.compose.scene.state.SceneParams>) {
        sceneParamsRegistry.registerAll(viewId, params)
    }

    /**
     * 检查是否可以返回
     *
     * @return 是否可以返回
     */
    fun canGoBack(): Boolean {
        return currentScene != PlayerScene.MAIN
    }

    /**
     * 返回上一个场景
     */
    fun goBack() {
        when (currentScene) {
            PlayerScene.PLAYER -> transitionToScene(PlayerScene.MAIN)
            PlayerScene.LYRIC -> transitionToScene(PlayerScene.PLAYER)
            PlayerScene.QUEUE -> transitionToScene(PlayerScene.PLAYER)
            PlayerScene.ALBUM_DETAIL -> transitionToScene(PlayerScene.MAIN)
            PlayerScene.EFFECTS -> transitionToScene(PlayerScene.PLAYER)
            else -> {}
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        currentScene = PlayerScene.MAIN
        targetScene = PlayerScene.MAIN
        sourceScene = PlayerScene.MAIN
        isTransitioning = false
        transitionProgress = 0f
        listState.resetGestureState()
        dualEngineState.reset()
    }
}

/**
 * 记住 SceneManager
 */
@Composable
fun rememberSceneManager(): SceneManager {
    val listState = com.rawsmusic.ui.settings.compose.scene.state.ComposeListState()
    val dualEngineState = com.rawsmusic.ui.settings.compose.scene.state.DualEngineState()
    val sceneParamsRegistry = com.rawsmusic.ui.settings.compose.scene.state.rememberSceneParamsRegistry()

    return remember(listState, dualEngineState, sceneParamsRegistry) {
        SceneManager(listState, dualEngineState, sceneParamsRegistry)
    }
}
