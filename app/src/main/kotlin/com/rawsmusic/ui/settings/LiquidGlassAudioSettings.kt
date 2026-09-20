package com.rawsmusic.ui.settings

import android.content.Intent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.PcmDitherMode
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService

@Composable
fun LiquidGlassAudioSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var runtimeController by remember {
        mutableStateOf(PlayerService.currentRuntimeController() ?: PlayerController.getInstanceOrNull())
    }
    fun requireRuntimeController(reason: String): PlayerController {
        val current = runtimeController
        if (current != null) return current
        return PlayerService.obtainRuntimeController(
            context,
            "audio_settings:$reason",
            ensureService = true
        ).also { runtimeController = it }
    }
    val usbExclusiveActive by (runtimeController?.usbExclusiveActive
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    var usbExclusiveRequested by remember { mutableStateOf(AppPreferences.Player.usbExclusiveRequested) }

    var outputMode by remember { mutableStateOf(AppPreferences.Player.audioOutputMode) }
    // v6f: 采样率/位深选项根据当前输出引擎过滤
    val sampleRates = remember(outputMode) { AudioOutputManager.getSampleRateOptionsForMode(outputMode) }
    val bitDepths = remember(outputMode) { AudioOutputManager.getBitDepthOptionsForMode(outputMode) }

    var sampleRateIndex by remember(outputMode) {
        mutableStateOf(sampleRates.indexOf(AudioOutputManager.getTargetSampleRate()).coerceAtLeast(0))
    }
    var bitDepthIndex by remember(outputMode) {
        mutableStateOf(bitDepths.indexOf(AudioOutputManager.getTargetBitDepth()).coerceAtLeast(0))
    }
    var normalization by remember { mutableStateOf(AppPreferences.Player.volumeNormalizationEnabled) }
    var gapless by remember { mutableStateOf(AppPreferences.Player.gaplessPlaybackEnabled) }
    var playbackSpeed by remember { mutableStateOf(AppPreferences.Player.playbackSpeed) }
    var playbackSpeedChangesPitch by remember {
        mutableStateOf(AppPreferences.Player.playbackSpeedChangesPitch)
    }
    var trackProgressMemoryEnabled by remember { mutableStateOf(AppPreferences.Player.trackProgressMemoryEnabled) }
    var playCountEnabled by remember { mutableStateOf(AppPreferences.Player.playCountEnabled) }
    var playCountThresholdPercent by remember {
        mutableStateOf(AppPreferences.Player.playCountThresholdPercent.coerceIn(1, 100))
    }
    var infoDialogId by remember { mutableStateOf(0) }
    var ditherMode by remember { mutableStateOf(AppPreferences.Player.pcmDitherMode) }

    val ditherOptions = listOf(
        PcmDitherMode.OFF.id to (stringResource(R.string.settings_audio_dither_none) to stringResource(R.string.settings_audio_dither_none_desc)),
        PcmDitherMode.RPDF.id to (stringResource(R.string.settings_audio_dither_rpdf) to stringResource(R.string.settings_audio_dither_rpdf_desc)),
        PcmDitherMode.TPDF.id to (stringResource(R.string.settings_audio_dither_tpdf) to stringResource(R.string.settings_audio_dither_tpdf_desc)),
        PcmDitherMode.TPDF_HIGH_PASS.id to (stringResource(R.string.settings_audio_dither_tpdf_hp) to stringResource(R.string.settings_audio_dither_tpdf_hp_desc)),
        PcmDitherMode.GAUSSIAN.id to (stringResource(R.string.settings_audio_dither_gaussian) to stringResource(R.string.settings_audio_dither_gaussian_desc)),
        PcmDitherMode.F_WEIGHTED.id to (stringResource(R.string.settings_audio_dither_f_weighted) to stringResource(R.string.settings_audio_dither_f_weighted_desc)),
        PcmDitherMode.MODIFIED_E_WEIGHTED.id to (stringResource(R.string.settings_audio_dither_modified_e) to stringResource(R.string.settings_audio_dither_modified_e_desc)),
        PcmDitherMode.SHIBATA.id to (stringResource(R.string.settings_audio_dither_shibata) to stringResource(R.string.settings_audio_dither_shibata_desc)),
        PcmDitherMode.LOW_SHIBATA.id to (stringResource(R.string.settings_audio_dither_low_shibata) to stringResource(R.string.settings_audio_dither_low_shibata_desc)),
        PcmDitherMode.HIGH_SHIBATA.id to (stringResource(R.string.settings_audio_dither_high_shibata) to stringResource(R.string.settings_audio_dither_high_shibata_desc))
    )
    val selectedDitherName = ditherOptions.firstOrNull { it.first == ditherMode }?.second?.first
        ?: stringResource(R.string.settings_audio_dither_modified_e)

    fun applyAudioOutputSettings() {
        com.rawsmusic.ui.songs.PlayerHolder.controller?.applyAudioOutputSettingsChanged()
    }

    fun selectEngine(mode: AudioOutputMode) {
        val isAvailable = AudioOutputManager.isOutputModeAvailable(mode, context)
        if (!isAvailable) {
            AppNoticeBus.error(
                context.getString(
                    R.string.settings_audio_output_unavailable,
                    AudioOutputManager.getOutputModeLabel(mode)
                )
            )
            return
        }
        outputMode = mode
        AudioOutputManager.setOutputMode(mode)
        // 切换引擎后，如果当前采样率/位深超出新引擎范围，自动钳制
        val maxRate = AudioOutputManager.getMaxSampleRateForMode(mode)
        val curRate = AudioOutputManager.getTargetSampleRate()
        if (curRate > maxRate) {
            AudioOutputManager.setTargetSampleRate(maxRate)
        }
        val allowedBits = AudioOutputManager.getBitDepthOptionsForMode(mode).toList()
        val curBits = AudioOutputManager.getTargetBitDepth()
        if (curBits !in allowedBits) {
            AudioOutputManager.setTargetBitDepth(0)
        }
        applyAudioOutputSettings()
    }

    fun applyPlaybackSpeed(value: Float) {
        val normalized = value.coerceIn(0.25f, 3f)
        playbackSpeed = normalized
        AppPreferences.Player.playbackSpeed = normalized
        com.rawsmusic.ui.songs.PlayerHolder.controller?.setPlaybackSpeed(normalized)
    }

    fun applyPlaybackPitchMode(changePitch: Boolean) {
        playbackSpeedChangesPitch = changePitch
        AppPreferences.Player.playbackSpeedChangesPitch = changePitch
        // At 1.00x both processors are identity, so persisting the preference is enough.
        if (kotlin.math.abs(playbackSpeed - 1f) > 0.0001f) {
            com.rawsmusic.ui.songs.PlayerHolder.controller?.setPlaybackSpeed(playbackSpeed)
        }
    }

    Box(Modifier.fillMaxSize()) {
    SettingsPage(title = stringResource(R.string.settings_audio_quality_title), onBack = onBack) {
        // ==========================
        // v6f: 每个输出引擎一张卡片，图标在左，点击展开输出品质
        // ==========================
        val engines = listOf(
            Triple(AudioOutputMode.OPENSL_ES, R.drawable.ic_audio_opensl_png, stringResource(R.string.settings_audio_engine_opensl_hint)),
            Triple(AudioOutputMode.AAUDIO, R.drawable.ic_audio_aaudio_png, stringResource(R.string.settings_audio_engine_aaudio_hint)),
            Triple(AudioOutputMode.AUDIO_TRACK, R.drawable.ic_audio_track_png, stringResource(R.string.settings_audio_engine_audiotrack_hint)),
            Triple(AudioOutputMode.DIRECT, R.drawable.ic_audio_hires_png, stringResource(R.string.settings_audio_engine_direct_hint))
        )

        UsbExclusiveTransportCard(
            androidOutputLabel = AudioOutputManager.getOutputModeLabel(outputMode),
            active = usbExclusiveActive,
            requested = usbExclusiveRequested,
            onClick = {
                val controller = requireRuntimeController("toggle_usb_exclusive")
                if (usbExclusiveActive || usbExclusiveRequested || AppPreferences.Player.usbExclusiveRequested) {
                    usbExclusiveRequested = false
                    controller.disableUsbExclusive()
                } else {
                    usbExclusiveRequested = true
                    controller.enableUsbExclusive()
                }
            }
        )

        for ((mode, iconRes, rangeHint) in engines) {
            val isSelected = outputMode == mode
            val label = AudioOutputManager.getOutputModeLabel(mode)
            val isAvailable = AudioOutputManager.isOutputModeAvailable(mode, context)

            EngineCard(
                iconRes = iconRes,
                label = label,
                rangeHint = rangeHint,
                isSelected = isSelected,
                isEnabled = isAvailable,
                onClick = { selectEngine(mode) }
            ) {
                // 展开的输出品质区域（采样率 + 位深滑条）
                AnimatedVisibility(
                    visible = isSelected,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(Modifier.padding(top = 12.dp)) {
                        // 采样率
                        SliderPreference(
                            title = stringResource(R.string.settings_audio_sample_rate),
                            summary = null,
                            valueText = AudioOutputManager.SAMPLE_RATE_LABELS[sampleRates[sampleRateIndex]] ?: stringResource(R.string.settings_auto),
                            value = sampleRateIndex.toFloat(),
                            onValueChange = { idx ->
                                sampleRateIndex = idx.toInt()
                                val rate = sampleRates.getOrElse(sampleRateIndex) { 0 }
                                AudioOutputManager.setTargetSampleRate(rate)
                            },
                            onValueChangeFinished = { applyAudioOutputSettings() },
                            valueRange = 0f..(sampleRates.size - 1).toFloat(),
                            steps = (sampleRates.size - 2).coerceAtLeast(0),
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step
                        )

                        Spacer(Modifier.height(8.dp))

                        // 位深
                        SliderPreference(
                            title = stringResource(R.string.settings_audio_bit_depth),
                            summary = null,
                            valueText = AudioOutputManager.BIT_DEPTH_LABELS[bitDepths[bitDepthIndex]] ?: stringResource(R.string.settings_auto),
                            value = bitDepthIndex.toFloat(),
                            onValueChange = { idx ->
                                bitDepthIndex = idx.toInt()
                                val depth = bitDepths.getOrElse(bitDepthIndex) { 0 }
                                AudioOutputManager.setTargetBitDepth(depth)
                            },
                            onValueChangeFinished = { applyAudioOutputSettings() },
                            valueRange = 0f..(bitDepths.size - 1).toFloat(),
                            steps = (bitDepths.size - 2).coerceAtLeast(0),
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_audio_internal_precision_section))
            Spacer(Modifier.height(4.dp))
            var internalDoublePrecision by remember {
                mutableStateOf(AppPreferences.Player.internalDoublePrecisionProcessingEnabled)
            }
            SwitchPreference(
                title = stringResource(R.string.settings_audio_internal_precision_title),
                summary = stringResource(R.string.settings_audio_internal_precision_summary),
                checked = internalDoublePrecision,
                onCheckedChange = { checked ->
                    internalDoublePrecision = checked
                    AppPreferences.Player.internalDoublePrecisionProcessingEnabled = checked
                    com.rawsmusic.ui.songs.PlayerHolder.controller
                        ?.setInternalDoublePrecisionProcessing(checked)
                }
            )
            var monoOutputEnabled by remember {
                mutableStateOf(AppPreferences.MonoOutput.isEnabled)
            }
            SwitchPreference(
                title = stringResource(R.string.settings_audio_mono_output_title),
                summary = stringResource(R.string.settings_audio_mono_output_summary),
                checked = monoOutputEnabled,
                onCheckedChange = { checked ->
                    monoOutputEnabled = checked
                    AppPreferences.MonoOutput.isEnabled = checked
                    com.rawsmusic.ui.songs.PlayerHolder.controller
                        ?.monoOutputController
                        ?.setEnabled(checked)
                }
            )
            DitherSettingsPreference(
                title = stringResource(R.string.settings_audio_dither_title),
                description = stringResource(R.string.settings_audio_dither_summary, selectedDitherName),
                options = ditherOptions,
                selectedId = ditherMode,
                onSelected = { id ->
                    ditherMode = id
                    AppPreferences.Player.pcmDitherMode = id
                    com.rawsmusic.ui.songs.PlayerHolder.controller?.setPcmDitherMode(id)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsActionRow(
                title = stringResource(R.string.settings_audio_focus_title),
                description = stringResource(R.string.settings_audio_focus_summary),
                iconRes = R.drawable.ic_settings_audio_output,
                onClick = {
                    (context as? BaseSettingsActivity)
                        ?.navigateToSettings(AudioFocusSettingsActivity::class.java)
                        ?: context.startActivity(Intent(context, AudioFocusSettingsActivity::class.java))
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_audio_playback_options))
            Spacer(Modifier.height(4.dp))
            InfoSwitchRow(stringResource(R.string.settings_audio_volume_normalization), stringResource(R.string.settings_audio_volume_normalization_desc), normalization, { infoDialogId = 1 }) { checked ->
                normalization = checked
                AppPreferences.Player.volumeNormalizationEnabled = checked
            }
            SettingsActionRow(
                title = stringResource(R.string.settings_audio_dvc),
                description = stringResource(R.string.settings_audio_dvc_desc),
                iconRes = R.drawable.ic_settings_effects,
                onClick = {
                    (context as? BaseSettingsActivity)
                        ?.navigateToSettings(DvcSettingsActivity::class.java)
                        ?: context.startActivity(Intent(context, DvcSettingsActivity::class.java))
                }
            )
            InfoSwitchRow(stringResource(R.string.settings_audio_gapless), stringResource(R.string.settings_audio_gapless_desc), gapless, { infoDialogId = 2 }) { checked ->
                gapless = checked
                AppPreferences.Player.gaplessPlaybackEnabled = checked
            }
            SliderPreference(
                title = stringResource(R.string.settings_audio_speed_title),
                summary = stringResource(R.string.settings_audio_speed_summary),
                valueText = stringResource(R.string.settings_audio_speed_value, playbackSpeed),
                value = playbackSpeed,
                onValueChange = { value ->
                    playbackSpeed = (kotlin.math.round(value / 0.05f) * 0.05f)
                        .coerceIn(0.25f, 3f)
                },
                onValueChangeFinished = {
                    applyPlaybackSpeed(playbackSpeed)
                },
                valueRange = 0.25f..3f,
                steps = 54,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_speed_change_pitch_title),
                summary = stringResource(R.string.settings_audio_speed_change_pitch_summary),
                checked = playbackSpeedChangesPitch,
                onCheckedChange = ::applyPlaybackPitchMode,
            )
            Text(
                text = stringResource(R.string.settings_audio_speed_presets_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
            val speedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                speedPresets.chunked(3).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            TextButton(
                                onClick = { applyPlaybackSpeed(preset) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_audio_speed_value, preset))
                            }
                        }
                    }
                }
            }
            SettingsActionRow(
                title = stringResource(R.string.settings_audio_speed_reset),
                description = stringResource(R.string.settings_audio_speed_reset_summary),
                onClick = {
                    applyPlaybackSpeed(1f)
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_playback_history_section))
            Spacer(Modifier.height(4.dp))

            SwitchPreference(
                title = stringResource(R.string.settings_track_progress_memory_title),
                summary = stringResource(R.string.settings_track_progress_memory_summary),
                checked = trackProgressMemoryEnabled,
                onCheckedChange = { checked ->
                    trackProgressMemoryEnabled = checked
                    AppPreferences.Player.trackProgressMemoryEnabled = checked
                    if (!checked) AppPreferences.Player.lastPosition = 0L
                }
            )

            SwitchPreference(
                title = stringResource(R.string.settings_play_count_title),
                summary = stringResource(R.string.settings_play_count_summary),
                checked = playCountEnabled,
                onCheckedChange = { checked ->
                    playCountEnabled = checked
                    AppPreferences.Player.playCountEnabled = checked
                }
            )

            SliderPreference(
                title = stringResource(R.string.settings_play_count_threshold_title),
                summary = stringResource(R.string.settings_play_count_threshold_summary),
                valueText = stringResource(R.string.settings_percent_value, playCountThresholdPercent),
                value = playCountThresholdPercent.toFloat(),
                onValueChange = { value ->
                    playCountThresholdPercent = value.toInt().coerceIn(1, 100)
                    AppPreferences.Player.playCountThresholdPercent = playCountThresholdPercent
                },
                valueRange = 1f..100f,
                steps = 98,
                enabled = playCountEnabled,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_audio_bluetooth_sco))
            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(R.string.settings_audio_bluetooth_sco_desc),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            var scoMode by remember { mutableStateOf(AppPreferences.Player.bluetoothScoMode) }
            var scoDownsample by remember { mutableStateOf(AppPreferences.Player.bluetoothScoDownsample) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in listOf(0, 1, 2)) {
                    val label = AudioOutputManager.getScoModeLabel(mode)
                    val isSelected = scoMode == mode
                    TextButton(
                        onClick = {
                            scoMode = mode
                            AppPreferences.Player.bluetoothScoMode = mode
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            fontFamily = appFontFamily()
                        )
                    }
                }
            }

            Text(
                AudioOutputManager.getScoModeDescription(scoMode),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            if (scoMode > 0) {
                InfoSwitchRow(
                    stringResource(R.string.settings_audio_sco_downsample),
                    stringResource(R.string.settings_audio_sco_downsample_desc),
                    scoDownsample,
                    { infoDialogId = 4 }
                ) { checked ->
                    scoDownsample = checked
                    AppPreferences.Player.bluetoothScoDownsample = checked
                }
            }
        }

    }

    if (infoDialogId > 0) {
        val pair: Pair<String, String> = when (infoDialogId) {
            1 -> stringResource(R.string.settings_audio_info_volume_title) to stringResource(R.string.settings_audio_info_volume_body)
            2 -> stringResource(R.string.settings_audio_info_gapless_title) to stringResource(R.string.settings_audio_info_gapless_body)
            4 -> stringResource(R.string.settings_audio_info_sco_title) to stringResource(R.string.settings_audio_info_sco_body)
            else -> "" to ""
        }
        val title = pair.first
        val body = pair.second
        AlertDialog(
            onDismissRequest = { infoDialogId = 0 },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = { Text(body, fontSize = 14.sp, lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = { infoDialogId = 0 }) { Text(stringResource(R.string.settings_dialog_ok)) }
            }
        )
    }
    }
}

