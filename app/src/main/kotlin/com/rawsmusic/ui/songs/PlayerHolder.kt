package com.rawsmusic.ui.songs

import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerRuntimeRegistry

object PlayerHolder {
    var controller: PlayerController?
        get() = PlayerRuntimeRegistry.currentControllerOrNull()
        set(value) {
            if (value != null) {
                PlayerRuntimeRegistry.attachController(value, "ui_holder")
            }
        }
}
