package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult

/**
 * A vendor adapter is protocol knowledge, not UI code. It may only match devices whose wire
 * protocol it understands. Returning a positive score is therefore a safety assertion.
 */
interface UsbVendorDeviceAdapter {
    val adapterId: String

    /** 0 means no match. Higher scores are more specific. Equal top scores are rejected. */
    fun match(device: DeviceControlDevice, inventory: UsbVendorControlInventory): Int

    suspend fun probe(context: UsbVendorAdapterContext): UsbVendorAdapterProbeResult

    suspend fun write(
        context: UsbVendorAdapterContext,
        request: DeviceControlWriteRequest,
        current: DeviceControlSnapshot,
    ): DeviceControlWriteResult = DeviceControlWriteResult.Rejected("usb_vendor_adapter_write_not_implemented")
}

data class UsbVendorAdapterContext(
    val device: DeviceControlDevice,
    val generation: Long,
    val inventory: UsbVendorControlInventory,
    val transport: UsbVendorTransport,
)

sealed interface UsbVendorAdapterProbeResult {
    data class Ready(
        val capabilities: List<DeviceControlCapability>,
        val message: String? = null,
        val diagnostics: List<String> = emptyList(),
    ) : UsbVendorAdapterProbeResult

    data class Unsupported(
        val reason: String,
        val diagnostics: List<String> = emptyList(),
    ) : UsbVendorAdapterProbeResult

    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
        val diagnostics: List<String> = emptyList(),
    ) : UsbVendorAdapterProbeResult
}

class UsbVendorAdapterRegistry(
    adapters: List<UsbVendorDeviceAdapter> = emptyList(),
) {
    private val adapters = adapters.toList()

    sealed interface Match {
        data class Selected(val adapter: UsbVendorDeviceAdapter, val score: Int) : Match
        data object None : Match
        data class Ambiguous(val adapterIds: List<String>, val score: Int) : Match
    }

    fun select(device: DeviceControlDevice, inventory: UsbVendorControlInventory): Match {
        val matches = adapters.mapNotNull { adapter ->
            val score = runCatching { adapter.match(device, inventory) }.getOrDefault(0)
            score.takeIf { it > 0 }?.let { adapter to it }
        }
        if (matches.isEmpty()) return Match.None
        val topScore = matches.maxOf { it.second }
        val top = matches.filter { it.second == topScore }
        if (top.size != 1) return Match.Ambiguous(top.map { it.first.adapterId }.sorted(), topScore)
        return Match.Selected(top.single().first, topScore)
    }

    companion object {
        /** Production starts empty: unknown vendor protocols are never guessed. */
        val Empty = UsbVendorAdapterRegistry()
    }
}
