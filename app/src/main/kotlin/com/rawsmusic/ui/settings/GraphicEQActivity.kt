package com.rawsmusic.ui.settings

import android.os.Bundle
import android.util.Log
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.GraphicEQController

/**
 * Compatibility route for callers that still open the old GraphicEQActivity.
 * The actual controls are shared with the unified audio-effects workspace.
 */
class GraphicEQActivity : BaseSettingsActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val controller = try {
            playerController?.ensureGraphicEQConnected()
            playerController?.graphicEqController
        } catch (e: Exception) {
            Log.e("GraphicEQActivity", "Failed to get GEQ controller", e)
            null
        }

        setContent {
            SettingsPage(
                title = stringResource(R.string.settings_effects_graphic_eq_title),
                onBack = { finish() }
            ) {
                GraphicEqualizerSettingsContent(
                    controller = controller,
                    showSectionHeader = false
                )
            }
        }
    }
}
