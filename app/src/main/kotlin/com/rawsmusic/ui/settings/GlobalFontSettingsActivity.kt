package com.rawsmusic.ui.settings

import android.os.Bundle

class GlobalFontSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlobalFontSettingsScreen(
                onBack = { finish() },
                onApply = {}
            )
        }
    }
}
