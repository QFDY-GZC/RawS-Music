package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSoftwareVolumeCurveTest {
    @Test
    fun sixtyDbRangeMakesFirstSixteenStepLevelVeryQuiet() {
        val firstStep = 1f / 16f

        assertEquals(0f, UsbSoftwareVolumeCurve.gainForLocalStream(0f, 60), 0f)
        assertEquals(
            0.00153993f,
            UsbSoftwareVolumeCurve.gainForLocalStream(firstStep, 60),
            0.0000001f,
        )
        assertEquals(
            0.0316228f,
            UsbSoftwareVolumeCurve.gainForLocalStream(0.5f, 60),
            0.000001f,
        )
        assertEquals(1f, UsbSoftwareVolumeCurve.gainForLocalStream(1f, 60), 0f)
    }

    @Test
    fun curveIsMonotonicAcrossWholeUiRange() {
        var previous = 0f
        for (i in 0..1000) {
            val volume = i / 1000f
            val gain = UsbSoftwareVolumeCurve.gainForLocalStream(volume, 60)
            assertTrue(gain >= previous)
            previous = gain
        }
    }

    @Test
    fun largerRangeMakesSameUiPositionQuieter() {
        val volume = 1f / 16f
        val gain40 = UsbSoftwareVolumeCurve.gainForLocalStream(volume, 40)
        val gain60 = UsbSoftwareVolumeCurve.gainForLocalStream(volume, 60)
        val gain80 = UsbSoftwareVolumeCurve.gainForLocalStream(volume, 80)

        assertTrue(gain80 < gain60)
        assertTrue(gain60 < gain40)
    }

    @Test
    fun rangeIsClampedToSupportedBounds() {
        val volume = 0.5f
        assertEquals(
            UsbSoftwareVolumeCurve.gainForLocalStream(volume, 40),
            UsbSoftwareVolumeCurve.gainForLocalStream(volume, 1),
            0f,
        )
        assertEquals(
            UsbSoftwareVolumeCurve.gainForLocalStream(volume, 80),
            UsbSoftwareVolumeCurve.gainForLocalStream(volume, 200),
            0f,
        )
    }
}
