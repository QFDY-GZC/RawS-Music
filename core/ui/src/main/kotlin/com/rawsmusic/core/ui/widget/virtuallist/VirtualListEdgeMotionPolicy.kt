package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
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
internal const val VIRTUAL_LIST_HEADER_MAX_STRETCH_DP = 50f
internal const val VIRTUAL_LIST_HEADER_CHILD_STRETCH_DP = 20f
internal const val VIRTUAL_LIST_EDGE_REBOUND_MIN_FACTOR = 0.5f
internal const val VIRTUAL_LIST_EDGE_REBOUND_TIME_BASE_MS = 1000f
internal const val VIRTUAL_LIST_EDGE_BEZIER_TOLERANCE_BASE = 500f
internal const val VIRTUAL_LIST_EDGE_REBOUND_SPEED_PX_PER_SECOND = 1500f

private const val VIRTUAL_LIST_EDGE_BEZIER_P1_X = 0.103f
private const val VIRTUAL_LIST_EDGE_BEZIER_P1_Y = 0.389f
private const val VIRTUAL_LIST_EDGE_BEZIER_P2_X = 0.307f
private const val VIRTUAL_LIST_EDGE_BEZIER_P2_Y = 0.966f
private const val VIRTUAL_LIST_EDGE_BEZIER_DERIVATIVE_EPSILON = 1.0e-6f
private const val VIRTUAL_LIST_EDGE_BEZIER_NEWTON_STEPS = 8

/**
 * Accumulated overscroll target. The layout first consumes the complete move, then subtracts:
 *
 *   currentEdge - quarter * ln(1 + accumulatedRaw / quarter)
 *
 * through a second layout delta. Therefore the remaining visible edge displacement is the logarithmic
 * term itself, not `raw - logarithmicTerm`.
 */
internal fun virtualListVisualOvershootPx(rawPullPx: Float, maxOvershootPx: Float): Float {
    if (maxOvershootPx <= 0f || rawPullPx == 0f) return 0f
    val magnitude = abs(rawPullPx)
    val quarter = maxOvershootPx * 0.25f
    val visual = quarter * ln(1f + magnitude / quarter.coerceAtLeast(0.001f))
    return sign(rawPullPx) * visual.coerceIn(0f, maxOvershootPx)
}

/**
 * Outward-edge correction after the first layout pass.
 *
 * The first pass has already laid out the holders, so [candidateEdgePx] is the
 * layout-clamped signed edge (±P), not the unrestricted pointer delta. The correction computes
 * `rollback = abs(candidate) - quarter*ln(1 + abs(rawAccumulator)/quarter)` and applies a second
 * layout delta in the opposite direction, limited by the current MOVE magnitude.
 */
internal fun virtualListCorrectExtendedEdgePx(
    candidateEdgePx: Float,
    inputDeltaPx: Float,
    rawAccumulatorPx: Float,
    maxOvershootPx: Float,
): Float {
    if (maxOvershootPx <= 0f || candidateEdgePx == 0f || inputDeltaPx == 0f) return candidateEdgePx
    val targetMagnitude = abs(
        virtualListVisualOvershootPx(
            rawPullPx = rawAccumulatorPx,
            maxOvershootPx = maxOvershootPx,
        )
    )
    val rollbackMagnitude = abs(candidateEdgePx) - targetMagnitude
    val rollback = if (rollbackMagnitude < abs(inputDeltaPx)) {
        rollbackMagnitude
    } else {
        abs(inputDeltaPx)
    }
    return (candidateEdgePx - sign(candidateEdgePx) * rollback)
        .coerceIn(-maxOvershootPx, maxOvershootPx)
}

/**
 * Distribute edge deformation by visible grid-row ordinal rather than physical Y coordinate:
 *
 *   rowFactor = (row - firstVisibleRow + 1) / visibleRowCount
 *
 * where visibleRowCount is the exclusive-last-row minus firstVisibleRow.  Every item in the same
 * grid row therefore receives exactly the same edge transform, including partially visible rows.
 */
