package com.rawsmusic.ui.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import kotlin.math.roundToInt

class NavbarExtension @JvmOverloads constructor(
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
    private var hasMeasured = false
    private var isInitiallyFolded = false

    fun setInitiallyFolded() {
        isInitiallyFolded = true
        foldProgress = 1f
        visibility = View.INVISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!hasMeasured) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (measuredHeight > 0) {
                originalHeight = measuredHeight
                hasMeasured = true
                if (isInitiallyFolded && foldProgress == 1f) {
                    isInitiallyFolded = false
                    setMeasuredDimension(measuredWidth, 0)
                    return
                }
            }
        } else if (originalHeight > 0) {
            val currentHeight = (originalHeight * (1f - foldProgress)).roundToInt()
            val newHeightSpec = MeasureSpec.makeMeasureSpec(maxOf(1, currentHeight), MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, newHeightSpec)
            return
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (foldProgress in 0.01f..0.99f && originalHeight > 0) {
            canvas.save()
            val currentRotationX = -25f * foldProgress

            camera.save()
            camera.rotateX(currentRotationX)
            camera.getMatrix(matrix)
            camera.restore()

            matrix.preTranslate(-width / 2f, -originalHeight.toFloat())
            matrix.postTranslate(width / 2f, originalHeight.toFloat())

            canvas.translate(0f, -originalHeight * foldProgress * 0.15f)
            canvas.concat(matrix)

            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun unfold(duration: Long = 400L) {
        if (isAnimating) return
        if (!hasMeasured || originalHeight == 0) {
            visibility = View.VISIBLE
            measure(
                MeasureSpec.makeMeasureSpec((parent as? View)?.width ?: 0, MeasureSpec.EXACTLY),
                MeasureSpec.UNSPECIFIED
            )
            if (measuredHeight > 0) {
                originalHeight = measuredHeight
                hasMeasured = true
            }
        }
        if (foldProgress == 0f) return
        visibility = View.VISIBLE
        animateFold(foldProgress, 0f, duration)
    }

    fun fold(duration: Long = 350L) {
        if (isAnimating || foldProgress == 1f) return
        animateFold(foldProgress, 1f, duration)
    }

    fun foldImmediate() {
        if (isAnimating) return
        if (!hasMeasured) {
            measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            originalHeight = measuredHeight
            hasMeasured = true
        }
        foldProgress = 1f
        updateLayoutParams { height = 0 }
        requestLayout()
        invalidate()
        visibility = GONE
    }

    fun unfoldImmediate() {
        if (isAnimating) return
        foldProgress = 0f
        visibility = VISIBLE
        if (originalHeight > 0) {
            updateLayoutParams { height = originalHeight }
        }
        requestLayout()
        invalidate()
    }

    private fun animateFold(from: Float, to: Float, duration: Long) {
        if (visibility != View.VISIBLE) visibility = View.VISIBLE

        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { animator ->
                foldProgress = animator.animatedValue as Float
                val currentHeight = (originalHeight * (1f - foldProgress)).roundToInt()
                updateLayoutParams { height = maxOf(1, currentHeight) }
                invalidate()
            }
            start()
            isAnimating = true
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                    visibility = if (foldProgress == 1f) GONE else VISIBLE
                }
            })
        }
    }
}
