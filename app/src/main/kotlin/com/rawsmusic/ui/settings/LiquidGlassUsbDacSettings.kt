package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.helper.UsbDacFeedbackHelper
import com.rawsmusic.R
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.usb.UsbDsdTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LiquidGlassUsbDacSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimeController by remember {
        mutableStateOf(
            PlayerService.currentRuntimeController()
                ?: PlayerController.getInstanceOrNull()
        )
    }

    fun requireRuntimeController(reason: String): PlayerController {
        val current = runtimeController
        if (current != null) return current
        return PlayerService.obtainRuntimeController(
            context,
            "usb_dac_settings:$reason",
            ensureService = true
        )
    }


    var bitPerfectMode by remember { mutableStateOf(AppPreferences.Player.usbBitPerfectMode) }
    var exclusiveRequested by remember { mutableStateOf(AppPreferences.Player.usbExclusiveRequested) }
    var hardwareFU by remember { mutableStateOf(AppPreferences.Player.hardwareFeatureUnitEnabled) }
    var safeExclusive by remember { mutableStateOf(AppPreferences.Player.usbSafeExclusiveMode) }
    var usbNoCI by remember { mutableStateOf(AppPreferences.Player.usbNoControlInterface) }
    var usbLinearVol by remember { mutableStateOf(AppPreferences.Player.usbLinearVolume) }
    var usbForce1ms by remember { mutableStateOf(AppPreferences.Player.usbForce1MsPacket) }
    var targetSampleRate by remember { mutableStateOf(AppPreferences.Player.usbTargetSampleRate) }
    var targetBitDepth by remember { mutableStateOf(AppPreferences.Player.usbTargetBitDepth) }
    val usbCapabilities by (runtimeController?.usbCapabilities
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val usbExclusiveActiveState = runtimeController?.usbExclusiveActive?.collectAsState()
    val usbExclusiveActive = usbExclusiveActiveState?.value == true
    var showDeviceStatus by remember { mutableStateOf(false) }
    var deviceStatus by remember { mutableStateOf<PlayerController.UsbDeviceStatus?>(null) }
    var usbVolumeMode by remember { mutableStateOf(AppPreferences.Player.usbVolumeMode) }
    var usbSoftwareVolumeRangeDb by remember {
        mutableStateOf(AppPreferences.Player.usbSoftwareVolumeRangeDb)
    }
    var disableDacClockInfo by remember { mutableStateOf(AppPreferences.Player.usbDisableDacClockInfo) }
    var releaseBandwidthAfterPlayback by remember { mutableStateOf(AppPreferences.Player.usbReleaseBandwidthAfterPlayback) }
    var dacPreheatMs by remember { mutableStateOf(AppPreferences.Player.usbDacPreheatMs) }
    var showDigitalVolumeWarning by remember { mutableStateOf(false) }
    var preheatExpanded by remember { mutableStateOf(false) }

    // 打开设置页时主动刷新一次 USB capabilities
    LaunchedEffect(Unit) {
        runtimeController = requireRuntimeController("open")
        runtimeController?.refreshUsbCapabilities("usb_settings_open")
        deviceStatus = runtimeController?.getUsbDeviceStatus()
    }

    LaunchedEffect(runtimeController) {
        while (true) {
            deviceStatus = runtimeController?.getUsbDeviceStatus()
            delay(1200)
        }
    }

    // DSD 转换设置状态
    var dsdEnabled by remember { mutableStateOf(AppPreferences.Player.dsdConversionEnabled) }
    var dsdRate by remember { mutableStateOf(AppPreferences.Player.dsdRate) }
    var dsdType by remember { mutableStateOf(AppPreferences.Player.dsdConversionType) }
    var dsdDither by remember { mutableStateOf(AppPreferences.Player.dsdDitherEnabled) }
    var dsdTransportMode by remember { mutableStateOf(AppPreferences.Player.usbDsdTransportMode) }
    val dsdRateOptions = listOf(64, 128, 256, 512)
    val caps = usbCapabilities
    val supportedDsdRates = caps?.supportedDsdRates.orEmpty()
    val nativeDsdCapabilityUnknown =
        caps == null ||
            (!caps.hasAnyNativeDsdDescriptor && caps.nativeDsdFormats.isEmpty())
    val dsdCapabilityUnknown = nativeDsdCapabilityUnknown || supportedDsdRates.isEmpty()
    fun canAttemptNativeDsd(rate: Int): Boolean =
        nativeDsdCapabilityUnknown || caps?.supportsNativeDsd(rate) == true
    fun canAttemptDop(rate: Int): Boolean =
        dsdCapabilityUnknown || caps?.supportsDop(rate) == true
    val supportsCurrentDop = canAttemptDop(dsdRate)
    val supportsCurrentNativeDsd = canAttemptNativeDsd(dsdRate)
    val supportsCurrentDsd = supportsCurrentDop || supportsCurrentNativeDsd

    var applyJob by remember { mutableStateOf<Job?>(null) }

    fun applyUsbOutputSettings() {
        // Debounce: cancel any pending apply and schedule a new one after 500ms.
        // This prevents rapid stop/release/play cycles when the user scrolls
        // through sample rate or bit depth options quickly.
        applyJob?.cancel()
        applyJob = scope.launch {
            delay(500)
            runtimeController?.applyUsbOutputSettingsChanged(userInitiated = true)
        }
    }

    fun applyDsdSettings() {
        AppPreferences.Player.dsdConversionEnabled = dsdEnabled
        AppPreferences.Player.dsdRate = dsdRate
        AppPreferences.Player.dsdConversionType = dsdType
        AppPreferences.Player.dsdDitherEnabled = dsdDither
        AppPreferences.Player.usbDsdTransportMode = dsdTransportMode
        val pc = runtimeController
        if (pc != null) {
            pc.applyUsbOutputSettingsChanged(userInitiated = true)
        } else {
            // Preferences are already saved. Never mutate process-global native DSD state from a
            // settings screen without the PlayerController transport transaction: a stale USB
            // handle may still own the previous DSD altsetting even though the UI shows AAudio.
            android.util.Log.w(
                "UsbDacSettings",
                "DSD setting staged only: PlayerController unavailable; apply on next clean USB init",
            )
        }
    }

    fun selectBitPerfectMode(newMode: UsbBitPerfectMode) {
        val pc = runtimeController ?: requireRuntimeController("select_bit_perfect_mode").also {
            runtimeController = it
        }
        val result = pc.setUsbBitPerfectMode(newMode)
        bitPerfectMode = AppPreferences.Player.usbBitPerfectMode
        if (result != 0) {
            AppNoticeBus.post(
                message = if (newMode.requestsBitPerfect) {
                    context.getString(R.string.usb_dac_enable_exclusive_required)
                } else {
                    context.getString(R.string.usb_dac_switch_output_failed)
                },
                icon = AppNoticeIcon.ERROR,
            )
        }
        deviceStatus = pc.getUsbDeviceStatus()
    }

    fun selectVolumeMode(mode: Int) {
        if (mode == 2) {
            showDigitalVolumeWarning = true
            return
        }
        val pc = runtimeController ?: requireRuntimeController("select_volume_mode").also {
            runtimeController = it
        }
        usbVolumeMode = mode
        pc.setUsbVolumeMode(mode)
        bitPerfectMode = AppPreferences.Player.usbBitPerfectMode
        hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
        deviceStatus = pc.getUsbDeviceStatus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
    SettingsPage(title = stringResource(R.string.usb_dac_title), onBack = onBack) {
        val liveStatus = deviceStatus
        val outputSummary = remember(liveStatus, targetSampleRate, targetBitDepth) {
            buildUsbDashboardOutputText(liveStatus, targetSampleRate, targetBitDepth)
        }
        val deviceName = liveStatus?.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.usb_dac_not_connected)
        val connected = liveStatus?.connected == true

        UsbDacOverviewHeader(
            deviceName = deviceName,
            connected = connected,
            exclusiveRequested = exclusiveRequested,
            exclusiveActive = usbExclusiveActive,
            androidOutputLabel = AudioOutputManager.getOutputModeLabel(AppPreferences.Player.audioOutputMode),
            capabilities = caps,
        )

        SettingsCard {
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_exclusive_mode),
                description = when {
                    usbExclusiveActive -> stringResource(R.string.usb_dac_exclusive_active_desc)
                    exclusiveRequested -> stringResource(R.string.usb_dac_exclusive_pending_desc)
                    else -> stringResource(R.string.usb_dac_exclusive_desc)
                },
                note = if (exclusiveRequested && !usbExclusiveActive) {
                    stringResource(R.string.usb_dac_exclusive_waiting_note)
                } else {
                    null
                },
                checked = usbExclusiveActive || exclusiveRequested
            ) { checked ->
                val pc = runtimeController ?: requireRuntimeController("toggle_exclusive").also {
                    runtimeController = it
                }
                if (checked) {
                    exclusiveRequested = true
                    pc.enableUsbExclusive()
                } else {
                    exclusiveRequested = false
                    pc.disableUsbExclusive()
                }
                deviceStatus = pc.getUsbDeviceStatus()
            }
            val outputModeEntry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = stringResource(R.string.usb_dac_general_output),
                        summary = stringResource(R.string.usb_dac_general_output_desc),
                        selected = bitPerfectMode == UsbBitPerfectMode.OFF,
                        onClick = { selectBitPerfectMode(UsbBitPerfectMode.OFF) }
                    ),
                    DropdownItem(
                        text = stringResource(R.string.usb_dac_bit_perfect_when_possible),
                        summary = stringResource(R.string.usb_dac_bit_perfect_when_possible_desc),
                        selected = bitPerfectMode == UsbBitPerfectMode.WHEN_POSSIBLE,
                        onClick = { selectBitPerfectMode(UsbBitPerfectMode.WHEN_POSSIBLE) }
                    ),
                    DropdownItem(
                        text = stringResource(R.string.usb_dac_bit_perfect_strict),
                        summary = stringResource(R.string.usb_dac_bit_perfect_strict_desc),
                        selected = bitPerfectMode == UsbBitPerfectMode.STRICT,
                        onClick = { selectBitPerfectMode(UsbBitPerfectMode.STRICT) }
                    )
                )
            )
            DacDropdownRow(
                iconRes = R.drawable.ic_dac_sample_rate_png,
                iconContentDescription = stringResource(R.string.usb_dac_sample_rate),
                title = stringResource(R.string.usb_dac_sample_rate),
                value = AudioOutputManager.SAMPLE_RATE_LABELS[targetSampleRate] ?: stringResource(R.string.usb_dac_auto),
                subtitle = stringResource(R.string.usb_dac_current_output_format, outputSummary.substringBefore("/").trim()),
                entry = DropdownEntry(
                    items = (listOf(0) + caps?.supportedSampleRates.orEmpty()
                        .filter { it in 1..AudioOutputManager.USB_MAX_TARGET_SAMPLE_RATE }
                        .distinct()
                        .sorted()).distinct().map { rate ->
                        DropdownItem(
                            text = formatSampleRate(rate),
                            selected = targetSampleRate == rate,
                            onClick = {
                                targetSampleRate = rate
                                AudioOutputManager.setUsbTargetSampleRate(rate)
                                applyUsbOutputSettings()
                            }
                        )
                    }
                ),
                maxHeight = 430.dp
            )
            DacDropdownRow(
                iconRes = R.drawable.ic_dac_bit_rate_png,
                iconContentDescription = stringResource(R.string.usb_dac_bit_depth),
                title = stringResource(R.string.usb_dac_bit_depth),
                value = AudioOutputManager.BIT_DEPTH_LABELS[targetBitDepth] ?: stringResource(R.string.usb_dac_auto),
                subtitle = stringResource(R.string.usb_dac_bit_depth_desc),
                entry = DropdownEntry(
                    items = (listOf(0) + caps?.supportedBitDepths.orEmpty())
                        .distinct()
                        .sorted()
                        .map { depth ->
                            DropdownItem(
                                text = AudioOutputManager.BIT_DEPTH_LABELS[depth] ?: "${depth}bit",
                                selected = targetBitDepth == depth,
                                onClick = {
                                    targetBitDepth = depth
                                    AudioOutputManager.setUsbTargetBitDepth(depth)
                                    applyUsbOutputSettings()
                                }
                            )
                        }
                ),
                maxHeight = 360.dp
            )
            DacDropdownRow(
                iconRes = R.drawable.ic_volume_up,
                iconTinted = true,
                iconContentDescription = stringResource(R.string.usb_dac_volume_mode),
                title = stringResource(R.string.usb_dac_volume_mode),
                value = usbVolumeModeLabel(usbVolumeMode),
                subtitle = stringResource(R.string.usb_dac_volume_mode_summary),
                entry = DropdownEntry(
                    items = listOf(1, 0, 2).map { mode ->
                        DropdownItem(
                            text = usbVolumeModeLabel(mode),
                            summary = usbVolumeModeDescription(mode),
                            selected = usbVolumeMode.coerceIn(0, 2) == mode,
                            onClick = { selectVolumeMode(mode) }
                        )
                    }
                ),
                maxHeight = 320.dp
            )
            if (usbVolumeMode.coerceIn(0, 2) == 0) {
                DacDropdownRow(
                    iconRes = R.drawable.ic_equalizer_bars,
                    iconTinted = true,
                    iconContentDescription = stringResource(R.string.usb_dac_software_volume_fineness),
                    title = stringResource(R.string.usb_dac_software_volume_fineness),
                    value = stringResource(
                        R.string.usb_dac_software_volume_range_value,
                        usbSoftwareVolumeRangeDb,
                    ),
                    subtitle = stringResource(R.string.usb_dac_software_volume_fineness_desc),
                    entry = DropdownEntry(
                        items = listOf(40, 50, 60, 70, 80).map { rangeDb ->
                            DropdownItem(
                                text = context.getString(
                                    R.string.usb_dac_software_volume_range_value,
                                    rangeDb,
                                ),
                                summary = softwareVolumeRangeDescription(context, rangeDb),
                                selected = usbSoftwareVolumeRangeDb == rangeDb,
                                onClick = {
                                    usbSoftwareVolumeRangeDb = rangeDb
                                    AppPreferences.Player.usbSoftwareVolumeRangeDb = rangeDb
                                    runtimeController?.refreshUsbSoftwareVolumeCurve(
                                        "usb_settings_range_$rangeDb",
                                    )
                                },
                            )
                        }
                    ),
                    maxHeight = 360.dp,
                )
            }
            DacDropdownRow(
                iconRes = R.drawable.ic_audio_bit_perfect_png,
                iconContentDescription = stringResource(R.string.usb_dac_output_mode),
                title = stringResource(R.string.usb_dac_output_mode),
                value = when (bitPerfectMode) {
                    UsbBitPerfectMode.OFF -> stringResource(R.string.usb_dac_general_output)
                    UsbBitPerfectMode.WHEN_POSSIBLE -> stringResource(R.string.usb_dac_bit_perfect_when_possible_short)
                    UsbBitPerfectMode.STRICT -> stringResource(R.string.usb_dac_bit_perfect_strict)
                },
                subtitle = stringResource(R.string.usb_dac_output_mode_summary),
                entry = outputModeEntry,
                maxHeight = 300.dp
            )
            DacSettingRow(
                iconRes = R.drawable.ic_usb_line,
                iconTinted = true,
                iconContentDescription = stringResource(R.string.usb_dac_device_chain),
                title = stringResource(R.string.usb_dac_device_chain),
                value = if (liveStatus?.running == true) stringResource(R.string.usb_dac_outputting) else if (connected) stringResource(R.string.usb_dac_connected) else stringResource(R.string.usb_dac_disconnected),
                subtitle = liveStatus?.outputChain ?: stringResource(R.string.usb_dac_waiting_chain),
                onClick = {
                    val controller = runtimeController ?: requireRuntimeController("device_chain_open")
                    runtimeController = controller
                    deviceStatus = controller.getUsbDeviceStatus()
                    showDeviceStatus = true
                }
            )
        }

        SettingsCard {
            DacSectionHeader(
                title = stringResource(R.string.usb_dac_dsd_output),
                iconRes = R.drawable.ic_audio_codec_dsd_png,
                contentDescription = stringResource(R.string.usb_dac_dsd_output)
            )
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_pcm_to_dsd),
                description = stringResource(R.string.usb_dac_pcm_to_dsd_desc),
                checked = dsdEnabled
            ) { checked ->
                if (checked && bitPerfectMode != UsbBitPerfectMode.OFF) {
                    bitPerfectMode = UsbBitPerfectMode.OFF
                    AppPreferences.Player.usbBitPerfectMode = UsbBitPerfectMode.OFF
                }
                dsdEnabled = checked
                AppPreferences.Player.dsdConversionEnabled = checked
                applyDsdSettings()
            }
            DsdOptionGroup(stringResource(R.string.usb_dac_pcm_to_dsd_rate)) {
                dsdRateOptions.forEach { rate ->
                    DsdOptionChip(
                        label = "DSD$rate",
                        selected = dsdRate == rate,
                        enabled = true
                    ) {
                        dsdRate = rate
                        AppPreferences.Player.dsdRate = rate
                        applyDsdSettings()
                    }
                }
            }
            DsdOptionGroup(stringResource(R.string.usb_dac_dsd_transport)) {
                listOf(
                    UsbDsdTransport.PCM to "PCM",
                    UsbDsdTransport.DOP to "DoP",
                    UsbDsdTransport.NATIVE to "Native DSD"
                ).forEach { (transport, label) ->
                    DsdOptionChip(
                        label = label,
                        selected = UsbDsdTransport.fromPref(dsdTransportMode) == transport,
                        enabled = true
                    ) {
                        dsdTransportMode = transport.prefValue
                        AppPreferences.Player.usbDsdTransportMode = transport.prefValue
                        applyDsdSettings()
                    }
                }
            }
            DsdOptionGroup(stringResource(R.string.usb_dac_conversion_algorithm)) {
                listOf(
                    0 to stringResource(R.string.usb_dac_dsd_algorithm_standard),
                    1 to stringResource(R.string.usb_dac_dsd_algorithm_high_quality),
                    2 to stringResource(R.string.usb_dac_dsd_algorithm_low_latency)
                ).forEach { (type, label) ->
                    DsdOptionChip(
                        label = label,
                        selected = dsdType == type,
                        enabled = true
                    ) {
                        dsdType = type
                        AppPreferences.Player.dsdConversionType = type
                        applyDsdSettings()
                    }
                }
            }
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_dsd_dither),
                description = stringResource(R.string.usb_dac_dsd_dither_desc),
                checked = dsdDither
            ) { checked ->
                dsdDither = checked
                AppPreferences.Player.dsdDitherEnabled = checked
                applyDsdSettings()
            }
            Text(
                text = stringResource(R.string.usb_dac_dsd_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                fontFamily = appFontFamily()
            )
        }

        SettingsCard {
            SectionHeader(stringResource(R.string.usb_dac_advanced_options))
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_force_1ms),
                description = stringResource(R.string.usb_dac_force_1ms_desc),
                note = stringResource(R.string.usb_dac_force_1ms_note),
                checked = usbForce1ms
            ) { checked ->
                usbForce1ms = checked
                AppPreferences.Player.usbForce1MsPacket = checked
                val pc = runtimeController
                pc?.applyUsbOutputSettingsChanged(userInitiated = true)
            }
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_disable_clock_info),
                description = stringResource(R.string.usb_dac_disable_clock_info_desc),
                checked = disableDacClockInfo
            ) { checked ->
                disableDacClockInfo = checked
                AppPreferences.Player.usbDisableDacClockInfo = checked
                runtimeController?.applyUsbOutputSettingsChanged(userInitiated = true)
            }
            AdvancedSwitchRow(
                title = stringResource(R.string.usb_dac_release_bandwidth),
                description = stringResource(R.string.usb_dac_release_bandwidth_desc),
                checked = releaseBandwidthAfterPlayback
            ) { checked ->
                releaseBandwidthAfterPlayback = checked
                AppPreferences.Player.usbReleaseBandwidthAfterPlayback = checked
            }
            PreheatSettingRow(
                preheatMs = dacPreheatMs,
                expanded = preheatExpanded,
                onToggle = { preheatExpanded = !preheatExpanded },
                onSelected = { value ->
                    dacPreheatMs = value
                    AppPreferences.Player.usbDacPreheatMs = value
                }
            )
        }

        SettingsCard {
            SectionHeader(stringResource(R.string.usb_dac_feedback_section))
            DacSettingRow(
                iconText = stringResource(R.string.usb_dac_feedback_icon),
                title = stringResource(R.string.usb_dac_feedback_title),
                value = stringResource(R.string.usb_dac_feedback_action),
                subtitle = stringResource(R.string.usb_dac_feedback_desc),
                onClick = {
                    scope.launch {
                        UsbDacFeedbackHelper(context).launchPlaybackFeedbackEmail(runtimeController)
                    }
                }
            )
        }

        Spacer(Modifier.height(220.dp))
    }

        UsbDeviceStatusDialog(
            visible = showDeviceStatus,
            status = deviceStatus,
            onRefresh = {
                deviceStatus = runtimeController?.getUsbDeviceStatus()
            },
            onDismiss = { showDeviceStatus = false }
        )

        DigitalVolumeWarningDialog(
            visible = showDigitalVolumeWarning,
            onConfirm = {
                val pc = runtimeController ?: requireRuntimeController("digital_volume_warning")
                runtimeController = pc
                usbVolumeMode = 2
                pc.setUsbVolumeMode(2)
                bitPerfectMode = AppPreferences.Player.usbBitPerfectMode
                hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
                deviceStatus = pc.getUsbDeviceStatus()
                showDigitalVolumeWarning = false
            },
            onDismiss = { showDigitalVolumeWarning = false }
        )
    }
}

