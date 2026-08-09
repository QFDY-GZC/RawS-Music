package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutDynamicBackground(
    modifier: Modifier = Modifier,
    backgroundModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val supported = remember { isRuntimeShaderSupported() }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val surface = MiuixTheme.colorScheme.surface
    val preset = remember(isDark) { if (isDark) AboutBgPreset.dark else AboutBgPreset.light }
    val painter = remember { AboutBgPainter() }
    val time = rememberAboutFrameSeconds(supported)
    val colorStage = remember { Animatable(0f) }

    LaunchedEffect(supported, preset) {
        if (!supported) return@LaunchedEffect
        var target = 1f
        while (isActive) {
            delay((preset.colorPeriodSeconds * 500f).toLong())
            colorStage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
            target += 1f
        }
    }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize().then(backgroundModifier)) {
            drawRect(surface)
            if (supported) {
                val stage = colorStage.value
                val base = stage.toInt()
                val fraction = stage - base
                val start = preset.palette(base)
                val end = preset.palette(base + 1)
                val colors = FloatArray(16) { index ->
                    start[index] + (end[index] - start[index]) * fraction
                }
                painter.update(
                    width = size.width,
                    height = size.height,
                    time = time(),
                    isDark = isDark,
                    preset = preset,
                    colors = colors
                )
                drawRect(painter.brush)
            }
        }
        content()
    }
}

@Composable
private fun rememberAboutFrameSeconds(playing: Boolean): () -> Float {
    var time by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) {
            offset = time
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            time = offset + (now - start) / 1_000_000_000f
        }
    }
    return { time }
}

private class AboutBgPainter {
    private val shader by lazy {
        RuntimeShader(ABOUT_OS3_SHADER).also {
            it.setFloatUniform("uTranslateY", 0f)
            it.setFloatUniform("uNoiseScale", 1.5f)
            it.setFloatUniform("uPointRadiusMulti", 1f)
            it.setFloatUniform("uAlphaMulti", 1f)
            it.setFloatUniform("uAlphaOffset", 0.1f)
            it.setFloatUniform("uShadowOffset", 0.01f)
        }
    }
    val brush: Brush by lazy { shader.asBrush() }
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastDark: Boolean? = null

    fun update(
        width: Float,
        height: Float,
        time: Float,
        isDark: Boolean,
        preset: AboutBgPreset,
        colors: FloatArray
    ) {
        if (width != lastWidth || height != lastHeight) {
            shader.setFloatUniform("uResolution", width, height)
            shader.setFloatUniform("uBound", 0f, 0.22f, 1f, 0.78f)
            lastWidth = width
            lastHeight = height
        }
        if (lastDark != isDark) {
            shader.setFloatUniform("uPoints", preset.points)
            shader.setFloatUniform("uPointOffset", preset.pointOffset)
            shader.setFloatUniform("uLightOffset", preset.lightOffset)
            shader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
            shader.setFloatUniform("uShadowColorMulti", 0.3f)
            shader.setFloatUniform("uShadowColorOffset", 0.3f)
            shader.setFloatUniform("uShadowNoiseScale", 5f)
            lastDark = isDark
        }
        shader.setFloatUniform("uColors", colors)
        shader.setFloatUniform("uAnimTime", time)
    }
}

private class AboutBgPreset(
    val points: FloatArray,
    private val colors1: FloatArray,
    private val colors2: FloatArray,
    private val colors3: FloatArray,
    val colorPeriodSeconds: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float
) {
    fun palette(index: Int): FloatArray = when (index.mod(4)) {
        0 -> colors2
        1 -> colors1
        2 -> colors2
        else -> colors3
    }

    companion object {
        private val commonPoints = floatArrayOf(
            0.8f, 0.2f, 1f, 0.8f, 0.9f, 1f,
            0.2f, 0.9f, 1f, 0.2f, 0.2f, 1f
        )
        val light = AboutBgPreset(
            commonPoints,
            floatArrayOf(1f, 0.9f, 0.94f, 1f, 1f, 0.84f, 0.89f, 1f, 0.97f, 0.73f, 0.82f, 1f, 0.64f, 0.65f, 0.98f, 1f),
            floatArrayOf(0.58f, 0.74f, 1f, 1f, 1f, 0.9f, 0.93f, 1f, 0.74f, 0.76f, 1f, 1f, 0.97f, 0.77f, 0.84f, 1f),
            floatArrayOf(0.98f, 0.86f, 0.9f, 1f, 0.6f, 0.73f, 0.98f, 1f, 0.92f, 0.93f, 1f, 1f, 0.56f, 0.69f, 1f, 1f),
            5f, 0.1f, 0.2f, 0.2f
        )
        val dark = AboutBgPreset(
            commonPoints,
            floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
            floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0f, 0.2f, 0.78f, 0.5f),
            floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
            8f, 0f, 0.17f, 0.4f
        )
    }
}

private const val ABOUT_OS3_SHADER = """
uniform vec2 uResolution;
uniform float uAnimTime;
uniform vec4 uBound;
uniform float uTranslateY;
uniform vec3 uPoints[4];
uniform vec4 uColors[4];
uniform float uAlphaMulti;
uniform float uNoiseScale;
uniform float uPointOffset;
uniform float uPointRadiusMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;
uniform float uAlphaOffset;
uniform float uShadowColorMulti;
uniform float uShadowColorOffset;
uniform float uShadowNoiseScale;
uniform float uShadowOffset;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.13);
    p3 += dot(p3, p3.yzx + 3.333);
    return fract((p3.x + p3.y) * p3.z);
}
float perlin(vec2 x) {
    vec2 i = floor(x); vec2 f = fract(x);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
float gradientNoise(vec2 uv) {
    return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
}
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -0.3333333, 0.6666667, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y); float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 0.6666667, 0.3333333, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
vec4 main(vec2 fragCoord) {
    vec2 vUv = fragCoord / uResolution; vUv.y = 1.0 - vUv.y;
    vec2 uv = vUv - vec2(0.0, uTranslateY);
    uv = (uv - uBound.xy) / uBound.zw;
    vec4 color = vec4(0.0);
    float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime));
    for (int i = 0; i < 4; i++) {
        vec4 pointColor = uColors[i]; pointColor.rgb *= pointColor.a;
        vec2 point = uPoints[i].xy;
        point.x += sin(uAnimTime + point.y) * uPointOffset;
        point.y += cos(uAnimTime + point.x) * uPointOffset;
        float pct = smoothstep(uPoints[i].z * uPointRadiusMulti, 0.0, distance(uv, point));
        color.rgb = mix(color.rgb, pointColor.rgb, pct);
        color.a = mix(color.a, pointColor.a, pct);
    }
    float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);
    color.rgb /= max(color.a, 0.0001);
    vec3 hsv = rgb2hsv(color.rgb);
    hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
    color.rgb = hsv2rgb(hsv) + oppositeNoise * uLightOffset;
    color.a = clamp(color.a, 0.0, 1.0) * uAlphaMulti;
    color += (1.0 / 255.0) * gradientNoise(fragCoord) - (0.5 / 255.0);
    return vec4(color.rgb * color.a, color.a);
}
"""
