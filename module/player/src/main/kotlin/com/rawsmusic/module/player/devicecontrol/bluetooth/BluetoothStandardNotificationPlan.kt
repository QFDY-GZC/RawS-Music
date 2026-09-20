package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID

/**
 * Notify/indicate whitelist derived strictly from the same Bluetooth SIG characteristics that the
 * standard read plan already understands. Vendor characteristics can never enter this plan.
 */
internal object BluetoothStandardNotificationPlan {
    enum class Mode { NOTIFY, INDICATE }

    data class Target(
        val serviceIndex: Int,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val kind: BluetoothStandardReadPlan.Kind,
        val mode: Mode,
    )

    fun fromInventory(inventory: BluetoothGattInventory): List<Target> =
        BluetoothStandardReadPlan.fromInventory(inventory).mapNotNull { readTarget ->
            val characteristic = inventory.services.getOrNull(readTarget.serviceIndex)
                ?.takeIf { it.uuid == readTarget.serviceUuid }
                ?.characteristics
                ?.firstOrNull { it.uuid == readTarget.characteristicUuid }
                ?: return@mapNotNull null
            val mode = when {
                BluetoothGattInventory.Property.NOTIFY in characteristic.properties -> Mode.NOTIFY
                BluetoothGattInventory.Property.INDICATE in characteristic.properties -> Mode.INDICATE
                else -> return@mapNotNull null
            }
            Target(
                serviceIndex = readTarget.serviceIndex,
                serviceUuid = readTarget.serviceUuid,
                characteristicUuid = readTarget.characteristicUuid,
                kind = readTarget.kind,
                mode = mode,
            )
        }
}
