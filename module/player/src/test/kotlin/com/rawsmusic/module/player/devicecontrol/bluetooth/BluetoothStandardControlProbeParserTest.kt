package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BluetoothStandardControlProbeParserTest {
    @Test
    fun mapsVcsVocsAndMultipleAicsInstancesWithoutCollidingAddresses() {
        val inventory = BluetoothGattInventory(
            services = listOf(
                service(BluetoothLeAudioStandard.VCS_SERVICE, BluetoothLeAudioStandard.VOLUME_STATE),
                service(
                    BluetoothLeAudioStandard.VOCS_SERVICE,
                    BluetoothLeAudioStandard.VOLUME_OFFSET_STATE,
                    BluetoothLeAudioStandard.AUDIO_OUTPUT_DESCRIPTION,
                ),
                service(
                    BluetoothLeAudioStandard.AICS_SERVICE,
                    BluetoothLeAudioStandard.AUDIO_INPUT_STATE,
                    BluetoothLeAudioStandard.GAIN_SETTING_PROPERTIES,
                    BluetoothLeAudioStandard.AUDIO_INPUT_DESCRIPTION,
                ),
                service(
                    BluetoothLeAudioStandard.AICS_SERVICE,
                    BluetoothLeAudioStandard.AUDIO_INPUT_STATE,
                    BluetoothLeAudioStandard.GAIN_SETTING_PROPERTIES,
                ),
            ),
        )
        val plan = BluetoothStandardReadPlan.fromInventory(inventory)
        fun target(serviceIndex: Int, kind: BluetoothStandardReadPlan.Kind) =
            plan.single { it.serviceIndex == serviceIndex && it.kind == kind }
        val data = BluetoothStandardProbeData(
            inventory = inventory,
            values = listOf(
                value(target(0, BluetoothStandardReadPlan.Kind.VCS_VOLUME_STATE), byteArrayOf(200.toByte(), 1, 9)),
                value(target(1, BluetoothStandardReadPlan.Kind.VOCS_VOLUME_OFFSET_STATE), byteArrayOf(10, 0, 1)),
                value(target(1, BluetoothStandardReadPlan.Kind.VOCS_OUTPUT_DESCRIPTION), "Left".encodeToByteArray()),
                value(target(2, BluetoothStandardReadPlan.Kind.AICS_INPUT_STATE), byteArrayOf((-6).toByte(), 0, 2, 3)),
                value(target(2, BluetoothStandardReadPlan.Kind.AICS_GAIN_SETTING_PROPERTIES), byteArrayOf(5, (-20).toByte(), 20)),
                value(target(2, BluetoothStandardReadPlan.Kind.AICS_INPUT_DESCRIPTION), "Mic".encodeToByteArray()),
                value(target(3, BluetoothStandardReadPlan.Kind.AICS_INPUT_STATE), byteArrayOf(4, 1, 3, 4)),
                value(target(3, BluetoothStandardReadPlan.Kind.AICS_GAIN_SETTING_PROPERTIES), byteArrayOf(10, (-10).toByte(), 10)),
            ),
            diagnostics = emptyList(),
        )
        val snapshot = BluetoothStandardControlProbeParser.parse(
            DeviceControlDevice("bluetooth:test", "Test", DeviceConnectionKind.BLUETOOTH),
            generation = 4,
            data = data,
        )

        assertEquals(8, snapshot.capabilities.size)
        val volume = assertIs<DeviceControlCapability.Volume>(snapshot.capabilities[0])
        assertEquals(200.0, volume.channels.single().current)
        assertTrue(volume.mutes.single().current == true)
        assertEquals(DeviceControlAccess.READ_ONLY, volume.channels.single().access)

        val firstGain = assertIs<DeviceControlCapability.Range>(snapshot.capabilities[2]).control
        assertEquals(-3.0, firstGain.current)
        assertEquals(0.5, firstGain.ranges.single().step)
        val firstAddress = assertIs<DeviceControlBackendAddress.BluetoothStandard>(firstGain.address)
        assertEquals(0, firstAddress.serviceInstanceIndex)

        val secondGain = assertIs<DeviceControlCapability.Range>(snapshot.capabilities[5]).control
        val secondAddress = assertIs<DeviceControlBackendAddress.BluetoothStandard>(secondGain.address)
        assertEquals(1, secondAddress.serviceInstanceIndex)
    }

    private fun service(uuid: UUID, vararg characteristics: UUID): BluetoothGattInventory.Service =
        BluetoothGattInventory.Service(
            uuid = uuid,
            type = 0,
            includedServiceUuids = emptyList(),
            characteristics = characteristics.map {
                BluetoothGattInventory.Characteristic(
                    uuid = it,
                    properties = setOf(BluetoothGattInventory.Property.READ),
                    permissions = 0,
                    descriptors = emptyList(),
                )
            },
        )

    private fun value(target: BluetoothStandardReadPlan.Target, bytes: ByteArray) =
        BluetoothStandardReadValue(target, bytes)
}
