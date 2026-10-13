package com.rawsmusic.module.scanner

import org.junit.Assert.*
import org.junit.Test

class AutomaticScanPolicyTest {
    @Test fun startupUsesTwoSecondsAfterBootSettles() {
        assertEquals(2000L, AutomaticScanPolicy().startupDelayMs(60001))
    }
    @Test fun startupWaitsTenSecondsDuringBoot() {
        val policy = AutomaticScanPolicy()
        assertEquals(10000L, policy.startupDelayMs(0))
        assertEquals(10000L, policy.startupDelayMs(60000))
    }
    @Test fun repeatedChangesUseLongerWindow() {
        val policy = AutomaticScanPolicy()
        assertEquals(1000L, policy.systemChangeDelayMs(100000, 60001, false))
        assertEquals(1000L, policy.systemChangeDelayMs(100010, 60001, false))
        assertEquals(12000L, policy.systemChangeDelayMs(100020, 60001, false))
        assertEquals(1000L, policy.systemChangeDelayMs(120021, 60001, false))
    }
    @Test fun immediateChangesUseShorterWindow() {
        val policy = AutomaticScanPolicy()
        assertEquals(500L, policy.systemChangeDelayMs(100000, 60001, true))
        assertEquals(500L, policy.systemChangeDelayMs(100010, 60001, true))
        assertEquals(1000L, policy.systemChangeDelayMs(100020, 60001, true))
    }
    @Test fun bootDelayIsAddedAtBothReferenceCallSites() {
        assertEquals(21000L, AutomaticScanPolicy().systemChangeDelayMs(100000, 60000, false))
        assertEquals(20500L, AutomaticScanPolicy().systemChangeDelayMs(100000, 60000, true))
    }
    @Test fun gatesHaveExactBoundaries() {
        val policy = AutomaticScanPolicy()
        policy.initializationAtMs = 100000
        assertFalse(policy.canDispatch(102999, false, 0))
        assertTrue(policy.canDispatch(103000, false, 0))
        policy.requestsEnabledAtMs = 110000
        assertFalse(policy.canDispatch(114999, false, 0))
        assertTrue(policy.canDispatch(115000, false, 0))
        policy.fileDeletedAtMs = 120000
        assertFalse(policy.canDispatch(130999, false, 0))
        assertTrue(policy.canDispatch(131000, false, 0))
        assertFalse(policy.canDispatch(140000, true, 0))
        assertFalse(policy.canDispatch(140000, false, 1))
    }
}
