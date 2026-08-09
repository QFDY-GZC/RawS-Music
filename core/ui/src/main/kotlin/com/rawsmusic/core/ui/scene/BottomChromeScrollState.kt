package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared scroll-direction state for the main tab bar and mini player. */
@Stable
class BottomChromeScrollState {
    var hidden by mutableStateOf(false)
        private set

    // While the PLAYER sheet is expanded or transitioning, the underlying MAIN content must
    // not retarget floating MiniPlayer/navigation geometry from incidental scroll deltas.
    // Keep stacked navigation as a stable sibling of the player sheet; its collapsed
    // endpoint does not react to the list behind the sheet while the sheet owns the gesture.
    private var interactionLocked: Boolean = false

    fun setInteractionLocked(locked: Boolean) {
        interactionLocked = locked
    }

    fun onContentScroll(deltaY: Float) {
        if (interactionLocked) return
        when {
            deltaY > 1f -> hidden = true
            deltaY < -1f -> hidden = false
        }
    }

    fun reset() {
        hidden = false
    }
}

val LocalBottomChromeScrollState = compositionLocalOf<BottomChromeScrollState?> { null }
