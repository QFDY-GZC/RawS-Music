package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice

private fun characteristic(uuid: java.util.UUID, vararg p: BluetoothGattInventory.Property) =
    BluetoothGattInventory.Characteristic(uuid, p.toSet(), 0, emptyList())

fun main() {
    val vcs = BluetoothGattInventory.Service(
        BluetoothLeAudioStandard.VCS_SERVICE, 0, emptyList(), listOf(
            characteristic(BluetoothLeAudioStandard.VOLUME_STATE, BluetoothGattInventory.Property.READ),
            characteristic(BluetoothLeAudioStandard.VOLUME_CONTROL_POINT, BluetoothGattInventory.Property.WRITE),
        )
    )
    val aics = BluetoothGattInventory.Service(
        BluetoothLeAudioStandard.AICS_SERVICE, 0, emptyList(), listOf(
            characteristic(BluetoothLeAudioStandard.AUDIO_INPUT_STATE, BluetoothGattInventory.Property.READ),
            characteristic(BluetoothLeAudioStandard.GAIN_SETTING_PROPERTIES, BluetoothGattInventory.Property.READ),
            characteristic(BluetoothLeAudioStandard.AUDIO_INPUT_CONTROL_POINT, BluetoothGattInventory.Property.WRITE),
        )
    )
    val inventory = BluetoothGattInventory(listOf(vcs, aics))
    val plan = BluetoothStandardReadPlan.fromInventory(inventory)
    fun target(kind: BluetoothStandardReadPlan.Kind) = plan.first { it.kind == kind }
    val data = BluetoothStandardProbeData(
        inventory,
        listOf(
            BluetoothStandardReadValue(target(BluetoothStandardReadPlan.Kind.VCS_VOLUME_STATE), byteArrayOf(120, 0, 3)),
            BluetoothStandardReadValue(target(BluetoothStandardReadPlan.Kind.AICS_INPUT_STATE), byteArrayOf(4, 0, 2, 9)),
            BluetoothStandardReadValue(target(BluetoothStandardReadPlan.Kind.AICS_GAIN_SETTING_PROPERTIES), byteArrayOf(5, (-20).toByte(), 20)),
        ),
        emptyList(),
    )
    val snap = BluetoothStandardControlProbeParser.parse(
        DeviceControlDevice("bluetooth:00:11:22:33:44:55", "test", DeviceConnectionKind.BLUETOOTH),
        1,
        data,
    )
    val vcsCap = snap.capabilities.filterIsInstance<DeviceControlCapability.Volume>().single()
    check(vcsCap.channels.single().access == DeviceControlAccess.READ_WRITE)
    check(vcsCap.mutes.single().access == DeviceControlAccess.READ_WRITE)
    val aicsGain = snap.capabilities.filterIsInstance<DeviceControlCapability.Range>().single()
    check(aicsGain.control.access == DeviceControlAccess.READ_WRITE)
    val aicsMode = snap.capabilities.filterIsInstance<DeviceControlCapability.Choice>().single()
    check(aicsMode.control.access == DeviceControlAccess.READ_WRITE)
    check(aicsMode.control.options.map { it.value } == listOf("manual", "automatic"))
    println("BluetoothStandardWritableMappingTest: OK")
}
