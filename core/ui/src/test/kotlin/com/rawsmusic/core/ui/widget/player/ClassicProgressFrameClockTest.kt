package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class ClassicProgressFrameClockTest {
    @Test fun repeatedInputRetargetingDoesNotResetFrameTime() {
        val clock = ClassicProgressFrameClock()
        val progress = ClassicProgressSpring(0f)
        assertEquals(0f, clock.advance(1_000_000_000L), 0f)
        repeat(120) { i ->
            // Multiple input samples before one vsync must not restart the animation clock.
            repeat(4) { progress.target = (i + 1) / 120f }
            val dt = clock.advance(1_000_000_000L + (i + 1) * 8_333_333L)
            assertEquals(1f / 120f, dt, 0.00001f)
            progress.advance(dt, 0.15f)
        }
        assertTrue(progress.value > 0.9f)
    }

    @Test fun idleWakeStartsANewClockButDroppedFramesKeepBoundedTime() {
        val clock = ClassicProgressFrameClock()
        clock.advance(1_000_000_000L)
        assertEquals(0.064f, clock.advance(3_000_000_000L), 0f)
        clock.reset()
        assertEquals(0f, clock.advance(9_000_000_000L), 0f)
        assertEquals(0.016f, clock.advance(9_016_000_000L), 0.00001f)
    }
}
