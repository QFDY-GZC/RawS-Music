package com.rawsmusic.core.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.rawsmusic.core.common.model.LyricLine
import android.graphics.BlurMaskFilter
import android.util.Log
import com.rawsmusic.core.ui.widget.lyric.LyricEffectManager
import com.rawsmusic.core.ui.widget.lyric.LineConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU 高斯模糊实现，基于标准分离式高斯核。
 * 优化版本：降低分辨率、复用数组、缓存内核。
 * 所有 Android 版本一致的真正模糊效果。
 */
object KugouBlur {
    // 模糊分辨率缩放因子 (1/2 分辨率，像素量降为 1/4)
    private const val BLUR_SCALE = 0.5f
    // 最大模糊半径限制
    private const val MAX_RADIUS = 15
    // 内核缓存 (避免重复计算)
    private val kernelCache = HashMap<Int, FloatArray>()
    // 复用的像素数组 (避免频繁分配)
    private var reusableSrcPixels: IntArray? = null
    private var reusableDstPixels: IntArray? = null
    
    /**
     * 对 Bitmap 执行高斯模糊 (优化版)
     * @param source 源 Bitmap（会被读取但不会修改）
     * @param blurRadius 模糊半径（像素）
     * @return 模糊后的新 Bitmap，调用方负责 recycle
     */
    fun blurBitmap(source: Bitmap, blurRadius: Float): Bitmap {
        if (blurRadius < 1f) return source

        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return source

        // 降低分辨率进行模糊 (性能提升约 4x)
        val blurW = (srcW * BLUR_SCALE).toInt().coerceAtLeast(1)
        val blurH = (srcH * BLUR_SCALE).toInt().coerceAtLeast(1)
        
        // 缩小源 Bitmap
        val scaledSource = Bitmap.createScaledBitmap(source, blurW, blurH, false)
        
        val radius = (blurRadius * BLUR_SCALE).roundToInt().coerceIn(1, MAX_RADIUS)
        val kernel = getKernel(radius)
        
        // 复用像素数组
        val pixelCount = blurW * blurH
        val srcPixels = getReusableArray(reusableSrcPixels, pixelCount).also { reusableSrcPixels = it }
        val hPass = getReusableArray(reusableDstPixels, pixelCount).also { reusableDstPixels = it }
        
        scaledSource.getPixels(srcPixels, 0, blurW, 0, 0, blurW, blurH)

        // 水平 pass
        convolveHorizontal(srcPixels, hPass, blurW, blurH, kernel, radius)

        // 垂直 pass (直接写入目标 Bitmap)
        val dest = Bitmap.createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
        val destPixels = IntArray(pixelCount)
        convolveVertical(hPass, destPixels, blurW, blurH, kernel, radius)
        dest.setPixels(destPixels, 0, blurW, 0, 0, blurW, blurH)
        
        // 如果缩小了，需要缩放回原尺寸
        return if (blurW != srcW || blurH != srcH) {
            val result = Bitmap.createScaledBitmap(dest, srcW, srcH, true)
            dest.recycle()
            if (scaledSource !== source) scaledSource.recycle()
            result
        } else {
            if (scaledSource !== source) scaledSource.recycle()
            dest
        }
    }
    
    private fun getReusableArray(existing: IntArray?, needed: Int): IntArray {
        return if (existing != null && existing.size >= needed) existing else IntArray(needed)
    }
    
    private fun getKernel(radius: Int): FloatArray {
        return kernelCache.getOrPut(radius) { buildKernel(radius) }
    }

    private fun buildKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        val sigma = radius / 2.5f
        val sigma2 = 2f * sigma * sigma
        var sum = 0f
        for (i in 0 until size) {
            val x = i - radius
            kernel[i] = kotlin.math.exp(-(x * x) / sigma2)
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    private fun convolveHorizontal(
        src: IntArray, dst: IntArray, w: Int, h: Int,
        kernel: FloatArray, radius: Int
    ) {
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val pixel = src[rowOffset + sx]
                    val weight = kernel[k + radius]
                    a += ((pixel ushr 24) and 0xFF) * weight
                    r += ((pixel ushr 16) and 0xFF) * weight
                    g += ((pixel ushr 8) and 0xFF) * weight
                    b += (pixel and 0xFF) * weight
                }
                dst[rowOffset + x] =
                    (a.roundToInt().coerceIn(0, 255) shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
    }

    private fun convolveVertical(
        src: IntArray, dst: IntArray, w: Int, h: Int,
        kernel: FloatArray, radius: Int
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val pixel = src[sy * w + x]
                    val weight = kernel[k + radius]
                    a += ((pixel ushr 24) and 0xFF) * weight
                    r += ((pixel ushr 16) and 0xFF) * weight
                    g += ((pixel ushr 8) and 0xFF) * weight
                    b += (pixel and 0xFF) * weight
                }
                dst[y * w + x] =
                    (a.roundToInt().coerceIn(0, 255) shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
    }
}

class SingleLineLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Lottie 动画效果管理器 (从 JSON 加载参数，失败时使用硬编码 fallback)
    private val effectManager = LyricEffectManager(context)
    
    // 从 JSON 加载的 Line 配置 (懒加载，可能为 null)
    private val line0Config: LineConfig? by lazy { 
        Log.i(TAG, "line0Config 初始化，调用 effectManager.getLine0Config()")
        effectManager.getLine0Config() 
    }
    private val line1Config: LineConfig? by lazy { 
        Log.i(TAG, "line1Config 初始化，调用 effectManager.getLine1Config()")
        effectManager.getLine1Config() 
    }
    
    init {
        Log.i(TAG, "SingleLineLyricView 初始化完成")
    }

