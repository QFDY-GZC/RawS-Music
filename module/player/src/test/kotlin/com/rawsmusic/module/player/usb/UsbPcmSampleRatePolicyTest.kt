package com.rawsmusic.module.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbPcmSampleRatePolicyTest {
    @Test
    fun autoUsesDeviceAdvertisedCeilingInsteadOfGlobal44100() {
        val d = UsbPcmSampleRatePolicy.choose(
            sourceRate = 44_100,
            requestedRate = 0,
            advertisedRates = listOf(48_000, 96_000),
        )
        assertEquals(48_000, d.selectedRate)
        assertFalse(d.requestedRateRejected)
    }

    @Test
    fun autoPreserves44100WhenDeviceActuallyAdvertisesIt() {
        val d = UsbPcmSampleRatePolicy.choose(
            sourceRate = 44_100,
            requestedRate = 0,
            advertisedRates = listOf(44_100, 48_000, 96_000),
        )
        assertEquals(44_100, d.selectedRate)
    }

    @Test
    fun staleExplicitRateFallsBackToNearestCurrentDeviceRate() {
        val d = UsbPcmSampleRatePolicy.choose(
            sourceRate = 44_100,
            requestedRate = 44_100,
            advertisedRates = listOf(48_000, 96_000),
        )
        assertEquals(48_000, d.selectedRate)
        assertTrue(d.requestedRateRejected)
    }
}
