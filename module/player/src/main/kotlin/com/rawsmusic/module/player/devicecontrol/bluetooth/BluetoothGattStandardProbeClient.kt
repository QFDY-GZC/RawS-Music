package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only probe of Bluetooth SIG-defined audio control characteristics.
 *
 * Safety rule: a characteristic is read only when [BluetoothStandardReadPlan] whitelists it.
 * There is deliberately no write API here and vendor characteristics are never read by this client.
 */
internal class BluetoothGattStandardProbeClient(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Ready(val data: BluetoothStandardProbeData) : Result
        data class Failed(val reason: String) : Result
        data object PermissionRequired : Result
    }

    private data class PendingRead(
        val target: BluetoothStandardReadPlan.Target,
        val characteristic: BluetoothGattCharacteristic,
    )

    suspend fun probe(device: BluetoothDevice): Result {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return Result.PermissionRequired
        }

        val completion = CompletableDeferred<Result>()
        val finished = AtomicBoolean(false)
        val lock = Any()
        val pending = ArrayDeque<PendingRead>()
        val values = mutableListOf<BluetoothStandardReadValue>()
        val diagnostics = mutableListOf<String>()
        var inventory: BluetoothGattInventory? = null
        var current: PendingRead? = null
        var gattRef: BluetoothGatt? = null

        fun completeOnce(result: Result) {
            if (finished.compareAndSet(false, true)) completion.complete(result)
        }

        fun finishReady() {
            val readyInventory = synchronized(lock) { inventory }
            if (readyInventory == null) {
                completeOnce(Result.Failed("gatt_inventory_missing"))
                return
            }
            val data = synchronized(lock) {
                BluetoothStandardProbeData(
                    inventory = readyInventory,
                    values = values.toList(),
                    diagnostics = diagnostics.toList(),
                )
            }
            completeOnce(Result.Ready(data))
        }

        fun startNextRead(gatt: BluetoothGatt) {
            while (!finished.get()) {
                val next = synchronized(lock) {
                    if (current != null) return
                    pending.pollFirst()?.also { current = it }
                }
                if (next == null) {
                    finishReady()
                    return
                }
                val accepted = runCatching { gatt.readCharacteristic(next.characteristic) }
                    .getOrDefault(false)
                if (accepted) return

                synchronized(lock) {
                    diagnostics += "read_rejected:${next.target.kind}:${next.target.serviceIndex}"
                    if (current === next) current = null
                }
            }
        }

        fun handleRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?,
            status: Int,
        ) {
            val completed = synchronized(lock) {
                val active = current
                if (active == null || active.characteristic !== characteristic) {
                    diagnostics += "unexpected_read_callback:${characteristic.uuid}"
                    null
                } else {
                    current = null
                    active
                }
            } ?: return

            synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    values += BluetoothStandardReadValue(completed.target, value.copyOf())
                } else {
                    diagnostics += "read_failed:${completed.target.kind}:status=$status"
                }
            }
            startNextRead(gatt)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        completeOnce(Result.Failed("gatt_connection_status_$status"))
                    }
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        if (!started) completeOnce(Result.Failed("gatt_discover_services_rejected"))
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        completeOnce(Result.Failed("gatt_disconnected_during_standard_probe"))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    completeOnce(Result.Failed("gatt_service_discovery_status_$status"))
                    return
                }
                val discovered = runCatching { buildBluetoothGattInventory(gatt) }
                    .getOrElse { error ->
                        completeOnce(Result.Failed("gatt_inventory_failed:${error.javaClass.simpleName}"))
                        return
                    }
                val plan = BluetoothStandardReadPlan.fromInventory(discovered)
                synchronized(lock) {
                    inventory = discovered
                    plan.forEach { target ->
                        val service = gatt.services.orEmpty().getOrNull(target.serviceIndex)
                        val characteristic = service
                            ?.takeIf { it.uuid == target.serviceUuid }
                            ?.characteristics
                            ?.firstOrNull { it.uuid == target.characteristicUuid }
                        if (characteristic == null) {
                            diagnostics += "planned_characteristic_missing:${target.kind}:${target.serviceIndex}"
                        } else {
                            pending += PendingRead(target, characteristic)
                        }
                    }
                }
                startNextRead(gatt)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    handleRead(gatt, characteristic, characteristic.value?.copyOf(), status)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleRead(gatt, characteristic, value, status)
            }
        }

        return try {
            val openedGatt = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(appContext, false, callback)
                }
            }.getOrElse { error ->
                return Result.Failed("gatt_connect_failed:${error.javaClass.simpleName}")
            } ?: return Result.Failed("gatt_connect_returned_null")
            gattRef = openedGatt
            withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: Result.Failed("gatt_standard_probe_timeout")
        } finally {
            val gatt = gattRef
            if (gatt != null) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 12_000L
    }
}
