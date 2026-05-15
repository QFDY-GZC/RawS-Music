package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rawsmusic.core.common.utils.UiUtils

class CircularProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = UiUtils.dpToPx(context, 4f)
        color = 0x33FFFFFF
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = UiUtils.dpToPx(context, 4f)
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var progress = 0f
    private var max = 100f

    var progressColor: Int
        get() = progressPaint.color
        set(value) {
            progressPaint.color = value
            invalidate()
        }

    fun setProgress(current: Long, total: Long) {
        max = total.toFloat().coerceAtLeast(1f)
        progress = current.toFloat().coerceIn(0f, max)
        invalidate()
    }

    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, max)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = backgroundPaint.strokeWidth / 2
        rect.set(padding, padding, width - padding, height - padding)

        canvas.drawArc(rect, 0f, 360f, false, backgroundPaint)

        val sweepAngle = (progress / max) * 360f
        canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }
}
