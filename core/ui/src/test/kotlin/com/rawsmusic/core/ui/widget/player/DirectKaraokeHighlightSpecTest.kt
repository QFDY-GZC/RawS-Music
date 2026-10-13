package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectKaraokeHighlightSpecTest {
    @Test fun lastPixelsAreAlreadyOpaqueBeforeTheMaskIsRemoved() {
        val start = DirectKaraokeHighlightSpec.featherStart(100f, 0.9999f, 30f, false)
        val alpha = ((start + 30f - 99.9f) / 30f).coerceIn(0f, 1f)
        assertEquals(1f, alpha, 0.001f)
        assertEquals(100f, DirectKaraokeHighlightSpec.featherStart(100f, 1f, 30f, false), 0.0001f)
    }

    @Test fun rightToLeftFeatherFinishesOutsideTheLeftEdge() {
        val start = DirectKaraokeHighlightSpec.featherStart(100f, 0.9999f, 30f, true)
        val alpha = ((0.1f - start) / 30f).coerceIn(0f, 1f)
        assertEquals(1f, alpha, 0.001f)
        assertEquals(-30f, DirectKaraokeHighlightSpec.featherStart(100f, 1f, 30f, true), 0.0001f)
    }

    @Test fun shortWordsWithFullWidthFeatherDoNotJumpAtCompletion() {
        val start = DirectKaraokeHighlightSpec.featherStart(20f, 0.9999f, 20f, false)
        val alpha = ((start + 20f - 19.99f) / 20f).coerceIn(0f, 1f)
        assertEquals(1f, alpha, 0.001f)
    }

    @Test fun fallbackMaskReachesFullOpacityContinuously() {
        val stops = DirectKaraokeHighlightSpec.maskStops(0.9999f, 0.3f)
        assertTrue(stops[1] > 0.999f)
        assertEquals(1f, stops[2], 0.0001f)
        assertEquals(1f, DirectKaraokeHighlightSpec.maskStops(1f, 0.3f)[1], 0.0001f)
    }

    @Test fun zeroFeatherKeepsTheOriginalSweepDistance() {
        assertEquals(25f, DirectKaraokeHighlightSpec.featherStart(100f, 0.25f, 0f, false), 0.0001f)
        assertEquals(75f, DirectKaraokeHighlightSpec.featherStart(100f, 0.25f, 0f, true), 0.0001f)
    }

    @Test fun splitLongNoteLastGlyphFinishesWithoutAnOpacityStep() {
        // Two glyphs: widths 60/40, feather 30. The second glyph uses the same whole-word edge.
        val boundary = DirectKaraokeHighlightSpec.sweepDistance(100f, 30f) * 0.9999f - 60f
        val alpha = ((boundary - 39.9f) / 30f).coerceIn(0f, 1f)
        assertEquals(1f, alpha, 0.001f)
        assertEquals(130f, DirectKaraokeHighlightSpec.sweepDistance(100f, 30f), 0.0001f)
    }

    @Test fun featherTravelIsClampedToMeasuredWordWidth() {
        assertEquals(40f, DirectKaraokeHighlightSpec.sweepDistance(20f, 80f), 0.0001f)
        assertEquals(20f, DirectKaraokeHighlightSpec.sweepDistance(20f, -10f), 0.0001f)
        assertEquals(0f, DirectKaraokeHighlightSpec.sweepDistance(0f, 30f), 0.0001f)
    }
    @Test fun featherIsNotCompressedAtTheStartOfAWord() {
        assertEquals(-4f, DirectKaraokeHighlightSpec.featherStart(100f, 0.2f, 30f, false), 0.0001f)
        assertEquals(74f, DirectKaraokeHighlightSpec.featherStart(100f, 0.2f, 30f, true), 0.0001f)
    }
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
        assertEquals(0.45f, stops[1], 0.0001f)
        assertEquals(0.55f, stops[2], 0.0001f)
    }
}
