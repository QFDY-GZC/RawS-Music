package com.rawsmusic.ui.settings

import android.os.Bundle

class CompressorActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = try {
            playerController?.ensureCompressorConnected()
            playerController?.compressorController
        } catch (_: Exception) { null }
        setContent {
            if (controller != null) {
                LiquidGlassCompressorScreen(
                    compressorController = controller,
                    onBack = { finish() }
                )
            }
        }
    }
}
