package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AndroidSpatialAudio
import com.rawsmusic.module.player.AndroidSpatialHeadTracker
import com.rawsmusic.ui.songs.PlayerHolder
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
    val context = LocalContext.current
    val initialSystemSpatialSnapshot = remember { AndroidSpatialAudio.snapshot(context) }
    var systemSpatialEnabled by remember {
        mutableStateOf(
            AppPreferences.Player.androidSpatialAudioEnabled &&
                initialSystemSpatialSnapshot.canRequestPlatform
        )
    }
    var systemSpatialSnapshot by remember {
        mutableStateOf(initialSystemSpatialSnapshot)
    }

    DisposableEffect(context) {
        val observer = AndroidSpatialAudio.observe(context) {
            systemSpatialSnapshot = AndroidSpatialAudio.snapshot(context)
        }
        onDispose { observer.close() }
    }

    val systemSpatialSummary = when {
        systemSpatialSnapshot.customRendererRequested ->
            stringResource(R.string.settings_android_spatial_status_custom_active)
        !systemSpatialSnapshot.apiSupported ->
            stringResource(R.string.settings_android_spatial_status_android_12_required)
        !systemSpatialSnapshot.featureSupported ->
            stringResource(R.string.settings_android_spatial_status_not_supported)
        !systemSpatialSnapshot.backendSupportsExplicitBehavior ->
            stringResource(
                R.string.settings_android_spatial_status_backend_unsupported,
                systemSpatialSnapshot.outputMode.name
            )
        !systemSpatialEnabled ->
            stringResource(R.string.settings_android_spatial_status_off)
        !systemSpatialSnapshot.available ->
            stringResource(R.string.settings_android_spatial_status_route_unavailable)
        !systemSpatialSnapshot.platformEnabled ->
            stringResource(R.string.settings_android_spatial_status_system_disabled)
        !systemSpatialSnapshot.stereoCanBeSpatialized ->
            stringResource(R.string.settings_android_spatial_status_stereo_not_supported)
        systemSpatialSnapshot.headTrackerAvailable ->
            stringResource(R.string.settings_android_spatial_status_active_head_tracking)
        systemSpatialSnapshot.effective ->
            stringResource(R.string.settings_android_spatial_status_active)
        else ->
            stringResource(R.string.settings_android_spatial_status_requested)
    }

    SectionHeader(stringResource(R.string.settings_android_spatial_section))
    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_android_spatial_enable),
            summary = systemSpatialSummary,
            checked = systemSpatialEnabled,
            enabled = systemSpatialSnapshot.canRequestPlatform,
            onCheckedChange = { checked ->
                systemSpatialEnabled = checked
                if (checked) {
                    AppPreferences.Player.androidBinauralSpatialEnabled = false
                }
                PlayerHolder.controller?.setAndroidSpatialAudioEnabled(checked)
                    ?: run {
                        AppPreferences.Player.androidSpatialAudioEnabled = checked
                        if (checked) AppPreferences.Player.androidBinauralSpatialEnabled = false
                    }
                systemSpatialSnapshot = AndroidSpatialAudio.snapshot(context)
            }
        )
    }

    var rawSpatialEnabled by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralSpatialEnabled)
    }
    var rawSpatialIntensity by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralSpatialIntensity.toFloat())
    }
    var rawSpatialRoom by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralSpatialRoom.toFloat())
    }
    var rawSpatialBrirEnabled by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralBrirEnabled)
    }
    var rawSpatialSeparation by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralSeparation.toFloat())
    }
    var rawSpatialHeadSize by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralHeadSizeCentimeters.toFloat())
    }
    var rawSpatialPinna by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralPinnaDetail.toFloat())
    }
    var headTrackingCapability by remember(context) {
        mutableStateOf(AndroidSpatialHeadTracker.capability(context))
    }
    var rawSpatialHeadTracking by remember {
        mutableStateOf(AppPreferences.Player.androidBinauralHeadTrackingEnabled)
    }
    DisposableEffect(context) {
        val observer = AndroidSpatialHeadTracker.observeCapability(context) { capability ->
            headTrackingCapability = capability
        }
        onDispose { observer.close() }
    }

    fun applyRawSpatialAdvanced() {
        PlayerHolder.controller?.setAndroidBinauralAdvancedParameters(
            rawSpatialBrirEnabled,
            rawSpatialSeparation,
            rawSpatialHeadSize,
            rawSpatialPinna
        ) ?: run {
            AppPreferences.Player.androidBinauralBrirEnabled = rawSpatialBrirEnabled
            AppPreferences.Player.androidBinauralSeparation = rawSpatialSeparation.roundToInt()
            AppPreferences.Player.androidBinauralHeadSizeCentimeters = rawSpatialHeadSize.roundToInt()
            AppPreferences.Player.androidBinauralPinnaDetail = rawSpatialPinna.roundToInt()
        }
    }

    SectionHeader(stringResource(R.string.settings_raw_spatial_section))
    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_raw_spatial_enable),
            summary = stringResource(
                if (rawSpatialEnabled) {
                    R.string.settings_raw_spatial_status_on
                } else {
                    R.string.settings_raw_spatial_status_off
                }
            ),
            checked = rawSpatialEnabled,
            onCheckedChange = { checked ->
                rawSpatialEnabled = checked
                if (checked) {
                    systemSpatialEnabled = false
                    AppPreferences.Player.androidSpatialAudioEnabled = false
                }
                PlayerHolder.controller?.setAndroidBinauralSpatialEnabled(checked)
                    ?: run {
                        AppPreferences.Player.androidBinauralSpatialEnabled = checked
                    }
                systemSpatialSnapshot = AndroidSpatialAudio.snapshot(context)
            }
        )
        ExpandableEffectContent(enabled = rawSpatialEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                SliderPreference(
                    title = stringResource(R.string.settings_raw_spatial_intensity_title),
                    summary = stringResource(R.string.settings_raw_spatial_intensity_summary),
                    valueText = stringResource(
                        R.string.settings_percent_value_one_decimal,
                        rawSpatialIntensity
                    ),
                    value = rawSpatialIntensity,
                    onValueChange = { value ->
                        rawSpatialIntensity = value.coerceIn(0f, 100f)
                        PlayerHolder.controller?.setAndroidBinauralSpatialParameters(
                            rawSpatialIntensity,
                            rawSpatialRoom
                        ) ?: run {
                            AppPreferences.Player.androidBinauralSpatialIntensity =
                                rawSpatialIntensity.roundToInt()
                        }
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SliderPreference(
                    title = stringResource(R.string.settings_raw_spatial_room_title),
                    summary = stringResource(R.string.settings_raw_spatial_room_summary),
                    valueText = stringResource(
                        R.string.settings_percent_value_one_decimal,
                        rawSpatialRoom
                    ),
                    value = rawSpatialRoom,
                    onValueChange = { value ->
                        rawSpatialRoom = value.coerceIn(0f, 100f)
                        PlayerHolder.controller?.setAndroidBinauralSpatialParameters(
                            rawSpatialIntensity,
                            rawSpatialRoom
                        ) ?: run {
                            AppPreferences.Player.androidBinauralSpatialRoom =
                                rawSpatialRoom.roundToInt()
                        }
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                SwitchPreference(
                    title = stringResource(R.string.settings_raw_spatial_brir_title),
                    summary = stringResource(R.string.settings_raw_spatial_brir_summary),
                    checked = rawSpatialBrirEnabled,
                    onCheckedChange = { checked ->
                        rawSpatialBrirEnabled = checked
                        applyRawSpatialAdvanced()
                    }
                )
                SliderPreference(
                    title = stringResource(R.string.settings_raw_spatial_separation_title),
                    summary = stringResource(R.string.settings_raw_spatial_separation_summary),
                    valueText = stringResource(
                        R.string.settings_percent_value_one_decimal,
                        rawSpatialSeparation
                    ),
                    value = rawSpatialSeparation,
                    onValueChange = { value ->
                        rawSpatialSeparation = value.coerceIn(0f, 100f)
                        applyRawSpatialAdvanced()
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SliderPreference(
                    title = stringResource(R.string.settings_raw_spatial_head_size_title),
                    summary = stringResource(R.string.settings_raw_spatial_head_size_summary),
                    valueText = stringResource(
                        R.string.settings_centimeter_value_one_decimal,
                        rawSpatialHeadSize
                    ),
                    value = rawSpatialHeadSize,
                    onValueChange = { value ->
                        rawSpatialHeadSize = value.coerceIn(48f, 68f)
                        applyRawSpatialAdvanced()
                    },
                    valueRange = 48f..68f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SliderPreference(
                    title = stringResource(R.string.settings_raw_spatial_pinna_title),
                    summary = stringResource(R.string.settings_raw_spatial_pinna_summary),
                    valueText = stringResource(
                        R.string.settings_percent_value_one_decimal,
                        rawSpatialPinna
                    ),
                    value = rawSpatialPinna,
                    onValueChange = { value ->
                        rawSpatialPinna = value.coerceIn(0f, 100f)
                        applyRawSpatialAdvanced()
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_raw_spatial_head_tracking_title),
                    summary = if (headTrackingCapability.available) {
                        stringResource(
                            R.string.settings_raw_spatial_head_tracking_available,
                            headTrackingCapability.sensorName.orEmpty()
                        )
                    } else {
                        stringResource(R.string.settings_raw_spatial_head_tracking_unavailable)
                    },
                    checked = rawSpatialHeadTracking,
                    enabled = headTrackingCapability.available,
                    onCheckedChange = { checked ->
                        val accepted = PlayerHolder.controller
                            ?.setAndroidBinauralHeadTrackingEnabled(checked)
                            ?: (checked && headTrackingCapability.available).also {
                                AppPreferences.Player.androidBinauralHeadTrackingEnabled = it
                            }
                        rawSpatialHeadTracking = accepted
                    }
                )
                if (rawSpatialHeadTracking && headTrackingCapability.available) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            text = stringResource(R.string.settings_raw_spatial_recenter),
                            onClick = {
                                PlayerHolder.controller?.recenterAndroidBinauralHeadTracking()
                            }
                        )
                    }
                }
            }
        }
    }

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
        ExpandableEffectContent(enabled = spatialEnabled) {
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
        ExpandableEffectContent(enabled = crossfeedEnabled) {
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
