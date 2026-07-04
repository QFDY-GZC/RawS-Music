package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.core.ui.scene.pages.AboutPage

class AboutActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AboutPage(onBack = { finish() })
        }
    }
}
