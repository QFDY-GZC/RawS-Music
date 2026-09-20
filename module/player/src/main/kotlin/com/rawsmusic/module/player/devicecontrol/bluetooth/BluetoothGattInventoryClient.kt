package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only GATT service discovery for the already-resolved active Bluetooth device.
 * This class never reads unknown characteristic values and has no write API.
 *
 * For dual-mode audio devices the control plane is normally BLE even while media travels over
 * A2DP. Prefer TRANSPORT_LE explicitly; TRANSPORT_AUTO is retained only as a bounded fallback
 * because some Android/vendor stacks route dual-mode GATT poorly when AUTO is selected.
 */
internal class BluetoothGattInventoryClient(
    context: Context,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext
    private val companionScanner = BluetoothLeControlPeripheralScanner(context)

    sealed interface Result {
        data class Ready(
            val inventory: BluetoothGattInventory,
            val controlDevice: BluetoothDevice,
            val controlTransport: Int?,
            val controlAutoConnect: Boolean,
            val diagnostics: List<String> = emptyList(),
        ) : Result
        data class Failed(
            val reason: String,
            val diagnostics: List<String> = emptyList(),
        ) : Result
        data object PermissionRequired : Result
    }

    private sealed interface AttemptResult {
        data class Ready(
            val inventory: BluetoothGattInventory,
            val diagnostics: List<String>,
        ) : AttemptResult
        data class Failed(
            val reason: String,
            val diagnostics: List<String>,
        ) : AttemptResult
    }

    suspend fun discover(
        device: BluetoothDevice,
        onProgress: (String) -> Unit = {},
    ): Result {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            return Result.PermissionRequired
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return when (val result = discoverAttempt(device, null, "DEFAULT", onProgress)) {
                is AttemptResult.Ready -> Result.Ready(
                    result.inventory,
                    device,
                    controlTransport = null,
                    controlAutoConnect = false,
                    diagnostics = result.diagnostics,
                )
                is AttemptResult.Failed -> Result.Failed(result.reason, result.diagnostics)
            }
        }

        val diagnostics = mutableListOf<String>()
        var lastReason = "gatt_inventory_failed"
        val vendorHints = device.cachedVendorServiceUuids()
        val companionFirst = prefersBluetoothControlCompanionLookup(vendorHints)

        suspend fun tryCompanion(allowSameAddress: Boolean): Result? {
            return when (val companion = companionScanner.resolve(device, onProgress)) {
                is BluetoothLeControlPeripheralScanner.Result.Found -> {
                    diagnostics += companion.diagnostics
                    val sameAddress = runCatching {
                        companion.device.address.equals(device.address, ignoreCase = true)
                    }.getOrDefault(false)
                    if (sameAddress && !allowSameAddress) {
                        onProgress("bt.gatt companion_same_address skipped=already_tried_direct_le")
                        null
                    } else {
                        when (val attempt = discoverAttempt(
                            companion.device,
                            BluetoothDevice.TRANSPORT_LE,
                            "LE_COMPANION",
                            onProgress,
                        )) {
                            is AttemptResult.Ready -> {
                                diagnostics += attempt.diagnostics
                                Result.Ready(
                                    attempt.inventory,
                                    companion.device,
                                    controlTransport = BluetoothDevice.TRANSPORT_LE,
                                    controlAutoConnect = false,
                                    diagnostics = diagnostics.distinct(),
                                )
                            }
                            is AttemptResult.Failed -> {
                                diagnostics += attempt.diagnostics
                                lastReason = attempt.reason
                                null
                            }
                        }
                    }
                }
                is BluetoothLeControlPeripheralScanner.Result.Failed -> {
                    diagnostics += companion.diagnostics
                    lastReason = "$lastReason;${companion.reason}"
                    null
                }
                BluetoothLeControlPeripheralScanner.Result.PermissionRequired -> {
                    diagnostics += "bt.scan permission=required"
                    onProgress("bt.scan permission=required")
                    Result.PermissionRequired
                }
            }
        }

        // OPO/BBK 079A devices are a dual-mode case. The current audio identity is already on an
        // active BR/EDR bearer, and Android explicitly supports GATT over TRANSPORT_BREDR for
        // dual-mode devices. Real Enco Free4 diagnostics show its same-address LE advertisements
        // as connectable=false, so prefer the already-connected BR/EDR bearer before spending time
        // looking for a separate BLE control peripheral.
        if (companionFirst) {
            val routeType = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
            if (routeType != BluetoothDevice.DEVICE_TYPE_LE) {
                onProgress("bt.gatt strategy=bredr_first reason=opo_079a_hint_and_dual_route")
                when (val attempt = discoverAttempt(
                    device = device,
                    transport = BluetoothDevice.TRANSPORT_BREDR,
                    label = "BREDR",
                    onProgress = onProgress,
                    timeoutMs = OPO_BREDR_TIMEOUT_MS,
                )) {
                    is AttemptResult.Ready -> {
                        diagnostics += attempt.diagnostics
                        return Result.Ready(
                            attempt.inventory,
                            device,
                            controlTransport = BluetoothDevice.TRANSPORT_BREDR,
                            controlAutoConnect = false,
                            diagnostics = diagnostics.distinct(),
                        )
                    }
                    is AttemptResult.Failed -> {
                        diagnostics += attempt.diagnostics
                        lastReason = attempt.reason
                    }
                }
            } else {
                onProgress("bt.gatt BREDR skipped=device_type_le")
            }

            onProgress("bt.gatt strategy=companion_after_bredr reason=opo_bredr_unavailable")
            tryCompanion(allowSameAddress = true)?.let { return it }

            // Some bonded OPO routes do not advertise a connectable LE peripheral while Android
            // still retains enough bond/identity state to reconnect it later. Keep one bounded LE
            // auto-connect fallback after BR/EDR GATT and companion discovery have both failed.
            onProgress("bt.gatt strategy=bounded_auto_connect reason=opo_bredr_and_companion_unavailable")
            when (val attempt = discoverAttempt(
                device = device,
                transport = BluetoothDevice.TRANSPORT_LE,
                label = "LE_AUTO_CONNECT",
                onProgress = onProgress,
                autoConnect = true,
                timeoutMs = OPO_AUTO_CONNECT_TIMEOUT_MS,
            )) {
                is AttemptResult.Ready -> {
                    diagnostics += attempt.diagnostics
                    return Result.Ready(
                        attempt.inventory,
                        device,
                        controlTransport = BluetoothDevice.TRANSPORT_LE,
                        controlAutoConnect = true,
                        diagnostics = diagnostics.distinct(),
                    )
                }
                is AttemptResult.Failed -> {
                    diagnostics += attempt.diagnostics
                    lastReason = attempt.reason
                    return Result.Failed(
                        reason = "gatt_inventory_failed:$lastReason",
                        diagnostics = diagnostics.distinct(),
                    )
                }
            }
        }

        // Generic path: try the bonded/current-route identity directly over LE.
        when (val attempt = discoverAttempt(device, BluetoothDevice.TRANSPORT_LE, "LE", onProgress)) {
            is AttemptResult.Ready -> {
                diagnostics += attempt.diagnostics
                return Result.Ready(
                    attempt.inventory,
                    device,
                    controlTransport = BluetoothDevice.TRANSPORT_LE,
                    controlAutoConnect = false,
                    diagnostics = diagnostics.distinct(),
                )
            }
            is AttemptResult.Failed -> {
                diagnostics += attempt.diagnostics
                lastReason = attempt.reason
            }
        }

        // Generic dual-mode fallback: look for a separate LE control identity only after the
        // direct LE attempt. OPO already performed this step above.
        if (!companionFirst) {
            tryCompanion(allowSameAddress = false)?.let { return it }
        }

        // AUTO remains a final compatibility fallback for vendor Bluetooth stacks that reject an
        // explicit LE transport on the bonded identity.
        val type = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        if (type != BluetoothDevice.DEVICE_TYPE_LE) {
            when (val attempt = discoverAttempt(device, BluetoothDevice.TRANSPORT_AUTO, "AUTO", onProgress)) {
                is AttemptResult.Ready -> {
                    diagnostics += attempt.diagnostics
                    return Result.Ready(
                        attempt.inventory,
                        device,
                        controlTransport = BluetoothDevice.TRANSPORT_AUTO,
                        controlAutoConnect = false,
                        diagnostics = diagnostics.distinct(),
                    )
                }
                is AttemptResult.Failed -> {
                    diagnostics += attempt.diagnostics
                    lastReason = attempt.reason
                }
            }
        }
        return Result.Failed(
            reason = "gatt_inventory_failed:$lastReason",
            diagnostics = diagnostics.distinct(),
        )
    }

    private suspend fun discoverAttempt(
        device: BluetoothDevice,
        transport: Int?,
        label: String,
        onProgress: (String) -> Unit,
        autoConnect: Boolean = false,
        timeoutMs: Long = attemptTimeoutMs,
    ): AttemptResult {
        val completion = CompletableDeferred<AttemptResult>()
        val finished = AtomicBoolean(false)
        val stage = AtomicReference("connect_start")
        val callbackDiagnostics = mutableListOf<String>()
        val diagnosticLock = Any()
        var gattRef: BluetoothGatt? = null

        fun diagnostic(value: String) {
            val line = "bt.gatt.$label $value"
            synchronized(diagnosticLock) { callbackDiagnostics += line }
            onProgress(line)
        }

        fun snapshotDiagnostics(): List<String> = synchronized(diagnosticLock) {
            callbackDiagnostics.toList()
        }

        fun completeOnce(result: AttemptResult) {
            if (finished.compareAndSet(false, true)) completion.complete(result)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                diagnostic("connection status=$status state=$newState")
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        stage.set("connection_error")
                        completeOnce(
                            AttemptResult.Failed(
                                "transport_${label.lowercase()}_connection_status_$status",
                                snapshotDiagnostics(),
                            ),
                        )
                    }
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        stage.set("connected")
                        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        diagnostic("discoverServices accepted=$started")
                        if (started) {
                            stage.set("service_discovery")
                        } else {
                            stage.set("discover_rejected")
                            completeOnce(
                                AttemptResult.Failed(
                                    "transport_${label.lowercase()}_discover_services_rejected",
                                    snapshotDiagnostics(),
                                ),
                            )
                        }
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        stage.set("disconnected")
                        completeOnce(
                            AttemptResult.Failed(
                                "transport_${label.lowercase()}_disconnected_before_inventory",
                                snapshotDiagnostics(),
                            ),
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                diagnostic("servicesDiscovered status=$status count=${gatt.services.orEmpty().size}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stage.set("service_discovery_error")
                    completeOnce(
                        AttemptResult.Failed(
                            "transport_${label.lowercase()}_service_discovery_status_$status",
                            snapshotDiagnostics(),
                        ),
                    )
                    return
                }
                stage.set("inventory")
                val inventory = runCatching { buildBluetoothGattInventory(gatt) }
                    .getOrElse { error ->
                        completeOnce(
                            AttemptResult.Failed(
                                "transport_${label.lowercase()}_inventory_failed:${error.javaClass.simpleName}",
                                snapshotDiagnostics(),
                            ),
                        )
                        return
                    }
                stage.set("ready")
                completeOnce(
                    AttemptResult.Ready(
                        inventory,
                        snapshotDiagnostics() + "bt.gatt.selected_transport=$label",
                    ),
                )
            }
        }

        return try {
            diagnostic("open transport=$label autoConnect=$autoConnect timeoutMs=$timeoutMs")
            val openedGatt = runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        transport == BluetoothDevice.TRANSPORT_BREDR -> {
                        // GATT over BR/EDR has no LE PHY. Use the transport-only public overload
                        // rather than passing an LE PHY mask to OEM stacks.
                        device.connectGatt(appContext, autoConnect, callback, transport)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && transport != null -> {
                        // Keep LE callbacks on the main looper for OEM-stack consistency. For direct
                        // LE connections request the 1M PHY; Android documents that the PHY value
                        // is ignored when autoConnect=true.
                        device.connectGatt(
                            appContext,
                            autoConnect,
                            callback,
                            transport,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            Handler(Looper.getMainLooper()),
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && transport != null -> {
                        device.connectGatt(appContext, autoConnect, callback, transport)
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        device.connectGatt(appContext, autoConnect, callback)
                    }
                }
            }.getOrElse { error ->
                return AttemptResult.Failed(
                    "transport_${label.lowercase()}_connect_failed:${error.javaClass.simpleName}",
                    snapshotDiagnostics(),
                )
            } ?: return AttemptResult.Failed(
                "transport_${label.lowercase()}_connect_returned_null",
                snapshotDiagnostics(),
            )
            gattRef = openedGatt
            withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: AttemptResult.Failed(
                    "transport_${label.lowercase()}_timeout_stage_${stage.get()}",
                    snapshotDiagnostics() + "bt.gatt.$label timeout_stage=${stage.get()}",
                )
        } finally {
            val gatt = gattRef
            if (gatt != null) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                // A failed direct connection still registers/unregisters a GATT client in the
                // Android Bluetooth stack. Give vendor stacks a short bounded cleanup window
                // before the next LE/AUTO attempt instead of immediately piling another client
                // registration onto the same remote identity.
                delay(GATT_CLIENT_CLEANUP_DELAY_MS)
            }
        }
    }

    companion object {
        private const val DEFAULT_ATTEMPT_TIMEOUT_MS = 5_500L
        private const val OPO_BREDR_TIMEOUT_MS = 5_500L
        private const val OPO_AUTO_CONNECT_TIMEOUT_MS = 12_000L
        private const val GATT_CLIENT_CLEANUP_DELAY_MS = 250L
    }
}

internal fun buildBluetoothGattInventory(gatt: BluetoothGatt): BluetoothGattInventory {
    return BluetoothGattInventory(
        services = gatt.services.orEmpty().map { service ->
            BluetoothGattInventory.Service(
                uuid = service.uuid,
                type = service.type,
                includedServiceUuids = service.includedServices.orEmpty().map { it.uuid },
                characteristics = service.characteristics.orEmpty().map { characteristic ->
                    BluetoothGattInventory.Characteristic(
                        uuid = characteristic.uuid,
                        properties = BluetoothGattPropertyDecoder.decode(characteristic.properties),
                        permissions = characteristic.permissions,
                        descriptors = characteristic.descriptors.orEmpty().map { descriptor ->
                            BluetoothGattInventory.Descriptor(
                                uuid = descriptor.uuid,
                                permissions = descriptor.permissions,
                            )
                        },
                    )
                },
            )
        },
    )
}
