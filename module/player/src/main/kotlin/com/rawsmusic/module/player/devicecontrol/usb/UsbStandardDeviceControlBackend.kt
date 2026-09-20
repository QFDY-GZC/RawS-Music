package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSession
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteValue
import com.rawsmusic.module.player.devicecontrol.ResolvedDeviceControl
import com.rawsmusic.module.player.devicecontrol.SerializedDeviceControlWriter
import com.rawsmusic.module.player.devicecontrol.accepts
import com.rawsmusic.module.player.devicecontrol.resolveControl
import com.rawsmusic.module.player.usb.UsbAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Standard UAC control backend. Audio streaming remains owned entirely by [UsbAudioEngine]. */
class UsbStandardDeviceControlBackend(
    private val engine: UsbAudioEngine,
) : DeviceControlBackend {
    override suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession? {
        if (device.connectionKind != DeviceConnectionKind.USB || !engine.isInitialized()) return null
        return UsbStandardDeviceControlSession(engine, device, generation)
    }
}

internal class UsbStandardDeviceControlSession(
    private val engine: UsbAudioEngine,
    override val device: DeviceControlDevice,
    override val generation: Long,
) : DeviceControlSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(
        DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.IDLE,
        ),
    )
    override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var closed = false

    private val writer = SerializedDeviceControlWriter(
        scope = scope,
        generation = generation,
        currentGeneration = { if (closed) Long.MIN_VALUE else generation },
        canExecute = { !closed && engine.isInitialized() },
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
        val probed = engine.probeStandardHardwareControls(generation)
        _snapshot.value = when {
            probed == null -> DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.ERROR,
                message = "usb_standard_probe_failed",
            )
            !samePhysicalDevice(device, probed.device) -> DeviceControlSnapshot(
                generation = generation,
                device = device,
                probeState = DeviceControlSnapshot.ProbeState.ERROR,
                message = "usb_device_identity_changed",
            )
            else -> sanitizeUsbStandardSnapshot(probed.copy(device = device))
        }
    }

    override suspend fun refresh() = probe()

    override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        if (closed) return DeviceControlWriteResult.Rejected("control_session_closed")
        return writer.submit(request)
    }

    private suspend fun executeWrite(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        val before = _snapshot.value
        if (before.probeState != DeviceControlSnapshot.ProbeState.READY) {
            return DeviceControlWriteResult.Rejected("usb_standard_controls_not_ready")
        }
        val resolved = before.resolveControl(request.controlId)
            ?: return DeviceControlWriteResult.Rejected("unknown_control:${request.controlId.value}")
        if (!resolved.access.isWritable) {
            return DeviceControlWriteResult.Rejected("control_is_read_only:${request.controlId.value}")
        }
        val address = resolved.address as? DeviceControlBackendAddress.UsbAudioClass
            ?: return DeviceControlWriteResult.Rejected("control_not_owned_by_usb_uac")

        // Keep the mature dedicated Feature Unit volume policy as the sole owner for hardware
        // volume for now. Other standard UAC controls are enabled by Phase 3. The two paths can
        // be unified later without creating competing volume writers during this rollout.
        if (address.selector == 0x02 && request.controlId.value.startsWith("usb:uac:fu:")) {
            return DeviceControlWriteResult.Rejected("usb_feature_unit_volume_owned_by_existing_path")
        }

        val wireValue = when (resolved) {
            is ResolvedDeviceControl.Numeric -> {
                val value = (request.value as? DeviceControlWriteValue.Number)?.value
                    ?: return DeviceControlWriteResult.Rejected("numeric_control_requires_number")
                if (!resolved.control.accepts(value)) {
                    return DeviceControlWriteResult.Rejected("numeric_value_out_of_range")
                }
                value
            }
            is ResolvedDeviceControl.Toggle -> {
                val enabled = (request.value as? DeviceControlWriteValue.Toggle)?.enabled
                    ?: return DeviceControlWriteResult.Rejected("toggle_control_requires_toggle")
                if (enabled) 1.0 else 0.0
            }
            is ResolvedDeviceControl.Choice ->
                return DeviceControlWriteResult.Rejected("usb_standard_choice_write_not_supported")
        }

        return when (val native = engine.writeStandardHardwareControl(address, wireValue)) {
            is UsbStandardControlWriteResult.Applied -> {
                // Do a full capability readback instead of trusting requested/quantized values.
                // This also refreshes access/ranges if firmware changes them as a side effect.
                val readback = engine.probeStandardHardwareControls(generation)
                if (readback == null || !samePhysicalDevice(device, readback.device)) {
                    DeviceControlWriteResult.Failed("usb_write_applied_but_readback_probe_failed")
                } else {
                    val applied = sanitizeUsbStandardSnapshot(readback.copy(device = device))
                    _snapshot.value = applied
                    DeviceControlWriteResult.Applied(applied)
                }
            }
            is UsbStandardControlWriteResult.Rejected -> DeviceControlWriteResult.Rejected(native.reason)
            is UsbStandardControlWriteResult.Failed -> DeviceControlWriteResult.Failed(
                "usb_standard_write_failed:${native.reason}:${native.transportCode ?: 0}",
            )
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        writer.close()
        scope.cancel()
        _snapshot.value = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.IDLE,
            message = "closed",
        )
    }

    private fun sanitizeUsbStandardSnapshot(snapshot: DeviceControlSnapshot): DeviceControlSnapshot {
        // The existing UsbHardwareVolumeCoordinator remains the sole writer for Feature Unit
        // volume during this rollout. Expose that control here as read-only so the generic UI
        // never presents a slider that this session intentionally rejects. Mute and every other
        // standard UAC control keep the access reported by the descriptor/probe.
        val capabilities = snapshot.capabilities.map { capability ->
            if (capability !is DeviceControlCapability.Volume) return@map capability
            capability.copy(
                channels = capability.channels.map { control ->
                    val address = control.address as? DeviceControlBackendAddress.UsbAudioClass
                    if (address?.selector == 0x02 && control.id.value.startsWith("usb:uac:fu:")) {
                        control.copy(access = DeviceControlAccess.READ_ONLY)
                    } else {
                        control
                    }
                },
            )
        }
        return snapshot.copy(capabilities = capabilities)
    }

    private fun samePhysicalDevice(expected: DeviceControlDevice, actual: DeviceControlDevice?): Boolean {
        actual ?: return false
        if (expected.vendorId != null && actual.vendorId != null && expected.vendorId != actual.vendorId) return false
        if (expected.productId != null && actual.productId != null && expected.productId != actual.productId) return false
        return true
    }
}
