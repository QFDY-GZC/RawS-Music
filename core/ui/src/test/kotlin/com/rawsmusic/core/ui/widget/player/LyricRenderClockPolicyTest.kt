package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRenderClockPolicyTest {
    @Test fun moderateRenderLagCannotRemainHundredsOfMillisecondsBehind() {
        val update = LyricRenderClockPolicy.onSourceUpdate(9700f, 9950f, 10000f)
        assertEquals(9968f, update.positionMs, 0.001f)
        assertFalse(update.hardReanchored)
    }
    @Test fun lyricSeekAndPositionUseSourceTimeAtDoubleSpeed() {
        val timeline = com.rawsmusic.core.common.utils.LyricPlaybackTimeline
        val previous = timeline.speed
        try {
            timeline.speed = 2f
            assertEquals(60000L, timeline.toLyricTime(30000L))
            assertEquals(30000L, timeline.toPlaybackTime(60000L))
            timeline.speed = 0.5f
            assertEquals(60000L, timeline.toLyricTime(120000L))
            assertEquals(120000L, timeline.toPlaybackTime(60000L))
            timeline.speed = Float.NaN
            assertEquals(1000L, timeline.toLyricTime(1000L))
        } finally {
            timeline.speed = previous
        }
    }
    @Test fun displayFramesFollowCommittedPlaybackRate() {
        assertEquals(1200f, LyricRenderClockPolicy.advance(1000f, 100f, 2f), 0f)
        assertEquals(1050f, LyricRenderClockPolicy.advance(1000f, 100f, 0.5f), 0f)
        assertEquals(1100f, LyricRenderClockPolicy.advance(1000f, 100f, Float.NaN), 0f)
    }

    @Test fun changingRateDoesNotMultiplyTheExistingPosition() {
        val first = LyricRenderClockPolicy.advance(1000f, 100f, 2f)
        assertEquals(1250f, LyricRenderClockPolicy.advance(first, 100f, 0.5f), 0f)
    }
    @Test fun delayedFramesDoNotAccumulateClockDebt() {
        var position = 1000f
        repeat(20) { position = LyricRenderClockPolicy.advance(position, 100f) }
        assertEquals(3000f, position, 0.001f)
        assertFalse(LyricRenderClockPolicy.onSourceUpdate(position, 2900f, 3000f).hardReanchored)
    }

    @Test fun invalidFrameIntervalsDoNotMoveTheClock() {
        for (delta in listOf(-20f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1000f, LyricRenderClockPolicy.advance(1000f, delta), 0f)
        }
    }

    @Test
    fun ordinaryAudioBlockCallbackDoesNotModulateRenderClock() {
        val update = LyricRenderClockPolicy.onSourceUpdate(
            renderedPositionMs = 10_020f,
            previousSourcePositionMs = 9_984f,
            sourcePositionMs = 10_000f,
        )
        assertFalse(update.hardReanchored)
        assertEquals(10_020f, update.positionMs, 0.001f)
        assertEquals(10_036.67f, LyricRenderClockPolicy.advance(update.positionMs, 16.67f), 0.01f)
    }

    @Test
    fun forwardRenderLeadIsBoundedBeforeKaraokeWordBoundary() {
        val update = LyricRenderClockPolicy.onSourceUpdate(
            renderedPositionMs = 10_180f,
            previousSourcePositionMs = 9_950f,
            sourcePositionMs = 10_000f,
        )

        assertTrue(update.hardReanchored)
        assertEquals(
            10_000f + LyricRenderClockPolicy.MAX_FORWARD_RENDER_LEAD_MS,
            update.positionMs,
            0.001f,
        )
    }

    @Test
    fun forwardSeekHardReanchors() {
        val update = LyricRenderClockPolicy.onSourceUpdate(
            renderedPositionMs = 10_000f,
            previousSourcePositionMs = 9_980f,
            sourcePositionMs = 25_000f,
        )
        assertTrue(update.hardReanchored)
        assertEquals(25_000f, update.positionMs, 0.001f)
    }

    @Test
    fun backwardSeekHardReanchorsEvenWhenPhaseErrorIsModerate() {
        val update = LyricRenderClockPolicy.onSourceUpdate(
            renderedPositionMs = 10_000f,
            previousSourcePositionMs = 10_040f,
            sourcePositionMs = 9_850f,
        )
        assertTrue(update.hardReanchored)
        assertEquals(9_850f, update.positionMs, 0.001f)
    }

    @Test
    fun continuousFramesAdvanceExactlyOneX() {
        var position = 1_000f
        repeat(120) {
            position = LyricRenderClockPolicy.advance(position, 8.333333f)
        }
        assertEquals(2_000f, position, 0.2f)
    }
}
