package com.rawsmusic.module.player.devicecontrol.bluetooth

import android.bluetooth.BluetoothDevice

internal fun BluetoothDevice.toControlIdentityDiagnosticLines(source: String? = null): List<String> = buildList {
    add(
        buildString {
            append("bt.identity")
            source?.let { append(" source=").append(it) }
            append(" type=").append(deviceTypeLabel(runCatching { type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)))
            append(" bond=").append(bondStateLabel(runCatching { bondState }.getOrDefault(BluetoothDevice.BOND_NONE)))
        },
    )
    val cachedUuids = runCatching { uuids.orEmpty().map { it.uuid.toString() }.sorted() }.getOrDefault(emptyList())
    add("bt.cached_uuids count=${cachedUuids.size}${cachedUuids.takeIf { it.isNotEmpty() }?.joinToString(prefix = " values=", separator = ",").orEmpty()}")
}

private fun deviceTypeLabel(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
    BluetoothDevice.DEVICE_TYPE_LE -> "LE"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
    else -> "UNKNOWN"
}

private fun bondStateLabel(state: Int): String = when (state) {
    BluetoothDevice.BOND_BONDED -> "BONDED"
    BluetoothDevice.BOND_BONDING -> "BONDING"
    BluetoothDevice.BOND_NONE -> "NONE"
    else -> "UNKNOWN"
}
