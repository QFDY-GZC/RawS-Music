package com.kyant.backdrop.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.kyant.backdrop.BackdropEffectScope
import kotlin.random.Random

object NoiseTexture {

    private var noiseBitmap: Bitmap? = null

    fun getNoiseBitmap(size: Int = 256): Bitmap {
        return noiseBitmap ?: generateNoiseBitmap(size).also { noiseBitmap = it }
    }

    private fun generateNoiseBitmap(size: Int): Bitmap {
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createNoiseShader(
        noiseAlpha: Float = 0.05f,
        tileScale: Float = 1f
    ): RuntimeShader {
        val shader = RuntimeShader(NOISE_SHADER)
        shader.setFloatUniform("noiseAlpha", noiseAlpha)
        shader.setFloatUniform("tileScale", tileScale)
        return shader
    }

    fun release() {
        noiseBitmap?.recycle()
        noiseBitmap = null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun BackdropEffectScope.noise(
    alpha: Float = 0.05f,
    tileScale: Float = 1f
) {
    val shader = obtainRuntimeShader("noise", NOISE_SHADER)
    shader.apply {
        setFloatUniform("noiseAlpha", alpha)
        setFloatUniform("tileScale", tileScale)
    }
    val noiseEffect = RenderEffect.createRuntimeShaderEffect(shader, "noiseContent")
    val currentEffect = renderEffect
    renderEffect = if (currentEffect != null) {
        RenderEffect.createBlendModeEffect(currentEffect, noiseEffect, android.graphics.BlendMode.DST_ATOP)
    } else {
        noiseEffect
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private val NOISE_SHADER = """
uniform float noiseAlpha;
uniform float tileScale;
uniform shader noiseContent;

float random(float2 st) {
    return fract(sin(dot(st.xy, float2(12.9898, 78.233))) * 43758.5453123);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord * tileScale / 256.0;
    float noise = random(floor(uv * 256.0));
    return half4(half3(noise), noiseAlpha);
}
""".trimIndent()
