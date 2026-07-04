package com.rawsmusic.ui.settings

import android.os.Bundle

class Panoramic360Activity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = try {
            playerController?.ensurePanoramic360Connected()
            playerController?.panoramic360Controller
        } catch (_: Exception) { null }
        setContent {
            if (controller != null) {
                LiquidGlassPanoramic360Screen(
                    controller = controller,
                    onBack = { finish() }
                )
            }
        }
    }
}
