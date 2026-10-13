package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordSweepTimingTest {
    @Test fun sustainedWordKeepsSourceSweepDuringOverlap() {
        val end = lyricWordSweepEnd(1000, 4000, 1100, 5000, sustained = true)
        assertEquals(4000L, end)
        assertEquals(0.5f, DirectKaraokeHighlightSpec.progress(2500f, 1000, end), 0.0001f)
    }
    @Test fun ordinaryWordStillClipsOverlap() {
        assertEquals(1100L, lyricWordSweepEnd(1000, 4000, 1100, 5000, sustained = false))
    }
    @Test fun missingSourceEndUsesNextWord() {
        assertEquals(1500L, lyricWordSweepEnd(1000, 0, 1500, 5000, sustained = true))
    }
}
