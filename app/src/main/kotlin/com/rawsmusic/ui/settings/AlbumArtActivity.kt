package com.rawsmusic.ui.settings

import android.os.Bundle

class AlbumArtActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassAlbumArtSettingsScreen(onBack = { finish() })
        }
    }
}