@Composable
private fun UsbDeviceStatusDialog(
    visible: Boolean,
    status: PlayerController.UsbDeviceStatus?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.76f
    RawMiuixOverlayDialog(
        show = visible,
        title = stringResource(R.string.usb_dac_device_status_title),
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
            renderInRootScaffold = true
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (status == null) {
                    Text(
                        "\u6682\u65e0\u72b6\u6001\u6570\u636e",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        fontFamily = appFontFamily()
                    )
                } else {
                    StatusSection("\u8bbe\u5907") {
                        StatusRow("\u540d\u79f0", status.deviceName)
                        StatusRow("VID/PID", status.vendorProductId)
                        StatusRow("\u8fde\u63a5", if (status.connected) "\u5df2\u8fde\u63a5" else "\u672a\u8fde\u63a5")
                        StatusRow("\u6743\u9650", if (status.permissionGranted) "\u5df2\u6388\u6743" else "\u672a\u6388\u6743")
                        StatusRow("\u7ba1\u7406\u5668", status.managerState)
                    }
                    StatusSection("\u64ad\u653e\u94fe\u8def") {
                        StatusRow("\u72ec\u5360", if (status.exclusiveActive) "\u5df2\u542f\u7528" else "\u672a\u542f\u7528")
                        StatusRow("\u5f15\u64ce", if (status.initialized) "\u5df2\u521d\u59cb\u5316" else "\u672a\u521d\u59cb\u5316")
                        StatusRow("\u4f20\u8f93", if (status.running) "\u6b63\u5728\u8f93\u51fa" else "\u672a\u8f93\u51fa")
                        StatusRow("Bit-Perfect 策略", when (AppPreferences.Player.usbBitPerfectMode) {
                            UsbBitPerfectMode.OFF -> stringResource(R.string.usb_dac_general_output)
                            UsbBitPerfectMode.WHEN_POSSIBLE -> stringResource(R.string.usb_dac_bit_perfect_when_possible)
                            UsbBitPerfectMode.STRICT -> stringResource(R.string.usb_dac_bit_perfect_strict)
                        })
                        StatusRow("Bit-Perfect 当前", if (status.bitPerfect) "\u5f00" else "\u5173")
                        StatusRow("\u64ad\u653e\u6a21\u5f0f", status.playbackMode)
                        StatusRow("\u94fe\u8def", status.outputChain)
                    }
                    StatusSection("\u683c\u5f0f") {
                        StatusRow("\u6e90\u683c\u5f0f", status.sourceFormat)
                        StatusRow("\u76ee\u6807\u683c\u5f0f", status.targetFormat)
                        StatusRow("\u5b9e\u9645\u8f93\u51fa", status.actualOutputFormat)
                        StatusRow("PCM\u2192DSD", status.dsdInfo)
                    }
                    StatusSection("\u4f20\u8f93") {
                        StatusRow("\u63a5\u53e3", status.interfaceInfo)
                        StatusRow("\u7aef\u70b9", status.endpointInfo)
                        StatusRow("Buffer", status.bufferInfo)
                        StatusRow("吞吐", status.transportDiagnostics)
                        StatusRow("Audible", status.audibleDiagnostics)
                        StatusRow("Feedback", status.feedbackDiagnostics)
                        StatusRow("Clock", status.clockDiagnostics)
                        StatusRow("\u786c\u4ef6\u97f3\u91cf", status.hardwareVolumeInfo)
                        StatusRow("Feature Unit", status.featureUnitDiagnostics)
                    }
                    StatusSection("Profile / Recovery") {
                        StatusRow("Profile", status.profileDiagnostics)
                        StatusRow("Recovery", status.recoveryDiagnostics)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.common_refresh),
                    onClick = onRefresh
                )
                TextButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun StatusSection(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MiuixTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = appFontFamily()
            )
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            fontFamily = appFontFamily()
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = appFontFamily()
        )
    }
}

