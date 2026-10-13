package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineBreakPolicyTest {
    @Test fun oneTimedTokenCanWrapAnEntireSentenceWithoutDroppingSpaces() {
        val text = "hello world again"
        val parts = splitWideLyricText(text, 7) { it.length }
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 7 })
        assertEquals(listOf("hello ", "world ", "again"), parts)
    }
    @Test fun emergencyWrapPreservesCombiningCharactersAndSurrogates() {
        val text = "a\u0301a\u0301😀😀"
        val parts = splitWideLyricText(text, 2) { it.length }
        assertEquals(listOf("a\u0301", "a\u0301", "😀", "😀"), parts)
        assertEquals(text, parts.joinToString(""))
    }
    @Test fun explicitNewlinesAreKeptEvenWhenTheSentenceFits() {
        assertEquals(listOf("one\n", "two"), splitWideLyricText("one\ntwo", 100) { it.length })
    }
    @Test fun ordinaryTimedWordStaysOneGroup() {
        assertEquals(listOf("singing"), splitWideLyricText("singing", 100) { it.length })
    }
    @Test fun explicitLineEndingStartsANewVisualRow() {
        val tokens = listOf(LyricWrapToken("hello\n", 45, false),
            LyricWrapToken("world", 45, false))
        assertEquals(setOf(1), flowLyricBreaks(tokens, 300))
        assertEquals(setOf(1), balancedLyricBreaks(tokens, 300))
    }
    @Test fun annotatedTokensFillEachRowBeforeWrapping() {
        val tokens = listOf(LyricWrapToken("まる", 50, false),
            LyricWrapToken("で", 30, false), LyricWrapToken("この", 30, false))
        assertEquals(setOf(2), flowLyricBreaks(tokens, 80))
    }
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
