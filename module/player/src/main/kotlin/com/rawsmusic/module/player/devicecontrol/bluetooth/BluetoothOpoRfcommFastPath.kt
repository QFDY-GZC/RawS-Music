package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import java.util.Locale

/**
 * Narrow real-device fast path for OPPO Enco Free4 after the 079A RFCOMM transport was proven on
 * physical hardware. Keep this intentionally model-scoped so other OPO-family devices still go
 * through transport discovery until their own bearer is proven.
 */
internal object BluetoothOpoRfcommFastPath {
    fun matches(device: DeviceControlDevice, bluetoothDevice: BluetoothDevice): Boolean {
        val routeName = normalize(device.displayName)
        val bluetoothName = normalize(runCatching { bluetoothDevice.name }.getOrNull().orEmpty())
        val nameMatches = routeName.startsWith(ENCO_FREE4) || bluetoothName.startsWith(ENCO_FREE4)
        if (!nameMatches) return false
        return bluetoothDevice.cachedVendorServiceUuids().contains(BluetoothOpoRfcommProtocol.SERVICE_UUID)
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private const val ENCO_FREE4 = "oppo enco free4"
}
