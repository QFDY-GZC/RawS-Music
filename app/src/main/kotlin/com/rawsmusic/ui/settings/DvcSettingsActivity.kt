package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.module.player.PlayerService

class DvcSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DvcSettingsScreen(
                onBack = { finish() },
                controller = PlayerService.currentRuntimeController() ?: playerController,
            )
        }
    }
}
