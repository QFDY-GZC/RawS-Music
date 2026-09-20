package com.rawsmusic.module.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCrossfadeReadinessCoordinatorTest {
    @Test
    fun returnsImmediatelyWhenPreparedAlreadyExists() = runBlocking {
        val coordinator = ManualCrossfadeReadinessCoordinator(pollIntervalMs = 1L)
        val outcome = coordinator.await(
            timeoutMs = 100L,
            isRequestCurrent = { true },
            snapshot = { "ready" },
        )

        assertEquals("ready", outcome.prepared)
        assertFalse(outcome.timedOut)
        assertFalse(outcome.requestBecameObsolete)
    }

    @Test
    fun waitsWithoutBlockingRendererOwnershipUntilPrepared() = runBlocking {
        var prepared: String? = null
        val coordinator = ManualCrossfadeReadinessCoordinator(pollIntervalMs = 1L)
        launch {
            kotlinx.coroutines.delay(12L)
            prepared = "ready"
        }

        val outcome = coordinator.await(
            timeoutMs = 200L,
            isRequestCurrent = { true },
            snapshot = { prepared },
        )

        assertEquals("ready", outcome.prepared)
        assertTrue(outcome.waitedMs >= 1L)
        assertFalse(outcome.timedOut)
    }

    @Test
    fun obsoleteRequestStopsWaitingWithoutClaimingPreparedState() = runBlocking {
        var current = true
        val coordinator = ManualCrossfadeReadinessCoordinator(pollIntervalMs = 1L)
        launch {
            kotlinx.coroutines.delay(8L)
            current = false
        }

        val outcome = coordinator.await<String>(
            timeoutMs = 200L,
            isRequestCurrent = { current },
            snapshot = { null },
        )

        assertNull(outcome.prepared)
        assertTrue(outcome.requestBecameObsolete)
        assertFalse(outcome.timedOut)
    }

    @Test
    fun terminalPrepareFailureEndsGraceImmediately() = runBlocking {
        var terminal: String? = null
        val coordinator = ManualCrossfadeReadinessCoordinator(pollIntervalMs = 1L)
        launch {
            kotlinx.coroutines.delay(8L)
            terminal = "open_returned_zero"
        }

        val outcome = coordinator.await<String>(
            timeoutMs = 1200L,
            isRequestCurrent = { true },
            snapshot = { null },
            terminalFailure = { terminal },
        )

        assertNull(outcome.prepared)
        assertEquals("open_returned_zero", outcome.terminalReason)
        assertFalse(outcome.timedOut)
        assertTrue(outcome.waitedMs < 1200L)
    }
    @Test
    fun manualRetargetPolicyOnlyReplacesDifferentActiveTarget() {
        assertTrue(
            shouldManualRetargetActiveTransition(
                transitionActive = true,
                activeTargetPath = "/music/B.flac",
                requestedPath = "/music/C.flac",
            )
        )
        assertFalse(
            shouldManualRetargetActiveTransition(
                transitionActive = true,
                activeTargetPath = "/music/B.flac",
                requestedPath = "/music/B.flac",
            )
        )
        assertFalse(
            shouldManualRetargetActiveTransition(
                transitionActive = false,
                activeTargetPath = "/music/B.flac",
                requestedPath = "/music/C.flac",
            )
        )
        assertFalse(
            shouldManualRetargetActiveTransition(
                transitionActive = true,
                activeTargetPath = null,
                requestedPath = "/music/C.flac",
            )
        )
    }

}
