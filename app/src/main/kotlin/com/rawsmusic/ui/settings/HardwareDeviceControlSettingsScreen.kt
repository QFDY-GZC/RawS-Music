package com.rawsmusic.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.devicecontrol.DeviceChoiceControl
import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlDiagnostics
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceToggleControl
import com.rawsmusic.module.player.devicecontrol.VendorControl
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HardwareDeviceControlSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember {
        mutableStateOf(PlayerService.currentRuntimeController() ?: PlayerController.getInstanceOrNull())
    }
    val emptySnapshotFlow = remember { MutableStateFlow(DeviceControlSnapshot(0L, null)) }
    val snapshot by (controller?.hardwareDeviceControlSnapshot ?: emptySnapshotFlow).collectAsState()
    val bluetoothDeviceControlPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        scope.launch { controller?.refreshHardwareDeviceControls() }
    }

    LaunchedEffect(Unit) {
        if (controller == null) {
            controller = PlayerService.obtainRuntimeController(
                context,
                "hardware_device_control_settings",
                ensureService = true,
            )
        }
        // Entering/re-entering this page must not trigger a full device probe. PlayerController
        // is service-owned and keeps DeviceControlManager/session/snapshot alive while the process
        // remains alive. Only ensure the current route is bound; this is a no-op for the same live
        // stable device. The explicit Refresh button and permission callback remain force-refresh.
        controller?.ensureHardwareDeviceControlsBound()
    }

    fun write(controlId: DeviceControlId, value: DeviceControlWriteValue) {
        val runtime = controller ?: return
        scope.launch {
            when (val result = runtime.writeHardwareDeviceControl(DeviceControlWriteRequest(controlId, value))) {
                is DeviceControlWriteResult.Applied -> Unit
                is DeviceControlWriteResult.Rejected -> AppNoticeBus.error(
                    context.getString(R.string.hardware_device_control_write_rejected, result.reason)
                )
                is DeviceControlWriteResult.Failed -> AppNoticeBus.error(
                    context.getString(R.string.hardware_device_control_write_failed, result.reason)
                )
            }
        }
    }

    fun copyDiagnostics() {
        val report = DeviceControlDiagnostics.render(snapshot)
        if (report.isBlank()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("RawSMusic Hardware DSP", report))
        Toast.makeText(
            context,
            context.getString(R.string.hardware_device_control_diagnostics_copied),
            Toast.LENGTH_SHORT,
        ).show()
    }

    SettingsPage(title = stringResource(R.string.hardware_device_control_title), onBack = onBack) {
        HardwareDeviceHeader(
            snapshot = snapshot,
            onRefresh = { scope.launch { controller?.refreshHardwareDeviceControls() } },
            onCopyDiagnostics = ::copyDiagnostics,
        )

        when (snapshot.probeState) {
            DeviceControlSnapshot.ProbeState.PROBING -> HardwareStatusCard(
                stringResource(R.string.hardware_device_control_probing),
            )
            DeviceControlSnapshot.ProbeState.RECONNECTING -> HardwareStatusCard(
                stringResource(R.string.hardware_device_control_reconnecting),
            )
            DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED -> HardwarePermissionCard(
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothDeviceControlPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                            ),
                        )
                    } else {
                        scope.launch { controller?.refreshHardwareDeviceControls() }
                    }
                },
            )
            DeviceControlSnapshot.ProbeState.DISCONNECTED -> HardwareStatusCard(
                stringResource(R.string.hardware_device_control_disconnected),
            )
            DeviceControlSnapshot.ProbeState.UNSUPPORTED -> HardwareStatusCard(
                stringResource(R.string.hardware_device_control_unsupported),
            )
            DeviceControlSnapshot.ProbeState.ERROR -> HardwareStatusCard(
                snapshot.message ?: stringResource(R.string.hardware_device_control_error),
            )
            DeviceControlSnapshot.ProbeState.IDLE -> if (snapshot.device == null) {
                HardwareStatusCard(stringResource(R.string.hardware_device_control_no_device))
            }
            DeviceControlSnapshot.ProbeState.READY -> Unit
        }

        snapshot.capabilities.forEach { capability ->
            HardwareCapabilityCard(capability = capability, onWrite = ::write)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HardwareDeviceHeader(
    snapshot: DeviceControlSnapshot,
    onRefresh: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snapshot.device?.displayName ?: stringResource(R.string.hardware_device_control_no_device),
                    fontSize = 18.sp,
                )
                val transport = when (snapshot.device?.connectionKind) {
                    DeviceConnectionKind.USB -> stringResource(R.string.hardware_device_control_usb)
                    DeviceConnectionKind.BLUETOOTH -> stringResource(R.string.hardware_device_control_bluetooth)
                    null -> stringResource(R.string.hardware_device_control_waiting_route)
                }
                Text(
                    text = transport,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot.device != null) {
                    TextButton(
                        text = stringResource(R.string.hardware_device_control_copy_diagnostics),
                        onClick = onCopyDiagnostics,
                    )
                }
                TextButton(text = stringResource(R.string.hardware_device_control_refresh), onClick = onRefresh)
            }
        }
    }
}

