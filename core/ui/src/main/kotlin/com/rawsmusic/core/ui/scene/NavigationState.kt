package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 纯 Compose 导航状态管理。
 *
 * 持有当前场景、返回栈、过渡动画状态、手势拖拽状态。
 * 替代旧主界面壳中散布的 composeCurrentScene / composeBackStack /
 * composeIsTransitioning / composeTransitionProgress 等字段。
 */
@Stable
class NavigationState {

    private companion object {
        val settingsScenes = setOf(
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
            NavScene.ABOUT,
        )
    }

    // ==================== 当前场景 ====================

    var currentScene by mutableStateOf(NavScene.HOME)
        private set

    /** 当前场景的导航参数 */
    var currentArgument by mutableStateOf("")
        internal set

    // ==================== 返回栈 ====================

    private val _backStack = mutableStateListOf(NavScene.HOME)
    val backStack: List<NavScene> get() = _backStack

    // ==================== 过渡动画状态 ====================

    var isTransitioning by mutableStateOf(false)
        internal set

    var transitionProgress by mutableFloatStateOf(0f)
        internal set

    /** 过渡动画的源场景 */
    var transitionFromScene by mutableStateOf(NavScene.HOME)
        internal set

    /** 过渡动画的目标场景 */
    var transitionToScene by mutableStateOf(NavScene.HOME)
        internal set

    // ==================== 手势拖拽返回状态 ====================

    var isDraggingBack by mutableStateOf(false)
        internal set

    var dragBackProgress by mutableFloatStateOf(0f)
        internal set

    var dragBackReleaseToken by mutableIntStateOf(0)
        internal set

    var dragBackReleaseCommit by mutableStateOf(false)
        internal set

    var dragBackReleaseProgress by mutableFloatStateOf(0f)
        internal set

    var dragBackReleaseVelocity by mutableFloatStateOf(0f)
        internal set

    /** 返回手势方向：1 表示页面向右离场，-1 表示页面向左离场。 */
    var dragBackDirection by mutableFloatStateOf(1f)
        private set

    // ==================== 程序化返回动画状态 ====================

    /** 是否正在播放程序化返回动画（非手势驱动） */
    var isAnimatingBack by mutableStateOf(false)
        internal set

    /** 程序化返回动画进度 0→1（从当前页面位置到完全离开） */
    var animatingBackProgress by mutableFloatStateOf(0f)
        internal set

    // ==================== 导航方法 ====================

    /**
     * 导航到目标场景，加入返回栈。
     * 如果正在过渡或目标已是当前场景，忽略。
     */
    fun navigateTo(scene: NavScene, argument: String = "") {
        if (isTransitioning) return
        if (scene == NavScene.HOME) {
            navigateHome()
            return
        }
        if (scene == currentScene) return
        _backStack.add(scene)
        currentArgument = argument
        currentScene = scene
    }

    fun navigateToSettings(scene: NavScene = NavScene.SETTINGS) {
        if (isTransitioning) return
        if (scene !in settingsScenes) {
            navigateTo(scene)
            return
        }

        if (scene == NavScene.SETTINGS) {
            val rootIndex = _backStack.indexOf(NavScene.SETTINGS)
            if (rootIndex >= 0) {
                // SETTINGS 已在栈中，裁剪到它
                while (_backStack.lastIndex > rootIndex) {
                    _backStack.removeAt(_backStack.lastIndex)
                }
            } else {
                // SETTINGS 不在栈中，只加一次
                if (currentScene != NavScene.SETTINGS) {
                    _backStack.add(NavScene.SETTINGS)
                }
            }
            currentArgument = ""
            currentScene = NavScene.SETTINGS
            return
        }

        // 非 SETTINGS 的子场景
        if (currentScene !in settingsScenes && _backStack.lastOrNull() != NavScene.SETTINGS) {
            _backStack.add(NavScene.SETTINGS)
        }
        if (scene != currentScene) {
            _backStack.add(scene)
            currentArgument = ""
            currentScene = scene
        }
    }

