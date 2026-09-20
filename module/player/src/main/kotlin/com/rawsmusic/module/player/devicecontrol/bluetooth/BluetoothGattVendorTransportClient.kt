package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serialized GATT transport used only after a concrete vendor adapter has matched the device.
 * It has no discovery heuristics and no API that iterates/reads arbitrary characteristics.
 */
internal class BluetoothGattVendorTransportClient(
    context: Context,
    private val device: BluetoothDevice,
    private val transport: Int? = BluetoothDevice.TRANSPORT_LE,
    private val autoConnect: Boolean = false,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : BluetoothVendorGattTransport {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val stateLock = Any()

    sealed interface Event {
        data class Disconnected(val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private data class ReadCompletion(val value: ByteArray?, val status: Int)
    private data class WriteCompletion(val status: Int)
    private data class DescriptorCompletion(val status: Int)

    private var gattRef: BluetoothGatt? = null
    private var servicesReady = false
    private var connectDeferred: CompletableDeferred<String>? = null
    private var pendingReadCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingRead: CompletableDeferred<ReadCompletion>? = null
    private var pendingWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<WriteCompletion>? = null
    private var pendingDescriptor: BluetoothGattDescriptor? = null
    private var pendingDescriptorWrite: CompletableDeferred<DescriptorCompletion>? = null
    private val notificationTargetsByCharacteristic = IdentityHashMap<BluetoothGattCharacteristic, BluetoothVendorGattTarget>()
    private val notificationFlows = ConcurrentHashMap<BluetoothVendorGattTarget, MutableSharedFlow<ByteArray>>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    synchronized(stateLock) {
                        servicesReady = false
                        connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_connection_status_$status")
                        failPendingLocked()
                    }
                    _events.tryEmit(Event.Disconnected("gatt_connection_status_$status"))
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                    if (!started) synchronized(stateLock) {
                        connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_discover_services_rejected")
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(stateLock) {
                        servicesReady = false
                        connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_disconnected_before_ready")
                        failPendingLocked()
                    }
                    _events.tryEmit(Event.Disconnected("gatt_disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(stateLock) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesReady = true
                    connectDeferred?.takeIf { !it.isCompleted }?.complete("")
                } else {
                    servicesReady = false
                    connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_service_discovery_status_$status")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(characteristic, characteristic.value?.copyOf(), status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(characteristic, value.copyOf(), status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(stateLock) {
                if (pendingWriteCharacteristic !== characteristic) return
                pendingWriteCharacteristic = null
                pendingWrite?.takeIf { !it.isCompleted }?.complete(WriteCompletion(status))
                pendingWrite = null
            }
        }


        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(stateLock) {
                if (pendingDescriptor !== descriptor) return
                pendingDescriptor = null
                pendingDescriptorWrite?.takeIf { !it.isCompleted }?.complete(DescriptorCompletion(status))
                pendingDescriptorWrite = null
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(characteristic, characteristic.value?.copyOf() ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(characteristic, value.copyOf())
        }
    }

    override suspend fun read(target: BluetoothVendorGattTarget): Result<ByteArray> = mutex.withLock {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@withLock Result.failure(IllegalStateException("bluetooth_connect_permission_required"))
        }
        val error = ensureConnectedLocked()
        if (error != null) return@withLock Result.failure(IllegalStateException(error))
        val characteristic = findCharacteristicLocked(target)
            ?: return@withLock Result.failure(IllegalArgumentException("vendor_characteristic_missing"))
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            return@withLock Result.failure(IllegalAccessException("vendor_characteristic_not_readable"))
        }
        val completion = readCharacteristicLocked(characteristic)
            ?: return@withLock Result.failure(IllegalStateException("vendor_gatt_read_timeout"))
        if (completion.status != BluetoothGatt.GATT_SUCCESS || completion.value == null) {
            return@withLock Result.failure(IllegalStateException("vendor_gatt_read_status_${completion.status}"))
        }
        Result.success(completion.value)
    }

    override suspend fun write(target: BluetoothVendorGattTarget, value: ByteArray): Result<Unit> = mutex.withLock {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@withLock Result.failure(IllegalStateException("bluetooth_connect_permission_required"))
        }
        val error = ensureConnectedLocked()
        if (error != null) return@withLock Result.failure(IllegalStateException(error))
        val characteristic = findCharacteristicLocked(target)
            ?: return@withLock Result.failure(IllegalArgumentException("vendor_characteristic_missing"))
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            return@withLock Result.failure(IllegalAccessException("vendor_characteristic_not_write_request"))
        }
        val completion = writeCharacteristicLocked(characteristic, value)
            ?: return@withLock Result.failure(IllegalStateException("vendor_gatt_write_timeout"))
        if (completion.status != BluetoothGatt.GATT_SUCCESS) {
            return@withLock Result.failure(IllegalStateException("vendor_gatt_write_status_${completion.status}"))
        }
        Result.success(Unit)
    }

    override suspend fun subscribe(target: BluetoothVendorGattTarget): Result<Unit> = mutex.withLock {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@withLock Result.failure(IllegalStateException("bluetooth_connect_permission_required"))
        }
        val error = ensureConnectedLocked()
        if (error != null) return@withLock Result.failure(IllegalStateException(error))
        val characteristic = findCharacteristicLocked(target)
            ?: return@withLock Result.failure(IllegalArgumentException("vendor_characteristic_missing"))
        val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) {
            return@withLock Result.failure(IllegalAccessException("vendor_characteristic_not_notifiable"))
        }
        val gatt = gattRef ?: return@withLock Result.failure(IllegalStateException("gatt_not_connected"))
        val cccd = characteristic.getDescriptor(CCCD_UUID)
            ?: return@withLock Result.failure(IllegalStateException("vendor_cccd_missing"))
        if (!runCatching { gatt.setCharacteristicNotification(characteristic, true) }.getOrDefault(false)) {
            return@withLock Result.failure(IllegalStateException("vendor_set_notification_rejected"))
        }
        val value = if (supportsIndicate && !supportsNotify) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val completion = writeDescriptorLocked(cccd, value)
            ?: return@withLock Result.failure(IllegalStateException("vendor_cccd_write_timeout"))
        if (completion.status != BluetoothGatt.GATT_SUCCESS) {
            return@withLock Result.failure(IllegalStateException("vendor_cccd_write_status_${completion.status}"))
        }
        synchronized(stateLock) { notificationTargetsByCharacteristic[characteristic] = target }
        notificationFlows.computeIfAbsent(target) { MutableSharedFlow(extraBufferCapacity = 16) }
        Result.success(Unit)
    }

    override suspend fun unsubscribe(target: BluetoothVendorGattTarget): Result<Unit> = mutex.withLock {
        val error = ensureConnectedLocked()
        if (error != null) return@withLock Result.failure(IllegalStateException(error))
        val characteristic = findCharacteristicLocked(target)
            ?: return@withLock Result.failure(IllegalArgumentException("vendor_characteristic_missing"))
        val gatt = gattRef ?: return@withLock Result.failure(IllegalStateException("gatt_not_connected"))
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val completion = writeDescriptorLocked(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                ?: return@withLock Result.failure(IllegalStateException("vendor_cccd_disable_timeout"))
            if (completion.status != BluetoothGatt.GATT_SUCCESS) {
                return@withLock Result.failure(IllegalStateException("vendor_cccd_disable_status_${completion.status}"))
            }
        }
        runCatching { gatt.setCharacteristicNotification(characteristic, false) }
        synchronized(stateLock) { notificationTargetsByCharacteristic.remove(characteristic) }
        Result.success(Unit)
    }

    override fun notifications(target: BluetoothVendorGattTarget): Flow<ByteArray> =
        notificationFlows[target] ?: emptyFlow()

    suspend fun close() = mutex.withLock { closeLocked() }

    private suspend fun ensureConnectedLocked(): String? {
        if (synchronized(stateLock) { servicesReady && gattRef != null }) return null
        closeLocked()
        val deferred = CompletableDeferred<String>()
        synchronized(stateLock) { connectDeferred = deferred }
        val opened = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && transport != null -> {
                    device.connectGatt(appContext, autoConnect, callback, transport)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    device.connectGatt(appContext, autoConnect, callback)
                }
            }
        }.getOrElse { error ->
            synchronized(stateLock) { connectDeferred = null }
            return "gatt_connect_failed:${error.javaClass.simpleName}"
        } ?: run {
            synchronized(stateLock) { connectDeferred = null }
            return "gatt_connect_returned_null"
        }
        synchronized(stateLock) { gattRef = opened }
        val error = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: "gatt_connect_timeout"
        synchronized(stateLock) { if (connectDeferred === deferred) connectDeferred = null }
        if (error.isNotEmpty()) {
            closeLocked()
            return error
        }
        return null
    }

    private fun findCharacteristicLocked(target: BluetoothVendorGattTarget): BluetoothGattCharacteristic? {
        if (target.serviceInstanceIndex < 0) return null
        val service = gattRef?.services.orEmpty()
            .filter { it.uuid == target.serviceUuid }
            .getOrNull(target.serviceInstanceIndex)
            ?: return null
        return service.characteristics.orEmpty().firstOrNull { it.uuid == target.characteristicUuid }
    }

    private suspend fun readCharacteristicLocked(characteristic: BluetoothGattCharacteristic): ReadCompletion? {
        val gatt = gattRef ?: return null
        val deferred = CompletableDeferred<ReadCompletion>()
        synchronized(stateLock) {
            if (pendingRead != null || pendingWrite != null) return null
            pendingReadCharacteristic = characteristic
            pendingRead = deferred
        }
        val started = runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
        if (!started) {
            synchronized(stateLock) {
                pendingReadCharacteristic = null
                pendingRead = null
            }
            return ReadCompletion(null, STATUS_LOCAL_REJECTED)
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(stateLock) {
            if (pendingRead === deferred) {
                pendingRead = null
                pendingReadCharacteristic = null
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun writeCharacteristicLocked(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): WriteCompletion? {
        val gatt = gattRef ?: return null
        val deferred = CompletableDeferred<WriteCompletion>()
        synchronized(stateLock) {
            if (pendingRead != null || pendingWrite != null) return null
            pendingWriteCharacteristic = characteristic
            pendingWrite = deferred
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value.copyOf()
        val started = runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
        if (!started) {
            synchronized(stateLock) {
                pendingWriteCharacteristic = null
                pendingWrite = null
            }
            return WriteCompletion(STATUS_LOCAL_REJECTED)
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(stateLock) {
            if (pendingWrite === deferred) {
                pendingWrite = null
                pendingWriteCharacteristic = null
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun writeDescriptorLocked(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): DescriptorCompletion? {
        val gatt = gattRef ?: return null
        val deferred = CompletableDeferred<DescriptorCompletion>()
        synchronized(stateLock) {
            if (pendingRead != null || pendingWrite != null || pendingDescriptorWrite != null) return null
            pendingDescriptor = descriptor
            pendingDescriptorWrite = deferred
        }
        descriptor.value = value.copyOf()
        val started = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        if (!started) {
            synchronized(stateLock) {
                pendingDescriptor = null
                pendingDescriptorWrite = null
            }
            return DescriptorCompletion(STATUS_LOCAL_REJECTED)
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(stateLock) {
            if (pendingDescriptorWrite === deferred) {
                pendingDescriptorWrite = null
                pendingDescriptor = null
            }
        }
        return result
    }

    private fun dispatchNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val target = synchronized(stateLock) { notificationTargetsByCharacteristic[characteristic] } ?: return
        notificationFlows.computeIfAbsent(target) { MutableSharedFlow(extraBufferCapacity = 16) }
            .tryEmit(value)
    }

    private fun completeRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ) {
        synchronized(stateLock) {
            if (pendingReadCharacteristic !== characteristic) return
            pendingReadCharacteristic = null
            pendingRead?.takeIf { !it.isCompleted }?.complete(ReadCompletion(value, status))
            pendingRead = null
        }
    }

    private fun failPendingLocked() {
        pendingRead?.takeIf { !it.isCompleted }?.complete(ReadCompletion(null, STATUS_DISCONNECTED))
        pendingWrite?.takeIf { !it.isCompleted }?.complete(WriteCompletion(STATUS_DISCONNECTED))
        pendingRead = null
        pendingReadCharacteristic = null
        pendingWrite = null
        pendingWriteCharacteristic = null
        pendingDescriptorWrite?.takeIf { !it.isCompleted }?.complete(DescriptorCompletion(STATUS_DISCONNECTED))
        pendingDescriptorWrite = null
        pendingDescriptor = null
    }

    private fun closeLocked() {
        val gatt = synchronized(stateLock) {
            servicesReady = false
            failPendingLocked()
            connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_closed")
            connectDeferred = null
            notificationTargetsByCharacteristic.clear()
            notificationFlows.clear()
            gattRef.also { gattRef = null }
        }
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val STATUS_LOCAL_REJECTED = -10_001
        private const val STATUS_DISCONNECTED = -10_002
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
