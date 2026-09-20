package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/**
 * VirtualList edge physics used by the retained list runtime.
 *
 * Keep this separate from ArtworkPager/SharedContentGesture. Collection-detail overscroll is owned
 * by VirtualList itself: the scroller exposes one signed edge overshoot and the layout engine applies
 * a progressively stronger offset/scale to each attached holder.
 */
internal const val VIRTUAL_LIST_MAX_OVERSHOOT_DP = 44f
internal const val VIRTUAL_LIST_HOLDER_SHIFT_DP = 30f
internal const val VIRTUAL_LIST_MAX_HOLDER_SCALE_DELTA = 0.04f
internal const val VIRTUAL_LIST_EDGE_REBOUND_MIN_MS = 500
internal const val VIRTUAL_LIST_EDGE_REBOUND_SPEED_PX_PER_SECOND = 1500f

// Edge rebound curve.
internal val VirtualListEdgeReboundEasing = CubicBezierEasing(0.103f, 0.389f, 0.307f, 0.966f)

/**
 * Raw finger travel is not the visual overshoot. The edge-scroll policy reduces the usable edge delta with a
 * logarithmic term based on one quarter of maxOvershoot before the LayoutRes is updated. This
 * equivalent accumulated form keeps the same shape while giving Compose one stable authoritative
 * signed overshoot value.
 */
internal fun virtualListVisualOvershootPx(rawPullPx: Float, maxOvershootPx: Float): Float {
    if (maxOvershootPx <= 0f || rawPullPx == 0f) return 0f
    val magnitude = abs(rawPullPx)
    val quarter = maxOvershootPx * 0.25f
    val resisted = magnitude - quarter * ln(1f + magnitude / quarter.coerceAtLeast(0.001f))
    return sign(rawPullPx) * resisted.coerceIn(0f, maxOvershootPx)
}

/**
 * The edge-scroll policy keeps consuming the pointer transaction after the edge has been captured. Limiting the
 * accumulated raw pull to 1.5x maxOvershoot reaches the recovered 44dp visual cap without making a
 * large pull require an equally large reverse drag before normal scrolling can resume.
 */
internal fun virtualListRawPullCapPx(maxOvershootPx: Float): Float =
    maxOvershootPx.coerceAtLeast(0f) * 1.5f

/**
 * The edge-layout policy distributes edge deformation by attached row order. A holder near the lower edge
 * receives more of the transform than one near the upper edge, producing the rubber-sheet motion.
 */
internal fun virtualListHolderEdgeFraction(
    holderTopPx: Float,
    holderBottomPx: Float,
    viewportHeightPx: Int,
): Float {
    val viewport = viewportHeightPx.coerceAtLeast(1).toFloat()
    val bottom = holderBottomPx.coerceAtLeast(holderTopPx)
    return (bottom / viewport).coerceIn(0f, 1f)
}

internal fun virtualListHolderScaleY(
    overshootPx: Float,
    maxOvershootPx: Float,
    holderFraction: Float,
): Float {
    if (maxOvershootPx <= 0f || overshootPx == 0f) return 1f
    val normalized = (abs(overshootPx) / maxOvershootPx).coerceIn(0f, 1f)
    val signedDelta = sign(overshootPx) * VIRTUAL_LIST_MAX_HOLDER_SCALE_DELTA *
        normalized * holderFraction.coerceIn(0f, 1f)
    return 1f + signedDelta
}

internal fun virtualListHolderTranslationY(
    overshootPx: Float,
    maxOvershootPx: Float,
    holderShiftPx: Float,
    holderFraction: Float,
): Float {
    if (maxOvershootPx <= 0f || overshootPx == 0f) return 0f
    val normalized = (abs(overshootPx) / maxOvershootPx).coerceIn(0f, 1f)
    return sign(overshootPx) * holderShiftPx.coerceAtLeast(0f) *
        normalized * holderFraction.coerceIn(0f, 1f)
}

internal fun virtualListEdgeReboundDurationMs(overshootPx: Float): Int =
    maxOf(
        VIRTUAL_LIST_EDGE_REBOUND_MIN_MS,
        (abs(overshootPx) / VIRTUAL_LIST_EDGE_REBOUND_SPEED_PX_PER_SECOND * 1000f).toInt(),
    )

