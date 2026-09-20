package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Window-level visibility flag for the ARTIST_DETAIL fullscreen artwork layer. */
object ArtistArtworkViewerRuntime {
    var visible by mutableStateOf(false)
        private set

    fun updateVisible(value: Boolean) {
        visible = value
    }
}
