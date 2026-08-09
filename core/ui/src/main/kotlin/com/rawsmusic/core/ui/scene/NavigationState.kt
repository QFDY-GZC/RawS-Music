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
internal enum class NavigationMotionHint {
    DEFAULT,
    BOTTOM_NAVIGATION,
}

@Stable
class NavigationState {

    private companion object {
        val settingsScenes = setOf(
            NavScene.SETTINGS,
            NavScene.AUDIO_EFFECTS,
            NavScene.APPEARANCE,
            NavScene.PERSONALIZATION_SETTINGS,
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
    private val argumentStack = mutableStateListOf("")
    /** 每个栈项记录进入该页面时使用的动画，返回时使用同一动画的反向。 */
    private val entryMotionStack = mutableStateListOf(NavigationMotionHint.DEFAULT)

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

    /**
     * 返回动画正在揭示的目标场景。
     *
     * currentScene 必须等页面动画真正结束后才能切换，否则内容层会提前换页；
     * 底部导航栏则应在返回动作开始时就反馈目标入口，因此单独暴露预览场景。
     */
    var backPreviewScene by mutableStateOf<NavScene?>(null)
        private set
    private var siblingDragTarget: NavScene? = null

    // ==================== 程序化返回动画状态 ====================

    /** 是否正在播放程序化返回动画（非手势驱动） */
    var isAnimatingBack by mutableStateOf(false)
        internal set

    /** 程序化返回动画进度 0→1（从当前页面位置到完全离开） */
    var animatingBackProgress by mutableFloatStateOf(0f)
        internal set

    /**
     * 最近一次前向导航的视觉来源。
     *
     * 底部导航栏属于同一 Activity 内的顶层入口切换，必须保持中心缩放转场；
     * 页面内部导航则继续按场景关系选择共享元素或设置式横向转场。
     */
    internal var navigationMotionHint by mutableStateOf(NavigationMotionHint.DEFAULT)
        private set

    /**
     * 当前返回动作应使用的动画来源。
     *
     * AUDIO_EFFECTS 在设置入口中是系统 Activity 页面，但从底部导航进入时是 Compose
     * 顶层页面。返回动画必须取决于本次栈项的进入方式，不能只按页面类型判断。
     */
    internal var backNavigationMotionHint by mutableStateOf(NavigationMotionHint.DEFAULT)
        private set

    // ==================== 导航方法 ====================

    /**
     * 导航到目标场景，加入返回栈。
     * 如果正在过渡或目标已是当前场景，忽略。
     */
    fun navigateTo(scene: NavScene, argument: String = "") {
        if (isTransitioning) return
        navigationMotionHint = NavigationMotionHint.DEFAULT
        clearBackPreview()
        if (scene == NavScene.HOME) {
            navigateHome()
            return
        }
        if (scene == currentScene) return
        _backStack.add(scene)
        argumentStack.add(argument)
        entryMotionStack.add(NavigationMotionHint.DEFAULT)
        currentArgument = argument
        currentScene = scene
    }

    /**
     * 从底部导航栏切换顶层入口。
     *
     * 底栏需要保留最近访问顺序，使 HOME → SONGS → AUDIO_EFFECTS 的返回关系仍为
     * AUDIO_EFFECTS → SONGS → HOME；但同一个入口不能被反复追加，否则往返切换后会
     * 形成 SONGS/AUDIO_EFFECTS 的历史循环。
     *
     * 因此切换前先移除当前入口上方的详情页，再把目标入口从已有历史中取出并追加到
     * 栈顶。返回栈最终是一个按最近访问顺序排列、且没有重复项的底栏入口列表。
     */
    fun navigateFromBottomNavigation(scene: NavScene, argument: String = "") {
        if (isTransitioning) return
        if (scene == currentScene) return

        clearBackPreview()
        navigationMotionHint = NavigationMotionHint.BOTTOM_NAVIGATION
        moveBottomNavigationEntryToTop(scene, argument)
    }

    private fun moveBottomNavigationEntryToTop(scene: NavScene, argument: String) {
        if (scene == NavScene.HOME) {
            _backStack.clear()
            _backStack.add(NavScene.HOME)
            argumentStack.clear()
            argumentStack.add("")
            entryMotionStack.clear()
            entryMotionStack.add(NavigationMotionHint.DEFAULT)
            currentArgument = ""
            currentScene = NavScene.HOME
            return
        }

        val bottomEntries = NavScene.bottomNavigationEntries.toSet()

        // 底栏切换离开详情页时，只保留其所属的最近顶层入口。
        while (_backStack.size > 1 && _backStack.last() !in bottomEntries) {
            _backStack.removeAt(_backStack.lastIndex)
            argumentStack.removeAt(argumentStack.lastIndex)
            entryMotionStack.removeAt(entryMotionStack.lastIndex)
        }

        // 目标入口若已存在，先从旧位置移除，再追加到栈顶形成去重的最近访问顺序。
        for (index in _backStack.lastIndex downTo 1) {
            if (_backStack[index] == scene) {
                _backStack.removeAt(index)
                argumentStack.removeAt(index)
                entryMotionStack.removeAt(index)
            }
        }

        _backStack.add(scene)
        argumentStack.add(argument)
        entryMotionStack.add(NavigationMotionHint.BOTTOM_NAVIGATION)
        currentArgument = argument
        currentScene = scene
    }

    fun navigateToSettings(scene: NavScene = NavScene.SETTINGS) {
        if (isTransitioning) return
        navigationMotionHint = NavigationMotionHint.DEFAULT
        clearBackPreview()
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
                    argumentStack.removeAt(argumentStack.lastIndex)
                    entryMotionStack.removeAt(entryMotionStack.lastIndex)
                }
            } else {
                // SETTINGS 不在栈中，只加一次
                if (currentScene != NavScene.SETTINGS) {
                    _backStack.add(NavScene.SETTINGS)
                    argumentStack.add("")
                    entryMotionStack.add(NavigationMotionHint.DEFAULT)
                }
            }
            currentArgument = ""
            currentScene = NavScene.SETTINGS
            return
        }

