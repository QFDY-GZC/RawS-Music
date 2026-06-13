package com.rawsmusic.ui.settings.compose.scene.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Compose 工具类
 * 对应原版的工具函数
 *
 * 提供各种工具函数：颜色转换、单位转换、数学计算等
 */
object ComposeUtils {
    /**
     * HSL 颜色转换
     *
     * @param h 色相 (0..360)
     * @param s 饱和度 (0..1)
     * @param l 亮度 (0..1)
     * @return Color
     */
    fun hsl(h: Float, s: Float, l: Float): Color {
        val hn = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hn / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            hn < 60f -> Triple(c, x, 0f)
            hn < 120f -> Triple(x, c, 0f)
            hn < 180f -> Triple(0f, c, x)
            hn < 240f -> Triple(0f, x, c)
            hn < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            (r + m).coerceIn(0f, 1f),
            (g + m).coerceIn(0f, 1f),
            (b + m).coerceIn(0f, 1f)
        )
    }

    /**
     * 线性插值
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 比例 (0..1)
     * @return 插值结果
     */
    fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    /**
     * 线性插值 (Int)
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 比例 (0..1)
     * @return 插值结果
     */
    fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + (end - start) * fraction.coerceIn(0f, 1f)).toInt()
    }

    /**
     * 线性插值 (Dp)
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 比例 (0..1)
     * @return 插值结果
     */
    fun lerp(start: Dp, end: Dp, fraction: Float): Dp {
        return Dp(lerp(start.value, end.value, fraction))
    }

    /**
     * 线性插值 (Color)
     *
     * @param start 起始颜色
     * @param end 结束颜色
     * @param fraction 比例 (0..1)
     * @return 插值结果
     */
    fun lerp(start: Color, end: Color, fraction: Float): Color {
        return Color(
            red = lerp(start.red, end.red, fraction),
            green = lerp(start.green, end.green, fraction),
            blue = lerp(start.blue, end.blue, fraction),
            alpha = lerp(start.alpha, end.alpha, fraction)
        )
    }

    /**
     * 限制范围
     *
     * @param value 值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun coerceIn(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }

    /**
     * 限制范围 (Int)
     *
     * @param value 值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun coerceIn(value: Int, min: Int, max: Int): Int {
        return value.coerceIn(min, max)
    }

    /**
     * 限制范围 (Dp)
     *
     * @param value 值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun coerceIn(value: Dp, min: Dp, max: Dp): Dp {
        return Dp(coerceIn(value.value, min.value, max.value))
    }

    /**
     * 格式化时长
     *
     * @param seconds 秒数
     * @return 格式化后的时长 (mm:ss)
     */
    fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    /**
     * 格式化时长
     *
     * @param milliseconds 毫秒数
     * @return 格式化后的时长 (mm:ss)
     */
    fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).toInt()
        return formatDuration(seconds)
    }

    /**
     * 格式化百分比
     *
     * @param value 值 (0..1)
     * @return 格式化后的百分比 (xx.x%)
     */
    fun formatPercent(value: Float): String {
        return "%.1f%%".format(value * 100f)
    }

    /**
     * 格式化百分比
     *
     * @param value 值 (0..100)
     * @return 格式化后的百分比 (xx.x%)
     */
    fun formatPercent(value: Int): String {
        return "%.1f%%".format(value.toFloat())
    }

    /**
     * 格式化小数
     *
     * @param value 值
     * @param decimals 小数位数
     * @return 格式化后的小数
     */
    fun formatDecimal(value: Float, decimals: Int = 2): String {
        return "%.${decimals}f".format(value)
    }

    /**
     * 格式化小数
     *
     * @param value 值
     * @param decimals 小数位数
     * @return 格式化后的小数
     */
    fun formatDecimal(value: Double, decimals: Int = 2): String {
        return "%.${decimals}f".format(value)
    }

    /**
     * 格式化文件大小
     *
     * @param bytes 字节数
     * @return 格式化后的文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * 格式化数字
     *
     * @param number 数字
     * @return 格式化后的数字
     */
    fun formatNumber(number: Long): String {
        return when {
            number < 1000 -> "$number"
            number < 1000 * 1000 -> "${number / 1000}K"
            number < 1000 * 1000 * 1000 -> "${number / (1000 * 1000)}M"
            else -> "${number / (1000 * 1000 * 1000)}B"
        }
    }

    /**
     * 格式化数字
     *
     * @param number 数字
     * @return 格式化后的数字
     */
    fun formatNumber(number: Int): String {
        return formatNumber(number.toLong())
    }

    /**
     * 计算两点距离
     *
     * @param x1 点1 X 坐标
     * @param y1 点1 Y 坐标
     * @param x2 点2 X 坐标
     * @param y2 点2 Y 坐标
     * @return 距离
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算两点距离 (Dp)
     *
     * @param x1 点1 X 坐标
     * @param y1 点1 Y 坐标
     * @param x2 点2 X 坐标
     * @param y2 点2 Y 坐标
     * @return 距离
     */
    fun distance(x1: Dp, y1: Dp, x2: Dp, y2: Dp): Dp {
        return Dp(distance(x1.value, y1.value, x2.value, y2.value))
    }
}
