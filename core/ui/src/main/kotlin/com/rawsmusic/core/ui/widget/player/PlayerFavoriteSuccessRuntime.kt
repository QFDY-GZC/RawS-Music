package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class PlayerFavoriteSuccessEvent(val revision: Long, val songKey: String)

/** Main-thread, process-local feedback, not restored playlist membership. */
object PlayerFavoriteSuccessRuntime {
    var latest by mutableStateOf<PlayerFavoriteSuccessEvent?>(null)
        private set
    fun publishAdded(songKey: String) {
        latest = PlayerFavoriteSuccessEvent((latest?.revision ?: 0L) + 1L, songKey)
    }
}