@Composable
private fun OutputTargetRow(
    sampleRate: Int,
    bitDepth: Int
) {
    
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "\u5f53\u524d\u76ee\u6807",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onBackground,
                fontFamily = appFontFamily()
            )
            Text(
                "\u91c7\u6837\u7387 ${AudioOutputManager.SAMPLE_RATE_LABELS[sampleRate] ?: "\u81ea\u52a8"}  /  \u6bd4\u7279\u7387 ${AudioOutputManager.BIT_DEPTH_LABELS[bitDepth] ?: "\u81ea\u52a8"}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
                fontFamily = appFontFamily()
            )
        }
    }
}

@Composable
private fun DsdOptionGroup(
    title: String,
    content: @Composable () -> Unit
) {
    
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onBackground,
            fontFamily = appFontFamily()
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun DsdOptionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    
    RadioButtonPreference(
        title = label,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun formatSampleRate(rate: Int): String {
    return AudioOutputManager.SAMPLE_RATE_LABELS[rate] ?: when {
        rate >= 1_000_000 -> "${rate / 1_000_000.0} MHz"
        rate % 1000 == 0 -> "${rate / 1000} kHz"
        else -> "${rate / 1000.0} kHz"
    }
}

private enum class ResampleTab { BitRate, SampleRate }

@Composable
private fun ResampleBottomSheet(
    visible: Boolean,
    sampleRate: Int,
    bitDepth: Int,
    capabilities: com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?,
    onSampleRateChange: (Int) -> Unit,
    onBitDepthChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(ResampleTab.SampleRate) }
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.68f
    // 动态采样率：仅显示设备支持的 + Auto；capabilities 为空时只显示 Auto
    val deviceRates = capabilities
        ?.supportedSampleRates
        ?.filter { it > 0 && it <= AudioOutputManager.USB_MAX_TARGET_SAMPLE_RATE }
        ?.distinct()
        ?.sorted()
        .orEmpty()
    val sampleRates = listOf(0) + deviceRates

    val bitDepths = buildList {
        add(0)
        addAll(capabilities?.supportedBitDepths.orEmpty())
    }.distinct().sorted()

    RawMiuixOverlayDialog(
        show = visible,
        title = stringResource(R.string.usb_dac_output_mode),
        summary = stringResource(R.string.usb_dac_general_output_desc),
        onDismissRequest = onDismiss,
        renderInRootScaffold = true
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SheetTabButton(stringResource(R.string.usb_dac_bit_rate_tab), tab == ResampleTab.BitRate) {
                    tab = ResampleTab.BitRate
                }
                SheetTabButton(stringResource(R.string.usb_dac_sample_rate), tab == ResampleTab.SampleRate) {
                    tab = ResampleTab.SampleRate
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (tab == ResampleTab.SampleRate) stringResource(R.string.usb_dac_sample_rate) else stringResource(R.string.usb_dac_sample_bits),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.height(8.dp))

                if (tab == ResampleTab.SampleRate) {
                    sampleRates.forEach { rate ->
                        SheetOptionPreference(formatSampleRate(rate), sampleRate == rate) {
                            onSampleRateChange(rate)
                        }
                    }
                } else {
                    bitDepths.forEach { depth ->
                        val label = AudioOutputManager.BIT_DEPTH_LABELS[depth] ?: "${depth}bit"
                        SheetOptionPreference(label, bitDepth == depth) {
                            onBitDepthChange(depth)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.usb_dac_close),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun SheetTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        text = label,
        onClick = onClick,
        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
            textColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
        ),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun SheetOptionPreference(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    RadioButtonPreference(
        title = label,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}


@Composable
private fun UsbDacOverviewHeader(
    deviceName: String,
    connected: Boolean,
    exclusiveRequested: Boolean,
    exclusiveActive: Boolean,
    androidOutputLabel: String,
    capabilities: com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?,
) {
    val effectiveCapabilities = capabilities.takeIf { connected }
    val maxSampleRate = effectiveCapabilities?.supportedSampleRates?.maxOrNull()
    val maxBitDepth = effectiveCapabilities?.supportedBitDepths?.maxOrNull()
    val maxChannels = effectiveCapabilities
        ?.pcmFormats
        ?.map { it.channels }
        ?.filter { it > 0 }
        ?.maxOrNull()

    val routeText = when {
        exclusiveActive -> stringResource(R.string.usb_dac_header_route_exclusive, androidOutputLabel)
        exclusiveRequested -> stringResource(R.string.usb_dac_header_route_starting, androidOutputLabel)
        else -> stringResource(R.string.usb_dac_header_route_android, androidOutputLabel)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (connected) Color(0xFF55D889) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (connected) stringResource(R.string.usb_dac_connected) else stringResource(R.string.usb_dac_disconnected),
                        color = if (connected) Color(0xFF55D889) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = appFontFamily()
                    )
                }

                Text(
                    deviceName,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                    fontFamily = appFontFamily()
                )
                Text(
                    routeText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    fontFamily = appFontFamily()
                )
            }

            Spacer(Modifier.width(14.dp))
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.05f),
                                Color.Transparent,
                            )
                        ),
                        RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_audio_output_usb_png),
                    contentDescription = stringResource(R.string.usb_dac_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DacCapabilityStat(
                label = stringResource(R.string.usb_dac_capability_max_sample_rate),
                value = maxSampleRate?.let(::formatSampleRate) ?: "—",
                modifier = Modifier.weight(1f)
            )
            DacCapabilityDivider()
            DacCapabilityStat(
                label = stringResource(R.string.usb_dac_capability_max_bit_depth),
                value = maxBitDepth?.let { "${it}-bit" } ?: "—",
                modifier = Modifier.weight(1f)
            )
            DacCapabilityDivider()
            DacCapabilityStat(
                label = stringResource(R.string.usb_dac_capability_max_channels),
                value = maxChannels?.let { stringResource(R.string.usb_dac_channel_count, it) } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DacCapabilityStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = appFontFamily()
        )
        Text(
            label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
            fontFamily = appFontFamily()
        )
    }
}

