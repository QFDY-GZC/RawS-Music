package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Shared across the Activity's separate Compose roots; registration order is not z-order. */
internal object PlayerPlaylistBackRuntime {
    private var owners by mutableStateOf<Set<Any>>(emptySet())
    val mounted: Boolean get() = owners.isNotEmpty()

    fun attach(owner: Any) { owners = owners + owner }
    fun detach(owner: Any) { owners = owners - owner }
}

/** Put inside AnimatedVisibility's content so the lower popup stays blocked through exit. */
@Composable
fun PlayerPlaylistBackOwner() {
    val owner = remember { Any() }
    DisposableEffect(owner) {
        PlayerPlaylistBackRuntime.attach(owner)
        onDispose { PlayerPlaylistBackRuntime.detach(owner) }
    }
}
