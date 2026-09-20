package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class BluetoothVendorGattTarget(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val serviceInstanceIndex: Int = 0,
)

interface BluetoothVendorGattTransport {
    suspend fun read(target: BluetoothVendorGattTarget): Result<ByteArray>
    suspend fun write(target: BluetoothVendorGattTarget, value: ByteArray): Result<Unit>

    /** Explicit opt-in only: no unknown characteristic is subscribed automatically. */
    suspend fun subscribe(target: BluetoothVendorGattTarget): Result<Unit>
    suspend fun unsubscribe(target: BluetoothVendorGattTarget): Result<Unit>
    fun notifications(target: BluetoothVendorGattTarget): Flow<ByteArray>
}

/** Vendor protocol knowledge. Unknown GATT attributes must never be probed by guessing. */
interface BluetoothVendorGattAdapter {
    val adapterId: String

    /** 0 means no match. Higher values mean a more specific protocol fingerprint. */
    fun match(device: DeviceControlDevice, inventory: BluetoothGattInventory): Int

    suspend fun probe(context: BluetoothVendorGattAdapterContext): BluetoothVendorGattProbeResult

    suspend fun write(
        context: BluetoothVendorGattAdapterContext,
        request: DeviceControlWriteRequest,
        current: DeviceControlSnapshot,
    ): DeviceControlWriteResult = DeviceControlWriteResult.Rejected("bluetooth_vendor_write_not_implemented")

    /** Adapter must explicitly name every characteristic whose notifications it understands. */
    fun notificationTargets(
        context: BluetoothVendorGattAdapterContext,
        current: DeviceControlSnapshot,
    ): List<BluetoothVendorGattTarget> = emptyList()

    /** Decode one already-whitelisted notification into capability state or request a re-probe. */
    suspend fun onNotification(
        context: BluetoothVendorGattAdapterContext,
        target: BluetoothVendorGattTarget,
        value: ByteArray,
        current: DeviceControlSnapshot,
    ): BluetoothVendorGattNotificationResult = BluetoothVendorGattNotificationResult.Ignore
}

data class BluetoothVendorGattAdapterContext(
    val device: DeviceControlDevice,
    val generation: Long,
    val inventory: BluetoothGattInventory,
    val transport: BluetoothVendorGattTransport,
)

sealed interface BluetoothVendorGattProbeResult {
    data class Ready(
        val capabilities: List<DeviceControlCapability>,
        val message: String? = null,
    ) : BluetoothVendorGattProbeResult

    data class Unsupported(val reason: String) : BluetoothVendorGattProbeResult
    data class Failed(val reason: String, val cause: Throwable? = null) : BluetoothVendorGattProbeResult
}

sealed interface BluetoothVendorGattNotificationResult {
    data object Ignore : BluetoothVendorGattNotificationResult
    data object Refresh : BluetoothVendorGattNotificationResult
    data class Updated(
        val capabilities: List<DeviceControlCapability>,
        val message: String? = null,
    ) : BluetoothVendorGattNotificationResult
}

class BluetoothVendorGattAdapterRegistry(
    adapters: List<BluetoothVendorGattAdapter> = emptyList(),
) {
    private val adapters = adapters.toList()

    sealed interface Match {
        data class Selected(val adapter: BluetoothVendorGattAdapter, val score: Int) : Match
        data object None : Match
        data class Ambiguous(val adapterIds: List<String>, val score: Int) : Match
    }

    fun select(device: DeviceControlDevice, inventory: BluetoothGattInventory): Match {
        val candidates = adapters.mapNotNull { adapter ->
            val score = runCatching { adapter.match(device, inventory) }.getOrDefault(0)
            score.takeIf { it > 0 }?.let { adapter to it }
        }
        if (candidates.isEmpty()) return Match.None
        val topScore = candidates.maxOf { it.second }
        val top = candidates.filter { it.second == topScore }
        if (top.size != 1) return Match.Ambiguous(top.map { it.first.adapterId }.sorted(), topScore)
        return Match.Selected(top.single().first, topScore)
    }

    companion object {
        /** No wildcard/fallback vendor adapter is registered in production. */
        val Empty = BluetoothVendorGattAdapterRegistry()
    }
}
