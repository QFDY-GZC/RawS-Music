package com.rawsmusic.module.player.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class UsbBackgroundPlaybackCoordinatorTest {
    private fun activeSnapshot() = UsbBackgroundPlaybackCoordinator.Snapshot(
        released = false,
        exclusiveActive = true,
        engineExclusiveMode = true,
        appInBackground = true,
        controllerPlaying = true,
        controllerPreparing = false,
        backendPlaying = false,
        backendPreparing = false,
    )

    @Test
    fun sustainRequiresBackgroundExclusivePlayback() {
        val coordinator = UsbBackgroundPlaybackCoordinator(clockMs = { 1_000L })
        assertTrue(coordinator.shouldSustain(activeSnapshot()))
        assertFalse(coordinator.shouldSustain(activeSnapshot().copy(appInBackground = false)))
        assertFalse(coordinator.shouldSustain(activeSnapshot().copy(exclusiveActive = false)))
        assertFalse(
            coordinator.shouldSustain(
                activeSnapshot().copy(controllerPlaying = false, backendPlaying = false)
            )
        )
    }

    @Test
    fun idleReleaseRejectsPlayingOrPreparingStates() {
        val coordinator = UsbBackgroundPlaybackCoordinator(clockMs = { 1_000L })
        val idle = activeSnapshot().copy(controllerPlaying = false)
        assertTrue(coordinator.shouldReleaseIdle(idle))
        assertFalse(coordinator.shouldReleaseIdle(idle.copy(controllerPreparing = true)))
        assertFalse(coordinator.shouldReleaseIdle(idle.copy(backendPreparing = true)))
    }

    @Test
    fun reinforceLoggingIsThrottled() {
        var now = 10_000L
        val coordinator = UsbBackgroundPlaybackCoordinator(clockMs = { now })
        assertTrue(coordinator.shouldLogReinforce())
        now += 1_000L
        assertFalse(coordinator.shouldLogReinforce())
        now += 9_000L
        assertTrue(coordinator.shouldLogReinforce())
    }
    @Test
    fun delayedIdleReleaseRechecksTheLatestSnapshot() = runBlocking {
        var snapshot = activeSnapshot().copy(controllerPlaying = false)
        var releases = 0
        val coordinator = UsbBackgroundPlaybackCoordinator(
            clockMs = { 1_000L },
            scope = this,
            snapshotProvider = { snapshot },
            releaseIdleResources = { releases += 1 },
        )

        coordinator.scheduleIdleRelease("idle", delayMs = 1L)
        delay(20L)
        assertEquals(1, releases)

        snapshot = activeSnapshot().copy(controllerPlaying = false)
        coordinator.scheduleIdleRelease("cancelled", delayMs = 10L)
        snapshot = activeSnapshot()
        delay(30L)
        assertEquals(1, releases)
    }

}
