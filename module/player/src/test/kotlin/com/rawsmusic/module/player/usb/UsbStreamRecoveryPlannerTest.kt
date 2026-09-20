package com.rawsmusic.module.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Native owns recovery policy. Host JVM tests intentionally exercise only the conservative
 * no-native fallback; policy branches are covered by src/test/cpp/raw_usb_recovery_policy_test.cpp.
 */
class UsbStreamRecoveryPlannerTest {
    @Test
    fun nativeUnavailableTransportFailureUsesNonLearningFullReopen() {
        val plan = UsbStreamRecoveryPlanner.plan(
            kind = UsbSilentKind.TransportError,
            stats = null,
            profile = null,
            detail = "host_test",
        )

        assertEquals(UsbRecoveryAction.FullReopen, plan.action)
        assertTrue(plan.forceFullReopen)
        assertFalse(plan.disableFeedback)
        assertFalse(plan.disableClockSet)
        assertFalse(plan.disableFeatureUnit)
        assertFalse(plan.shouldRecordLearnedPolicy)
    }

    @Test
    fun nativeUnavailableProducerDipRemainsObserveOnly() {
        val plan = UsbStreamRecoveryPlanner.plan(
            kind = UsbSilentKind.DecoderNotFeeding,
            stats = null,
            profile = null,
            detail = "host_test",
        )
        assertEquals(UsbRecoveryAction.Observe, plan.action)
        assertFalse(plan.requiresProfileRestart)
    }
}
