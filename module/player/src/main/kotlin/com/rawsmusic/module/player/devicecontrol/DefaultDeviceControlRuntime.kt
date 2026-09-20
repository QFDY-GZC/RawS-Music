package com.rawsmusic.module.player.devicecontrol

import android.content.Context
import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothStandardDeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothVendorGattAdapterRegistry
import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothVendorGattDeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothVendorRfcommProbeBackend
import com.rawsmusic.module.player.devicecontrol.usb.UsbStandardDeviceControlBackend
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorAdapterRegistry
import com.rawsmusic.module.player.devicecontrol.usb.UsbVendorDeviceControlBackend
import com.rawsmusic.module.player.usb.UsbAudioEngine
import kotlinx.coroutines.CoroutineScope

data class DefaultDeviceControlRuntime(
    val manager: DeviceControlManager,
    val resolver: CurrentAudioDeviceControlResolver,
)

/** Constructs the protocol backends behind one manager. */
fun createDefaultDeviceControlRuntime(
    context: Context,
    scope: CoroutineScope,
    usbEngine: UsbAudioEngine,
    usbVendorAdapters: UsbVendorAdapterRegistry = UsbVendorAdapterRegistry.Empty,
    bluetoothVendorAdapters: BluetoothVendorGattAdapterRegistry = BluetoothVendorGattAdapterRegistry.Empty,
): DefaultDeviceControlRuntime {
    val manager = DeviceControlManager(
        scope = scope,
        backends = listOf(
            UsbStandardDeviceControlBackend(usbEngine),
            UsbVendorDeviceControlBackend(usbEngine, usbVendorAdapters),
            BluetoothStandardDeviceControlBackend(context),
            BluetoothVendorGattDeviceControlBackend(context, bluetoothVendorAdapters),
            BluetoothVendorRfcommProbeBackend(context),
        ),
    )
    return DefaultDeviceControlRuntime(
        manager = manager,
        resolver = CurrentAudioDeviceControlResolver(context, usbEngine),
    )
}
