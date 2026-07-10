package com.rawsmusic.ui.settings

import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.helper.UsbDacFeedbackHelper
import com.rawsmusic.R
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.usb.UsbAudioEngine
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


    var bitPerfect by remember { mutableStateOf(AppPreferences.Player.bitPerfectEnabled) }
    var hardwareFU by remember { mutableStateOf(AppPreferences.Player.hardwareFeatureUnitEnabled) }
    var safeExclusive by remember { mutableStateOf(AppPreferences.Player.usbSafeExclusiveMode) }
    var usbNoCI by remember { mutableStateOf(AppPreferences.Player.usbNoControlInterface) }
    var usbLinearVol by remember { mutableStateOf(AppPreferences.Player.usbLinearVolume) }
    var usbForce1ms by remember { mutableStateOf(AppPreferences.Player.usbForce1MsPacket) }
    var targetSampleRate by remember { mutableStateOf(AppPreferences.Player.usbTargetSampleRate) }
    var targetBitDepth by remember { mutableStateOf(AppPreferences.Player.usbTargetBitDepth) }
    val usbCapabilities by (runtimeController?.usbCapabilities
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    var showResampleSheet by remember { mutableStateOf(false) }
    var showDeviceStatus by remember { mutableStateOf(false) }
    var deviceStatus by remember { mutableStateOf<PlayerController.UsbDeviceStatus?>(null) }
    var usbVolumeMode by remember { mutableStateOf(AppPreferences.Player.usbVolumeMode) }
    var disableDacClockInfo by remember { mutableStateOf(AppPreferences.Player.usbDisableDacClockInfo) }
    var releaseBandwidthAfterPlayback by remember { mutableStateOf(AppPreferences.Player.usbReleaseBandwidthAfterPlayback) }
    var dacPreheatMs by remember { mutableStateOf(AppPreferences.Player.usbDacPreheatMs) }
    var showVolumeModeDialog by remember { mutableStateOf(false) }
    var showDigitalVolumeWarning by remember { mutableStateOf(false) }
    var preheatExpanded by remember { mutableStateOf(false) }
    var dashboardMetricInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

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
            UsbAudioEngine.setDsdConversion(
                enabled = dsdEnabled,
                rate = dsdRate,
                type = dsdType,
                dither = dsdDither,
                dop = dsdEnabled && (UsbDsdTransport.fromPref(dsdTransportMode) == UsbDsdTransport.DOP)
            )
        }
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
        val bufferText = remember(liveStatus) { extractUsbBufferMsText(liveStatus) }
        val stabilityText = remember(liveStatus) { extractUsbStabilityText(liveStatus) }
        val healthText = remember(liveStatus) { extractUsbHealthText(liveStatus) }
        val deviceName = liveStatus?.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.usb_dac_not_connected)
        val connected = liveStatus?.connected == true

        UsbDacDashboardCard(
            deviceName = deviceName,
            outputSummary = outputSummary,
            connected = connected,
            bufferText = bufferText,
            stabilityText = stabilityText,
            healthText = healthText,
            onMetricClick = { label, value ->
                dashboardMetricInfo = label to dashboardMetricExplanation(label, value, liveStatus)
            }
        )

        SettingsCard {
            DacSettingRow(
                iconRes = R.drawable.ic_audio_bit_perfect_png,
                iconContentDescription = stringResource(R.string.usb_dac_output_mode),
                title = stringResource(R.string.usb_dac_output_mode),
                value = if (bitPerfect) stringResource(R.string.usb_dac_bit_perfect) else stringResource(R.string.usb_dac_general_output),
                subtitle = if (bitPerfect) stringResource(R.string.usb_dac_bit_perfect_desc) else stringResource(R.string.usb_dac_general_output_desc),
                onClick = {
                    val pc = runtimeController ?: requireRuntimeController("toggle_bit_perfect")
                    runtimeController = pc
                    val newValue = !bitPerfect
                    val result = pc.setUsbBitPerfectEnabled(newValue)
                    bitPerfect = AppPreferences.Player.bitPerfectEnabled
                    if (result != 0) {
                        Toast.makeText(context, if (newValue) context.getString(R.string.usb_dac_enable_exclusive_required) else context.getString(R.string.usb_dac_switch_output_failed), Toast.LENGTH_SHORT).show()
                    }
                    deviceStatus = pc.getUsbDeviceStatus()
                }
            )
            DacSettingRow(
                iconRes = R.drawable.ic_dac_sample_rate_png,
                iconContentDescription = stringResource(R.string.usb_dac_sample_rate),
                title = stringResource(R.string.usb_dac_sample_rate),
                value = AudioOutputManager.SAMPLE_RATE_LABELS[targetSampleRate] ?: stringResource(R.string.usb_dac_auto),
                subtitle = stringResource(R.string.usb_dac_current_output_format, outputSummary.substringBefore("/").trim()),
                onClick = { showResampleSheet = true }
            )
            DacSettingRow(
                iconRes = R.drawable.ic_dac_bit_rate_png,
                iconContentDescription = stringResource(R.string.usb_dac_bit_depth),
                title = stringResource(R.string.usb_dac_bit_depth),
                value = AudioOutputManager.BIT_DEPTH_LABELS[targetBitDepth] ?: stringResource(R.string.usb_dac_auto),
                subtitle = stringResource(R.string.usb_dac_bit_depth_desc),
                onClick = { showResampleSheet = true }
            )
            DacSettingRow(
                iconText = stringResource(R.string.usb_dac_volume_short),
                title = stringResource(R.string.usb_dac_volume_mode),
                value = usbVolumeModeLabel(usbVolumeMode),
                subtitle = usbVolumeModeDescription(usbVolumeMode),
                onClick = { showVolumeModeDialog = true }
            )
            DacSettingRow(
                iconRes = R.drawable.ic_dac_waveform_png,
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
                if (checked && bitPerfect) {
                    bitPerfect = false
                    AppPreferences.Player.bitPerfectEnabled = false
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

        ResampleBottomSheet(
            visible = showResampleSheet,
            sampleRate = targetSampleRate,
            bitDepth = targetBitDepth,
            capabilities = usbCapabilities,
            onSampleRateChange = { rate ->
                targetSampleRate = rate
                AudioOutputManager.setUsbTargetSampleRate(rate)
                applyUsbOutputSettings()
            },
            onBitDepthChange = { depth ->
                targetBitDepth = depth
                AudioOutputManager.setUsbTargetBitDepth(depth)
                applyUsbOutputSettings()
            },
            onDismiss = { showResampleSheet = false }
        )

        UsbDeviceStatusDialog(
            visible = showDeviceStatus,
            status = deviceStatus,
            onRefresh = {
                deviceStatus = runtimeController?.getUsbDeviceStatus()
            },
            onDismiss = { showDeviceStatus = false }
        )

        VolumeModeDialog(
            visible = showVolumeModeDialog,
            selectedMode = usbVolumeMode,
            onSelect = { mode ->
                if (mode == 2) {
                    showDigitalVolumeWarning = true
                } else {
                    val pc = runtimeController ?: requireRuntimeController("volume_mode")
                    runtimeController = pc
                    usbVolumeMode = mode
                    pc.setUsbVolumeMode(mode)
                    bitPerfect = AppPreferences.Player.bitPerfectEnabled
                    hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
                    deviceStatus = pc.getUsbDeviceStatus()
                }
                showVolumeModeDialog = false
            },
            onDismiss = { showVolumeModeDialog = false }
        )

        DigitalVolumeWarningDialog(
            visible = showDigitalVolumeWarning,
            onConfirm = {
                val pc = runtimeController ?: requireRuntimeController("digital_volume_warning")
                runtimeController = pc
                usbVolumeMode = 2
                pc.setUsbVolumeMode(2)
                bitPerfect = AppPreferences.Player.bitPerfectEnabled
                hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
                deviceStatus = pc.getUsbDeviceStatus()
                showDigitalVolumeWarning = false
            },
            onDismiss = { showDigitalVolumeWarning = false }
        )
        DashboardMetricInfoDialog(
            metric = dashboardMetricInfo,
            onDismiss = { dashboardMetricInfo = null }
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
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogInteraction = remember { MutableInteractionSource() }
        val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.76f
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 28.dp)
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        interactionSource = dialogInteraction,
                        indication = null
                    ) {},
                color = Color(0xFF050505),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "USB DAC \u8bbe\u5907\u72b6\u6001",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = appFontFamily()
                        )
                        TextButton(onClick = onDismiss) {
                            Text("\u5173\u95ed", color = Color.White, fontFamily = appFontFamily())
                        }
                    }

                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (status == null) {
                            Text(
                                "\u6682\u65e0\u72b6\u6001\u6570\u636e",
                                color = Color(0xFFBDBDBD),
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
                                StatusRow("Bit-Perfect", if (status.bitPerfect) "\u5f00" else "\u5173")
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
                        TextButton(onClick = onRefresh) {
                            Text("\u5237\u65b0", color = Color.White, fontFamily = appFontFamily())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = appFontFamily()
        )
        content()
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
            color = Color(0xFF8E8E8E),
            fontSize = 12.sp,
            fontFamily = appFontFamily()
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = Color(0xFFE5E5E5),
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
    
    Box(
        Modifier
            .background(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = when {
                selected -> MiuixTheme.colorScheme.onPrimary
                enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontFamily = appFontFamily()
        )
    }
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
    val sheetInteraction = remember { MutableInteractionSource() }
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.64f
    // 动态采样率：仅显示设备支持的 + Auto；capabilities 为空时只显示 Auto
    val deviceRates = capabilities
        ?.supportedSampleRates
        ?.filter { it > 0 && it <= 384000 }
        ?.distinct()
        ?.sorted()
        .orEmpty()
    val sampleRates = listOf(0) + deviceRates

    val bitDepths = buildList {
        add(0)
        addAll(capabilities?.supportedBitDepths.orEmpty())
    }.distinct().sorted()

    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp)
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        interactionSource = sheetInteraction,
                        indication = null
                    ) {},
                color = Color(0xFF262626),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
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
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = appFontFamily()
                        )
                        Spacer(Modifier.height(14.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (tab == ResampleTab.SampleRate) {
                                sampleRates.forEach { rate ->
                                    SheetOptionChip(formatSampleRate(rate), sampleRate == rate) {
                                        onSampleRateChange(rate)
                                    }
                                }
                            } else {
                                bitDepths.forEach { depth ->
                                    val label = AudioOutputManager.BIT_DEPTH_LABELS[depth] ?: "${depth}bit"
                                    SheetOptionChip(label, bitDepth == depth) {
                                        onBitDepthChange(depth)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            Modifier
                                .background(Color(0xFF6A6A6A), RoundedCornerShape(28.dp))
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 30.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.usb_dac_close),
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = appFontFamily()
                            )
                        }
                    }
                }
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
    Text(
        text = label,
        Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        color = if (selected) Color.White else Color(0xFF8E8E8E),
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        fontFamily = appFontFamily()
    )
}

