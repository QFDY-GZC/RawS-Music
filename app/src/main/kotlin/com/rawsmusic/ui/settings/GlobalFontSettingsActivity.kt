package com.rawsmusic.ui.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.module.data.prefs.FontManager

class GlobalFontSettingsActivity : BaseSettingsActivity() {
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val imported = FontManager.importFont(this, uri) ?: return@Thread
            FontManager.selectFont(this, imported)
            runOnUiThread {
                com.rawsmusic.core.ui.theme.RawThemeRuntimeState.invalidate()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlobalFontSettingsScreen(
                onBack = { finish() },
                onApply = {},
                onImportFont = { fontPicker.launch(arrayOf("*/*")) },
            )
        }
    }
}
