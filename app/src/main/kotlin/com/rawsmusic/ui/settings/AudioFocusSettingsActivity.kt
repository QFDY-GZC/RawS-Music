package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.module.player.PlayerService

class AudioFocusSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioFocusSettingsScreen(
                onBack = { finish() },
                onSettingsChanged = {
                    (PlayerService.currentRuntimeController() ?: playerController)
                        ?.refreshAudioFocusSettings()
                }
            )
        }
    }
}