@Composable
private fun HardwarePermissionCard(onGrant: () -> Unit) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.hardware_device_control_permission_required),
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
            TextButton(
                text = stringResource(R.string.hardware_device_control_grant_permission),
                onClick = onGrant,
            )
        }
    }
}

@Composable
private fun HardwareStatusCard(message: String) {
    SettingsCard {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun HardwareCapabilityCard(
    capability: DeviceControlCapability,
    onWrite: (DeviceControlId, DeviceControlWriteValue) -> Unit,
) {
    SectionHeader(capability.label)
    SettingsCard {
        when (capability) {
            is DeviceControlCapability.GraphicEq -> {
                capability.enabled?.let { HardwareToggleControl(it, onWrite) }
                capability.bands.forEach { band ->
                    val label = band.label ?: band.frequencyHz?.let(::formatFrequency)
                        ?: band.gain.label
                    HardwareNumericControl(band.gain, label, onWrite)
                }
            }
            is DeviceControlCapability.ParametricEq -> capability.bands.forEachIndexed { index, band ->
                Text(
                    text = band.label ?: stringResource(R.string.hardware_device_control_peq_band, index + 1),
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                band.enabled?.let { HardwareToggleControl(it, onWrite) }
                band.frequency?.let { HardwareNumericControl(it, it.label, onWrite) }
                band.q?.let { HardwareNumericControl(it, it.label, onWrite) }
                band.gain?.let { HardwareNumericControl(it, it.label, onWrite) }
            }
            is DeviceControlCapability.Volume -> {
                capability.channels.forEach { HardwareNumericControl(it, it.label, onWrite) }
                capability.mutes.forEach { HardwareToggleControl(it, onWrite) }
            }
            is DeviceControlCapability.Range -> HardwareNumericControl(capability.control, capability.label, onWrite)
            is DeviceControlCapability.Toggle -> HardwareToggleControl(capability.control, onWrite)
            is DeviceControlCapability.Choice -> HardwareChoiceControl(capability.control, onWrite)
            is DeviceControlCapability.Dynamics -> {
                capability.enabled?.let { HardwareToggleControl(it, onWrite) }
                capability.ratio?.let { HardwareNumericControl(it, it.label, onWrite) }
                capability.maxAmplitude?.let { HardwareNumericControl(it, it.label, onWrite) }
                capability.threshold?.let { HardwareNumericControl(it, it.label, onWrite) }
                capability.attack?.let { HardwareNumericControl(it, it.label, onWrite) }
                capability.release?.let { HardwareNumericControl(it, it.label, onWrite) }
            }
            is DeviceControlCapability.Vendor -> capability.controls.forEach { control ->
                when (control) {
                    is VendorControl.Numeric -> HardwareNumericControl(control.control, control.label, onWrite)
                    is VendorControl.Toggle -> HardwareToggleControl(control.control, onWrite)
                    is VendorControl.Choice -> HardwareChoiceControl(control.control, onWrite)
                }
            }
        }
    }
}

@Composable
private fun HardwareNumericControl(
    control: DeviceNumericControl,
    title: String,
    onWrite: (DeviceControlId, DeviceControlWriteValue) -> Unit,
) {
    // Copy cross-module public properties into locals before using them in lambdas.
    // Kotlin cannot smart-cast control.current here because DeviceNumericControl lives
    // in another module and its public getter could theoretically change between reads.
    val current = control.current
    val ranges = control.ranges
    val range = remember(ranges, current) {
        ranges.firstOrNull { r -> current == null || current in r.min..r.max }
            ?: ranges.firstOrNull()
    }
    if (range == null || current == null || !current.isFinite() || range.max <= range.min) {
        HardwareReadOnlyValue(title, current?.let { formatNumericValue(it, control.unit) } ?: "—")
        return
    }

    var draft by remember(control.id.value, current) { mutableStateOf(current.toFloat()) }
    val min = range.min.toFloat()
    val max = range.max.toFloat()
    val step = range.step?.takeIf { it > 0.0 }
    val steps = step?.let {
        (((range.max - range.min) / it).roundToInt() - 1).coerceIn(0, 1000)
    } ?: 0
    val writable = control.access == DeviceControlAccess.READ_WRITE

    SliderPreference(
        title = title,
        summary = if (writable) null else stringResource(R.string.hardware_device_control_read_only),
        valueText = formatNumericValue(draft.toDouble(), control.unit),
        value = draft.coerceIn(min, max),
        onValueChange = { raw ->
            val quantized = quantize(raw.toDouble(), range.min, range.max, step).toFloat()
            draft = quantized
            if (writable) onWrite(control.id, DeviceControlWriteValue.Number(quantized.toDouble()))
        },
        valueRange = min..max,
        steps = steps,
        enabled = writable,
        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
    )
}

@Composable
private fun HardwareToggleControl(
    control: DeviceToggleControl,
    onWrite: (DeviceControlId, DeviceControlWriteValue) -> Unit,
) {
    val value = control.current ?: false
    val writable = control.access == DeviceControlAccess.READ_WRITE && control.current != null
    SwitchPreference(
        title = control.label,
        summary = if (writable) null else stringResource(R.string.hardware_device_control_read_only),
        checked = value,
        enabled = writable,
        onCheckedChange = { if (writable) onWrite(control.id, DeviceControlWriteValue.Toggle(it)) },
    )
}

@Composable
private fun HardwareChoiceControl(
    control: DeviceChoiceControl,
    onWrite: (DeviceControlId, DeviceControlWriteValue) -> Unit,
) {
    val writable = control.access == DeviceControlAccess.READ_WRITE
    val selected = control.options.firstOrNull { it.value == control.currentValue }
    val entry = remember(control.options, control.currentValue, writable) {
        DropdownEntry(
            items = control.options.map { option ->
                DropdownItem(
                    text = option.label,
                    selected = option.value == control.currentValue,
                    onClick = {
                        if (writable) onWrite(control.id, DeviceControlWriteValue.Choice(option.value))
                    },
                )
            },
        )
    }
    RawWindowDropdownPreference(
        entry = entry,
        title = control.label,
        summary = selected?.label ?: if (writable) "" else stringResource(R.string.hardware_device_control_read_only),
        enabled = writable && control.options.isNotEmpty(),
        showValue = selected != null,
        maxHeight = 430.dp,
        collapseOnSelection = true,
    )
}

@Composable
private fun HardwareReadOnlyValue(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Text(text = value, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun quantize(value: Double, min: Double, max: Double, step: Double?): Double {
    val clamped = value.coerceIn(min, max)
    if (step == null || step <= 0.0) return clamped
    return (min + ((clamped - min) / step).roundToInt() * step).coerceIn(min, max)
}

private fun formatFrequency(hz: Double): String = when {
    hz >= 1000.0 -> {
        val khz = hz / 1000.0
        if (abs(khz - khz.roundToInt()) < 0.01) "${khz.roundToInt()} kHz" else "%.1f kHz".format(khz)
    }
    else -> "${hz.roundToInt()} Hz"
}

private fun formatNumericValue(value: Double, unit: String?): String {
    val text = when {
        abs(value - value.roundToInt()) < 0.0001 -> value.roundToInt().toString()
        else -> "%.2f".format(value).trimEnd('0').trimEnd('.')
    }
    return if (unit.isNullOrBlank()) text else "$text $unit"
}
