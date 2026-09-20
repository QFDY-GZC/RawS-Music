package com.rawsmusic.module.player.devicecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceControlDynamicBandsTest {
    @Test
    fun `graphic eq preserves every device reported band without a fixed cap`() {
        listOf(6, 10, 20, 30).forEach { count ->
            val capability = graphicEq(count)
            assertEquals(count, capability.bands.size)

            val report = DeviceControlDiagnostics.render(
                DeviceControlSnapshot(
                    generation = count.toLong(),
                    device = DeviceControlDevice(
                        stableId = "usb:1234:5678",
                        displayName = "Dynamic EQ fixture",
                        connectionKind = DeviceConnectionKind.USB,
                    ),
                    capabilities = listOf(capability),
                    probeState = DeviceControlSnapshot.ProbeState.READY,
                ),
            )
            assertTrue("bands=$count" in report)
            assertTrue((0 until count).all { "band[$it]" in report })
        }
    }

    private fun graphicEq(count: Int): DeviceControlCapability.GraphicEq {
        val baseAddress = DeviceControlBackendAddress.UsbAudioClass(
            interfaceNumber = 0,
            entityId = 5,
            selector = 6,
            channel = 0,
        )
        val bands = (0 until count).map { index ->
            val gain = DeviceNumericControl(
                id = DeviceControlId("fixture.eq.band.$index.gain"),
                label = "Band ${index + 1}",
                unit = "dB",
                access = DeviceControlAccess.READ_WRITE,
                current = 0.0,
                ranges = listOf(DeviceNumericRange(-12.0, 12.0, 0.5)),
                address = baseAddress.copy(elementIndex = index),
            )
            GraphicEqBand(
                id = DeviceControlId("fixture.eq.band.$index"),
                frequencyHz = 20.0 * (index + 1),
                gain = gain,
            )
        }
        return DeviceControlCapability.GraphicEq(
            id = DeviceControlId("fixture.eq.$count"),
            label = "$count-band EQ",
            bands = bands,
        )
    }
}
