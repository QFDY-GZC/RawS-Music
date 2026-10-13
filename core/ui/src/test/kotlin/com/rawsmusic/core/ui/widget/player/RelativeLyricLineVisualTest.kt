package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeLyricLineVisualTest {
    @Test fun relativeProgressOnlyAppliesToActiveUntimedLinesWhenEnabled() {
        assertTrue(usesRelativeLineProgress(enabled = true, active = true, timedWordCount = 0))
        assertFalse(usesRelativeLineProgress(enabled = false, active = true, timedWordCount = 0))
        assertFalse(usesRelativeLineProgress(enabled = true, active = false, timedWordCount = 0))
        assertFalse(usesRelativeLineProgress(enabled = true, active = true, timedWordCount = 2))
    }

    @Test fun relativeProgressUsesEachLinesOwnTimeBounds() {
        assertEquals(0f, relativeLyricLineProgress(900f, 1_000L, 3_000L), 0f)
        assertEquals(0.25f, relativeLyricLineProgress(1_500f, 1_000L, 3_000L), 0.0001f)
        assertEquals(1f, relativeLyricLineProgress(3_100f, 1_000L, 3_000L), 0f)
    }

    @Test fun wrappedRowsAdvanceSequentiallyWithinOneLyricLine() {
        val widths = floatArrayOf(40f, 60f)
        assertEquals(0.5f, relativeVisualRowProgress(0.2f, widths, 0), 0.0001f)
        assertEquals(0f, relativeVisualRowProgress(0.2f, widths, 1), 0f)
        assertEquals(1f, relativeVisualRowProgress(0.7f, widths, 0), 0f)
        assertEquals(0.5f, relativeVisualRowProgress(0.7f, widths, 1), 0.0001f)
    }

    @Test fun naturalFollowKeepsGentlerDepthBlurWhileManualScrollDisablesIt() {
        val target = lyricDepthBlurTarget(
            enabled = true, highlighted = false, distance = 3, highlightAll = false,
        )
        assertTrue(target.value > 0f)
        assertTrue(target.value < 5f)
        assertEquals(target, lyricDepthBlurForFrame(target, naturalFollowRunning = true, zoomActive = false))
        assertEquals(0f, lyricDepthBlurTarget(
            enabled = false, highlighted = false, distance = 3, highlightAll = false,
        ).value, 0f)
    }
}
