package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals

class UsbTrackBoundaryPolicyTest {
    @Test
    fun noneModeStillGetsTransportSafetyFade() {
        assertEquals(30, UsbTrackBoundaryPolicy.resolveFadeMs(0))
    }

    @Test
    fun configuredManualFadeIsPreserved() {
        assertEquals(400, UsbTrackBoundaryPolicy.resolveFadeMs(400))
    }
}
