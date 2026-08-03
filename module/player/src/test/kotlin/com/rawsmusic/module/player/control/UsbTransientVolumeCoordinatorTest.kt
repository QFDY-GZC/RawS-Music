package com.rawsmusic.module.player.control

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransientVolumeCoordinatorTest {
    @Test
    fun criticalWindowAndSafetyRouteRemainTransient() = runBlocking {
        var now = 1_000L
        var hardwareRoute = true
        var transientDb: Int? = null
        var cachedDb: Int? = null
        var softwareGain: Float? = null
        val coordinator = UsbTransientVolumeCoordinator(
            UsbTransientVolumeCoordinator.Callbacks(
                elapsedRealtimeMs = { now },
                isReleased = { false },
                isExclusiveActive = { true },
                isHardwareRouteActive = { hardwareRoute },
                routeDescription = { if (hardwareRoute) "HardwareUserVolume" else "Software" },
                currentHandle = { 7L },
                canControlHardwareVolume = { true },
                currentUserHardwareDb = { -12 },
                setTransientHardwareDb = { db, _ -> transientDb = db },
                setCachedHardwareDb = { _, db -> cachedDb = db },
                setSoftwareGain = { softwareGain = it },
                logInfo = {},
            )
        )

        coordinator.enterCriticalStartup("test", 500L)
        assertTrue(coordinator.isCriticalStartup())
        now = 1_501L
        assertFalse(coordinator.isCriticalStartup())

        coordinator.applyNoDataSafety("pause")
        assertEquals(-35, transientDb)
        assertEquals(null, cachedDb)

        hardwareRoute = false
        coordinator.applyNoDataSafety("software")
        assertTrue((softwareGain ?: 1f) < 0.02f)
    }

    @Test
    fun finalRampMayRestoreCachedUserVolume() = runBlocking {
        val writes = mutableListOf<Int>()
        var cachedDb: Int? = null
        val coordinator = UsbTransientVolumeCoordinator(
            UsbTransientVolumeCoordinator.Callbacks(
                elapsedRealtimeMs = { 0L },
                isReleased = { false },
                isExclusiveActive = { true },
                isHardwareRouteActive = { true },
                routeDescription = { "HardwareUserVolume" },
                currentHandle = { 11L },
                canControlHardwareVolume = { true },
                currentUserHardwareDb = { -10 },
                setTransientHardwareDb = { db, _ -> writes += db },
                setCachedHardwareDb = { _, db -> cachedDb = db },
                setSoftwareGain = {},
                logInfo = {},
            )
        )

        coordinator.rampHardwareDb(-13, -10, "resume", stepDelayMs = 0L, cacheFinal = true)
        assertEquals(listOf(-13, -12, -11), writes)
        assertEquals(-10, cachedDb)
    }
}
