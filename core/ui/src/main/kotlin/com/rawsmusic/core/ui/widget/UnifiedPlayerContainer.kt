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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.ui.util.AdaptivePadTransformation
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.foundation.gestures.detectVerticalDragGestures

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
        ALBUM_DETAIL,   // 专辑详情界面
        EFFECTS         // 音效/EQ界面
    }

    var currentScene: Scene = Scene.MAIN
        private set

    // ==================== Compose 状态属性 ====================
    /** 当前场景的 Compose 可观察状态 */
    var composeCurrentScene by mutableStateOf(Scene.MAIN)
        private set

    /** 是否正在过渡的 Compose 可观察状态 */
    var composeIsTransitioning by mutableStateOf(false)
        private set

    /** 过渡进度的 Compose 可观察状态 (0..1) */
    var composeTransitionProgress by mutableFloatStateOf(0f)
        private set

    /** 是否正在播放的 Compose 可观察状态 */
    var composeIsPlaying by mutableStateOf(false)

    // ==================== 沉浸模式 ====================
    private var immersiveBackground: ImmersiveBackgroundView? = null
    private var originalCoverImageView: View? = null
    private var miniCoverView: View? = null // 主界面专属的迷你封面层级
    private var playBgScrim: View? = null
    var isImmersiveEnabled = true
        private set
    private var isDarkMode = true
    var isMiniCoverEnabled = true
        private set
    var isDefaultBackgroundEnabled = false
        private set
    private var immersiveViewId = View.NO_ID
    private var isApplyingImmersiveParams = false

    fun initImmersiveViews(
        immersiveBg: ImmersiveBackgroundView,
        coverImg: View,
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
                    Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = immersiveAlpha, visibility = immersiveVisibility),
                    Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = immersiveAlpha, visibility = immersiveVisibility)
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
                        Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = 0f, visibility = View.INVISIBLE),
                        Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = 0f, visibility = View.INVISIBLE)
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
                coverScenes[Scene.EFFECTS] = SceneParams(Scene.EFFECTS, alpha = playerCoverAlpha, visibility = playerCoverVisibility)

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
                        Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = scrimAlpha, visibility = scrimVisibility),
                        Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = scrimAlpha, visibility = scrimVisibility)
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
        // 默认背景模式：强制隐藏所有沉浸/封面背景视图
        if (isDefaultBackgroundEnabled) {
            immersiveBackground?.visibility = View.GONE
            immersiveBackground?.alpha = 0f
            // 封面只在播放页/歌词页等非主界面场景显示
            val coverVisible = currentScene != Scene.MAIN
            originalCoverImageView?.visibility = if (coverVisible) View.VISIBLE else View.INVISIBLE
            originalCoverImageView?.alpha = if (coverVisible) 1f else 0f
            miniCoverView?.visibility = View.GONE
            miniCoverView?.alpha = 0f
            playBgScrim?.visibility = View.GONE
            return
        }

        immersiveBackground?.let { bg ->
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

    fun updateDefaultBackgroundEnabled(enabled: Boolean) {
        if (isDefaultBackgroundEnabled == enabled) {
            forceApplyImmersiveVisibility()
            return
        }
        isDefaultBackgroundEnabled = enabled
        applyImmersiveSceneParams()
    }

    fun updateImmersiveCover(path: String?) {
        originalCoverImageView?.let { coverImg ->
            when (coverImg) {
                is CoverImageView -> coverImg.loadCover(path)
                is ImageView -> BitmapProvider.load(
                    key = path ?: return,
                    imageView = coverImg,
                    targetWidth = coverImg.width.coerceAtLeast(512),
                    targetHeight = coverImg.height.coerceAtLeast(512)
                )
            }
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
        immersiveBackground?.isMiniCoverEnabled = enabled
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.isMiniCoverEnabled = enabled
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
        miniCoverView?.let { miniCover ->
            if (miniCover is ImmersiveBackgroundView) {
                miniCover.isImmersiveEnabled = isImmersive
            }
        }
        immersiveBackground?.isMiniCoverEnabled = isMiniCoverEnabled
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
        const val ROTATION = 1 shl 7        // 128   - rotation 参与
        const val ALPHA_MULTIPLIER = 1 shl 8 // 256  - alpha 乘以场景系数
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
        val cornerRadius: Float = -1f,
        val rotation: Float = 0f,
        val alphaMultiplier: Float = 1f
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
        var fromAlpha: Float = 1f
        var fromScaleX: Float = 1f
        var fromScaleY: Float = 1f
        var fromTranslationX: Float = 0f
        var fromTranslationY: Float = 0f
        var fromVisibility: Int = View.VISIBLE
        var fromCornerRadius: Float = -1f
        var fromRotation: Float = 0f
        var fromAlphaMultiplier: Float = 1f

        var toAlpha: Float = 1f
        var toScaleX: Float = 1f
        var toScaleY: Float = 1f
        var toTranslationX: Float = 0f
        var toTranslationY: Float = 0f
        var toVisibility: Int = View.VISIBLE
        var toCornerRadius: Float = -1f
        var toRotation: Float = 0f
        var toAlphaMultiplier: Float = 1f
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

    // ==================== Compose 可观察的场景注册表 ====================
    /** Compose 可观察的场景注册表，key 为字符串标识 */
    private val composeSceneRegistry = mutableStateMapOf<String, MutableMap<Scene, SceneParams>>()

    /**
     * 注册 Compose 场景参数
     * @param key 场景参数的唯一标识（如 "cover", "title", "lyric_bg"）
     * @param scene 目标场景
     * @param params 场景参数
     */
    fun registerComposeSceneParams(key: String, scene: Scene, params: SceneParams) {
        val viewMap = composeSceneRegistry.getOrPut(key) { mutableMapOf() }
        viewMap[scene] = params
    }

    /**
     * 批量注册 Compose 场景参数
     */
    fun registerComposeViewScenes(key: String, vararg sceneParams: Pair<Scene, SceneParams>) {
        sceneParams.forEach { (scene, params) ->
            registerComposeSceneParams(key, scene, params)
        }
    }

    /**
     * 获取指定 key 的当前场景参数
     */
    fun getComposeSceneParams(key: String): SceneParams? {
        return composeSceneRegistry[key]?.get(composeCurrentScene)
    }

    /**
     * 获取指定 key 在指定场景的参数
     */
    fun getComposeSceneParams(key: String, scene: Scene): SceneParams? {
        return composeSceneRegistry[key]?.get(scene)
    }

    /**
     * 获取指定 key 的插值参数（用于过渡动画）
     */
    fun getInterpolatedSceneParams(key: String): SceneParams? {
        val sceneMap = composeSceneRegistry[key] ?: return null
        if (!composeIsTransitioning) {
            return sceneMap[composeCurrentScene]
        }
        val fromParams = sceneMap[fromScene] ?: return sceneMap[composeCurrentScene]
        val toParams = sceneMap[toScene] ?: return sceneMap[composeCurrentScene]
        return lerpSceneParams(fromParams, toParams, composeTransitionProgress)
    }

    /**
     * 线性插值两个 SceneParams
     */
    private fun lerpSceneParams(from: SceneParams, to: SceneParams, fraction: Float): SceneParams {
        val f = fraction.coerceIn(0f, 1f)
        return SceneParams(
            scene = if (f < 0.5f) from.scene else to.scene,
            alpha = from.alpha + (to.alpha - from.alpha) * f,
            translationX = from.translationX + (to.translationX - from.translationX) * f,
            translationY = from.translationY + (to.translationY - from.translationY) * f,
            scaleX = from.scaleX + (to.scaleX - from.scaleX) * f,
            scaleY = from.scaleY + (to.scaleY - from.scaleY) * f,
            visibility = if (f < 0.5f) from.visibility else to.visibility,
            cornerRadius = from.cornerRadius + (to.cornerRadius - from.cornerRadius) * f,
            rotation = from.rotation + (to.rotation - from.rotation) * f,
            alphaMultiplier = from.alphaMultiplier + (to.alphaMultiplier - from.alphaMultiplier) * f
        )
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

    /** PLAYER→MAIN 过渡前的封面参数准备回调（外部注册封面到列表位置的参数，完成后调用 onReady） */
    var onPreparePlayerToMain: ((onReady: () -> Unit) -> Unit)? = null

    /** PLAYER→LYRIC 过渡前的封面参数准备回调（外部注册封面到歌词页位置的参数） */
    var onPreparePlayerToLyric: (() -> Unit)? = null

    /** PLAYER→QUEUE 过渡前的准备回调 */
    var onPreparePlayerToQueue: (() -> Unit)? = null

    /** PLAYER→ALBUM_DETAIL 过渡前的准备回调 */
    var onPreparePlayerToAlbumDetail: (() -> Unit)? = null

    /** PLAYER→EFFECTS 过渡前的准备回调 */
    var onPreparePlayerToEffects: (() -> Unit)? = null

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

        // 🚨 同步锁：动画开始前强制隐藏旧场景的View，避免重叠显示
        // includeExiting=true：程序化转场需要立即隐藏退出方向的View，防止新旧场景同时半透明
        lockOldScene(from, to, includeExiting = true)
        // 兜底：强制隐藏所有不属于目标场景的View，确保无遗漏
        forceHideNonTargetScene(to)

        val params = buildAnimParams(from, to)
        Log.d("SceneTransition", "transitionToScene: built ${params.size} anim params for $from → $to")
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
        // 同步 Compose 状态
        composeIsTransitioning = true
        composeTransitionProgress = 0f

        val gen = sceneAnimGeneration
        val safetyGen = sceneAnimGeneration
        postDelayed({
            if (isTransitioning && sceneAnimGeneration == safetyGen) {
                Log.w("SceneTransition", "transitionToScene: safety timeout, force-clearing isTransitioning")
                isTransitioning = false
                dragState = DragState.IDLE
                activeAnimParams = null
            }
        }, duration + 1500)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(PAGE_DECELERATE)
            addUpdateListener { anim ->
                transitionRatio = anim.animatedFraction
                applyRatio(params, transitionRatio)
                onTransitionProgress?.invoke(to, transitionRatio)
                // 同步 Compose 状态
                composeTransitionProgress = transitionRatio
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
                    }
                    val capturedOldScene = currentScene
                    currentScene = targetScene
                    transitionRatio = 0f
                    isTransitioning = false
                    activeAnimParams = null
                    // 同步 Compose 状态
                    composeCurrentScene = targetScene
                    composeIsTransitioning = false
                    composeTransitionProgress = 0f
                    Log.d("SceneTransition", "transitionToScene end: oldScene=$capturedOldScene → currentScene=$currentScene")
                    post {
                        if (sceneAnimGeneration != gen) {
                            Log.d("SceneTransition", "transitionToScene onSceneChanged skipped: generation mismatch")
                            return@post
                        }
                        try {
                            onSceneChanged?.invoke(targetScene, capturedOldScene)
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
            // 🚨 同步锁：动画开始前强制隐藏旧场景的View
            lockOldScene(currentScene, targetScene, includeExiting = true)
            // 兜底：强制隐藏所有不属于目标场景的View
            forceHideNonTargetScene(targetScene)
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
        Log.w("SceneTransition", "=== switchToSceneSilent: $currentScene -> $targetScene ===")
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
        // 同步 Compose 状态
        composeCurrentScene = targetScene
        composeIsTransitioning = false
        composeTransitionProgress = 0f
        // 仅在场景实际变化时才触发回调，避免递归调用
        if (oldScene != targetScene) {
            Log.w("SceneTransition", "=== switchToSceneSilent invoking onSceneChanged: $oldScene -> $targetScene ===")
            onSceneChanged?.invoke(targetScene, oldScene)
        }
    }

    // ==================== 同步锁机制（解决重叠显示问题） ====================

    /**
     * 动画开始前，强制隐藏旧场景的所有View（GONE + alpha=0）
     * 
     * 核心逻辑（对标 Poweramp mo7061t 的容器级隔离）：
     * - 遍历 sceneRegistry，找出只属于 fromScene 但不属于 toScene 的 View
     * - 这些 View 在动画开始前就被强制设为 GONE，避免与新场景 View 同时可见
     * - 当 includeExiting=true 时，还会锁定"退出方向"的 View（同时注册了两个场景但从可见→不可见）
     * 
     * @param fromScene 旧场景
     * @param toScene 新场景
     * @param excludeIds 不参与锁定的 View ID 集合（如共享背景）
     * @param includeExiting 是否也锁定"退出方向"的 View（用于程序化转场，拖拽手势不使用）
     */
    private fun lockOldScene(fromScene: Scene, toScene: Scene, excludeIds: Set<Int> = emptySet(), includeExiting: Boolean = false) {
        for ((viewId, sceneMap) in sceneRegistry) {
            if (viewId in excludeIds) continue
            val view = findViewById<View>(viewId) ?: continue

            val fromParams = sceneMap[fromScene]
            val toParams = sceneMap[toScene]

            if (fromParams != null && toParams == null) {
                view.alpha = 0f
                view.visibility = View.INVISIBLE
                Log.d("SceneTransition", "lockOldScene: locked viewId=$viewId (from=$fromScene only)")
            } else if (includeExiting && fromParams != null && toParams != null) {
                val isExiting = (fromParams.alpha > 0f && toParams.alpha == 0f) ||
                    (fromParams.visibility == View.VISIBLE && toParams.visibility != View.VISIBLE)
                if (isExiting) {
                    view.alpha = 0f
                    view.visibility = View.INVISIBLE
                    Log.d("SceneTransition", "lockOldScene: locked viewId=$viewId (exiting: alpha ${fromParams.alpha}→${toParams.alpha}, vis ${fromParams.visibility}→${toParams.visibility})")
                }
            }
        }

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childId = child.id
            if (childId == View.NO_ID || childId in excludeIds) continue

            val sceneMap = sceneRegistry[childId]
            if (sceneMap == null) {
                if (child.visibility == View.VISIBLE && child.alpha > 0f) {
                    Log.d("SceneTransition", "lockOldScene: unregistered viewId=$childId visibility=${child.visibility} alpha=${child.alpha}")
                }
                continue
            }

            val fromParams = sceneMap[fromScene]
            val toParams = sceneMap[toScene]

            if (fromParams != null && toParams == null) {
                child.alpha = 0f
                child.visibility = View.INVISIBLE
            } else if (includeExiting && fromParams != null && toParams != null) {
                val isExiting = (fromParams.alpha > 0f && toParams.alpha == 0f) ||
                    (fromParams.visibility == View.VISIBLE && toParams.visibility != View.VISIBLE)
                if (isExiting) {
                    child.alpha = 0f
                    child.visibility = View.INVISIBLE
                }
            }
        }
    }

    // ==================== 动画参数构建（对标 e4.m2820 捕获 from 值） ====================

    /**
     * 兜底方法：强制隐藏所有不属于目标场景的 View
     * 在 lockOldScene 之后调用，确保没有遗漏的 View 导致重叠显示
     * 
     * @param targetScene 目标场景
     */
    private fun forceHideNonTargetScene(targetScene: Scene) {
        for ((viewId, sceneMap) in sceneRegistry) {
            val view = findViewById<View>(viewId) ?: continue
            if (sceneMap[targetScene] == null) {
                // 该 View 没有注册目标场景参数 → 强制隐藏（用INVISIBLE保持View树完整，避免RecyclerView回收）
                if (view.visibility != View.INVISIBLE || view.alpha != 0f) {
                    view.alpha = 0f
                    view.visibility = View.INVISIBLE
                    Log.d("SceneTransition", "forceHideNonTargetScene: hidden viewId=$viewId (no params for $targetScene)")
                }
            }
        }
    }

    /**
     * 构建从 fromScene 到 toScene 的所有 View 动画参数
     * 捕获当前 View 属性作为 from 值，注册的目标场景属性作为 to 值
     */
    private fun buildAnimParams(fromScene: Scene, toScene: Scene): MutableList<StateAnimParams> {
        val result = mutableListOf<StateAnimParams>()

        for ((viewId, sceneMap) in sceneRegistry) {
            val view = findViewById<View>(viewId) ?: continue

            val fromParams = sceneMap[fromScene]
            val toParams = sceneMap[toScene]
            if (fromParams == null && toParams == null) continue

            Log.d("SceneTransition", "buildAnimParams: viewId=$viewId, fromScene=$fromScene, toScene=$toScene, fromParams=$fromParams, toParams=$toParams")

            val animParams = StateAnimParams(view)
            var flags = 0

            val fp = fromParams ?: captureCurrentParams(view, fromScene)
            val tp = toParams ?: SceneParams(
                scene = toScene,
                alpha = 0f,
                visibility = View.GONE
            )

            if (fp.alpha != tp.alpha) {
                flags = flags or PropFlag.ALPHA
                animParams.fromAlpha = view.alpha
                animParams.toAlpha = tp.alpha
            } else if (view.alpha != tp.alpha) {
                view.alpha = tp.alpha
            }

            if (fp.scaleX != tp.scaleX) {
                flags = flags or PropFlag.SCALE_X
                animParams.fromScaleX = view.scaleX
                animParams.toScaleX = tp.scaleX
            } else if (view.scaleX != tp.scaleX) {
                view.scaleX = tp.scaleX
            }

            if (fp.scaleY != tp.scaleY) {
                flags = flags or PropFlag.SCALE_Y
                animParams.fromScaleY = view.scaleY
                animParams.toScaleY = tp.scaleY
            } else if (view.scaleY != tp.scaleY) {
                view.scaleY = tp.scaleY
            }

            if (fp.translationX != tp.translationX) {
                flags = flags or PropFlag.TRANSLATION_X
                animParams.fromTranslationX = view.translationX
                animParams.toTranslationX = tp.translationX
            } else if (view.translationX != tp.translationX) {
                view.translationX = tp.translationX
            }

            if (fp.translationY != tp.translationY) {
                flags = flags or PropFlag.TRANSLATION_Y
                animParams.fromTranslationY = view.translationY
                animParams.toTranslationY = tp.translationY
            } else if (view.translationY != tp.translationY) {
                view.translationY = tp.translationY
            }

            val needVisAnim = fp.visibility != tp.visibility || view.visibility != tp.visibility
            if (needVisAnim) {
                flags = flags or PropFlag.VISIBILITY
                animParams.fromVisibility = view.visibility
                animParams.toVisibility = tp.visibility
            } else if (view.visibility != tp.visibility) {
                view.visibility = tp.visibility
            }

            if (fp.cornerRadius >= 0f && tp.cornerRadius >= 0f && fp.cornerRadius != tp.cornerRadius) {
                flags = flags or PropFlag.CORNER_RADIUS
                animParams.fromCornerRadius = fp.cornerRadius
                animParams.toCornerRadius = tp.cornerRadius
            }

            if (fp.rotation != tp.rotation) {
                flags = flags or PropFlag.ROTATION
                animParams.fromRotation = view.rotation
                animParams.toRotation = tp.rotation
            } else if (view.rotation != tp.rotation) {
                view.rotation = tp.rotation
            }

            if (fp.alphaMultiplier != tp.alphaMultiplier) {
                flags = flags or PropFlag.ALPHA_MULTIPLIER
                animParams.fromAlphaMultiplier = fp.alphaMultiplier
                animParams.toAlphaMultiplier = tp.alphaMultiplier
            }

            if (flags != 0) {
                animParams.flags = flags
                result.add(animParams)
                // 诊断日志：打印所有动画 View 的 from/to 值（用 logcat 过滤 CoverAnim）
                val resName = try { resources.getResourceEntryName(view.id) } catch (_: Exception) { "unknown" }
                android.util.Log.d("CoverAnim", "buildAnimParams $resName: " +
                    "from=(${animParams.fromTranslationX},${animParams.fromTranslationY} " +
                    "scale=(${animParams.fromScaleX},${animParams.fromScaleY}) " +
                    "alpha=${animParams.fromAlpha}) " +
                    "to=(${animParams.toTranslationX},${animParams.toTranslationY} " +
                    "scale=(${animParams.toScaleX},${animParams.toScaleY}) " +
                    "alpha=${animParams.toAlpha}) " +
                    "viewState=(${view.translationX},${view.translationY} " +
                    "scale=(${view.scaleX},${view.scaleY}) " +
                    "left=${view.left} top=${view.top} " +
                    "w=${view.width} h=${view.height})")
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

            val isEntering = ap.toVisibility == View.VISIBLE && ap.fromVisibility != View.VISIBLE
            val isExiting = ap.fromVisibility == View.VISIBLE && ap.toVisibility != View.VISIBLE

            if ((flags and PropFlag.VISIBILITY) != 0) {
                if (isEntering && ratio > 0f && view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                } else if (isExiting && ratio >= 1f && view.visibility != ap.toVisibility) {
                    view.visibility = ap.toVisibility
                }
            }
            if ((flags and PropFlag.VISIBILITY) == 0 && view.visibility != View.VISIBLE) {
                if (ap.toVisibility == View.VISIBLE && ratio > 0f) {
                    view.visibility = View.VISIBLE
                }
            }

            if ((flags and PropFlag.ALPHA) != 0) {
                val baseAlpha = lerp(ap.fromAlpha, ap.toAlpha, ratio)
                val multiplier = if ((flags and PropFlag.ALPHA_MULTIPLIER) != 0) {
                    lerp(ap.fromAlphaMultiplier, ap.toAlphaMultiplier, ratio)
                } else 1f
                view.alpha = baseAlpha * multiplier
            } else if ((flags and PropFlag.ALPHA_MULTIPLIER) != 0) {
                val multiplier = lerp(ap.fromAlphaMultiplier, ap.toAlphaMultiplier, ratio)
                view.alpha = ap.fromAlpha * multiplier
            }

            if ((flags and PropFlag.SCALE_X) != 0) {
                view.scaleX = lerp(ap.fromScaleX, ap.toScaleX, ratio)
            }

            if ((flags and PropFlag.SCALE_Y) != 0) {
                view.scaleY = lerp(ap.fromScaleY, ap.toScaleY, ratio)
            }

            if ((flags and PropFlag.TRANSLATION_X) != 0) {
                view.translationX = lerp(ap.fromTranslationX, ap.toTranslationX, ratio)
            }

            if ((flags and PropFlag.TRANSLATION_Y) != 0) {
                view.translationY = lerp(ap.fromTranslationY, ap.toTranslationY, ratio)
            }

            if ((flags and PropFlag.ROTATION) != 0) {
                view.rotation = lerp(ap.fromRotation, ap.toRotation, ratio)
            }

            if ((flags and PropFlag.CORNER_RADIUS) != 0) {
                val radius = lerp(ap.fromCornerRadius, ap.toCornerRadius, ratio)
                when (view) {
                    is com.google.android.material.imageview.ShapeableImageView -> {
                        view.shapeAppearanceModel = view.shapeAppearanceModel
                            .toBuilder()
                            .setAllCornerSizes(radius)
                            .build()
                    }
                    is CoverImageView -> {
                        view.cornerRadius = radius
                    }
                }
            }

            if (ratio >= 1f && (flags and PropFlag.VISIBILITY) != 0) {
                view.visibility = ap.toVisibility
            }
            else if (ratio >= 1f && ap.toVisibility == View.GONE && view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }

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

    /**
     * 速度自适应动画时长（对标 Poweramp b4 的 velocity-aware duration）
     *
     * 公式：duration = |1 / (1/baseDuration + |velocity|)|
     * - velocity=0 时退化为 baseDuration * ratioDelta
     * - velocity 越大，时长越短（fling 快速收尾）
     * - clamp 到 [VELOCITY_ADAPT_MIN_MS, VELOCITY_ADAPT_MAX_MS] 防止极端值
     */
    private fun calcVelocityAdaptedDuration(baseDuration: Long, ratioDelta: Float, velocity: Float): Long {
        if (ratioDelta <= 0f) return VELOCITY_ADAPT_MIN_MS
        val baseMs = (baseDuration * ratioDelta).coerceAtLeast(VELOCITY_ADAPT_MIN_MS.toFloat())
        if (velocity == 0f) return baseMs.toLong()
        val absVel = abs(velocity)
        val adapted = abs(1f / (1f / baseMs + absVel * VELOCITY_SENSITIVITY))
        return adapted.coerceIn(VELOCITY_ADAPT_MIN_MS.toFloat(), VELOCITY_ADAPT_MAX_MS.toFloat()).toLong()
    }

    // ==================== StateAnim 状态动画系统（对标 Poweramp q4 微交互动画） ====================

    private val stateAnimMap = mutableMapOf<Int, ValueAnimator>()

    fun startStateAnim(
        view: View,
        targetAlpha: Float? = null,
        targetScaleX: Float? = null,
        targetScaleY: Float? = null,
        targetTranslationX: Float? = null,
        targetTranslationY: Float? = null,
        targetRotation: Float? = null,
        duration: Long = STATE_ANIM_DEFAULT_DURATION,
        interpolator: android.animation.TimeInterpolator? = DecelerateInterpolator(STATE_ANIM_DECELERATE),
        onUpdate: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        val viewId = view.id
        stateAnimMap[viewId]?.cancel()
        val hasAlpha = targetAlpha != null
        val hasScaleX = targetScaleX != null
        val hasScaleY = targetScaleY != null
        val hasTransX = targetTranslationX != null
        val hasTransY = targetTranslationY != null
        val hasRotation = targetRotation != null
        val fromAlpha = if (hasAlpha) view.alpha else 0f
        val fromScaleX = if (hasScaleX) view.scaleX else 0f
        val fromScaleY = if (hasScaleY) view.scaleY else 0f
        val fromTransX = if (hasTransX) view.translationX else 0f
        val fromTransY = if (hasTransY) view.translationY else 0f
        val fromRotation = if (hasRotation) view.rotation else 0f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            if (interpolator != null) this.interpolator = interpolator
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                if (hasAlpha) view.alpha = lerp(fromAlpha, targetAlpha!!, f)
                if (hasScaleX) view.scaleX = lerp(fromScaleX, targetScaleX!!, f)
                if (hasScaleY) view.scaleY = lerp(fromScaleY, targetScaleY!!, f)
                if (hasTransX) view.translationX = lerp(fromTransX, targetTranslationX!!, f)
                if (hasTransY) view.translationY = lerp(fromTransY, targetTranslationY!!, f)
                if (hasRotation) view.rotation = lerp(fromRotation, targetRotation!!, f)
                onUpdate?.invoke(f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stateAnimMap.remove(viewId)
                    onEnd?.invoke()
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    stateAnimMap.remove(viewId)
                }
            })
            start()
        }
        stateAnimMap[viewId] = animator
    }

    fun cancelStateAnim(view: View) {
        stateAnimMap[view.id]?.cancel()
        stateAnimMap.remove(view.id)
    }

    fun cancelAllStateAnims() {
        stateAnimMap.values.forEach { it.cancel() }
        stateAnimMap.clear()
    }

    /**
     * 按钮按压弹回动画（对标 ButtonAnimHelper.pressReleaseAnim 的 StateAnim 版本）
     * scale 缩小到 pressScale 再回弹到 1.0f，带弹性效果
     */
    fun buttonPressAnim(view: View, pressScale: Float = BUTTON_PRESS_SCALE, duration: Long = BUTTON_PRESS_DURATION) {
        startStateAnim(view,
            targetScaleX = pressScale, targetScaleY = pressScale,
            duration = duration / 2,
            interpolator = AccelerateInterpolator(),
            onUpdate = { if (it >= 0.8f) cancelStateAnim(view) },
            onEnd = {
                startStateAnim(view,
                    targetScaleX = 1f, targetScaleY = 1f,
                    duration = duration,
                    interpolator = OvershootInterpolator(BUTTON_RELEASE_OVERSHOOT))
            })
    }

    /**
     * 弹性回弹动画（对标 Poweramp r1 的 cubic ease-out）
     * 原始公式：1-(1-t)³，即 3t-3t²+t³
     * 用于封面拖拽释放后的回弹和场景切换回弹
     */
    fun springSettleAnim(
        view: View,
        targetScaleX: Float = 1f,
        targetScaleY: Float = 1f,
        targetTranslationX: Float = 0f,
        targetTranslationY: Float = 0f,
        targetAlpha: Float? = null,
        duration: Long = SPRING_SETTLE_DURATION
    ) {
        startStateAnim(view,
            targetAlpha = targetAlpha,
            targetScaleX = targetScaleX,
            targetScaleY = targetScaleY,
            targetTranslationX = targetTranslationX,
            targetTranslationY = targetTranslationY,
            duration = duration,
            interpolator = CUBIC_EASE_OUT
        )
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
            if (params.cornerRadius >= 0f) {
                when (view) {
                    is com.google.android.material.imageview.ShapeableImageView -> {
                        view.shapeAppearanceModel = view.shapeAppearanceModel
                            .toBuilder()
                            .setAllCornerSizes(params.cornerRadius)
                            .build()
                    }
                    is CoverImageView -> {
                        view.cornerRadius = params.cornerRadius
                    }
                }
            }
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
     * 从当前场景拖向目标场景，构建动画参数
     * @param targetScene 目标场景。PLAYER→MAIN，LYRIC→PLAYER
     */
    fun startCoverDrag(targetScene: Scene = Scene.MAIN) {
        if (currentScene != Scene.PLAYER && currentScene != Scene.LYRIC) return
        if (currentScene == targetScene) return
        sceneAnimGeneration++ // 使旧动画的延迟回调失效
        sceneAnimator?.cancel()
        val from = currentScene
        val to = targetScene
        // 🚨 同步锁：拖拽开始时强制隐藏旧场景的View
        lockOldScene(from, to)
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
     * @param shouldClose 是否应关闭到目标场景（toScene）
     * @param duration 动画时长
     */
    fun endCoverDrag(shouldClose: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
        if (!isCoverDragging) return
        isCoverDragging = false

        val targetScene = if (shouldClose) toScene else fromScene
        val endRatio = if (shouldClose) 1f else 0f
        val startRatio = transitionRatio

        val params = activeAnimParams
        if (params == null || params.isEmpty()) {
            val oldScene = currentScene
            currentScene = targetScene
            transitionRatio = 0f
            isTransitioning = false
            activeAnimParams = null
            switchToSceneSilent(targetScene)
            post {
                try {
                    onSceneChanged?.invoke(targetScene, oldScene)
                } catch (e: Exception) {
                    Log.e("SceneTransition", "endCoverDrag onSceneChanged error", e)
                }
            }
            return
        }
        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = calcVelocityAdaptedDuration(duration, ratioDelta, velocity)

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

    /**
     * Predictive Back API: 带 swipeRight 参数的封面拖拽开始
     * 供 MainActivity.setupPredictiveBack() 调用
     * @param targetScene 目标场景。PLAYER→MAIN，LYRIC→PLAYER
     */
    fun startCoverDrag(swipeRight: Boolean, targetScene: Scene = Scene.MAIN) {
        startCoverDrag(targetScene)
    }

    /**
     * Predictive Back API: 封面拖拽进度更新
     * 供 MainActivity.setupPredictiveBack() 调用
     */
    fun updateCoverDragProgress(ratio: Float) {
        updateCoverDrag(ratio)
    }

    /**
     * Predictive Back API: 封面拖拽释放
     * 供 MainActivity.setupPredictiveBack() 调用
     */
    fun releaseCoverDrag(shouldClose: Boolean, velocity: Float) {
        endCoverDrag(shouldClose, velocity = velocity)
    }

    /**
     * Predictive Back API: 场景拖拽进度更新（QUEUE/ALBUM_DETAIL/EFFECTS/LYRIC）
     * 直接使用 ratio 驱动动画，不依赖触摸坐标
     */
    fun updateDragBackProgress(ratio: Float) {
        if (activeAnimParams == null) return
        val clamped = ratio.coerceIn(0f, 1f)
        transitionRatio = clamped
        activeAnimParams?.let { applyRatio(it, clamped) }
        onTransitionProgress?.invoke(toScene, clamped)
    }

    /**
     * Predictive Back API: 场景拖拽结束（QUEUE/ALBUM_DETAIL/EFFECTS/LYRIC）
     * 供 MainActivity.setupPredictiveBack() 调用
     */
    fun endDragBack(shouldGoBack: Boolean, velocity: Float = 0f) {
        val params = activeAnimParams
        if (params == null || params.isEmpty()) {
            isTransitioning = false
            activeAnimParams = null
            return
        }

        val startRatio = transitionRatio
        val endRatio = if (shouldGoBack) 1f else 0f
        val targetScene = if (shouldGoBack) toScene else fromScene

        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = calcVelocityAdaptedDuration(SCENE_ANIM_DURATION, ratioDelta, velocity)

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
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sceneAnimator = null
                    if (cancelled) {
                        isTransitioning = false
                        activeAnimParams = null
                        return
                    }
                    val capturedOldScene = currentScene
                    currentScene = targetScene
                    transitionRatio = 0f
                    isTransitioning = false
                    activeAnimParams = null
                    switchToSceneSilent(targetScene)
                    val gen = sceneAnimGeneration
                    post {
                        if (sceneAnimGeneration != gen) return@post
                        onSceneChanged?.invoke(targetScene, capturedOldScene)
                    }
                }
            })
            start()
        }
        sceneAnimator = animator
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
        // 🚨 同步锁：拖拽开始时强制隐藏旧场景的View
        lockOldScene(actualFrom, actualTo)
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
    fun endCoverSwipeUpDrag(shouldOpen: Boolean, duration: Long = SCENE_ANIM_DURATION, velocity: Float = 0f) {
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
            val oldSceneForCallback = oldScene
            post {
                try {
                    onSceneChanged?.invoke(targetScene, oldSceneForCallback)
                } catch (e: Exception) {
                    Log.e("SceneTransition", "endCoverSwipeUpDrag onSceneChanged error", e)
                }
            }
            return
        }
        val ratioDelta = abs(endRatio - startRatio)
        val animDuration = calcVelocityAdaptedDuration(duration, ratioDelta, velocity)

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

    /** 主界面容器引用，用于深层页面手势返回 */
    var mainContainer: com.rawsmusic.core.ui.scene.UnifiedMainContainer? = null

    /** 当前是否在 MAIN 场景且有可返回的子页面 */
    private val canGoBackInMain: Boolean
        get() = currentScene == Scene.MAIN &&
                !disableDeepPageSwipe &&
                (mainContainer?.canNavigateBack() == true)

    /** 外部 overlay 显示时禁止手势拦截（如歌曲操作面板、元数据详情面板） */
    var disableGestureIntercept: Boolean = false

    // ==================== 回调 ====================
    var onSwipeBack: (() -> Unit)? = null
    var onImmersiveSwipeLeft: (() -> Unit)? = null
    var onLeftEdgeSwipe: (() -> Unit)? = null
    var onPlayerSwipeToMain: (() -> Unit)? = null
    var onHomeSwipeRightDrag: ((offset: Float) -> Unit)? = null
    var onHomeSwipeRightRelease: ((shouldOpen: Boolean) -> Unit)? = null

    private var isLyricAtTop = false

    fun setLyricAtTopBoundary(atTop: Boolean) {
        isLyricAtTop = atTop
    }

    // ==================== 触摸拦截 ====================

    /**
     * 关键修复：覆盖 requestDisallowInterceptTouchEvent
     * 当边缘滑动进行时，忽略子视图（如 PowerListView）的拦截请求，
     * 确保 onInterceptTouchEvent 能持续收到后续的 MOVE 事件。
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // 边缘滑动进行中时，忽略子视图的 requestDisallowInterceptTouchEvent(true) 调用
        if (disallowIntercept && isEdgeDrag) {
            return
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /**
     * dispatchTouchEvent 总是会被调用，不会被 requestDisallowInterceptTouchEvent 阻止。
     * 用它来检测边缘滑动，然后强制拦截后续事件。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val leftEdge = ev.rawX < LEFT_EDGE_ZONE_DP * density
                val rightEdge = ev.rawX > (width - RIGHT_EDGE_ZONE_DP * density)
                isEdgeDrag = leftEdge || rightEdge
                if (isEdgeDrag) {
                    // 强制拦截边缘滑动事件，防止子视图调用 requestDisallowInterceptTouchEvent
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragState == DragState.SETTLING) return false
        if (isTransitioning) return true
        if (isCoverSwipeUpDragging) return false
        if (disableGestureIntercept) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canDrag = canStartDrag(ev.rawX, ev.rawY)
                android.util.Log.d("GestureDebug", "onInterceptTouchEvent DOWN: canStartDrag=$canDrag, canGoBackInMain=$canGoBackInMain, scene=$currentScene, x=${ev.rawX}, w=$width")
                if (!canDrag) return false
                dragStartX = ev.rawX
                dragStartY = ev.rawY
                isHorizontalSwipe = null
                // 支持左右两侧边缘滑动
                val leftEdge = ev.rawX < LEFT_EDGE_ZONE_DP * density
                val rightEdge = ev.rawX > (width - RIGHT_EDGE_ZONE_DP * density)
                isEdgeDrag = leftEdge || rightEdge
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
                    val dominatedByHorizontal = when {
                        currentScene == Scene.PLAYER || currentScene == Scene.LYRIC -> abs(dx) > abs(dy) * 1.5f
                        canGoBackInMain && isEdgeDrag -> true  // 边缘滑动直接判定为水平
                        canGoBackInMain -> abs(dx) > abs(dy) * 0.6f  // 可返回时更宽松
                        else -> abs(dx) > abs(dy)
                    }
                    isHorizontalSwipe = dominatedByHorizontal
                    android.util.Log.d("GestureDebug", "onInterceptTouchEvent MOVE: dx=$dx, dy=$dy, isHorizontal=$isHorizontalSwipe, scene=$currentScene, canGoBackInMain=$canGoBackInMain")
                    if (isHorizontalSwipe == true) {
                        val accepted = checkSwipeAccepted(dx)
                        android.util.Log.d("GestureDebug", "checkSwipeAccepted=$accepted")
                        if (accepted) {
                            when (currentScene) {
                                Scene.PLAYER, Scene.LYRIC -> {
                                    // 沉浸模式 PLAYER 左滑：启动独立歌词界面
                                    if (isImmersiveEnabled && currentScene == Scene.PLAYER && dx < 0) {
                                        onImmersiveSwipeLeft?.invoke()
                                        resetTouch()
                                        return false
                                    }
                                    onDragStart(dx < 0)
                                }
                                Scene.QUEUE, Scene.ALBUM_DETAIL, Scene.EFFECTS -> {
                                    // 这些场景的边缘滑动返回 MAIN
                                    Log.d("SceneTransition", "onInterceptTouchEvent: QUEUE/ALBUM_DETAIL/EFFECTS edge drag, calling onDragStart")
                                    onDragStart(directionLeft = false)
                                }
                                Scene.MAIN -> {
                                    if (canGoBackInMain) {
                                        // 根据滑动方向或边缘位置确定方向
                                        // 全屏滑动：根据 dx 方向
                                        // 边缘滑动：根据边缘位置
                                        val swipeRight = if (isEdgeDrag) {
                                            // 边缘滑动：左侧边缘向右滑，右侧边缘向左滑
                                            dragStartX < LEFT_EDGE_ZONE_DP * density
                                        } else {
                                            // 全屏滑动：根据滑动方向
                                            dx > 0
                                        }
                                        mainContainer?.startDragBack(
                                            swipeRight = swipeRight,
                                            initialTouchX = dragStartX,
                                            initialTouchY = dragStartY
                                        )
                                    }
                                }
                                else -> {}
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
     * 判断当前场景下滑动是否应该被接受
     * PLAYER 场景：全屏右滑返回 MAIN，左滑进入 LYRIC
     * MAIN + 深层页面：全屏左/右滑返回上一级（使用 SceneController 拖拽动画）
     * MAIN + 首页：右滑交给菜单处理（不拦截）
     * LYRIC, QUEUE, ALBUM_DETAIL, EFFECTS：边缘滑动支持双向
     */
    private fun checkSwipeAccepted(dx: Float): Boolean {
        return when (currentScene) {
            Scene.PLAYER -> dx > 0 || dx < 0  // PLAYER 支持双向
            Scene.LYRIC -> isEdgeDrag && (dx > 0 || dx < 0)  // 边缘滑动支持双向
            Scene.QUEUE -> isEdgeDrag && (dx > 0 || dx < 0)  // 边缘滑动支持双向
            Scene.ALBUM_DETAIL -> isEdgeDrag && (dx > 0 || dx < 0)  // 边缘滑动支持双向
            Scene.EFFECTS -> isEdgeDrag && (dx > 0 || dx < 0)  // 边缘滑动支持双向
            Scene.MAIN -> {
                if (canGoBackInMain) {
                    // 深层页面：全屏滑动和边缘滑动都支持
                    true
                } else {
                    dx > 0
                }
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        android.util.Log.d("SceneTransition", "onTouchEvent: action=${ev.actionMasked}, dragState=$dragState, scene=$currentScene")
        // 过渡动画期间消费所有触摸事件
        if (isTransitioning) return true
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
        // 边缘滑动不排除
        val leftEdge = x < LEFT_EDGE_ZONE_DP * density
        val rightEdge = x > width - RIGHT_EDGE_ZONE_DP * density
        if (!leftEdge && !rightEdge && x > width - EDGE_EXCLUSION_DP * density) return false
        if (isTransitioning) return false
        return true
    }

    /**
     * 拖拽开始：确定拖拽方向并构建动画参数
     * 对标 Poweramp 的 e4.s() 调用前准备
     */
    fun onDragStart(directionLeft: Boolean) {
        sceneAnimator?.cancel()
        isTransitioning = false
        transitionRatio = 0f

        Log.d("SceneTransition", "onDragStart: currentScene=$currentScene, directionLeft=$directionLeft, lyricEnabled=$lyricEnabled")

        when (currentScene) {
            Scene.PLAYER -> {
                if (directionLeft) {
                    dragFromScene = Scene.PLAYER
                    dragToScene = Scene.LYRIC
                    fromScene = dragFromScene
                    toScene = dragToScene
                    onPreparePlayerToLyric?.invoke()
                    lockOldScene(dragFromScene, dragToScene)
                    activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
                } else if (!directionLeft) {
                    dragFromScene = Scene.PLAYER
                    dragToScene = Scene.EFFECTS
                    fromScene = dragFromScene
                    toScene = dragToScene
                    onPreparePlayerToEffects?.invoke()
                    lockOldScene(dragFromScene, dragToScene)
                    activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
                }
                Log.d("SceneTransition", "onDragStart PLAYER: dragFromScene=$dragFromScene → dragToScene=$dragToScene")
            }
            Scene.LYRIC -> {
                dragFromScene = Scene.LYRIC
                dragToScene = Scene.PLAYER
                fromScene = dragFromScene
                toScene = dragToScene
                lockOldScene(dragFromScene, dragToScene)
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.QUEUE -> {
                dragFromScene = Scene.QUEUE
                dragToScene = Scene.MAIN
                fromScene = dragFromScene
                toScene = dragToScene
                lockOldScene(dragFromScene, dragToScene)
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.ALBUM_DETAIL -> {
                dragFromScene = Scene.ALBUM_DETAIL
                dragToScene = Scene.MAIN
                fromScene = dragFromScene
                toScene = dragToScene
                lockOldScene(dragFromScene, dragToScene)
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.EFFECTS -> {
                dragFromScene = Scene.EFFECTS
                dragToScene = Scene.MAIN
                fromScene = dragFromScene
                toScene = dragToScene
                lockOldScene(dragFromScene, dragToScene)
                activeAnimParams = buildAnimParams(dragFromScene, dragToScene)
            }
            Scene.MAIN -> { /* 已在 onInterceptTouchEvent 中调用 startDragBack */ }
        }
        if (activeAnimParams != null) {
            isTransitioning = true
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
                // 边缘滑动支持双向
                val ratio = (kotlin.math.abs(dx) / width.toFloat()).coerceIn(0f, 1f)
                Log.d("SceneTransition", "handleDragMove LYRIC: dx=$dx, ratio=$ratio, activeAnimParams=${activeAnimParams != null}")
                if (activeAnimParams != null) {
                    transitionRatio = ratio
                    applyRatio(activeAnimParams!!, ratio)
                }
            }
            Scene.QUEUE, Scene.ALBUM_DETAIL -> {
                // 边缘滑动支持双向
                val ratio = (kotlin.math.abs(dx) / width.toFloat()).coerceIn(0f, 1f)
                Log.d("SceneTransition", "handleDragMove QUEUE/ALBUM_DETAIL: dx=$dx, ratio=$ratio, activeAnimParams=${activeAnimParams != null}")
                if (activeAnimParams != null) {
                    transitionRatio = ratio
                    applyRatio(activeAnimParams!!, ratio)
                }
            }
            Scene.EFFECTS -> {
                // 边缘滑动支持双向
                val ratio = (kotlin.math.abs(dx) / width.toFloat()).coerceIn(0f, 1f)
                Log.d("SceneTransition", "handleDragMove EFFECTS: dx=$dx, ratio=$ratio, activeAnimParams=${activeAnimParams != null}")
                if (activeAnimParams != null) {
                    transitionRatio = ratio
                    applyRatio(activeAnimParams!!, ratio)
                }
            }
            Scene.MAIN -> {
                if (canGoBackInMain) {
                    // 深层页面：驱动 SceneController 拖拽返回动画，实时跟随手指（跟手）
                    mainContainer?.updateDragBack(ev.rawX, ev.rawY)
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
                    (dragToScene == Scene.EFFECTS && isFlingRight) ||
                    (dragToScene == Scene.LYRIC && isFlingLeft)

                Log.d("SceneTransition", "handleDragRelease PLAYER: dragToScene=$dragToScene, shouldGoToTarget=$shouldGoToTarget, ratio=$transitionRatio, threshold=$SWIPE_THRESHOLD_RATIO")

                if (shouldGoToTarget) {
                    if (dragToScene == Scene.EFFECTS) {
                        Log.d("SceneTransition", "handleDragRelease PLAYER→EFFECTS settle")
                        settleFromCurrentRatio(Scene.EFFECTS, transitionRatio, 1f, vx)
                    } else {
                        Log.d("SceneTransition", "handleDragRelease PLAYER→LYRIC settle")
                        settleFromCurrentRatio(dragToScene, transitionRatio, 1f, vx)
                    }
                } else {
                    Log.d("SceneTransition", "handleDragRelease PLAYER REVERT → $dragFromScene settle(ratio=$transitionRatio, 0f)")
                    settleFromCurrentRatio(dragFromScene, transitionRatio, 0f, vx)
                }
            }
            Scene.LYRIC -> {
                val shouldGoBack = transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight
                Log.d("SceneTransition", "handleDragRelease LYRIC: shouldGoBack=$shouldGoBack, ratio=$transitionRatio, isFlingRight=$isFlingRight")
                if (shouldGoBack) {
                    settleFromCurrentRatio(Scene.PLAYER, transitionRatio, 1f, vx)
                } else {
                    settleFromCurrentRatio(Scene.LYRIC, transitionRatio, 0f, vx)
                }
            }
            Scene.QUEUE, Scene.ALBUM_DETAIL -> {
                val shouldGoBack = transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight
                if (shouldGoBack) {
                    settleFromCurrentRatio(Scene.PLAYER, transitionRatio, 1f, vx)
                } else {
                    settleFromCurrentRatio(currentScene, transitionRatio, 0f, vx)
                }
            }
            Scene.EFFECTS -> {
                val shouldGoBack = transitionRatio > SWIPE_THRESHOLD_RATIO || isFlingRight
                if (shouldGoBack) {
                    settleFromCurrentRatio(Scene.PLAYER, transitionRatio, 1f, vx)
                } else {
                    settleFromCurrentRatio(Scene.EFFECTS, transitionRatio, 0f, vx)
                }
            }
            Scene.MAIN -> {
                if (canGoBackInMain) {
                    // 使用 SceneController 中的 dragCurrentRatio 来判断
                    val ratio = mainContainer?.getDragBackRatio() ?: 0f
                    val shouldGoBack = ratio > SWIPE_THRESHOLD_RATIO ||
                        (ratio > 0 && isFlingRight) || (ratio > 0 && isFlingLeft)
                    mainContainer?.endDragBack(shouldGoBack, vx)
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
        endRatio: Float,
        velocity: Float = 0f
    ) {
        val params = activeAnimParams ?: run {
            Log.w("SceneTransition", "settleFromCurrentRatio: activeAnimParams is NULL, clearing state! targetScene=$targetScene")
            isTransitioning = false
            dragState = DragState.IDLE
            activeAnimParams = null
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
        val duration = calcVelocityAdaptedDuration(SCENE_ANIM_DURATION, ratioDelta, velocity)

        val gen = sceneAnimGeneration
        val settleSafetyGen = sceneAnimGeneration
        postDelayed({
            if (isTransitioning && sceneAnimGeneration == settleSafetyGen) {
                Log.w("SceneTransition", "settleFromCurrentRatio: safety timeout, force-clearing isTransitioning")
                isTransitioning = false
                dragState = DragState.IDLE
                activeAnimParams = null
            }
        }, duration + 1500)
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
        forceApplyImmersiveVisibility()
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
        Log.w("SceneTransition", "=== closePlayPage called, currentScene=$currentScene, animated=$animated ===")
        if (currentScene != Scene.PLAYER) {
            Log.w("SceneTransition", "=== closePlayPage: currentScene != PLAYER, returning ===")
            return
        }
        if (animated) transitionToScene(Scene.MAIN) else switchToSceneSilent(Scene.MAIN)
    }

    /**
     * 关闭播放页并执行封面对齐动画（从当前位置过渡到列表封面位置）
     * 外部在调用前应先通过 onPreparePlayerToMain 注册封面参数
     */
    fun closePlayPageWithCoverAlign(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        if (animated) {
            onPreparePlayerToMain?.invoke {
                transitionToScene(Scene.MAIN)
            } ?: transitionToScene(Scene.MAIN)
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
            Scene.EFFECTS -> {
                closeEffectsPage(animated)
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

    fun openEffectsPage(animated: Boolean = true) {
        if (currentScene != Scene.PLAYER) return
        onPreparePlayerToEffects?.invoke()
        if (animated) transitionToScene(Scene.EFFECTS) else switchToSceneSilent(Scene.EFFECTS)
    }

    fun closeEffectsPage(animated: Boolean = true) {
        if (currentScene != Scene.EFFECTS) return
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
        cancelAllStateAnims()
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

    // ==================== Compose 版本的便捷 API ====================

    /**
     * Compose 版本的打开播放页
     */
    fun composeOpenPlayPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.MAIN) return
        if (animated) {
            onPrepareMainToPlayer?.invoke()
            composeTransitionToScene(Scene.PLAYER, scope = scope)
        } else {
            composeSwitchToSceneSilent(Scene.PLAYER)
        }
    }

    /**
     * Compose 版本的关闭播放页
     */
    fun composeClosePlayPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        if (animated) composeTransitionToScene(Scene.MAIN, scope = scope)
        else composeSwitchToSceneSilent(Scene.MAIN)
    }

    /**
     * Compose 版本的关闭播放页并执行封面对齐动画
     */
    fun composeClosePlayPageWithCoverAlign(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        if (animated) {
            onPreparePlayerToMain?.invoke {
                composeTransitionToScene(Scene.MAIN, scope = scope)
            } ?: composeTransitionToScene(Scene.MAIN, scope = scope)
        } else {
            composeSwitchToSceneSilent(Scene.MAIN)
        }
    }

    /**
     * Compose 版本的打开歌词页
     */
    fun composeOpenLyricPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        onPreparePlayerToLyric?.invoke()
        if (animated) composeTransitionToScene(Scene.LYRIC, scope = scope)
        else composeSwitchToSceneSilent(Scene.LYRIC)
    }

    /**
     * Compose 版本的关闭歌词页
     */
    fun composeCloseLyricPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.LYRIC) return
        if (animated) composeTransitionToScene(Scene.PLAYER, scope = scope)
        else composeSwitchToSceneSilent(Scene.PLAYER)
    }

    /**
     * Compose 版本的返回首页
     */
    fun composeBackToHome(animated: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        when (composeCurrentScene) {
            Scene.LYRIC -> {
                composeCloseLyricPage(animated, scope)
                if (animated) scope.launch {
                    kotlinx.coroutines.delay(350)
                    composeClosePlayPage(animated, scope)
                } else composeClosePlayPage(false, scope)
            }
            Scene.QUEUE -> {
                composeCloseQueuePage(animated, scope)
                if (animated) scope.launch {
                    kotlinx.coroutines.delay(350)
                    composeClosePlayPage(animated, scope)
                } else composeClosePlayPage(false, scope)
            }
            Scene.ALBUM_DETAIL -> {
                composeCloseAlbumDetailPage(animated, scope)
                if (animated) scope.launch {
                    kotlinx.coroutines.delay(350)
                    composeClosePlayPage(animated, scope)
                } else composeClosePlayPage(false, scope)
            }
            Scene.EFFECTS -> {
                composeCloseEffectsPage(animated, scope)
            }
            Scene.PLAYER -> composeClosePlayPage(animated, scope)
            Scene.MAIN -> {}
        }
    }

    /**
     * Compose 版本的打开队列页
     */
    fun composeOpenQueuePage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        onPreparePlayerToQueue?.invoke()
        if (animated) composeTransitionToScene(Scene.QUEUE, scope = scope)
        else composeSwitchToSceneSilent(Scene.QUEUE)
    }

    /**
     * Compose 版本的关闭队列页
     */
    fun composeCloseQueuePage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.QUEUE) return
        if (animated) composeTransitionToScene(Scene.PLAYER, scope = scope)
        else composeSwitchToSceneSilent(Scene.PLAYER)
    }

    /**
     * Compose 版本的打开专辑详情页
     */
    fun composeOpenAlbumDetailPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        onPreparePlayerToAlbumDetail?.invoke()
        if (animated) composeTransitionToScene(Scene.ALBUM_DETAIL, scope = scope)
        else composeSwitchToSceneSilent(Scene.ALBUM_DETAIL)
    }

    /**
     * Compose 版本的关闭专辑详情页
     */
    fun composeCloseAlbumDetailPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.ALBUM_DETAIL) return
        if (animated) composeTransitionToScene(Scene.PLAYER, scope = scope)
        else composeSwitchToSceneSilent(Scene.PLAYER)
    }

    /**
     * Compose 版本的打开音效页
     */
    fun composeOpenEffectsPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER) return
        onPreparePlayerToEffects?.invoke()
        if (animated) composeTransitionToScene(Scene.EFFECTS, scope = scope)
        else composeSwitchToSceneSilent(Scene.EFFECTS)
    }

    /**
     * Compose 版本的关闭音效页
     */
    fun composeCloseEffectsPage(animated: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.EFFECTS) return
        if (animated) composeTransitionToScene(Scene.PLAYER, scope = scope)
        else composeSwitchToSceneSilent(Scene.PLAYER)
    }

    /**
     * Compose 版本的同步旋转状态
     */
    fun composeSyncRotationState(isPlaying: Boolean) {
        composeIsPlaying = isPlaying
    }

    // ==================== Compose 版本的 StateAnim 系统 ====================

    /** Compose 版本的动画状态映射 */
    private val composeStateAnimMap = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Compose 版本的状态动画
     * 使用 Animatable 替代 ValueAnimator
     *
     * @param key 动画的唯一标识
     * @param targetAlpha 目标透明度
     * @param targetScaleX 目标 X 缩放
     * @param targetScaleY 目标 Y 缩放
     * @param targetTranslationX 目标 X 平移
     * @param targetTranslationY 目标 Y 平移
     * @param targetRotation 目标旋转
     * @param duration 动画时长
     * @param scope CoroutineScope
     * @param onUpdate 每帧更新回调
     * @param onEnd 动画结束回调
     */
    fun composeStartStateAnim(
        key: String,
        targetAlpha: Float? = null,
        targetScaleX: Float? = null,
        targetScaleY: Float? = null,
        targetTranslationX: Float? = null,
        targetTranslationY: Float? = null,
        targetRotation: Float? = null,
        duration: Long = STATE_ANIM_DEFAULT_DURATION,
        scope: kotlinx.coroutines.CoroutineScope,
        onUpdate: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        composeStateAnimMap[key]?.cancel()

        val job = scope.launch {
            val animatable = Animatable(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(duration.toInt())
            ) {
                onUpdate?.invoke(value)
            }
            composeStateAnimMap.remove(key)
            onEnd?.invoke()
        }
        composeStateAnimMap[key] = job
    }

    /**
     * Compose 版本的取消状态动画
     */
    fun composeCancelStateAnim(key: String) {
        composeStateAnimMap[key]?.cancel()
        composeStateAnimMap.remove(key)
    }

    /**
     * Compose 版本的取消所有状态动画
     */
    fun composeCancelAllStateAnims() {
        composeStateAnimMap.values.forEach { it.cancel() }
        composeStateAnimMap.clear()
    }

    /**
     * Compose 版本的按钮按压弹回动画
     */
    fun composeButtonPressAnim(
        key: String,
        pressScale: Float = BUTTON_PRESS_SCALE,
        duration: Long = BUTTON_PRESS_DURATION,
        scope: kotlinx.coroutines.CoroutineScope,
        onUpdate: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        composeStartStateAnim(
            key = key,
            targetScaleX = pressScale,
            targetScaleY = pressScale,
            duration = duration / 2,
            scope = scope,
            onUpdate = onUpdate,
            onEnd = {
                composeStartStateAnim(
                    key = key,
                    targetScaleX = 1f,
                    targetScaleY = 1f,
                    duration = duration,
                    scope = scope,
                    onEnd = onEnd
                )
            }
        )
    }

    /**
     * Compose 版本的弹性回弹动画
     */
    fun composeSpringSettleAnim(
        key: String,
        targetScaleX: Float = 1f,
        targetScaleY: Float = 1f,
        targetTranslationX: Float = 0f,
        targetTranslationY: Float = 0f,
        targetAlpha: Float? = null,
        duration: Long = SPRING_SETTLE_DURATION,
        scope: kotlinx.coroutines.CoroutineScope,
        onUpdate: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        composeStartStateAnim(
            key = key,
            targetAlpha = targetAlpha,
            targetScaleX = targetScaleX,
            targetScaleY = targetScaleY,
            targetTranslationX = targetTranslationX,
            targetTranslationY = targetTranslationY,
            duration = duration,
            scope = scope,
            onUpdate = onUpdate,
            onEnd = onEnd
        )
    }

    // ==================== Compose 版本的沉浸模式 ====================

    /** Compose 版本的沉浸模式启用状态 */
    var composeIsImmersiveEnabled by mutableStateOf(true)
        private set

    /** Compose 版本的迷你封面启用状态 */
    var composeIsMiniCoverEnabled by mutableStateOf(true)
        private set

    /** Compose 版本的默认背景启用状态 */
    var composeIsDefaultBackgroundEnabled by mutableStateOf(false)
        private set

    /** Compose 版本的暗色模式状态 */
    var composeIsDarkMode by mutableStateOf(true)
        private set

    /**
     * Compose 版本的初始化沉浸模式
     * 不再需要 View 引用，只设置状态
     */
    fun composeInitImmersive(
        isImmersiveEnabled: Boolean = true,
        isMiniCoverEnabled: Boolean = true
    ) {
        composeIsImmersiveEnabled = isImmersiveEnabled
        composeIsMiniCoverEnabled = isMiniCoverEnabled
        composeApplyImmersiveSceneParams()
    }

    /**
     * Compose 版本的应用沉浸模式场景参数
     * 使用 Compose 可观察的场景注册表
     */
    fun composeApplyImmersiveSceneParams() {
        // 沉浸背景
        if (composeIsImmersiveEnabled) {
            val immersiveAlpha = if (composeIsImmersiveEnabled) 1f else 0f
            registerComposeViewScenes("immersive_bg",
                Scene.MAIN to SceneParams(Scene.MAIN, alpha = 0f),
                Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = immersiveAlpha),
                Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = immersiveAlpha),
                Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = immersiveAlpha),
                Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = immersiveAlpha),
                Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = immersiveAlpha)
            )
        }

        // 迷你封面
        val miniCoverAlpha = if (composeIsMiniCoverEnabled) 1f else 0f
        registerComposeViewScenes("mini_cover",
            Scene.MAIN to SceneParams(Scene.MAIN, alpha = miniCoverAlpha),
            Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = 0f),
            Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = 0f),
            Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = 0f),
            Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = 0f),
            Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = 0f)
        )

        // 播放页封面
        val playerCoverAlpha = if (composeIsImmersiveEnabled) 0f else 1f
        registerComposeViewScenes("play_cover",
            Scene.MAIN to SceneParams(Scene.MAIN, alpha = 0f),
            Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = playerCoverAlpha),
            Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = playerCoverAlpha),
            Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = playerCoverAlpha),
            Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = playerCoverAlpha),
            Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = playerCoverAlpha)
        )

        // 播放页遮罩
        val scrimAlpha = if (composeIsImmersiveEnabled) 0f else 1f
        registerComposeViewScenes("play_bg_scrim",
            Scene.MAIN to SceneParams(Scene.MAIN, alpha = 0f),
            Scene.PLAYER to SceneParams(Scene.PLAYER, alpha = scrimAlpha),
            Scene.LYRIC to SceneParams(Scene.LYRIC, alpha = scrimAlpha),
            Scene.QUEUE to SceneParams(Scene.QUEUE, alpha = scrimAlpha),
            Scene.ALBUM_DETAIL to SceneParams(Scene.ALBUM_DETAIL, alpha = scrimAlpha),
            Scene.EFFECTS to SceneParams(Scene.EFFECTS, alpha = scrimAlpha)
        )
    }

    /**
     * Compose 版本的更新沉浸模式设置
     */
    fun composeUpdateImmersiveSettings(isImmersive: Boolean, isDark: Boolean) {
        composeIsImmersiveEnabled = isImmersive
        composeIsDarkMode = isDark
        composeApplyImmersiveSceneParams()
    }

    /**
     * Compose 版本的刷新沉浸模式状态
     */
    fun composeRefreshImmersiveState(isImmersive: Boolean) {
        composeIsImmersiveEnabled = isImmersive
        composeApplyImmersiveSceneParams()
    }

    /**
     * Compose 版本的更新迷你封面启用状态
     */
    fun composeUpdateMiniCoverEnabled(enabled: Boolean) {
        composeIsMiniCoverEnabled = enabled
        composeApplyImmersiveSceneParams()
    }

    /**
     * Compose 版本的更新默认背景启用状态
     */
    fun composeUpdateDefaultBackgroundEnabled(enabled: Boolean) {
        composeIsDefaultBackgroundEnabled = enabled
        composeApplyImmersiveSceneParams()
    }

    // ==================== Compose 版本的手势处理方法 ====================

    /** Compose 版本的封面拖拽状态 */
    var composeIsCoverDragging by mutableStateOf(false)
        private set

    /** Compose 版本的封面上滑拖拽状态 */
    var composeIsCoverSwipeUpDragging by mutableStateOf(false)
        private set

    /**
     * Compose 版本的开始封面拖拽
     * @param targetScene 目标场景
     * @param scope CoroutineScope
     */
    fun composeStartCoverDrag(targetScene: Scene = Scene.MAIN, scope: kotlinx.coroutines.CoroutineScope) {
        if (composeCurrentScene != Scene.PLAYER && composeCurrentScene != Scene.LYRIC) return
        if (composeCurrentScene == targetScene) return
        sceneAnimGeneration++
        fromScene = composeCurrentScene
        toScene = targetScene
        composeTransitionProgress = 0f
        composeIsTransitioning = true
        composeIsCoverDragging = true
    }

    /**
     * Compose 版本的更新封面拖拽进度
     * @param ratio 0=PLAYER，1=MAIN
     */
    fun composeUpdateCoverDrag(ratio: Float) {
        if (!composeIsCoverDragging) return
        composeTransitionProgress = ratio.coerceIn(0f, 1f)
        onTransitionProgress?.invoke(toScene, composeTransitionProgress)
    }

    /**
     * Compose 版本的结束封面拖拽
     * @param shouldClose 是否应关闭到目标场景
     * @param duration 动画时长
     * @param scope CoroutineScope
     */
    fun composeEndCoverDrag(
        shouldClose: Boolean,
        duration: Long = SCENE_ANIM_DURATION,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (!composeIsCoverDragging) return
        composeIsCoverDragging = false

        val targetScene = if (shouldClose) toScene else fromScene
        val endRatio = if (shouldClose) 1f else 0f
        val startRatio = composeTransitionProgress

        if (shouldClose) {
            composeTransitionToScene(targetScene, duration, scope)
        } else {
            // 回弹
            sceneAnimGeneration++
            val gen = sceneAnimGeneration
            scope.launch {
                val animatable = Animatable(startRatio)
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(duration.toInt())
                ) {
                    composeTransitionProgress = value
                }
                if (sceneAnimGeneration == gen) {
                    composeIsTransitioning = false
                }
            }
        }
    }

    /**
     * Compose 版本的开始封面上滑拖拽
     */
    fun composeStartCoverSwipeUpDrag(
        from: Scene = Scene.PLAYER,
        to: Scene = Scene.LYRIC,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (composeCurrentScene != from && composeCurrentScene != to) return
        sceneAnimGeneration++
        val actualFrom = composeCurrentScene
        val actualTo = if (actualFrom == from) to else from
        if (actualFrom == Scene.PLAYER && actualTo == Scene.LYRIC) onPreparePlayerToLyric?.invoke()
        fromScene = actualFrom
        toScene = actualTo
        composeTransitionProgress = 0f
        composeIsTransitioning = true
        composeIsCoverSwipeUpDragging = true
    }

    /**
     * Compose 版本的更新封面上滑拖拽进度
     */
    fun composeUpdateCoverSwipeUpDrag(ratio: Float) {
        if (!composeIsCoverSwipeUpDragging) return
        composeTransitionProgress = ratio.coerceIn(0f, 1f)
        onTransitionProgress?.invoke(toScene, composeTransitionProgress)
    }

    /**
     * Compose 版本的结束封面上滑拖拽
     */
    fun composeEndCoverSwipeUpDrag(
        shouldOpen: Boolean,
        duration: Long = SCENE_ANIM_DURATION,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (!composeIsCoverSwipeUpDragging) return
        composeIsCoverSwipeUpDragging = false

        val targetScene = if (shouldOpen) toScene else fromScene
        if (shouldOpen) {
            composeTransitionToScene(targetScene, duration, scope)
        } else {
            sceneAnimGeneration++
            val gen = sceneAnimGeneration
            scope.launch {
                val animatable = Animatable(composeTransitionProgress)
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(duration.toInt())
                ) {
                    composeTransitionProgress = value
                }
                if (sceneAnimGeneration == gen) {
                    composeIsTransitioning = false
                }
            }
        }
    }

    // ==================== Compose 版本的场景切换方法 ====================

    /**
     * Compose 版本的场景切换动画
     * 使用 Animatable 替代 ValueAnimator
     *
     * @param targetScene 目标场景
     * @param duration 动画时长 (ms)
     * @param scope CoroutineScope 用于启动动画
     */
    fun composeTransitionToScene(
        targetScene: Scene,
        duration: Long = SCENE_ANIM_DURATION,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (composeCurrentScene == targetScene && !composeIsTransitioning) return

        sceneAnimGeneration++
        val gen = sceneAnimGeneration
        val from = composeCurrentScene
        val to = targetScene

        fromScene = from
        toScene = to
        composeIsTransitioning = true
        composeTransitionProgress = 0f

        scope.launch {
            val animatable = Animatable(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                composeTransitionProgress = value
                onTransitionProgress?.invoke(to, value)
            }

            // 动画完成
            if (sceneAnimGeneration == gen) {
                composeCurrentScene = targetScene
                composeIsTransitioning = false
                composeTransitionProgress = 0f
                onSceneChanged?.invoke(targetScene, from)
            }
        }
    }

    /**
     * Compose 版本的从当前进度继续动画
     */
    fun composeTransitionFromCurrentRatio(
        targetScene: Scene,
        duration: Long = SCENE_ANIM_DURATION,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        sceneAnimGeneration++
        val gen = sceneAnimGeneration
        val startRatio = composeTransitionProgress
        val endRatio = if (fromScene == targetScene) 0f else 1f

        toScene = targetScene
        composeIsTransitioning = true

        scope.launch {
            val animatable = Animatable(startRatio)
            animatable.animateTo(
                targetValue = endRatio,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                composeTransitionProgress = value
                onTransitionProgress?.invoke(targetScene, value)
            }

            // 动画完成
            if (sceneAnimGeneration == gen) {
                composeCurrentScene = targetScene
                composeIsTransitioning = false
                composeTransitionProgress = 0f
                onSceneChanged?.invoke(targetScene, fromScene)
            }
        }
    }

    /**
     * Compose 版本的静默切换场景
     */
    fun composeSwitchToSceneSilent(targetScene: Scene) {
        val oldScene = composeCurrentScene
        composeCurrentScene = targetScene
        composeIsTransitioning = false
        composeTransitionProgress = 0f
        if (oldScene != targetScene) {
            onSceneChanged?.invoke(targetScene, oldScene)
        }
    }

    // ==================== Compose UI 组件 ====================

    /**
     * Compose 版本的场景导航宿主
     * 管理播放器场景切换 (MAIN/PLAYER/LYRIC/QUEUE/ALBUM_DETAIL/EFFECTS)
     */
    @Composable
    fun ComposePlayerNavHost(
        modifier: Modifier = Modifier,
        sceneContent: @Composable (Scene) -> Unit
    ) {
        val currentScene = composeCurrentScene
        val isTransitioning = composeIsTransitioning
        val transitionProgress = composeTransitionProgress

        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 过渡时的缩放效果
                    if (isTransitioning) {
                        val scale = 1f - (transitionProgress * 0.05f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
        ) {
            sceneContent(currentScene)
        }
    }

    /**
     * Compose 版本的封面拖拽容器
     * 支持上下拖拽切换 PLAYER/LYRIC 场景
     */
    @Composable
    fun ComposeCoverDragContainer(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val isDragging = composeIsCoverDragging
        val isSwipeUpDragging = composeIsCoverSwipeUpDragging
        val progress = composeTransitionProgress

        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isDragging || isSwipeUpDragging) {
                        // 拖拽时的视觉反馈
                        val scale = 1f - (progress * 0.02f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            // 根据起始位置判断是下拉还是上滑
                            if (offset.y < size.height / 2) {
                                composeStartCoverDrag(Scene.MAIN, scope)
                            } else {
                                composeStartCoverSwipeUpDrag(Scene.PLAYER, Scene.LYRIC, scope)
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val ratio = (dragAmount / size.height).coerceIn(0f, 1f)
                            if (isDragging) {
                                composeUpdateCoverDrag(ratio)
                            } else if (isSwipeUpDragging) {
                                composeUpdateCoverSwipeUpDrag(ratio)
                            }
                        },
                        onDragEnd = {
                            if (isDragging) {
                                val shouldClose = progress > 0.3f
                                composeEndCoverDrag(shouldClose, scope = scope)
                            } else if (isSwipeUpDragging) {
                                val shouldOpen = progress > 0.3f
                                composeEndCoverSwipeUpDrag(shouldOpen, scope = scope)
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }

    companion object {
        private const val EDGE_EXCLUSION_DP = 20f
        private const val LEFT_EDGE_ZONE_DP = 24f
        private const val RIGHT_EDGE_ZONE_DP = 24f
        private const val SWIPE_THRESHOLD_RATIO = 0.30f

        private const val PAGE_DECELERATE = 2.0f
        private const val SCENE_ANIM_DURATION = 250L

        private const val VELOCITY_ADAPT_MIN_MS = 100L
        private const val VELOCITY_ADAPT_MAX_MS = 250L
        private const val VELOCITY_SENSITIVITY = 0.002f

        private const val SPRING_SETTLE_DURATION = 350L

        /** Poweramp r1 cubic ease-out: 1-(1-t)³ = 3t-3t²+t³ */
        private val CUBIC_EASE_OUT = android.animation.TimeInterpolator { t ->
            val inv = 1f - t
            1f - inv * inv * inv
        }

        private const val STATE_ANIM_DEFAULT_DURATION = 200L
        private const val STATE_ANIM_DECELERATE = 2.0f
        private const val BUTTON_PRESS_SCALE = 0.85f
        private const val BUTTON_PRESS_DURATION = 150L
        private const val BUTTON_RELEASE_OVERSHOOT = 1.05f
    }
}
