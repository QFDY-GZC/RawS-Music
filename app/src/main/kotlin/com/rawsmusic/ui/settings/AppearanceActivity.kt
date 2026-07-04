package com.rawsmusic.ui.settings

import android.os.Bundle

class AppearanceActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassAppearanceScreen(onBack = { finish() })
        }
    }
}
