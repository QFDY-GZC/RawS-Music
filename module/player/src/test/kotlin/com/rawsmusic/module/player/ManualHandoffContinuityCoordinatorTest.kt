package com.rawsmusic.module.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualHandoffContinuityCoordinatorTest {
    @Test fun nextCommandWaitsUntilMixAndDecoderOwnershipBothFinish() = runBlocking {
        var time = 0L
        var checks = 0
        var ownershipChecks = 0
        val coordinator = ManualHandoffContinuityCoordinator(
            ManualCrossfadeReadinessCoordinator(1) { time++ })
        val outcome = coordinator.await(100, { true },
            mixing = { ++checks < 3 },
            ownershipPending = { ++ownershipChecks < 3 })
        assertNotNull(outcome.prepared)
        assertTrue(checks >= 3)
        assertTrue(ownershipChecks >= 3)
        assertTrue(outcome.waitedMs > 0)
    }

    @Test fun SupersededCommandReturnsWithoutClaimingNewTrack() = runBlocking {
        var time = 0L
        var requestChecks = 0
        val coordinator = ManualHandoffContinuityCoordinator(
            ManualCrossfadeReadinessCoordinator(1) { time++ })
        val outcome = coordinator.await(100, { ++requestChecks < 3 }, { true }, { true })
        assertTrue(outcome.requestBecameObsolete)
        assertNull(outcome.prepared)
    }

    @Test fun idleRendererNeedsNoExtraWait() = runBlocking {
        val outcome = ManualHandoffContinuityCoordinator().await(100,
            { true }, { false }, { false })
        assertNotNull(outcome.prepared)
        assertEquals(0L, outcome.waitedMs)
    }

    @Test fun stalledHandoffHasABoundedWaitWithoutPretendingToCommit() = runBlocking {
        var time = 0L
        val coordinator = ManualHandoffContinuityCoordinator(
            ManualCrossfadeReadinessCoordinator(1) { time += 10; time })
        val outcome = coordinator.await(30, { true }, { true }, { true })
        assertTrue(outcome.timedOut)
        assertNull(outcome.prepared)
    }
}
