package com.rawsmusic.core.ui.widget.flow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color

@RequiresApi(33)
internal class RawAlbumColorAgslRenderer {
    private val runtimeShader = RuntimeShader(ALBUM_COLOR_AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        shader = runtimeShader
    }
    private var currentBitmap: Bitmap? = null
    private var targetBitmap: Bitmap? = null
    private var currentCoverShader: BitmapShader? = null
    private var targetCoverShader: BitmapShader? = null

    private fun makeBitmapShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            it.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }

    private fun currentShader(bitmap: Bitmap): BitmapShader {
        if (currentBitmap !== bitmap || currentCoverShader == null) {
            currentBitmap = bitmap
            currentCoverShader = makeBitmapShader(bitmap)
        }
        return requireNotNull(currentCoverShader)
    }

    private fun targetShader(bitmap: Bitmap): BitmapShader {
        if (targetBitmap !== bitmap || targetCoverShader == null) {
            targetBitmap = bitmap
            targetCoverShader = makeBitmapShader(bitmap)
        }
        return requireNotNull(targetCoverShader)
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        current: Bitmap,
        target: Bitmap?,
        transitionProgress: Float,
        colors: List<Color>,
        phase: Float,
        seed: Float,
        darkMode: Boolean,
        alpha: Float,
    ) {
        if (width <= 0f || height <= 0f || current.isRecycled) return
        val targetValue = target?.takeUnless { it.isRecycled } ?: current

        runtimeShader.setInputShader("currentCover", currentShader(current))
        runtimeShader.setInputShader("targetCover", targetShader(targetValue))
        runtimeShader.setFloatUniform("resolution", width, height)
        runtimeShader.setFloatUniform("textureSize", current.width.toFloat(), current.height.toFloat())
        runtimeShader.setFloatUniform(
            "targetTextureSize",
            targetValue.width.toFloat(),
            targetValue.height.toFloat(),
        )
        runtimeShader.setFloatUniform("phase", phase)
        runtimeShader.setFloatUniform("seed", seed)
        runtimeShader.setFloatUniform("transitionProgress", transitionProgress.coerceIn(0f, 1f))
        runtimeShader.setFloatUniform("darkMode", if (darkMode) 1f else 0f)
        runtimeShader.setFloatUniform("sceneAlpha", alpha.coerceIn(0f, 1f))
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

private const val ALBUM_COLOR_AGSL = """
uniform shader currentCover;
uniform shader targetCover;
uniform float2 resolution;
uniform float2 textureSize;
uniform float2 targetTextureSize;
uniform float phase;
uniform float seed;
uniform float transitionProgress;
uniform float darkMode;
uniform float sceneAlpha;

float2 backdropUvA(float2 uv) {
    float aspect = resolution.x/max(resolution.y,1.0);
    float2 q = uv-float2(0.5);
    if (aspect < 1.0) {
        q.x *= aspect;
    } else {
        q.y /= aspect;
    }

    float zoom = 1.11 + 0.045*sin(phase*0.72+seed);
    q /= zoom;
    q += float2(
        0.060*sin(phase*0.62+seed*0.7),
        0.046*cos(phase*0.54+seed)
    );
    q += float2(
        0.012*sin(q.y*4.0+phase*0.82+seed),
        0.010*cos(q.x*3.6-phase*0.76+seed*0.5)
    );
    return q+float2(0.5);
}

float2 backdropUvB(float2 uv) {
    float aspect = resolution.x/max(resolution.y,1.0);
    float2 q = uv-float2(0.5);
    if (aspect < 1.0) {
        q.x *= aspect;
    } else {
        q.y /= aspect;
    }

    float zoom = 1.16 + 0.035*cos(phase*0.58+seed*1.3);
    q /= zoom;
    q += float2(
        -0.052*cos(phase*0.50+seed),
        0.055*sin(phase*0.66+seed*0.4)
    );
    q += float2(
        -0.010*cos(q.y*3.3-phase*0.70+seed*0.8),
        0.012*sin(q.x*3.8+phase*0.74+seed)
    );
    return q+float2(0.5);
}

half4 main(float2 xy) {
    float2 normalized = xy/resolution;
    float2 uvA = backdropUvA(normalized);
    float2 uvB = backdropUvB(normalized);

    half3 currentA = currentCover.eval(uvA*textureSize).rgb;
    half3 currentB = currentCover.eval(uvB*textureSize).rgb;
    half3 targetA = targetCover.eval(uvA*targetTextureSize).rgb;
    half3 targetB = targetCover.eval(uvB*targetTextureSize).rgb;

    half3 current = mix(currentA,currentB,half(0.32));
    half3 target = mix(targetA,targetB,half(0.32));
    half3 outc = mix(current,target,half(transitionProgress));

    half luma = dot(outc,half3(0.2126,0.7152,0.0722));
    half backdropSaturation = mix(half(1.06),half(1.12),half(darkMode));
    outc = mix(half3(luma),outc,backdropSaturation);

    if (darkMode>0.5) {
        outc *= half3(0.72);
        outc = mix(outc,half3(0.0),half(0.08));
    } else {
        outc = mix(half3(0.97),outc,half(0.84));
    }
    return half4(clamp(outc,half3(0.0),half3(1.0)),half(sceneAlpha));
}
"""
