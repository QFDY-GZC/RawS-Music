package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val effectExpandEnter = expandVertically() + fadeIn()
private val effectExpandExit = shrinkVertically() + fadeOut()

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
    val thresholdDb by controller.thresholdDB.collectAsState()
    val ratio by controller.ratio.collectAsState()
    val attackMs by controller.attackMs.collectAsState()
    val releaseMs by controller.releaseMs.collectAsState()
    val makeupGainDb by controller.makeupGainDB.collectAsState()
    val kneeWidthDb by controller.kneeWidthDB.collectAsState()
    val detectionMode by controller.detectionMode.collectAsState()
    val currentGr by controller.currentGR.collectAsState()

    // Poll only while this content is composed and the compressor is active.
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
            summary = stringResource(R.string.settings_effects_compressor_desc),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        AnimatedVisibility(
            visible = enabled,
            enter = effectExpandEnter,
            exit = effectExpandExit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                CompressorGainReductionMeter(currentGr = currentGr)

                SliderPreference(
                    title = stringResource(R.string.settings_compressor_threshold),
                    summary = null,
                    valueText = stringResource(R.string.settings_db_value_one_decimal, thresholdDb),
                    value = thresholdDb.coerceIn(-60f, 0f),
                    onValueChange = controller::setThreshold,
                    valueRange = -60f..0f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_compressor_ratio),
                    summary = null,
                    valueText = stringResource(R.string.settings_ratio_value, ratio),
                    value = ratio.coerceIn(1f, 20f),
                    onValueChange = controller::setRatio,
                    valueRange = 1f..20f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_compressor_attack),
                    summary = null,
                    valueText = stringResource(R.string.settings_ms_value_one_decimal, attackMs),
                    value = attackMs.coerceIn(0.1f, 100f),
                    onValueChange = controller::setAttack,
                    valueRange = 0.1f..100f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_compressor_release),
                    summary = null,
                    valueText = stringResource(R.string.settings_ms_value_integer, releaseMs.toInt()),
                    value = releaseMs.coerceIn(10f, 1000f),
                    onValueChange = controller::setRelease,
                    valueRange = 10f..1000f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_compressor_makeup_gain),
                    summary = null,
                    valueText = stringResource(R.string.settings_db_value_one_decimal, makeupGainDb),
                    value = makeupGainDb.coerceIn(0f, 24f),
                    onValueChange = controller::setMakeupGain,
                    valueRange = 0f..24f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_compressor_knee),
                    summary = null,
                    valueText = stringResource(R.string.settings_db_value_one_decimal, kneeWidthDb),
                    value = kneeWidthDb.coerceIn(0f, 30f),
                    onValueChange = controller::setKneeWidth,
                    valueRange = 0f..30f
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_compressor_detection_mode),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    RadioButtonPreference(
                        title = stringResource(R.string.settings_compressor_peak),
                        selected = detectionMode == 0,
                        onClick = { controller.setDetectionMode(0) }
                    )
                    RadioButtonPreference(
                        title = stringResource(R.string.settings_compressor_rms),
                        selected = detectionMode == 1,
                        onClick = { controller.setDetectionMode(1) }
                    )
                }
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
        AnimatedVisibility(
            visible = enabled,
            enter = effectExpandEnter,
            exit = effectExpandExit
        ) {
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
        AnimatedVisibility(
            visible = enabled,
            enter = effectExpandEnter,
            exit = effectExpandExit
        ) {
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
