package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceNumericRange
import com.rawsmusic.module.player.devicecontrol.GraphicEqBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbVendorAdapterRegistryTest {
    private val device = DeviceControlDevice(
        stableId = "usb:1234:5678",
        displayName = "Fixture DSP",
        connectionKind = DeviceConnectionKind.USB,
        vendorId = 0x1234,
        productId = 0x5678,
    )
    private val inventory = UsbVendorControlInventory(
        device = device,
        extensionUnits = listOf(
            UsbVendorControlInventory.ExtensionUnit(1, 9, 0x4455, listOf(5), listOf(0x03)),
        ),
        hidInterfaces = emptyList(),
        vendorInterfaces = emptyList(),
    )

    @Test
    fun `highest specificity adapter wins and can generate five bands dynamically`() {
        val low = fixtureAdapter("low", 10)
        val high = fixtureAdapter("fixture.dsp.v1", 100)
        val registry = UsbVendorAdapterRegistry(listOf(low, high))
        val match = registry.select(device, inventory)
        assertTrue(match is UsbVendorAdapterRegistry.Match.Selected)
        assertEquals("fixture.dsp.v1", (match as UsbVendorAdapterRegistry.Match.Selected).adapter.adapterId)

        val bands = (0 until 5).map { index ->
            GraphicEqBand(
                id = DeviceControlId("vendor.eq.$index"),
                frequencyHz = listOf(60.0, 230.0, 910.0, 3_600.0, 14_000.0)[index],
                gain = DeviceNumericControl(
                    id = DeviceControlId("vendor.eq.$index.gain"),
                    label = "Band ${index + 1}",
                    unit = "dB",
                    access = DeviceControlAccess.READ_WRITE,
                    current = 0.0,
                    ranges = listOf(DeviceNumericRange(-6.0, 6.0, 0.5)),
                    address = DeviceControlBackendAddress.UsbVendor(
                        adapterId = "fixture.dsp.v1",
                        endpointKey = "eq.band.$index",
                    ),
                ),
            )
        }
        val capability = DeviceControlCapability.GraphicEq(
            id = DeviceControlId("vendor.eq"),
            label = "Hardware EQ",
            bands = bands,
        )
        assertEquals(5, capability.bands.size)
    }

    @Test
    fun `equal top scores are rejected as ambiguous`() {
        val registry = UsbVendorAdapterRegistry(
            listOf(fixtureAdapter("a", 100), fixtureAdapter("b", 100)),
        )
        val match = registry.select(device, inventory)
        assertTrue(match is UsbVendorAdapterRegistry.Match.Ambiguous)
        assertEquals(listOf("a", "b"), (match as UsbVendorAdapterRegistry.Match.Ambiguous).adapterIds)
    }

    private fun fixtureAdapter(id: String, score: Int) = object : UsbVendorDeviceAdapter {
        override val adapterId = id
        override fun match(device: DeviceControlDevice, inventory: UsbVendorControlInventory): Int = score
        override suspend fun probe(context: UsbVendorAdapterContext) =
            UsbVendorAdapterProbeResult.Unsupported("fixture")
    }
}
