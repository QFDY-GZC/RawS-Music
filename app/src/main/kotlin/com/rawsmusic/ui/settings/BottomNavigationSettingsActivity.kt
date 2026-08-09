package com.rawsmusic.ui.settings

import android.os.Bundle

class BottomNavigationSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BottomNavigationSettingsScreen(onBack = { finish() })
        }
    }
}
