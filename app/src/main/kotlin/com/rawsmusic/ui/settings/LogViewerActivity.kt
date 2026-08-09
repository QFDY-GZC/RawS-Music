package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.ui.log.LogViewerScreen

class LogViewerActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LogViewerScreen(onBack = { finish() })
        }
    }
}
