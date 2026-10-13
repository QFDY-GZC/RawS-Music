package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.data.prefs.LyricFontManager

class GlobalFontSettingsActivity : BaseSettingsActivity() {
    private val globalFontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val imported = FontManager.importFont(this, uri) ?: return@Thread
            FontManager.selectFont(this, imported)
            runOnUiThread {
                com.rawsmusic.core.ui.theme.RawThemeRuntimeState.invalidate()
            }
        }.start()
    }

    private val lyricFontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val imported = LyricFontManager.importFont(this, uri) ?: return@Thread
            LyricFontManager.selectFont(imported)
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FontSettingsScreen(
                onBack = { finish() },
                onApplyGlobal = {},
                onImportGlobalFont = {
                    withSystemFileAccess { globalFontPicker.launch(arrayOf("*/*")) }
                },
                onImportLyricFont = {
                    withSystemFileAccess { lyricFontPicker.launch(arrayOf("*/*")) }
                },
                initialSlot = FontSettingsSlot.GLOBAL,
            )
        }
    }
}