@Composable
private fun SheetOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            Modifier
                .size(30.dp)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(15.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        Text(
            label,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = appFontFamily()
        )
    }
}


@Composable
private fun UsbDacDashboardCard(
    deviceName: String,
    outputSummary: String,
    connected: Boolean,
    bufferText: String,
    stabilityText: String,
    healthText: String,
    onMetricClick: (String, String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF1B275C),
                            Color(0xFF0E1D42),
                            Color(0xFF10213F)
                        )
                    ),
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.ic_usb_exclusive_dashboard_png),
                            contentDescription = stringResource(R.string.usb_dac_exclusive_mode),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            stringResource(R.string.usb_dac_exclusive_mode),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp),
                            fontFamily = appFontFamily()
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.usb_dac_current_output_sample_rate),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontFamily = appFontFamily()
                        )
                        Text(
                            outputSummary,
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                            fontFamily = appFontFamily()
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    deviceName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(if (connected) Color(0xFF32D765) else Color(0xFF8D8D8D), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (connected) stringResource(R.string.usb_dac_connected) else stringResource(R.string.usb_dac_disconnected),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        fontFamily = appFontFamily()
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DashboardMetric(
                        label = stringResource(R.string.usb_dac_buffer),
                        value = bufferText,
                        iconRes = R.drawable.ic_dac_buffer_png,
                        modifier = Modifier.weight(1f)
                    ) {
                        onMetricClick("buffer", bufferText)
                    }
                    DashboardMetric(
                        label = stringResource(R.string.usb_dac_stability),
                        value = stabilityText,
                        iconRes = R.drawable.ic_dac_waveform_png,
                        modifier = Modifier.weight(1f)
                    ) {
                        onMetricClick("stability", stabilityText)
                    }
                    DashboardMetric(
                        label = stringResource(R.string.usb_dac_health),
                        value = healthText,
                        iconRes = R.drawable.ic_audio_hires_png,
                        modifier = Modifier.weight(1f)
                    ) {
                        onMetricClick("health", healthText)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
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
private fun DashboardMetric(
    label: String,
    value: String,
    iconRes: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(3.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp, fontFamily = appFontFamily())
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp), fontFamily = appFontFamily())
    }
}

