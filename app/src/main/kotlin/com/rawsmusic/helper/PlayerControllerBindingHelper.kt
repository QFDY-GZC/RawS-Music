package com.rawsmusic.helper

import com.rawsmusic.module.player.PlayerController

class PlayerControllerBindingHelper(
    private val setPlayerController: (PlayerController) -> Unit
) {
    fun bind(controller: PlayerController) {
        setPlayerController(controller)
    }
}
