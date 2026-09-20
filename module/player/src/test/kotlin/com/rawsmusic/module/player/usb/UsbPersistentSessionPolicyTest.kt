package com.rawsmusic.module.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbPersistentSessionPolicyTest {
    private fun config(rate: Int) = UsbAudioConfig(
        iface = 0,
        alt = 0,
        outEp = 0,
        fbEp = 0,
        sampleRate = rate,
        bits = 24,
        channels = 2,
        subslot = 3,
        sourceBits = 24,
    )

    @Test
    fun allowsHealthyPcmRateOnlyReconfigure() {
        val decision = UsbPersistentSessionPolicy.decide(
            current = config(96_000),
            target = config(44_100),
            currentSourceRate = 96_000,
            targetSourceRate = 44_100,
            currentSourceBits = 24,
            targetSourceBits = 24,
            currentDsdSessionKey = null,
            targetDsdSessionKey = null,
            liveSessionHealthy = true,
            policyChanged = false,
            feedbackPolicyRequiresReinit = false,
        )

        assertEquals(UsbPersistentSessionAction.RECONFIGURE_RATE_IN_PLACE, decision.action)
        assertTrue(decision.preservePhysicalSession)
    }

    @Test
    fun allowsHealthyPcmStreamProfileChangeButRejectsPolicyChanges() {
        val geometryChange = UsbPersistentSessionPolicy.decide(
            current = config(96_000),
            target = config(44_100).copy(bits = 32, subslot = 4),
            currentSourceRate = 96_000,
            targetSourceRate = 44_100,
            currentSourceBits = 24,
            targetSourceBits = 24,
            currentDsdSessionKey = null,
            targetDsdSessionKey = null,
            liveSessionHealthy = true,
            policyChanged = false,
            feedbackPolicyRequiresReinit = false,
        )
        assertEquals(UsbPersistentSessionAction.RECONFIGURE_STREAM_PROFILE_IN_PLACE, geometryChange.action)
        assertTrue(geometryChange.preservePhysicalSession)

        val policyChange = UsbPersistentSessionPolicy.decide(
            current = config(96_000),
            target = config(44_100),
            currentSourceRate = 96_000,
            targetSourceRate = 44_100,
            currentSourceBits = 24,
            targetSourceBits = 24,
            currentDsdSessionKey = null,
            targetDsdSessionKey = null,
            liveSessionHealthy = true,
            policyChanged = true,
            feedbackPolicyRequiresReinit = false,
        )
        assertEquals(UsbPersistentSessionAction.FULL_REOPEN, policyChange.action)
    }

    @Test
    fun rejectsDsdAndPoisonedSessions() {
        val dsd = UsbPersistentSessionPolicy.decide(
            current = config(96_000),
            target = config(44_100),
            currentSourceRate = 96_000,
            targetSourceRate = 44_100,
            currentSourceBits = 24,
            targetSourceBits = 24,
            currentDsdSessionKey = "native:256",
            targetDsdSessionKey = null,
            liveSessionHealthy = true,
            policyChanged = false,
            feedbackPolicyRequiresReinit = false,
        )
        assertEquals(UsbPersistentSessionAction.FULL_REOPEN, dsd.action)

        val poisoned = UsbPersistentSessionPolicy.decide(
            current = config(96_000),
            target = config(44_100),
            currentSourceRate = 96_000,
            targetSourceRate = 44_100,
            currentSourceBits = 24,
            targetSourceBits = 24,
            currentDsdSessionKey = null,
            targetDsdSessionKey = null,
            liveSessionHealthy = false,
            policyChanged = false,
            feedbackPolicyRequiresReinit = false,
        )
        assertEquals(UsbPersistentSessionAction.FULL_REOPEN, poisoned.action)
    }

    @Test
    fun persistentControlPlanePerformsSingleBestEffortClockValidityRead() {
        val controlPlane = UsbPersistentSessionPolicy.persistentControlPlane()
        assertTrue(controlPlane.minimumControlPlane)
        assertTrue(controlPlane.verifyClockReadback)
        assertTrue(controlPlane.forceAltZeroBeforeRateChange)
    }
}
