package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.FixedDeviceControlSession
import com.rawsmusic.module.player.devicecontrol.SerializedDeviceControlWriter
import com.rawsmusic.module.player.devicecontrol.resolveControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phase-6C vendor GATT adapter backend. No wildcard protocol adapter is installed. */
class BluetoothVendorGattDeviceControlBackend(
    context: Context,
    private val registry: BluetoothVendorGattAdapterRegistry = BluetoothVendorGattAdapterRegistry.Empty,
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
                // Enco Free4 has now been physically proven to expose its OPO control plane on
                // 079A RFCOMM. Do not concurrently open the BREDR-GATT inventory lane for this
                // exact model: it only returns GAP/GATT (1800/1801) and competes with the RFCOMM
                // socket during page startup. Other OPO-family devices keep the generic path.
                if (BluetoothOpoRfcommFastPath.matches(device, lookup.device)) null
                else Session(appContext, lookup.device, registry, device, generation, lookup.source)
            }
            is BluetoothBoundDeviceLookup.Result.Failed -> FixedDeviceControlSession(
                device = device,
                generation = generation,
                initialState = DeviceControlSnapshot.ProbeState.ERROR,
                initialMessage = "bluetooth_vendor_backend_open_failed:${lookup.reason}",
                initialDiagnostics = listOf("bt.backend vendor open_failed=${lookup.reason}"),
            )
        }
    }

    private class Session(
        context: Context,
        private val bluetoothDevice: BluetoothDevice,
        private val registry: BluetoothVendorGattAdapterRegistry,
        override val device: DeviceControlDevice,
        override val generation: Long,
        private val identitySource: String,
    ) : DeviceControlSession {
        private val appContext = context.applicationContext
        private val inventoryClient = BluetoothGattInventoryClient(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var notificationJobs: List<Job> = emptyList()
        private var reconnectJob: Job? = null
        private val _snapshot = MutableStateFlow(
            DeviceControlSnapshot(
                generation = generation,
                device = device,
                diagnostics = bluetoothDevice.toControlIdentityDiagnosticLines(identitySource),
            ),
        )
        override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

        @Volatile private var closed = false
        private var transportClient: BluetoothGattVendorTransportClient? = null
        private var transportEventJob: Job? = null
        private var selectedAdapter: BluetoothVendorGattAdapter? = null
        private var adapterContext: BluetoothVendorGattAdapterContext? = null
        private val writer = SerializedDeviceControlWriter(
            scope = scope,
            generation = generation,
            currentGeneration = { if (closed) Long.MIN_VALUE else generation },
            canExecute = { !closed && selectedAdapter != null && adapterContext != null },
            coalesceMs = 32L,
            execute = ::executeWrite,
        )

        override suspend fun probe() {
            if (closed) return
            clearNotificationJobs()
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.PROBING,
                diagnostics = bluetoothDevice.toControlIdentityDiagnosticLines(identitySource),
            )
            val progressDiagnostics = mutableListOf<String>()
            fun publishProgress(line: String) {
                synchronized(progressDiagnostics) {
                    progressDiagnostics += line
                    if (progressDiagnostics.size > MAX_PROGRESS_DIAGNOSTICS) {
                        progressDiagnostics.removeAt(0)
                    }
                    _snapshot.value = DeviceControlSnapshot(
                        generation = generation,
                        device = device,
                        probeState = DeviceControlSnapshot.ProbeState.PROBING,
                        message = "bluetooth_vendor_inventory_probing",
                        diagnostics = (
                            bluetoothDevice.toControlIdentityDiagnosticLines(identitySource) +
                                progressDiagnostics.toList()
                        ).distinct(),
                    )
                }
            }
            var gattDiagnostics: List<String> = emptyList()
            var controlBluetoothDevice = bluetoothDevice
            var controlTransport: Int? = BluetoothDevice.TRANSPORT_LE
            var controlAutoConnect = false
            val inventory = when (val result = inventoryClient.discover(bluetoothDevice, ::publishProgress)) {
                is BluetoothGattInventoryClient.Result.Ready -> {
                    gattDiagnostics = result.diagnostics
                    controlBluetoothDevice = result.controlDevice
                    controlTransport = result.controlTransport
                    controlAutoConnect = result.controlAutoConnect
                    result.inventory
                }
                is BluetoothGattInventoryClient.Result.Failed -> {
                    _snapshot.value = error(
                        result.reason,
                        bluetoothDevice.toControlIdentityDiagnosticLines(identitySource) + result.diagnostics,
                    )
                    return
                }
                BluetoothGattInventoryClient.Result.PermissionRequired -> {
                    _snapshot.value = DeviceControlSnapshot(
                        generation = generation,
                        device = device,
                        probeState = DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED,
                        message = "bluetooth_connect_permission_required",
                        diagnostics = _snapshot.value.diagnostics,
                    )
                    return
                }
            }
            val inventoryDiagnostics = (
                bluetoothDevice.toControlIdentityDiagnosticLines(identitySource) +
                    gattDiagnostics +
                    inventory.toDiagnosticLines()
            ).distinct()
            when (val match = registry.select(device, inventory)) {
                BluetoothVendorGattAdapterRegistry.Match.None -> {
                    closeTransportClient()
                    selectedAdapter = null
                    adapterContext = null
                    _snapshot.value = DeviceControlSnapshot(
                        generation = generation,
                        device = device,
                        probeState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                        message = "bluetooth_vendor_adapter_not_found;services=${inventory.services.size}",
                        diagnostics = inventoryDiagnostics,
                    )
                }
                is BluetoothVendorGattAdapterRegistry.Match.Ambiguous -> {
                    closeTransportClient()
                    selectedAdapter = null
                    adapterContext = null
                    _snapshot.value = error(
                        "bluetooth_vendor_adapter_ambiguous:${match.adapterIds.joinToString(",")}",
                        inventoryDiagnostics,
                    )
                }
                is BluetoothVendorGattAdapterRegistry.Match.Selected -> {
                    val selectedTransport = replaceTransportClient(
                        controlBluetoothDevice,
                        controlTransport,
                        controlAutoConnect,
                    )
                    val context = BluetoothVendorGattAdapterContext(
                        device = device,
                        generation = generation,
                        inventory = inventory,
                        transport = selectedTransport,
                    )
                    when (val result = runCatching { match.adapter.probe(context) }.getOrElse {
                        BluetoothVendorGattProbeResult.Failed("bluetooth_vendor_probe_exception", it)
                    }) {
                        is BluetoothVendorGattProbeResult.Ready -> {
                            selectedAdapter = match.adapter
                            adapterContext = context
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = device,
                                capabilities = result.capabilities,
                                probeState = if (result.capabilities.isEmpty()) {
                                    DeviceControlSnapshot.ProbeState.UNSUPPORTED
                                } else DeviceControlSnapshot.ProbeState.READY,
                                message = result.message,
                                diagnostics = inventoryDiagnostics,
                            )
                            if (result.capabilities.isNotEmpty()) {
                                configureNotifications(match.adapter, context)
                            }
                        }
                        is BluetoothVendorGattProbeResult.Unsupported -> {
                            selectedAdapter = match.adapter
                            adapterContext = context
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = device,
                                probeState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                                message = result.reason,
                                diagnostics = inventoryDiagnostics,
                            )
                        }
                        is BluetoothVendorGattProbeResult.Failed -> {
                            selectedAdapter = null
                            adapterContext = null
                            _snapshot.value = error(result.reason, inventoryDiagnostics)
                        }
                    }
                }
            }
        }

        override suspend fun refresh() {
            reconnectJob?.cancel()
            reconnectJob = null
            probe()
        }

        override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            if (closed) return DeviceControlWriteResult.Rejected("control_session_closed")
            return writer.submit(request)
        }

        private suspend fun executeWrite(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            val adapter = selectedAdapter
                ?: return DeviceControlWriteResult.Rejected("bluetooth_vendor_adapter_not_selected")
            val context = adapterContext
                ?: return DeviceControlWriteResult.Rejected("bluetooth_vendor_context_missing")
            val resolved = _snapshot.value.resolveControl(request.controlId)
                ?: return DeviceControlWriteResult.Rejected("unknown_control:${request.controlId.value}")
            val address = resolved.address as? DeviceControlBackendAddress.BluetoothGatt
                ?: return DeviceControlWriteResult.Rejected("control_not_owned_by_bluetooth_vendor_gatt")
            if (address.adapterId != adapter.adapterId) {
                return DeviceControlWriteResult.Rejected("bluetooth_vendor_adapter_address_mismatch")
            }
            return when (val result = adapter.write(context, request, _snapshot.value)) {
                is DeviceControlWriteResult.Applied -> {
                    val applied = result.snapshot.copy(generation = generation, device = device)
                    _snapshot.value = applied
                    DeviceControlWriteResult.Applied(applied)
                }
                is DeviceControlWriteResult.Rejected -> result
                is DeviceControlWriteResult.Failed -> result
            }
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            reconnectJob?.cancel()
            transportEventJob?.cancel()
            transportEventJob = null
            writer.close()
            clearNotificationJobs()
            selectedAdapter = null
            adapterContext = null
            transportClient?.close()
            transportClient = null
            scope.cancel()
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.IDLE,
                message = "closed",
            )
        }

        private suspend fun replaceTransportClient(
            controlDevice: BluetoothDevice,
            controlTransport: Int?,
            controlAutoConnect: Boolean,
        ): BluetoothGattVendorTransportClient {
            closeTransportClient()
            val client = BluetoothGattVendorTransportClient(
                appContext,
                controlDevice,
                transport = controlTransport,
                autoConnect = controlAutoConnect,
            )
            transportClient = client
            transportEventJob = scope.launch {
                client.events.collect { event ->
                    if (closed) return@collect
                    when (event) {
                        is BluetoothGattVendorTransportClient.Event.Disconnected -> scheduleReconnect(event.reason)
                    }
                }
            }
            return client
        }

        private suspend fun closeTransportClient() {
            transportEventJob?.cancel()
            transportEventJob = null
            transportClient?.close()
            transportClient = null
        }

        private fun scheduleReconnect(reason: String) {
            if (closed || reconnectJob?.isActive == true) return
            clearNotificationJobs()
            val diagnostics = _snapshot.value.diagnostics
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.RECONNECTING,
                message = reason,
                diagnostics = diagnostics,
            )
            reconnectJob = scope.launch {
                var backoffMs = RECONNECT_INITIAL_DELAY_MS
                while (!closed) {
                    delay(backoffMs)
                    probe()
                    when (_snapshot.value.probeState) {
                        DeviceControlSnapshot.ProbeState.READY,
                        DeviceControlSnapshot.ProbeState.UNSUPPORTED -> return@launch
                        DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED -> return@launch
                        else -> Unit
                    }
                    backoffMs = (backoffMs * 2L).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                }
            }
        }

        private suspend fun configureNotifications(
            adapter: BluetoothVendorGattAdapter,
            context: BluetoothVendorGattAdapterContext,
        ) {
            clearNotificationJobs()
            val targets = runCatching { adapter.notificationTargets(context, _snapshot.value) }
                .getOrDefault(emptyList())
                .distinct()
            if (targets.isEmpty()) return

            val jobs = mutableListOf<Job>()
            for (target in targets) {
                val subscribed = context.transport.subscribe(target)
                if (subscribed.isFailure) continue
                jobs += scope.launch {
                    context.transport.notifications(target).collectLatest { value ->
                        if (closed || selectedAdapter !== adapter || adapterContext !== context) return@collectLatest
                        when (val decoded = runCatching {
                            adapter.onNotification(context, target, value, _snapshot.value)
                        }.getOrDefault(BluetoothVendorGattNotificationResult.Ignore)) {
                            BluetoothVendorGattNotificationResult.Ignore -> Unit
                            BluetoothVendorGattNotificationResult.Refresh -> scope.launch { probe() }
                            is BluetoothVendorGattNotificationResult.Updated -> {
                                _snapshot.value = DeviceControlSnapshot(
                                    generation = generation,
                                    device = device,
                                    capabilities = decoded.capabilities,
                                    probeState = if (decoded.capabilities.isEmpty()) {
                                        DeviceControlSnapshot.ProbeState.UNSUPPORTED
                                    } else DeviceControlSnapshot.ProbeState.READY,
                                    message = decoded.message,
                                    diagnostics = _snapshot.value.diagnostics,
                                )
                            }
                        }
                    }
                }
            }
            notificationJobs = jobs
        }

        private fun clearNotificationJobs() {
            notificationJobs.forEach { it.cancel() }
            notificationJobs = emptyList()
        }

        private fun error(reason: String, diagnostics: List<String> = emptyList()) = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.ERROR,
            message = reason,
            diagnostics = diagnostics,
        )
    }

    private companion object {
        const val MAX_PROGRESS_DIAGNOSTICS = 24
        const val RECONNECT_INITIAL_DELAY_MS = 300L
        const val RECONNECT_MAX_DELAY_MS = 5_000L
    }
}
