package com.rawsmusic.module.player.devicecontrol

import kotlinx.coroutines.flow.StateFlow

/**
 * Control-plane session for one physical device. It intentionally has no PCM/DoP/DSD API.
 */
interface DeviceControlSession {
    val device: DeviceControlDevice
    val generation: Long
    val snapshot: StateFlow<DeviceControlSnapshot>

    suspend fun probe()
    suspend fun refresh()
    suspend fun write(request: DeviceControlWriteRequest): DeviceControlWriteResult
    suspend fun close()
}

interface DeviceControlBackend {
    suspend fun open(device: DeviceControlDevice, generation: Long): DeviceControlSession?
}

sealed interface DeviceControlWriteValue {
    data class Number(val value: Double) : DeviceControlWriteValue
    data class Toggle(val enabled: Boolean) : DeviceControlWriteValue
    data class Choice(val value: String) : DeviceControlWriteValue
}

data class DeviceControlWriteRequest(
    val controlId: DeviceControlId,
    val value: DeviceControlWriteValue,
)

sealed interface DeviceControlWriteResult {
    data class Applied(val snapshot: DeviceControlSnapshot) : DeviceControlWriteResult
    data class Rejected(val reason: String) : DeviceControlWriteResult
    data class Failed(val reason: String, val cause: Throwable? = null) : DeviceControlWriteResult
}
