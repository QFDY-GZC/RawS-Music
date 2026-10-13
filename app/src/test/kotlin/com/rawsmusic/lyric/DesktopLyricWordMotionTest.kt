package com.rawsmusic.lyric

import com.rawsmusic.core.common.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopLyricWordMotionTest {
    @Test fun onlyCurrentWordMovesAndRepeatedWordsKeepTheirOwnRange() {
        val spans = desktopLyricWordSpans("go go", listOf(
            LyricWord("go", 0, 1000), LyricWord("go", 1000, 2000)
        ), 1500, true, 2f)
        assertEquals(listOf(0, 2, 3), spans.map { it.start })
        assertEquals(0f, spans[0].lift, 0f)
        assertEquals(0f, spans[1].lift, 0f)
        assertEquals(2f, spans[2].lift, 0.0001f)
        assertEquals(0.5f, spans[2].progress, 0.0001f)
    }
    @Test fun untimedTextAndDisabledLiftStayOnBaseline() {
        assertEquals(0f, desktopLyricWordSpans("text", emptyList(), 500, true, 2f).single().lift, 0f)
        assertEquals(0f, desktopLyricWordSpans("go", listOf(LyricWord("go", 0, 1000)),
            500, false, 2f).single().lift, 0f)
    }
}
