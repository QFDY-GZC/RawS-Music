package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimeRetentionPolicyTest {
    @Test
    fun pausedRuntimeSurvivesUiHostDestruction() {
        assertTrue(
            PlaybackRuntimeRetentionPolicy.shouldRetain(
                controllerState = PlayState.PAUSED,
                serviceState = PlayState.PAUSED,
                usbActive = false,
                persistedUsbActive = false,
                hasRequestedSong = false,
            )
        )
    }

    @Test
    fun preparingOrQueuedSelectionSurvivesUiHostDestruction() {
        assertTrue(
            PlaybackRuntimeRetentionPolicy.shouldRetain(
                controllerState = PlayState.PREPARING,
                serviceState = PlayState.IDLE,
                usbActive = false,
                persistedUsbActive = false,
                hasRequestedSong = false,
            )
        )
        assertTrue(
            PlaybackRuntimeRetentionPolicy.shouldRetain(
                controllerState = PlayState.IDLE,
                serviceState = PlayState.IDLE,
                usbActive = false,
                persistedUsbActive = false,
                hasRequestedSong = true,
            )
        )
    }

    @Test
    fun trulyIdleRuntimeMayBeDisposed() {
        assertFalse(
            PlaybackRuntimeRetentionPolicy.shouldRetain(
                controllerState = PlayState.IDLE,
                serviceState = PlayState.IDLE,
                usbActive = false,
                persistedUsbActive = false,
                hasRequestedSong = false,
            )
        )
    }
}
