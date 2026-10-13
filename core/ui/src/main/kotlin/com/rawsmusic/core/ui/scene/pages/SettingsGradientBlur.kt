package com.rawsmusic.core.ui.scene.pages

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.kyant.backdrop.BackdropEffectScope
import kotlin.math.ceil

/** Both inputs use the same recorded backdrop, including the page's solid fill. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun BackdropEffectScope.settingsGradientBlur(radiusPx: Float) {
    if (radiusPx <= 0f || size.height <= 0f) return
    // Keep the Gaussian kernel outside the visible overlay; otherwise its bottom edge clamps
    // to the overlay's last row rather than sampling the content immediately below it.
    padding = ceil(radiusPx * 3f)
    val blurMask = obtainRuntimeShader("settings_blurred_weight", SettingsSampleWeightShader)
    val sharpMask = obtainRuntimeShader("settings_sharp_weight", SettingsSampleWeightShader)
    for ((shader, sharp) in listOf(blurMask to 0f, sharpMask to 1f)) {
        shader.setFloatUniform("height", size.height)
        shader.setFloatUniform("inset", padding)
        shader.setFloatUniform("sharp", sharp)
    }
    val gaussian = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val blurred = RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(blurMask, "content"),
        gaussian,
    )
    val original = RenderEffect.createRuntimeShaderEffect(sharpMask, "content")
    // Premultiplied samples have complementary weights: output alpha remains the input alpha.
    renderEffect = RenderEffect.createBlendModeEffect(blurred, original, BlendMode.PLUS)
}

private const val SettingsSampleWeightShader = """
uniform shader content;
uniform float height;
uniform float inset;
uniform float sharp;
half4 main(float2 coord) {
    float t = clamp((coord.y - inset) / height, 0.0, 1.0);
    float originalWeight = t * t * t;
    float weight = mix(1.0 - originalWeight, originalWeight, sharp);
    return content.eval(coord) * weight;
}
"""
