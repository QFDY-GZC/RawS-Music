package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BluetoothStandardNotificationPlanTest {
    @Test
    fun subscribesOnlyKnownReadableStandardCharacteristicsWithNotifyOrIndicate() {
        val inventory = BluetoothGattInventory(
            services = listOf(
                BluetoothGattInventory.Service(
                    uuid = BluetoothLeAudioStandard.VCS_SERVICE,
                    type = 0,
                    includedServiceUuids = emptyList(),
                    characteristics = listOf(
                        characteristic(
                            BluetoothLeAudioStandard.VOLUME_STATE,
                            BluetoothGattInventory.Property.READ,
                            BluetoothGattInventory.Property.NOTIFY,
                        ),
                        characteristic(
                            BluetoothLeAudioStandard.VOLUME_FLAGS,
                            BluetoothGattInventory.Property.READ,
                            BluetoothGattInventory.Property.INDICATE,
                        ),
                        // Unknown vendor characteristic: even READ+NOTIFY must not be subscribed.
                        characteristic(
                            java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                            BluetoothGattInventory.Property.READ,
                            BluetoothGattInventory.Property.NOTIFY,
                        ),
                    ),
                ),
            ),
        )

        val plan = BluetoothStandardNotificationPlan.fromInventory(inventory)
        assertEquals(2, plan.size)
        assertEquals(BluetoothLeAudioStandard.VOLUME_STATE, plan[0].characteristicUuid)
        assertEquals(BluetoothStandardNotificationPlan.Mode.NOTIFY, plan[0].mode)
        assertEquals(BluetoothLeAudioStandard.VOLUME_FLAGS, plan[1].characteristicUuid)
        assertEquals(BluetoothStandardNotificationPlan.Mode.INDICATE, plan[1].mode)
        assertTrue(plan.none { it.characteristicUuid.toString().startsWith("aaaaaaaa") })
    }

    private fun characteristic(
        uuid: java.util.UUID,
        vararg properties: BluetoothGattInventory.Property,
    ) = BluetoothGattInventory.Characteristic(
        uuid = uuid,
        properties = properties.toSet(),
        permissions = 0,
        descriptors = listOf(BluetoothGattInventory.Descriptor(CCCD, 0)),
    )

    private companion object {
        val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
