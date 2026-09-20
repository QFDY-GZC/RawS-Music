package com.rawsmusic.core.ui.scene

import android.view.Choreographer
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * One scroll/minimize owner for the complete bottom chrome.
 *
 * The old implementation stored separate 96px navigation and 64px MiniPlayer travel.  That made
 * the two siblings drift apart and also meant pages had to know which visual style was selected.
 * This state now owns only the normalized UIKit-like tab/accessory environment transition; the
 * geometry renderer maps the same progress to every surface.
 */
@Stable
class BottomChromeScrollState {
    var hidden by mutableStateOf(false)
        private set

    /** 0 = expanded tab/accessory environment, 1 = minimized environment. */
    var visibilityProgress by mutableFloatStateOf(0f)
        private set

    /** Last stable endpoint. Transient values never replace this latch. */
    var stableMode by mutableStateOf(BottomChromeStableMode.Expanded)
        private set

    var scrollInteractionActive by mutableStateOf(false)
        private set

    var topInRootPx by mutableFloatStateOf(0f)
        private set

    /** Resting chrome top used by followers. It is never retargeted from a moving child. */
    var restingTopInRootPx by mutableFloatStateOf(0f)
        private set

    private var interactionLocked: Boolean = false
    private var scrollMorphEnabled: Boolean = true
    private var lockedVisibilityProgress: Float = 0f
    private var gestureRangePx: Float = 72f
    private var visualTravelPx: Float = 28f
    private var pendingVisibilityProgress: Float = Float.NaN
    private var progressFramePosted: Boolean = false
    private val progressFrameCallback = Choreographer.FrameCallback {
        progressFramePosted = false
        val pending = pendingVisibilityProgress
        pendingVisibilityProgress = Float.NaN
        if (!pending.isNaN() && !interactionLocked && scrollMorphEnabled) {
            visibilityProgress = pending.coerceIn(0f, 1f)
        }
    }

    /** Value consumed by placement/drawing nodes without rebuilding the navigation tree. */
    val renderVisibilityProgress: Float
        get() = if (interactionLocked) lockedVisibilityProgress else visibilityProgress

    /** Single visual travel shared by floating actions and other chrome followers. */
    val renderChromeOffsetPx: Float
        get() = (renderVisibilityProgress * visualTravelPx).coerceIn(0f, visualTravelPx)

    val renderFloatingActionOffsetPx: Float
        get() = renderChromeOffsetPx

    val renderChromeTopInRootPx: Float
        get() {
            val restingTop = restingTopInRootPx.takeIf { it > 0f } ?: topInRootPx
            return restingTop + renderChromeOffsetPx
        }

    fun setInteractionLocked(locked: Boolean) {
        if (locked && !interactionLocked) {
            // Capture the latest input sample, including a delta coalesced for the next vsync.
            // Otherwise opening PLAYER in the middle of a fast scroll can freeze the chrome one
            // input event behind the physical list.
            flushPendingProgress()
            lockedVisibilityProgress = visibilityProgress
        }
        interactionLocked = locked
    }

    /**
     * DEFAULT mirrors the current glass bottom navigation-style scroll morph. STATIC keeps the bottom chrome
     * at its expanded endpoint regardless of list/page scrolling. Changing this at runtime must
     * collapse any transient/minimized state immediately so pages and the chrome agree on insets.
     */
    fun setScrollMorphEnabled(enabled: Boolean) {
        if (scrollMorphEnabled == enabled) return
        scrollMorphEnabled = enabled
        if (!enabled) {
            clearPendingProgress()
            visibilityProgress = 0f
            stableMode = BottomChromeStableMode.Expanded
            hidden = false
            scrollInteractionActive = false
            lockedVisibilityProgress = 0f
        }
    }

    fun updateTopInRootPx(topInRootPx: Float) {
        this.topInRootPx = topInRootPx.coerceAtLeast(0f)
        if (this.topInRootPx > 0f && restingTopInRootPx <= 0f) {
            restingTopInRootPx = this.topInRootPx
        }
    }

