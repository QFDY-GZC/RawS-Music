package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.util.UUID

/** Read-only BLE GATT inventory. It intentionally contains no characteristic values. */
data class BluetoothGattInventory(
    val services: List<Service>,
) {
    data class Service(
        val uuid: UUID,
        val type: Int,
        val includedServiceUuids: List<UUID>,
        val characteristics: List<Characteristic>,
    )

    data class Characteristic(
        val uuid: UUID,
        val properties: Set<Property>,
        val permissions: Int,
        val descriptors: List<Descriptor>,
    )

    data class Descriptor(
        val uuid: UUID,
        val permissions: Int,
    )

    enum class Property {
        BROADCAST,
        READ,
        WRITE_NO_RESPONSE,
        WRITE,
        NOTIFY,
        INDICATE,
        SIGNED_WRITE,
        EXTENDED_PROPERTIES,
    }
}

/** Pure bitmask decoder kept Android-free so it can be regression tested on the host. */
internal object BluetoothGattPropertyDecoder {
    private const val PROPERTY_BROADCAST = 0x01
    private const val PROPERTY_READ = 0x02
    private const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    private const val PROPERTY_WRITE = 0x08
    private const val PROPERTY_NOTIFY = 0x10
    private const val PROPERTY_INDICATE = 0x20
    private const val PROPERTY_SIGNED_WRITE = 0x40
    private const val PROPERTY_EXTENDED_PROPS = 0x80

    fun decode(mask: Int): Set<BluetoothGattInventory.Property> = buildSet {
        if (mask and PROPERTY_BROADCAST != 0) add(BluetoothGattInventory.Property.BROADCAST)
        if (mask and PROPERTY_READ != 0) add(BluetoothGattInventory.Property.READ)
        if (mask and PROPERTY_WRITE_NO_RESPONSE != 0) add(BluetoothGattInventory.Property.WRITE_NO_RESPONSE)
        if (mask and PROPERTY_WRITE != 0) add(BluetoothGattInventory.Property.WRITE)
        if (mask and PROPERTY_NOTIFY != 0) add(BluetoothGattInventory.Property.NOTIFY)
        if (mask and PROPERTY_INDICATE != 0) add(BluetoothGattInventory.Property.INDICATE)
        if (mask and PROPERTY_SIGNED_WRITE != 0) add(BluetoothGattInventory.Property.SIGNED_WRITE)
        if (mask and PROPERTY_EXTENDED_PROPS != 0) add(BluetoothGattInventory.Property.EXTENDED_PROPERTIES)
    }
}
