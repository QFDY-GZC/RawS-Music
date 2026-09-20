package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice

internal class ActiveBluetoothDeviceResolver(context: Context) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Resolved(
            val route: BluetoothAudioRouteSnapshot,
            val bluetoothDevice: BluetoothDevice,
            val device: DeviceControlDevice,
            val confidence: BluetoothRouteIdentityMatch.Confidence,
        ) : Result

        data class Unresolved(val reason: String) : Result
        data class PermissionRequired(val device: DeviceControlDevice) : Result
        data object BluetoothUnavailable : Result
    }

    fun resolve(route: BluetoothAudioRouteSnapshot): Result {
        if (!BluetoothDeviceControlPermissions.hasConnectPermission(appContext)) {
            val routeAddress = route.routeAddress?.trim().orEmpty()
            val stableId = if (BluetoothAdapter.checkBluetoothAddress(routeAddress)) {
                "bluetooth:${routeAddress.lowercase()}"
            } else {
                // Provisional route-scoped identity used only to surface the permission state.
                // Once CONNECT permission is granted the resolver re-binds to the bonded device.
                "bluetooth-route:${route.audioDeviceId}"
            }
            return Result.PermissionRequired(
                DeviceControlDevice(
                    stableId = stableId,
                    displayName = route.productName?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio Device",
                    connectionKind = DeviceConnectionKind.BLUETOOTH,
                ),
            )
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
            ?: return Result.BluetoothUnavailable

        val bonded = runCatching { adapter.bondedDevices.orEmpty() }
            .getOrElse { return Result.Unresolved("cannot_read_bonded_devices:${it.javaClass.simpleName}") }
        val identities = bonded.map { device ->
            BluetoothKnownDeviceIdentity(
                address = device.address,
                name = runCatching { device.name }.getOrNull(),
            )
        }
        val match = BluetoothRouteIdentityMatcher.match(
            route = route,
            knownDevices = identities,
            isValidBluetoothAddress = BluetoothAdapter::checkBluetoothAddress,
        )
        if (match !is BluetoothRouteIdentityMatch.Matched) {
            return Result.Unresolved((match as BluetoothRouteIdentityMatch.Unresolved).reason)
        }
        val device = bonded.firstOrNull {
            it.address.equals(match.device.address, ignoreCase = true)
        } ?: return Result.Unresolved("matched_bonded_device_disappeared")

        val displayName = route.productName?.takeIf { it.isNotBlank() }
            ?: runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Bluetooth Audio Device"
        return Result.Resolved(
            route = route,
            bluetoothDevice = device,
            device = DeviceControlDevice(
                stableId = "bluetooth:${device.address.lowercase()}",
                displayName = displayName,
                connectionKind = DeviceConnectionKind.BLUETOOTH,
            ),
            confidence = match.confidence,
        )
    }
}
