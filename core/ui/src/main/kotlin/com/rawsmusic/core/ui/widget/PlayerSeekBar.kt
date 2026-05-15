package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PlayerSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density

    // ---------- 尺寸参数 ----------
    private val trackHeight = 4f * dp                // 正常轨道高度
    private val capRadius = trackHeight / 2f          // 轨道圆角
    private val headExtraHeight = 1f * dp             // 头部加粗额外高度（两边各加这么多）
    private val headBlurRadius = trackHeight * 2f     // 头部模糊半径
    private val glowCoverage = 0.108f                 // 辉光覆盖最后10.8%的已播放区域（减少40%）

    // ---------- 画笔 ----------
    // 轨道背景
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF
        style = Paint.Style.FILL
    }

    // 进度条渐变（灰→白）
    private val progressGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 辉光画笔（渐变模糊）
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 路径缓存
    private val progressPath = Path()
    private val glowPath = Path()
    private val tempRect = RectF()

    // ---------- 状态 ----------
    private var progress = 0f
    private var max = 100f
    private var isDragging = false

    private var breathPhase = 0f
    private var breathAlpha = 1f
    private var isBreathing = false
    private var breathAnimator: ValueAnimator? = null

    private val touchSlop = 16f * dp

    var onSeekListener: ((progress: Float) -> Unit)? = null
    var onSeekStartListener: (() -> Unit)? = null
    var onSeekStopListener: ((progress: Float) -> Unit)? = null

    init {
        // 软件层支持 BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setProgress(current: Long, total: Long) {
        if (isDragging || !isAttachedToWindow) return
        max = total.toFloat().coerceAtLeast(1f)
        progress = current.toFloat().coerceIn(0f, max)
        invalidate()
    }

    fun startBreathing() {
        if (isBreathing || !isAttachedToWindow) return
        isBreathing = true
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (!isAttachedToWindow) { cancel(); return@addUpdateListener }
                breathPhase = it.animatedValue as Float
                breathAlpha = 0.55f + 0.45f * breathPhase
                invalidate()
            }
            start()
        }
    }

    fun stopBreathing() {
        isBreathing = false
        breathAlpha = 1f
        breathPhase = 0f
        breathAnimator?.cancel()
        if (isAttachedToWindow) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        breathAnimator?.cancel()
        breathAnimator = null
        isBreathing = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = ((trackHeight + headExtraHeight * 2) * 8).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cy = height / 2f
        // 留出头部可能超出轨道的空间
        val padding = capRadius + headBlurRadius + headExtraHeight
        val trackLeft = 8f * dp + padding
        val trackRight = width - 8f * dp - padding
        val trackWidth = trackRight - trackLeft

        // 1. 背景轨道
        val bgRect = RectF(trackLeft - capRadius, cy - trackHeight / 2, trackRight + capRadius, cy + trackHeight / 2)
        canvas.drawRoundRect(bgRect, capRadius, capRadius, trackBgPaint)

        val fraction = if (max > 0f) (progress / max).coerceIn(0f, 1f) else 0f
        if (fraction.isNaN() || fraction.isInfinite()) return

        val progressWidth = fraction * trackWidth
        if (progressWidth < 1f) return

        val progressRight = trackLeft + progressWidth
        val intensity = if (isDragging) 1.2f else if (isBreathing) 0.8f + 0.2f * breathPhase else 1f

        // 2. 绘制渐变辉光（最后18%区域，末端最明显）- 放在底层
        drawGradientGlow(canvas, trackLeft, progressRight, cy, intensity)

        // 3. 绘制基础进度条（灰→白渐变细线）
        drawBaseProgress(canvas, trackLeft, progressRight, cy, intensity)

        // 4. 绘制末端加粗模糊过渡（平滑超出轨道，无圆球感）- 放在顶层覆盖
        drawSmoothThickHead(canvas, progressRight, cy, intensity)
    }

    /**
     * 基础进度条：灰→白线性渐变
     */
    private fun drawBaseProgress(canvas: Canvas, left: Float, right: Float, cy: Float, intensity: Float) {
        progressGradientPaint.shader = LinearGradient(
            left - capRadius, 0f, right + capRadius, 0f,
            intArrayOf(
                Color.argb(200, 180, 180, 180),   // 灰色
                Color.argb(255, 255, 255, 255)    // 白色
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        progressGradientPaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)

        tempRect.set(left - capRadius, cy - trackHeight / 2, right + capRadius, cy + trackHeight / 2)
        progressPath.reset()
        progressPath.addRoundRect(tempRect, capRadius, capRadius, Path.Direction.CW)
        canvas.drawPath(progressPath, progressGradientPaint)
    }

    /**
     * 末端平滑加粗过渡：不画独立圆球，而是叠加一个向左渐变透明的加宽矩形。
     * 通过模糊实现柔和衔接，产生线条末端自然变粗变亮的效果。
     */
    private fun drawSmoothThickHead(canvas: Canvas, x: Float, cy: Float, intensity: Float) {
        // 加宽过渡区域的宽度，足够容纳渐变过渡
        val headWidth = trackHeight * 2.5f
        // 加宽后的总高度
        val headHeight = trackHeight + headExtraHeight * 2

        // 区域矩形：左边缘向左延伸，右边缘与进度条末端对齐
        val headRect = RectF(
            x - headWidth, 
            cy - headHeight / 2, 
            x + capRadius, 
            cy + headHeight / 2
        )

        // 核心魔法：左透明右亮的线性渐变，让加宽效果平滑融入细线
        val headGradient = LinearGradient(
            headRect.left, 0f, headRect.right, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),          // 左侧全透明，与细线平滑衔接
                Color.argb(255, 255, 255, 255)         // 右侧高亮，产生加粗感
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val blurRadius = headBlurRadius * 0.6f * intensity // 模糊半径适中，保持实体感
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = headGradient
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            alpha = (220 * intensity).toInt().coerceIn(0, 255)
        }

        // 圆角使用 capRadius，保持与线条一致的圆润度，彻底避免产生球形感
        canvas.drawRoundRect(headRect, capRadius, capRadius, headPaint)
    }

    /**
     * 渐变辉光：仅覆盖已播放区域的最后 glowCoverage (18%)，辉光强度从左（弱）到右（强）渐变。
     */
    private fun drawGradientGlow(canvas: Canvas, trackLeft: Float, progressRight: Float, cy: Float, intensity: Float) {
        val trackWidth = (progressRight - trackLeft)
        if (trackWidth <= 0f) return
        val glowStart = progressRight - trackWidth * glowCoverage
        if (glowStart >= progressRight) return

        val glowLeft = maxOf(glowStart, trackLeft)
        val glowRight = progressRight

        // 使用线性渐变控制辉光 alpha，从左（透明）到右（高亮）
        val glowGradient = LinearGradient(
            glowLeft, 0f, glowRight, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),          // 左侧全透明
                Color.argb(140, 255, 255, 255)         // 右侧高亮
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val blurRadius = headBlurRadius * 0.8f * intensity
        glowPaint.shader = glowGradient
        glowPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        glowPaint.alpha = (200 * intensity).toInt().coerceIn(0, 255)

        val saveCount = canvas.saveLayer(null, null)

        // 绘制辉光区域（圆角矩形）
        val glowRect = RectF(glowLeft - capRadius, cy - trackHeight / 2, glowRight + capRadius, cy + trackHeight / 2)
        glowPath.reset()
        glowPath.addRoundRect(glowRect, capRadius, capRadius, Path.Direction.CW)
        canvas.drawPath(glowPath, glowPaint)

        // 挖空内部，仅保留向外扩散的部分
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val inset = trackHeight * 0.25f
        glowRect.inset(inset, inset)
        val insetRadius = (capRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(glowRect, insetRadius, insetRadius, clearPaint)

        canvas.restoreToCount(saveCount)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val padding = capRadius + headBlurRadius + headExtraHeight
        val trackLeft = 8f * dp + padding
        val trackRight = width - 8f * dp - padding
        val trackWidth = trackRight - trackLeft

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < 0f || event.y > height) return false
                isDragging = true
                breathAlpha = 1f
                onSeekStartListener?.invoke()
                updateProgress(event.x, trackLeft, trackWidth)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgress(event.x, trackLeft, trackWidth)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                val stopFraction = if (max > 0f) (progress / max).coerceIn(0f, 1f) else 0f
                if (!stopFraction.isNaN() && !stopFraction.isInfinite()) {
                    onSeekStopListener?.invoke(stopFraction)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateProgress(x: Float, trackLeft: Float, trackWidth: Float) {
        if (trackWidth <= 0f || !isAttachedToWindow) return
        val fraction = ((x - trackLeft) / trackWidth).coerceIn(0f, 1f)
        if (fraction.isNaN() || fraction.isInfinite()) return
        progress = fraction * max
        onSeekListener?.invoke(fraction)
        invalidate()
    }
}