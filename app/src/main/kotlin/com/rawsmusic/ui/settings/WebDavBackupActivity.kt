package com.rawsmusic.ui.settings

import android.os.Bundle

class WebDavBackupActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WebDavBackupScreen(onBack = { finish() })
        }
    }
}
