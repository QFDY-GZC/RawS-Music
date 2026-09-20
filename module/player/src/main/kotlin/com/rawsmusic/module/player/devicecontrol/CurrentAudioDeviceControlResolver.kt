package com.rawsmusic.module.player.devicecontrol

import android.content.Context
import com.rawsmusic.module.player.devicecontrol.bluetooth.ActiveBluetoothDeviceResolver
import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothAudioRouteSnapshot
import com.rawsmusic.module.player.usb.UsbAudioEngine

/** Resolves the physical device whose hardware controls should currently be shown. */
class CurrentAudioDeviceControlResolver(
    context: Context,
    private val usbEngine: UsbAudioEngine,
) {
    private val bluetoothResolver = ActiveBluetoothDeviceResolver(context)

    sealed interface Result {
        data class Resolved(val device: DeviceControlDevice, val source: Source) : Result
        data class Unresolved(val reason: String) : Result
        data object None : Result
    }

    enum class Source { USB_EXCLUSIVE_SESSION, BLUETOOTH_ROUTE }

    fun resolve(bluetoothRoute: BluetoothAudioRouteSnapshot?, usbExclusiveActive: Boolean = false): Result {
        if (usbExclusiveActive && !usbEngine.isInitialized()) {
            return Result.Unresolved("usb_exclusive_active_before_control_session_ready")
        }
        if (usbExclusiveActive && usbEngine.isInitialized()) {
            val capabilities = usbEngine.getDeviceCapabilities()
            if (capabilities != null) {
                return Result.Resolved(
                    device = DeviceControlDevice(
                        stableId = buildString {
                            append("usb:")
                            append(capabilities.vendorId.toString(16).padStart(4, '0'))
                            append(':')
                            append(capabilities.productId.toString(16).padStart(4, '0'))
                        },
                        displayName = capabilities.deviceName.ifBlank { "USB Audio Device" },
                        connectionKind = DeviceConnectionKind.USB,
                        vendorId = capabilities.vendorId.takeIf { it != 0 },
                        productId = capabilities.productId.takeIf { it != 0 },
                    ),
                    source = Source.USB_EXCLUSIVE_SESSION,
                )
            }
            // A live USB session without a capability snapshot is still significant: do not
            // accidentally fall through and bind controls to a stale Bluetooth route.
            return Result.Unresolved("usb_session_active_without_device_capabilities")
        }

        val route = bluetoothRoute ?: return Result.None
        return when (val resolved = bluetoothResolver.resolve(route)) {
            is ActiveBluetoothDeviceResolver.Result.Resolved ->
                Result.Resolved(resolved.device, Source.BLUETOOTH_ROUTE)
            is ActiveBluetoothDeviceResolver.Result.Unresolved -> Result.Unresolved(resolved.reason)
            is ActiveBluetoothDeviceResolver.Result.PermissionRequired ->
                Result.Resolved(resolved.device, Source.BLUETOOTH_ROUTE)
            ActiveBluetoothDeviceResolver.Result.BluetoothUnavailable ->
                Result.Unresolved("bluetooth_unavailable")
        }
    }
}