    /** Scroll distance needed to move between the two stable tab-bar environments. */
    fun updateGestureRangePx(rangePx: Float) {
        gestureRangePx = rangePx.coerceAtLeast(1f)
    }

    /** Physical travel of the complete chrome while entering the minimized environment. */
    fun updateVisualTravelPx(travelPx: Float) {
        visualTravelPx = travelPx.coerceAtLeast(0f)
    }

    suspend fun onScrollActivityChanged(active: Boolean) {
        if (interactionLocked || !scrollMorphEnabled) return
        scrollInteractionActive = active
        if (active) return
        flushPendingProgress()
        settleTo(
            if (visibilityProgress >= 0.5f) {
                BottomChromeStableMode.Minimized
            } else {
                BottomChromeStableMode.Expanded
            }
        )
    }

    suspend fun settleTo(mode: BottomChromeStableMode) {
        if (interactionLocked) return
        if (!scrollMorphEnabled && mode == BottomChromeStableMode.Minimized) return
        flushPendingProgress()
        scrollInteractionActive = false
        val target = if (mode == BottomChromeStableMode.Minimized) 1f else 0f
        val start = visibilityProgress
        if (abs(target - start) > 0.001f) {
            // One settle clock for accessory, navigation and all followers. A new content scroll
            // cancels the collecting LaunchedEffect and resumes from the exact current value.
            animate(
                initialValue = start,
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 520f),
            ) { value, _ ->
                if (!interactionLocked && !scrollInteractionActive && scrollMorphEnabled) {
                    visibilityProgress = value.coerceIn(0f, 1f)
                }
            }
        }
        if (
            !interactionLocked &&
            !scrollInteractionActive &&
            (scrollMorphEnabled || mode == BottomChromeStableMode.Expanded)
        ) {
            visibilityProgress = target
            stableMode = mode
            hidden = mode == BottomChromeStableMode.Minimized
        }
    }

    fun onContentScroll(deltaY: Float) {
        if (interactionLocked || !scrollMorphEnabled) return
        if (abs(deltaY) <= 0.5f) return
        scrollInteractionActive = true
        // Pointer input can deliver multiple scroll deltas inside one display frame. Writing the
        // Compose snapshot for every delta rebuilt/re-measured the complete MiniPlayer/navigation
        // morph several times before HWUI could draw once. retained-view implementation's bottom chrome is sampled from
        // the display clock; accumulate input here and publish at most one visual state per vsync.
        val base = if (pendingVisibilityProgress.isNaN()) visibilityProgress else pendingVisibilityProgress
        val next = (base + deltaY / gestureRangePx).coerceIn(0f, 1f)
        pendingVisibilityProgress = next
        if (!progressFramePosted) {
            progressFramePosted = true
            Choreographer.getInstance().postFrameCallback(progressFrameCallback)
        }
        // Do not replace stableMode for an in-between value. Keep the remembered
        // lastSpecifiedTabAccessoryEnvironment while UIKit is reporting transient environments.
        if (next <= 0.001f) {
            stableMode = BottomChromeStableMode.Expanded
            hidden = false
        } else if (next >= 0.999f) {
            stableMode = BottomChromeStableMode.Minimized
            hidden = true
        }
    }

    fun reset() {
        clearPendingProgress()
        visibilityProgress = 0f
        stableMode = BottomChromeStableMode.Expanded
        hidden = false
        scrollInteractionActive = false
        lockedVisibilityProgress = 0f
        topInRootPx = 0f
        restingTopInRootPx = 0f
    }

    fun resetGeometryAnchor() {
        topInRootPx = 0f
        restingTopInRootPx = 0f
    }

    private fun flushPendingProgress() {
        val pending = pendingVisibilityProgress
        if (pending.isNaN()) return
        if (progressFramePosted) {
            Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
            progressFramePosted = false
        }
        pendingVisibilityProgress = Float.NaN
        visibilityProgress = pending.coerceIn(0f, 1f)
    }

    private fun clearPendingProgress() {
        if (progressFramePosted) {
            Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
            progressFramePosted = false
        }
        pendingVisibilityProgress = Float.NaN
    }
}

val LocalBottomChromeScrollState = compositionLocalOf<BottomChromeScrollState?> { null }
