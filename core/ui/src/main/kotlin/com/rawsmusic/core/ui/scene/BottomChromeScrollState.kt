package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared scroll-direction state for the main tab bar and mini player. */
@Stable
class BottomChromeScrollState {
    var hidden by mutableStateOf(false)
        private set

    /**
     * Continuous list-driven progress: 0 keeps the chrome at its resting position and 1 moves
     * it to the hidden position. Keeping this as a shared value avoids starting an independent
     * animation for the player, navigation and floating actions on every scroll callback.
     */
    var visibilityProgress by mutableFloatStateOf(0f)
        private set

    var topInRootPx by mutableFloatStateOf(0f)
        private set

    /** Resting anchor used by floating actions; it must not follow the moving chrome itself. */
    var restingTopInRootPx by mutableFloatStateOf(0f)
        private set

    // While the PLAYER sheet is expanded or transitioning, the underlying MAIN content must
    // not retarget floating MiniPlayer/navigation geometry from incidental scroll deltas.
    // Keep stacked navigation as a stable sibling of the player sheet; its collapsed
    // endpoint does not react to the list behind the sheet while the sheet owns the gesture.
    private var interactionLocked: Boolean = false
    private var lockedVisibilityProgress: Float = 0f
    private var scrollRangePx: Float = 96f
    private var miniPlayerScrollRangePx: Float = 64f

    /** Value consumed by placement/drawing nodes without rebuilding the navigation tree. */
    val renderVisibilityProgress: Float
        get() = if (interactionLocked) lockedVisibilityProgress else visibilityProgress

    /**
     * Current bottom-chrome top edge in root pixels. Shared chrome consumers use this single
     * anchor instead of adding a second density-dependent offset.
     */
    val renderChromeTopInRootPx: Float
        get() {
            // Read the same normalized progress that drives the chrome offsets. Using the last
            // onGloballyPositioned coordinate here makes the action bubbles lag one layout pass
            // behind the player/navigation bar and then jump when that pass catches up.
            // Keep the first measured top as the anchor and derive every frame from the shared
            // progress instead, so the bubbles follow the bar continuously.
            val restingTop = restingTopInRootPx.takeIf { it > 0f } ?: topInRootPx
            return restingTop + renderVisibilityProgress * scrollRangePx
        }

    /**
     * Root-pixel travel for chrome followers. Consumers read this from an offset/placement
     * lambda so the action bubbles move with the same frame as the chrome without triggering a
     * second measurement pass. It intentionally does not depend on the measured anchor: the
     * anchor is only descriptive geometry, while the normalized progress is the single source
     * of truth for the shared translation.
     */
    val renderChromeOffsetPx: Float
        get() = (renderVisibilityProgress * scrollRangePx).coerceIn(0f, scrollRangePx)

    // The floating action bar follows the mini-player, not the full stacked
    // navigation chrome. Keep this derived from the shared progress so both
    // surfaces move together during the same gesture.
    val renderMiniPlayerOffsetPx: Float
        get() = (renderVisibilityProgress * miniPlayerScrollRangePx)
            .coerceIn(0f, miniPlayerScrollRangePx)

    /** Floating top actions follow the mini-player, not the full navigation stack. */
    val renderFloatingActionOffsetPx: Float
        get() = renderMiniPlayerOffsetPx

    /** Offset for a follower anchored above the selected bottom-bar style. */
    fun renderFollowerOffsetPx(normalStyle: Boolean): Float =
        if (normalStyle) renderChromeOffsetPx else renderMiniPlayerOffsetPx

    fun renderFollowerTravelPx(normalStyle: Boolean): Float =
        if (normalStyle) scrollRangePx else miniPlayerScrollRangePx

    fun setInteractionLocked(locked: Boolean) {
        if (locked && !interactionLocked) {
            lockedVisibilityProgress = visibilityProgress
        }
        interactionLocked = locked
    }

    fun updateTopInRootPx(topInRootPx: Float) {
        this.topInRootPx = topInRootPx.coerceAtLeast(0f)
        if (this.topInRootPx > 0f && restingTopInRootPx <= 0f) {
            restingTopInRootPx = this.topInRootPx
        }
    }

    fun updateScrollRangePx(scrollRangePx: Float) {
        this.scrollRangePx = scrollRangePx.coerceAtLeast(1f)
    }

    fun updateMiniPlayerScrollRangePx(scrollRangePx: Float) {
        miniPlayerScrollRangePx = scrollRangePx.coerceAtLeast(1f)
    }

    fun onContentScroll(deltaY: Float) {
        if (interactionLocked) return
        if (kotlin.math.abs(deltaY) <= 0.5f) return
        visibilityProgress = (
            visibilityProgress + deltaY / scrollRangePx
        ).coerceIn(0f, 1f)
        hidden = visibilityProgress >= 0.5f
    }

    fun reset() {
        visibilityProgress = 0f
        hidden = false
        lockedVisibilityProgress = 0f
        topInRootPx = 0f
        restingTopInRootPx = 0f
    }

    fun resetGeometryAnchor() {
        topInRootPx = 0f
        restingTopInRootPx = 0f
    }
}

val LocalBottomChromeScrollState = compositionLocalOf<BottomChromeScrollState?> { null }
