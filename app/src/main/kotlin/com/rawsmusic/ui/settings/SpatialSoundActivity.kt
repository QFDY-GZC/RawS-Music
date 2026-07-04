package com.rawsmusic.ui.settings

import android.os.Bundle

class SpatialSoundActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassSpatialSoundScreen(onBack = { finish() })
        }
    }
}