internal fun virtualListHolderEdgeFraction(
    itemIndex: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    columns: Int,
): Float {
    if (itemIndex < 0 || firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex) return 0f
    val safeColumns = columns.coerceAtLeast(1)
    val firstVisibleRow = firstVisibleIndex / safeColumns
    // Convert the last visible item into an exclusive grid-row bound using integer division.
    val lastVisibleRowExclusive = (lastVisibleIndex + safeColumns) / safeColumns
    val visibleRowCount = lastVisibleRowExclusive - firstVisibleRow
    if (visibleRowCount <= 0) return 0f
    val row = itemIndex / safeColumns
    return ((row - firstVisibleRow + 1).toFloat() / visibleRowCount.toFloat()).coerceIn(0f, 1f)
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

/**
 * Child translation within the collection header during overscroll.
 *
 * The header View itself remains a full-bounds scale=1 holder. Its internal AAItemView first
 * scrolls by the signed edge amount, then each laid-out child gets an additional translation
 * proportional to its Y position. maxStretchOvershoot is 50dp and the stored child stretch range
 * is 20dp.
 */
internal fun virtualListHeaderChildTranslationY(
    edgePx: Float,
    maxStretchPx: Float,
    childStretchPx: Float,
    headerHeightPx: Float,
    childTopPx: Float,
): Float {
    if (edgePx == 0f || maxStretchPx <= 0f || headerHeightPx <= 0f) return 0f
    var stretchSlope = (edgePx / maxStretchPx) * (childStretchPx / headerHeightPx)
    if (edgePx < 0f) {
        stretchSlope *= childStretchPx / maxStretchPx
    }
    val extra = max(0f, childTopPx + edgePx) * stretchSlope
    return edgePx + extra
}

internal fun virtualListEdgeReboundDurationMs(overshootPx: Float): Int =
    (
        maxOf(
            VIRTUAL_LIST_EDGE_REBOUND_MIN_FACTOR,
            abs(overshootPx) / VIRTUAL_LIST_EDGE_REBOUND_SPEED_PX_PER_SECOND,
        ) * VIRTUAL_LIST_EDGE_REBOUND_TIME_BASE_MS
    ).toInt()

/**
 * Rebound uses timeScale=max(0.5, distance/1500), solver tolerance=1/(timeScale*500), and normalized
 * time=(elapsedMs/1000)/timeScale. Keep these values separate from a fixed easing specification
 * because numerical tolerance varies with rebound distance.
 */
internal fun virtualListEdgeReboundTimeScale(overshootPx: Float): Float =
    maxOf(
        VIRTUAL_LIST_EDGE_REBOUND_MIN_FACTOR,
        abs(overshootPx) / VIRTUAL_LIST_EDGE_REBOUND_SPEED_PX_PER_SECOND,
    )

internal fun virtualListEdgeReboundTolerance(timeScale: Float): Float =
    1f / (timeScale.coerceAtLeast(Float.MIN_VALUE) * VIRTUAL_LIST_EDGE_BEZIER_TOLERANCE_BASE)

internal fun virtualListEdgeReboundProgress(
    elapsedMs: Long,
    overshootPx: Float,
): Float {
    val timeScale = virtualListEdgeReboundTimeScale(overshootPx)
    val x = ((elapsedMs.toFloat() / 1000f) / timeScale).coerceAtMost(1f)
    return edgeReboundCubicBezier(
        x = x,
        tolerance = virtualListEdgeReboundTolerance(timeScale),
    )
}

/** Evaluate the edge rebound curve with a caller-supplied numerical tolerance. */
internal fun edgeReboundCubicBezier(x: Float, tolerance: Float): Float =
    scrollCubicBezier(
        p1x = VIRTUAL_LIST_EDGE_BEZIER_P1_X,
        p1y = VIRTUAL_LIST_EDGE_BEZIER_P1_Y,
        p2x = VIRTUAL_LIST_EDGE_BEZIER_P2_X,
        p2y = VIRTUAL_LIST_EDGE_BEZIER_P2_Y,
        x = x,
        tolerance = tolerance,
    )

/** Cubic Bezier solver shared by scroll and edge-rebound animations. */
internal fun scrollCubicBezier(
    p1x: Float,
    p1y: Float,
    p2x: Float,
    p2y: Float,
    x: Float,
    tolerance: Float,
): Float {
    val xC = p1x * 3f
    val xB = ((p2x - p1x) * 3f) - xC
    val xA = (1f - xC) - xB
    val yC = p1y * 3f
    val yB = ((p2y - p1y) * 3f) - yC
    val yA = (1f - yC) - yB

    var t = x
    var step = 0
    while (step < VIRTUAL_LIST_EDGE_BEZIER_NEWTON_STEPS) {
        val error = (((xA * t + xB) * t + xC) * t) - x
        if (abs(error) < tolerance) {
            return ((yA * t + yB) * t + yC) * t
        }
        val derivative = ((3f * xA * t + 2f * xB) * t) + xC
        if (abs(derivative) < VIRTUAL_LIST_EDGE_BEZIER_DERIVATIVE_EPSILON) break
        t -= error / derivative
        step++
    }

    var lower = 0f
    if (x < 0f) return 0f
    var upper = 1f
    if (x > 1f) return 1f
    t = x
    while (lower < upper) {
        val curveX = ((xA * t + xB) * t + xC) * t
        if (abs(curveX - x) < tolerance) break
        if (x > curveX) {
            lower = t
        } else {
            upper = t
        }
        t = lower + ((upper - lower) * 0.5f)
    }
    return ((yA * t + yB) * t + yC) * t
}


/** Deceleration and minimum duration for the scroll fling. */
internal const val SCROLL_FLING_DECELERATION = 9000f
internal const val SCROLL_FLING_MIN_DURATION_S = 0.75f

/** Stronger deceleration for velocity carried into the edge after contact. */
internal const val SCROLL_FLING_EDGE_DECELERATION = 1000000f

/**
 * Integrates scroll displacement from a decaying velocity curve.
 *
 * `start()` sets T = max(minDuration, |v| / deceleration) and cubic tolerance = 1/(T*500).
 * On each frame, `step()` evaluates the
 * spent fraction of the initial velocity, the current velocity is `(1 - fraction) * v0`, and the
 * returned displacement is the trapezoid `(previousVelocity + velocity) * dt / 2`. On the frame
 * that reaches fraction 1, dt is multiplied by `fraction - 1`, so the final step is zero.
 *
 * Fling uses the 0.103/0.389/0.307/0.966 curve with (9000, 0.75); after the list reaches an edge,
 * the edge phase restarts the runner with a linear (0,0,1,1) curve and (1e6, 0).
 */
internal class ScrollVelocityRunner(
    private val p1x: Float,
    private val p1y: Float,
    private val p2x: Float,
    private val p2y: Float,
) {
    /** Velocity after the most recent step (px/s, same sign as the scroll delta). */
    var velocity: Float = 0f
        private set
    /** Whether the velocity curve has reached its endpoint. */
    var finished: Boolean = true
        private set
    private var initialVelocity = 0f
    private var durationS = 0f
    private var tolerance = 0f
    private var startMs = 0L
    private var lastMs = 0L

    fun start(velocityPxPerSecond: Float, deceleration: Float, minDurationS: Float, nowMs: Long) {
        durationS = max(
            minDurationS,
            abs(velocityPxPerSecond / (sign(velocityPxPerSecond) * deceleration)),
        )
        startMs = nowMs
        lastMs = nowMs
        initialVelocity = velocityPxPerSecond
        tolerance = 1f / (durationS * 500f)
        velocity = velocityPxPerSecond
        finished = false
    }

    fun step(nowMs: Long): Float {
        val fraction = scrollCubicBezier(
            p1x = p1x,
            p1y = p1y,
            p2x = p2x,
            p2y = p2y,
            x = minOf(1f, ((nowMs - startMs) / 1000f) / durationS),
            tolerance = tolerance,
        )
        var dtS = (nowMs - lastMs) / 1000f
        if (fraction >= 1f) {
            finished = true
            dtS *= fraction - 1f
        }
        val nextVelocity = (1f - fraction) * initialVelocity
        val displacement = ((velocity + nextVelocity) * dtS) / 2f
        velocity = nextVelocity
        lastMs = nowMs
        return displacement
    }

    companion object {
        fun fling() = ScrollVelocityRunner(
            VIRTUAL_LIST_EDGE_BEZIER_P1_X,
            VIRTUAL_LIST_EDGE_BEZIER_P1_Y,
            VIRTUAL_LIST_EDGE_BEZIER_P2_X,
            VIRTUAL_LIST_EDGE_BEZIER_P2_Y,
        )

        fun linear() = ScrollVelocityRunner(0f, 0f, 1f, 1f)
    }
}
