package com.rawsmusic.module.player.devicecontrol.bluetooth

internal data class BluetoothStandardReadValue(
    val target: BluetoothStandardReadPlan.Target,
    val value: ByteArray,
)

internal data class BluetoothStandardProbeData(
    val inventory: BluetoothGattInventory,
    val values: List<BluetoothStandardReadValue>,
    val diagnostics: List<String>,
)
