package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidRouteRecoveryPolicyTest {
    @Test fun recoveryKeepsAvailableExplicitBluetoothRoute() {
        assertEquals(7, recoveryOutputDeviceId(0, 7, 7, setOf(2, 7)))
    }
    @Test fun notYetRoutedTrackKeepsExplicitPreference() {
        assertEquals(7, recoveryOutputDeviceId(0, 7, 0, setOf(2, 7)))
    }
    @Test fun disconnectedPreferenceFallsBackToSystem() {
        assertEquals(0, recoveryOutputDeviceId(0, 7, 7, setOf(2)))
    }
    @Test fun userOrSystemRerouteMustNotBeOverriddenByCachedPreference() {
        assertEquals(0, recoveryOutputDeviceId(0, 7, 2, setOf(2, 7)))
    }
    @Test fun explicitNewRouteWinsButUnavailableRequestCannotReviveOldRoute() {
        assertEquals(8, recoveryOutputDeviceId(8, 7, 7, setOf(2, 7, 8)))
        assertEquals(0, recoveryOutputDeviceId(8, 7, 7, setOf(2, 7)))
    }
}
