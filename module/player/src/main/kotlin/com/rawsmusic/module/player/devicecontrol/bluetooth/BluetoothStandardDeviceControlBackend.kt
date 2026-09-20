package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.FixedDeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.ResolvedDeviceControl
import com.rawsmusic.module.player.devicecontrol.SerializedDeviceControlWriter
import com.rawsmusic.module.player.devicecontrol.accepts
import com.rawsmusic.module.player.devicecontrol.resolveControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Bluetooth SIG VCS/VOCS/AICS backend. Vendor GATT remains a separate future adapter layer. */
internal class BluetoothStandardDeviceControlBackend(
    context: Context,
) : DeviceControlBackend {
    private val appContext = context.applicationContext

    override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession? {
        if (device.connectionKind != DeviceConnectionKind.BLUETOOTH) return null
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return FixedDeviceControlSession(
                device = device,
                generation = generation,
                initialState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                initialMessage = "bluetooth_connect_permission_required",
            )
        }
        return when (val lookup = BluetoothBoundDeviceLookup.resolve(appContext, device)) {
            is BluetoothBoundDeviceLookup.Result.Found -> {
                if (lookup.device.shouldDeferStandardGattProbeToKnownVendorControl()) {
                    FixedDeviceControlSession(
                        device = device,
                        generation = generation,
                        initialState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                        initialMessage = "bluetooth_standard_probe_deferred_known_vendor_control",
                        initialDiagnostics =
                            lookup.device.toControlIdentityDiagnosticLines(lookup.source) +
                                "bt.standard skipped=opo_vendor_hint_without_cached_vcs_vocs_aics",
                    )
                } else {
                    BluetoothStandardDeviceControlSession(
                        appContext,
                        lookup.device,
                        device,
                        generation,
                        lookup.source,
                    )
                }
            }
            is BluetoothBoundDeviceLookup.Result.Failed -> FixedDeviceControlSession(
                device = device,
                generation = generation,
                initialState = DeviceControlSnapshot.ProbeState.ERROR,
                initialMessage = "bluetooth_standard_backend_open_failed:${lookup.reason}",
                initialDiagnostics = listOf("bt.backend standard open_failed=${lookup.reason}"),
            )
        }
    }
}

