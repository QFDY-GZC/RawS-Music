package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView

class MarqueeTextView : TextView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var scrollOffset = 0f
    private var textWidth = 0f
    private var isMarqueeNeeded = false
    private var animator: ValueAnimator? = null
    private var isAttached = false

    private val ghostSpacing: Float
        get() = textSize * 1.5f

    private val scrollSpeed: Float
        get() = textSize * 0.8f

    private val availableWidth: Int
        get() = measuredWidth - paddingLeft - paddingRight

    private fun updateTextWidth() {
        val p = paint ?: return
        textWidth = p.measureText(text?.toString() ?: "")
        isMarqueeNeeded = textWidth > availableWidth
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateTextWidth()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        scrollOffset = 0f
        updateTextWidth()
        restartAnimator()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTextWidth()
        restartAnimator()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        restartAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        stopAnimator()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            restartAnimator()
        } else {
            stopAnimator()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isMarqueeNeeded || layout == null) {
            scrollOffset = 0f
            super.onDraw(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = paddingLeft.toFloat()
        val padRight = paddingRight.toFloat()
        val contentW = w - padLeft - padRight

        canvas.save()

        val clipBounds = Rect(paddingLeft, 0, width - paddingRight, height)
        canvas.clipRect(clipBounds)

        val fm = paint.fontMetrics
        val textH = fm.descent - fm.ascent
        val baseline = (h - textH) / 2f - fm.ascent

        val mainX = padLeft - scrollOffset
        if (mainX + textWidth > padLeft && mainX < w - padRight) {
            canvas.drawText(text.toString(), mainX, baseline, paint)
        }

        val ghostX = mainX + textWidth + ghostSpacing
        if (ghostX + textWidth > padLeft && ghostX < w - padRight) {
            canvas.drawText(text.toString(), ghostX, baseline, paint)
        }

        canvas.restore()
    }

    private fun restartAnimator() {
        stopAnimator()
        if (!isAttached || !isMarqueeNeeded || width == 0) return

        val unit = textWidth + ghostSpacing
        val durationMs = (unit / scrollSpeed * 1000f).toLong().coerceAtLeast(1000L)

        animator = ValueAnimator.ofFloat(0f, unit).apply {
            this.interpolator = LinearInterpolator()
            this.duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                scrollOffset = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator?.removeAllUpdateListeners()
        animator = null
    }
}
