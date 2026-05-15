package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.rawsmusic.core.common.utils.UiUtils

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var amplitudes = FloatArray(0)
    private var barCount = 64
    private var barGap = 2f

    var color: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    fun setAmplitudes(amps: FloatArray) {
        amplitudes = amps
        invalidate()
    }

    fun setBarCount(count: Int) {
        barCount = count
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty() || width == 0 || height == 0) return

        val barWidth = (width - barGap * (barCount - 1)) / barCount
        val step = amplitudes.size.toFloat() / barCount

        paint.color = color

        for (i in 0 until barCount) {
            val ampIndex = (i * step).toInt().coerceIn(amplitudes.indices)
            val amplitude = amplitudes[ampIndex]
            val barHeight = amplitude * height * 0.8f

            val x = i * (barWidth + barGap)
            val y = (height - barHeight) / 2f

            canvas.drawRoundRect(
                x, y, x + barWidth, y + barHeight,
                barWidth / 2f, barWidth / 2f, paint
            )
        }
    }
}