internal class BluetoothStandardDeviceControlSession(
    context: Context,
    private val bluetoothDevice: BluetoothDevice,
    override val device: DeviceControlDevice,
    override val generation: Long,
    private val identitySource: String,
) : DeviceControlSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BluetoothGattStandardControlClient(context)
    private val _snapshot = MutableStateFlow(
        DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.IDLE,
            diagnostics = bluetoothDevice.toControlIdentityDiagnosticLines(identitySource),
        ),
    )
    override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var closed = false
    private var notificationRefreshJob: Job? = null
    private var reconnectJob: Job? = null
    private val eventJob: Job = scope.launch {
        client.events.collect { event ->
            if (closed) return@collect
            when (event) {
                is BluetoothGattStandardControlClient.Event.StateChanged -> scheduleNotificationRefresh()
                is BluetoothGattStandardControlClient.Event.Disconnected -> scheduleReconnect(event.reason)
            }
        }
    }

    private val writer = SerializedDeviceControlWriter(
        scope = scope,
        generation = generation,
        currentGeneration = { if (closed) Long.MIN_VALUE else generation },
        canExecute = { !closed },
        coalesceMs = 80L,
        execute = ::executeWrite,
    )

    override suspend fun probe() {
        if (closed) return
        reconnectJob?.cancel()
        reconnectJob = null
        performProbe(
            startState = DeviceControlSnapshot.ProbeState.PROBING,
            failureState = DeviceControlSnapshot.ProbeState.ERROR,
        )
    }

    override suspend fun refresh() = probe()

    private suspend fun performProbe(
        startState: DeviceControlSnapshot.ProbeState?,
        failureState: DeviceControlSnapshot.ProbeState,
    ): Boolean {
        if (closed) return false
        if (startState != null) {
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = startState,
                diagnostics = bluetoothDevice.toControlIdentityDiagnosticLines(identitySource),
            )
        }
        return when (val result = client.probe(bluetoothDevice)) {
            is BluetoothGattStandardControlClient.Result.Ready -> {
                _snapshot.value = parsedSnapshot(result.data)
                _snapshot.value.probeState == DeviceControlSnapshot.ProbeState.READY ||
                    _snapshot.value.probeState == DeviceControlSnapshot.ProbeState.UNSUPPORTED
            }
            is BluetoothGattStandardControlClient.Result.Applied -> {
                _snapshot.value = parsedSnapshot(result.data)
                true
            }
            is BluetoothGattStandardControlClient.Result.Failed -> {
                _snapshot.value = stateSnapshot(failureState, result.reason)
                false
            }
            is BluetoothGattStandardControlClient.Result.Rejected -> {
                _snapshot.value = stateSnapshot(failureState, result.reason)
                false
            }
            BluetoothGattStandardControlClient.Result.PermissionRequired -> {
                _snapshot.value = stateSnapshot(
                    DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                    "bluetooth_connect_permission_required",
                )
                false
            }
        }
    }

    private fun scheduleNotificationRefresh() {
        if (closed) return
        notificationRefreshJob?.cancel()
        notificationRefreshJob = scope.launch {
            // Several standard characteristics can notify for one physical user action. Re-read
            // the complete whitelist once after a short quiet period so VCS/VOCS/AICS remain an
            // atomic device snapshot.
            delay(NOTIFICATION_REFRESH_DEBOUNCE_MS)
            if (closed || _snapshot.value.probeState != DeviceControlSnapshot.ProbeState.READY) return@launch
            val ok = performProbe(startState = null, failureState = DeviceControlSnapshot.ProbeState.RECONNECTING)
            if (!ok && _snapshot.value.probeState == DeviceControlSnapshot.ProbeState.RECONNECTING) {
                scheduleReconnect(_snapshot.value.message ?: "gatt_notification_readback_failed")
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (closed || reconnectJob?.isActive == true) return
        notificationRefreshJob?.cancel()
        notificationRefreshJob = null
        _snapshot.value = stateSnapshot(DeviceControlSnapshot.ProbeState.RECONNECTING, reason)
        reconnectJob = scope.launch {
            var backoffMs = RECONNECT_INITIAL_DELAY_MS
            while (!closed) {
                delay(backoffMs)
                val ok = performProbe(
                    startState = null,
                    failureState = DeviceControlSnapshot.ProbeState.RECONNECTING,
                )
                if (ok) return@launch
                if (_snapshot.value.probeState == DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED) {
                    return@launch
                }
                backoffMs = (backoffMs * 2L).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }
    }

    override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        if (closed) return DeviceControlWriteResult.Rejected("control_session_closed")
        return writer.submit(request)
    }

    private suspend fun executeWrite(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        val before = _snapshot.value
        if (before.probeState != DeviceControlSnapshot.ProbeState.READY) {
            return DeviceControlWriteResult.Rejected("bluetooth_standard_controls_not_ready")
        }
        val resolved = before.resolveControl(request.controlId)
            ?: return DeviceControlWriteResult.Rejected("unknown_control:${request.controlId.value}")
        if (!resolved.access.isWritable) {
            return DeviceControlWriteResult.Rejected("control_is_read_only:${request.controlId.value}")
        }
        val address = resolved.address as? DeviceControlBackendAddress.BluetoothStandard
            ?: return DeviceControlWriteResult.Rejected("control_not_owned_by_bluetooth_standard")

        val command = buildCommand(resolved, request)
            ?: return DeviceControlWriteResult.Rejected("unsupported_bluetooth_standard_write")

        return when (val result = client.write(bluetoothDevice, address, command)) {
            is BluetoothGattStandardControlClient.Result.Applied -> {
                val applied = BluetoothStandardControlProbeParser.parse(device, generation, result.data)
                _snapshot.value = applied
                DeviceControlWriteResult.Applied(applied)
            }
            is BluetoothGattStandardControlClient.Result.Rejected -> DeviceControlWriteResult.Rejected(result.reason)
            is BluetoothGattStandardControlClient.Result.Failed -> DeviceControlWriteResult.Failed(result.reason)
            BluetoothGattStandardControlClient.Result.PermissionRequired ->
                DeviceControlWriteResult.Rejected("bluetooth_connect_permission_required")
            is BluetoothGattStandardControlClient.Result.Ready ->
                DeviceControlWriteResult.Failed("unexpected_probe_result_during_write")
        }
    }

    private fun buildCommand(
        resolved: ResolvedDeviceControl,
        request: DeviceControlWriteRequest,
    ): BluetoothGattStandardControlClient.Command? {
        val id = request.controlId.value
        return when {
            id.startsWith("bt.vcs.") && id.endsWith(".volume.setting") -> {
                val numeric = resolved as? ResolvedDeviceControl.Numeric ?: return null
                val value = (request.value as? DeviceControlWriteValue.Number)?.value ?: return null
                if (!numeric.control.accepts(value)) return null
                val rounded = value.roundToInt()
                if (kotlin.math.abs(value - rounded) > 1e-6 || rounded !in 0..255) return null
                BluetoothGattStandardControlClient.Command.VcsSetVolume(rounded)
            }
            id.startsWith("bt.vcs.") && id.endsWith(".mute") -> {
                val enabled = (request.value as? DeviceControlWriteValue.Toggle)?.enabled ?: return null
                BluetoothGattStandardControlClient.Command.VcsSetMute(enabled)
            }
            id.startsWith("bt.vocs.") && id.endsWith(".offset") -> {
                val numeric = resolved as? ResolvedDeviceControl.Numeric ?: return null
                val value = (request.value as? DeviceControlWriteValue.Number)?.value ?: return null
                if (!numeric.control.accepts(value)) return null
                val rounded = value.roundToInt()
                if (kotlin.math.abs(value - rounded) > 1e-6 || rounded !in -255..255) return null
                BluetoothGattStandardControlClient.Command.VocsSetOffset(rounded)
            }
            id.startsWith("bt.aics.") && id.endsWith(".gain") -> {
                val numeric = resolved as? ResolvedDeviceControl.Numeric ?: return null
                val value = (request.value as? DeviceControlWriteValue.Number)?.value ?: return null
                if (!numeric.control.accepts(value)) return null
                BluetoothGattStandardControlClient.Command.AicsSetGainDb(value)
            }
            id.startsWith("bt.aics.") && id.endsWith(".mute") -> {
                val enabled = (request.value as? DeviceControlWriteValue.Toggle)?.enabled ?: return null
                BluetoothGattStandardControlClient.Command.AicsSetMute(enabled)
            }
            id.startsWith("bt.aics.") && id.endsWith(".gain_mode") -> {
                val value = (request.value as? DeviceControlWriteValue.Choice)?.value ?: return null
                when (value) {
                    "manual" -> BluetoothGattStandardControlClient.Command.AicsSetGainMode(false)
                    "automatic" -> BluetoothGattStandardControlClient.Command.AicsSetGainMode(true)
                    else -> null
                }
            }
            else -> null
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        notificationRefreshJob?.cancel()
        reconnectJob?.cancel()
        eventJob.cancel()
        writer.close()
        client.close()
        scope.cancel()
        _snapshot.value = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.IDLE,
            message = "closed",
        )
    }


    private fun parsedSnapshot(data: BluetoothStandardProbeData): DeviceControlSnapshot {
        val parsed = BluetoothStandardControlProbeParser.parse(device, generation, data)
        return parsed.copy(
            diagnostics = (bluetoothDevice.toControlIdentityDiagnosticLines(identitySource) + parsed.diagnostics).distinct(),
        )
    }

    private fun stateSnapshot(state: DeviceControlSnapshot.ProbeState, reason: String): DeviceControlSnapshot {
        val previous = _snapshot.value
        return DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = state,
            message = reason,
            diagnostics = previous.diagnostics,
        )
    }

    private companion object {
        const val NOTIFICATION_REFRESH_DEBOUNCE_MS = 80L
        const val RECONNECT_INITIAL_DELAY_MS = 300L
        const val RECONNECT_MAX_DELAY_MS = 5_000L
    }
}
