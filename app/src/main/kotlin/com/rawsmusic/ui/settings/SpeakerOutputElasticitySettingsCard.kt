package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.SpeakerOutputEffectController
import com.rawsmusic.module.player.dsp.SpeakerOutputElasticityController
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单卡片扬声器外放设置。
 *
 * 模式选择使用与参量均衡器“段数”相同的 MIUIX WindowDropdownPreference；
 * 各模式参数独立保存，切换模式只改变当前 Native 处理器，不覆盖其他模式参数。
 */
@Composable
internal fun SpeakerOutputElasticitySettingsContent(
    controller: SpeakerOutputElasticityController?,
    showSectionHeader: Boolean = true
) {
    if (showSectionHeader) SectionHeader(stringResource(R.string.settings_speaker_output_title))

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_speaker_output_enable),
                summary = stringResource(R.string.settings_speaker_output_unavailable),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val mode by controller.mode.collectAsState()

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_speaker_output_enable),
            summary = when (mode) {
                SpeakerOutputEffectController.Mode.ELASTICITY -> stringResource(R.string.settings_speaker_output_elasticity_desc)
                SpeakerOutputEffectController.Mode.POWERFUL -> stringResource(R.string.settings_speaker_output_powerful_desc)
                SpeakerOutputEffectController.Mode.WIDE -> stringResource(R.string.settings_speaker_output_wide_desc)
            },
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        ExpandableEffectContent(enabled = enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                SpeakerOutputModePreference(mode = mode, onModeSelected = controller::setMode)

                when (mode) {
                    SpeakerOutputEffectController.Mode.ELASTICITY -> ElasticityParameters(controller)
                    SpeakerOutputEffectController.Mode.POWERFUL -> PowerfulParameters(controller)
                    SpeakerOutputEffectController.Mode.WIDE -> WideParameters(controller)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = stringResource(R.string.settings_speaker_output_reset_current_mode),
                        onClick = controller::resetCurrentModeToDefaults
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerOutputModePreference(
    mode: SpeakerOutputEffectController.Mode,
    onModeSelected: (SpeakerOutputEffectController.Mode) -> Unit
) {
    val elasticityTitle = stringResource(R.string.settings_speaker_output_mode_elasticity)
    val elasticitySummary = stringResource(R.string.settings_speaker_output_mode_elasticity_summary)
    val powerfulTitle = stringResource(R.string.settings_speaker_output_mode_powerful)
    val powerfulSummary = stringResource(R.string.settings_speaker_output_mode_powerful_summary)
    val wideTitle = stringResource(R.string.settings_speaker_output_mode_wide)
    val wideSummary = stringResource(R.string.settings_speaker_output_mode_wide_summary)
    val entry = remember(
        mode,
        elasticityTitle,
        elasticitySummary,
        powerfulTitle,
        powerfulSummary,
        wideTitle,
        wideSummary
    ) {
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = elasticityTitle,
                    summary = elasticitySummary,
                    selected = mode == SpeakerOutputEffectController.Mode.ELASTICITY,
                    onClick = { onModeSelected(SpeakerOutputEffectController.Mode.ELASTICITY) }
                ),
                DropdownItem(
                    text = powerfulTitle,
                    summary = powerfulSummary,
                    selected = mode == SpeakerOutputEffectController.Mode.POWERFUL,
                    onClick = { onModeSelected(SpeakerOutputEffectController.Mode.POWERFUL) }
                ),
                DropdownItem(
                    text = wideTitle,
                    summary = wideSummary,
                    selected = mode == SpeakerOutputEffectController.Mode.WIDE,
                    onClick = { onModeSelected(SpeakerOutputEffectController.Mode.WIDE) }
                )
            )
        )
    }
    WindowDropdownPreference(
        entry = entry,
        title = stringResource(R.string.settings_speaker_output_mode),
        summary = when (mode) {
            SpeakerOutputEffectController.Mode.ELASTICITY -> elasticitySummary
            SpeakerOutputEffectController.Mode.POWERFUL -> powerfulSummary
            SpeakerOutputEffectController.Mode.WIDE -> wideSummary
        },
        enabled = true,
        showValue = true,
        maxHeight = 420.dp,
        collapseOnSelection = true
    )
}

