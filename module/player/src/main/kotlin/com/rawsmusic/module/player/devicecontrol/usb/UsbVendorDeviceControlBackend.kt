package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.SerializedDeviceControlWriter
import com.rawsmusic.module.player.devicecontrol.resolveControl
import com.rawsmusic.module.player.usb.UsbAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phase-4A vendor discovery/adapter backend. Unknown protocols remain passive inventory only. */
class UsbVendorDeviceControlBackend(
    private val engine: UsbAudioEngine,
    private val registry: UsbVendorAdapterRegistry = UsbVendorAdapterRegistry.Empty,
) : DeviceControlBackend {
    override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession? {
        if (device.connectionKind != DeviceConnectionKind.USB || !engine.isInitialized()) return null
        return Session(engine, registry, device, generation)
    }

    private class Session(
        private val engine: UsbAudioEngine,
        private val registry: UsbVendorAdapterRegistry,
        override val device: DeviceControlDevice,
        override val generation: Long,
    ) : DeviceControlSession {
        private val _snapshot = MutableStateFlow(DeviceControlSnapshot(generation, device))
        private val transport = UsbAudioEngineVendorTransport(engine)
        override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

        @Volatile private var closed = false
        private var adapter: UsbVendorDeviceAdapter? = null
        private var context: UsbVendorAdapterContext? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writer = SerializedDeviceControlWriter(
            scope = scope,
            generation = generation,
            currentGeneration = { if (closed) Long.MIN_VALUE else generation },
            canExecute = { !closed && adapter != null && context != null && engine.isInitialized() },
            coalesceMs = 24L,
            execute = ::executeWrite,
        )

        override suspend fun probe() {
            if (closed) return
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.PROBING,
            )
            val inventory = UsbVendorControlInventoryParser.parse(engine.probeVendorControlInventoryJson())
            if (inventory == null) {
                _snapshot.value = error("usb_vendor_inventory_failed")
                return
            }
            val inventoryDiagnostics = inventory.toDiagnosticLines()
            if (!samePhysicalDevice(device, inventory.device)) {
                _snapshot.value = error("usb_vendor_device_identity_changed", inventoryDiagnostics)
                return
            }
            when (val match = registry.select(device, inventory)) {
                UsbVendorAdapterRegistry.Match.None -> {
                    adapter = null
                    context = null
                    _snapshot.value = DeviceControlSnapshot(
                        generation = generation,
                        device = device,
                        probeState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                        message = buildString {
                            append("usb_vendor_adapter_not_found")
                            if (inventory.extensionUnits.isNotEmpty()) append(";extension_units=${inventory.extensionUnits.size}")
                            if (inventory.hidInterfaces.isNotEmpty()) {
                                append(";hid_interfaces=${inventory.hidInterfaces.size}")
                                val dspCandidates = inventory.hidInterfaces.count { it.isLikelyDspHidCandidate }
                                val consumerControls = inventory.hidInterfaces.count {
                                    it.hidSemantic == UsbVendorControlInventory.HidSemantic.CONSUMER_CONTROL
                                }
                                append(";hid_dsp_candidates=$dspCandidates")
                                if (consumerControls > 0) append(";hid_consumer_controls=$consumerControls")
                            }
                            if (inventory.vendorInterfaces.isNotEmpty()) append(";vendor_interfaces=${inventory.vendorInterfaces.size}")
                        },
                        diagnostics = inventoryDiagnostics,
                    )
                }
                is UsbVendorAdapterRegistry.Match.Ambiguous -> {
                    adapter = null
                    context = null
                    _snapshot.value = error(
                        "usb_vendor_adapter_ambiguous:${match.adapterIds.joinToString(",")}",
                        inventoryDiagnostics,
                    )
                }
                is UsbVendorAdapterRegistry.Match.Selected -> {
                    val selectedContext = UsbVendorAdapterContext(device, generation, inventory, transport)
                    when (val result = runCatching { match.adapter.probe(selectedContext) }.getOrElse {
                        UsbVendorAdapterProbeResult.Failed("usb_vendor_probe_exception", it)
                    }) {
                        is UsbVendorAdapterProbeResult.Ready -> {
                            adapter = match.adapter
                            context = selectedContext
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = device,
                                capabilities = result.capabilities,
                                probeState = if (result.capabilities.isEmpty()) {
                                    DeviceControlSnapshot.ProbeState.UNSUPPORTED
                                } else DeviceControlSnapshot.ProbeState.READY,
                                message = result.message,
                                diagnostics = inventoryDiagnostics + result.diagnostics,
                            )
                        }
                        is UsbVendorAdapterProbeResult.Unsupported -> {
                            adapter = match.adapter
                            context = selectedContext
                            _snapshot.value = DeviceControlSnapshot(
                                generation = generation,
                                device = device,
                                probeState = DeviceControlSnapshot.ProbeState.UNSUPPORTED,
                                message = result.reason,
                                diagnostics = inventoryDiagnostics + result.diagnostics,
                            )
                        }
                        is UsbVendorAdapterProbeResult.Failed -> {
                            adapter = null
                            context = null
                            _snapshot.value = error(result.reason, inventoryDiagnostics + result.diagnostics)
                        }
                    }
                }
            }
        }

        override suspend fun refresh() = probe()

        override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            if (closed) return DeviceControlWriteResult.Rejected("control_session_closed")
            return writer.submit(request)
        }

        private suspend fun executeWrite(request: DeviceControlWriteRequest): DeviceControlWriteResult {
            val owner = adapter ?: return DeviceControlWriteResult.Rejected("usb_vendor_adapter_not_selected")
            val ctx = context ?: return DeviceControlWriteResult.Rejected("usb_vendor_context_missing")
            val resolved = _snapshot.value.resolveControl(request.controlId)
                ?: return DeviceControlWriteResult.Rejected("unknown_control:${request.controlId.value}")
            val address = resolved.address as? DeviceControlBackendAddress.UsbVendor
                ?: return DeviceControlWriteResult.Rejected("control_not_owned_by_usb_vendor")
            if (address.adapterId != owner.adapterId) {
                return DeviceControlWriteResult.Rejected("usb_vendor_adapter_address_mismatch")
            }
            return when (val result = owner.write(ctx, request, _snapshot.value)) {
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
            writer.close()
            adapter = null
            context = null
            _snapshot.value = DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.IDLE,
                message = "closed",
            )
            scope.cancel()
        }

        private fun error(reason: String, diagnostics: List<String> = emptyList()) = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.ERROR,
            message = reason,
            diagnostics = diagnostics,
        )

        private fun samePhysicalDevice(expected: DeviceControlDevice, actual: DeviceControlDevice): Boolean {
            if (expected.vendorId != null && actual.vendorId != null && expected.vendorId != actual.vendorId) return false
            if (expected.productId != null && actual.productId != null && expected.productId != actual.productId) return false
            return true
        }
    }
}
