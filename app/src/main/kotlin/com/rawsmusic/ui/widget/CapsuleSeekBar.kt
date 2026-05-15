package com.rawsmusic.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CapsuleSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density

    private val trackHeight = 3f * dp
    private val capRadius = trackHeight / 2f
    private val headExtraHeight = 1f * dp
    private val headBlurRadius = trackHeight * 2f
    private val glowCoverage = 0.126f  // 减少30%辉光范围

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF
        style = Paint.Style.FILL
    }

    private val progressGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val progressPath = Path()
    private val glowPath = Path()
    private val tempRect = RectF()

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
        val desired = (trackHeight + headExtraHeight * 2 + headBlurRadius).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cy = height / 2f
        val padding = capRadius + headBlurRadius + headExtraHeight
        val trackLeft = 0f
        val trackRight = width.toFloat()
        val trackWidth = trackRight - trackLeft

        val bgRect = RectF(trackLeft, cy - trackHeight / 2, trackRight, cy + trackHeight / 2)
        canvas.drawRoundRect(bgRect, capRadius, capRadius, trackBgPaint)

        val fraction = if (max > 0f) (progress / max).coerceIn(0f, 1f) else 0f
        if (fraction.isNaN() || fraction.isInfinite()) return

        val progressWidth = fraction * trackWidth
        if (progressWidth < 1f) return

        val progressRight = trackLeft + progressWidth
        val intensity = if (isDragging) 1.2f else if (isBreathing) 0.8f + 0.2f * breathPhase else 1f

        drawGradientGlow(canvas, trackLeft, progressRight, cy, intensity)
        drawBaseProgress(canvas, trackLeft, progressRight, cy, intensity)
        drawSmoothThickHead(canvas, progressRight, cy, intensity)
    }

    private fun drawBaseProgress(canvas: Canvas, left: Float, right: Float, cy: Float, intensity: Float) {
        progressGradientPaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(
                Color.argb(200, 180, 180, 180),
                Color.argb(255, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        progressGradientPaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)

        tempRect.set(left, cy - trackHeight / 2, right, cy + trackHeight / 2)
        progressPath.reset()
        progressPath.addRoundRect(tempRect, capRadius, capRadius, Path.Direction.CW)
        canvas.drawPath(progressPath, progressGradientPaint)
    }

    private fun drawSmoothThickHead(canvas: Canvas, x: Float, cy: Float, intensity: Float) {
        val headWidth = trackHeight * 2.5f
        val headHeight = trackHeight + headExtraHeight * 2

        val headRect = RectF(
            x - headWidth,
            cy - headHeight / 2,
            x + capRadius,
            cy + headHeight / 2
        )

        val headGradient = LinearGradient(
            headRect.left, 0f, headRect.right, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(255, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val blurRadius = headBlurRadius * 0.6f * intensity
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = headGradient
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            alpha = (220 * intensity).toInt().coerceIn(0, 255)
        }

        canvas.drawRoundRect(headRect, capRadius, capRadius, headPaint)
    }

    private fun drawGradientGlow(canvas: Canvas, trackLeft: Float, progressRight: Float, cy: Float, intensity: Float) {
        val trackWidth = (progressRight - trackLeft)
        if (trackWidth <= 0f) return
        val glowStart = progressRight - trackWidth * glowCoverage
        if (glowStart >= progressRight) return

        val glowLeft = maxOf(glowStart, trackLeft)
        val glowRight = progressRight

        val glowGradient = LinearGradient(
            glowLeft, 0f, glowRight, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(140, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val blurRadius = headBlurRadius * 0.8f * intensity
        glowPaint.shader = glowGradient
        glowPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        glowPaint.alpha = (200 * intensity).toInt().coerceIn(0, 255)

        val saveCount = canvas.saveLayer(null, null)

        val glowRect = RectF(glowLeft, cy - trackHeight / 2, glowRight, cy + trackHeight / 2)
        glowPath.reset()
        glowPath.addRoundRect(glowRect, capRadius, capRadius, Path.Direction.CW)
        canvas.drawPath(glowPath, glowPaint)

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
        val trackLeft = 0f
        val trackRight = width.toFloat()
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
