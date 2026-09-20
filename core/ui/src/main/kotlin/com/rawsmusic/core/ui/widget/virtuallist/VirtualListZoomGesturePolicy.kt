package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val REFERENCE_ZOOM_VELOCITY_THRESHOLD_DP_PER_SECOND = 500f
internal const val REFERENCE_ZOOM_COMMIT_PROGRESS = 0.30f
internal const val REFERENCE_ZOOM_CANCEL_VELOCITY_WINDOW = 0.20f
internal const val REFERENCE_ZOOM_COMMIT_VELOCITY_WINDOW = 0.80f
internal const val REFERENCE_ZOOM_SETTLE_MS = 500
internal const val REFERENCE_ZOOM_REBOUND_MIN_MS = 350
internal const val REFERENCE_ZOOM_COMMIT_MIN_SETTLE_MS = 100
internal const val REFERENCE_ZOOM_CANCEL_MIN_SETTLE_MS = REFERENCE_ZOOM_SETTLE_MS / 3
internal const val REFERENCE_ZOOM_COMMIT_MIN_PROGRESS_VELOCITY_PER_SECOND = 2f
internal const val REFERENCE_ZOOM_COMMIT_MAX_PROGRESS_VELOCITY_PER_SECOND = 8f
internal const val REFERENCE_ZOOM_CANCEL_MIN_PROGRESS_VELOCITY_PER_SECOND = -8f
internal const val REFERENCE_ZOOM_CANCEL_MAX_PROGRESS_VELOCITY_PER_SECOND = -3.5f


/** scaleGestureOwner.p() acquires the zoom owner from the current span motion, not touch-down total span. */
internal fun virtualListPinchExpandsNow(rawDelta: Float, spanDeltaRatio: Float): Boolean = when {
    spanDeltaRatio > 0f -> true
    spanDeltaRatio < 0f -> false
    else -> rawDelta > 0f
}

/**
 * transitionOwner.P() only interrupts a completed endpoint rebound when a real new scale motion arrives. Merely
 * placing two fingers down must not snap the residual holder scale before direction is known.
 */
internal fun shouldCanonicalizeSettledVirtualListEndpointForNewPinch(
    endpointReached: Boolean,
    spanDeltaRatio: Float,
): Boolean = endpointReached && abs(spanDeltaRatio) > 0.000001f

/**
 * Reference transitionOwner.P() can hand an endpoint directly to the adjacent zoom transition without exposing a
 * settled adapter/layout tree in between. Keep the ordered mode graph explicit so Raw can perform
 * the same owner-to-owner handoff while the retained transition holders remain mounted.
 */
internal fun adjacentVirtualListMode(
    mode: ComposeVirtualListDisplayMode,
    zoomIn: Boolean,
): ComposeVirtualListDisplayMode? = if (zoomIn) {
    when (mode) {
        ComposeVirtualListDisplayMode.LIST_SMALL -> ComposeVirtualListDisplayMode.LIST_NORMAL
        ComposeVirtualListDisplayMode.LIST_NORMAL -> ComposeVirtualListDisplayMode.LIST_ZOOMED
        ComposeVirtualListDisplayMode.LIST_ZOOMED -> ComposeVirtualListDisplayMode.GRID_4
        ComposeVirtualListDisplayMode.GRID_4 -> ComposeVirtualListDisplayMode.GRID_3
        ComposeVirtualListDisplayMode.GRID_3 -> ComposeVirtualListDisplayMode.GRID_2
        ComposeVirtualListDisplayMode.GRID_2 -> null
    }
} else {
    when (mode) {
        ComposeVirtualListDisplayMode.GRID_2 -> ComposeVirtualListDisplayMode.GRID_3
        ComposeVirtualListDisplayMode.GRID_3 -> ComposeVirtualListDisplayMode.GRID_4
        ComposeVirtualListDisplayMode.GRID_4 -> ComposeVirtualListDisplayMode.LIST_ZOOMED
        ComposeVirtualListDisplayMode.LIST_ZOOMED -> ComposeVirtualListDisplayMode.LIST_NORMAL
        ComposeVirtualListDisplayMode.LIST_NORMAL -> ComposeVirtualListDisplayMode.LIST_SMALL
        ComposeVirtualListDisplayMode.LIST_SMALL -> null
    }
}

/** Per-frame scale delta expressed in the active transition's forward coordinate system. */
internal fun virtualListOrientedPinchFrameDelta(
    spanDeltaRatio: Float,
    transitionZoomIn: Boolean,
): Float = if (transitionZoomIn) spanDeltaRatio else -spanDeltaRatio

internal data class VirtualListZoomReleaseDecision(
    val confirm: Boolean,
    /** scaleGestureOwner's second release velocity, already expressed in transition-progress units/second. */
    val forwardedProgressVelocityPerSecond: Float,
)

internal enum class VirtualListZoomSettleKind {
    AccelerateDecelerate,
    ConstantVelocity,
}

internal data class VirtualListZoomSettleSpec(
    val kind: VirtualListZoomSettleKind,
    val durationMs: Int,
    val progressVelocityPerSecond: Float = 0f,
)

