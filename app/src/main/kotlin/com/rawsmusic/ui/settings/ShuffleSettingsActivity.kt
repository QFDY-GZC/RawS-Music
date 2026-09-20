package com.rawsmusic.ui.settings

import android.os.Bundle

class ShuffleSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShuffleSettingsScreen(onBack = { finish() }) }
    }
}
