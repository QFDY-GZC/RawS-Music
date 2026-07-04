package com.kyant.backdrop.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ShaderBrush
import com.kyant.backdrop.BackdropEffectScope
import org.intellij.lang.annotations.Language

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun BackdropEffectScope.liquidGlass(
    cornerRadius: Float,
    refraction: Float = 0.85f,
    curve: Float = 0.7f,
    dispersion: Float = 0.4f,
    saturation: Float = 1.0f,
    contrast: Float = 1.0f,
    edge: Float = 0.2f,
    tintR: Float = 0f,
    tintG: Float = 0f,
    tintB: Float = 0f,
    tintA: Float = 0f
) {
    val shader = obtainRuntimeShader("liquidGlass", LIQUID_GLASS_SHADER)
    val w = size.width
    val h = size.height
    shader.apply {
        setFloatUniform("resolution", w, h)
        setFloatUniform("lensCenter", w / 2f, h / 2f)
        setFloatUniform("lensSize", w, h)
        setFloatUniform("cornerRadius", cornerRadius)
        setFloatUniform("refraction", refraction)
        setFloatUniform("curve", curve)
        setFloatUniform("dispersion", dispersion)
        setFloatUniform("saturation", saturation)
        setFloatUniform("contrast", contrast)
        setFloatUniform("edge", edge)
        setFloatUniform("tint", tintR, tintG, tintB, tintA)
    }
    val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    val currentEffect = renderEffect
    renderEffect = if (currentEffect != null) {
        RenderEffect.createChainEffect(currentEffect, effect)
    } else {
        effect
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun liquidGlassShaderBrush(
    width: Float,
    height: Float,
    cornerRadius: Float,
    refraction: Float = 0.85f,
    curve: Float = 0.7f,
    dispersion: Float = 0.4f,
    saturation: Float = 1.0f,
    contrast: Float = 1.0f,
    edge: Float = 0.2f,
    tintR: Float = 0f,
    tintG: Float = 0f,
    tintB: Float = 0f,
    tintA: Float = 0f
): ShaderBrush {
    val shader = RuntimeShader(LIQUID_GLASS_SHADER)
    shader.setFloatUniform("resolution", width, height)
    shader.setFloatUniform("lensCenter", width / 2f, height / 2f)
    shader.setFloatUniform("lensSize", width, height)
    shader.setFloatUniform("cornerRadius", cornerRadius)
    shader.setFloatUniform("refraction", refraction)
    shader.setFloatUniform("curve", curve)
    shader.setFloatUniform("dispersion", dispersion)
    shader.setFloatUniform("saturation", saturation)
    shader.setFloatUniform("contrast", contrast)
    shader.setFloatUniform("edge", edge)
    shader.setFloatUniform("tint", tintR, tintG, tintB, tintA)
    return ShaderBrush(shader)
}

@Language("AGSL")
val LIQUID_GLASS_SHADER = """
uniform float2 resolution;
uniform float2 lensCenter;
uniform float2 lensSize;
uniform float cornerRadius;
uniform float refraction;
uniform float curve;
uniform float dispersion;
uniform float saturation;
uniform float contrast;
uniform float4 tint;
uniform float edge;
uniform shader content;

const float ANTIALIAS_RADIUS = 1.5;

float roundedRectDistance(float2 point, float2 boxExtent, float radius) {
    float2 offsetFromCorner = abs(point) - boxExtent + float2(radius);
    float outsideDistance = length(max(offsetFromCorner, 0.0));
    float insideDistance = min(max(offsetFromCorner.x, offsetFromCorner.y), 0.0);
    return outsideDistance + insideDistance - radius;
}

float2 calculateSurfaceGradient(float2 point, float2 boxExtent, float radius) {
    float2 innerOffset = abs(point) - boxExtent + float2(radius);
    float2 signVector = float2(
        point.x >= 0.0 ? 1.0 : -1.0,
        point.y >= 0.0 ? 1.0 : -1.0
    );

    if (max(innerOffset.x, innerOffset.y) > 0.0) {
        float2 clampedOffset = max(innerOffset, 0.0);
        return signVector * normalize(clampedOffset);
    }

    if (innerOffset.x > innerOffset.y) {
        return float2(signVector.x, 0.0);
    }
    return float2(0.0, signVector.y);
}

float getLuminance(half3 rgb) {
    return dot(rgb, half3(0.2126, 0.7152, 0.0722));
}

half3 applyColorGrading(half3 inputColor, float satLevel, float contrastLevel, float4 tintOverlay) {
    float gray = getLuminance(inputColor);
    half3 saturatedColor = half3(clamp(mix(half3(gray), inputColor, satLevel), 0.0, 1.0));
    half3 contrastedColor = half3(clamp((saturatedColor - 0.5) * contrastLevel + 0.5, 0.0, 1.0));
    return mix(contrastedColor, half3(tintOverlay.rgb), tintOverlay.a);
}

half4 main(float2 fragCoord) {
    float2 halfExtent = lensSize * 0.5;
    float clampedRadius = min(cornerRadius, min(halfExtent.x, halfExtent.y));

    float2 localPos = fragCoord - lensCenter;
    float dist = roundedRectDistance(localPos, halfExtent, clampedRadius);

    if (dist > ANTIALIAS_RADIUS) {
        return content.eval(fragCoord);
    }

    float2 surfaceDir = calculateSurfaceGradient(localPos, halfExtent, clampedRadius);

    float2 samplingCoord = clamp(fragCoord, float2(0.0), resolution);
    if (refraction > 0.0 && curve > 0.0) {
        float minExtent = min(halfExtent.x, halfExtent.y);
        float normalizedDepth = clamp(-dist / (minExtent * refraction), 0.0, 1.0);
        float sphericalFactor = 1.0 - normalizedDepth;
        float bendAmount = 1.0 - sqrt(1.0 - sphericalFactor * sphericalFactor);
        float displacement = bendAmount * curve * minExtent;
        samplingCoord = clamp(fragCoord - displacement * surfaceDir, float2(0.0), resolution);
    }

    half4 sampledColor;
    if (dispersion > 0.0) {
        float2 normalizedPos = localPos / halfExtent;
        float2 chromaticShift = dispersion * normalizedPos * normalizedPos * normalizedPos * min(halfExtent.x, halfExtent.y) * 0.1;

        float2 redCoord = samplingCoord - chromaticShift;
        float2 greenCoord = samplingCoord;
        float2 blueCoord = samplingCoord + chromaticShift;

        float redDist = roundedRectDistance(redCoord - lensCenter, halfExtent, clampedRadius);
        float blueDist = roundedRectDistance(blueCoord - lensCenter, halfExtent, clampedRadius);

        half4 greenSample = content.eval(greenCoord);
        half4 redSample = (redDist <= 0.0) ? content.eval(redCoord) : greenSample;
        half4 blueSample = (blueDist <= 0.0) ? content.eval(blueCoord) : greenSample;

        sampledColor = half4(redSample.r, greenSample.g, blueSample.b, greenSample.a);
    } else {
        sampledColor = content.eval(samplingCoord);
    }

    if (sampledColor.a <= 0.0) {
        sampledColor = content.eval(fragCoord);
    }

    sampledColor.rgb = applyColorGrading(sampledColor.rgb, saturation, contrast, tint);

    if (edge > 0.0) {
        float rimFactor = smoothstep(-edge * 10.0, 0.0, dist);
        float2 lightDir = normalize(float2(-1.0, -1.0));
        float lightIntensity = abs(dot(surfaceDir, lightDir));
        sampledColor.rgb += half3(rimFactor * lightIntensity * edge);
    }

    float edgeAlpha = 1.0 - smoothstep(-ANTIALIAS_RADIUS * 0.5, ANTIALIAS_RADIUS * 0.5, dist);

    half4 originalColor = content.eval(fragCoord);
    return mix(originalColor, sampledColor, edgeAlpha);
}
"""
