package com.rawsmusic.module.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PlayerEventBus {
    data class PlayerAction(val action: String, val position: Long = 0L)

    private val _events = MutableSharedFlow<PlayerAction>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerAction> = _events.asSharedFlow()

    fun emit(action: String, position: Long = 0L) {
        _events.tryEmit(PlayerAction(action, position))
    }
}
