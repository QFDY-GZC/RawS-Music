package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RubyWordPlacementTest {
    @Test fun shortDashAndKanjiUseTheSameRubyLane() {
        val kanji = rubyInkPlacement(16, 16, -12, 3, 100, 100, -83, 8, 2, rowBaseInkTop = -83)
        val dash = rubyInkPlacement(16, 16, -12, 3, 100, 100, -34, -28, 2, rowBaseInkTop = -83)
        assertEquals(kanji.annotationTop + 16 - kanji.firstBaseline,
            dash.annotationTop + 16 - dash.firstBaseline)
    }
    @Test fun extraFontBoxLeadingCannotMoveIdenticalInk() {
        val compact = rubyInkPlacement(14, 14, -10, 2, 20, 20, -18, 4, 2)
        val padded = rubyInkPlacement(14, 14, -10, 2, 50, 50, -18, 4, 2)
        assertEquals(compact.height, padded.height)
        assertEquals(compact.firstBaseline, padded.firstBaseline)
        assertEquals(compact.baseTop + 20 - 18, padded.baseTop + 50 - 18)
    }
    @Test fun annotationFontBoxLeadingCannotInflateTheRow() {
        val compact = rubyInkPlacement(14, 14, -10, 2, 20, 20, -18, 4, 2)
        val padded = rubyInkPlacement(30, 30, -10, 2, 20, 20, -18, 4, 2)
        assertEquals(compact.height, padded.height)
        assertEquals(compact.firstBaseline, padded.firstBaseline)
        assertEquals(0, padded.annotationTop + 30 - 10)
    }
}
