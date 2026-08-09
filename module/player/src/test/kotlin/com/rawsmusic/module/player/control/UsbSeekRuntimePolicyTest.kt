package com.rawsmusic.module.player.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbSeekRuntimePolicyTest {
    private val ready = UsbSeekRuntimePolicy.Snapshot(
        usbExclusiveActive = true,
        publicPreparing = false,
        decoderPreparing = false,
        transportTransitioning = false,
        seekAlreadyRunning = false,
        handle = 42L,
        engineInitialized = true,
    )

    @Test
    fun readyRuntimeIsAdmitted() {
        assertTrue(UsbSeekRuntimePolicy.isReady(ready))
    }

    @Test
    fun everyBusyOrDetachedStateIsRejected() {
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(usbExclusiveActive = false)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(publicPreparing = true)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(decoderPreparing = true)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(transportTransitioning = true)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(seekAlreadyRunning = true)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(handle = 0L)))
        assertFalse(UsbSeekRuntimePolicy.isReady(ready.copy(engineInitialized = false)))
    }

    @Test
    fun retryPacingPreservesFastThenSlowWindow() {
        assertEquals(120L, UsbSeekRuntimePolicy.retryDelayMs(0))
        assertEquals(120L, UsbSeekRuntimePolicy.retryDelayMs(3))
        assertEquals(180L, UsbSeekRuntimePolicy.retryDelayMs(4))
        assertEquals(180L, UsbSeekRuntimePolicy.retryDelayMs(11))
    }
}
