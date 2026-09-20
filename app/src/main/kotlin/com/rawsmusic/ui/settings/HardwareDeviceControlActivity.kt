package com.rawsmusic.ui.settings

import android.os.Bundle

class HardwareDeviceControlActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HardwareDeviceControlSettingsScreen(onBack = { finish() })
        }
    }
}