@Composable
private fun ElasticityParameters(controller: SpeakerOutputElasticityController) {
    val strength by controller.strengthPercent.collectAsState()
    val detectorLowHz by controller.detectorLowHz.collectAsState()
    val detectorHighHz by controller.detectorHighHz.collectAsState()
    val sensitivity by controller.sensitivityPercent.collectAsState()
    val gateDb by controller.gateThresholdDb.collectAsState()
    val fastAttackMs by controller.fastAttackMs.collectAsState()
    val fastReleaseMs by controller.fastReleaseMs.collectAsState()
    val slowAttackMs by controller.slowAttackMs.collectAsState()
    val slowReleaseMs by controller.slowReleaseMs.collectAsState()
    val gainAttackMs by controller.gainAttackMs.collectAsState()
    val gainReleaseMs by controller.gainReleaseMs.collectAsState()
    val maxBoostDb by controller.maxBoostDb.collectAsState()
    val peakCeilingDb by controller.peakCeilingDb.collectAsState()

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_core_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_strength),
        summary = stringResource(R.string.settings_speaker_output_strength_desc),
        valueText = stringResource(R.string.settings_percent_value, strength.toInt()),
        value = strength.coerceIn(0f, 100f),
        onValueChange = controller::setStrengthPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_max_boost),
        summary = stringResource(R.string.settings_speaker_output_max_boost_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, maxBoostDb),
        value = maxBoostDb.coerceIn(0f, 6f),
        onValueChange = controller::setMaxBoostDb,
        valueRange = 0f..6f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_gain_release),
        summary = stringResource(R.string.settings_speaker_output_gain_release_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, gainReleaseMs),
        value = gainReleaseMs.coerceIn(10f, 250f),
        onValueChange = controller::setGainReleaseMs,
        valueRange = 10f..250f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_detector_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_detector_low),
        summary = stringResource(R.string.settings_speaker_output_detector_low_desc),
        valueText = stringResource(R.string.settings_hz_value, detectorLowHz.toInt()),
        value = detectorLowHz.coerceIn(50f, 250f),
        onValueChange = controller::setDetectorLowHz,
        valueRange = 50f..250f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_detector_high),
        summary = stringResource(R.string.settings_speaker_output_detector_high_desc),
        valueText = stringResource(R.string.settings_hz_value, detectorHighHz.toInt()),
        value = detectorHighHz.coerceIn(400f, 2500f),
        onValueChange = controller::setDetectorHighHz,
        valueRange = 400f..2500f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_sensitivity),
        summary = stringResource(R.string.settings_speaker_output_sensitivity_desc),
        valueText = stringResource(R.string.settings_percent_value, sensitivity.toInt()),
        value = sensitivity.coerceIn(0f, 100f),
        onValueChange = controller::setSensitivityPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_gate),
        summary = stringResource(R.string.settings_speaker_output_gate_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, gateDb),
        value = gateDb.coerceIn(-72f, -24f),
        onValueChange = controller::setGateThresholdDb,
        valueRange = -72f..-24f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_envelope_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_fast_attack),
        summary = stringResource(R.string.settings_speaker_output_fast_attack_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, fastAttackMs),
        value = fastAttackMs.coerceIn(0.2f, 5f),
        onValueChange = controller::setFastAttackMs,
        valueRange = 0.2f..5f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_fast_release),
        summary = stringResource(R.string.settings_speaker_output_fast_release_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, fastReleaseMs),
        value = fastReleaseMs.coerceIn(8f, 100f),
        onValueChange = controller::setFastReleaseMs,
        valueRange = 8f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_slow_attack),
        summary = stringResource(R.string.settings_speaker_output_slow_attack_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, slowAttackMs),
        value = slowAttackMs.coerceIn(4f, 80f),
        onValueChange = controller::setSlowAttackMs,
        valueRange = 4f..80f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_slow_release),
        summary = stringResource(R.string.settings_speaker_output_slow_release_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, slowReleaseMs),
        value = slowReleaseMs.coerceIn(40f, 500f),
        onValueChange = controller::setSlowReleaseMs,
        valueRange = 40f..500f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_gain_attack),
        summary = stringResource(R.string.settings_speaker_output_gain_attack_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, gainAttackMs),
        value = gainAttackMs.coerceIn(0.2f, 10f),
        onValueChange = controller::setGainAttackMs,
        valueRange = 0.2f..10f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_protection_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_peak_ceiling),
        summary = stringResource(R.string.settings_speaker_output_peak_ceiling_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, peakCeilingDb),
        value = peakCeilingDb.coerceIn(-6f, -0.1f),
        onValueChange = controller::setPeakCeilingDb,
        valueRange = -6f..-0.1f
    )
}

