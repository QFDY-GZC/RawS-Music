package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricFontManager

class LyricFontSettingsActivity : BaseSettingsActivity() {

    private val fontPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val result = LyricFontManager.importFont(this, uri)
            if (result != null) {
                AppPreferences.LyricFont.fontName = result.name
                AppPreferences.LyricFont.fontPath = result.path
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiquidGlassLyricFontSettingsScreen(
                onImportFont = { fontPicker.launch(arrayOf("*/*")) },
                onBack = { finish() }
            )
        }
    }
}
