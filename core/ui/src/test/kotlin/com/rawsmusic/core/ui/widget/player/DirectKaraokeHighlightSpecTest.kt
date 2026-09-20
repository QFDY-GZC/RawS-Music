package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectKaraokeHighlightSpecTest {
    @Test
    fun `display frame positions advance highlight between coarse player callbacks`() {
        val begin = 1_000L
        val end = 2_000L
        val p0 = DirectKaraokeHighlightSpec.progress(1_200f, begin, end)
        val p1 = DirectKaraokeHighlightSpec.progress(1_208.33f, begin, end)
        val p2 = DirectKaraokeHighlightSpec.progress(1_216.66f, begin, end)
        assertTrue(p1 > p0)
        assertTrue(p2 > p1)
        assertEquals(0.2f, p0, 0.0001f)
    }

    @Test
    fun `feather stays behind the moving highlight edge`() {
        val stops = DirectKaraokeHighlightSpec.maskStops(0.5f, 0.1f)
        assertEquals(0.4f, stops[1], 0.0001f)
        assertEquals(0.5f, stops[2], 0.0001f)
    }
}
