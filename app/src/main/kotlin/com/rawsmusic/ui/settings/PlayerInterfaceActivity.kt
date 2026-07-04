package com.rawsmusic.ui.settings

import android.os.Bundle

class PlayerInterfaceActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassPlayerInterfaceScreen(onBack = { finish() })
        }
    }
}