    /**
     * 返回上一级（无动画，直接切换）。
     * @return true 如果成功返回
     */
    fun navigateBack(): Boolean {
        if (isTransitioning) return false
        if (!canNavigateBack()) return false
        _backStack.removeAt(_backStack.lastIndex)
        currentScene = _backStack.last()
        return true
    }

    /**
     * 返回上一级，由 SceneTransitionHost 驱动动画。
     * 设置 isAnimatingBack = true，SceneTransitionHost 检测到后播放返回动画，
     * 动画完成后调用 completeAnimatingBack() 执行真正的 navigateBack()。
     * @return true 如果可以返回
     */
    fun navigateBackAnimated(): Boolean {
        if (isTransitioning || isAnimatingBack) return false
        if (!canNavigateBack()) return false
        dragBackDirection = 1f
        isAnimatingBack = true
        animatingBackProgress = 0f
        return true
    }

    fun startBackDrag(direction: Float = 1f): Boolean {
        if (isTransitioning || isAnimatingBack || isDraggingBack) return false
        if (_backStack.size <= 1) return false
        dragBackDirection = if (direction < 0f) -1f else 1f
        isDraggingBack = true
        dragBackProgress = 0f
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        dragBackReleaseCommit = false
        return true
    }

    fun updateBackDrag(progress: Float) {
        if (!isDraggingBack) return
        dragBackProgress = progress.coerceIn(-0.2f, 1.2f)
    }

    fun releaseBackDrag(commit: Boolean, velocity: Float = 0f) {
        if (!isDraggingBack) return
        dragBackReleaseCommit = commit
        dragBackReleaseProgress = dragBackProgress
        dragBackReleaseVelocity = velocity
        isDraggingBack = false
        dragBackReleaseToken += 1
    }

    /**
     * 由 SceneTransitionHost 在返回动画完成后调用。
     * 执行真正的返回操作并清理状态。
     */
    internal fun completeAnimatingBack() {
        isAnimatingBack = false
        animatingBackProgress = 0f
        _backStack.removeAt(_backStack.lastIndex)
        currentScene = _backStack.last()
    }

    /**
     * 返回主页，清空返回栈。
     */
    fun navigateHome() {
        if (isTransitioning) return
        _backStack.clear()
        _backStack.add(NavScene.HOME)
        currentScene = NavScene.HOME
    }

    /**
     * 静默切换场景（不触发动画，不修改返回栈）。
     * 用于从播放器返回时恢复之前的场景。
     */
    fun switchToSilent(scene: NavScene) {
        currentScene = scene
    }

    // ==================== 查询方法 ====================

    fun isAtHome(): Boolean = currentScene == NavScene.HOME

    fun canNavigateBack(): Boolean {
        if (currentScene == NavScene.HOME) return false
        if (_backStack.size <= 1) return false
        // 根 SETTINGS 页面（栈为 [HOME, SETTINGS]）：禁用内部返回，
        // 让系统接管（预测性返回动画 + finish Activity）
        if (currentScene == NavScene.SETTINGS && _backStack.size == 2
            && _backStack[0] == NavScene.HOME) return false
        return true
    }

    fun getPreviousScene(): NavScene? {
        return if (_backStack.size > 1) _backStack[_backStack.lastIndex - 1] else null
    }

    // ==================== 持久化接口 ====================

    fun restorePersistentState(
        stack: List<NavScene>,
        scene: NavScene,
        argument: String
    ) {
        val safeStack = stack
            .filter { it in NavScene.entries }
            .toMutableList()

        if (safeStack.isEmpty() || safeStack.first() != NavScene.HOME) {
            safeStack.add(0, NavScene.HOME)
        }

        if (scene !in safeStack) {
            safeStack.add(scene)
        }

        _backStack.clear()
        _backStack.addAll(safeStack)

        currentScene = scene
        currentArgument = argument

        isTransitioning = false
        transitionProgress = 0f
        transitionFromScene = scene
        transitionToScene = scene

        isDraggingBack = false
        dragBackProgress = 0f
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        dragBackReleaseCommit = false

        isAnimatingBack = false
        animatingBackProgress = 0f
    }

    fun persistentBackStackIds(): List<Int> {
        return _backStack.map { it.id }
    }
}
