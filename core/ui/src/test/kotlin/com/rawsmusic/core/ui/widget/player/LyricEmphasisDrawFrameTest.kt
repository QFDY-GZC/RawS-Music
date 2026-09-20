package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.*
import org.junit.Test

class LyricEmphasisDrawFrameTest {
    @Test fun longNoteAdvancesBetweenSourceCallbacks() {
        val frame = LyricEmphasisDrawFrame(2)
        val widths = listOf(40f, 40f)
        frame.sample(1400f, 1000L, 3000L, widths, false)
        val before = frame.fractions[0]
        frame.sample(1416.667f, 1000L, 3000L, widths, false)
        assertTrue(frame.fractions[0] > before)
        assertTrue(frame.fractions[0] - before < 0.1f)
    }

    @Test fun pauseReusesTheSameFrameAndSeekResetsEmphasis() {
        val frame = LyricEmphasisDrawFrame(2)
        val widths = listOf(40f, 40f)
        frame.sample(1600f, 1000L, 3000L, widths, false)
        val offsets = frame.offsets
        frame.sample(1600f, 1000L, 3000L, widths, false)
        assertSame(offsets, frame.offsets)
        frame.sample(900f, 1000L, 3000L, widths, false)
        assertArrayEquals(floatArrayOf(0f, 0f), frame.fractions, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), frame.offsets, 0f)
    }

    @Test fun spacingKeepsSubpixelMotionAndInvalidatesOnMeasurement() {
        val frame = LyricEmphasisDrawFrame(2)
        frame.sample(1500f, 1000L, 3000L, listOf(41f, 37f), false)
        val offset = frame.offsets[0]
        assertTrue(offset != offset.toInt().toFloat())
        frame.sample(1500f, 1000L, 3000L, listOf(82f, 74f), false)
        assertEquals(offset * 2f, frame.offsets[0], 0.0001f)
        frame.sample(1500f, 1000L, 3000L, listOf(82f, 74f), true)
        assertEquals(-offset * 2f, frame.offsets[0], 0.0001f)
    }
}
