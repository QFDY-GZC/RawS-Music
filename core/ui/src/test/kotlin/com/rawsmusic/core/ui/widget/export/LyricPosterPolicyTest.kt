package com.rawsmusic.core.ui.widget.export

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricPosterPolicyTest {
    @Test fun posterCanGenerateWithoutTimedLyrics() {
        assertEquals(true, LyricPosterPolicy.canGenerate(hasSong = true, hasLyrics = false))
        assertEquals(false, LyricPosterPolicy.canGenerate(hasSong = false, hasLyrics = false))
    }
    @Test fun arbitrarySixLinesCanBeSelectedInSongOrder() {
        var selected = emptySet<Int>()
        listOf(9, 1, 7, 3, 5, 11, 13).forEach { selected = LyricPosterPolicy.toggle(selected, it) }
        assertEquals(listOf(1, 3, 5, 7, 9, 11), LyricPosterPolicy.ordered(selected))
        assertEquals(setOf(1, 3, 5, 7, 9), LyricPosterPolicy.toggle(selected, 11))
    }

    @Test fun heightKeepsLyricsSeparateFromFooter() {
        assertEquals(1440, LyricPosterPolicy.height(1440, 100, 102, 96, 162, 102))
        assertEquals(1962, LyricPosterPolicy.height(1440, 1500, 102, 96, 162, 102))
    }

    @Test fun signatureIsTrimmedAndCanBeHidden() {
        assertEquals("hello", LyricPosterPolicy.signature("  hello  "))
        assertEquals("", LyricPosterPolicy.signature("   "))
        assertEquals(40, LyricPosterPolicy.signature("x".repeat(80)).length)
    }
}
