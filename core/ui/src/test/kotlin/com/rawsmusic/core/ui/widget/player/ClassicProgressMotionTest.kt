package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class ClassicProgressMotionTest {
    @Test fun pausedDragHasWhiteHeadButNoCone() {
        assertEquals(ClassicConeMode.Off, classicConeMode(false, true))
        assertEquals(0.1f, classicHeadLength(1f, false, true), 0.00001f)
    }

    @Test fun playingDragKeepsTwentyPercentHeadAndChangesCone() {
        assertEquals(ClassicConeMode.Dragging, classicConeMode(true, true))
        assertEquals(0.2f, classicHeadLength(1f, true, true), 0.00001f)
        assertEquals(ClassicConeMode.Playing, classicConeMode(true, false))
    }

    @Test fun shortProgressHeadNeverExtendsPastPlayedSegment() {
        assertEquals(0.04f, classicHeadLength(0.04f, true, false), 0.00001f)
        assertEquals(0f, classicHeadLength(0f, true, true), 0f)
        assertEquals(0f, classicHeadLength(0.5f, false, false), 0f)
    }

    @Test fun followSpringLagsThenSettlesWithoutOvershoot() {
        val spring = ClassicProgressSpring(0.2f)
        spring.target = 0.8f
        spring.advance(1f / 60f, 0.15f)
        assertTrue(spring.value > 0.2f && spring.value < 0.8f)
        repeat(120) { spring.advance(1f / 60f, 0.15f) }
        assertEquals(0.8f, spring.value, 0.0001f)
    }

    @Test fun interruptedFadeStartsFromCurrentValue() {
        val spring = ClassicProgressSpring(1f)
        spring.target = 0f
        repeat(5) { spring.advance(1f / 60f, 0.4f) }
        val interrupted = spring.value
        spring.target = 1f
        assertEquals(interrupted, spring.value, 0f)
        repeat(120) { spring.advance(1f / 60f, 0.3f) }
        assertEquals(1f, spring.value, 0.0001f)
    }

    @Test fun slowReleaseDoesNotFlingButFastReleaseDecelerates() {
        val fling = ClassicProgressFling()
        assertFalse(fling.start(0.5f, 99f, 500f))
        assertTrue(fling.start(0.5f, 1200f, 500f))
        val startSpeed = fling.velocityPx
        fling.advance(1f / 60f)
        assertTrue(fling.fraction > 0.5f)
        assertTrue(fling.velocityPx < startSpeed)
        repeat(180) { fling.advance(1f / 60f) }
        assertFalse(fling.running)
    }

    @Test fun flingStopsAtBothEndsAndNewTouchCancelsIt() {
        val fling = ClassicProgressFling()
        fling.start(0.99f, 8000f, 400f)
        repeat(180) { fling.advance(1f / 60f) }
        assertEquals(1f, fling.fraction, 0f)
        fling.start(0.01f, -8000f, 400f)
        repeat(180) { fling.advance(1f / 60f) }
        assertEquals(0f, fling.fraction, 0f)
        fling.start(0.5f, 1000f, 400f)
        fling.cancel()
        assertFalse(fling.running)
    }

    @Test fun oldPlaybackPositionCannotOverwriteJustCommittedSeek() {
        val hold = ClassicSeekHold()
        hold.commit(0.8f, 1000L)
        assertEquals(0.8f, hold.display(0.2f, 1100L), 0f)
        assertEquals(0.81f, hold.display(0.81f, 2000L), 0f)
        hold.commit(0.3f, 3000L)
        hold.clear()
        assertEquals(0f, hold.display(0f, 3010L), 0f)
    }
}