@Composable
private fun DacSettingRow(
    iconText: String? = null,
    iconRes: Int? = null,
    iconContentDescription: String? = null,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = iconContentDescription ?: title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(27.dp)
                )
            } else {
                Text(iconText.orEmpty(), color = MiuixTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = appFontFamily())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MiuixTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
            Text(subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
        }
        Text(value, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp, fontFamily = appFontFamily())
        Spacer(Modifier.width(8.dp))
        Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 24.sp)
    }
}

@Composable
private fun AdvancedSwitchRow(
    title: String,
    description: String,
    note: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MiuixTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
            Text(description, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
            if (!note.isNullOrBlank()) {
                Text(note, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f), fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp), fontFamily = appFontFamily())
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreheatSettingRow(
    preheatMs: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelected: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.usb_dac_preheat_event), color = MiuixTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
                Text(if (preheatMs <= 0) stringResource(R.string.usb_dac_never_preheat) else stringResource(R.string.usb_dac_ms_value, preheatMs), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
            }
            Text(if (expanded) stringResource(R.string.usb_dac_collapse) else stringResource(R.string.usb_dac_select), color = MiuixTheme.colorScheme.primary, fontSize = 13.sp, fontFamily = appFontFamily())
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 100, 200, 300, 400, 500, 800, 1000, 1500, 2000, 2500).forEach { value ->
                    DacChip(
                        label = if (value == 0) stringResource(R.string.usb_dac_never_preheat) else stringResource(R.string.usb_dac_ms_value, value),
                        selected = preheatMs == value
                    ) { onSelected(value) }
                }
            }
        }
    }
}