        // 非 SETTINGS 的子场景
        if (currentScene !in settingsScenes && _backStack.lastOrNull() != NavScene.SETTINGS) {
            _backStack.add(NavScene.SETTINGS)
            argumentStack.add("")
            entryMotionStack.add(NavigationMotionHint.DEFAULT)
        }
        if (scene != currentScene) {
            _backStack.add(scene)
            argumentStack.add("")
            entryMotionStack.add(NavigationMotionHint.DEFAULT)
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
        navigationMotionHint = NavigationMotionHint.DEFAULT
        if (!canNavigateBack()) return false
        popBackStack()
        clearBackPreview()
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
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
        navigationMotionHint = NavigationMotionHint.DEFAULT
        if (!canNavigateBack()) return false
        backNavigationMotionHint = currentEntryMotionHint()
        dragBackDirection = 1f
        backPreviewScene = getPreviousScene()
        isAnimatingBack = true
        animatingBackProgress = 0f
        return true
    }

    fun startBackDrag(direction: Float = 1f): Boolean {
        if (isTransitioning || isAnimatingBack || isDraggingBack) return false
        navigationMotionHint = NavigationMotionHint.DEFAULT
        if (!canNavigateBack()) return false
        backNavigationMotionHint = currentEntryMotionHint()
        dragBackDirection = if (direction < 0f) -1f else 1f
        backPreviewScene = getPreviousScene()
        isDraggingBack = true
        dragBackProgress = 0f
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        dragBackReleaseCommit = false
        return true
    }