@Composable
private fun DacCapabilityDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

@Composable
private fun DacSectionHeader(
    title: String,
    iconRes: Int,
    contentDescription: String? = title
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(22.dp)
        )
        Text(
            title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = appFontFamily()
        )
    }
}

@Composable
private fun DacSettingRow(
    iconText: String? = null,
    iconRes: Int? = null,
    iconTinted: Boolean = false,
    iconContentDescription: String? = null,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = subtitle,
        onClick = onClick,
        startAction = {
            Box(
                Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != null) {
                    if (iconTinted) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = iconContentDescription ?: title,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(27.dp),
                        )
                    } else {
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = iconContentDescription ?: title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(31.dp)
                        )
                    }
                } else {
                    Text(
                        iconText.orEmpty(),
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = appFontFamily()
                    )
                }
            }
        },
        endActions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    value,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    fontFamily = appFontFamily()
                )
                Icon(
                    imageVector = MiuixIcons.Regular.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    )
}

@Composable
private fun DacDropdownRow(
    iconText: String? = null,
    iconRes: Int? = null,
    iconTinted: Boolean = false,
    iconContentDescription: String? = null,
    title: String,
    value: String,
    subtitle: String,
    entry: DropdownEntry,
    maxHeight: androidx.compose.ui.unit.Dp
) {
    RawWindowDropdownPreference(
        entry = entry,
        title = title,
        summary = subtitle,
        showValue = true,
        valueOverride = value,
        maxHeight = maxHeight,
        collapseOnSelection = true,
        startAction = {
            Box(
                Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != null) {
                    if (iconTinted) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = iconContentDescription ?: title,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(27.dp),
                        )
                    } else {
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = iconContentDescription ?: title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(31.dp)
                        )
                    }
                } else {
                    Text(
                        iconText.orEmpty(),
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = appFontFamily()
                    )
                }
            }
        }
    )
}

