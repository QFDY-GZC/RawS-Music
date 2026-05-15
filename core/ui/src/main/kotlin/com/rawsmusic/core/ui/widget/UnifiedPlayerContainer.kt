package com.rawsmusic.core.ui.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import coil.imageLoader
import coil.load
import kotlin.math.abs

/**
 * Poweramp 模式的统一场景容器
 *
 * 核心架构（逆向自 Poweramp SceneViewGroupHelper / e4 / q4 / z3 / b4）：
 *
 * 1. **SceneParams 注册表**：每个 View 注册在各场景下的目标属性（alpha, scaleX/Y, translationX/Y, visibility）
 * 2. **StateAnimParams（对标 q4）**：动画执行时，先捕获当前属性作为 from，目标场景属性作为 to，用 flag 位标记哪些属性参与插值
 * 3. **Ratio 驱动**：所有属性共享一个 ratio（0~1），由单个 ValueAnimator 驱动。DecelerateInterpolator(2.0f) 塑形
 * 4. **拖拽-动画衔接**：手势拖拽直接写入 ratio，释放时从当前 ratio 动画到目标 0 或 1，实现无跳变过渡
 * 5. **Visibility-Alpha 联动**：从不可见→可见时 ratio > 0 即设 VISIBLE；从可见→不可见时 ratio = 1.0 才设最终 visibility
 */
class UnifiedPlayerContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ==================== 场景定义 ====================
    enum class Scene {
        MAIN,           // 主界面（歌曲列表）
        PLAYER,         // 播放界面
        LYRIC,          // 歌词界面
        QUEUE,          // 队列界面
        ALBUM_DETAIL    // 专辑详情界面
    }

    var currentScene: Scene = Scene.MAIN
        private set

    // ==================== 沉浸模式 ====================
    private var immersiveBackground: ImmersiveBackgroundView? = null
    private var originalCoverImageView: ImageView? = null
    private var miniCoverView: View? = null // 主界面专属的迷你封面层级
    private var playBgScrim: View? = null
    var isImmersiveEnabled = true
        private set
    private var isDarkMode = true
    var isMiniCoverEnabled = true
        private set
    private var immersiveViewId = View.NO_ID
    private var isApplyingImmersiveParams = false

    fun initImmersiveViews(
        immersiveBg: ImmersiveBackgroundView,
        coverImg: ImageView,
        playScrim: View? = null,
        miniCover: View? = null, // 新增参数：主界面的迷你封面 View
        isImmersiveEnabled: Boolean = true,
        isMiniCoverEnabled: Boolean = true
    ) {
        this.immersiveBackground = immersiveBg
        this.originalCoverImageView = coverImg
        this.playBgScrim = playScrim
        this.miniCoverView = miniCover // 接收迷你封面
        this.isImmersiveEnabled = isImmersiveEnabled
        this.isMiniCoverEnabled = isMiniCoverEnabled
        
        immersiveBg.isImmersiveEnabled = isImmersiveEnabled
        immersiveBg.isDarkMode = isDarkMode

        if (immersiveBg.id == View.NO_ID) {
            immersiveViewId = View.generateViewId()
            immersiveBg.id = immersiveViewId
        } else {
            immersiveViewId = immersiveBg.id
        }

        applyImmersiveSceneParams()
    }

    fun applyImmersiveSceneParams() {
        if (isApplyingImmersiveParams) return
        isApplyingImmersiveParams = true
        try {
            // ═══════ 层级 2：沉浸融合背景 ═══════
            val bgId = immersiveViewId
            if (bgId != View.NO_ID) {
                val immersiveAlpha = if (isImmersiveEnabled) 1f else 0f
                val immersiveVisibility = if (isImmersiveEnabled) View.VISIBLE else View.INVISIBLE

                registerViewScenes(bgId,
                    Scene.MAIN to SceneParams(Scene.MAIN, alpha = 0f, visibility = View.INVISIBLE),
                    Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = immersiveAlpha, visibility = immersiveVisibility),
                    Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = immersiveAlpha, visibility = immersiveVisibility),
                    Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = immersiveAlpha, visibility = immersiveVisibility),
                    Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = immersiveAlpha, visibility = immersiveVisibility)
                )
                immersiveBackground?.elevation = 1f // 位于流光之上，但低于播放控制键
            }

            // ═══════ 层级 3：主界面常驻封面 (新增独立层级) ═══════
            miniCoverView?.let { miniCover ->
                val miniCoverId = miniCover.id
                if (miniCoverId != View.NO_ID) {
                    // 只在主界面显示，受 isMiniCoverEnabled 控制，VIEW alpha 始终为1f
                    val mainAlpha = if (isMiniCoverEnabled) 1f else 0f
                    val mainVisibility = if (isMiniCoverEnabled) View.VISIBLE else View.INVISIBLE

                    registerViewScenes(miniCoverId,
                        Scene.MAIN to SceneParams(Scene.MAIN, alpha = mainAlpha, visibility = mainVisibility),
                        Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = 0f, visibility = View.INVISIBLE),
                        Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = 0f, visibility = View.INVISIBLE),
                        Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = 0f, visibility = View.INVISIBLE),
                        Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = 0f, visibility = View.INVISIBLE)
                    )
                    miniCover.elevation = 0f // 位于歌曲列表之下，流光背景之上

                    // 通过 coverAlpha 控制封面图片25%透明（不影响镜像和模糊层）
                    if (miniCover is ImmersiveBackgroundView) {
                        miniCover.coverAlpha = if (isMiniCoverEnabled) 0.25f else 1f
                    }
                }
            }

            // ═══════ 层级 4：播放页大封面 ═══════
            originalCoverImageView?.let { coverImg ->
                val coverId = coverImg.id
                val coverScenes = sceneRegistry.getOrPut(coverId) { mutableMapOf() }

                coverScenes[Scene.MAIN] = SceneParams(Scene.MAIN, alpha = 0f, visibility = View.INVISIBLE)
                
                // 沉浸模式下封面由 ImmersiveBackgroundView 渲染，ivPlayCover 不可见
                val playerCoverAlpha = if (isImmersiveEnabled) 0f else 1f
                val playerCoverVisibility = if (isImmersiveEnabled) View.INVISIBLE else View.VISIBLE

                coverScenes[Scene.PLAYER] = SceneParams(Scene.PLAYER, alpha = playerCoverAlpha, visibility = playerCoverVisibility)
                coverScenes[Scene.LYRIC] = SceneParams(Scene.LYRIC, alpha = playerCoverAlpha, visibility = playerCoverVisibility)
                coverScenes[Scene.QUEUE] = SceneParams(Scene.QUEUE, alpha = playerCoverAlpha, visibility = playerCoverVisibility)
                coverScenes[Scene.ALBUM_DETAIL] = SceneParams(Scene.ALBUM_DETAIL, alpha = playerCoverAlpha, visibility = playerCoverVisibility)

                coverImg.elevation = 10f // 位于沉浸背景之上
            }

            // ═══════ 层级 4：播放页遮罩 ═══════
            playBgScrim?.let { scrim ->
                val scrimId = scrim.id
                if (scrimId != View.NO_ID) {
                    val scrimAlpha = if (isImmersiveEnabled) 0f else 1f
                    val scrimVisibility = if (isImmersiveEnabled) View.INVISIBLE else View.VISIBLE
                    registerViewScenes(scrimId,
                        Scene.MAIN to SceneParams(Scene.MAIN, alpha = 0f, visibility = View.GONE),
                        Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = scrimAlpha, visibility = scrimVisibility),
                        Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = scrimAlpha, visibility = scrimVisibility),
                        Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = scrimAlpha, visibility = scrimVisibility),
                        Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = scrimAlpha, visibility = scrimVisibility)
                    )
                }
            }

            if (!isTransitioning) {
                switchToSceneSilent(currentScene)
            }

            forceApplyImmersiveVisibility()
        } finally {
            isApplyingImmersiveParams = false
        }
    }

    private fun forceApplyImmersiveVisibility() {
        val bg = immersiveBackground ?: return
        val sceneParams = sceneRegistry[bg.id]?.get(currentScene)
        if (sceneParams != null) {
            bg.visibility = sceneParams.visibility
            if (sceneParams.visibility != View.GONE) {
                bg.alpha = sceneParams.alpha
            }
        } else {
            if (currentScene == Scene.MAIN) {
                bg.visibility = View.INVISIBLE
                bg.alpha = 0f
            } else {
                bg.visibility = if (isImmersiveEnabled) View.VISIBLE else View.INVISIBLE
                bg.alpha = if (isImmersiveEnabled) 1f else 0f
            }
        }

        originalCoverImageView?.let { coverImg ->
            val coverParams = sceneRegistry[coverImg.id]?.get(currentScene)
            if (coverParams != null) {
                coverImg.visibility = coverParams.visibility
                if (coverParams.visibility != View.GONE) {
                    coverImg.alpha = coverParams.alpha
                }
            } else {
                if (currentScene == Scene.MAIN) {
                    coverImg.visibility = View.INVISIBLE
                    coverImg.alpha = 0f
                } else {
                    coverImg.visibility = View.VISIBLE
                    coverImg.alpha = 1f
                }
            }
        }

        // 新增：同步迷你封面层级
        miniCoverView?.let { miniCover ->
            val miniParams = sceneRegistry[miniCover.id]?.get(currentScene)
            if (miniParams != null) {
                miniCover.visibility = miniParams.visibility
                if (miniParams.visibility != View.GONE) {
                    miniCover.alpha = miniParams.alpha
                }
            } else {
                if (currentScene == Scene.MAIN) {
                    miniCover.visibility = if (isMiniCoverEnabled) View.VISIBLE else View.INVISIBLE
                    miniCover.alpha = if (isMiniCoverEnabled) 1f else 0f
                    if (miniCover is ImmersiveBackgroundView) {
                        miniCover.coverAlpha = if (isMiniCoverEnabled) 0.25f else 1f
                    }
                } else {
                    miniCover.visibility = View.INVISIBLE
                    miniCover.alpha = 0f
                }
            }
        }
    }

    fun updateImmersiveCover(path: String?) {
        originalCoverImageView?.load(path) {
            crossfade(true)
            transformations(com.rawsmusic.core.ui.util.SquarePadTransformation())
        }
        immersiveBackground?.setCover(path)
        // 更新主界面常驻封面
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.setCover(path)
            }
        }
    }

    fun updateMiniCoverEnabled(enabled: Boolean) {
        if (isMiniCoverEnabled == enabled) return
        isMiniCoverEnabled = enabled
        // 更新主界面常驻封面的启用状态
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.isImmersiveEnabled = enabled
            }
        }
        applyImmersiveSceneParams()
    }

    fun updateImmersiveSettings(isImmersive: Boolean, isDark: Boolean) {
        isImmersiveEnabled = isImmersive
        isDarkMode = isDark
        immersiveBackground?.isImmersiveEnabled = isImmersive
        immersiveBackground?.isDarkMode = isDark
        // 更新主界面常驻封面
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.isImmersiveEnabled = isImmersive
                miniCover.isDarkMode = isDark
            }
        }
        applyImmersiveSceneParams()
    }

    fun refreshImmersiveState(isImmersive: Boolean) {
        isImmersiveEnabled = isImmersive
        immersiveBackground?.isImmersiveEnabled = isImmersive
        // 更新主界面常驻封面
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.isImmersiveEnabled = isImmersive
            }
        }
        applyImmersiveSceneParams()
    }

    // ==================== 属性 flag 位（对标 Poweramp AbstractC0792 常量） ====================
    object PropFlag {
        const val ALPHA = 1 shl 0           // 1     - alpha 参与
        const val SCALE_X = 1 shl 1         // 2     - scaleX 参与
        const val SCALE_Y = 1 shl 2         // 4     - scaleY 参与
        const val TRANSLATION_X = 1 shl 3   // 8     - translationX 参与
        const val TRANSLATION_Y = 1 shl 4   // 16    - translationY 参与
        const val VISIBILITY = 1 shl 5      // 32    - visibility 参与
        const val CORNER_RADIUS = 1 shl 6   // 64    - cornerRadius 参与
    }

    // ==================== 场景参数数据类（对标 z3） ====================
    data class SceneParams(
        val scene: Scene,
        var alpha: Float = 1f,
        val translationX: Float = 0f,
        val translationY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        var visibility: Int = View.VISIBLE,
        val cornerRadius: Float = -1f
    )

    // ==================== 动画参数（对标 q4 StateAnimParams） ====================
    /**
     * 每个 View 的一次场景动画参数
     * 包含 from/to 值和参与插值的属性 flag
     */
    private class StateAnimParams(
        val view: View,
        var flags: Int = 0
    ) {
        // from 值（动画开始前捕获的当前属性）
        var fromAlpha: Float = 1f
        var fromScaleX: Float = 1f
        var fromScaleY: Float = 1f
        var fromTranslationX: Float = 0f
        var fromTranslationY: Float = 0f
        var fromVisibility: Int = View.VISIBLE
        var fromCornerRadius: Float = -1f

        // to 值（目标场景的属性）
        var toAlpha: Float = 1f
        var toScaleX: Float = 1f
        var toScaleY: Float = 1f
        var toTranslationX: Float = 0f
        var toTranslationY: Float = 0f
        var toVisibility: Int = View.VISIBLE
        var toCornerRadius: Float = -1f
    }

    // ==================== 场景注册表 ====================
    private val sceneRegistry = mutableMapOf<Int, MutableMap<Scene, SceneParams>>()

    fun registerSceneParams(viewId: Int, scene: Scene, params: SceneParams) {
        val viewMap = sceneRegistry.getOrPut(viewId) { mutableMapOf() }
        viewMap[scene] = params
    }

    fun registerViewScenes(viewId: Int, vararg sceneParams: Pair<Scene, SceneParams>) {
        sceneParams.forEach { (scene, params) ->
            registerSceneParams(viewId, scene, params)
        }
    }

    // ==================== Ratio 驱动的场景动画引擎（对标 e4 + b4） ====================

    /**
     * 当前场景过渡的 ratio（0 = fromScene，1 = toScene）
     * 手势拖拽直接写入此值，释放时动画驱动此值到 0 或 1
     */
    private var transitionRatio: Float = 0f

    /** 当前过渡的源场景 */
    private var fromScene: Scene = Scene.MAIN

    /** 当前过渡的目标场景 */
    private var toScene: Scene = Scene.MAIN

    /** 当前活跃的动画参数列表 */
    private var activeAnimParams: MutableList<StateAnimParams>? = null

    /** 场景切换动画器 */
    private var sceneAnimator: ValueAnimator? = null
    private var sceneAnimGeneration = 0 // 用于检测动画是否被新动画取代

    var isTransitioning: Boolean = false

    var lyricEnabled: Boolean = false

    var onSceneChanged: ((newScene: Scene, oldScene: Scene) -> Unit)? = null
    var onTransitionProgress: ((Scene, Float) -> Unit)? = null

    /** PLAYER→MAIN 过渡前的封面参数准备回调（外部注册封面到列表位置的参数） */
    var onPreparePlayerToMain: (() -> Unit)? = null

    /** PLAYER→LYRIC 过渡前的封面参数准备回调（外部注册封面到歌词页位置的参数） */
    var onPreparePlayerToLyric: (() -> Unit)? = null

    /** PLAYER→QUEUE 过渡前的准备回调 */
    var onPreparePlayerToQueue: (() -> Unit)? = null

    /** PLAYER→ALBUM_DETAIL 过渡前的准备回调 */
    var onPreparePlayerToAlbumDetail: (() -> Unit)? = null

    /** MAIN→PLAYER 过渡前的准备回调（外部恢复封面可见性等） */
    var onPrepareMainToPlayer: (() -> Unit)? = null

    /**
     * 场景切换动画（对标 e4.m2828 / b4）
     *
     * 核心流程：
     * 1. 收集所有注册了 fromScene 和 toScene 参数的 View
     * 2. 捕获当前属性作为 from 值（对标 e4.m2820 / q4 的 from 捕获）
     * 3. 注册目标场景参数作为 to 值
     * 4. 计算 flag 位标记哪些属性需要插值
     * 5. 单个 ValueAnimator 驱动 ratio 从 currentRatio → 1.0f
     * 6. 每帧调用 applyRatio() 插值所有属性
     */
    fun transitionToScene(targetScene: Scene, duration: Long = SCENE_ANIM_DURATION) {
        if (currentScene == targetScene && !isTransitioning) return

        sceneAnimGeneration++
        sceneAnimator?.cancel()

        val from = currentScene
        val to = targetScene

        Log.d("SceneTransition", "transitionToScene: $from → $to")

        val params = buildAnimParams(from, to)
        if (params.isEmpty()) {
            val oldScene = currentScene
            currentScene = targetScene
            isTransitioning = false
            Log.d("SceneTransition", "transitionToScene immediate: currentScene=$currentScene")
            val gen = sceneAnimGeneration
            post {
                if (sceneAnimGeneration != gen) return@post
                onSceneChanged?.invoke(targetScene, oldScene)
            }
            return
        }

        fromScene = from
        toScene = to
        activeAnimParams = params
        transitionRatio = 0f
        isTransitioning = true

        val gen = sceneAnimGeneration
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedFraction
                applyRatio(params, transitionRatio)
                onTransitionProgress?.invoke(to, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        Log.d("SceneTransition", "transitionToScene cancelled, skipping onSceneChanged")
                        isTransitioning = false
                        activeAnimParams = null
                        return
                    }
                    try {
                        applyRatio(params, 1f)
                    } catch (e: Exception) {
                        Log.e("SceneTransition", "transitionToScene applyRatio error", e)
                    } finally {
                        val oldScene = currentScene
                        currentScene = targetScene
                        transitionRatio = 0f
                        isTransitioning = false
                        activeAnimParams = null
                        Log.d("SceneTransition", "transitionToScene end: oldScene=$oldScene → currentScene=$currentScene")
                    }
                    post {
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "transitionToScene onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(targetScene, currentScene)
                        } catch (e: Exception) {
                            Log.e("SceneTransition", "transitionToScene onSceneChanged error", e)
                        }
                    }
                }
            })
            start()
        }
        sceneAnimator = animator
    }

    /**
     * 从当前 ratio 继续动画到目标场景（对标 b4 的 fromCurrentRatio 模式）
     * 用于手势释放后从拖拽位置平滑过渡到目标
     */
    fun transitionFromCurrentRatio(targetScene: Scene, duration: Long = SCENE_ANIM_DURATION) {
        sceneAnimGeneration++
        sceneAnimator?.cancel()

        val params = activeAnimParams ?: buildAnimParams(fromScene, targetScene)
        if (params.isEmpty()) {
            val oldScene = currentScene
            currentScene = targetScene
            isTransitioning = false
            val gen = sceneAnimGeneration
            post {
                if (sceneAnimGeneration != gen) return@post
                onSceneChanged?.invoke(targetScene, oldScene)
            }
            return
        }

        val currentRatio = transitionRatio
        // 如果 fromScene 就是 targetScene，说明要回弹，ratio 从当前位置→0
        val isRevert = (fromScene == targetScene)
        val startRatio = currentRatio
        val endRatio = if (isRevert) 0f else 1f

        if (fromScene != targetScene && toScene != targetScene) {
            // 完全不同的场景，重建参数
            activeAnimParams = buildAnimParams(currentScene, targetScene)
            fromScene = currentScene
            toScene = targetScene
            transitionRatio = 0f
        }

        toScene = targetScene
        isTransitioning = true

        val gen = sceneAnimGeneration
        val animator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedValue as Float
                applyRatio(activeAnimParams ?: params, transitionRatio)
                onTransitionProgress?.invoke(targetScene, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        Log.d("SceneTransition", "transitionFromCurrentRatio cancelled, skipping onSceneChanged")
                        isTransitioning = false
                        activeAnimParams = null
                        return
                    }
                    try {
                        applyRatio(activeAnimParams ?: params, endRatio)
                    } catch (e: Exception) {
                        Log.e("SceneTransition", "transitionFromCurrentRatio applyRatio error", e)
                    } finally {
                        val oldScene = currentScene
                        currentScene = targetScene
                        transitionRatio = 0f
                        isTransitioning = false
                        activeAnimParams = null
                    }
                    post {
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "transitionFromCurrentRatio onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(targetScene, currentScene)
                        } catch (e: Exception) {
                            Log.e("SceneTransition", "transitionFromCurrentRatio onSceneChanged error", e)
                        }
                    }
                }
            })
            start()
        }
        sceneAnimator = animator
    }

    /**
     * 静默切换场景：不触发任何动画，直接将所有 View 设置到目标状态。
     * 用于和外部手动动画配合，避免双重动画。
     * 与 switchToSceneImmediate 功能相同，语义上强调"绕过动画引擎"。
     */
    fun switchToSceneSilent(targetScene: Scene) {
        sceneAnimator?.cancel()

        for ((viewId, sceneMap) in sceneRegistry) {
            val view = findViewById<View>(viewId) ?: continue
            val params = sceneMap[targetScene] ?: continue
            applyParamsImmediate(view, params)
        }

        val oldScene = currentScene
        currentScene = targetScene
        transitionRatio = 0f
        isTransitioning = false
        activeAnimParams = null
        // 仅在场景实际变化时才触发回调，避免递归调用
        if (oldScene != targetScene) {
            onSceneChanged?.invoke(targetScene, oldScene)
        }
    }

    // ==================== 动画参数构建（对标 e4.m2820 捕获 from 值） ====================

    /**
     * 构建从 fromScene 到 toScene 的所有 View 动画参数
     * 捕获当前 View 属性作为 from 值，注册的目标场景属性作为 to 值
     */
    private fun buildAnimParams(fromScene: Scene, toScene: Scene): MutableList<StateAnimParams> {
        val result = mutableListOf<StateAnimParams>()

        for ((viewId, sceneMap) in sceneRegistry) {
            val view = findViewById<View>(viewId) ?: continue
            
            // originalCoverImageView 始终参与动画，由注册的场景参数决定其起止状态
            // 沉浸模式下 PLAYER 场景已正确设为 INVISIBLE，无需额外防御

            val fromParams = sceneMap[fromScene]
            val toParams = sceneMap[toScene]
            if (fromParams == null && toParams == null) continue

            val animParams = StateAnimParams(view)
            var flags = 0

            val fp = fromParams ?: captureCurrentParams(view, fromScene)
            val tp = toParams ?: continue

            // Alpha
            if (fp.alpha != tp.alpha) {
                flags = flags or PropFlag.ALPHA
                animParams.fromAlpha = view.alpha
                animParams.toAlpha = tp.alpha
            } else if (view.alpha != tp.alpha) {
                view.alpha = tp.alpha
            }

            // ScaleX
            if (fp.scaleX != tp.scaleX) {
                flags = flags or PropFlag.SCALE_X
                animParams.fromScaleX = view.scaleX
                animParams.toScaleX = tp.scaleX
            } else if (view.scaleX != tp.scaleX) {
                view.scaleX = tp.scaleX
            }

            // ScaleY
            if (fp.scaleY != tp.scaleY) {
                flags = flags or PropFlag.SCALE_Y
                animParams.fromScaleY = view.scaleY
                animParams.toScaleY = tp.scaleY
            } else if (view.scaleY != tp.scaleY) {
                view.scaleY = tp.scaleY
            }

            // TranslationX
            if (fp.translationX != tp.translationX) {
                flags = flags or PropFlag.TRANSLATION_X
                animParams.fromTranslationX = view.translationX
                animParams.toTranslationX = tp.translationX
            } else if (view.translationX != tp.translationX) {
                view.translationX = tp.translationX
            }

            // TranslationY
            if (fp.translationY != tp.translationY) {
                flags = flags or PropFlag.TRANSLATION_Y
                animParams.fromTranslationY = view.translationY
                animParams.toTranslationY = tp.translationY
            } else if (view.translationY != tp.translationY) {
                view.translationY = tp.translationY
            }

            // Visibility
            val needVisAnim = fp.visibility != tp.visibility || view.visibility != tp.visibility
            if (needVisAnim) {
                flags = flags or PropFlag.VISIBILITY
                animParams.fromVisibility = view.visibility
                animParams.toVisibility = tp.visibility
            } else if (view.visibility != tp.visibility) {
                view.visibility = tp.visibility
            }

            // CornerRadius
            if (fp.cornerRadius >= 0f && tp.cornerRadius >= 0f && fp.cornerRadius != tp.cornerRadius) {
                flags = flags or PropFlag.CORNER_RADIUS
                animParams.fromCornerRadius = fp.cornerRadius
                animParams.toCornerRadius = tp.cornerRadius
            }

            if (flags != 0) {
                animParams.flags = flags
                result.add(animParams)
            }
        }
        return result
    }

    // ==================== Ratio 插值引擎（对标 e4.r() 方法） ====================

    /**
     * 根据 ratio 对所有动画参数执行线性插值
     * ratio = 0 → from 值，ratio = 1 → to 值
     * 外部 ValueAnimator 的 DecelerateInterpolator 塑形 ratio 本身
     */
    private fun applyRatio(params: List<StateAnimParams>, ratio: Float) {
        for (ap in params) {
            val view = ap.view
            val flags = ap.flags

            // Visibility-Alpha 联动（对标 Poweramp e4.m2825）：
            // 从不可见→可见：ratio > 0 就设 VISIBLE（让 alpha 动画可见）
            if ((flags and PropFlag.VISIBILITY) != 0) {
                if (ap.toVisibility == View.VISIBLE && ratio > 0f && view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                } else if (ap.toVisibility != View.VISIBLE && ap.fromVisibility == View.VISIBLE && ratio >= 1f && view.visibility != ap.toVisibility) {
                    view.visibility = ap.toVisibility
                }
            }
            // 如果 visibility 没变但 to 是 VISIBLE 且当前不可见，也设 VISIBLE
            if ((flags and PropFlag.VISIBILITY) == 0 && view.visibility != View.VISIBLE) {
                if (ap.toVisibility == View.VISIBLE && ratio > 0f) {
                    view.visibility = View.VISIBLE
                }
            }

            // Alpha 插值（对标 e4.r: ((toAlpha - fromAlpha) * ratio) + fromAlpha）
            if ((flags and PropFlag.ALPHA) != 0) {
                view.alpha = lerp(ap.fromAlpha, ap.toAlpha, ratio)
            }

            // ScaleX
            if ((flags and PropFlag.SCALE_X) != 0) {
                view.scaleX = lerp(ap.fromScaleX, ap.toScaleX, ratio)
            }

            // ScaleY
            if ((flags and PropFlag.SCALE_Y) != 0) {
                view.scaleY = lerp(ap.fromScaleY, ap.toScaleY, ratio)
            }

            // TranslationX
            if ((flags and PropFlag.TRANSLATION_X) != 0) {
                view.translationX = lerp(ap.fromTranslationX, ap.toTranslationX, ratio)
            }

            // TranslationY
            if ((flags and PropFlag.TRANSLATION_Y) != 0) {
                view.translationY = lerp(ap.fromTranslationY, ap.toTranslationY, ratio)
            }

            // CornerRadius
            if ((flags and PropFlag.CORNER_RADIUS) != 0) {
                if (view is com.google.android.material.imageview.ShapeableImageView) {
                    val radius = lerp(ap.fromCornerRadius, ap.toCornerRadius, ratio)
                    view.shapeAppearanceModel = view.shapeAppearanceModel
                        .toBuilder()
                        .setAllCornerSizes(radius)
                        .build()
                }
            }

            // 动画结束后设最终 visibility（对标 e4.a() 直接应用）
            if (ratio >= 1f && (flags and PropFlag.VISIBILITY) != 0) {
                view.visibility = ap.toVisibility
            }
            // 兜底：如果没参与动画，但目标场景要求 GONE，强制设为 GONE
            else if (ratio >= 1f && ap.toVisibility == View.GONE && view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }

        // 动画结束后，强制将所有参与/未参与的 View 设定为目标场景的最终 Visibility
        if (ratio >= 1f) {
            for (ap in params) {
                val view = ap.view
                if (view.visibility != ap.toVisibility) {
                    view.visibility = ap.toVisibility
                }
            }
        }
    }

    /** 线性插值（对标 e4.r 中的 (to-from)*f+from 和 Utils.X） */
    private fun lerp(from: Float, to: Float, ratio: Float): Float {
        return from + (to - from) * ratio
    }

    // ==================== 捕获当前属性（对标 e4.m2821） ====================

    private fun captureCurrentParams(view: View, scene: Scene): SceneParams {
        return SceneParams(
            scene = scene,
            alpha = view.alpha,
            translationX = view.translationX,
            translationY = view.translationY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            visibility = view.visibility
        )
    }

    private fun applyParamsImmediate(view: View, params: SceneParams) {
        view.visibility = params.visibility
        if (params.visibility != View.GONE) {
            view.alpha = params.alpha
            view.translationX = params.translationX
            view.translationY = params.translationY
            view.scaleX = params.scaleX
            view.scaleY = params.scaleY
        }
    }

    // ==================== 手势拖拽 → ratio 联动 ====================

    /**
     * 根据手势拖拽进度直接设置 ratio 并应用属性
     * 这是对标 Poweramp 的拖拽场景：e4.s(ratio) 被拖拽直接调用
     */
    private fun applyDragRatio(fromScene: Scene, toScene: Scene, ratio: Float) {
        val params = activeAnimParams ?: buildAnimParams(fromScene, toScene)
        if (params.isEmpty()) return
        this.fromScene = fromScene
        this.toScene = toScene
        this.activeAnimParams = params
        this.transitionRatio = ratio
        applyRatio(params, ratio)
    }

    // ==================== 封面拖拽 API（对标 Poweramp c0 + r 模式） ====================

    private var isCoverDragging = false

    /**
     * 开始封面拖拽（对标 c0.m2964 + r.P）
     * 从 PLAYER 场景拖向 MAIN 场景，构建动画参数
     */
    fun startCoverDrag() {
        if (currentScene != Scene.PLAYER && currentScene != Scene.LYRIC) return
        sceneAnimGeneration++ // 使旧动画的延迟回调失效
        sceneAnimator?.cancel()
        val from = currentScene
        val to = Scene.MAIN
        activeAnimParams = buildAnimParams(from, to)
        fromScene = from
        toScene = to
        transitionRatio = 0f
        isTransitioning = true
        isCoverDragging = true
    }

    /**
     * 更新封面拖拽进度（对标 c0.mo2961 + r.mo1261）
     * @param ratio 0=PLAYER，1=MAIN
     */
    fun updateCoverDrag(ratio: Float) {
        if (!isCoverDragging) return
        val clamped = ratio.coerceIn(0f, 1f)
        transitionRatio = clamped
        activeAnimParams?.let { applyRatio(it, clamped) }
        onTransitionProgress?.invoke(toScene, clamped)
    }

    /**
     * 结束封面拖拽（对标 c0.m2964 + c0.X）
     * @param shouldClose 是否应关闭到 MAIN 场景
     * @param duration 动画时长
     */
    fun endCoverDrag(shouldClose: Boolean, duration: Long = SCENE_ANIM_DURATION) {
        if (!isCoverDragging) return
        isCoverDragging = false

        val targetScene = if (shouldClose) Scene.MAIN else fromScene
        val endRatio = if (shouldClose) 1f else 0f
        val startRatio = transitionRatio

        val params = activeAnimParams
        if (params == null || params.isEmpty()) {
            // 没有动画参数，直接完成过渡
            val oldScene = currentScene
            currentScene = targetScene
            transitionRatio = 0f
            isTransitioning = false
            activeAnimParams = null
            switchToSceneSilent(targetScene)
            // 延迟触发场景变化回调，确保状态已清理
            post {
                try {
                    onSceneChanged?.invoke(targetScene, currentScene)
                } catch (e: Exception) {
                    Log.e("SceneTransition", "endCoverDrag onSceneChanged error", e)
                }
            }
            return
        }
        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = (duration * ratioDelta).toLong().coerceAtLeast(80L)

        val animator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = animDuration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedValue as Float
                applyRatio(params, transitionRatio)
                onTransitionProgress?.invoke(targetScene, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        // 动画被取消（新动画启动），只清理状态，不触发onSceneChanged
                        Log.d("SceneTransition", "endCoverDrag cancelled, skipping onSceneChanged")
                        isTransitioning = false
                        activeAnimParams = null
                        return
                    }
                    var capturedOldScene = currentScene
                    try {
                        applyRatio(params, endRatio)
                        if (endRatio == 0f) {
                            for (ap in params) {
                                if ((ap.flags and PropFlag.VISIBILITY) != 0) {
                                    ap.view.visibility = ap.fromVisibility
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SceneTransition", "endCoverDrag applyRatio error", e)
                    } finally {
                        capturedOldScene = currentScene
                        currentScene = targetScene
                        transitionRatio = 0f
                        isTransitioning = false
                        activeAnimParams = null
                        Log.d("SceneTransition", "endCoverDrag end: oldScene=$capturedOldScene → currentScene=$currentScene, endRatio=$endRatio")
                    }
                    // 强制应用目标场景的最终状态，确保所有 View 可见性正确
                    if (endRatio == 1f) {
                        switchToSceneSilent(targetScene)
                    }
                    // 延迟触发场景变化回调，确保状态已清理
                    val oldSceneForCallback = capturedOldScene
                    val gen = sceneAnimGeneration
                    post {
                        // 检查是否被新动画取代
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "endCoverDrag onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(targetScene, oldSceneForCallback)
                        } catch (e: Exception) {
                            Log.e("SceneTransition", "endCoverDrag onSceneChanged error", e)
                        }
                    }
                }
            })
            start()
        }
        sceneAnimator = animator
        val safetyGen = sceneAnimGeneration
        // 安全机制：如果动画回调未触发，强制清除状态
        postDelayed({
            if (isTransitioning && sceneAnimGeneration == safetyGen) {
                Log.w("SceneTransition", "endCoverDrag safety: force clearing isTransitioning")
                isTransitioning = false
                activeAnimParams = null
                sceneAnimator = null
                switchToSceneSilent(targetScene)
            }
        }, animDuration + 500L)
    }

    // ==================== 封面上滑手势（PLAYER↔LYRIC） ====================

    private var isCoverSwipeUpDragging = false

    /**
     * 开始封面上滑拖拽（PLAYER→LYRIC）或下滑拖拽（LYRIC→PLAYER）
     * 与 startCoverDrag 同模式，但目标是 LYRIC 场景
     */
    fun startCoverSwipeUpDrag(from: Scene = Scene.PLAYER, to: Scene = Scene.LYRIC) {
        if (currentScene != from && currentScene != to) return
        sceneAnimGeneration++ // 使旧动画的延迟回调失效
        sceneAnimator?.cancel()
        val actualFrom = currentScene
        val actualTo = if (actualFrom == from) to else from
        Log.d("SceneTransition", "startCoverSwipeUpDrag: actualFrom=$actualFrom → actualTo=$actualTo")
        if (actualFrom == Scene.PLAYER && actualTo == Scene.LYRIC) onPreparePlayerToLyric?.invoke()
        activeAnimParams = buildAnimParams(actualFrom, actualTo)
        fromScene = actualFrom
        toScene = actualTo
        transitionRatio = 0f
        isTransitioning = true
        isCoverSwipeUpDragging = true
    }

    /**
     * 更新封面上滑拖拽进度
     * @param ratio 0=fromScene，1=toScene
     */
    fun updateCoverSwipeUpDrag(ratio: Float) {
        if (!isCoverSwipeUpDragging) return
        val clamped = ratio.coerceIn(0f, 1f)
        transitionRatio = clamped
        activeAnimParams?.let { applyRatio(it, clamped) }
        onTransitionProgress?.invoke(toScene, clamped)
    }

    /**
     * 结束封面上滑拖拽
     * @param shouldOpen true=过渡到toScene，false=回到fromScene
     */
    fun endCoverSwipeUpDrag(shouldOpen: Boolean, duration: Long = SCENE_ANIM_DURATION) {
        if (!isCoverSwipeUpDragging) return
        isCoverSwipeUpDragging = false

        val targetScene = if (shouldOpen) toScene else fromScene
        val endRatio = if (shouldOpen) 1f else 0f
        val startRatio = transitionRatio

        Log.d("SceneTransition", "endCoverSwipeUpDrag: shouldOpen=$shouldOpen, fromScene=$fromScene, toScene=$toScene, targetScene=$targetScene, startRatio=$startRatio, endRatio=$endRatio")

        val params = activeAnimParams
        if (params == null || params.isEmpty()) {
            Log.w("SceneTransition", "endCoverSwipeUpDrag: activeAnimParams is NULL or empty, completing transition directly")
            // 没有动画参数，直接完成过渡
            val oldScene = currentScene
            currentScene = targetScene
            transitionRatio = 0f
            isTransitioning = false
            activeAnimParams = null
            switchToSceneSilent(targetScene)
            // 延迟触发场景变化回调，确保状态已清理
            post {
                try {
                    onSceneChanged?.invoke(targetScene, currentScene)
                } catch (e: Exception) {
                    Log.e("SceneTransition", "endCoverSwipeUpDrag onSceneChanged error", e)
                }
            }
            return
        }
        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = (duration * ratioDelta).toLong().coerceAtLeast(80L)

        val animator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = animDuration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedValue as Float
                applyRatio(params, transitionRatio)
                onTransitionProgress?.invoke(targetScene, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        Log.d("SceneTransition", "endCoverSwipeUpDrag cancelled, skipping onSceneChanged")
                        isTransitioning = false
                        activeAnimParams = null
                        return
                    }
                    var capturedOldScene = currentScene
                    try {
                        applyRatio(params, endRatio)
                        if (endRatio == 0f) {
                            for (ap in params) {
                                if ((ap.flags and PropFlag.VISIBILITY) != 0) {
                                    ap.view.visibility = ap.fromVisibility
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SceneTransition", "endCoverSwipeUpDrag applyRatio error", e)
                    } finally {
                        capturedOldScene = currentScene
                        currentScene = targetScene
                        transitionRatio = 0f
                        isTransitioning = false
                        activeAnimParams = null
                        Log.d("SceneTransition", "endCoverSwipeUpDrag end: oldScene=$capturedOldScene → currentScene=$currentScene, endRatio=$endRatio")
                    }
                    // 延迟触发场景变化回调，确保状态已清理
                    val oldSceneForCallback = capturedOldScene
                    val gen = sceneAnimGeneration
                    post {
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "endCoverSwipeUpDrag onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(targetScene, oldSceneForCallback)
                        } catch (e: Exception) {
                            Log.e("SceneTransition", "endCoverSwipeUpDrag onSceneChanged error", e)
                        }
                    }
                }
            })
            start()
        }
        sceneAnimator = animator
        val safetyGen = sceneAnimGeneration
        // 安全机制：如果动画回调未触发，强制清除状态
        postDelayed({
            if (isTransitioning && sceneAnimGeneration == safetyGen) {
                Log.w("SceneTransition", "endCoverSwipeUpDrag safety: force clearing isTransitioning")
                isTransitioning = false
                activeAnimParams = null
                sceneAnimator = null
                switchToSceneSilent(targetScene)
            }
        }, animDuration + 500L)
    }

    // ==================== View 绑定（手势处理用） ====================
    private var navHostFragment: View? = null

    private var playBgView: View? = null
    private var miniPlayerBar: View? = null
    private var lyricContentContainer: View? = null
    private var lyricBgView: View? = null
    private var lyricMainLayer: View? = null

    fun bindViews(
        navHostFragment: View,
        playBgView: View,
        miniPlayerBar: View,
        lyricContentContainer: View,
        lyricBgView: View,
        lyricMainLayer: View
    ) {
        this.navHostFragment = navHostFragment
        this.playBgView = playBgView
        this.miniPlayerBar = miniPlayerBar
        this.lyricContentContainer = lyricContentContainer
        this.lyricBgView = lyricBgView
        this.lyricMainLayer = lyricMainLayer
    }

    // ==================== 手势处理 ====================

    enum class DragState { IDLE, TRACKING, DRAGGING, SETTLING }

    private var dragState = DragState.IDLE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isHorizontalSwipe: Boolean? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var velocityTracker: VelocityTracker? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingThreshold = 1500f * density

    private var dragFromScene: Scene = Scene.MAIN
    private var dragToScene: Scene = Scene.MAIN

    /** 拖拽起点是否在左侧边缘区域 */
    private var isEdgeDrag = false

    var isDeepHomePage: Boolean = false
    var disableDeepPageSwipe: Boolean = false
    var isCurrentlyPlaying: Boolean = false

    /** 外部 overlay 显示时禁止手势拦截（如歌曲操作面板、元数据详情面板） */
    var disableGestureIntercept: Boolean = false

    // ==================== 回调 ====================
    var onSwipeBack: (() -> Unit)? = null
    var onLeftEdgeSwipe: (() -> Unit)? = null
    var onPlayerSwipeToMain: (() -> Unit)? = null
    var onHomeSwipeRightDrag: ((offset: Float) -> Unit)? = null
    var onHomeSwipeRightRelease: ((shouldOpen: Boolean) -> Unit)? = null

    private var isLyricAtTop = false

    fun setLyricAtTopBoundary(atTop: Boolean) {
        isLyricAtTop = atTop
    }

    // ==================== 触摸拦截 ====================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragState == DragState.SETTLING) return false
        if (isTransitioning && ev.actionMasked == MotionEvent.ACTION_DOWN) return false
        if (isCoverSwipeUpDragging) return false
        if (disableGestureIntercept) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!canStartDrag(ev.rawX, ev.rawY)) return false
                dragStartX = ev.rawX
                dragStartY = ev.rawY
                isHorizontalSwipe = null
                isEdgeDrag = ev.rawX < LEFT_EDGE_ZONE_DP * density
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragState == DragState.DRAGGING) return true
                val dx = ev.rawX - dragStartX
                val dy = ev.rawY - dragStartY
                if (dragState == DragState.IDLE && isHorizontalSwipe == null
                    && abs(dx) + abs(dy) > touchSlop) {
                    dragState = DragState.TRACKING
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(ev)
                }
                if (isHorizontalSwipe == null && abs(dx) + abs(dy) > touchSlop) {
                    val dominatedByHorizontal = if (currentScene == Scene.PLAYER || currentScene == Scene.LYRIC) {
                        abs(dx) > abs(dy) * 1.5f
                    } else {
                        abs(dx) > abs(dy)
                    }
                    isHorizontalSwipe = dominatedByHorizontal
                    if (isHorizontalSwipe == true && dx > 0) {
                        val accepted = checkSwipeAccepted(dx)
                        if (accepted) {
                            if (currentScene == Scene.PLAYER || currentScene == Scene.LYRIC) {
                                onDragStart(dx < 0)
                            }
                            dragState = DragState.DRAGGING
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                    dragState = DragState.IDLE
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragState == DragState.DRAGGING) return true
                resetTouch()
            }
        }
        return dragState == DragState.DRAGGING
    }

    /**
     * 判断当前场景下右滑是否应该被接受
     * PLAYER 场景：边缘右滑返回 MAIN
     * MAIN + 深层页面：全屏右滑返回上一级（disableDeepPageSwipe 时仅边缘）
     * MAIN + 首页：交给菜单处理（不拦截）
     */
    private fun checkSwipeAccepted(dx: Float): Boolean {
        return when (currentScene) {
            Scene.PLAYER -> isEdgeDrag && dx > 0
            Scene.LYRIC -> isEdgeDrag && dx > 0
            Scene.QUEUE -> isEdgeDrag && dx > 0
            Scene.ALBUM_DETAIL -> isEdgeDrag && dx > 0
            Scene.MAIN -> {
                if (isDeepHomePage) {
                    !disableDeepPageSwipe || isEdgeDrag
                } else {
                    dx > 0
                }
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return false
            MotionEvent.ACTION_MOVE -> {
                if (dragState != DragState.DRAGGING) return false
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                velocityTracker?.addMovement(ev)
                handleDragMove(ev)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragState == DragState.DRAGGING) {
                    velocityTracker?.addMovement(ev)
                    handleDragRelease()
                }
                resetTouch()
                return dragState != DragState.IDLE
            }
        }
        return false
    }

    private fun canStartDrag(x: Float, y: Float): Boolean {
        if (x > width - EDGE_EXCLUSION_DP * density) return false
        if (isTransitioning) return false
        return true
    }

    /**
     * 拖拽开始：确定拖拽方向并构建动画参数
     * 对标 Poweramp 的 e4.s() 调用前准备
     */
    private fun onDragStart(directionLeft: Boolean) {
        // 取消正在进行的动画
        sceneAnimator?.cancel()
        isTransitioning = false

        Log.d("SceneTransition", "onDragStart: currentScene=$currentScene, directionLeft=$directionLeft, lyricEnabled=$lyricEnabled")

        when (currentScene) {
            Scene.PLAYER -> {
                if (directionLeft) {
                    dragFromScene = Scene.PLAYER
                    dragToScene = Scene.LYRIC
                    onPreparePlayerToLyric?.invoke()
                } else if (!directionLeft) {
                    dragFromScene = Scene.PLAYER
                    dragToScene = Scene.MAIN
                    onPreparePlayerToMain?.invoke()
                }
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
                Log.d("SceneTransition", "onDragStart PLAYER: dragFromScene=$dragFromScene → dragToScene=$dragToScene")
            }
            Scene.LYRIC -> {
                dragFromScene = Scene.LYRIC
                dragToScene = Scene.PLAYER
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.QUEUE -> {
                dragFromScene = Scene.QUEUE
                dragToScene = Scene.PLAYER
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.ALBUM_DETAIL -> {
                dragFromScene = Scene.ALBUM_DETAIL
                dragToScene = Scene.PLAYER
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.MAIN -> { /* 右滑 → 触发菜单，不走场景动画 */ }
        }
    }

    /**
     * 拖拽中：根据手指位移计算 ratio 并直接应用属性
     * 对标 Poweramp e4.s(ratio, ...) — 拖拽直接驱动场景比例
     */
    private fun handleDragMove(ev: MotionEvent) {
        val dx = ev.rawX - dragStartX

        when (currentScene) {
            Scene.PLAYER -> {
                if (dx < 0) {
                    val ratio = (-dx / width.toFloat()).coerceIn(0f, 1f)
                    if (activeAnimParams != null) {
                        transitionRatio = ratio
                        applyRatio(activeAnimParams!!, ratio)
                    }
                } else if (dx > 0) {
                    val ratio = (dx / width.toFloat()).coerceIn(0f, 1f)
                    if (activeAnimParams != null) {
                        transitionRatio = ratio
                        applyRatio(activeAnimParams!!, ratio)
                    }
                }
            }
            Scene.LYRIC -> {
                if (dx > 0) {
                    val ratio = (dx / width.toFloat()).coerceIn(0f, 1f)
                    if (activeAnimParams != null) {
                        transitionRatio = ratio
                        applyRatio(activeAnimParams!!, ratio)
                    }
                }
            }
            Scene.QUEUE, Scene.ALBUM_DETAIL -> {
                if (dx > 0) {
                    val ratio = (dx / width.toFloat()).coerceIn(0f, 1f)
                    if (activeAnimParams != null) {
                        transitionRatio = ratio
                        applyRatio(activeAnimParams!!, ratio)
                    }
                }
            }
            Scene.MAIN -> {
                if (isDeepHomePage) {
                    if (dx > 0) {
                        val offset = (dx / width.toFloat()).coerceIn(0f, 0.3f)
                        navHostFragment?.translationX = offset * width * 0.15f
                    }
                } else {
                    if (dx > 0) {
                        val offset = (dx / width.toFloat()).coerceIn(0f, 1f)
                        onHomeSwipeRightDrag?.invoke(offset)
                    }
                }
            }
        }
    }

    /**
     * 拖拽释放：从当前 ratio 平滑动画到目标场景
     * 对标 Poweramp b4：从 currentRatio 动画到 targetRatio（0 或 1）
     */
    private fun handleDragRelease() {
        dragState = DragState.SETTLING
        velocityTracker?.computeCurrentVelocity(1000)
        val vx = velocityTracker?.xVelocity ?: 0f
        val isFlingLeft = vx < -flingThreshold
        val isFlingRight = vx > flingThreshold

        Log.d("SceneTransition", "handleDragRelease: currentScene=$currentScene, dragFromScene=$dragFromScene, dragToScene=$dragToScene, transitionRatio=$transitionRatio, vx=$vx, isFlingLeft=$isFlingLeft, isFlingRight=$isFlingRight")

        when (currentScene) {
            Scene.PLAYER -> {
                val shouldGoToTarget = transitionRatio > SWIPE_THRESHOLD_RATIO ||
                    (dragToScene == Scene.MAIN && isFlingRight) ||
                    (dragToScene == Scene.LYRIC && isFlingLeft)

                Log.d("SceneTransition", "handleDragRelease PLAYER: dragToScene=$dragToScene, shouldGoToTarget=$shouldGoToTarget, ratio=$transitionRatio, threshold=$SWIPE_THRESHOLD_RATIO")

                if (shouldGoToTarget) {
                    if (dragToScene == Scene.MAIN) {
                        // 🚨 修复：不再重复调用 onPreparePlayerToMain 和重新 buildAnimParams
                        // 直接沿用 onDragStart 时准备好的参数，避免状态被覆盖导致跳变
                        Log.d("SceneTransition", "handleDragRelease PLAYER→MAIN settle")
                        settleFromCurrentRatio(Scene.MAIN, transitionRatio, 1f)
                    } else {
                        Log.d("SceneTransition", "handleDragRelease PLAYER→LYRIC settle")
                        settleFromCurrentRatio(dragToScene, transitionRatio, 1f)
                    }
                } else {
                    Log.d("SceneTransition", "handleDragRelease PLAYER REVERT → $dragFromScene settle(ratio=$transitionRatio, 0f)")
                    settleFromCurrentRatio(dragFromScene, transitionRatio, 0f)
                }
            }
            Scene.LYRIC -> {
                val shouldGoBack = transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight
                Log.d("SceneTransition", "handleDragRelease LYRIC: shouldGoBack=$shouldGoBack, ratio=$transitionRatio, isFlingRight=$isFlingRight")
                if (shouldGoBack) {
                    settleFromCurrentRatio(Scene.PLAYER, transitionRatio, 1f)
                } else {
                    settleFromCurrentRatio(Scene.LYRIC, transitionRatio, 0f)
                }
            }
            Scene.QUEUE, Scene.ALBUM_DETAIL -> {
                val shouldGoBack = transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight
                if (shouldGoBack) {
                    settleFromCurrentRatio(Scene.PLAYER, transitionRatio, 1f)
                } else {
                    settleFromCurrentRatio(currentScene, transitionRatio, 0f)
                }
            }
            Scene.MAIN -> {
                if (isDeepHomePage) {
                    val dx = lastRawX - dragStartX
                    if (dx > width * 0.2f || isFlingRight) {
                        onSwipeBack?.invoke()
                    }
                    navHostFragment?.translationX = 0f
                } else {
                    onHomeSwipeRightRelease?.invoke(isFlingRight)
                }
                dragState = DragState.IDLE
            }
        }
    }

    /**
     * 从当前 ratio 平滑过渡到目标 ratio（对标 b4.H(double d)）
     * 动画时长根据剩余比例动态计算
     */
    private fun settleFromCurrentRatio(
        targetScene: Scene,
        startRatio: Float,
        endRatio: Float
    ) {
        val params = activeAnimParams ?: run {
            Log.w("SceneTransition", "settleFromCurrentRatio: activeAnimParams is NULL, returning early! targetScene=$targetScene")
            return
        }

        Log.d("SceneTransition", "settleFromCurrentRatio: currentScene=$currentScene, targetScene=$targetScene, startRatio=$startRatio, endRatio=$endRatio, params=${params.size} views")

        // 🚨 修复 1：如果起始和目标比例一致，直接完成，防止 0 时长动画导致系统不回调 onAnimationEnd
        if (startRatio == endRatio) {
            applyRatio(params, endRatio)
            val oldScene = currentScene
            if (endRatio >= 1f) {
                currentScene = targetScene
            }
            transitionRatio = 0f
            isTransitioning = false
            activeAnimParams = null
            dragState = DragState.IDLE
            // 延迟触发场景变化回调，确保状态已清理
            val gen = sceneAnimGeneration
            post {
                if (sceneAnimGeneration != gen) return@post
                try {
                    onSceneChanged?.invoke(currentScene, oldScene)
                } catch (e: Exception) {
                    Log.e("SceneTransition", "settleFromCurrentRatio onSceneChanged error", e)
                }
            }
            return
        }

        isTransitioning = true
        val ratioDelta = abs(endRatio - startRatio)
        val duration = (SCENE_ANIM_DURATION * ratioDelta).toLong().coerceAtLeast(100L)

        val gen = sceneAnimGeneration
        val animator = ValueAnimator.ofFloat(startRatio, endRatio).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedValue as Float
                applyRatio(params, transitionRatio)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        Log.d("SceneTransition", "settleFromCurrentRatio cancelled, skipping onSceneChanged")
                        isTransitioning = false
                        activeAnimParams = null
                        dragState = DragState.IDLE
                        return
                    }
                    try {
                        applyRatio(params, endRatio)
                    } catch (e: Exception) {
                        Log.e("SceneTransition", "settleFromCurrentRatio applyRatio error", e)
                    } finally {
                        val oldScene = currentScene
                        if (endRatio >= 1f) {
                            currentScene = targetScene
                        }
                        Log.d("SceneTransition", "settleFromCurrentRatio end: oldScene=$oldScene → currentScene=$currentScene, endRatio=$endRatio, switched=${endRatio >= 1f}")
                        transitionRatio = 0f
                        isTransitioning = false
                        activeAnimParams = null
                        dragState = DragState.IDLE
                    }
                    // 延迟触发场景变化回调，确保状态已清理
                    val oldSceneForCallback = if (endRatio >= 1f) targetScene else currentScene
                    post {
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "settleFromCurrentRatio onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(currentScene, oldSceneForCallback)
                        } catch (e: Exception) {
                            Log.e("SceneTransition", "settleFromCurrentRatio onSceneChanged error", e)
                        }
                    }
                }
            })
            start()
        }
        sceneAnimator = animator

        // 🚨 修复 2：安全机制，如果动画回调因系统Bug丢失，强制复位，防止界面永久卡死
        postDelayed({
            if (isTransitioning && sceneAnimGeneration == gen) {
                Log.w("SceneTransition", "settleFromCurrentRatio safety: force clearing isTransitioning")
                isTransitioning = false
                activeAnimParams = null
                sceneAnimator = null
                switchToSceneSilent(targetScene)
            }
        }, duration + 500L)
    }

    private fun resetTouch() {
        dragState = DragState.IDLE
        isHorizontalSwipe = null
        isEdgeDrag = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 从后台恢复时重置所有交互状态，防止触摸事件被卡住
     */
    fun resetInteractionState() {
        resetTouch()
        isCoverDragging = false
        isCoverSwipeUpDragging = false
        sceneAnimator?.cancel()
        sceneAnimator = null
        isTransitioning = false
        activeAnimParams = null
    }

    /**
     * 强制重新应用当前场景的所有视图状态（包括 onSceneChanged 回调）。
     * 用于从后台恢复时，确保所有视图（包括不在 sceneRegistry 中的视图）
     * 都被正确设置。
     */
    fun forceReapplyCurrentScene() {
        val scene = currentScene
        // 先应用 sceneRegistry 中的视图参数
        sceneAnimator?.cancel()
        for ((viewId, sceneMap) in sceneRegistry) {
            val view = findViewById<View>(viewId) ?: continue
            val params = sceneMap[scene] ?: continue
            applyParamsImmediate(view, params)
        }
        transitionRatio = 0f
        isTransitioning = false
        activeAnimParams = null
        // 强制触发 onSceneChanged 回调，恢复所有依赖回调管理的视图状态
        onSceneChanged?.invoke(scene, scene)
    }

    // ==================== 便捷 API ====================

    fun openPlayPage(animated: Boolean = true) {
        if (currentScene != Scene.MAIN) return
        if (animated) {
            onPrepareMainToPlayer?.invoke()
            transitionToScene(Scene.PLAYER)
        } else {
            switchToSceneSilent(Scene.PLAYER)
        }
    }

    fun closePlayPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        if (animated) transitionToScene(Scene.MAIN) else switchToSceneSilent(Scene.MAIN)
    }

    /**
     * 关闭播放页并执行封面对齐动画（从当前位置过渡到列表封面位置）
     * 外部在调用前应先通过 onPreparePlayerToMain 注册封面参数
     */
    fun closePlayPageWithCoverAlign(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        if (animated) {
            onPreparePlayerToMain?.invoke()
            transitionToScene(Scene.MAIN)
        } else {
            switchToSceneSilent(Scene.MAIN)
        }
    }

    fun openLyricPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToLyric?.invoke()
        if (animated) transitionToScene(Scene.LYRIC) else switchToSceneSilent(Scene.LYRIC)
    }

    fun closeLyricPage(animated: Boolean = true) {
        if (currentScene != Scene.LYRIC) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun backToHome(animated: Boolean) {
        when (currentScene) {
            Scene.LYRIC -> {
                closeLyricPage(animated)
                if (animated) postDelayed({ closePlayPage(animated) }, 350)
                else closePlayPage(false)
            }
            Scene.QUEUE -> {
                closeQueuePage(animated)
                if (animated) postDelayed({ closePlayPage(animated) }, 350)
                else closePlayPage(false)
            }
            Scene.ALBUM_DETAIL -> {
                closeAlbumDetailPage(animated)
                if (animated) postDelayed({ closePlayPage(animated) }, 350)
                else closePlayPage(false)
            }
            Scene.PLAYER -> closePlayPage(animated)
            Scene.MAIN -> {}
        }
    }

    fun openQueuePage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToQueue?.invoke()
        if (animated) transitionToScene(Scene.QUEUE) else switchToSceneSilent(Scene.QUEUE)
    }

    fun closeQueuePage(animated: Boolean = true) {
        if (currentScene != Scene.QUEUE) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun openAlbumDetailPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToAlbumDetail?.invoke()
        if (animated) transitionToScene(Scene.ALBUM_DETAIL) else switchToSceneSilent(Scene.ALBUM_DETAIL)
    }

    fun closeAlbumDetailPage(animated: Boolean = true) {
        if (currentScene != Scene.ALBUM_DETAIL) return
        if (animated) transitionToScene(Scene.PLAYER) else switchToSceneSilent(Scene.PLAYER)
    }

    fun syncRotationState(isPlaying: Boolean) {
        isCurrentlyPlaying = isPlaying
    }

    fun stopAllRotations() {}

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sceneAnimator?.cancel()
        sceneAnimator = null
        resetTouch()
        velocityTracker?.recycle()
        velocityTracker = null
        
        immersiveBackground = null
        originalCoverImageView = null
        miniCoverView = null // 新增
        playBgScrim = null
        navHostFragment = null
        playBgView = null
        miniPlayerBar = null
        lyricContentContainer = null
        lyricBgView = null
        lyricMainLayer = null
    }

    companion object {
        private const val EDGE_EXCLUSION_DP = 20f
        private const val LEFT_EDGE_ZONE_DP = 24f
        private const val SWIPE_THRESHOLD_RATIO = 0.30f

        // Poweramp-style decelerate + 500ms for calmer transitions
        private const val PAGE_DECELERATE = 2.0f
        private const val SCENE_ANIM_DURATION = 500L
    }
}
