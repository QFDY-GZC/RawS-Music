package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice

/**
 * Rehydrates the BluetoothDevice that was already trusted by ActiveBluetoothDeviceResolver.
 *
 * Do not require a second bondedDevices membership lookup for the canonical bluetooth:<address>
 * identity. Android documents BluetoothDevice as a thin wrapper for a hardware address and
 * BluetoothAdapter.getRemoteDevice(address) as the way to obtain that wrapper for a known address.
 * Requiring the device to appear in bondedDevices again created an unnecessary second failure
 * point between route resolution and backend open. The stable id intentionally stores the address
 * in lowercase, while Android's documented string address form is uppercase; normalize it before
 * validation/getRemoteDevice instead of rejecting our own canonical identity.
 */
internal object BluetoothBoundDeviceLookup {
    sealed interface Result {
        data class Found(
            val device: BluetoothDevice,
            val source: String,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    fun resolve(context: Context, controlDevice: DeviceControlDevice): Result {
        val appContext = context.applicationContext
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
            ?: return Result.Failed("bluetooth_adapter_unavailable")

        val address = controlDevice.stableId
            .removePrefix(BLUETOOTH_STABLE_ID_PREFIX)
            .takeIf { it != controlDevice.stableId }
            ?.uppercase()
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress)
        if (address != null) {
            val remote = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            if (remote != null) return Result.Found(remote, "stable_id_address")
        }

        // Defensive fallback for future route identities that cannot expose a canonical address.
        // Only accept a unique bonded-name match; ambiguity is safer than controlling the wrong
        // physical earbuds.
        val expectedName = controlDevice.displayName.trim().takeIf { it.isNotEmpty() }
            ?: return Result.Failed("bluetooth_control_identity_has_no_address_or_name")
        val bonded = runCatching { adapter.bondedDevices.orEmpty() }
            .getOrElse { return Result.Failed("cannot_read_bonded_devices:${it.javaClass.simpleName}") }
        val matches = bonded.filter { candidate ->
            runCatching { candidate.name }.getOrNull()?.trim()?.equals(expectedName, ignoreCase = true) == true
        }
        return when (matches.size) {
            1 -> Result.Found(matches.single(), "unique_bonded_name")
            0 -> Result.Failed("bluetooth_control_device_not_found")
            else -> Result.Failed("bluetooth_control_device_name_ambiguous:${matches.size}")
        }
    }

    private const val BLUETOOTH_STABLE_ID_PREFIX = "bluetooth:"
}
