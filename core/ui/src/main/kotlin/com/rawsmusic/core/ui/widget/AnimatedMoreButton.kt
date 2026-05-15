package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 动画三点按钮
 * - 默认状态：三个点紧凑在一起 + 呼吸动画
 * - 点击/激活状态：三个点分散开来 + 呼吸动画
 */
class AnimatedMoreButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private var dotRadius = 2.5f * resources.displayMetrics.density
    private var dotSpacing = 5f * resources.displayMetrics.density  // 紧凑间距
    private var dotSpreadSpacing = 8f * resources.displayMetrics.density  // 分散间距

    // 当前间距（动画过渡）
    private var currentSpacing = dotSpacing
    // 是否展开（分散）
    private var isExpanded = false
    // 呼吸alpha
    private var breathAlpha = 1f
    // 呼吸动画
    private var breathAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        startBreathing()
    }

    /** 切换展开/紧凑状态 */
    fun toggleExpanded(): Boolean {
        isExpanded = !isExpanded
        animateSpacing()
        return isExpanded
    }

    /** 设置展开状态 */
    fun setExpanded(expanded: Boolean) {
        if (isExpanded != expanded) {
            isExpanded = expanded
            animateSpacing()
        }
    }

    private fun animateSpacing() {
        val target = if (isExpanded) dotSpreadSpacing else dotSpacing
        ValueAnimator.ofFloat(currentSpacing, target).apply {
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                currentSpacing = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 启动呼吸动画 */
    fun startBreathing() {
        if (breathAnimator?.isRunning == true) return
        breathAnimator = ValueAnimator.ofFloat(0.6f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                breathAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停止呼吸动画 */
    fun stopBreathing() {
        breathAnimator?.cancel()
        breathAnimator = null
        breathAlpha = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        dotPaint.alpha = (0xB0 * breathAlpha).toInt()

        val totalWidth = currentSpacing * 2
        val startX = cx - totalWidth / 2

        for (i in 0..2) {
            canvas.drawCircle(startX + i * currentSpacing, cy, dotRadius, dotPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBreathing()
    }
}
