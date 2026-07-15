package com.rawsmusic.ui.settings

import android.os.Bundle

class AudioSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassAudioSettingsScreen(
                onBack = { finish() },
                onNavigateToTransitionSettings = {
                    navigateToSettings(TransitionSettingsActivity::class.java)
                }
            )
        }
    }
}
