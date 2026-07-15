package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.ui.songs.PlayerHolder
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference

private const val DEFAULT_STEREO_WIDEN_STRENGTH = 250

@Composable
fun LiquidGlassSpatialSoundScreen(
    onBack: () -> Unit
) {
    SettingsPage(title = stringResource(R.string.settings_spatial_title), onBack = onBack) {
        SpatialSoundSettingsContent()
    }
}

/**
 * Shared spatial controls for the standalone page and unified audio-effects
 * workspace. Stereo expansion is continuously adjustable from 0% to 100%.
 */
@Composable
internal fun SpatialSoundSettingsContent() {
    val initialStereoStrength = remember {
        AppPreferences.Equalizer.virtualizer.coerceIn(0, 1000)
    }
    var spatialEnabled by remember { mutableStateOf(initialStereoStrength > 0) }
    var strength by remember { mutableStateOf(initialStereoStrength.toFloat()) }
    var savedStrength by remember {
        mutableStateOf(initialStereoStrength.takeIf { it > 0 } ?: DEFAULT_STEREO_WIDEN_STRENGTH)
    }

    var crossfeedEnabled by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedEnabled) }
    var cfLowCut by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedLowCut.toFloat()) }
    var cfHighCut by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedHighCut.toFloat()) }
    var cfAttenuation by remember { mutableStateOf(AppPreferences.Equalizer.crossfeedAttenuation / 10f) }

    fun applyStereoStrength(value: Int) {
        val coerced = value.coerceIn(0, 1000)
        strength = coerced.toFloat()
        spatialEnabled = coerced > 0
        if (coerced > 0) savedStrength = coerced
        PlayerHolder.controller?.setStereoWidenFactor(coerced / 1000f)
            ?: run { AppPreferences.Equalizer.virtualizer = coerced }
    }

    fun applyCrossfeedParams() {
        PlayerHolder.controller?.setCrossfeedParams(cfLowCut, cfHighCut, cfAttenuation)
            ?: run {
                AppPreferences.Equalizer.crossfeedLowCut = cfLowCut.roundToInt()
                AppPreferences.Equalizer.crossfeedHighCut = cfHighCut.roundToInt()
                AppPreferences.Equalizer.crossfeedAttenuation = (cfAttenuation * 10f).roundToInt()
            }
    }

    SectionHeader(stringResource(R.string.settings_spatial_expand_section))
    SettingsCard {
        SwitchRow(
            label = stringResource(R.string.settings_spatial_expand_enable),
            checked = spatialEnabled,
            onCheckedChange = { checked ->
                if (checked) {
                    applyStereoStrength(savedStrength.coerceIn(1, 1000))
                } else {
                    savedStrength = strength.toInt().takeIf { it > 0 } ?: savedStrength
                    applyStereoStrength(0)
                }
            }
        )
        AnimatedVisibility(
            visible = spatialEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                SliderPreference(
                    title = stringResource(R.string.settings_spatial_strength_title),
                    summary = stringResource(R.string.settings_spatial_strength_summary),
                    valueText = stringResource(
                        R.string.settings_percent_value_one_decimal,
                        strength / 10f
                    ),
                    value = strength,
                    onValueChange = { value ->
                        val next = value.coerceIn(0f, 1000f)
                        strength = next
                        spatialEnabled = next > 0f
                        if (next > 0f) savedStrength = next.roundToInt().coerceIn(1, 1000)
                        PlayerHolder.controller?.setStereoWidenFactor(next / 1000f)
                            ?: run {
                                AppPreferences.Equalizer.virtualizer =
                                    next.roundToInt().coerceIn(0, 1000)
                            }
                    },
                    onValueChangeFinished = {
                        applyStereoStrength(strength.roundToInt().coerceIn(0, 1000))
                    },
                    valueRange = 0f..1000f,
                    showKeyPoints = false
                )
            }
        }
    }

    SectionHeader(stringResource(R.string.settings_spatial_crossfeed_section))
    SettingsCard {
        SwitchRow(
            label = stringResource(R.string.settings_spatial_crossfeed_enable),
            checked = crossfeedEnabled,
            onCheckedChange = { checked ->
                crossfeedEnabled = checked
                PlayerHolder.controller?.setCrossfeedEnabled(checked)
                    ?: run { AppPreferences.Equalizer.crossfeedEnabled = checked }
            }
        )
        AnimatedVisibility(
            visible = crossfeedEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                SliderPreference(
                    title = stringResource(R.string.settings_spatial_crossfeed_low_cut_title),
                    summary = stringResource(R.string.settings_spatial_crossfeed_low_cut_summary),
                    valueText = stringResource(R.string.settings_hz_value, cfLowCut.roundToInt()),
                    value = cfLowCut,
                    onValueChange = { value ->
                        cfLowCut = value.roundToInt().coerceIn(50, 1000).toFloat()
                        applyCrossfeedParams()
                    },
                    valueRange = 50f..1000f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SliderPreference(
                    title = stringResource(R.string.settings_spatial_crossfeed_high_cut_title),
                    summary = stringResource(R.string.settings_spatial_crossfeed_high_cut_summary),
                    valueText = stringResource(R.string.settings_hz_value, cfHighCut.roundToInt()),
                    value = cfHighCut,
                    onValueChange = { value ->
                        cfHighCut = value.roundToInt().coerceIn(500, 8000).toFloat()
                        applyCrossfeedParams()
                    },
                    valueRange = 500f..8000f,
                    steps = 15,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SliderPreference(
                    title = stringResource(R.string.settings_spatial_crossfeed_attenuation_title),
                    summary = stringResource(R.string.settings_spatial_crossfeed_attenuation_summary),
                    valueText = stringResource(R.string.settings_db_value_one_decimal, cfAttenuation),
                    value = cfAttenuation,
                    onValueChange = { value ->
                        cfAttenuation = (value * 10f).roundToInt().coerceIn(0, 150) / 10f
                        applyCrossfeedParams()
                    },
                    valueRange = 0f..15f,
                    steps = 29,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
            }
        }
    }
}
