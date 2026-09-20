package com.rawsmusic.module.player.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionTelemetryTest {
    @Test
    fun firstOutputCompletesPlayAndKeepsBoundedHistory() {
        var now = 0L
        val logs = mutableListOf<String>()
        val telemetry = PlaybackTransitionTelemetry(
            tag = "test",
            maxEvents = 16,
            nanoTime = { now += 1_000_000L; now },
            logSink = logs::add,
            nativeContextSink = { _, _ -> },
        )
        val coordinator = FfmpegTransitionTraceCoordinator(telemetry)

        coordinator.beginPlay("/music/a.flac", generation = 7)
        repeat(24) {
            telemetry.record(
                coordinator.currentPlay(),
                PlaybackTransitionPhase.DECODER_OPEN_BEGIN,
                detail = "n=$it",
            )
        }
        coordinator.onPcmSubmitted(frames = 256, renderedFrame = 256, backend = "test")

        val snapshot = coordinator.snapshot()
        assertEquals(16, snapshot.events.size)
        assertFalse(snapshot.activeTransitionIds.isNotEmpty())
        assertTrue(snapshot.events.any { it.phase == PlaybackTransitionPhase.FIRST_PCM_SUBMITTED })
        assertTrue(logs.all { it.startsWith("TRANSITION_TRACE") })
    }

    @Test
    fun gaplessCommitFinishesOnlyAfterFirstNewOutput() {
        var now = 0L
        val telemetry = PlaybackTransitionTelemetry(
            tag = "test",
            nanoTime = { now += 1_000_000L; now },
            logSink = {},
            nativeContextSink = { _, _ -> },
        )
        val coordinator = FfmpegTransitionTraceCoordinator(telemetry)
        coordinator.beginPlay("/music/a.flac", 1)
        coordinator.onPcmSubmitted(256, 256, "test")

        val handoff = coordinator.ensureHandoff(
            reason = PlaybackTransitionReason.NATURAL_GAPLESS,
            generation = 1,
            sourcePath = "/music/a.flac",
            targetPath = "/music/b.flac",
        )
        coordinator.handoffCommitted("sample_boundary")
        assertTrue(coordinator.snapshot().activeTransitionIds.contains(handoff.transitionId))

        coordinator.onPcmSubmitted(256, 512, "test")
        val snapshot = coordinator.snapshot()
        assertFalse(snapshot.activeTransitionIds.contains(handoff.transitionId))
        assertTrue(snapshot.events.any {
            it.token?.transitionId == handoff.transitionId &&
                it.phase == PlaybackTransitionPhase.HANDOFF_COMMITTED
        })
        assertTrue(snapshot.events.any {
            it.token?.transitionId == handoff.transitionId &&
                it.phase == PlaybackTransitionPhase.FIRST_PCM_SUBMITTED
        })
    }

    @Test
    fun newerSeekCancelsOlderToken() {
        var now = 0L
        val telemetry = PlaybackTransitionTelemetry(
            tag = "test",
            nanoTime = { now += 1_000_000L; now },
            logSink = {},
            nativeContextSink = { _, _ -> },
        )
        val coordinator = FfmpegTransitionTraceCoordinator(telemetry)
        coordinator.beginPlay("/music/a.flac", 2)

        val first = coordinator.beginSeek(1000, 2, "/music/a.flac")
        val second = coordinator.beginSeek(2000, 2, "/music/a.flac")
        val snapshot = coordinator.snapshot()

        assertFalse(snapshot.activeTransitionIds.contains(first.transitionId))
        assertTrue(snapshot.activeTransitionIds.contains(second.transitionId))
        assertTrue(snapshot.events.any {
            it.token?.transitionId == first.transitionId &&
                it.phase == PlaybackTransitionPhase.CANCELLED
        })
    }
}
