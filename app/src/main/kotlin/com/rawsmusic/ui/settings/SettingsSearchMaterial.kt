package com.rawsmusic.ui.settings

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Portable material on every ROM. This is not the proprietary RenderNode shader. */
internal object SettingsSearchMaterial {
    // handleStateChangedAnimation extends the background's layout by 20 physical pixels;
    // layoutBackgroundRight anchors the functional button at the parent's trailing edge.
    fun cancelLeft(width: Float, buttonWidth: Float, progress: Float): Float =
        width - buttonWidth + cancelTranslation(progress)

    fun cancelTranslation(progress: Float): Float = 20f * (1f - progress)

    fun searchWidth(width: Int, buttonWidth: Int, gap: Int, progress: Float): Int =
        (width - (buttonWidth + gap) * progress).roundToInt().coerceIn(0, width)

    // Visual connection range, not a reconstruction of the native parameter multiplication.
    const val connectionRangeDp = 12f
    data class Capsule(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun distance(x: Float, y: Float): Float {
            val radius = (bottom - top) / 2f
            val qx = abs(x - (left + right) / 2f) - max(0f, (right - left) / 2f - radius)
            val qy = abs(y - (top + bottom) / 2f)
            return hypot(max(qx, 0f), qy) + min(max(qx, qy), 0f) - radius
        }
    }

    fun distance(search: Capsule, cancel: Capsule, x: Float, y: Float, density: Float): Float {
        val a = search.distance(x, y)
        val b = cancel.distance(x, y)
        val k = connectionRangeDp * density
        if (k <= 0f) return min(a, b)
        val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
        return b + (a - b) * h - k * h * (1f - h)
    }

    // The same mask is rendered once behind both controls: overlapping translucent fills
    // must not stack and turn the attachment darker than either capsule.
    const val shader = """
        uniform float4 searchBounds;
        uniform float4 cancelBounds;
        uniform float smoothing;
        uniform float cancelOpacity;
        layout(color) uniform half4 material;
        float capsule(float2 p, float4 bounds) {
            float r = (bounds.w - bounds.y) * 0.5;
            float2 q = abs(p - (bounds.xy + bounds.zw) * 0.5)
                - float2(max(0.0, (bounds.z - bounds.x) * 0.5 - r), 0.0);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }
        half4 main(float2 p) {
            float a = capsule(p, searchBounds);
            float b = capsule(p, cancelBounds);
            float h = clamp(0.5 + 0.5 * (b - a) / smoothing, 0.0, 1.0);
            float d = mix(b, a, h) - smoothing * h * (1.0 - h);
            float cover = 1.0 - smoothstep(-0.5, 0.5, d);
            float searchCover = 1.0 - smoothstep(-0.5, 0.5, a);
            float alpha = cover * material.a * mix(cancelOpacity, 1.0, searchCover);
            return half4(material.rgb * alpha, alpha);
        }
    """
}
