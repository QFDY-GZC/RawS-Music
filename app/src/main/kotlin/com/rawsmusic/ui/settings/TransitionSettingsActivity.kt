package com.rawsmusic.ui.settings

import android.os.Bundle

class TransitionSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TransitionSettingsScreen(onBack = { finish() })
        }
    }
}
