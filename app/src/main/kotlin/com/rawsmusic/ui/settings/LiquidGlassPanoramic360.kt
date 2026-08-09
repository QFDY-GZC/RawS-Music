package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.Panoramic360Controller

@Composable
fun LiquidGlassPanoramic360Screen(
    controller: Panoramic360Controller,
    onBack: () -> Unit
) {
    SettingsPage(
        title = stringResource(R.string.settings_panoramic360_title),
        onBack = onBack
    ) {
        Panoramic360SettingsContent(
            controller = controller,
            showSectionHeader = false
        )
    }
}
