package com.rawsmusic.core.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import coil.size.Size
import coil.transform.Transformation

/**
 * 将非正方形图片填充为正方形的 Coil Transformation
 *
 * 背景：centerCrop 放大 + 暗色遮罩
 * 前景：fitCenter 居中完整显示
 */
class SquarePadTransformation : Transformation {

    override val cacheKey: String = "SquarePadTransformation"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        // 已经是正方形，直接返回
        if (w == h) return input

        return try {
            val side = maxOf(w, h)
            val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // 背景层：centerCrop 放大填满正方形
            val bgScale = side.toFloat() / minOf(w, h)
            val bgMatrix = Matrix()
            bgMatrix.setScale(bgScale, bgScale, w / 2f, h / 2f)
            bgMatrix.postTranslate((side - w * bgScale) / 2f, (side - h * bgScale) / 2f)
            canvas.drawBitmap(input, bgMatrix, paint)

            // 暗色遮罩
            canvas.drawColor(0x66000000)

            // 前景层：fitCenter 居中绘制原图
            val fgScale = side.toFloat() / maxOf(w, h)
            val fgMatrix = Matrix()
            fgMatrix.setScale(fgScale, fgScale, w / 2f, h / 2f)
            fgMatrix.postTranslate((side - w * fgScale) / 2f, (side - h * fgScale) / 2f)
            canvas.drawBitmap(input, fgMatrix, paint)

            output
        } catch (e: Exception) {
            // 出错时返回原图，避免加载失败
            input
        }
    }
}
