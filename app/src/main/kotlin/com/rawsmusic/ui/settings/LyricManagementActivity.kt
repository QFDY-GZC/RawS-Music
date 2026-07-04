package com.rawsmusic.ui.settings

import android.os.Bundle

class LyricManagementActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassLyricManagementScreen(
                onNavigateToLyricFontSettings = { navigateToSettings(LyricFontSettingsActivity::class.java) },
                onBack = { finish() }
            )
        }
    }
}