@Composable
private fun PowerfulParameters(controller: SpeakerOutputElasticityController) {
    val strength by controller.powerfulStrengthPercent.collectAsState()
    val bodyLow by controller.powerfulBodyLowHz.collectAsState()
    val bodyHigh by controller.powerfulBodyHighHz.collectAsState()
    val bassBoost by controller.powerfulBassBoostDb.collectAsState()
    val harmonic by controller.powerfulHarmonicPercent.collectAsState()
    val threshold by controller.powerfulCompressorThresholdDb.collectAsState()
    val ratio by controller.powerfulCompressorRatio.collectAsState()
    val attack by controller.powerfulCompressorAttackMs.collectAsState()
    val release by controller.powerfulCompressorReleaseMs.collectAsState()
    val mix by controller.powerfulParallelMixPercent.collectAsState()
    val makeup by controller.powerfulMakeupGainDb.collectAsState()
    val presence by controller.powerfulPresenceBoostDb.collectAsState()
    val ceiling by controller.powerfulPeakCeilingDb.collectAsState()

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_powerful_energy_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_strength),
        summary = stringResource(R.string.settings_speaker_output_powerful_strength_desc),
        valueText = stringResource(R.string.settings_percent_value, strength.toInt()),
        value = strength.coerceIn(0f, 100f),
        onValueChange = controller::setPowerfulStrengthPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_bass_boost),
        summary = stringResource(R.string.settings_speaker_output_powerful_bass_boost_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, bassBoost),
        value = bassBoost.coerceIn(0f, 6f),
        onValueChange = controller::setPowerfulBassBoostDb,
        valueRange = 0f..6f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_harmonic),
        summary = stringResource(R.string.settings_speaker_output_powerful_harmonic_desc),
        valueText = stringResource(R.string.settings_percent_value, harmonic.toInt()),
        value = harmonic.coerceIn(0f, 100f),
        onValueChange = controller::setPowerfulHarmonicPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_presence),
        summary = stringResource(R.string.settings_speaker_output_powerful_presence_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, presence),
        value = presence.coerceIn(0f, 4f),
        onValueChange = controller::setPowerfulPresenceBoostDb,
        valueRange = 0f..4f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_powerful_body_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_body_low),
        summary = stringResource(R.string.settings_speaker_output_powerful_body_low_desc),
        valueText = stringResource(R.string.settings_hz_value, bodyLow.toInt()),
        value = bodyLow.coerceIn(40f, 140f),
        onValueChange = controller::setPowerfulBodyLowHz,
        valueRange = 40f..140f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_body_high),
        summary = stringResource(R.string.settings_speaker_output_powerful_body_high_desc),
        valueText = stringResource(R.string.settings_hz_value, bodyHigh.toInt()),
        value = bodyHigh.coerceIn(180f, 700f),
        onValueChange = controller::setPowerfulBodyHighHz,
        valueRange = 180f..700f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_powerful_density_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_threshold),
        summary = stringResource(R.string.settings_speaker_output_powerful_threshold_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, threshold),
        value = threshold.coerceIn(-36f, -6f),
        onValueChange = controller::setPowerfulCompressorThresholdDb,
        valueRange = -36f..-6f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_ratio),
        summary = stringResource(R.string.settings_speaker_output_powerful_ratio_desc),
        valueText = stringResource(R.string.settings_ratio_value_one_decimal, ratio),
        value = ratio.coerceIn(1f, 8f),
        onValueChange = controller::setPowerfulCompressorRatio,
        valueRange = 1f..8f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_attack),
        summary = stringResource(R.string.settings_speaker_output_powerful_attack_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, attack),
        value = attack.coerceIn(2f, 80f),
        onValueChange = controller::setPowerfulCompressorAttackMs,
        valueRange = 2f..80f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_release),
        summary = stringResource(R.string.settings_speaker_output_powerful_release_desc),
        valueText = stringResource(R.string.settings_ms_value_one_decimal, release),
        value = release.coerceIn(40f, 500f),
        onValueChange = controller::setPowerfulCompressorReleaseMs,
        valueRange = 40f..500f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_mix),
        summary = stringResource(R.string.settings_speaker_output_powerful_mix_desc),
        valueText = stringResource(R.string.settings_percent_value, mix.toInt()),
        value = mix.coerceIn(0f, 100f),
        onValueChange = controller::setPowerfulParallelMixPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_powerful_makeup),
        summary = stringResource(R.string.settings_speaker_output_powerful_makeup_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, makeup),
        value = makeup.coerceIn(0f, 6f),
        onValueChange = controller::setPowerfulMakeupGainDb,
        valueRange = 0f..6f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_protection_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_peak_ceiling),
        summary = stringResource(R.string.settings_speaker_output_powerful_ceiling_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, ceiling),
        value = ceiling.coerceIn(-6f, -0.1f),
        onValueChange = controller::setPowerfulPeakCeilingDb,
        valueRange = -6f..-0.1f
    )
}

