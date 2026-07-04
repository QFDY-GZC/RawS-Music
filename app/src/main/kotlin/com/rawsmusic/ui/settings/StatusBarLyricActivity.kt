package com.rawsmusic.ui.settings

import android.os.Bundle

class StatusBarLyricActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassStatusBarLyricScreen(onBack = { finish() })
        }
    }
}
