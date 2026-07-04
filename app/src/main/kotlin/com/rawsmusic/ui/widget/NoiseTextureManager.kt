package com.rawsmusic.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.random.Random

object NoiseTextureManager {

    private var noiseBitmap: Bitmap? = null

    fun getNoiseBitmap(context: Context): Bitmap {
        return noiseBitmap ?: generateNoiseBitmap().also { noiseBitmap = it }
    }

    private fun generateNoiseBitmap(size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
        }

        val random = Random(42)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val noise = random.nextInt(0, 256)
                paint.color = Color.argb(noise, 128, 128, 128)
                canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
            }
        }
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun createNoiseRenderEffect(
        context: Context,
        alpha: Float = 0.05f,
        tileSize: Float = 1f
    ): RenderEffect {
        val bitmap = getNoiseBitmap(context)
        val bitmapShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        if (tileSize != 1f) {
            val matrix = android.graphics.Matrix()
            matrix.setScale(1f / tileSize, 1f / tileSize)
            bitmapShader.setLocalMatrix(matrix)
        }

        val shaderEffect = RenderEffect.createShaderEffect(bitmapShader)

        return if (alpha < 1f) {
            val colorMatrix = android.graphics.ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, alpha, 0f
                )
            )
            val colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            RenderEffect.createColorFilterEffect(colorFilter, shaderEffect)
        } else {
            shaderEffect
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun blendWithMainEffect(
        mainEffect: RenderEffect,
        noiseEffect: RenderEffect,
        blendMode: android.graphics.BlendMode = android.graphics.BlendMode.DST_ATOP
    ): RenderEffect {
        return RenderEffect.createBlendModeEffect(mainEffect, noiseEffect, blendMode)
    }

    fun release() {
        noiseBitmap?.recycle()
        noiseBitmap = null
    }
}