@Composable
private fun WideParameters(controller: SpeakerOutputElasticityController) {
    val strength by controller.wideStrengthPercent.collectAsState()
    val crossover by controller.wideCrossoverHz.collectAsState()
    val width by controller.wideWidthDb.collectAsState()
    val decorrelation by controller.wideDecorrelationPercent.collectAsState()
    val bassCenter by controller.wideBassCenterPercent.collectAsState()
    val centerProtection by controller.wideCenterProtectionPercent.collectAsState()
    val ceiling by controller.widePeakCeilingDb.collectAsState()

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_wide_space_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_strength),
        summary = stringResource(R.string.settings_speaker_output_wide_strength_desc),
        valueText = stringResource(R.string.settings_percent_value, strength.toInt()),
        value = strength.coerceIn(0f, 100f),
        onValueChange = controller::setWideStrengthPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_width),
        summary = stringResource(R.string.settings_speaker_output_wide_width_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, width),
        value = width.coerceIn(0f, 6f),
        onValueChange = controller::setWideWidthDb,
        valueRange = 0f..6f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_crossover),
        summary = stringResource(R.string.settings_speaker_output_wide_crossover_desc),
        valueText = stringResource(R.string.settings_hz_value, crossover.toInt()),
        value = crossover.coerceIn(300f, 2200f),
        onValueChange = controller::setWideCrossoverHz,
        valueRange = 300f..2200f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_decorrelation),
        summary = stringResource(R.string.settings_speaker_output_wide_decorrelation_desc),
        valueText = stringResource(R.string.settings_percent_value, decorrelation.toInt()),
        value = decorrelation.coerceIn(0f, 60f),
        onValueChange = controller::setWideDecorrelationPercent,
        valueRange = 0f..60f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_wide_stability_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_bass_center),
        summary = stringResource(R.string.settings_speaker_output_wide_bass_center_desc),
        valueText = stringResource(R.string.settings_percent_value, bassCenter.toInt()),
        value = bassCenter.coerceIn(0f, 100f),
        onValueChange = controller::setWideBassCenterPercent,
        valueRange = 0f..100f
    )
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_wide_center_protection),
        summary = stringResource(R.string.settings_speaker_output_wide_center_protection_desc),
        valueText = stringResource(R.string.settings_percent_value, centerProtection.toInt()),
        value = centerProtection.coerceIn(0f, 100f),
        onValueChange = controller::setWideCenterProtectionPercent,
        valueRange = 0f..100f
    )

    SpeakerOutputParameterGroupTitle(stringResource(R.string.settings_speaker_output_protection_group))
    SliderPreference(
        title = stringResource(R.string.settings_speaker_output_peak_ceiling),
        summary = stringResource(R.string.settings_speaker_output_wide_ceiling_desc),
        valueText = stringResource(R.string.settings_db_value_one_decimal, ceiling),
        value = ceiling.coerceIn(-6f, -0.1f),
        onValueChange = controller::setWidePeakCeilingDb,
        valueRange = -6f..-0.1f
    )
}

@Composable
private fun SpeakerOutputParameterGroupTitle(title: String) {
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}