@Composable
private fun AdvancedSwitchRow(
    title: String,
    description: String,
    note: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchPreference(
        title = title,
        summary = buildString {
            append(description)
            if (!note.isNullOrBlank()) {
                append("\n")
                append(note)
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PreheatSettingRow(
    preheatMs: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelected: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.usb_dac_preheat_event),
            summary = if (preheatMs <= 0) {
                stringResource(R.string.usb_dac_never_preheat)
            } else {
                stringResource(R.string.usb_dac_ms_value, preheatMs)
            },
            onClick = onToggle,
            endActions = {
                Text(
                    if (expanded) stringResource(R.string.usb_dac_collapse)
                    else stringResource(R.string.usb_dac_select),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontFamily = appFontFamily()
                )
            }
        )
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                listOf(0, 100, 200, 300, 400, 500, 800, 1000, 1500, 2000, 2500).forEach { value ->
                    RadioButtonPreference(
                        title = if (value == 0) stringResource(R.string.usb_dac_never_preheat) else stringResource(R.string.usb_dac_ms_value, value),
                        selected = preheatMs == value,
                        onClick = { onSelected(value) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitalVolumeWarningDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    RawMiuixOverlayDialog(
        show = visible,
        title = stringResource(R.string.usb_dac_max_volume_warning),
        onDismissRequest = onDismiss,
        renderInRootScaffold = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.usb_dac_max_volume_warning_desc),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = appFontFamily()
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.usb_dac_cancel),
                    onClick = onDismiss
                )
                TextButton(
                    text = stringResource(R.string.usb_dac_confirm_enable),
                    onClick = onConfirm,
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

private fun usbVolumeModeLabel(mode: Int): String = when (mode.coerceIn(0, 2)) {
    1 -> "硬件音量"
    2 -> "数字音量"
    else -> "软件音量"
}

private fun usbVolumeModeDescription(mode: Int): String = when (mode.coerceIn(0, 2)) {
    1 -> "使用 DAC Feature Unit 控制音量"
    2 -> "固定 0dB 最大输出，禁止音量调节"
    else -> "使用播放器软件增益控制音量"
}

private fun softwareVolumeRangeDescription(context: android.content.Context, rangeDb: Int): String =
    when (rangeDb.coerceIn(40, 80)) {
    40 -> context.getString(R.string.usb_dac_software_volume_range_40_desc)
    50 -> context.getString(R.string.usb_dac_software_volume_range_50_desc)
    60 -> context.getString(R.string.usb_dac_software_volume_range_60_desc)
    70 -> context.getString(R.string.usb_dac_software_volume_range_70_desc)
    else -> context.getString(R.string.usb_dac_software_volume_range_80_desc)
}

private fun buildUsbDashboardOutputText(status: PlayerController.UsbDeviceStatus?, targetSampleRate: Int, targetBitDepth: Int): String {
    val actual = status?.actualOutputFormat.orEmpty()
    val sr = Regex("(\\d+)Hz").find(actual)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val bits = Regex("(\\d+)bit").find(actual)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val srText = when {
        sr != null && sr > 0 -> formatSampleRate(sr)
        targetSampleRate > 0 -> AudioOutputManager.SAMPLE_RATE_LABELS[targetSampleRate] ?: formatSampleRate(targetSampleRate)
        else -> "自动"
    }
    val bitText = when {
        bits != null && bits > 0 -> "${bits}-bit"
        targetBitDepth > 0 -> AudioOutputManager.BIT_DEPTH_LABELS[targetBitDepth] ?: "${targetBitDepth}-bit"
        else -> "自动"
    }
    return "$srText / $bitText"
}

private fun extractUsbBufferMsText(status: PlayerController.UsbDeviceStatus?): String {
    val buffer = status?.bufferInfo.orEmpty()
    val ms = Regex("/\\s*(\\d+)\\s*ms").find(buffer)?.groupValues?.getOrNull(1)
    return if (ms != null) "${ms} ms" else "-- ms"
}

private fun extractUsbStabilityText(status: PlayerController.UsbDeviceStatus?): String {
    val ratio = Regex("ratio=([0-9.]+)").find(status?.transportDiagnostics.orEmpty())?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    return if (ratio != null && ratio > 0.0) "${"%.1f".format((ratio.coerceAtMost(1.0) * 100.0))}%" else "--%"
}

private fun extractUsbHealthText(status: PlayerController.UsbDeviceStatus?): String {
    val text = listOf(status?.transportDiagnostics, status?.audibleDiagnostics, status?.recoveryDiagnostics).joinToString(" ")
    if (status == null) return "--%"
    val hasHardError = text.contains("fatal", true) || text.contains("xfer=", true) && !text.contains("xfer=0", true)
    val hasSoftError = text.contains("underrun=", true) && !text.contains("underrun=0", true) || text.contains("submit=", true) && !text.contains("submit=0", true)
    return when {
        !status.connected -> "0%"
        hasHardError -> "72%"
        hasSoftError -> "88%"
        status.running -> "98%"
        else -> "95%"
    }
}