    fun startSiblingDrag(target: NavScene, direction: Float): Boolean {
        if (isTransitioning || isAnimatingBack || isDraggingBack || target == currentScene) return false
        navigationMotionHint = NavigationMotionHint.BOTTOM_NAVIGATION
        backNavigationMotionHint = NavigationMotionHint.BOTTOM_NAVIGATION
        dragBackDirection = if (direction < 0f) -1f else 1f
        siblingDragTarget = target
        backPreviewScene = target
        isDraggingBack = true
        dragBackProgress = 0f
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        dragBackReleaseCommit = false
        return true
    }

    /** Clears an interrupted predictive-back preview after foreground/window restoration. */
    fun resetTransientBackState() {
        isDraggingBack = false
        dragBackProgress = 0f
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        dragBackReleaseCommit = false
        isAnimatingBack = false
        animatingBackProgress = 0f
        siblingDragTarget = null
        clearBackPreview()
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
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
        if (canNavigateBack()) {
            popBackStack()
        }
        clearBackPreview()
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
    }

    /** SceneTransitionHost 在手势提交或取消的回弹结束后统一收口状态。 */
    internal fun completeBackDrag(commit: Boolean) {
        val siblingTarget = siblingDragTarget
        if (commit) {
            if (siblingTarget != null) {
                moveBottomNavigationEntryToTop(siblingTarget, "")
            } else if (canNavigateBack()) {
                popBackStack()
            }
        }
        siblingDragTarget = null
        dragBackReleaseCommit = false
        dragBackReleaseProgress = 0f
        dragBackReleaseVelocity = 0f
        clearBackPreview()
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
    }

    /**
     * 返回主页，清空返回栈。
     */
    fun navigateHome() {
        if (isTransitioning) return
        navigationMotionHint = NavigationMotionHint.DEFAULT
        clearBackPreview()
        _backStack.clear()
        _backStack.add(NavScene.HOME)
        argumentStack.clear()
        argumentStack.add("")
        entryMotionStack.clear()
        entryMotionStack.add(NavigationMotionHint.DEFAULT)
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
        currentArgument = ""
        currentScene = NavScene.HOME
    }

    /**
     * 静默切换场景（不触发动画，不修改返回栈）。
     * 用于从播放器返回时恢复之前的场景。
     */
    fun switchToSilent(scene: NavScene) {
        navigationMotionHint = NavigationMotionHint.DEFAULT
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
        clearBackPreview()
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

    private fun popBackStack() {
        _backStack.removeAt(_backStack.lastIndex)
        argumentStack.removeAt(argumentStack.lastIndex)
        entryMotionStack.removeAt(entryMotionStack.lastIndex)
        currentScene = _backStack.last()
        currentArgument = argumentStack.lastOrNull().orEmpty()
    }

    private fun currentEntryMotionHint(): NavigationMotionHint {
        return entryMotionStack.getOrNull(entryMotionStack.lastIndex)
            ?: NavigationMotionHint.DEFAULT
    }

    private fun clearBackPreview() {
        backPreviewScene = null
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
        argumentStack.clear()
        repeat(safeStack.size.coerceAtLeast(1)) { argumentStack.add("") }
        argumentStack[argumentStack.lastIndex] = argument

        entryMotionStack.clear()
        safeStack.forEachIndexed { index, entry ->
            val previous = safeStack.getOrNull(index - 1)
            entryMotionStack.add(
                if (
                    index > 0 &&
                    entry in NavScene.bottomNavigationEntries &&
                    previous in NavScene.bottomNavigationEntries
                ) {
                    NavigationMotionHint.BOTTOM_NAVIGATION
                } else {
                    NavigationMotionHint.DEFAULT
                }
            )
        }

        currentScene = scene
        currentArgument = argument

        navigationMotionHint = NavigationMotionHint.DEFAULT
        backNavigationMotionHint = NavigationMotionHint.DEFAULT
        isTransitioning = false
        transitionProgress = 0f
        transitionFromScene = scene
        transitionToScene = scene

        isDraggingBack = false
        dragBackProgress = 0f
        backPreviewScene = null
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
