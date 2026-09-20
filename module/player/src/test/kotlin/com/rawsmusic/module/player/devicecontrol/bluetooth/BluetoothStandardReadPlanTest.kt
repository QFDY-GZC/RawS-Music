package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BluetoothStandardReadPlanTest {
    @Test
    fun onlyKnownReadableStandardCharacteristicsArePlanned() {
        val unknown = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val inventory = BluetoothGattInventory(
            services = listOf(
                BluetoothGattInventory.Service(
                    uuid = BluetoothLeAudioStandard.VCS_SERVICE,
                    type = 0,
                    includedServiceUuids = emptyList(),
                    characteristics = listOf(
                        characteristic(BluetoothLeAudioStandard.VOLUME_STATE, read = true),
                        characteristic(BluetoothLeAudioStandard.VOLUME_CONTROL_POINT, read = false, write = true),
                        characteristic(unknown, read = true),
                    ),
                ),
                BluetoothGattInventory.Service(
                    uuid = BluetoothLeAudioStandard.VOCS_SERVICE,
                    type = 0,
                    includedServiceUuids = emptyList(),
                    characteristics = listOf(characteristic(BluetoothLeAudioStandard.VOLUME_OFFSET_STATE, read = true)),
                ),
            ),
        )

        val plan = BluetoothStandardReadPlan.fromInventory(inventory)
        assertEquals(
            listOf(
                BluetoothStandardReadPlan.Kind.VCS_VOLUME_STATE,
                BluetoothStandardReadPlan.Kind.VOCS_VOLUME_OFFSET_STATE,
            ),
            plan.map { it.kind },
        )
        assertFalse(plan.any { it.characteristicUuid == unknown })
        assertFalse(plan.any { it.characteristicUuid == BluetoothLeAudioStandard.VOLUME_CONTROL_POINT })
    }

    private fun characteristic(uuid: UUID, read: Boolean, write: Boolean = false): BluetoothGattInventory.Characteristic {
        val props = buildSet {
            if (read) add(BluetoothGattInventory.Property.READ)
            if (write) add(BluetoothGattInventory.Property.WRITE)
        }
        return BluetoothGattInventory.Characteristic(uuid, props, 0, emptyList())
    }
}
