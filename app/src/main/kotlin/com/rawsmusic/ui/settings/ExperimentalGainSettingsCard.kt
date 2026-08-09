package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.ExperimentalGainController
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ExperimentalGainSettingsCard(controller: ExperimentalGainController?) {
    SettingsCard {
        if (controller == null) {
            SwitchPreference(
                title = stringResource(R.string.settings_experimental_gain_title),
                summary = stringResource(R.string.settings_experimental_gain_unavailable),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
            return@SettingsCard
        }

        val enabled by controller.isEnabled.collectAsState()
        val gainDb by controller.gainDb.collectAsState()

        SwitchPreference(
            title = stringResource(R.string.settings_experimental_gain_title),
            summary = stringResource(R.string.settings_experimental_gain_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        ExpandableEffectContent(enabled = enabled) {
            SliderPreference(
                title = stringResource(R.string.settings_experimental_gain_level),
                summary = stringResource(R.string.settings_experimental_gain_level_summary),
                valueText = stringResource(R.string.settings_db_value_positive_one_decimal, gainDb),
                value = gainDb,
                onValueChange = controller::setGainDb,
                valueRange = 0f..30f,
                steps = 59,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
            Text(
                text = stringResource(R.string.settings_experimental_gain_warning),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
