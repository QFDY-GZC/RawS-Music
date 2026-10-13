package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class SystemMediaCompatibilityTest {
    @Test fun largeSystemArtworkKeepsItsDecodedResolution() {
        assertEquals(1200 to 900, systemMediaArtworkSize(1200, 900))
        assertEquals(600 to 1200, systemMediaArtworkSize(600, 1200))
        assertEquals(128 to 128, systemMediaArtworkSize(128, 128))
    }

    @Test fun bluetoothPlaybackContinuesPulsingWhileLyricsDoNotChange() {
        val pulse = BluetoothPlaybackPulse()
        assertTrue(pulse.shouldPublish(true, true, 0))
        assertFalse(pulse.shouldPublish(true, true, 499))
        assertTrue(pulse.shouldPublish(true, true, 500))
        assertFalse(pulse.shouldPublish(true, true, 501))
        assertTrue(pulse.shouldPublish(true, true, 1000))
    }

    @Test fun pauseAndDisabledModeNeverPublishPlayingPulses() {
        val pulse = BluetoothPlaybackPulse()
        assertFalse(pulse.shouldPublish(false, true, 0))
        assertFalse(pulse.shouldPublish(true, false, 500))
        assertTrue(pulse.shouldPublish(true, true, 600))
        assertFalse(pulse.shouldPublish(true, false, 700))
        assertTrue(pulse.shouldPublish(true, true, 710))
    }
}
