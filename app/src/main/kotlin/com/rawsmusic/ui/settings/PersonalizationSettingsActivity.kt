package com.rawsmusic.ui.settings

import android.os.Bundle

class PersonalizationSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PersonalizationSettingsScreen(onBack = { finish() })
        }
    }
}