/** Direct port of ZoomGesture.mo2959() decision/velocity forwarding. */
internal fun resolveVirtualListZoomRelease(
    progress: Float,
    velocityDpPerSecond: Float,
    progressVelocityPerSecond: Float,
    transitionZoomIn: Boolean,
): VirtualListZoomReleaseDecision {
    val p = progress.coerceIn(0f, 1f)
    val confirm = if (abs(velocityDpPerSecond) >= REFERENCE_ZOOM_VELOCITY_THRESHOLD_DP_PER_SECOND) {
        (velocityDpPerSecond > 0f) == transitionZoomIn
    } else {
        p > REFERENCE_ZOOM_COMMIT_PROGRESS
    }
    // scaleGestureOwner transforms the second velocity into transition coordinates and divides it by four before
    // The pointer owner supplies transition-coordinate velocity to the settle policy.
    val forwardVelocity = when {
        confirm && p > REFERENCE_ZOOM_COMMIT_VELOCITY_WINDOW -> progressVelocityPerSecond / 4f
        !confirm && p < REFERENCE_ZOOM_CANCEL_VELOCITY_WINDOW -> progressVelocityPerSecond / 4f
        else -> 0f
    }
    return VirtualListZoomReleaseDecision(confirm, forwardVelocity)
}

/**
 * Transition settle selection for the zoom gesture owner.
 *
 * No forwarded velocity: a 500ms base duration is scaled by the remaining progress. Commit has a
 * 100ms floor; cancel uses 500/3ms. Both use AccelerateDecelerateInterpolator.
 *
 * Valid forwarded velocity: z1.P() clamps to +2..+8 progress/s for commit and -8..-3.5 progress/s
 * for cancel, then advances linearly until the selected endpoint is crossed.
 */
internal fun resolveVirtualListZoomSettleSpec(
    startProgress: Float,
    confirm: Boolean,
    forwardedProgressVelocityPerSecond: Float,
): VirtualListZoomSettleSpec {
    val start = startProgress.coerceIn(0f, 1f)
    val end = if (confirm) 1f else 0f
    val travel = abs(end - start)
    val validVelocity = when {
        confirm && forwardedProgressVelocityPerSecond > 1e-4f ->
            forwardedProgressVelocityPerSecond.coerceIn(
                REFERENCE_ZOOM_COMMIT_MIN_PROGRESS_VELOCITY_PER_SECOND,
                REFERENCE_ZOOM_COMMIT_MAX_PROGRESS_VELOCITY_PER_SECOND,
            )
        !confirm && forwardedProgressVelocityPerSecond < -1e-4f ->
            forwardedProgressVelocityPerSecond.coerceIn(
                REFERENCE_ZOOM_CANCEL_MIN_PROGRESS_VELOCITY_PER_SECOND,
                REFERENCE_ZOOM_CANCEL_MAX_PROGRESS_VELOCITY_PER_SECOND,
            )
        else -> 0f
    }
    if (validVelocity != 0f && travel > 0f) {
        return VirtualListZoomSettleSpec(
            kind = VirtualListZoomSettleKind.ConstantVelocity,
            durationMs = max(1, ceil(travel / abs(validVelocity) * 1000f).toInt()),
            progressVelocityPerSecond = validVelocity,
        )
    }

    val minDuration = if (confirm) {
        REFERENCE_ZOOM_COMMIT_MIN_SETTLE_MS
    } else {
        REFERENCE_ZOOM_CANCEL_MIN_SETTLE_MS
    }
    return VirtualListZoomSettleSpec(
        kind = VirtualListZoomSettleKind.AccelerateDecelerate,
        durationMs = max(minDuration, (travel * REFERENCE_ZOOM_SETTLE_MS).roundToInt()),
    )
}

/**
 * ZoomGesture.B()/K() edge law. Logical progress remains 0..1 for commit decisions, while the
 * attached source/target holder geometry may move slightly beyond an endpoint under resistance.
 * Positive/target overrun is normalized by 3; negative/source overrun by 0.9, exactly as scaleGestureOwner.B().
 */
internal fun virtualListZoomVisualProgress(logicalProgress: Float): Float = when {
    logicalProgress > 1f -> {
        val normalized = (min(3f, logicalProgress - 1f) / 3f).coerceIn(0f, 1f)
        1f + referenceZoomResistance(normalized)
    }
    logicalProgress < 0f -> {
        val normalized = (min(0.9f, -logicalProgress) / 0.9f).coerceIn(0f, 1f)
        -referenceZoomResistance(normalized)
    }
    else -> logicalProgress
}

/** edgeResistance(value, 0.2, 0.2) derived for the VirtualList zoom edge. */
private fun referenceZoomResistance(normalized: Float): Float {
    val value = normalized.coerceIn(0f, 1f)
    return sqrt(value * 0.2f) * 0.2f
}

/**
 * scaleGestureOwner writes its resisted value to attached holders. Raw keeps source->target geometry clamped to
 * the legal 0..1 transition and maps only endpoint overrun to a holder-local scale. The historical
 * function name is kept for call-site/test compatibility; its result must never scale the whole page.
 */
internal fun virtualListZoomEndpointGroupScale(
    logicalProgress: Float,
    visualProgress: Float = virtualListZoomVisualProgress(logicalProgress),
    transitionZoomIn: Boolean = true,
): Float {
    val endpointProgress = logicalProgress.coerceIn(0f, 1f)
    val resistedOverrun = visualProgress - endpointProgress
    // visualProgress is expressed in transition coordinates. Convert the endpoint overrun back to
    // physical pinch coordinates before turning it into holder scale: a source-side overrun of a
    // zoom-out transition is produced by an expanding pinch and must therefore enlarge (> 1), not
    // shrink. Likewise a target-side overrun of the same transition must shrink (< 1).
    val physicalOverrun = if (transitionZoomIn) resistedOverrun else -resistedOverrun
    return (1f + physicalOverrun).coerceIn(0.9105f, 1.0895f)
}

internal fun virtualListZoomNeedsEndpointRebound(visualProgress: Float): Boolean =
    visualProgress < 0f || visualProgress > 1f

internal fun resolveInterruptedVirtualListZoomCommit(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) > REFERENCE_ZOOM_COMMIT_PROGRESS