@Composable
private fun DacChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontFamily = appFontFamily()
        )
    }
}

@Composable
private fun DashboardMetricInfoDialog(
    metric: Pair<String, String>?,
    onDismiss: () -> Unit
) {
    if (metric == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dashboardMetricTitle(metric.first), fontWeight = FontWeight.SemiBold, fontFamily = appFontFamily()) },
        text = { Text(metric.second, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = appFontFamily()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.usb_dac_got_it)) } }
    )
}

@Composable
private fun VolumeModeDialog(
    visible: Boolean,
    selectedMode: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    MiuixChoiceDialog(
        visible = visible,
        title = stringResource(R.string.usb_dac_volume_mode),
        selectedValue = selectedMode.coerceIn(0, 2),
        items = listOf(1, 0, 2).map { mode ->
            MiuixChoiceItem(
                value = mode,
                title = usbVolumeModeLabel(mode),
                summary = usbVolumeModeDescription(mode)
            )
        },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
private fun DigitalVolumeWarningDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usb_dac_max_volume_warning), fontWeight = FontWeight.SemiBold, fontFamily = appFontFamily()) },
        text = { Text(stringResource(R.string.usb_dac_max_volume_warning_desc), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = appFontFamily()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.usb_dac_confirm_enable)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.usb_dac_cancel)) } }
    )
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

private fun dashboardMetricTitle(label: String): String = label

private fun dashboardMetricExplanation(
    label: String,
    value: String,
    status: PlayerController.UsbDeviceStatus?
): String = when (label) {
    "缓冲区" -> "当前值：$value\n\n表示 native USB 环形缓冲区折算出的可播放时间。数值越高，抗短暂调度抖动能力越强；过高则会增加切歌、暂停、seek 的响应延迟。"
    "稳定率" -> "当前值：$value\n\n根据 USB 完成吞吐、回调频率、underrun、submit/xfer 错误等指标估算。接近 100% 表示 host 到 DAC 的传输节奏稳定。"
    "健康率" -> "当前值：$value\n\n综合设备连接、独占状态、真实输出、缓冲水位、错误计数和恢复状态。低于 90% 时建议打开设备链路查看具体诊断。"
    else -> "当前值：$value\n\n${status?.transportDiagnostics.orEmpty()}"
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
