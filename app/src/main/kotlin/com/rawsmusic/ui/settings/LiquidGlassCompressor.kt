package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.CompressorController

/**
 * Standalone compatibility route. The actual controls live in
 * [CompressorSettingsContent] so the route and the audio-effects workspace use
 * exactly the same MIUIX UI and controller behavior.
 */
@Composable
fun LiquidGlassCompressorScreen(
    compressorController: CompressorController,
    onBack: () -> Unit
) {
    SettingsPage(
        title = stringResource(R.string.settings_compressor_title),
        onBack = onBack
    ) {
        CompressorSettingsContent(
            controller = compressorController,
            showSectionHeader = false
        )
    }
}
