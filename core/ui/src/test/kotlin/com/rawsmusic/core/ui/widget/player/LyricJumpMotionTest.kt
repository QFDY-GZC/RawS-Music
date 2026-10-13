package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class LyricJumpMotionTest {
    @Test fun rematerializingTheTargetPreservesRowsThatRemainVisible() {
        val engine = LyricPullEngine()
        val oldRows = listOf(9, 10, 11, 12)
        engine.beginForwardPull(10, 220f, 1000f, oldRows, 1000)
        for (time in 1016L..1160L step 16L) engine.advance(time, oldRows)
        engine.beginForwardPull(13, 220f, 1000f, oldRows, 1170)
        val before = engine.advance(1170, oldRows)
        val position = before.stackOffsetPx + before.offsetsPx.getValue(12)
        val newRows = listOf(12, 13, 14, 15)
        engine.rebindJumpRows(13, newRows, 1170, true)
        val after = engine.advance(1170, newRows)
        assertEquals(position, after.stackOffsetPx + after.offsetsPx.getValue(12), .001f)
    }
    @Test fun repeatingAnUnmaterializedJumpDoesNotAddTheSameDistanceAgain() {
        val field = LyricPullFieldState()
        field.updateViewportHeight(1000)
        field.setVisibleIndices(listOf(9, 10, 11))
        assertTrue(field.beginJumpTransition(20, 220f, 1000, true))
        val before = field.stackOffsetPx
        assertFalse(field.beginJumpTransition(20, 220f, 1000, true))
        assertEquals(before, field.stackOffsetPx, .001f)
    }
    @Test fun distantDestinationKeepsTheIncomingLineWithinTheViewport() {
        assertEquals(500f, LyricJumpMotionSpec.displacement(3000f, 1000f), .001f)
        assertEquals(-250f, LyricJumpMotionSpec.displacement(-3000f, 1000f), .001f)
        assertEquals(180f, LyricJumpMotionSpec.displacement(180f, 1000f), .001f)
    }
    @Test fun invalidMeasurementsDoNotProduceAnInvalidLayerTransform() {
        assertEquals(0f, LyricJumpMotionSpec.displacement(Float.NaN, 1000f), .001f)
        assertEquals(0f, LyricJumpMotionSpec.displacement(100f, 0f), .001f)
    }
    @Test fun newlyMaterializedDestinationReceivesTheSameTrailingWave() {
        val engine = LyricPullEngine()
        engine.beginForwardPull(20, 220f, 1000f, listOf(9, 10, 11), 1000)
        val rows = listOf(19, 20, 21, 22)
        engine.rebindJumpRows(20, rows, 1016, true)
        val entry = engine.advance(1016, rows)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(20))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(21))
        assertEquals(0f, entry.offsetsPx.getValue(20), .001f)
        engine.advance(1032, rows)
        val wave = engine.advance(1048, rows)
        assertTrue(wave.offsetsPx.getValue(21) > wave.offsetsPx.getValue(20))
        engine.advance(1064, rows)
        engine.advance(1080, rows)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(21))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(22))
    }
    @Test fun backwardJumpPreservesItsDirectionAndSettles() {
        val engine = LyricPullEngine()
        val rows = listOf(9, 10, 11, 12)
        engine.beginTransition(10, -220f, 1000f, rows, 1000, true)
        engine.rebindJumpRows(10, rows, 1016, true)
        assertTrue(engine.advance(1016, rows).stackOffsetPx < 0f)
        var result = engine.advance(1032, rows)
        for (time in 1048L..3500L step 16L) result = engine.advance(time, rows)
        assertFalse(result.running)
        assertEquals(0f, result.stackOffsetPx, .001f)
    }
}
