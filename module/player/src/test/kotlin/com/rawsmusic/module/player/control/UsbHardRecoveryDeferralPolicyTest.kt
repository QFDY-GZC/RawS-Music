package com.rawsmusic.module.player.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHardRecoveryDeferralPolicyTest {
    @Test
    fun backgroundPlaybackAndRecentTransitionDeferRecovery() {
        val playing = UsbHardRecoveryDeferralPolicy.evaluate(
            UsbHardRecoveryDeferralPolicy.Snapshot(
                exclusiveActive = true,
                appInBackground = true,
                backgroundEnteredAtMs = 1_000L,
                nowMs = 10_000L,
                playing = true,
                transportTransitioning = false,
                usbSeeking = false,
                recovering = false,
            )
        )
        assertTrue(playing.defer)
        assertTrue(playing.playingInBackground)

        val recent = UsbHardRecoveryDeferralPolicy.evaluate(
            UsbHardRecoveryDeferralPolicy.Snapshot(
                exclusiveActive = true,
                appInBackground = false,
                backgroundEnteredAtMs = 8_500L,
                nowMs = 10_000L,
                playing = false,
                transportTransitioning = false,
                usbSeeking = false,
                recovering = false,
            )
        )
        assertTrue(recent.defer)
        assertTrue(recent.recentlyBackgrounded)
    }

    @Test
    fun stableIdleForegroundAllowsRecovery() {
        val decision = UsbHardRecoveryDeferralPolicy.evaluate(
            UsbHardRecoveryDeferralPolicy.Snapshot(
                exclusiveActive = true,
                appInBackground = false,
                backgroundEnteredAtMs = 1_000L,
                nowMs = 10_000L,
                playing = false,
                transportTransitioning = false,
                usbSeeking = false,
                recovering = false,
            )
        )
        assertFalse(decision.defer)
    }
}
