package com.rawsmusic.core.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.renderscript.Toolkit

/**
 * 图像处理引擎
 *
 * 封装饱和度调整和高斯模糊，统一使用 RenderScript Intrinsics Replacement Toolkit。
 * Toolkit 内部自动处理版本兼容：API 31+ 使用 native C++ 实现，低版本使用 RenderScript。
 */
object ImageProcessingEngine {

    /**
     * 调整 Bitmap 饱和度
     * @param bitmap 原始图片
     * @param saturation 饱和度因子，1.0 为原图，0 为灰色，>1 增强
     * @return 新的调整后 Bitmap
     */
    fun adjustSaturation(bitmap: Bitmap, saturation: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(saturation)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /**
     * 对 Bitmap 进行高斯模糊
     *
     * 使用 RenderScript Intrinsics Replacement Toolkit，它内部处理版本兼容：
     * - API 31+: native C++ 实现，支持 16KB 页面对齐
     * - API < 31: 自动降级到 RenderScript
     *
     * @param bitmap 需要模糊的图片
     * @param radius 模糊半径（0 < radius <= 25）
     * @return 模糊后的新 Bitmap
     */
    fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        if (radius <= 0f) return bitmap
        val safeRadius = radius.coerceIn(1f, 25f).toInt()
        return Toolkit.blur(bitmap, safeRadius)
    }

    /**
     * 完整处理管线：饱和度调整 → 模糊
     * @param bitmap 原始图片
     * @param saturation 饱和度因子
     * @param blurRadius 模糊半径
     * @return 处理后的 Bitmap
     */
    fun process(bitmap: Bitmap, saturation: Float, blurRadius: Float): Bitmap {
        val saturated = adjustSaturation(bitmap, saturation)
        val blurred = blurBitmap(saturated, blurRadius)
        // 如果中间 Bitmap 不再需要，回收
        if (saturated !== bitmap && saturated !== blurred) {
            saturated.recycle()
        }
        return blurred
    }
}
