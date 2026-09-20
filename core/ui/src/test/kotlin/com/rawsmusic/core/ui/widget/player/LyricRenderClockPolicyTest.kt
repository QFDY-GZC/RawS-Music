package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRenderClockPolicyTest {
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
