package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.google.android.renderscript.Toolkit
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * 动态封面背景 View
 *
 * 完全按照反编译源码 bh.class 实现：
 * 1. onDraw 中实时合成：三层旋转封面 → 饱和度 → 颜色叠加 → drawBitmapMesh(流动光) → Toolkit blur
 * 2. 极小尺寸渲染 (viewSize*1.3/scaleFactor)，模糊半径 25 作用于小图，极快
 * 3. 结果转 BitmapShader(MIRROR) + 缩放矩阵平铺
 * 4. AlphaAnimation 交叉淡入淡出
 * 5. postInvalidateDelayed(42ms) ≈24fps 动画循环
 */
class DynamicCoverBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 屏幕密度 >= 420dpi 时的缩放因子 */
        private const val SCALE_FACTOR_HD = 32f
        /** 普通密度的缩放因子 */
        private const val SCALE_FACTOR_NORMAL = 20f
        /** 屏幕密度 >= 420dpi 时的降特效缩放因子 */
        private const val SCALE_FACTOR_REDUCED_HD = 72f
        /** 普通密度的降特效缩放因子 */
        private const val SCALE_FACTOR_REDUCED_NORMAL = 48f
        /** 模糊半径 */
        private const val BLUR_RADIUS = 25
        /** 默认黑色占位图 */
        private val PLACEHOLDER_BITMAP by lazy {
            Bitmap.createBitmap(intArrayOf(-0x1000000), 1, 1, Bitmap.Config.ARGB_8888)
        }
        /** 动画帧间隔 (ms)，约 24fps */
        private const val FRAME_DELAY = 42L
        /** 淡入淡出持续时间 (ms) */
        private const val CROSSFADE_DURATION = 500L
    }

    // ───── 缩放因子（根据屏幕密度） ─────
    private val densityDpi = context.resources.configuration.densityDpi
    private var scaleFactor = if (densityDpi >= 420) SCALE_FACTOR_HD else SCALE_FACTOR_NORMAL

    // ───── 模糊半径（可配置，homeBgView 使用更高模糊） ─────
    private var blurRadius: Int = BLUR_RADIUS

    // ───── 饱和度因子 ─────
    private var saturationFactor = 2.5f

    // ───── 旋转动画 ─────
    private val rotateAnim1 = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 100000L; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
    }
    private val rotateAnim2 = ValueAnimator.ofFloat(0f, -360f).apply {
        duration = 70000L; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
    }
    private val rotateAnim3 = ValueAnimator.ofFloat(0f, -360f).apply {
        duration = 40000L; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
    }

    // ───── 画笔 ─────
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    // ───── 淡入淡出动画 ─────
    private val crossfadeAnim = AlphaAnimation(1f, 0f).apply {
        duration = CROSSFADE_DURATION
        interpolator = PathInterpolator(0f, 0f, 0.3f, 1f)
    }
    private val crossfadeTransformation = android.view.animation.Transformation()

    // ───── Shader 与 Matrix ─────
    private var shaderMatrix = Matrix().apply { setScale(scaleFactor, scaleFactor) }
    private var currentShader: BitmapShader? = null
    private var previousShader: BitmapShader? = null

    // ───── 圆角路径 ─────
    private var clipPath: Path? = null
    private var topCornerRadius = 0f

    // ───── 位图 ─────
    private var currentBitmap: Bitmap? = null
    private var pendingBitmap: Bitmap? = null
    private var currentBufferBitmap: Bitmap? = null
    /** 缓存：Key=(w,h)，Value=(buffer1, buffer2)，最多保留 1 组 */
    private val bitmapCache = HashMap<Pair<Int, Int>, Pair<Bitmap, Bitmap>>()
    private val MAX_CACHE_ENTRIES = 1
    private var onDrawBitmap1: Bitmap? = null
    private var onDrawBitmap2: Bitmap? = null
    private var onDrawResultBitmap: Bitmap? = null

    // ───── 异步模糊 ─────
    private val blurScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var blurJob: Job? = null
    @Volatile private var pendingBlurResult: Bitmap? = null

    // ───── 叠加颜色 ─────
    private var overlayColors = intArrayOf(0xB31A1A1A.toInt(), 0x1AFFFFFF)
    private var baseColor = 0xFF1A1614.toInt()

    // ───── 颜色回调 ─────
    private var onColorsExtracted: ((primary: Int, dark: Int) -> Unit)? = null

    // ───── 流动光网格 ─────
    private var meshVertices: FloatArray? = null
    private var isFlowingLightRunning = false
    private var isFlowingLightPaused = false
    private var isDynamic = false
    private var allowDynamicRunning = false
    private var meshDelayCounter = 0
    private var frameCounter = 0
    private var fpsTimestamp1 = 0L
    private var fpsTimestamp2 = 0L

    // ───── 暗度 ─────
    private var dimAmount = 0f
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        currentPaint.alpha = 0
        previousPaint.alpha = 255
    }

    // ═══════════════════════════════════════
    // 公共 API
    // ═══════════════════════════════════════

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return

        // 相同封面跳过
        if (bitmap == currentBitmap || !bitmap.sameAs(currentBitmap)) {
            // 如果淡入淡出还在进行，暂存待处理位图
            if (crossfadeAnim.hasStarted() && !crossfadeAnim.hasEnded()) {
                pendingBitmap = bitmap
                return
            }

            // 取消之前动画
            crossfadeAnim.cancel()
            crossfadeAnim.reset()
            rotateAnim1.cancel()
            rotateAnim2.cancel()
            rotateAnim3.cancel()

            // 保存当前 shader 为 previous
            previousShader = currentShader
            previousPaint.shader = previousShader

            // 清空当前 shader（触发 onDraw 中重新合成）
            currentShader = null
            currentBitmap = bitmap

            // 随机选择网格顶点（流动光效果）
            meshVertices = generateMeshVertices()

            // 启动淡入淡出
            crossfadeAnim.start()
            crossfadeAnim.getTransformation(System.currentTimeMillis(), crossfadeTransformation)
            currentPaint.alpha = 0
            meshDelayCounter = 0
            isFlowingLightRunning = false

            invalidate()
        }
    }

    fun setTopCornerRadius(radius: Float) {
        topCornerRadius = radius.coerceAtMost(8f * resources.displayMetrics.density)
        updateClipPath()
    }

    fun setReducedEffects(reduced: Boolean) {
        val newScale = if (reduced) {
            if (densityDpi >= 420) SCALE_FACTOR_REDUCED_HD else SCALE_FACTOR_REDUCED_NORMAL
        } else {
            if (densityDpi >= 420) SCALE_FACTOR_HD else SCALE_FACTOR_NORMAL
        }
        val newSaturation = if (reduced) 3.5f else 2.5f

        if (newScale == scaleFactor && newSaturation == saturationFactor) return

        pauseAnims()
        scaleFactor = newScale
        saturationFactor = newSaturation
        shaderMatrix.reset()
        shaderMatrix.setScale(scaleFactor, scaleFactor)

        currentBitmap?.let { setArtwork(it) }
    }

    fun pauseAnimations() {
        isFlowingLightPaused = true
        pauseAnims()
        fpsTimestamp1 = 0L
        fpsTimestamp2 = 0L
    }

    fun resumeAnimations() {
        isFlowingLightPaused = false
        if (rotateAnim1.isStarted && rotateAnim1.isPaused) {
            rotateAnim1.resume()
            rotateAnim2.resume()
            rotateAnim3.resume()
        }
        invalidate()
    }

    fun setDimAmount(amount: Float) {
        dimAmount = amount.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOverlayColors(colors: IntArray) {
        overlayColors = colors
        // 清除当前 shader，触发 onDraw 重新合成
        currentShader = null
        invalidate()
    }

    /** 设置模糊半径（默认 25，主界面可设置更高以获得更柔和背景） */
    fun setBlurRadius(radius: Int) {
        if (blurRadius == radius) return
        blurRadius = radius.coerceAtLeast(1)
        currentShader = null
        invalidate()
    }

    /**
     * 设置颜色提取回调，当封面颜色被提取后通知外部
     * primary: 背景主色调（用于状态栏）
     * dark: 背景深色调（用于歌词页状态栏）
     */
    fun setOnColorsExtractedListener(listener: ((primary: Int, dark: Int) -> Unit)?) {
        onColorsExtracted = listener
    }

    fun setDynamic(dynamic: Boolean) {
        isDynamic = dynamic
    }

    fun setAllowDynamicRunning(allow: Boolean) {
        allowDynamicRunning = allow
    }

    fun syncFrom(source: DynamicCoverBackgroundView) {
        val bitmap = source.currentBitmap ?: return
        if (bitmap.isRecycled) return

        this.overlayColors = source.overlayColors.copyOf()
        this.saturationFactor = source.saturationFactor
        if (source.scaleFactor != this.scaleFactor) {
            this.scaleFactor = source.scaleFactor
            this.shaderMatrix.reset()
            this.shaderMatrix.setScale(scaleFactor, scaleFactor)
        }
        this.blurRadius = source.blurRadius

        if (rotateAnim1.isStarted && source.rotateAnim1.isStarted) {
            val targetFraction1 = source.rotateAnim1.animatedFraction
            val targetFraction2 = source.rotateAnim2.animatedFraction
            val targetFraction3 = source.rotateAnim3.animatedFraction
            rotateAnim1.setCurrentFraction(targetFraction1)
            rotateAnim2.setCurrentFraction(targetFraction2)
            rotateAnim3.setCurrentFraction(targetFraction3)
        }

        setArtwork(bitmap)

        if (source.isAnimationRunning()) resumeAnimations() else pauseAnimations()
    }

    fun isAnimationRunning(): Boolean {
        return rotateAnim1.isStarted && !rotateAnim1.isPaused
    }

    fun clearArtwork() {
        pendingBitmap = null
        currentBitmap = null
        currentShader = null
        previousShader = null
        currentPaint.shader = null
        previousPaint.shader = null
        currentBufferBitmap = null
        onDrawBitmap1 = null
        onDrawBitmap2 = null
        onDrawResultBitmap = null
        pendingBlurResult = null
        blurJob?.cancel()
        recycleAllCache()
        crossfadeAnim.cancel()
        rotateAnim1.cancel()
        rotateAnim2.cancel()
        rotateAnim3.cancel()
        invalidate()
    }

    // ═══════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════

    private fun pauseAnims() {
        if (rotateAnim1.isStarted) {
            rotateAnim1.pause()
            rotateAnim2.pause()
            rotateAnim3.pause()
        }
    }

    private fun generateMeshVertices(): FloatArray {
        // 简化网格顶点：6x6 网格，轻微随机偏移
        val cols = 5
        val rows = 5
        val vertCount = (cols + 1) * (rows + 1) * 2
        val vertices = FloatArray(vertCount)
        for (j in 0..rows) {
            for (i in 0..cols) {
                val idx = (j * (cols + 1) + i) * 2
                vertices[idx] = i.toFloat() / cols
                vertices[idx + 1] = j.toFloat() / rows
            }
        }
        return vertices
    }

    private fun updateClipPath() {
        if (width <= 0 || height <= 0) return
        val path = Path()
        val r = topCornerRadius
        path.addRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )
        clipPath = path
    }

    // ═══════════════════════════════════════
    // onDraw — 核心渲染管线
    // ═══════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = currentBitmap
        // 无效状态：停止一切
        if (bitmap == null || bitmap.isRecycled || width == 0 || height == 0) {
            crossfadeAnim.cancel()
            crossfadeAnim.reset()
            rotateAnim1.cancel()
            rotateAnim2.cancel()
            rotateAnim3.cancel()
            return
        }

        // ── 需要重新合成 shader 的情况 ──
        if (currentShader == null || (rotateAnim1.isStarted && !rotateAnim1.isPaused)) {
            val startTime = System.currentTimeMillis()

            // 1. 计算极小渲染尺寸
            // 背景场景扩大300%，确保推开二级菜单时背景覆盖全屏
            val renderW = ((width * 3.0f) / scaleFactor).toInt().coerceAtLeast(1)
            val renderH = ((height * 3.0f) / scaleFactor).toInt().coerceAtLeast(1)

            // 2. 获取或创建缓存位图
            val cacheKey = Pair(renderW, renderH)
            val cached = bitmapCache[cacheKey]
            if (cached != null) {
                onDrawBitmap1 = cached.first
                onDrawBitmap2 = cached.second
            } else {
                // 清理旧缓存，限制最大条目
                evictOldCache()
                val bmp1 = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                val bmp2 = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                bitmapCache[cacheKey] = Pair(bmp1, bmp2)
                onDrawBitmap1 = bmp1
                onDrawBitmap2 = bmp2
            }

            // 3. 选择缓冲区（交替使用）
            if (currentBufferBitmap != onDrawBitmap1) {
                onDrawBitmap2 = onDrawBitmap1
            }
            currentBufferBitmap = onDrawBitmap2
            val buffer = currentBufferBitmap!!
            val compositeCanvas = Canvas(buffer)

            // 4. 计算封面缩放：以最大维度铺满（支持非正方形专辑图）
            val coverSize = (max(renderW, renderH) * 1.3f)
            // 使用专辑图的最大维度计算缩放比例，确保非正方形专辑图也能完整显示
            val bitmapMaxDim = max(bitmap.width, bitmap.height).coerceAtLeast(1)
            val scale = coverSize / bitmapMaxDim
            val w = renderW.toFloat()
            val h = renderH.toFloat()
            val offsetX = -(coverSize - w) / 2f
            val offsetY = -(coverSize - h) / 2f
            val halfCoverSize = coverSize / 2f

            // 饱和度画笔
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(saturationFactor)
            val saturationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            saturationPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)

            // ─── 第一层：居中，动画1旋转 ───
            val rot1 = if (rotateAnim1.isStarted) rotateAnim1.animatedValue as Float else 0f
            val matrix1 = Matrix()
            matrix1.setScale(scale, scale)
            matrix1.postRotate(rot1, halfCoverSize, halfCoverSize)
            matrix1.postTranslate(offsetX, offsetY)
            compositeCanvas.drawBitmap(bitmap, matrix1, saturationPaint)

            // ─── 第二层：左上偏移，动画2旋转 ───
            val rot2 = if (rotateAnim2.isStarted) rotateAnim2.animatedValue as Float else 0f
            val matrix2 = Matrix()
            matrix2.setScale(scale, scale)
            matrix2.postRotate(rot2, halfCoverSize, halfCoverSize)
            matrix2.postTranslate(offsetX, offsetY)
            matrix2.postTranslate(-0.95f * w, -0.7f * h)
            compositeCanvas.drawBitmap(bitmap, matrix2, saturationPaint)

            // ─── 第三层：左下偏移，动画3旋转（绕视图中心再旋转） ───
            val rot3 = if (rotateAnim3.isStarted) rotateAnim3.animatedValue as Float else 0f
            val matrix3 = Matrix()
            matrix3.setScale(scale, scale)
            matrix3.postRotate(rot3, halfCoverSize, halfCoverSize)
            matrix3.postTranslate(offsetX, offsetY)
            matrix3.postTranslate(-0.5f * w, 0.7f * h)
            matrix3.postRotate(rot3, w / 2f, h / 2f)
            compositeCanvas.drawBitmap(bitmap, matrix3, saturationPaint)

            // ─── 流动光网格变形 ───
            if (isFlowingLightRunning) {
                val verts = meshVertices
                if (verts != null) {
                    try {
                        compositeCanvas.drawBitmapMesh(buffer, 5, 5, verts, 0, null, 0, null)
                    } catch (_: Exception) {}
                }
            }

            // ─── 颜色叠加（非占位图时） ───
            if (!bitmap.sameAs(PLACEHOLDER_BITMAP)) {
                for (color in overlayColors) {
                    val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                    overlayPaint.style = Paint.Style.FILL
                    overlayPaint.color = color
                    compositeCanvas.drawPaint(overlayPaint)
                }

                // ─── 高斯模糊（异步执行，避免阻塞UI线程） ───
                val blurInput = Bitmap.createBitmap(buffer)
                blurJob?.cancel()
                blurJob = blurScope.launch {
                    try {
                        val blurred = Toolkit.blur(blurInput, blurRadius)
                        blurInput.recycle()
                        pendingBlurResult = blurred
                    } catch (_: Exception) {
                        blurInput.recycle()
                        pendingBlurResult = null
                    }
                    post {
                        currentShader = null
                        invalidate()
                    }
                }
                onDrawResultBitmap = pendingBlurResult ?: buffer
            } else {
                onDrawResultBitmap = buffer
            }

            // ─── 创建 BitmapShader(MIRROR) ───
            val resultBmp = onDrawResultBitmap ?: buffer
            val shaderWidth = resultBmp.width.toFloat()
            val shaderHeight = resultBmp.height.toFloat()

            val localMatrix = Matrix(shaderMatrix)
            localMatrix.preTranslate(
                -(shaderWidth - shaderWidth / 1.3f) / 2f,
                -(shaderHeight - shaderHeight / 1.3f) / 2f
            )

            currentShader = BitmapShader(resultBmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            currentShader?.setLocalMatrix(localMatrix)
            currentPaint.shader = currentShader

            // FPS 控制：如果帧耗时 > 15ms，增加延迟计数器
            if (!isFlowingLightRunning && rotateAnim1.isStarted && !rotateAnim1.isPaused) {
                if (System.currentTimeMillis() - startTime > 15) {
                    meshDelayCounter++
                } else {
                    meshDelayCounter = 0
                }
                if (meshDelayCounter > 3) {
                    // 性能不足，跳帧
                    post { pauseAnims() }
                }
            }
        }

        // ── 绘制 previous shader（淡出） ──
        val path = clipPath
        if (previousShader != null && path != null) {
            canvas.drawPath(path, previousPaint)
        }

        // ── 绘制 current shader（淡入） ──
        if (path != null) {
            canvas.drawPath(path, currentPaint)
        }

        // ── 处理淡入淡出动画 ──
        if (crossfadeAnim.hasStarted() && !crossfadeAnim.hasEnded()) {
            crossfadeAnim.getTransformation(System.currentTimeMillis(), crossfadeTransformation)
            currentPaint.alpha = ((1f - crossfadeTransformation.alpha) * 255f).toInt()
            postInvalidateDelayed(FRAME_DELAY)
            return
        }

        // ── 动画循环 ──
        if (rotateAnim1.isStarted) {
            if (!rotateAnim1.isPaused && isDynamic && allowDynamicRunning) {
                postInvalidateDelayed(FRAME_DELAY)
                frameCounter++
                if (frameCounter == 12) {
                    frameCounter = 0
                    fpsTimestamp1 = fpsTimestamp2
                    fpsTimestamp2 = System.currentTimeMillis()
                }
                return
            }
            return
        }

        // ── 动画未启动：设置初始状态并启动 ──
        previousPaint.alpha = 255
        currentPaint.alpha = 255

        // 如果有待处理的位图
        val pending = pendingBitmap
        if (pending != null) {
            pendingBitmap = null
            setArtwork(pending)
            return
        }

        // 启动旋转动画
        rotateAnim1.end()
        rotateAnim2.end()
        rotateAnim3.end()
        rotateAnim1.start()
        rotateAnim2.start()
        rotateAnim3.start()
        invalidate()
    }

    // ═══════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipPath()
        // 尺寸变化时重新设置封面
        val pending = pendingBitmap
        if (pending != null) {
            pendingBitmap = null
            setArtwork(pending)
        } else {
            currentBitmap?.let { setArtwork(it) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blurJob?.cancel()
        blurScope.cancel()
        recycleAllCache()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            pauseAnims()
            blurJob?.cancel()
        }
    }

    // ═══════════════════════════════════════
    // 缓存管理
    // ═══════════════════════════════════════

    private fun evictOldCache() {
        if (bitmapCache.size >= MAX_CACHE_ENTRIES) {
            val oldest = bitmapCache.keys.firstOrNull() ?: return
            bitmapCache.remove(oldest)?.let { pair ->
                if (!pair.first.isRecycled) pair.first.recycle()
                if (!pair.second.isRecycled) pair.second.recycle()
            }
        }
    }

    private fun recycleAllCache() {
        for ((_, pair) in bitmapCache) {
            if (!pair.first.isRecycled) pair.first.recycle()
            if (!pair.second.isRecycled) pair.second.recycle()
        }
        bitmapCache.clear()
    }
}