    companion object {
        private const val TAG = "SingleLineLyricView"
        // 酷狗 Lottie 设计稿尺寸
        private const val DESIGN_WIDTH = 1080f
        private const val DESIGN_HEIGHT = 2700f

        // 设计稿字号 (像素, 1080px 画布)
        private const val DESIGN_TEXT_SIZE_PX = 110f
        private const val DESIGN_TRANS_SIZE_PX = 70f
        private const val LINE_SPACING_MULTIPLIER = 0.22f
        // 歌词整体下移偏移 (设计稿像素)
        private const val LYRIC_Y_OFFSET_DESIGN = 600f

        // 设计稿布局坐标 (像素, 1080×2700 画布)
        // Line0: p=[223.262, 875.7, 0], a=[-4.756, -47.56, 0]
        //        实际文本基线 Y = 875.7 - (-47.56) = 923.26
        // Line1: p=[1342.318, 1090.366, 0], a=[1132.256, -45.92, 0]
        //        实际 X = 1342.318 - 1132.256 = 210.062, Y = 1090.366 - (-45.92) = 1136.286
        private const val LINE0_FINAL_X_DESIGN = 55.262f
        private const val LINE0_Y_DESIGN = 923.26f
        private const val LINE1_X_DESIGN = 210.062f
        private const val LINE1_Y_DESIGN = 1136.286f
        private const val TRANSLATION_Y_DESIGN = 2500f  // 翻译区域 Y (估算)

        // 动画时长 (基于24fps)
        private const val TOTAL_ANIM_MS = 3250L           // 0→78帧: 78/24*1000
        private const val END_FADE_WINDOW_MS = 467L        // (105.2-94)/24*1000
        private const val FLY_OUT_DURATION = 1000L
        private const val DEFAULT_LINE_DURATION_MS = 3000L

        // ========== 酷狗 Lottie 时序参数 (毫秒) ==========
        private const val LINE0_START_MS = 0L
        private const val LINE0_END_MS = 1983L             // 47.6/24*1000
        private const val LINE1_START_MS = 1283L            // 30.8/24*1000
        private const val LINE1_END_MS = 3250L              // 78/24*1000

        // 缩放 (酷狗 Lottie 原版)
        private const val LINE0_SCALE_FROM = 1.3f           // 130%
        private const val LINE1_SCALE_FROM = 1.2f           // 120%

        // 位移X (Line0 从右侧滑入, 设计稿像素)
        // Lottie: posX 223→55.262, 移动 167.738px on 1080px canvas
        private const val LINE0_TRANSLATE_X_DESIGN = 167.738f

        // ========== 逐字符 Cascade 属性 ==========
        // Line0: position [0,-200,0]→[0,0,0], scale 220%→100%
        // Line1: scale 220% 恒定, 无位移
        private const val CHAR_Y_OFFSET_DESIGN = 200f       // 设计稿像素
        private const val CHAR_SCALE_FROM = 2.2f            // 220%

        // ========== Line0 独立时间线比例 (帧号→比例, 基于 Line0 总帧 47.6) ==========
        // 位移: 0→28.4帧, 完成比例 = 28.4/47.6 = 0.5966
        // 模糊: 12.754→47.6帧, 起始比例 = 12.754/47.6 = 0.2680, 结束 = 1.0
        // 透明度: 28.91→47.6帧, 起始比例 = 28.91/47.6 = 0.6074, 结束 = 1.0
        private const val CHAR_POS_COMPLETE_RATIO = 0.5966f
        private const val CHAR_BLUR_START_RATIO = 0.2680f
        private const val CHAR_OPACITY_START_RATIO = 0.6074f

        // ========== Line1 独立时间线比例 (基于 Line1 区间 30.8→78 帧, 总长 47.2 帧) ==========
        // 模糊: 30.8→65.6457帧, 结束比例 = (65.6457-30.8)/47.2 ≈ 0.7382 (从头开始减模糊)
        // 透明度: 59.31→78帧, 起始比例 = (59.31-30.8)/47.2 ≈ 0.6040
        private const val CHAR_BLUR_END_RATIO_LINE1 = 0.7382f
        private const val CHAR_OPACITY_START_RATIO_LINE1 = 0.6040f

        // 模糊: AE 100% ≈ 字号的 50%
        private const val BLUR_FONT_RATIO = 0.5f

        // 飞出动画
        private const val FLYOUT_TRANSLATE_X_RATIO = 0.08f
        private const val FLYOUT_TRANSLATE_Y_RATIO = 0.12f

        // 模糊缓存
        private const val BLUR_QUANT = 2  // 模糊半径量化步长(像素)
        private const val MAX_BLUR_CACHE = 200  // 缓存上限条目
    }

    private val density = resources.displayMetrics.density
    private val horizontalPadding = 20f * density

