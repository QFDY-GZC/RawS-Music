package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.module.data.prefs.FontManager

class GlobalFontSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlobalFontSettingsScreen(
                onBack = { finish() },
                onApply = {
                    FontManager.rebuildTypeface(this)
                    FontManager.clearScaledCache()
                    recreate()
                }
            )
        }
    }
}
