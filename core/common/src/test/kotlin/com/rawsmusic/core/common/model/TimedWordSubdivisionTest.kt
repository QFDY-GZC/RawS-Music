package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedWordSubdivisionTest {
    @Test
    fun cjkTokenBecomesContinuousGraphemeSlices() {
        val slices = subdivideCjkTimedWord(
            LyricWord(begin = 1_000L, end = 2_000L, text = "你好世界")
        )

        assertEquals(listOf("你", "好", "世", "界"), slices.map { it.text })
        assertEquals(1_000L, slices.first().begin)
        assertEquals(2_000L, slices.last().end)
        assertTrue(slices.zipWithNext().all { (left, right) -> left.end == right.begin })
    }

    @Test
    fun latinWordRemainsAWord() {
        val word = LyricWord(begin = 0L, end = 1_000L, text = "hello")

        assertEquals(listOf(word), subdivideCjkTimedWord(word))
    }
}
