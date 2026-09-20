package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessPlanHandoffGateTest {
    @Test
    fun nextAfterCurrentPlanWaitsUntilAudiblePendingTrackCommits() {
        val gate = GaplessPlanHandoffGate()
        gate.armRenderedPending("B.flac", decoderSerial = 35L, generation = 8)

        assertTrue(
            gate.deferPlanIfCommitting(
                generation = 8,
                nextPath = "C.flac",
                automaticCrossfadeEnabled = false,
                reason = "queue_advance",
            )
        )

        val deferred = gate.commit(
            path = "B.flac",
            generation = 8,
            expectedDecoderSerial = 35L,
        )
        assertEquals("C.flac", deferred?.nextPath)
        assertEquals("queue_advance", deferred?.reason)
        assertFalse(
            gate.deferPlanIfCommitting(
                generation = 8,
                nextPath = "D.flac",
                automaticCrossfadeEnabled = false,
                reason = "after_commit",
            )
        )
    }

    @Test
    fun newerGenerationIsNeverBlockedByStaleHandoff() {
        val gate = GaplessPlanHandoffGate()
        gate.armRenderedPending("B.flac", decoderSerial = 35L, generation = 8)
        assertFalse(
            gate.deferPlanIfCommitting(
                generation = 9,
                nextPath = "X.flac",
                automaticCrossfadeEnabled = false,
                reason = "new_session",
            )
        )
    }

    @Test
    fun mismatchedSerialCannotReleaseBarrier() {
        val gate = GaplessPlanHandoffGate()
        gate.armRenderedPending("B.flac", decoderSerial = 35L, generation = 8)
        assertTrue(gate.deferPlanIfCommitting(8, "C.flac", false, "queue_advance"))
        assertNull(gate.commit("B.flac", 8, expectedDecoderSerial = 36L))
        assertTrue(gate.deferPlanIfCommitting(8, "D.flac", false, "latest_plan_wins"))
        assertEquals(
            "D.flac",
            gate.commit("B.flac", 8, expectedDecoderSerial = 35L)?.nextPath,
        )
    }
}
