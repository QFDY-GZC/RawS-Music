package com.rawsmusic.core.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 动态封面渐变背景 Drawable
 * 从封面提取的颜色生成从上到下的渐变
 */
class CoverGradientDrawable : Drawable() {

    private var startColor: Int = android.graphics.Color.parseColor("#FF252220")
    private var endColor: Int = android.graphics.Color.parseColor("#FF141210")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gradient: LinearGradient? = null
    private var lastBounds: Rect = Rect()
    private var colorsDirty = true

    fun setColors(start: Int, end: Int) {
        this.startColor = start
        this.endColor = end
        colorsDirty = true
        gradient = null
        updateGradient()
        invalidateSelf()
    }

    private fun updateGradient() {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        if (colorsDirty || bounds != lastBounds) {
            lastBounds.set(bounds)
            colorsDirty = false
            gradient = LinearGradient(
                0f, bounds.top.toFloat(),
                0f, bounds.bottom.toFloat(),
                startColor,
                endColor,
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        if (gradient == null || colorsDirty || bounds != lastBounds) {
            updateGradient()
        }

        canvas.drawRect(bounds, paint)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateGradient()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
