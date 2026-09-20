package com.rawsmusic.module.player.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight session for route-scoped states that exist before a transport can be opened,
 * such as Android Nearby Devices permission being missing. Keeping this as a real session lets
 * DeviceControlManager surface a precise state instead of misreporting the device as unsupported.
 */
internal class FixedDeviceControlSession(
    override val device: DeviceControlDevice,
    override val generation: Long,
    initialState: DeviceControlSnapshot.ProbeState,
    initialMessage: String,
    initialDiagnostics: List<String> = emptyList(),
) : DeviceControlSession {
    private val _snapshot = MutableStateFlow(
        DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = initialState,
            message = initialMessage,
            diagnostics = initialDiagnostics,
        ),
    )
    override val snapshot: StateFlow<DeviceControlSnapshot> = _snapshot.asStateFlow()

    override suspend fun probe() = Unit
    override suspend fun refresh() = Unit

    override suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult =
        DeviceControlWriteResult.Rejected("control_transport_unavailable:${_snapshot.value.message.orEmpty()}")

    override suspend fun close() {
        _snapshot.value = DeviceControlSnapshot(
            generation = generation,
            device = device,
            probeState = DeviceControlSnapshot.ProbeState.IDLE,
            message = "closed",
        )
    }
}
