package com.rawsmusic.module.player.devicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlModelTest {
    @Test
    fun `uac2 access pair is decoded without treating reserved pair as writable`() {
        assertEquals(DeviceControlAccess.ABSENT, DeviceControlAccess.fromUac2Pair(0b00))
        assertEquals(DeviceControlAccess.READ_ONLY, DeviceControlAccess.fromUac2Pair(0b01))
        assertEquals(DeviceControlAccess.ABSENT, DeviceControlAccess.fromUac2Pair(0b10))
        assertEquals(DeviceControlAccess.READ_WRITE, DeviceControlAccess.fromUac2Pair(0b11))
        assertFalse(DeviceControlAccess.READ_ONLY.isWritable)
        assertTrue(DeviceControlAccess.READ_WRITE.isWritable)
    }

    @Test
    fun `graphic eq band count is data driven`() {
        val address = DeviceControlBackendAddress.UsbAudioClass(1, 5, 6, 0)
        val bands = listOf(63.0, 250.0, 1_000.0, 4_000.0, 16_000.0).mapIndexed { index, hz ->
            GraphicEqBand(
                id = DeviceControlId("eq.$index"),
                frequencyHz = hz,
                gain = DeviceNumericControl(
                    id = DeviceControlId("eq.$index.gain"),
                    label = "$hz Hz",
                    unit = "dB",
                    access = DeviceControlAccess.READ_WRITE,
                    current = 0.0,
                    ranges = listOf(DeviceNumericRange(-12.0, 12.0, 0.5)),
                    address = address.copy(channel = index + 1),
                ),
            )
        }
        val capability = DeviceControlCapability.GraphicEq(
            id = DeviceControlId("eq"),
            label = "Graphic EQ",
            bands = bands,
        )

        assertEquals(5, capability.bands.size)
        assertEquals(16_000.0, capability.bands.last().frequencyHz ?: 0.0, 0.0)
    }
}
