package com.rawsmusic.core.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 频率响应曲线颜色配置
 */
data class PEQCurveColors(
    val background: Color = Color(0xFF1A1A2E),
    val gridLine: Color = Color(0xFF2A2A4A),
    val zeroLine: Color = Color(0xFF4A4A6A),
    val curveLine: Color = Color(0xFF00D4FF),
    val curveFill: Brush = Brush.verticalGradient(
        colors = listOf(
            Color(0x4000D4FF),
            Color(0x0000D4FF)
        )
    ),
    val labelColor: Color = Color(0xFF8A8AAA),
    val filterDot: Color = Color(0xFFFF6B6B)
)

/**
 * 频率响应曲线视图
 * @param frequencies 频率数组 (Hz)
 * @param magnitudes 增益数组 (dB)
 * @param gainRange 增益显示范围 (默认 -18..18 dB)
 * @param colors 颜色配置
 * @param modifier Modifier
 */
@Composable
fun PEQCurveView(
    frequencies: FloatArray,
    magnitudes: FloatArray,
    gainRange: ClosedFloatingPointRange<Float> = -18f..18f,
    colors: PEQCurveColors = PEQCurveColors(),
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background)
    ) {
        val width = size.width
        val height = size.height
        val paddingLeft = 45f
        val paddingRight = 20f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingLeft - paddingRight

        // 绘制网格线
        drawGrid(chartWidth, chartHeight, paddingLeft, gainRange, colors)

        // 绘制零线
        drawZeroLine(chartWidth, chartHeight, paddingLeft, gainRange, colors)

        // 绘制频率响应曲线
        if (frequencies.isNotEmpty() && magnitudes.isNotEmpty() && frequencies.size == magnitudes.size) {
            drawCurve(frequencies, magnitudes, chartWidth, chartHeight, paddingLeft, gainRange, colors)
        }

        // 绘制刻度标签
        drawLabels(chartWidth, chartHeight, paddingLeft, gainRange, colors)
    }
}

private fun DrawScope.drawGrid(
    chartWidth: Float,
    chartHeight: Float,
    padding: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    colors: PEQCurveColors
) {
    // 水平网格线 (增益刻度)
    val gainStep = 3f // 每3dB一条线
    val minGain = gainRange.start
    val maxGain = gainRange.endInclusive

    var gain = minGain
    while (gain <= maxGain) {
        val y = padding + chartHeight * (1f - (gain - minGain) / (maxGain - minGain))
        drawLine(
            color = colors.gridLine,
            start = Offset(padding, y),
            end = Offset(padding + chartWidth, y),
            strokeWidth = if (gain == 0f) 2f else 0.5f
        )
        gain += gainStep
    }

    // 垂直网格线 (频率刻度 - 对数分布)
    val freqLines = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    for (freq in freqLines) {
        val x = padding + freqToX(freq) * chartWidth
        drawLine(
            color = colors.gridLine,
            start = Offset(x, padding),
            end = Offset(x, padding + chartHeight),
            strokeWidth = if (freq == 1000f) 2f else 0.5f
        )
    }
}

private fun DrawScope.drawZeroLine(
    chartWidth: Float,
    chartHeight: Float,
    padding: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    colors: PEQCurveColors
) {
    val minGain = gainRange.start
    val maxGain = gainRange.endInclusive
    val zeroY = padding + chartHeight * (1f - (0f - minGain) / (maxGain - minGain))

    drawLine(
        color = colors.zeroLine,
        start = Offset(padding, zeroY),
        end = Offset(padding + chartWidth, zeroY),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
    )
}

private fun DrawScope.drawCurve(
    frequencies: FloatArray,
    magnitudes: FloatArray,
    chartWidth: Float,
    chartHeight: Float,
    padding: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    colors: PEQCurveColors
) {
    val minGain = gainRange.start
    val maxGain = gainRange.endInclusive

    // 创建曲线路径
    val curvePath = Path()
    val fillPath = Path()

    // 起始点
    val startX = padding + freqToX(frequencies[0]) * chartWidth
    val startY = padding + chartHeight * (1f - (magnitudes[0].coerceIn(minGain, maxGain) - minGain) / (maxGain - minGain))
    curvePath.moveTo(startX, startY)
    fillPath.moveTo(startX, padding + chartHeight)
    fillPath.lineTo(startX, startY)

    // 绘制曲线
    for (i in 1 until frequencies.size) {
        val x = padding + freqToX(frequencies[i]) * chartWidth
        val y = padding + chartHeight * (1f - (magnitudes[i].coerceIn(minGain, maxGain) - minGain) / (maxGain - minGain))
        curvePath.lineTo(x, y)
        fillPath.lineTo(x, y)
    }

    // 闭合填充路径
    val endX = padding + freqToX(frequencies.last()) * chartWidth
    fillPath.lineTo(endX, padding + chartHeight)
    fillPath.close()

    // 绘制填充
    drawPath(
        path = fillPath,
        brush = colors.curveFill
    )

    // 绘制曲线
    drawPath(
        path = curvePath,
        color = colors.curveLine,
        style = Stroke(
            width = 3f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawLabels(
    chartWidth: Float,
    chartHeight: Float,
    padding: Float,
    gainRange: ClosedFloatingPointRange<Float>,
    colors: PEQCurveColors
) {
    // 频率标签
    val freqLabels = listOf(100f, 1000f, 10000f)
    val freqTexts = listOf("100", "1k", "10k")

    val nativeCanvas = drawContext.canvas.nativeCanvas
    // Alternative: drawIntoCanvas { canvas -> canvas.nativeCanvas }
        val freqPaint = android.graphics.Paint().apply {
            color = colors.labelColor.toArgb()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val gainPaint = android.graphics.Paint().apply {
            color = colors.labelColor.toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.RIGHT
        }

    for (i in freqLabels.indices) {
        val x = padding + freqToX(freqLabels[i]) * chartWidth
        nativeCanvas.drawText(
            freqTexts[i],
            x,
            padding + chartHeight + 30f,
            freqPaint
        )
    }

    // 增益标签
    val gainStep = 6f
    val minGain = gainRange.start
    val maxGain = gainRange.endInclusive

    var gain = minGain
    while (gain <= maxGain) {
        val y = padding + chartHeight * (1f - (gain - minGain) / (maxGain - minGain))
        val text = if (gain > 0) "+${gain.toInt()}" else gain.toInt().toString()
        nativeCanvas.drawText(
            text,
            padding - 10f,
            y + 8f,
            gainPaint
        )
        gain += gainStep
    }
}

/**
 * 频率到X坐标的映射 (对数尺度)
 * 20Hz -> 0.0, 20000Hz -> 1.0
 */
private fun freqToX(freq: Float): Float {
    val minLog = log10(20.0)
    val maxLog = log10(20000.0)
    return ((log10(freq.toDouble()) - minLog) / (maxLog - minLog)).toFloat()
}