    // 按设计稿缩放的字号和阴影 (在 onSizeChanged 中初始化)
    private var scaledTextSize = DESIGN_TEXT_SIZE_PX  // 默认值, onSizeChanged 后更新
    private var scaledTransSize = DESIGN_TRANS_SIZE_PX
    private var baseShadowRadius = 4f * density
    private val baseShadowDx = 0f
    private val baseShadowDy = 2f * density
    private val baseShadowColor = Color.argb(80, 0, 0, 0)

    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledTextSize
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
    }

    private val transTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledTransSize
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledTextSize
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledTextSize
        color = Color.parseColor("#60FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
    }

    // 逐字符模糊绘制用的临时 paint
    private val charPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    // 模糊Bitmap缓存 (字符索引_模糊半径 -> Bitmap)
    private val blurredBitmapCache = HashMap<Int, Bitmap>()

    private var currentText: String = ""
    private var currentTranslation: String? = null
    private var currentLine: LyricLine? = null
    private var currentPositionMs: Long = 0L
    private var skipAnimation: Boolean = false

    fun setLyricTypeface(typeface: Typeface?) {
        mainTextPaint.typeface = typeface
        transTextPaint.typeface = typeface
        highlightPaint.typeface = typeface
        dimPaint.typeface = typeface
        charPaint.typeface = typeface
        invalidate()
    }

    // ========== 设计稿坐标映射 ==========
    /** 设计稿像素 → 屏幕像素 (统一基于 View 宽度缩放, 保证文字和间距比例正确) */
    private fun designPxToScreen(designPx: Float): Float {
        return if (width > 0) designPx * (width / DESIGN_WIDTH) else designPx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val scale = w / DESIGN_WIDTH
        scaledTextSize = DESIGN_TEXT_SIZE_PX * scale
        scaledTransSize = DESIGN_TRANS_SIZE_PX * scale
        baseShadowRadius = scaledTextSize * 0.036f  // 4/110 ≈ 0.036

        mainTextPaint.textSize = scaledTextSize
        mainTextPaint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
        transTextPaint.textSize = scaledTransSize
        transTextPaint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
        highlightPaint.textSize = scaledTextSize
        highlightPaint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
        dimPaint.textSize = scaledTextSize
        dimPaint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
    }

    // ========== 模糊渲染 ==========

    /**
     * CPU 高斯模糊绘制字符 (全 Android 版本一致)
     * 使用模糊 Bitmap 缓存：同一歌词周期内，相同字符+相同模糊半径直接复用。
     * 模糊半径量化为 BLUR_QUANT 步长，大幅减少缓存 miss。
     */

    private fun drawBlurredChar(
        canvas: Canvas,
        text: String,
        charIndex: Int,
        x: Float,
        baseline: Float,
        paint: Paint,
        blurRadius: Float,
        alpha: Int
    ) {
        // 调试日志：每100帧打印一次模糊信息
        if (charIndex == 0 && animTimeMs % 500 < 20) {
            Log.d("LyricBlur", "animTime=${animTimeMs}ms, blurRadius=$blurRadius, alpha=$alpha")
        }
        
        if (blurRadius < 1f) {
            paint.alpha = alpha
            paint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
            canvas.drawText(text, charIndex, charIndex + 1, x, baseline, paint)
            return
        }

        val fm = paint.fontMetrics
        val charWidth = paint.measureText(text, charIndex, charIndex + 1)
        val intRadius = blurRadius.roundToInt().coerceIn(1, 15)
        // 量化模糊半径以提高缓存命中率 (例如步长2: 1,3,5→2,4,6; 7,9→8,10)
        val quantizedRadius = (((intRadius + BLUR_QUANT / 2) / BLUR_QUANT) * BLUR_QUANT).coerceAtMost(15)
        val padding = quantizedRadius * 2 + 2

        val cacheKey = charIndex * 100 + quantizedRadius
        var cachedBmp = blurredBitmapCache[cacheKey]

        if (cachedBmp == null || cachedBmp.isRecycled) {
            val charHeight = fm.descent - fm.ascent
            val bmpW = (charWidth + padding * 2).toInt()
            val bmpH = (charHeight + padding * 2).toInt()
            if (bmpW <= 0 || bmpH <= 0) return

            val charBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val offCanvas = Canvas(charBmp)
            offCanvas.drawText(text, charIndex, charIndex + 1, padding.toFloat(), padding - fm.ascent, paint)

            cachedBmp = KugouBlur.blurBitmap(charBmp, quantizedRadius.toFloat())
            charBmp.recycle()

            // 缓存未满则存入，否则直接使用不缓存
            if (blurredBitmapCache.size < MAX_BLUR_CACHE) {
                blurredBitmapCache[cacheKey] = cachedBmp
            }
        }

        val destX = x - padding
        val destY = baseline + fm.ascent - padding
        val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        canvas.drawBitmap(cachedBmp, destX, destY, blurPaint)
    }

    private fun clearBlurCache() {
        for (bmp in blurredBitmapCache.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        blurredBitmapCache.clear()
    }

    // ========== 酷狗架构：每行独立动画状态 ==========
    private data class LineAnimState(
        var revealProgress: Float = 0f,   // 0→1: 逐字符揭示进度
        var scale: Float = 1.0f,          // 缩放
        var alpha: Float = 1f,            // 透明度
    )

    private var animTimeMs = 0L           // 主动画时间 0→3250ms
    private var fadeOutAlpha = 1f         // 歌词结束淡出
    private var isAnimating = false
    private var isFlyingOut = false
    private var currentAnimator: ValueAnimator? = null
    private var animationGeneration = 0

    data class WrappedLine(
        val text: String,
        val charStart: Int,
        val charEnd: Int,
        val breakBefore: Boolean = false,
        // === 酷狗风格拆分：携带逐词时间 ===
        val words: List<com.rawsmusic.core.common.model.LyricWord> = emptyList(),
        val lineStartTime: Long = 0L,   // 该子行第一个词的 begin
        val lineEndTime: Long = 0L,     // 该子行最后一个词的 end
        val subLineIndex: Int = 0       // 在同一逻辑行中的子行序号
    ) {
        /** 是否有逐词时间数据 */
        val hasWordTiming: Boolean get() = words.isNotEmpty()
    }

    // ========== 缓动函数 (酷狗 Lottie 原版贝塞尔曲线) ==========
    private fun easeOutCubic(t: Float): Float {
        val t1 = t.coerceIn(0f, 1f)
        return 1f - (1f - t1) * (1f - t1) * (1f - t1)
    }

    // Line0 scale: cubicBezier(0.333, 0, 0.27, 1)
    private fun kugouLine0ScaleEase(t: Float): Float {
        return cubicBezier(t.coerceIn(0f, 1f), 0.333f, 0f, 0.27f, 1f)
    }

    // Line1 scale: cubicBezier(0.333, 0, 0.26, 1)
    private fun kugouLine1ScaleEase(t: Float): Float {
        return cubicBezier(t.coerceIn(0f, 1f), 0.333f, 0f, 0.26f, 1f)
    }

    // Line0 translate: cubicBezier(0.167, 0.12, 0.05, 1)
    private fun kugouTranslateEase(t: Float): Float {
        return cubicBezier(t.coerceIn(0f, 1f), 0.167f, 0.12f, 0.05f, 1f)
    }

    private fun cubicBezier(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val u = 1f - t
        return (u * u * u * p0) + (3f * u * u * t * p1) + (3f * u * t * t * p2) + (t * t * t * p3)
    }

    // ========== 逐行动画状态计算 (基于毫秒时间) ==========
    // 使用 LottieMathEngine 的标准关键帧采样
    private fun calcLine0State(timeMs: Long, hasMultipleLines: Boolean): LineAnimState {
        val config = line0Config ?: return LineAnimState(revealProgress = 1f, scale = 1f, alpha = 1f)
        val frame = timeMs.toFloat() / 1000f * effectManager.getFps()
        
        // Range Offset 驱动揭示进度
        val rangeOffset = config.getRangeOffset(frame)
        // 将 -100%~100% 映射为 0~1 的揭示进度
        val revealProgress = ((rangeOffset + 1f) / 2f).coerceIn(0f, 1f)
        
        // 图层缩放
        val scaleArr = config.getScale(frame)
        val scale = if (scaleArr.isNotEmpty()) scaleArr[0] / 100f else 1f
        
        return LineAnimState(
            revealProgress = revealProgress,
            scale = scale,
            alpha = 1f
        )
    }

    private fun calcLine1State(timeMs: Long): LineAnimState {
        val config = line1Config ?: return LineAnimState(revealProgress = 1f, scale = 1f, alpha = 1f)
        val frame = timeMs.toFloat() / 1000f * effectManager.getFps()
        
        // Range Offset 驱动揭示进度
        val rangeOffset = config.getRangeOffset(frame)
        val revealProgress = ((rangeOffset + 1f) / 2f).coerceIn(0f, 1f)
        
        // 图层缩放
        val scaleArr = config.getScale(frame)
        val scale = if (scaleArr.isNotEmpty()) scaleArr[0] / 100f else 1f
        
        return LineAnimState(
            revealProgress = revealProgress,
            scale = scale,
            alpha = 1f
        )
    }

    // ========== 公开 API ==========
    fun showLyric(line: LyricLine, positionMs: Long) {
        skipAnimation = line.skipAnimation

        // 短歌词自动跳过动画：显示时间 < 2秒则直接显示最终状态
        val minAnimDuration = 2000L
        val lineDuration = (line.endTime - line.timeStamp).coerceAtLeast(0)
        if (lineDuration < minAnimDuration) {
            skipAnimation = true
        }

        currentLine = line
        if (currentText != line.text) {
            clearBlurCache()
        }
        currentText = line.text
        currentTranslation = line.translation.takeIf { it.isNotBlank() }
        currentPositionMs = positionMs

        animationGeneration++
        currentAnimator?.cancel()
        isFlyingOut = false

        if (skipAnimation) {
            // 核心动画时长：取 Line0/Line1 的最后一个关键帧时间
            val totalAnimMs = maxOf(line0Config?.endMs ?: 0f, line1Config?.endMs ?: 0f).toLong()
            animTimeMs = totalAnimMs
            fadeOutAlpha = 1f
            isAnimating = false
            updateDisplayAlpha()
            invalidate()
        } else if (width == 0 || height == 0) {
            animTimeMs = 0L
            fadeOutAlpha = 1f
            updateDisplayAlpha()
            post { playFlyInAnimation() }
        } else {
            playFlyInAnimation()
        }
    }

    fun hideLyric(listener: (() -> Unit)? = null) {
        animationGeneration++
        currentAnimator?.cancel()
        playFlyOutAnimation(listener)
    }

    fun updatePosition(positionMs: Long) {
        currentPositionMs = positionMs
        if (!isAnimating) {
            updateDisplayAlpha()
        }
        if (currentLine?.hasWordTiming == true) {
            invalidate()
        }
    }

    fun showImmediate(text: String, translation: String? = null) {
        currentText = text
        currentTranslation = translation
        currentLine = null
        // 核心动画时长：取 Line0/Line1 的最后一个关键帧时间
        val totalAnimMs = maxOf(line0Config?.endMs ?: 0f, line1Config?.endMs ?: 0f).toLong()
        animTimeMs = totalAnimMs
        fadeOutAlpha = 1f
        isAnimating = false
        isFlyingOut = false
        updateDisplayAlpha()
        invalidate()
    }

    fun clear() {
        currentText = ""
        currentTranslation = null
        currentLine = null
        animationGeneration++
        currentAnimator?.cancel()
        animTimeMs = 0L
        fadeOutAlpha = 0f
        isAnimating = false
        isFlyingOut = false
        clearBlurCache()
        updateDisplayAlpha()
        invalidate()
    }

    // ========== 飞入动画 (毫秒时间驱动) ==========
    private fun playFlyInAnimation() {
        val gen = animationGeneration
        isAnimating = true
        isFlyingOut = false
        animTimeMs = 0L

        // 核心动画时长：取 Line0/Line1 的最后一个关键帧时间
        val totalAnimMs = maxOf(line0Config?.endMs ?: 0f, line1Config?.endMs ?: 0f).toLong()
        
        currentAnimator = ValueAnimator.ofFloat(0f, totalAnimMs.toFloat()).apply {
            duration = totalAnimMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (gen != animationGeneration) return@addUpdateListener
                animTimeMs = (anim.animatedValue as Float).toLong()
                updateDisplayAlpha()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gen != animationGeneration) return
                    isAnimating = false
                    isFlyingOut = false
                    animTimeMs = totalAnimMs
                    updateDisplayAlpha()
                    invalidate()
                }
            })
            start()
        }
    }

    // ========== 飞出动画 ==========
    private fun playFlyOutAnimation(listener: (() -> Unit)? = null) {
        val gen = animationGeneration
        isAnimating = true
        isFlyingOut = true
        animTimeMs = 0L

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLY_OUT_DURATION
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (gen != animationGeneration) return@addUpdateListener
                animTimeMs = ((anim.animatedValue as Float) * FLY_OUT_DURATION).toLong()
                updateDisplayAlpha()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gen != animationGeneration) return
                    isAnimating = false
                    isFlyingOut = false
                    animTimeMs = 0L
                    fadeOutAlpha = 0f
                    updateDisplayAlpha()
                    invalidate()
                    listener?.invoke()
                }
            })
            start()
        }
    }

    private fun updateDisplayAlpha() {
        val timeAlpha = computeEndFadeAlpha()
        super.setAlpha((fadeOutAlpha * timeAlpha).coerceIn(0f, 1f))
    }

    private fun computeEndFadeAlpha(): Float {
        val line = currentLine ?: return 1f
        val remaining = getRemainingMs(line, currentPositionMs)
        if (remaining >= END_FADE_WINDOW_MS) return 1f
        return (remaining.toFloat() / END_FADE_WINDOW_MS.toFloat()).coerceIn(0f, 1f)
    }

    private fun getRemainingMs(line: LyricLine, positionMs: Long): Long {
        val endTime = when {
            line.endTime > 0L -> line.endTime
            line.hasWordTiming -> line.words.lastOrNull()?.end?.takeIf { it > line.timeStamp }
                ?: (line.timeStamp + DEFAULT_LINE_DURATION_MS)
            else -> line.timeStamp + DEFAULT_LINE_DURATION_MS
        }
        return (endTime - positionMs).coerceAtLeast(0L)
    }

    // ========== 绘制主入口 ==========
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || currentText.isEmpty()) return

        val availableWidth = (width - paddingLeft - paddingRight - horizontalPadding * 2f).coerceAtLeast(1f)

        // 使用设计稿 Y 坐标作为基准 (统一宽度缩放, 从 JSON 加载)
        val frame = animTimeMs.toFloat() / 1000f * effectManager.getFps()
        val line0Pos = line0Config?.getPosition(frame) ?: floatArrayOf(0f, 923.26f, 0f)
        val line0Anchor = line0Config?.getAnchor(frame) ?: floatArrayOf(0f, 0f, 0f)
        val line0BaseY = (if (line0Pos.size >= 2) line0Pos[1] else 0f) + (if (line0Anchor.size >= 2) line0Anchor[1] else 0f)
        val titleTop = designPxToScreen(line0BaseY)

        val transText = currentTranslation?.takeIf { it.isNotBlank() }
        val transLines = transText?.let { splitTranslationText(it, transTextPaint, availableWidth) }

        // 用全宽计算换行 (设计稿坐标中的 X 在 drawLyricLines 中单独处理)
        val titleWidth = (width - 24f * density).coerceAtLeast(1f)
        val mainLines = buildMainDisplayLines(currentText, mainTextPaint, titleWidth)
        val mainFm = mainTextPaint.fontMetrics
        val mainLineHeight = mainFm.descent - mainFm.ascent
        val mainLineSpacing = mainLineHeight * LINE_SPACING_MULTIPLIER
        val mainBaselineStart = titleTop + mainLineHeight + designPxToScreen(LYRIC_Y_OFFSET_DESIGN)

        // 绘制主歌词（酷狗架构：逐行独立动画，使用设计稿坐标）
        drawLyricLines(
            canvas = canvas,
            segments = mainLines,
            startBaseline = mainBaselineStart,
            lineHeight = mainLineHeight,
            lineSpacing = mainLineSpacing
        )

        // 绘制翻译
        if (transLines != null) {
            val transFm = transTextPaint.fontMetrics
            val transLineHeight = transFm.descent - transFm.ascent
            val transLineSpacing = transLineHeight * LINE_SPACING_MULTIPLIER
            val centerX = width / 2f
            val transBlockHeight = transLines.size * transLineHeight + (transLines.size - 1) * transLineSpacing
            val translationTop = (designPxToScreen(TRANSLATION_Y_DESIGN) - transBlockHeight).coerceAtLeast(paddingTop.toFloat())
            drawTextLines(canvas, transLines, transTextPaint, transTextPaint, transTextPaint, centerX, translationTop, transLineHeight, transLineSpacing, false, null)
        }
    }

    // ========== 酷狗架构核心：逐行独立动画绘制 ==========
    private fun drawLyricLines(
        canvas: Canvas,
        segments: List<WrappedLine>,
        startBaseline: Float,
        lineHeight: Float,
        lineSpacing: Float
    ) {
        if (segments.isEmpty()) return

        // 按 breakBefore 分组为逻辑行
        val logicalLines = groupIntoLogicalLines(segments)
        val hasMultipleLines = logicalLines.size > 1

        // 计算每逻辑行动画状态 (基于毫秒时间)
        val lineStates = logicalLines.mapIndexed { index, _ ->
            if (index == 0) calcLine0State(animTimeMs, hasMultipleLines)
            else calcLine1State(animTimeMs)
        }

        // 绘制每逻辑行
        val frame = animTimeMs.toFloat() / 1000f * effectManager.getFps()
        var subLineOffsetY = 0f  // 子行在本地坐标中的 Y 偏移

        logicalLines.forEachIndexed { lineIdx, lineSegments ->
            val state = lineStates[lineIdx]
            val lineConfig = if (lineIdx == 0) line0Config else line1Config
            if (lineConfig == null) return@forEachIndexed

            // 当前 position（设计稿像素，从 Lottie 关键帧采样）
            val posArr = lineConfig.getPosition(frame)
            val currentPosX = designPxToScreen(if (posArr.size >= 2) posArr[0] else 0f)
            val currentPosY = designPxToScreen(if (posArr.size >= 2) posArr[1] else 0f)

            // 锚点（屏幕像素）
            val anchorArr = lineConfig.getAnchor(frame)
            val anchorX = designPxToScreen(if (anchorArr.size >= 2) anchorArr[0] else 0f)
            val anchorY = designPxToScreen(if (anchorArr.size >= 2) anchorArr[1] else 0f)

            // 拆分子行（wrap）共享同一逻辑行动画状态，每个子行独立绘制在不同 Y 位置
            for (segment in lineSegments) {
                if (state.alpha <= 0.01f) {
                    subLineOffsetY += lineHeight + lineSpacing
                    continue
                }

                val segText = segment.text
                if (segText.isBlank()) {
                    subLineOffsetY += lineHeight + lineSpacing
                    continue
                }

                canvas.save()

                // 1) 应用飞出位移
                if (isFlyingOut) {
                    val flyoutProgress = (animTimeMs.toFloat() / FLY_OUT_DURATION).coerceIn(0f, 1f)
                    val flyoutTx = -width * FLYOUT_TRANSLATE_X_RATIO * easeOutCubic(flyoutProgress)
                    val flyoutTy = height * FLYOUT_TRANSLATE_Y_RATIO * easeOutCubic(flyoutProgress)
                    canvas.translate(flyoutTx, flyoutTy)
                }

                // 2) Lottie 标准变换顺序: Position → (-Anchor) → Scale → (Anchor)
                canvas.translate(currentPosX, currentPosY)        // ① Position
                canvas.translate(-anchorX, -anchorY)              // ② 移动到锚点
                canvas.scale(state.scale, state.scale)            // ③ 缩放
                canvas.translate(anchorX, anchorY)                // ④ 恢复

                // 3) 子行 Y 偏移（本地坐标，被缩放影响）
                if (subLineOffsetY != 0f) {
                    canvas.translate(0f, subLineOffsetY)
                }

                // 4) 逐字符 cascade 揭示
                // 本地坐标: x=0, baseline=-anchorY（锚点到文本基线的偏移）
                // anchorY 为负值（-47.56），所以 -anchorY 为正，文本基线在锚点下方
                val localBaselineY = -anchorY
                drawCascadeRevealText(
                    canvas, segText, 0f, localBaselineY, mainTextPaint,
                    state.revealProgress, state.alpha, lineIdx,
                    wordTimedSegments = listOf(segment),
                    lineScale = state.scale
                )

                canvas.restore()

                subLineOffsetY += lineHeight + lineSpacing
            }

            // Line0 与 Line1 之间的额外间距（仅当有两个独立歌词行时）
            if (lineIdx > 0 && lineIdx < logicalLines.lastIndex) {
                val line0Pos = line0Config?.getPosition(frame) ?: floatArrayOf(0f, 923.26f, 0f)
                val line1Pos = line1Config?.getPosition(frame) ?: floatArrayOf(0f, 1136.286f, 0f)
                val line0Anchor = line0Config?.getAnchor(frame) ?: floatArrayOf(0f, 0f, 0f)
                val line1Anchor = line1Config?.getAnchor(frame) ?: floatArrayOf(0f, 0f, 0f)
                val line0Y = (if (line0Pos.size >= 2) line0Pos[1] else 0f) + (if (line0Anchor.size >= 2) line0Anchor[1] else 0f)
                val line1Y = (if (line1Pos.size >= 2) line1Pos[1] else 0f) + (if (line1Anchor.size >= 2) line1Anchor[1] else 0f)
                subLineOffsetY += designPxToScreen(line1Y - line0Y)
            }
        }
    }

    // ========== 酷狗核心：逐字符 Cascade 模糊揭示 ==========
    // Range Selector: based on characters, shape=2(Ramp Up), linear
    // 逐字属性独立时间线 (从 JSON 或 fallback 加载):
    //   位移: 0→28.4帧
    //   模糊: 12.754→47.6帧
    //   透明度: 28.91→47.6帧
    // Line0: position [0,-200,0]→[0,0,0], scale 220%→100%
    // Line1: scale 220% 恒定, 无位移
    private fun drawCascadeRevealText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        paint: Paint,
        revealProgress: Float,
        lineAlpha: Float,
        lineIndex: Int,
        wordTimedSegments: List<WrappedLine> = emptyList(),
        lineScale: Float = 1f
    ) {
        val charCount = text.length
        if (charCount == 0) return
        if (revealProgress <= 0f) return

        val isLine0 = lineIndex == 0
        val config = if (isLine0) line0Config else line1Config
        if (config == null) return
        val designToScreen = designPxToScreen(1f)

        // 快速路径：所有字符完全揭示且不透明，行级缩放回到1.0，且仅限Line0
        // Line1 有恒定 220% per-char 缩放，必须走逐字路径
        if (isLine0 && revealProgress >= 1f && lineAlpha >= 0.99f && lineScale < 1.01f) {
            paint.alpha = 255
            paint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
            val charWidthsFull = FloatArray(charCount)
            paint.getTextWidths(text, charWidthsFull)
            val totalW = charWidthsFull.sum()
            val availW = (width - x - horizontalPadding).coerceAtLeast(1f)
            val needScale = if (totalW > availW) availW / totalW else 1f
            if (needScale < 0.99f) {
                canvas.save()
                canvas.scale(needScale, needScale, x + totalW / 2f, baseline)
            }
            canvas.drawText(text, x, baseline, paint)
            if (needScale < 0.99f) {
                canvas.restore()
            }
            paint.alpha = 255
            return
        }

        // 最大模糊半径 = 字号的 blurFontRatio (AE 100% blur)
        val maxBlurRadius = scaledTextSize * BLUR_FONT_RATIO

        val charWidths = FloatArray(charCount)
        paint.getTextWidths(text, charWidths)

        charPaint.textSize = paint.textSize
        charPaint.color = paint.color
        charPaint.isFakeBoldText = paint.isFakeBoldText
        charPaint.typeface = paint.typeface

        // ===== 构建逐字符 reveal 进度数组 =====
        // 有逐词时间 → 用 currentPositionMs 驱动
        // 无逐词时间 → 用 revealProgress 线性映射
        val charReveals = FloatArray(charCount)
        val hasWordTiming = wordTimedSegments.any { it.hasWordTiming }

        if (hasWordTiming && currentLine != null) {
            // 酷狗模式：逐词时间驱动
            buildCharRevealsFromWords(charReveals, wordTimedSegments, charCount)
        } else {
            // 回退模式：线性映射
            val headPos = revealProgress * charCount
            if (config.rangeRandomize) {
                // 随机字符顺序：使用文本哈希作为种子，确保同一文本的随机顺序一致
                val randomOrder = (0 until charCount).toMutableList().apply {
                    shuffle(java.util.Random(text.hashCode().toLong()))
                }
                for (orderIndex in 0 until charCount) {
                    val charIndex = randomOrder[orderIndex]
                    charReveals[charIndex] = (headPos - orderIndex).coerceIn(0f, 1f)
                }
            } else {
                for (i in 0 until charCount) {
                    charReveals[i] = (headPos - i).coerceIn(0f, 1f)
                }
            }
        }

        // ===== 文字挤压修复: 整行缩放避免溢出 =====
        val charFrame = animTimeMs.toFloat() / 1000f * effectManager.getFps()
        val charScaleArr = config.getCharScale(charFrame)
        val maxCharScale = if (charScaleArr.isNotEmpty()) charScaleArr[0] / 100f else 1f
        val totalUnscaledWidth = charWidths.sum()
        val availableWidth = (width - x - horizontalPadding).coerceAtLeast(1f)
        // 计算最大视觉宽度（字符放大后的总宽度，包含行级缩放）
        val maxVisualWidth = totalUnscaledWidth * maxCharScale * lineScale
        val fitScale = if (maxVisualWidth > availableWidth) {
            (availableWidth / maxVisualWidth).coerceIn(0.5f, 1f)
        } else 1f

        // 用 canvas.scale 整体缩放，保持字符间距比例正确
        if (fitScale < 0.99f) {
            canvas.save()
            canvas.scale(fitScale, fitScale, x + totalUnscaledWidth / 2f, baseline)
        }

        // 使用 LottieMathEngine 采样逐字属性
        val frame = animTimeMs.toFloat() / 1000f * effectManager.getFps()
        
        var charX = x
        for (i in 0 until charCount) {
            val charReveal = charReveals[i]
            if (charReveal <= 0f) {
                charX += charWidths[i]
                continue
            }

            // ===== 使用 LottieMathEngine 采样逐字属性 =====
            // 逐字位置
            val charPos = config.getCharPosition(frame)
            val charYOffset = if (charPos.size >= 2) charPos[1] * (1f - charReveal) * designToScreen else 0f

            // 逐字缩放
            val charScaleArr = config.getCharScale(frame)
            val charScale = if (charScaleArr.isNotEmpty()) charScaleArr[0] / 100f else 1f

            // 逐字透明度
            val charOpacity = config.getCharOpacity(frame)
            val alphaInt = (charReveal * charOpacity * lineAlpha * 255).toInt().coerceIn(0, 255)

            // 逐字模糊
            val charBlurValue = config.getCharBlur(frame)
            val blurRadius = charBlurValue * (1f - charReveal) * designToScreen * 0.5f

            canvas.save()

            // 应用逐字 Y 偏移
            if (charYOffset != 0f) {
                canvas.translate(0f, charYOffset)
            }

            // 应用缩放
            if (charScale != 1f) {
                val cx = charX + charWidths[i] / 2f
                canvas.scale(charScale, charScale, cx, baseline)
            }

            // 绘制 (模糊或清晰)
            if (blurRadius > 1f) {
                drawBlurredChar(canvas, text, i, charX, baseline, charPaint, blurRadius, alphaInt)
            } else {
                charPaint.alpha = alphaInt
                charPaint.setShadowLayer(baseShadowRadius, baseShadowDx, baseShadowDy, baseShadowColor)
                canvas.drawText(text, i, i + 1, charX, baseline, charPaint)
            }

            canvas.restore()
            charX += charWidths[i]
        }

        // 恢复 fitScale 的 canvas 变换
        if (fitScale < 0.99f) {
            canvas.restore()
        }

        paint.alpha = 255
    }

    /**
     * 根据逐词时间构建每个字符的 reveal 进度 (0→1)。
     * 对应原版酷狗 m119201d() 中逐词推进的逻辑：
     * - 已过词: reveal=1
     * - 当前词: reveal=(currentMs - begin) / duration
     * - 未来词: reveal=0
     */
    private fun buildCharRevealsFromWords(
        charReveals: FloatArray,
        wordTimedSegments: List<WrappedLine>,
        totalCharCount: Int
    ) {
        val posMs = currentPositionMs
        // 合并所有子行的词列表
        val allWords = wordTimedSegments.flatMap { it.words }
        if (allWords.isEmpty()) return

        var charIdx = 0
        for (word in allWords) {
            val wordLen = word.text.length
            val wordReveal = when {
                posMs >= word.end -> 1f                     // 已过 → 完全揭示
                posMs >= word.begin && word.duration > 0 -> {  // 当前词
                    ((posMs - word.begin).toFloat() / word.duration.toFloat()).coerceIn(0f, 1f)
                }
                posMs < word.begin -> 0f                    // 未来 → 未揭示
                else -> 1f                                  // 无时间信息 → 直接揭示
            }
            // 该词的每个字符共享相同的 reveal 进度
            for (j in 0 until wordLen) {
                if (charIdx < totalCharCount) {
                    charReveals[charIdx] = wordReveal
                    charIdx++
                }
            }
        }
        // 剩余字符（如果有）设为0
        while (charIdx < totalCharCount) {
            charReveals[charIdx] = 0f
            charIdx++
        }
    }

    // ========== 将 segments 按 breakBefore 分为逻辑行 ==========
    private fun groupIntoLogicalLines(segments: List<WrappedLine>): List<List<WrappedLine>> {
        val result = mutableListOf<List<WrappedLine>>()
        var current = mutableListOf<WrappedLine>()
        for (seg in segments) {
            if (seg.breakBefore && current.isNotEmpty()) {
                result.add(current)
                current = mutableListOf()
            }
            current.add(seg)
        }
        if (current.isNotEmpty()) result.add(current)
        return if (result.isEmpty()) listOf(listOf(WrappedLine("", 0, 0))) else result
    }

    // ========== 文本分行逻辑 (酷狗风格：按词边界拆分，保留逐词时间) ==========
    private fun buildMainDisplayLines(text: String, paint: Paint, maxWidth: Float): List<WrappedLine> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return listOf(WrappedLine("", 0, 0))
        // 优先尝试酷狗风格的词边界拆分
        val wordTimingLines = checkAndSplitLine(normalized, paint, maxWidth)
        if (wordTimingLines.isNotEmpty()) return wordTimingLines
        // 无逐词时间时，退回字符级换行
        return wrapText(normalized, paint, maxWidth)
    }

    /**
     * 酷狗风格拆分：按词边界断行，保留逐词时间数据。
     * 对应原版 DuplicateLineLyricView.m119214s() 中的 checkNewLine 逻辑。
     *
     * @return 拆分后的 WrappedLine 列表（每个都携带词时间），无逐词时间时返回空列表
     */
    private fun checkAndSplitLine(text: String, paint: Paint, maxWidth: Float): List<WrappedLine> {
        val line = currentLine ?: return emptyList()
        if (!line.hasWordTiming || line.words.isEmpty()) return emptyList()

        val words = line.words
        val result = mutableListOf<WrappedLine>()
        var subLineIndex = 0

        // 累加宽度，找到断行点
        var segmentStart = 0          // 当前子行起始词索引
        var segmentCharStart = 0      // 当前子行起始字符位置
        var accumulatedWidth = 0f
        var lastSpaceWordIdx = -1     // 上一个空格词的索引（英文优先断行点）

        for (i in words.indices) {
            val word = words[i]
            val wordWidth = paint.measureText(word.text)

            // 检测空格词（英文断行优先点）
            if (word.text == " " || word.text == "\u00A0") {
                lastSpaceWordIdx = i
            }

            val newWidth = accumulatedWidth + wordWidth

            if (newWidth > maxWidth && i > segmentStart) {
                // 需要断行：决定在哪里断
                val breakIdx = if (lastSpaceWordIdx >= segmentStart) {
                    // 在上一个空格处断（空格归到前面的子行）
                    lastSpaceWordIdx + 1
                } else {
                    // 没有空格可断，就在当前词之前断
                    i
                }

                // 打包子行
                val subWords = words.subList(segmentStart, breakIdx)
                if (subWords.isNotEmpty()) {
                    val subText = subWords.joinToString("") { it.text }.trimEnd()
                    val subCharEnd = segmentCharStart + subText.length
                    result.add(WrappedLine(
                        text = subText,
                        charStart = segmentCharStart,
                        charEnd = subCharEnd,
                        breakBefore = false,  // 拆分子行不作为独立逻辑行，共享同一动画状态
                        words = subWords.toList(),
                        lineStartTime = subWords.first().begin,
                        lineEndTime = subWords.last().end,
                        subLineIndex = subLineIndex
                    ))
                    subLineIndex++
                }

                // 更新下一段的起点
                segmentStart = breakIdx
                segmentCharStart = result.lastOrNull()?.charEnd ?: segmentCharStart
                // 重新计算已消费的宽度（从 breakIdx 开始）
                accumulatedWidth = 0f
                lastSpaceWordIdx = -1
                // 回退到当前词重新累加
                // 注意：如果 breakIdx == i，当前词还没被累加，所以 continue 前不加宽度
                if (breakIdx == i) {
                    // 当前词成为新段的第一个词，继续循环会累加
                    continue
                }
                // 如果 breakIdx < i，说明断在空格处，当前词属于新段
            }

            accumulatedWidth += wordWidth
            // 更新字符位置（当段起始变化时）
            if (i == segmentStart && result.isNotEmpty()) {
                segmentCharStart = result.last().charEnd
            }
        }

        // 处理最后一段
        if (segmentStart < words.size) {
            val subWords = words.subList(segmentStart, words.size)
            val subText = subWords.joinToString("") { it.text }.trimEnd()
            if (subText.isNotEmpty()) {
                result.add(WrappedLine(
                    text = subText,
                    charStart = segmentCharStart,
                    charEnd = segmentCharStart + subText.length,
                    breakBefore = false,  // 拆分子行不作为独立逻辑行
                    words = subWords.toList(),
                    lineStartTime = subWords.first().begin,
                    lineEndTime = subWords.last().end,
                    subLineIndex = subLineIndex
                ))
            }
        }

        return result.ifEmpty { emptyList() }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<WrappedLine> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return listOf(WrappedLine("", 0, 0))
        val lines = mutableListOf<WrappedLine>()
        var baseIndex = 0
        var lineIndex = 0
        normalized.split('\n').forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isBlank()) {
                lines.add(WrappedLine("", baseIndex, baseIndex))
            } else {
                val hasSpaces = paragraph.contains(' ')
                var start = 0
                while (start < paragraph.length) {
                    val lastFit = if (hasSpaces) {
                        findBreakAtSpace(paragraph, start, paint, maxWidth)
                    } else {
                        findBreakByWidth(paragraph, start, paint, maxWidth)
                    }
                    val lineText = paragraph.substring(start, lastFit).trimEnd()
                    val actualEnd = start + lineText.length
                    lines.add(WrappedLine(lineText, baseIndex + start, baseIndex + actualEnd, breakBefore = lineIndex > 0))
                    lineIndex++
                    var nextStart = lastFit
                    while (nextStart < paragraph.length && paragraph[nextStart].isWhitespace()) {
                        nextStart++
                    }
                    start = max(nextStart, start + 1)
                }
            }
            if (paragraphIndex < normalized.split('\n').lastIndex) {
                baseIndex += paragraph.length + 1
            } else {
                baseIndex += paragraph.length
            }
        }
        return if (lines.isEmpty()) listOf(WrappedLine("", 0, 0)) else lines
    }

    private fun findBreakAtSpace(text: String, start: Int, paint: Paint, maxWidth: Float): Int {
        var lastSpace = -1
        var end = start + 1
        while (end <= text.length) {
            val w = paint.measureText(text, start, end)
            if (w > maxWidth) break
            if (end > start + 1 && text[end - 1] == ' ') {
                lastSpace = end - 1
            }
            end++
        }
        if (lastSpace > start) return lastSpace + 1
        return findBreakByWidth(text, start, paint, maxWidth)
    }

    private fun findBreakByWidth(text: String, start: Int, paint: Paint, maxWidth: Float): Int {
        var end = start + 1
        var lastFit = end
        while (end <= text.length) {
            if (paint.measureText(text, start, end) <= maxWidth || end == start + 1) {
                lastFit = end
                end++
            } else {
                break
            }
        }
        return lastFit
    }

    private fun splitTranslationText(text: String, paint: Paint, maxWidth: Float): List<WrappedLine> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return listOf(WrappedLine("", 0, 0))
        return splitLongText(normalized, paint, maxWidth)
    }

    private fun splitLongText(text: String, paint: Paint, maxWidth: Float): List<WrappedLine> {
        val lines = mutableListOf<WrappedLine>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            var lastFit = end
            while (end <= text.length) {
                val candidate = text.substring(start, end)
                if (paint.measureText(candidate) <= maxWidth || end == start + 1) {
                    lastFit = end
                    end++
                } else {
                    break
                }
            }
            val lineText = text.substring(start, lastFit)
            lines.add(WrappedLine(lineText, start, lastFit))
            start = max(lastFit, start + 1)
        }
        return if (lines.isEmpty()) listOf(WrappedLine(text, 0, text.length)) else lines
    }

    private fun drawTextLines(
        canvas: Canvas,
        lines: List<WrappedLine>,
        paint: Paint,
        dimPaint: Paint,
        highlightPaint: Paint,
        centerX: Float,
        startTop: Float,
        lineHeight: Float,
        lineSpacing: Float,
        shouldHighlight: Boolean,
        line: LyricLine?
    ) {
        if (lines.isEmpty()) return
        val fm = paint.fontMetrics
        var top = startTop
        lines.forEach { item ->
            val baseline = top + (-fm.ascent)
            val textWidth = paint.measureText(item.text)
            val startX = centerX - textWidth / 2f
            if (shouldHighlight && line != null) {
                val highlightCount = line.getHighlightedCharCount(currentPositionMs)
                val segHighlight = (highlightCount - item.charStart).coerceIn(0, item.text.length)
                dimPaint.alpha = 255
                dimPaint.textAlign = Paint.Align.LEFT
                highlightPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(item.text, startX, baseline, dimPaint)
                if (segHighlight > 0) {
                    val highlightWidth = paint.measureText(item.text, 0, segHighlight)
                    val clipTop = baseline + fm.ascent
                    val clipBottom = baseline + fm.descent
                    canvas.save()
                    canvas.clipRect(startX, clipTop, startX + highlightWidth, clipBottom)
                    canvas.drawText(item.text, startX, baseline, highlightPaint)
                    canvas.restore()
                }
            } else {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(item.text, centerX, baseline, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            top += lineHeight + lineSpacing
        }
        paint.textAlign = Paint.Align.LEFT
        dimPaint.textAlign = Paint.Align.LEFT
        highlightPaint.textAlign = Paint.Align.LEFT
    }
}
