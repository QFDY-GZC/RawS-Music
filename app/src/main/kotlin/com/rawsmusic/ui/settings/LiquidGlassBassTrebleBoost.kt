package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.TrebleBoostController

/**
 * Standalone compatibility route. The actual controls live in
 * [BassTrebleBoostSettingsContent] so this route and the advanced workspace do
 * not maintain two independent copies of the same DSP UI.
 */
@Composable
fun LiquidGlassBassTrebleBoostScreen(
    bassBoostController: BassBoostController,
    trebleBoostController: TrebleBoostController,
    onBack: () -> Unit
) {
    SettingsPage(
        title = stringResource(R.string.settings_bass_treble_title),
        onBack = onBack
    ) {
        BassTrebleBoostSettingsContent(
            bassController = bassBoostController,
            trebleController = trebleBoostController,
            showSectionHeader = false
        )
    }
}
