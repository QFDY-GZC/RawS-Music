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

    fun onContentScroll(deltaY: Float) {
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
