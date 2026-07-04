package com.rawsmusic.ui.settings

import android.os.Bundle

class UsbDacSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassUsbDacSettingsScreen(onBack = { finish() })
        }
    }
}
