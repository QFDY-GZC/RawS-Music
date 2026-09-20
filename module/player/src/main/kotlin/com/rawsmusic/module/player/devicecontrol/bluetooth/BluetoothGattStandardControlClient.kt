package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persistent, serialized GATT client for Bluetooth SIG VCS/VOCS/AICS.
 *
 * Every control-point write reads the current State characteristic immediately before encoding
 * the command, so the Change_Counter is fresh. A server-side Invalid Change Counter (0x80) is
 * retried once after another State read. After a successful write the standard read whitelist is
 * probed again and returned as authoritative readback.
 */
internal class BluetoothGattStandardControlClient(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val stateLock = Any()

    sealed interface Command {
        data class VcsSetVolume(val volumeSetting: Int) : Command
        data class VcsSetMute(val muted: Boolean) : Command
        data class VocsSetOffset(val offset: Int) : Command
        data class AicsSetGainDb(val gainDb: Double) : Command
        data class AicsSetMute(val muted: Boolean) : Command
        data class AicsSetGainMode(val automatic: Boolean) : Command
    }

    sealed interface Result {
        data class Ready(val data: BluetoothStandardProbeData) : Result
        data class Applied(val data: BluetoothStandardProbeData) : Result
        data class Rejected(val reason: String) : Result
        data class Failed(val reason: String) : Result
        data object PermissionRequired : Result
    }

    data class NotificationTarget(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val serviceInstanceIndex: Int,
    )

    sealed interface Event {
        data class StateChanged(val target: NotificationTarget) : Event
        data class Disconnected(val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private data class ReadCompletion(val value: ByteArray?, val status: Int)
    private data class WriteCompletion(val status: Int)
    private data class DescriptorCompletion(val status: Int)

    private var gattRef: BluetoothGatt? = null
    private var connectedDeviceAddress: String? = null
    private var servicesReady = false
    private var connectDeferred: CompletableDeferred<String>? = null
    private var pendingReadCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingRead: CompletableDeferred<ReadCompletion>? = null
    private var pendingWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<WriteCompletion>? = null
    private var pendingDescriptor: BluetoothGattDescriptor? = null
    private var pendingDescriptorWrite: CompletableDeferred<DescriptorCompletion>? = null
    private val notificationTargetsByCharacteristic =
        IdentityHashMap<BluetoothGattCharacteristic, NotificationTarget>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    synchronized(stateLock) {
                        servicesReady = false
                        connectDeferred?.takeIf { !it.isCompleted }
                            ?.complete("gatt_connection_status_$status")
                        failPendingLocked()
                    }
                    _events.tryEmit(Event.Disconnected("gatt_connection_status_$status"))
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                    if (!started) synchronized(stateLock) {
                        connectDeferred?.takeIf { !it.isCompleted }
                            ?.complete("gatt_discover_services_rejected")
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(stateLock) {
                        servicesReady = false
                        connectDeferred?.takeIf { !it.isCompleted }
                            ?.complete("gatt_disconnected_before_ready")
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
                    connectDeferred?.takeIf { !it.isCompleted }
                        ?.complete("gatt_service_discovery_status_$status")
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            synchronized(stateLock) {
                if (pendingDescriptor !== descriptor) return
                pendingDescriptor = null
                pendingDescriptorWrite?.takeIf { !it.isCompleted }
                    ?.complete(DescriptorCompletion(status))
                pendingDescriptorWrite = null
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(characteristic)
        }
    }

    suspend fun probe(device: BluetoothDevice): Result = operationMutex.withLock {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@withLock Result.PermissionRequired
        }
        val connectionError = ensureConnectedLocked(device)
        if (connectionError != null) return@withLock Result.Failed(connectionError)
        when (val probe = probeLocked()) {
            is ProbeResult.Ready -> {
                val subscriptionDiagnostics = configureStandardNotificationsLocked(probe.data.inventory)
                Result.Ready(
                    probe.data.copy(diagnostics = probe.data.diagnostics + subscriptionDiagnostics),
                )
            }
            is ProbeResult.Failed -> Result.Failed(probe.reason)
        }
    }

    suspend fun write(
        device: BluetoothDevice,
        address: DeviceControlBackendAddress.BluetoothStandard,
        command: Command,
    ): Result = operationMutex.withLock {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return@withLock Result.PermissionRequired
        }
        val connectionError = ensureConnectedLocked(device)
        if (connectionError != null) return@withLock Result.Failed(connectionError)

        val serviceUuid = runCatching { UUID.fromString(address.serviceUuid) }.getOrNull()
            ?: return@withLock Result.Rejected("invalid_standard_service_uuid")
        val controlPointUuid = runCatching { UUID.fromString(address.characteristicUuid) }.getOrNull()
            ?: return@withLock Result.Rejected("invalid_standard_characteristic_uuid")
        val service = findServiceInstanceLocked(serviceUuid, address.serviceInstanceIndex)
            ?: return@withLock Result.Rejected("standard_service_instance_missing")
        val controlPoint = service.characteristics.orEmpty().firstOrNull { it.uuid == controlPointUuid }
            ?: return@withLock Result.Rejected("standard_control_point_missing")
        if (controlPoint.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            return@withLock Result.Rejected("standard_control_point_not_writable")
        }

        val expectedControlPoint = expectedControlPoint(command)
        if (serviceUuid != expectedService(command) || controlPointUuid != expectedControlPoint) {
            return@withLock Result.Rejected("standard_command_address_mismatch")
        }

        var attempt = 0
        while (attempt < 2) {
            val payloadResult = buildPayloadLocked(service, command)
            when (payloadResult) {
                is PayloadResult.Rejected -> return@withLock Result.Rejected(payloadResult.reason)
                is PayloadResult.Failed -> return@withLock Result.Failed(payloadResult.reason)
                is PayloadResult.Ready -> {
                    val write = writeCharacteristicLocked(controlPoint, payloadResult.payload)
                        ?: return@withLock Result.Failed("standard_control_point_write_timeout")
                    if (write.status == BluetoothGatt.GATT_SUCCESS) {
                        return@withLock when (val readback = probeLocked()) {
                            is ProbeResult.Ready -> Result.Applied(readback.data)
                            is ProbeResult.Failed -> Result.Failed(
                                "standard_write_applied_readback_failed:${readback.reason}",
                            )
                        }
                    }
                    if (write.status == ATT_ERROR_INVALID_CHANGE_COUNTER && attempt == 0) {
                        attempt++
                        continue
                    }
                    return@withLock Result.Failed("standard_control_point_write_status_${write.status}")
                }
            }
        }
        Result.Failed("standard_control_point_retry_exhausted")
    }

    suspend fun close() = operationMutex.withLock {
        closeLocked()
    }

    private sealed interface ProbeResult {
        data class Ready(val data: BluetoothStandardProbeData) : ProbeResult
        data class Failed(val reason: String) : ProbeResult
    }

    private sealed interface PayloadResult {
        data class Ready(val payload: ByteArray) : PayloadResult
        data class Rejected(val reason: String) : PayloadResult
        data class Failed(val reason: String) : PayloadResult
    }

    private suspend fun probeLocked(): ProbeResult {
        val gatt = gattRef ?: return ProbeResult.Failed("gatt_not_connected")
        if (!servicesReady) return ProbeResult.Failed("gatt_services_not_ready")
        val inventory = runCatching { buildBluetoothGattInventory(gatt) }
            .getOrElse { return ProbeResult.Failed("gatt_inventory_failed:${it.javaClass.simpleName}") }
        val diagnostics = mutableListOf<String>()
        val values = mutableListOf<BluetoothStandardReadValue>()
        BluetoothStandardReadPlan.fromInventory(inventory).forEach { target ->
            val characteristic = gatt.services.orEmpty().getOrNull(target.serviceIndex)
                ?.takeIf { it.uuid == target.serviceUuid }
                ?.characteristics
                ?.firstOrNull { it.uuid == target.characteristicUuid }
            if (characteristic == null) {
                diagnostics += "planned_characteristic_missing:${target.kind}:${target.serviceIndex}"
                return@forEach
            }
            val read = readCharacteristicLocked(characteristic)
            if (read == null) {
                diagnostics += "read_timeout:${target.kind}:${target.serviceIndex}"
            } else if (read.status == BluetoothGatt.GATT_SUCCESS && read.value != null) {
                values += BluetoothStandardReadValue(target, read.value.copyOf())
            } else {
                diagnostics += "read_failed:${target.kind}:status=${read.status}"
            }
        }
        return ProbeResult.Ready(BluetoothStandardProbeData(inventory, values, diagnostics))
    }

    private suspend fun buildPayloadLocked(
        service: android.bluetooth.BluetoothGattService,
        command: Command,
    ): PayloadResult {
        return when (command) {
            is Command.VcsSetVolume,
            is Command.VcsSetMute -> {
                val stateCharacteristic = service.characteristics.orEmpty()
                    .firstOrNull { it.uuid == BluetoothLeAudioStandard.VOLUME_STATE }
                    ?: return PayloadResult.Rejected("vcs_volume_state_missing")
                val read = readCharacteristicLocked(stateCharacteristic)
                    ?: return PayloadResult.Failed("vcs_state_read_timeout")
                if (read.status != BluetoothGatt.GATT_SUCCESS || read.value == null) {
                    return PayloadResult.Failed("vcs_state_read_status_${read.status}")
                }
                val state = BluetoothLeAudioStandard.decodeVcsVolumeState(read.value)
                    ?: return PayloadResult.Failed("vcs_state_decode_failed")
                val payload = when (command) {
                    is Command.VcsSetVolume -> BluetoothLeAudioStandard.encodeVcsSetAbsoluteVolume(
                        state.changeCounter,
                        command.volumeSetting,
                    )
                    is Command.VcsSetMute -> BluetoothLeAudioStandard.encodeVcsMute(
                        state.changeCounter,
                        command.muted,
                    )
                    else -> null
                } ?: return PayloadResult.Rejected("vcs_value_out_of_range")
                PayloadResult.Ready(payload)
            }
            is Command.VocsSetOffset -> {
                val stateCharacteristic = service.characteristics.orEmpty()
                    .firstOrNull { it.uuid == BluetoothLeAudioStandard.VOLUME_OFFSET_STATE }
                    ?: return PayloadResult.Rejected("vocs_volume_offset_state_missing")
                val read = readCharacteristicLocked(stateCharacteristic)
                    ?: return PayloadResult.Failed("vocs_state_read_timeout")
                if (read.status != BluetoothGatt.GATT_SUCCESS || read.value == null) {
                    return PayloadResult.Failed("vocs_state_read_status_${read.status}")
                }
                val state = BluetoothLeAudioStandard.decodeVocsVolumeOffsetState(read.value)
                    ?: return PayloadResult.Failed("vocs_state_decode_failed")
                val payload = BluetoothLeAudioStandard.encodeVocsSetVolumeOffset(
                    state.changeCounter,
                    command.offset,
                ) ?: return PayloadResult.Rejected("vocs_offset_out_of_range")
                PayloadResult.Ready(payload)
            }
            is Command.AicsSetGainDb,
            is Command.AicsSetMute,
            is Command.AicsSetGainMode -> {
                val stateCharacteristic = service.characteristics.orEmpty()
                    .firstOrNull { it.uuid == BluetoothLeAudioStandard.AUDIO_INPUT_STATE }
                    ?: return PayloadResult.Rejected("aics_input_state_missing")
                val read = readCharacteristicLocked(stateCharacteristic)
                    ?: return PayloadResult.Failed("aics_state_read_timeout")
                if (read.status != BluetoothGatt.GATT_SUCCESS || read.value == null) {
                    return PayloadResult.Failed("aics_state_read_status_${read.status}")
                }
                val state = BluetoothLeAudioStandard.decodeAicsInputState(read.value)
                    ?: return PayloadResult.Failed("aics_state_decode_failed")
                val payload = when (command) {
                    is Command.AicsSetGainDb -> {
                        if (state.gainMode !in setOf(
                                BluetoothLeAudioStandard.AicsGainMode.MANUAL_ONLY,
                                BluetoothLeAudioStandard.AicsGainMode.MANUAL,
                            )
                        ) return PayloadResult.Rejected("aics_gain_not_writable_in_current_mode")
                        val propertiesCharacteristic = service.characteristics.orEmpty()
                            .firstOrNull { it.uuid == BluetoothLeAudioStandard.GAIN_SETTING_PROPERTIES }
                            ?: return PayloadResult.Rejected("aics_gain_properties_missing")
                        val propertiesRead = readCharacteristicLocked(propertiesCharacteristic)
                            ?: return PayloadResult.Failed("aics_gain_properties_read_timeout")
                        if (propertiesRead.status != BluetoothGatt.GATT_SUCCESS || propertiesRead.value == null) {
                            return PayloadResult.Failed("aics_gain_properties_status_${propertiesRead.status}")
                        }
                        val properties = BluetoothLeAudioStandard.decodeAicsGainSettingProperties(propertiesRead.value)
                            ?: return PayloadResult.Failed("aics_gain_properties_decode_failed")
                        val raw = properties.dbToRaw(command.gainDb)
                            ?: return PayloadResult.Rejected("aics_gain_out_of_range")
                        BluetoothLeAudioStandard.encodeAicsSetGain(state.changeCounter, raw)
                    }
                    is Command.AicsSetMute -> {
                        if (state.mute == BluetoothLeAudioStandard.AicsMuteState.DISABLED) {
                            return PayloadResult.Rejected("aics_mute_disabled")
                        }
                        BluetoothLeAudioStandard.encodeAicsMute(state.changeCounter, command.muted)
                    }
                    is Command.AicsSetGainMode -> {
                        if (state.gainMode !in setOf(
                                BluetoothLeAudioStandard.AicsGainMode.MANUAL,
                                BluetoothLeAudioStandard.AicsGainMode.AUTOMATIC,
                            )
                        ) return PayloadResult.Rejected("aics_gain_mode_fixed")
                        BluetoothLeAudioStandard.encodeAicsGainMode(state.changeCounter, command.automatic)
                    }
                    else -> null
                } ?: return PayloadResult.Rejected("aics_command_encode_failed")
                PayloadResult.Ready(payload)
            }
        }
    }

    private fun expectedService(command: Command): UUID = when (command) {
        is Command.VcsSetVolume, is Command.VcsSetMute -> BluetoothLeAudioStandard.VCS_SERVICE
        is Command.VocsSetOffset -> BluetoothLeAudioStandard.VOCS_SERVICE
        is Command.AicsSetGainDb, is Command.AicsSetMute, is Command.AicsSetGainMode ->
            BluetoothLeAudioStandard.AICS_SERVICE
    }

    private fun expectedControlPoint(command: Command): UUID = when (command) {
        is Command.VcsSetVolume, is Command.VcsSetMute -> BluetoothLeAudioStandard.VOLUME_CONTROL_POINT
        is Command.VocsSetOffset -> BluetoothLeAudioStandard.VOLUME_OFFSET_CONTROL_POINT
        is Command.AicsSetGainDb, is Command.AicsSetMute, is Command.AicsSetGainMode ->
            BluetoothLeAudioStandard.AUDIO_INPUT_CONTROL_POINT
    }

    private suspend fun ensureConnectedLocked(device: BluetoothDevice): String? {
        val current = synchronized(stateLock) {
            if (servicesReady && connectedDeviceAddress.equals(device.address, ignoreCase = true)) gattRef else null
        }
        if (current != null) return null
        closeLocked()
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return "bluetooth_connect_permission_required"
        }

        val deferred = CompletableDeferred<String>()
        synchronized(stateLock) {
            connectDeferred = deferred
            connectedDeviceAddress = device.address
        }
        val opened = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    device.connectGatt(
                        appContext,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK,
                        Handler(Looper.getMainLooper()),
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    device.connectGatt(appContext, false, callback)
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
            // Let the platform unregister the failed GATT client before a vendor inventory/backend
            // immediately attempts another connection to the same dual-mode earbud.
            delay(GATT_CLIENT_CLEANUP_DELAY_MS)
            return error
        }
        return null
    }

    private fun findServiceInstanceLocked(
        uuid: UUID,
        instance: Int,
    ): android.bluetooth.BluetoothGattService? {
        if (instance < 0) return null
        return gattRef?.services.orEmpty().filter { it.uuid == uuid }.getOrNull(instance)
    }

    private suspend fun readCharacteristicLocked(
        characteristic: BluetoothGattCharacteristic,
    ): ReadCompletion? {
        val gatt = gattRef ?: return null
        val deferred = CompletableDeferred<ReadCompletion>()
        synchronized(stateLock) {
            if (pendingRead != null || pendingWrite != null || pendingDescriptorWrite != null) return null
            pendingReadCharacteristic = characteristic
            pendingRead = deferred
        }
        val accepted = runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
        if (!accepted) {
            synchronized(stateLock) {
                if (pendingRead === deferred) {
                    pendingRead = null
                    pendingReadCharacteristic = null
                }
            }
            return ReadCompletion(null, STATUS_OPERATION_REJECTED)
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
        payload: ByteArray,
    ): WriteCompletion? {
        val gatt = gattRef ?: return null
        val deferred = CompletableDeferred<WriteCompletion>()
        synchronized(stateLock) {
            if (pendingRead != null || pendingWrite != null || pendingDescriptorWrite != null) return null
            pendingWriteCharacteristic = characteristic
            pendingWrite = deferred
        }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == 0
            }.getOrDefault(false)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload.copyOf()
            runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
        }
        if (!accepted) {
            synchronized(stateLock) {
                if (pendingWrite === deferred) {
                    pendingWrite = null
                    pendingWriteCharacteristic = null
                }
            }
            return WriteCompletion(STATUS_OPERATION_REJECTED)
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

    private suspend fun configureStandardNotificationsLocked(
        inventory: BluetoothGattInventory,
    ): List<String> {
        val gatt = gattRef ?: return listOf("notification_gatt_not_connected")
        val diagnostics = mutableListOf<String>()
        BluetoothStandardNotificationPlan.fromInventory(inventory).forEach { target ->
            val service = gatt.services.orEmpty().getOrNull(target.serviceIndex)
                ?.takeIf { it.uuid == target.serviceUuid } ?: return@forEach
            val characteristic = service.characteristics.orEmpty()
                .firstOrNull { it.uuid == target.characteristicUuid } ?: return@forEach
            if (synchronized(stateLock) { notificationTargetsByCharacteristic.containsKey(characteristic) }) {
                return@forEach
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                diagnostics += "notification_cccd_missing:${target.kind}:${target.serviceIndex}"
                return@forEach
            }
            val localEnabled = runCatching {
                gatt.setCharacteristicNotification(characteristic, true)
            }.getOrDefault(false)
            if (!localEnabled) {
                diagnostics += "notification_local_enable_rejected:${target.kind}:${target.serviceIndex}"
                return@forEach
            }
            val cccdValue = when (target.mode) {
                BluetoothStandardNotificationPlan.Mode.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                BluetoothStandardNotificationPlan.Mode.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val completion = writeDescriptorLocked(cccd, cccdValue)
            if (completion == null) {
                diagnostics += "notification_cccd_timeout:${target.kind}:${target.serviceIndex}"
                runCatching { gatt.setCharacteristicNotification(characteristic, false) }
                return@forEach
            }
            if (completion.status != BluetoothGatt.GATT_SUCCESS) {
                diagnostics += "notification_cccd_status_${completion.status}:${target.kind}:${target.serviceIndex}"
                runCatching { gatt.setCharacteristicNotification(characteristic, false) }
                return@forEach
            }
            val serviceInstance = inventory.services.take(target.serviceIndex)
                .count { it.uuid == target.serviceUuid }
            synchronized(stateLock) {
                notificationTargetsByCharacteristic[characteristic] = NotificationTarget(
                    serviceUuid = target.serviceUuid,
                    characteristicUuid = target.characteristicUuid,
                    serviceInstanceIndex = serviceInstance,
                )
            }
        }
        return diagnostics
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
        val accepted = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        if (!accepted) {
            synchronized(stateLock) {
                if (pendingDescriptorWrite === deferred) {
                    pendingDescriptor = null
                    pendingDescriptorWrite = null
                }
            }
            return DescriptorCompletion(STATUS_OPERATION_REJECTED)
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(stateLock) {
            if (pendingDescriptorWrite === deferred) {
                pendingDescriptor = null
                pendingDescriptorWrite = null
            }
        }
        return result
    }

    private fun dispatchNotification(characteristic: BluetoothGattCharacteristic) {
        val target = synchronized(stateLock) { notificationTargetsByCharacteristic[characteristic] } ?: return
        _events.tryEmit(Event.StateChanged(target))
    }

    private fun completeRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ) {
        synchronized(stateLock) {
            if (pendingReadCharacteristic !== characteristic) return
            pendingReadCharacteristic = null
            pendingRead?.takeIf { !it.isCompleted }?.complete(ReadCompletion(value?.copyOf(), status))
            pendingRead = null
        }
    }

    private fun failPendingLocked() {
        pendingRead?.takeIf { !it.isCompleted }
            ?.complete(ReadCompletion(null, STATUS_DISCONNECTED))
        pendingRead = null
        pendingReadCharacteristic = null
        pendingWrite?.takeIf { !it.isCompleted }
            ?.complete(WriteCompletion(STATUS_DISCONNECTED))
        pendingWrite = null
        pendingWriteCharacteristic = null
        pendingDescriptorWrite?.takeIf { !it.isCompleted }
            ?.complete(DescriptorCompletion(STATUS_DISCONNECTED))
        pendingDescriptorWrite = null
        pendingDescriptor = null
    }

    private fun closeLocked() {
        val gatt = synchronized(stateLock) {
            servicesReady = false
            connectedDeviceAddress = null
            connectDeferred?.takeIf { !it.isCompleted }?.complete("gatt_closed")
            connectDeferred = null
            failPendingLocked()
            notificationTargetsByCharacteristic.clear()
            gattRef.also { gattRef = null }
        }
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val GATT_CLIENT_CLEANUP_DELAY_MS = 250L
        private const val ATT_ERROR_INVALID_CHANGE_COUNTER = 0x80
        private const val STATUS_OPERATION_REJECTED = -10_001
        private const val STATUS_DISCONNECTED = -10_002
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
