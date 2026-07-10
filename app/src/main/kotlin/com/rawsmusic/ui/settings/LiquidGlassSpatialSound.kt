package com.rawsmusic.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.ui.songs.PlayerHolder
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference

private const val DEFAULT_STEREO_WIDEN_STRENGTH = 600
private const val MIN_AUDIBLE_STEREO_WIDEN_STRENGTH = 350

@Composable
fun LiquidGlassSpatialSoundScreen(
    onBack: () -> Unit
) {
    val initialStereoStrength = remember {
        AppPreferences.Equalizer.virtualizer.coerceIn(0, 1000).let { value ->
            if (value in 1 until MIN_AUDIBLE_STEREO_WIDEN_STRENGTH) {
                DEFAULT_STEREO_WIDEN_STRENGTH
            } else {
                value
            }
        }
    }
    var spatialEnabled by remember { mutableStateOf(initialStereoStrength > 0) }
    var strength by remember { mutableStateOf(initialStereoStrength.toFloat()) }
    var savedStrength by remember {
        mutableStateOf(initialStereoStrength.takeIf { it > 0 } ?: DEFAULT_STEREO_WIDEN_STRENGTH)
    }

    LaunchedEffect(initialStereoStrength) {
        if (AppPreferences.Equalizer.virtualizer in 1 until MIN_AUDIBLE_STEREO_WIDEN_STRENGTH) {
            PlayerHolder.controller?.setStereoWidenFactor(initialStereoStrength / 1000f)
                ?: run { AppPreferences.Equalizer.virtualizer = initialStereoStrength }
        }
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
    }

    SettingsPage(title = stringResource(R.string.settings_spatial_title), onBack = onBack) {
        SectionHeader(stringResource(R.string.settings_spatial_expand_section))
        SettingsCard {
            SwitchRow(
                label = stringResource(R.string.settings_spatial_expand_enable),
                checked = spatialEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        val target = savedStrength.coerceAtLeast(DEFAULT_STEREO_WIDEN_STRENGTH)
                        applyStereoStrength(target)
                    } else {
                        savedStrength = strength.toInt().takeIf { it > 0 } ?: savedStrength
                        applyStereoStrength(0)
                    }
                }
            )
            SliderPreference(
                title = stringResource(R.string.settings_spatial_strength_title),
                summary = stringResource(R.string.settings_spatial_strength_summary),
                valueText = stringResource(R.string.settings_percent_value, (strength.toInt() / 10)),
                value = strength,
                onValueChange = { value ->
                    val next = value.roundToInt().coerceIn(0, 1000)
                    strength = next.toFloat()
                    if (next > 0) spatialEnabled = true
                    PlayerHolder.controller?.setStereoWidenFactor(next / 1000f)
                        ?: run { AppPreferences.Equalizer.virtualizer = next }
                    if (next >= MIN_AUDIBLE_STEREO_WIDEN_STRENGTH) savedStrength = next
                },
                onValueChangeFinished = {
                    val next = strength.roundToInt().coerceIn(0, 1000)
                    applyStereoStrength(next)
                },
                valueRange = 0f..1000f,
                steps = 99,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                showKeyPoints = true,
                keyPoints = listOf(0f, 350f, 600f, 800f, 1000f)
            )
        }

        SectionHeader(stringResource(R.string.settings_spatial_crossfeed_section))
        SettingsCard {
            SwitchRow(
                label = stringResource(R.string.settings_spatial_crossfeed_enable),
                checked = crossfeedEnabled,
                onCheckedChange = { checked ->
                    crossfeedEnabled = checked
                    PlayerHolder.controller?.setCrossfeedEnabled(checked)
                }
            )
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
                enabled = crossfeedEnabled,
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
                enabled = crossfeedEnabled,
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
                enabled = crossfeedEnabled,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }
    }
}
