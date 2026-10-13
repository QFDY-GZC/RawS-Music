package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPronunciationTest {
    @Test fun timedTransliterationStaysAttachedToItsWholeBaseToken() {
        val source = listOf(LyricWord(text = "まる", begin = 14_402, end = 15_345))
        val annotated = attachTimedPronunciation(source,
            listOf(LyricWord(text = "maru", begin = 14_402, end = 15_345)))
        val display = annotated.flatMap(::subdivideCjkTimedWord).map { it.toDisplayLyricWord() }
        assertEquals(1, display.size)
        assertEquals("まる", display.single().text)
        assertEquals(listOf(LyricRubySyllable(14_402, 15_345, "maru")), display.single().rubySyllables())
    }

    @Test fun explicitRubySurvivesConversionWithoutInventingSyllableTiming() {
        val word = LyricWord(text = "仮名", begin = 1000, end = 3000,
            ruby = listOf(LyricRubySyllable(text = "かな")))
        assertEquals(listOf(LyricRubySyllable(text = "かな")), word.toDisplayLyricWord().rubySyllables())
    }

    @Test fun mismatchedTimingDoesNotAttachAnUnrelatedPronunciation() {
        val words = listOf(LyricWord(text = "世界", begin = 1000, end = 2000))
        assertTrue(attachTimedPronunciation(words,
            listOf(LyricWord(text = "sekai", begin = 3000, end = 4000))).single().ruby.isEmpty())
    }

    @Test fun nonAnnotatedCjkWordsKeepTheirExistingSubdivision() {
        assertEquals(listOf("世", "界"), subdivideCjkTimedWord(
            LyricWord(text = "世界", begin = 1000, end = 2000)).map { it.text })
    }
}
