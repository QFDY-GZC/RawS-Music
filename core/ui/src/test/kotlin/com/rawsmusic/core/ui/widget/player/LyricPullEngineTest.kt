package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPullEngineTest {
    @Test
    fun firstFrameStartsWithoutPlacementJump() {
        val engine = LyricPullEngine()
        val visible = listOf(4, 5, 6, 7)
        assertTrue(engine.beginForwardPull(4, 280f, 900f, visible, 2_000L))

        val firstFrame = engine.advance(2_000L, visible)
        visible.drop(1).forEach { index ->
            assertEquals(0f, firstFrame.offsetsPx.getValue(index), 0.0001f)
        }
    }

    @Test
    fun followingRowsUseWaitingPullingDoneLifecycle() {
        val engine = LyricPullEngine()
        val visible = listOf(10, 11, 12, 13)
        assertTrue(engine.beginForwardPull(10, 300f, 1_000f, visible, 1_000L))

        val delay = LyricPullSpec.itemDelayMs(300f, 1_000f)
        val beforeFirst = engine.advance(1_000L + delay - 1L, visible)
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(11))
        assertTrue(beforeFirst.offsetsPx.getValue(11) in 0f..300f)

        engine.advance(1_000L + delay, visible)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(11))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(12))

        val finished = engine.advance(
            1_000L + delay * 3L + LyricPullSpec.DURATION_MS,
            visible
        )
        assertFalse(finished.running)
        assertEquals(0f, finished.offsetsPx.getValue(13), 0.0001f)
        assertEquals(LyricPullItemPhase.Done, engine.phaseOf(13))
    }

    @Test
    fun laterRowsNeverOvertakePreviousRows() {
        val engine = LyricPullEngine()
        val visible = listOf(20, 21, 22, 23, 24)
        engine.beginForwardPull(20, 420f, 900f, visible, 5_000L)

        val frame = engine.advance(5_180L, visible)
        val offsets = visible.drop(1).map { frame.offsetsPx.getValue(it) }
        offsets.zipWithNext().forEach { (previous, next) ->
            assertTrue(next >= previous)
        }
    }

    @Test
    fun consecutiveForwardChangeRetargetsFromCurrentPlacement() {
        val engine = LyricPullEngine()
        val visible = listOf(30, 31, 32, 33, 34)
        engine.beginForwardPull(30, 240f, 1_000f, visible, 10_000L)
        val mid = engine.advance(10_240L, visible).offsetsPx.getValue(32)
        assertTrue(mid in 0f..240f)

        engine.beginForwardPull(31, 180f, 1_000f, visible, 10_240L)
        val retargeted = engine.advance(10_240L, visible).offsetsPx.getValue(32)
        assertEquals(mid, retargeted, 0.001f)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(32))
    }

    @Test
    fun resetClearsEveryPlacementImmediately() {
        val engine = LyricPullEngine()
        val visible = listOf(1, 2, 3)
        engine.beginForwardPull(1, 200f, 800f, visible, 0L)
        assertTrue(engine.advance(20L, visible).running)

        val reset = engine.reset()
        assertFalse(reset.running)
        assertTrue(reset.offsetsPx.isEmpty())
        assertEquals(LyricPullItemPhase.Initial, engine.phaseOf(2))
    }
}
