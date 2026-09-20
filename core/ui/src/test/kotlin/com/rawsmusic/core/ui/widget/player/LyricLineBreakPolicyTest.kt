package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineBreakPolicyTest {
    @Test
    fun prefersASeparatorBreakOverAWordBreak() {
        val tokens = listOf(
            LyricWrapToken("你", 32, false),
            LyricWrapToken("好", 32, false),
            LyricWrapToken(" ", 12, true),
            LyricWrapToken("世界", 64, false),
        )

        val breaks = balancedLyricBreaks(tokens, maxWidthPx = 80)

        assertEquals(setOf(3), breaks)
    }

    @Test
    fun neverPlacesASeparatorAtTheStartOfANewRow() {
        val tokens = listOf(
            LyricWrapToken("hello", 58, false),
            LyricWrapToken(" ", 12, true),
            LyricWrapToken("world", 58, false),
        )

        val breaks = balancedLyricBreaks(tokens, maxWidthPx = 70)

        assertTrue(breaks.none { it < tokens.size && tokens[it].isSpace })
    }
}
