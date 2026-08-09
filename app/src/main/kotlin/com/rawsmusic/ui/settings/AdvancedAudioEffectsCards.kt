package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.CompressorController
import com.rawsmusic.module.player.dsp.TrebleBoostController
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Reusable MIUIX compressor content shared by the advanced workspace and the
 * legacy standalone route. Enabling the effect expands its parameters;
 * disabling it always collapses the card again.
 */
@Composable
internal fun CompressorSettingsContent(
    controller: CompressorController?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_compressor_title))
    }

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_compressor_enable),
                summary = stringResource(R.string.settings_effects_compressor_desc),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val currentGr by controller.currentGR.collectAsState()

    LaunchedEffect(controller, enabled) {
        while (enabled) {
            controller.updateGR()
            delay(100L)
        }
        controller.updateGR()
    }

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_compressor_enable),
            summary = stringResource(R.string.settings_compressor_auto_mode_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        ExpandableEffectContent(enabled = enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                CompressorGainReductionMeter(currentGr = currentGr)
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_compressor_auto_mode),
                    description = stringResource(R.string.settings_compressor_auto_mode_active)
                )
            }
        }
    }
}

@Composable
private fun CompressorGainReductionMeter(currentGr: Float) {
    val normalized = (currentGr / 30f).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_compressor_gain_reduction),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (normalized > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(normalized)
                        .height(24.dp)
                        .background(MiuixTheme.colorScheme.primary)
                )
            }
            Text(
                text = stringResource(R.string.settings_db_value_one_decimal, currentGr),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Reusable MIUIX bass/treble content. Each effect owns one expandable card;
 * switching it off removes its controls from the layout immediately.
 */
@Composable
internal fun BassTrebleBoostSettingsContent(
    bassController: BassBoostController?,
    trebleController: TrebleBoostController?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_bass_treble_title))
    }

    BassBoostSettingsCard(controller = bassController)
    TrebleBoostSettingsCard(controller = trebleController)
}

@Composable
private fun BassBoostSettingsCard(controller: BassBoostController?) {
    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_bass_enable),
                summary = stringResource(R.string.settings_effects_bass_treble_desc),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val gainDb by controller.gainDB.collectAsState()
    val frequency by controller.frequency.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_bass_enable),
            summary = stringResource(R.string.settings_effects_bass_treble_desc),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled = enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SliderPreference(
                    title = stringResource(R.string.settings_effect_gain),
                    summary = null,
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, gainDb),
                    value = gainDb.coerceIn(-12f, 12f),
                    onValueChange = controller::setGain,
                    valueRange = -12f..12f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_effect_corner_frequency),
                    summary = null,
                    valueText = stringResource(R.string.settings_hz_value, frequency.toInt()),
                    value = frequency.coerceIn(50f, 500f),
                    onValueChange = controller::setFrequency,
                    valueRange = 50f..500f
                )
            }
        }
    }
}

@Composable
private fun TrebleBoostSettingsCard(controller: TrebleBoostController?) {
    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_treble_enable),
                summary = stringResource(R.string.settings_effects_bass_treble_desc),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val gainDb by controller.gainDB.collectAsState()
    val frequency by controller.frequency.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_treble_enable),
            summary = stringResource(R.string.settings_effects_bass_treble_desc),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled = enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SliderPreference(
                    title = stringResource(R.string.settings_effect_gain),
                    summary = null,
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, gainDb),
                    value = gainDb.coerceIn(-12f, 12f),
                    onValueChange = controller::setGain,
                    valueRange = -12f..12f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_effect_corner_frequency),
                    summary = null,
                    valueText = stringResource(R.string.settings_hz_value, frequency.toInt()),
                    value = frequency.coerceIn(2000f, 16000f),
                    onValueChange = controller::setFrequency,
                    valueRange = 2000f..16000f
                )
            }
        }
    }
}
