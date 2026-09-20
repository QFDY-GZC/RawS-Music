package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackStartedPresentationCoordinatorTest {
    @Test
    fun `first rendered pending frames start target track`() {
        val coordinator = TrackStartedPresentationCoordinator("test")
        coordinator.arm(
            path = "next.flac",
            decoderSerial = 22L,
            generation = 3,
            sampleRate = 48_000,
            durationMs = 180_000L,
        )

        val first = coordinator.onPendingFramesRendered(22L, 3, 480L)!!
        assertTrue(first.startedNow)
        assertEquals(10L, first.positionMs)
        assertEquals(180_000L, coordinator.activeDurationMsOrNull())

        val second = coordinator.onPendingFramesRendered(22L, 3, 960L)!!
        assertFalse(second.startedNow)
        assertEquals(30L, second.positionMs)
    }

    @Test
    fun `unrelated serial cannot start or advance presentation`() {
        val coordinator = TrackStartedPresentationCoordinator("test")
        coordinator.arm("target.flac", 44L, 8, 44_100, 10_000L)

        assertEquals(null, coordinator.onPendingFramesRendered(45L, 8, 441L))
        assertEquals(null, coordinator.activePositionMsOrNull())
    }

    @Test
    fun `commit returns monotonic renderer position and releases override`() {
        val coordinator = TrackStartedPresentationCoordinator("test")
        coordinator.arm("next.flac", 55L, 9, 48_000, 10_000L)
        coordinator.onPendingFramesRendered(55L, 9, 9_600L)

        assertEquals(200L, coordinator.commit("next.flac", rendererPositionMs = 150L))
        assertEquals(null, coordinator.activePositionMsOrNull())
    }
    @Test
    fun `current decoder can be registered before first handoff`() {
        val coordinator = TrackStartedPresentationCoordinator("test")
        // JVM tests normally use the compatibility fallback, so the return value may be false.
        coordinator.installCurrent(11L, 2, 48_000, positionMs = 500L)
        coordinator.arm("next.flac", 12L, 2, 48_000, 10_000L)
        val started = coordinator.onPendingFramesRendered(12L, 2, 480L)!!
        assertTrue(started.startedNow)
        assertEquals(10L, started.positionMs)
    }

}
