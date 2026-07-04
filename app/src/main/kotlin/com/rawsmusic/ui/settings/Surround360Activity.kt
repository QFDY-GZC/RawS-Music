package com.rawsmusic.ui.settings

import android.os.Bundle

class Surround360Activity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = try {
            playerController?.ensureSurround360Connected()
            playerController?.surround360Controller
        } catch (_: Exception) { null }
        setContent {
            if (controller != null) {
                LiquidGlassSurround360Screen(
                    controller = controller,
                    onBack = { finish() }
                )
            }
        }
    }
}
