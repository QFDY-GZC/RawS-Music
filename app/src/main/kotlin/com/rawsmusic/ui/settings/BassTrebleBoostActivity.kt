package com.rawsmusic.ui.settings

import android.os.Bundle

class BassTrebleBoostActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bassController = try {
            playerController?.ensureBassBoostConnected()
            playerController?.bassBoostController
        } catch (_: Exception) { null }
        val trebleController = try {
            playerController?.ensureTrebleBoostConnected()
            playerController?.trebleBoostController
        } catch (_: Exception) { null }
        setContent {
            if (bassController != null && trebleController != null) {
                LiquidGlassBassTrebleBoostScreen(
                    bassBoostController = bassController,
                    trebleBoostController = trebleController,
                    onBack = { finish() }
                )
            }
        }
    }
}