@Composable
private fun DitherSettingsPreference(
    title: String,
    description: String,
    options: List<Pair<Int, Pair<String, String>>>,
    selectedId: Int,
    onSelected: (Int) -> Unit
) {
    val dropdownEntry = remember(options, selectedId) {
        DropdownEntry(
            items = options.map { (id, option) ->
                DropdownItem(
                    text = option.first,
                    summary = option.second,
                    selected = selectedId == id,
                    onClick = { onSelected(id) }
                )
            }
        )
    }

    RawWindowDropdownPreference(
        entry = dropdownEntry,
        title = title,
        summary = description,
        enabled = options.isNotEmpty(),
        showValue = options.any { it.first == selectedId },
        maxHeight = 560.dp,
        collapseOnSelection = true,
    )
}

// ==========================
// v6f: 引擎卡片 — 图标在左，可展开
// ==========================

@Composable
private fun UsbExclusiveTransportCard(
    androidOutputLabel: String,
    active: Boolean,
    requested: Boolean,
    onClick: () -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val statusText = when {
        active -> stringResource(R.string.usb_dac_exclusive_active_short)
        requested -> stringResource(R.string.usb_dac_exclusive_pending_short)
        else -> stringResource(R.string.usb_dac_exclusive_off_short)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(64.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_audio_output_usb_png),
                    contentDescription = stringResource(R.string.usb_dac_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(52.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.usb_dac_independent_transport_title),
                    fontSize = 15.sp,
                    fontWeight = if (active || requested) FontWeight.Bold else FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                    fontFamily = appFontFamily()
                )
                Text(
                    stringResource(R.string.usb_dac_android_output_unchanged, androidOutputLabel),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (active || requested) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = appFontFamily()
            )
        }
        AnimatedVisibility(
            visible = active || requested,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = if (active) {
                    stringResource(R.string.usb_dac_exclusive_independent_active_desc)
                } else {
                    stringResource(R.string.usb_dac_exclusive_independent_pending_desc)
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
                fontFamily = appFontFamily()
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EngineCard(
    iconRes: Int,
    label: String,
    rangeHint: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val borderColor = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标在左：使用用户 SVG 转出的 PNG，保留原始配色；不加背景框、不 tint，尺寸放大
            Box(
                modifier = Modifier.width(64.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isEnabled) MiuixTheme.colorScheme.onBackground
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                    fontFamily = appFontFamily()
                )
                Text(
                    rangeHint,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 选中指示
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(8.dp)
                ) {}
            }
        }

        // 展开内容
        expandedContent()
    }

    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoSwitchRow(
    label: String,
    info: String,
    checked: Boolean,
    onInfoClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.settings_info_icon),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.clickable { onInfoClick() }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
