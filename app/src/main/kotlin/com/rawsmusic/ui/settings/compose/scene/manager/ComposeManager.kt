package com.rawsmusic.ui.settings.compose.scene.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.ui.settings.compose.scene.state.DualEngineState
import com.rawsmusic.ui.settings.compose.scene.state.SceneParamsRegistry

/**
 * Compose 管理器
 * 对应原版的管理系统
 *
 * 管理所有组件的生命周期和状态
 */
@Stable
class ComposeManager {
    // ==================== 状态 ====================

    /** 列表状态 */
    val listState = ComposeListState()

    /** 双引擎状态 */
    val dualEngineState = DualEngineState()

    /** 场景参数注册表 */
    val sceneParamsRegistry = SceneParamsRegistry()

    // ==================== 组件 ====================

    /** 场景管理器 */
    val sceneManager = SceneManager(listState, dualEngineState, sceneParamsRegistry)

    // ==================== 状态 ====================

    /** 是否已初始化 */
    var isInitialized by mutableStateOf(false)
        private set

    /** 是否正在加载 */
    var isLoading by mutableStateOf(false)
        private set

    /** 错误信息 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // ==================== 方法 ====================

    /**
     * 初始化
     */
    fun initialize() {
        if (isInitialized) return

        isLoading = true
        errorMessage = null

        try {
            // 初始化组件
            listState.resetGestureState()
            dualEngineState.reset()
            sceneParamsRegistry.clear()

            isInitialized = true
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    /**
     * 重置
     */
    fun reset() {
        listState.resetGestureState()
        dualEngineState.reset()
        sceneParamsRegistry.clear()
        sceneManager.reset()

        isInitialized = false
        isLoading = false
        errorMessage = null
    }

    /**
     * 清除错误
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * 注册场景参数
     *
     * @param viewId View ID
     * @param scene 场景
     * @param params 场景参数
     */
    fun registerSceneParams(
        viewId: String,
        scene: com.rawsmusic.ui.settings.compose.scene.container.PlayerScene,
        params: com.rawsmusic.ui.settings.compose.scene.state.SceneParams
    ) {
        sceneParamsRegistry.register(viewId, scene, params)
    }

    /**
     * 批量注册场景参数
     *
     * @param viewId View ID
     * @param params 场景参数映射
     */
    fun registerSceneParamsAll(
        viewId: String,
        params: Map<com.rawsmusic.ui.settings.compose.scene.container.PlayerScene, com.rawsmusic.ui.settings.compose.scene.state.SceneParams>
    ) {
        sceneParamsRegistry.registerAll(viewId, params)
    }

    /**
     * 获取场景参数
     *
     * @param viewId View ID
     * @param scene 场景
     * @return 场景参数
     */
    fun getSceneParams(
        viewId: String,
        scene: com.rawsmusic.ui.settings.compose.scene.container.PlayerScene
    ): com.rawsmusic.ui.settings.compose.scene.state.SceneParams {
        return sceneParamsRegistry.get(viewId, scene)
    }

    /**
     * 切换场景
     *
     * @param scene 目标场景
     */
    fun switchScene(scene: com.rawsmusic.ui.settings.compose.scene.container.PlayerScene) {
        sceneManager.transitionToScene(scene)
    }

    /**
     * 返回上一个场景
     */
    fun goBack() {
        sceneManager.goBack()
    }

    /**
     * 检查是否可以返回
     *
     * @return 是否可以返回
     */
    fun canGoBack(): Boolean {
        return sceneManager.canGoBack()
    }
}

/**
 * 记住 ComposeManager
 */
@Composable
fun rememberComposeManager(): ComposeManager {
    return remember { ComposeManager().also { it.initialize() } }
}
