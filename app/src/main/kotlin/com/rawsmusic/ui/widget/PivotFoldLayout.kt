package com.rawsmusic.ui.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.roundToInt

class PivotFoldLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val camera = Camera()
    private val matrix = Matrix()

    var foldProgress = 0f
        private set

    private var originalHeight = 0
    private var isAnimating = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (originalHeight == 0 && foldProgress == 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            originalHeight = measuredHeight
        } else {
            val currentRotationX = -90f * foldProgress
            val heightRatio = cos(Math.toRadians(currentRotationX.toDouble())).toFloat()
            val targetHeight = maxOf(1, (originalHeight * heightRatio).roundToInt())

            val newHeightSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, newHeightSpec)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (foldProgress in 0.01f..0.99f) {
            canvas.save()
            val currentRotationX = -90f * foldProgress

            camera.save()
            camera.rotateX(currentRotationX)
            camera.getMatrix(matrix)
            camera.restore()

            matrix.preTranslate(-width / 2f, -height.toFloat())
            matrix.postTranslate(width / 2f, height.toFloat())

            canvas.concat(matrix)
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun unfold(duration: Long = 100L) {
        if (isAnimating || foldProgress == 0f) return
        animateFold(foldProgress, 0f, duration)
    }

    fun fold(duration: Long = 100L) {
        if (isAnimating || foldProgress == 1f) return
        animateFold(foldProgress, 1f, duration)
    }

    fun foldImmediate() {
        if (isAnimating) return
        foldProgress = 1f
        requestLayout()
        invalidate()
        visibility = GONE
    }

    fun unfoldImmediate() {
        if (isAnimating) return
        foldProgress = 0f
        visibility = VISIBLE
        requestLayout()
        invalidate()
    }

    private fun animateFold(from: Float, to: Float, duration: Long) {
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                foldProgress = animator.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
            isAnimating = true
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                    if (foldProgress == 1f) {
                        visibility = GONE
                    }
                }
            })
        }
        if (to < from) {
            visibility = VISIBLE
        }
    }
}
