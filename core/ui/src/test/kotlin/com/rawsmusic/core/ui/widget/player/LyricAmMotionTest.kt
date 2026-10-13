package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class LyricAmMotionTest {
    @Test fun quickRetargetsKeepTheExistingFrameLoopAlive() {
        val field = LyricPullFieldState()
        field.updateViewportHeight(1000)
        field.setVisibleIndices(listOf(9, 10, 11, 12))
        field.beginTransition(10, 220f, 1000, true)
        val loop = field.generation
        field.advance(1016)
        field.beginTransition(11, 180f, 1022, true)
        assertEquals(loop, field.generation)
    }
    @Test fun anOlderVsyncCannotRewindTheTimeAfterABetweenFrameRetarget() {
        val a = LyricPullEngine(); val b = LyricPullEngine()
        val rows = listOf(9, 10, 11, 12)
        for (engine in listOf(a, b)) {
            engine.beginForwardPull(10, 220f, 1000f, rows, 1000)
            engine.advance(1016, rows)
            engine.beginForwardPull(11, 180f, 1000f, rows, 1022)
        }
        a.advance(1016, rows)
        val first = a.advance(1032, rows)
        val reference = b.advance(1032, rows)
        assertEquals(reference.stackOffsetPx, first.stackOffsetPx, .001f)
        for (row in rows) assertEquals(reference.offsetsPx.getValue(row), first.offsetsPx.getValue(row), .001f)
    }
    @Test fun visiblePreviousLineOccupiesTheSecondImmediateAnimationSlot() {
        val engine = LyricPullEngine()
        val rows = listOf(9, 10, 11, 12)
        engine.beginForwardPull(10, 220f, 1000f, rows, 1000)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(9))
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(10))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(11))
        engine.advance(1050, rows)
        assertEquals(LyricPullItemPhase.Pulling, engine.phaseOf(11))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(12))
    }
    @Test fun layoutCorrectionPreservesTheScheduledTrailingWave() {
        val engine = LyricPullEngine()
        val rows = listOf(10, 11, 12, 13)
        engine.beginForwardPull(10, 220f, 1000f, rows, 1000)
        engine.advance(1016, rows)
        engine.beginTransition(10, 2f, 1000f, rows, 1016, false)
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(12))
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(13))
        val wave = engine.advance(1048, rows)
        assertTrue(wave.offsetsPx.getValue(12) > wave.offsetsPx.getValue(11))
    }
    @Test fun aNearlySettledPreviousSentenceStillAllowsANewTrailingWave() {
        val engine = LyricPullEngine()
        val rows = listOf(10, 11, 12, 13, 14)
        engine.beginForwardPull(10, 220f, 1000f, rows, 1000)
        for (time in 1016L..1896L step 16L) engine.advance(time, rows)
        engine.beginForwardPull(11, 220f, 1000f, rows, 1900)
        assertEquals(LyricPullItemPhase.WaitingForPull, engine.phaseOf(13))
    }
    @Test fun defaultSpringMatchesTheVerifiedIosModel() {
        val motion = lyricFollowMotion(250L)
        assertEquals(100f, motion.stiffness, .0001f)
        assertEquals(.9f, motion.dampingRatio, .0001f)
    }
    @Test fun firstFollowingRowMovesWithTheAnchorInsteadOfWaitingAnotherBeat() {
        val engine = LyricPullEngine()
        engine.beginForwardPull(10, 200f, 1000f, listOf(10, 11, 12), 1000)
        assertEquals(0f, engine.advance(1100, listOf(10, 11, 12)).offsetsPx.getValue(11), .01f)
    }
    @Test fun compactPreviewsKeepTheirOriginalLineHeight() {
        assertEquals(22, LyricAmMotionSpec.primaryLineHeight(16, false))
        assertEquals(53, LyricAmMotionSpec.primaryLineHeight(28, true))
    }
    @Test fun aZeroDistanceLayoutRefreshMustNotStopAnInFlightSentence() {
        val field = LyricPullFieldState()
        field.updateViewportHeight(900)
        field.setVisibleIndices(listOf(10, 11, 12))
        field.beginTransition(10, 220f, 1000, true)
        val position = field.stackOffsetPx
        field.beginTransition(10, 0f, 1000)
        assertTrue(field.running)
        assertEquals(position, field.stackOffsetPx, .001f)
    }
}
