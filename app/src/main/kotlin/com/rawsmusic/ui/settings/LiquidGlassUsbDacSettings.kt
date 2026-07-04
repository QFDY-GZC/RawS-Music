package com.rawsmusic.ui.settings

import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.helper.UsbDacFeedbackHelper
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.PlayerController
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


    var bitPerfect by remember { mutableStateOf(AppPreferences.Player.bitPerfectEnabled) }
    var hardwareFU by remember { mutableStateOf(AppPreferences.Player.hardwareFeatureUnitEnabled) }
    var safeExclusive by remember { mutableStateOf(AppPreferences.Player.usbSafeExclusiveMode) }
    var usbNoCI by remember { mutableStateOf(AppPreferences.Player.usbNoControlInterface) }
    var usbLinearVol by remember { mutableStateOf(AppPreferences.Player.usbLinearVolume) }
    var usbForce1ms by remember { mutableStateOf(AppPreferences.Player.usbForce1MsPacket) }
    var targetSampleRate by remember { mutableStateOf(AppPreferences.Player.usbTargetSampleRate) }
    var targetBitDepth by remember { mutableStateOf(AppPreferences.Player.usbTargetBitDepth) }
    val usbCapabilities by (com.rawsmusic.ui.songs.PlayerHolder.controller?.usbCapabilities
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    var showResampleSheet by remember { mutableStateOf(false) }
    var showDeviceStatus by remember { mutableStateOf(false) }
    var deviceStatus by remember { mutableStateOf<PlayerController.UsbDeviceStatus?>(null) }

    // 打开设置页时主动刷新一次 USB capabilities
    LaunchedEffect(Unit) {
        com.rawsmusic.ui.songs.PlayerHolder.controller
            ?.refreshUsbCapabilities("usb_settings_open")
    }

    // DSD 转换设置状态
    var dsdEnabled by remember { mutableStateOf(AppPreferences.Player.dsdConversionEnabled) }
    var dsdRate by remember { mutableStateOf(AppPreferences.Player.dsdRate) }
    var dsdType by remember { mutableStateOf(AppPreferences.Player.dsdConversionType) }
    var dsdDither by remember { mutableStateOf(AppPreferences.Player.dsdDitherEnabled) }
    var dsdTransportMode by remember { mutableStateOf(AppPreferences.Player.usbDsdTransportMode) }
    val dsdRateOptions = listOf(64, 128, 256, 512, 1024)
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
            com.rawsmusic.ui.songs.PlayerHolder.controller?.applyUsbOutputSettingsChanged(userInitiated = true)
        }
    }

    fun applyDsdSettings() {
        AppPreferences.Player.dsdDopEnabled =
            UsbDsdTransport.fromPref(dsdTransportMode) == UsbDsdTransport.DOP
        val pc = com.rawsmusic.ui.songs.PlayerHolder.controller
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
    SettingsPage(title = "USB DAC 设置", onBack = onBack) {

        SettingsCard {
            SectionHeader("\u8bbe\u5907\u72b6\u6001")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        deviceStatus = com.rawsmusic.ui.songs.PlayerHolder.controller
                            ?.getUsbDeviceStatus()
                        showDeviceStatus = true
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "\u67e5\u770b USB DAC \u5f53\u524d\u72b6\u6001",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontFamily = appFontFamily()
                    )
                    Text(
                        "\u8bbe\u5907\u540d\u3001\u8f93\u51fa\u683c\u5f0f\u3001\u72ec\u5360\u3001\u94fe\u8def\u4e0e buffer \u6c34\u4f4d",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                        fontFamily = appFontFamily()
                    )
                }
                Text(
                    "\u6253\u5f00",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("问题反馈")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            UsbDacFeedbackHelper(context).launchPlaybackFeedbackEmail(
                                com.rawsmusic.ui.songs.PlayerHolder.controller
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "一键导出播放日志并通过邮箱反馈",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontFamily = appFontFamily()
                    )
                    Text(
                        "会整理当前播放期间的日志，并预填发送到 3091734331@qq.com。支持 QQ 邮箱、Gmail 等邮件客户端。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                        fontFamily = appFontFamily()
                    )
                }
                Text(
                    "发送",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("\u64ad\u653e\u7b56\u7565")
            SwitchRow("Bit-Perfect", bitPerfect) { checked ->
                val pc = com.rawsmusic.ui.songs.PlayerHolder.controller
                if (pc != null) {
                    val result = pc.setUsbBitPerfectEnabled(checked)
                    bitPerfect = AppPreferences.Player.bitPerfectEnabled
                    hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
                    if (result != 0) {
                        android.widget.Toast.makeText(
                            context,
                            if (checked) "需要先启用 USB 独占模式" else "切换完美比特失败",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    bitPerfect = checked
                    AppPreferences.Player.bitPerfectEnabled = checked
                    if (!checked) {
                        AppPreferences.Player.hardwareFeatureUnitEnabled = false
                        hardwareFU = false
                    }
                    UsbAudioEngine.nativeSetPolicy(false, checked, checked && hardwareFU)
                }
            }
            SwitchRow("USB 独占兼容模式", safeExclusive) { checked ->
                safeExclusive = checked
                AppPreferences.Player.usbSafeExclusiveMode = checked
                val pc = com.rawsmusic.ui.songs.PlayerHolder.controller
                pc?.applyUsbOutputSettingsChanged(userInitiated = true)
            }
            SwitchRow("强制 1ms 包间隔", usbForce1ms) { checked ->
                usbForce1ms = checked
                AppPreferences.Player.usbForce1MsPacket = checked
                val pc = com.rawsmusic.ui.songs.PlayerHolder.controller
                pc?.setUsbDacSettings(
                    noControlIface = usbNoCI,
                    forceUac1 = false,
                    linearVolume = usbLinearVol,
                    replaceVolume = false,
                    force1ms = checked
                )
                pc?.applyUsbOutputSettingsChanged(userInitiated = true)
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("PCM \u8f93\u51fa")
            OutputTargetRow(
                sampleRate = targetSampleRate,
                bitDepth = targetBitDepth
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showResampleSheet = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "\u91cd\u91c7\u6837\u9009\u62e9",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontFamily = appFontFamily()
                    )
                    Text(
                        "\u91c7\u6837\u7387 ${AudioOutputManager.SAMPLE_RATE_LABELS[targetSampleRate] ?: "\u81ea\u52a8"}  /  \u6bd4\u7279\u7387 ${AudioOutputManager.BIT_DEPTH_LABELS[targetBitDepth] ?: "\u81ea\u52a8"}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                        fontFamily = appFontFamily()
                    )
                }
                Text(
                    "\u9009\u62e9",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("DSD 输出")
            SwitchRow("启用 PCM→DSD 实时转换（Native DSD）", dsdEnabled) { checked ->
                if (checked) {
                    val nativeRates = dsdRateOptions.filter { canAttemptNativeDsd(it) }
                    val resolvedRate = when {
                        canAttemptNativeDsd(dsdRate) -> dsdRate
                        else -> nativeRates.lastOrNull()
                    }
                    if (resolvedRate == null) {
                        Toast.makeText(context, "当前 USB DAC 未确认 Native DSD 能力，已保持 PCM", Toast.LENGTH_SHORT).show()
                        dsdEnabled = false
                        AppPreferences.Player.dsdConversionEnabled = false
                        applyDsdSettings()
                    } else {
                        if (resolvedRate != dsdRate) {
                            dsdRate = resolvedRate
                            AppPreferences.Player.dsdRate = resolvedRate
                        }
                        dsdTransportMode = UsbDsdTransport.NATIVE.prefValue
                        AppPreferences.Player.usbDsdTransportMode = dsdTransportMode
                        dsdEnabled = true
                        AppPreferences.Player.dsdConversionEnabled = true
                        applyDsdSettings()
                    }
                } else {
                    dsdEnabled = false
                    AppPreferences.Player.dsdConversionEnabled = false
                    applyDsdSettings()
                }
            }
            DsdOptionGroup("DSD 源文件传输方式") {
                DsdOptionChip(
                    label = "PCM",
                    selected = !dsdEnabled
                ) {
                    dsdEnabled = false
                    AppPreferences.Player.dsdConversionEnabled = false
                    applyDsdSettings()
                }
                DsdOptionChip(
                    label = "DoP（DSD源）",
                    selected = !dsdEnabled && UsbDsdTransport.fromPref(dsdTransportMode) == UsbDsdTransport.DOP,
                    enabled = supportsCurrentDop
                ) {
                    dsdTransportMode = UsbDsdTransport.DOP.prefValue
                    AppPreferences.Player.usbDsdTransportMode = dsdTransportMode
                    applyDsdSettings()
                }
                DsdOptionChip(
                    label = "原生 DSD（DSD源）",
                    selected = !dsdEnabled && UsbDsdTransport.fromPref(dsdTransportMode) == UsbDsdTransport.NATIVE,
                    enabled = supportsCurrentNativeDsd
                ) {
                    dsdTransportMode = UsbDsdTransport.NATIVE.prefValue
                    AppPreferences.Player.usbDsdTransportMode = dsdTransportMode
                    applyDsdSettings()
                }
            }
            if (dsdEnabled) {
                DsdOptionGroup("PCM→DSD 倍率（Native DSD）") {
                    dsdRateOptions.forEach { value ->
                        val rateSupported = canAttemptNativeDsd(value)
                        DsdOptionChip(
                            label = "DSD$value",
                            selected = dsdRate == value,
                            enabled = rateSupported
                        ) {
                            dsdRate = value
                            AppPreferences.Player.dsdRate = value
                            applyDsdSettings()
                        }
                    }
                }
            }
            Text(
                text = when {
                    dsdCapabilityUnknown -> "尚未确认 USB DAC 的完整 DSD 能力，DSD 选项允许先尝试；播放初始化会按真实 UAC2 RAW_DATA/clock 再校验。"
                    dsdEnabled -> "PCM→DSD 使用 Native DSD（UAC2 RAW_DATA）传输。DoP 仅作为 DSD 源文件直出的兼容选项。"
                    UsbDsdTransport.fromPref(dsdTransportMode) == UsbDsdTransport.DOP -> "DoP 仅用于 DSD 源文件直出；PCM→DSD 转换始终使用 Native DSD。"
                    else -> "当前为 PCM 输出。DSD 源文件将根据设备能力自动选择 Native DSD 或 DoP 直出。"
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ========== Safe Core 说明 ==========
        SettingsCard {
            SectionHeader("Safe Core \u6a21\u5f0f")
            Text(
                text = "USB \u72ec\u5360\u6838\u5fc3\u8fd0\u884c\u5728 Safe Core \u6a21\u5f0f\u4e0b\u3002\n" +
                    "\u2022 \u4e0d\u4f7f\u7528 VID/PID \u786c\u7f16\u7801\u7b56\u7565\n" +
                    "\u2022 HID \u9065\u63a7\u9ed8\u8ba4\u7981\u7528\n" +
                    "\u2022 DSD/DoP \u8f93\u51fa\u7531 USB \u72ec\u5360\u94fe\u8def\u63a5\u7ba1\n" +
                    "\u2022 \u9ad8\u7ea7 DAC \u5f00\u5173\u5df2\u9501\u5b9a\u4e3a\u5b89\u5168\u9ed8\u8ba4\u503c\n" +
                    "\u2022 Native session envelope \u5904\u7406\u97f3\u91cf\u8fc7\u6e21",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("\u786c\u4ef6\u517c\u5bb9\u6027")
            SwitchRow("\u786c\u4ef6\u97f3\u91cf\u63a7\u5236", hardwareFU) { checked ->
                val pc = com.rawsmusic.ui.songs.PlayerHolder.controller
                if (pc != null) {
                    val result = pc.setUsbHardwareFeatureUnitEnabled(checked)
                    hardwareFU = AppPreferences.Player.hardwareFeatureUnitEnabled
                    if (result == 0) {
                        bitPerfect = AppPreferences.Player.bitPerfectEnabled
                    }
                } else {
                    hardwareFU = checked
                    AppPreferences.Player.hardwareFeatureUnitEnabled = checked
                    UsbAudioEngine.nativeSetPolicy(false, bitPerfect, checked)
                    android.widget.Toast.makeText(
                        context,
                        if (checked) "\u786c\u4ef6\u97f3\u91cf\u5c06\u5728\u4e0b\u6b21 USB \u64ad\u653e\u65f6\u542f\u7528" else "\u786c\u4ef6\u97f3\u91cf\u5df2\u5173\u95ed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            // Safe core: advanced DAC switches removed
        }

        // ========== UAC20 V2 Debug Testing ==========
        SettingsCard {
            SectionHeader("UAC20 V2 调试测试")

            val scope = rememberCoroutineScope()
            var v2SwitchEnabled by remember { mutableStateOf(false) }

            // 片段 1: 最小测试按钮 - stop legacy → delay → run v2 debug playback
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val controller = PlayerController.getInstance(context)

                        Log.i("UAC20-V2", "prepare debug smoke: disable gray switch first")
                        controller.setUac20DebugPlaybackSwitchEnabled(false)

                        // 先停掉当前 legacy 播放，避免 legacy-usb-active。
                        runCatching {
                            controller.stop()
                        }.onFailure {
                            Log.w("UAC20-V2", "controller.stop() failed/unsupported", it)
                        }

                        // 等 nativeClose / release interface / event thread 退出。
                        kotlinx.coroutines.delay(900)

                        controller.setUac20DebugPlaybackSwitchEnabled(true)

                        val result = controller.runCurrentUac20DebugDecodedPlaybackSmoke()

                        Log.i("UAC20-V2", "runCurrentUac20DebugDecodedPlaybackSmoke result=$result")
                        Log.i("UAC20-V2", controller.getLastUac20DebugProbeReport())
                        Log.i("UAC20-V2", controller.getLastUac20GrayPlaybackDecisionReport())

                        // 测试完关掉，避免下一次正常播放误进灰度。
                        controller.setUac20DebugPlaybackSwitchEnabled(false)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("V2 Debug Smoke (当前歌曲)")
            }

            // V2 Debug Generated Tone Smoke: 1kHz sine, bypasses FFmpeg/file decode.
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val controller = PlayerController.getInstance(context)

                        Log.i("UAC20-V2", "prepare generated tone smoke: disable gray switch first")
                        controller.setUac20DebugPlaybackSwitchEnabled(false)

                        runCatching {
                            controller.stop()
                        }.onFailure {
                            Log.w("UAC20-V2", "controller.stop() failed/unsupported", it)
                        }

                        kotlinx.coroutines.delay(900)

                        controller.setUac20DebugPlaybackSwitchEnabled(true)

                        val result = controller.runUac20DebugGeneratedToneSmoke()

                        Log.i("UAC20-V2", "runUac20DebugGeneratedToneSmoke result=$result")
                        Log.i("UAC20-V2", controller.getLastUac20DebugProbeReport())
                        Log.i("UAC20-V2", controller.getLastUac20GrayPlaybackDecisionReport())

                        controller.setUac20DebugPlaybackSwitchEnabled(false)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("V2 Debug Tone Smoke (1kHz Sine)")
            }

            // 片段 2: 打开 v2 灰度开关
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val controller = PlayerController.getInstance(context)
                        controller.setUac20DebugPlaybackSwitchEnabled(true)
                        v2SwitchEnabled = true
                        Log.i("UAC20-V2", "UAC20 gray playback switch enabled")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("Enable UAC20 V2 Gray")
            }

            // 关闭 v2 灰度开关
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val controller = PlayerController.getInstance(context)
                        controller.setUac20DebugPlaybackSwitchEnabled(false)
                        v2SwitchEnabled = false
                        Log.i("UAC20-V2", "UAC20 gray playback switch disabled")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("Disable UAC20 V2 Gray")
            }

            if (v2SwitchEnabled) {
                Text(
                    text = "⚠ V2 灰度开关已开启 - 正常播放将先尝试 v2 路径",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(300.dp))
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
                deviceStatus = com.rawsmusic.ui.songs.PlayerHolder.controller
                    ?.getUsbDeviceStatus()
            },
            onDismiss = { showDeviceStatus = false }
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
                        SheetTabButton("\u6bd4\u7279\u7387", tab == ResampleTab.BitRate) {
                            tab = ResampleTab.BitRate
                        }
                        SheetTabButton("\u91c7\u6837\u7387", tab == ResampleTab.SampleRate) {
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
                            if (tab == ResampleTab.SampleRate) "\u91c7\u6837\u7387" else "\u91c7\u6837\u4f4d\u6570",
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
                                "\u5173\u95ed",
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
