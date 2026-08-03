package com.rawsmusic.core.ui.widget.powerlist

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

@Immutable
data class ComposeItemPosition(
    val bounds: IntRect = IntRect.Zero,
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val sceneId: Int = PowerListSceneItem.SCENE_NORMAL
) {
    val width: Int get() = bounds.width
    val height: Int get() = bounds.height

    fun isEmpty(): Boolean = width <= 0 || height <= 0
}

@Immutable
data class ComposeItemRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val alpha: Float = 1f,
    val fontSizeSp: Float = 0f
)

@Immutable
data class ComposeTransitionRects(
    val cover: ComposeItemRect,
    val title: ComposeItemRect,
    val subtitle: ComposeItemRect,
    val meta: ComposeItemRect = ComposeItemRect(0, 0, 1, 1, 0f),
    val coverRadiusDp: Float
)

fun lerpComposeItemPosition(
    from: ComposeItemPosition,
    to: ComposeItemPosition,
    fraction: Float
): ComposeItemPosition {
    val f = fraction.coerceIn(0f, 1f)
    return ComposeItemPosition(
        bounds = IntRect(
            left = lerpInt(from.bounds.left, to.bounds.left, f),
            top = lerpInt(from.bounds.top, to.bounds.top, f),
            right = lerpInt(from.bounds.right, to.bounds.right, f),
            bottom = lerpInt(from.bounds.bottom, to.bounds.bottom, f)
        ),
        alpha = lerpFloat(from.alpha, to.alpha, f),
        scaleX = lerpFloat(from.scaleX, to.scaleX, f),
        scaleY = lerpFloat(from.scaleY, to.scaleY, f),
        sceneId = if (f < 0.5f) from.sceneId else to.sceneId
    )
}

private fun lerpInt(from: Int, to: Int, fraction: Float): Int {
    return (from + (to - from) * fraction).roundToInt()
}

private fun lerpFloat(from: Float, to: Float, fraction: Float): Float {
    return from + (to - from) * fraction
}
