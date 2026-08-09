package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.Surround360Controller

@Composable
fun LiquidGlassSurround360Screen(
    controller: Surround360Controller,
    onBack: () -> Unit
) {
    SettingsPage(
        title = stringResource(R.string.settings_surround360_title),
        onBack = onBack
    ) {
        Surround360SettingsContent(
            controller = controller,
            showSectionHeader = false
        )
    }
}
