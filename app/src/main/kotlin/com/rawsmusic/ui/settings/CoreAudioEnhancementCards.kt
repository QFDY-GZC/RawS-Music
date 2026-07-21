package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.DynamicEqController
import com.rawsmusic.module.player.dsp.LoudnessBalanceController
import com.rawsmusic.module.player.dsp.MonoBassController
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun CoreAudioEnhancementSettingsContent(
    loudnessBalanceController: LoudnessBalanceController?,
    monoBassController: MonoBassController?,
    dynamicEqController: DynamicEqController?
) {
    SectionHeader(stringResource(R.string.settings_core_audio_enhancements))
    LoudnessBalanceCard(loudnessBalanceController)
    MonoBassCard(monoBassController)
    DynamicEqCard(dynamicEqController)
}

@Composable
private fun LoudnessBalanceCard(controller: LoudnessBalanceController?) {
    if (controller == null) {
        DisabledEffectCard(
            R.string.settings_loudness_balance_title,
            R.string.settings_loudness_balance_summary
        )
        return
    }
    val enabled by controller.isEnabled.collectAsState()
    val amount by controller.loudnessPercent.collectAsState()
    val balance by controller.balance.collectAsState()
    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_loudness_balance_title),
            summary = stringResource(R.string.settings_loudness_balance_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SliderPreference(
                    title = stringResource(R.string.settings_loudness_amount),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, amount.toInt()),
                    value = amount,
                    onValueChange = controller::setLoudnessPercent,
                    valueRange = 0f..100f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_channel_balance),
                    summary = null,
                    valueText = when {
                        balance < -0.02f -> stringResource(R.string.settings_balance_left, (-balance * 100).toInt())
                        balance > 0.02f -> stringResource(R.string.settings_balance_right, (balance * 100).toInt())
                        else -> stringResource(R.string.settings_balance_center)
                    },
                    value = balance,
                    onValueChange = controller::setBalance,
                    valueRange = -1f..1f
                )
            }
        }
    }
}

@Composable
private fun MonoBassCard(controller: MonoBassController?) {
    if (controller == null) {
        DisabledEffectCard(R.string.settings_mono_bass_title, R.string.settings_mono_bass_summary)
        return
    }
    val enabled by controller.isEnabled.collectAsState()
    val crossover by controller.crossoverHz.collectAsState()
    val amount by controller.amountPercent.collectAsState()
    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_mono_bass_title),
            summary = stringResource(R.string.settings_mono_bass_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SliderPreference(
                    title = stringResource(R.string.settings_crossover_frequency),
                    summary = null,
                    valueText = stringResource(R.string.settings_hz_value, crossover.toInt()),
                    value = crossover,
                    onValueChange = controller::setCrossoverHz,
                    valueRange = 60f..300f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_mono_amount),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, amount.toInt()),
                    value = amount,
                    onValueChange = controller::setAmountPercent,
                    valueRange = 0f..100f
                )
            }
        }
    }
}

@Composable
private fun DynamicEqCard(controller: DynamicEqController?) {
    if (controller == null) {
        DisabledEffectCard(R.string.settings_dynamic_eq_title, R.string.settings_dynamic_eq_summary)
        return
    }
    val enabled by controller.isEnabled.collectAsState()
    val intensity by controller.intensityPercent.collectAsState()
    val deEsser by controller.deEsserPercent.collectAsState()
    val frequency by controller.deEsserFrequencyHz.collectAsState()
    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_dynamic_eq_title),
            summary = stringResource(R.string.settings_dynamic_eq_summary),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )
        ExpandableEffectContent(enabled) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SliderPreference(
                    title = stringResource(R.string.settings_dynamic_eq_intensity),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, intensity.toInt()),
                    value = intensity,
                    onValueChange = controller::setIntensityPercent,
                    valueRange = 0f..100f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_deesser_amount),
                    summary = null,
                    valueText = stringResource(R.string.settings_percent_value, deEsser.toInt()),
                    value = deEsser,
                    onValueChange = controller::setDeEsserPercent,
                    valueRange = 0f..100f
                )
                SliderPreference(
                    title = stringResource(R.string.settings_deesser_frequency),
                    summary = null,
                    valueText = stringResource(R.string.settings_hz_value, frequency.toInt()),
                    value = frequency,
                    onValueChange = controller::setDeEsserFrequencyHz,
                    valueRange = 4000f..10000f
                )
            }
        }
    }
}

@Composable
private fun DisabledEffectCard(title: Int, summary: Int) {
    SettingsCard {
        SwitchPreference(
            title = stringResource(title),
            summary = stringResource(summary),
            checked = false,
            enabled = false,
            onCheckedChange = {}
        )
    }
}
