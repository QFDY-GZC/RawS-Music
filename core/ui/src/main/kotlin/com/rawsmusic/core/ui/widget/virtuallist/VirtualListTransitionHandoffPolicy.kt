package com.rawsmusic.core.ui.widget.virtuallist

/**
 * The settled viewport clips ordinary scrolling, but a retained transition holder must be allowed to
 * transform beyond the unscaled viewport. The real scene/window boundary remains the final clip owner.
 */
internal fun shouldClipVirtualListViewport(
    transitionOverlayVisible: Boolean,
    externalSceneFrozen: Boolean,
): Boolean {
    // HOME/category now moves one provider-population RenderNode. Keep the already-rendered viewport
    // clipped before that affine is applied so the physical recycling row never needs to be destroyed
    // at transition capture. Internal list/grid zoom still owns an overlay and intentionally releases
    // this intermediate clip. `externalSceneFrozen` remains in the signature for policy call-site
    // stability, but no longer disables the viewport clip by itself.
    @Suppress("UNUSED_VARIABLE") val sceneFrozen = externalSceneFrozen
    return !transitionOverlayVisible
}

/**
 * Internal zoom holders are measured once at the larger source/target envelope.  The live frame can
 * be smaller than that envelope (most visibly LIST_ZOOMED <-> GRID_4), so endpoint elasticity must
 * pivot around the live frame centre rather than the stable layer centre.
 */
internal fun virtualListTransitionElasticOriginFraction(
    liveSizePx: Int,
    stableSizePx: Int,
): Float {
    if (stableSizePx <= 0) return 0.5f
    return (liveSizePx.coerceAtLeast(0) * 0.5f / stableSizePx.toFloat()).coerceIn(0f, 1f)
}

/** Extra local draw allowance required by holder-centred endpoint overpull. */
internal fun virtualListTransitionElasticClipOverflowPx(
    liveSizePx: Int,
    elasticScale: Float,
): Float {
    val overScale = (elasticScale - 1f).coerceAtLeast(0f)
    return liveSizePx.coerceAtLeast(0) * overScale * 0.5f
}

/** One immutable transition session resolves to exactly one canonical endpoint. */
internal fun resolveVirtualListTransitionEndpointScroll(
    committedToTarget: Boolean,
    sourceScrollYPx: Int,
    targetScrollYPx: Int,
): Int = if (committedToTarget) targetScrollYPx else sourceScrollYPx

/**
 * Keep the physical recycling row attached even while a retained scene transition owns drawing.
 * Scene eligibility decides which holders render; destroying the ring at transition start cancels
 * artwork requests and recreates display lists exactly in the first 120 Hz frames.
 */
internal fun virtualListRetainedRowsEachSide(sceneMotionActive: Boolean): Int = 1

/**
 * HOME/category GenericPivot must never clear the last valid presentation before the replacement
 * LayoutRes transaction is published. A persistent library runtime therefore preserves the source
 * presentation during both destination preflight and active outer motion; non-library compatibility
 * owners keep the historical clear behavior.
 */
internal fun shouldPreserveVirtualListPresentationForLibraryHandoff(
    hasPersistentLibraryRuntime: Boolean,
    libraryPreflightActive: Boolean,
    libraryMotionActive: Boolean,
    endpointProviderSwapActive: Boolean = false,
): Boolean = hasPersistentLibraryRuntime &&
    (libraryPreflightActive || libraryMotionActive || endpointProviderSwapActive)
