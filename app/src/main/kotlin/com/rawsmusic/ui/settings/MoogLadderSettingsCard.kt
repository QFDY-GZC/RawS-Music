package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.MoogLadderController
import kotlin.math.ln
import kotlin.math.pow
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

@Composable
internal fun MoogLadderSettingsCard(controller: MoogLadderController?) {
    SectionHeader(stringResource(R.string.settings_moog_section))
    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_moog_title),
                summary = stringResource(R.string.settings_moog_summary),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val mode by controller.mode.collectAsState()
    val cutoffHz by controller.cutoffHz.collectAsState()
    val resonance by controller.resonancePercent.collectAsState()
    val driveDb by controller.driveDb.collectAsState()
    val mix by controller.mixPercent.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_moog_title),
            summary = stringResource(R.string.settings_moog_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                MoogModePreference(mode, controller::setMode)
                SliderPreference(
                    title = stringResource(R.string.settings_moog_cutoff),
                    summary = stringResource(R.string.settings_moog_cutoff_summary),
                    valueText = stringResource(R.string.settings_hz_value, cutoffHz.toInt()),
                    value = cutoffToSlider(cutoffHz),
                    onValueChange = { controller.setCutoffHz(sliderToCutoff(it)) },
                    valueRange = 0f..1f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_moog_resonance),
                    summary = stringResource(R.string.settings_moog_resonance_summary),
                    valueText = stringResource(R.string.settings_percent_value, resonance.toInt()),
                    value = resonance,
                    onValueChange = controller::setResonancePercent,
                    valueRange = 0f..100f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_moog_drive),
                    summary = stringResource(R.string.settings_moog_drive_summary),
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, driveDb),
                    value = driveDb,
                    onValueChange = controller::setDriveDb,
                    valueRange = 0f..18f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_moog_mix),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, mix.toInt()),
                    value = mix,
                    onValueChange = controller::setMixPercent,
                    valueRange = 0f..100f
                )
            }
        }
    }
}

@Composable
private fun MoogModePreference(
    mode: MoogLadderController.Mode,
    onModeSelected: (MoogLadderController.Mode) -> Unit
) {
    val labels = listOf(
        stringResource(R.string.settings_moog_mode_lp24),
        stringResource(R.string.settings_moog_mode_lp12),
        stringResource(R.string.settings_moog_mode_hp24),
        stringResource(R.string.settings_moog_mode_bp12),
        stringResource(R.string.settings_moog_mode_notch)
    )
    val summaries = listOf(
        stringResource(R.string.settings_moog_mode_lp24_summary),
        stringResource(R.string.settings_moog_mode_lp12_summary),
        stringResource(R.string.settings_moog_mode_hp24_summary),
        stringResource(R.string.settings_moog_mode_bp12_summary),
        stringResource(R.string.settings_moog_mode_notch_summary)
    )
    val modes = MoogLadderController.Mode.entries
    val entry = remember(mode, labels, summaries) {
        DropdownEntry(
            items = modes.mapIndexed { index, candidate ->
                DropdownItem(
                    text = labels[index],
                    summary = summaries[index],
                    selected = candidate == mode,
                    onClick = { onModeSelected(candidate) }
                )
            }
        )
    }
    val index = mode.ordinal.coerceIn(labels.indices)
    WindowDropdownPreference(
        entry = entry,
        title = stringResource(R.string.settings_moog_variant),
        summary = summaries[index],
        enabled = true,
        showValue = true,
        maxHeight = 520.dp,
        collapseOnSelection = true
    )
}

private fun cutoffToSlider(cutoffHz: Float): Float =
    (ln(cutoffHz.coerceIn(20f, 20000f) / 20f) / ln(1000f)).coerceIn(0f, 1f)

private fun sliderToCutoff(value: Float): Float =
    (20.0 * 1000.0.pow(value.coerceIn(0f, 1f).toDouble())).toFloat()
