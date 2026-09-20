package com.rawsmusic.module.player.devicecontrol.bluetooth

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothVendorGattAdapterRegistryTest {
    private val serviceUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val device = DeviceControlDevice(
        stableId = "bluetooth:00:11:22:33:44:55",
        displayName = "Fixture Buds",
        connectionKind = DeviceConnectionKind.BLUETOOTH,
    )
    private val inventory = BluetoothGattInventory(
        services = listOf(
            BluetoothGattInventory.Service(
                uuid = serviceUuid,
                type = 0,
                includedServiceUuids = emptyList(),
                characteristics = emptyList(),
            ),
        ),
    )

    @Test
    fun `vendor registry selects only explicit protocol match`() {
        val none = fixtureAdapter("other", UUID.randomUUID(), 100)
        val exact = fixtureAdapter("fixture.buds.v1", serviceUuid, 100)
        val match = BluetoothVendorGattAdapterRegistry(listOf(none, exact)).select(device, inventory)
        assertTrue(match is BluetoothVendorGattAdapterRegistry.Match.Selected)
        assertEquals(
            "fixture.buds.v1",
            (match as BluetoothVendorGattAdapterRegistry.Match.Selected).adapter.adapterId,
        )
    }

    @Test
    fun `unknown service produces no vendor match`() {
        val match = BluetoothVendorGattAdapterRegistry(
            listOf(fixtureAdapter("other", UUID.randomUUID(), 100)),
        ).select(device, inventory)
        assertTrue(match is BluetoothVendorGattAdapterRegistry.Match.None)
    }

    private fun fixtureAdapter(id: String, uuid: UUID, score: Int) = object : BluetoothVendorGattAdapter {
        override val adapterId = id
        override fun match(device: DeviceControlDevice, inventory: BluetoothGattInventory): Int =
            if (inventory.services.any { it.uuid == uuid }) score else 0

        override suspend fun probe(context: BluetoothVendorGattAdapterContext) =
            BluetoothVendorGattProbeResult.Unsupported("fixture")
    }
}
