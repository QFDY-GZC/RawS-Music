package com.rawsmusic.core.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.palette.graphics.Palette
import coil.size.Size
import coil.transform.Transformation

/**
 * 自适应填充 Transformation
 * 
 * 让图片完整显示（fitCenter），空白区域用从图片提取的主色调填充
 */
class AdaptivePadTransformation(
    private val targetWidth: Int,
    private val targetHeight: Int
) : Transformation {

    override val cacheKey: String = "AdaptivePadTransformation_${targetWidth}x${targetHeight}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        
        // 计算目标宽高比
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val inputRatio = w.toFloat() / h.toFloat()
        
        // 如果宽高比接近，直接返回（差异小于 5%）
        if (Math.abs(targetRatio - inputRatio) / targetRatio < 0.05f) {
            return input
        }

        return try {
            // 创建目标尺寸的画布
            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // 提取主色调作为填充颜色
            val fillColor = extractDominantColor(input)
            canvas.drawColor(fillColor)

            // 计算 fitCenter 缩放
            val scale = minOf(
                targetWidth.toFloat() / w.toFloat(),
                targetHeight.toFloat() / h.toFloat()
            )
            
            // 计算居中位置
            val scaledWidth = w * scale
            val scaledHeight = h * scale
            val left = (targetWidth - scaledWidth) / 2f
            val top = (targetHeight - scaledHeight) / 2f

            // 绘制图片
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(left, top)
            canvas.drawBitmap(input, matrix, paint)

            output
        } catch (e: Exception) {
            // 出错时返回原图
            input
        }
    }

    /**
     * 提取图片的主色调
     */
    private fun extractDominantColor(bitmap: Bitmap): Int {
        return try {
            val palette = Palette.from(bitmap)
                .maximumColorCount(16)
                .resizeBitmapArea(100) // 小尺寸加速提取
                .generate()

            // 优先使用 dominant，其次 vibrant，最后 muted
            val dominant = palette.dominantSwatch?.rgb
            val vibrant = palette.vibrantSwatch?.rgb
            val muted = palette.mutedSwatch?.rgb

            val color = dominant ?: vibrant ?: muted ?: 0xFF1A1A2E.toInt()
            
            // 确保颜色足够暗（适合背景）
            darkenColor(color, 0.3f)
        } catch (e: Exception) {
            0xFF1A1A2E.toInt() // 默认深色
        }
    }

    /**
     * 加深颜色
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
