package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RubyInkPlacementTest {
    @Test fun actualInkHasExactlyOneGapWithoutPlaceholderBaselineCorrection() {
        val p = rubyInkPlacement(66, 66, -35, 2, 125, 125, -83, 20, 7)
        assertEquals(7, (p.baseTop + 125 - 83) - (p.annotationTop + 66 + 2))
        assertEquals(0, p.annotationTop + 66 - 35)
        assertEquals(147, p.height)
        assertEquals(p.baseTop + 125, p.firstBaseline)
    }
    @Test fun wrappedTextKeepsAllLastLineInkAndBaselines() {
        val p = rubyInkPlacement(16, 36, -12, 3, 30, 70, -24, 6, 2)
        assertEquals(35, p.annotationTop + 36 + 3)
        assertEquals(2, p.baseTop + 30 - 24 - 35)
        assertEquals(107, p.height)
        assertEquals(40, p.lastBaseline - p.firstBaseline)
    }
}
