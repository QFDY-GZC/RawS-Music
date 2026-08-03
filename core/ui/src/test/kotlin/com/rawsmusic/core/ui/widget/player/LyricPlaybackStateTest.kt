package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricPlaybackStateTest {
    @Test
    fun timestampOnlyPlaceholderDoesNotBecomeActive() {
        val lines = listOf(
            line(0L, 800L, "[00:00.000]"),
            line(1_000L, 3_000L, "First real line")
        )

        val state = calculateLyricPlaybackState(lines, 500L)

        assertEquals(-1, state.currentLineIndex)
        assertEquals(1, state.anchorLineIndex)
        assertTrue(state.activeLineIndices.isEmpty())
    }

    @Test
    fun realZeroTimestampLineStartsImmediately() {
        val lines = listOf(line(0L, 2_000L, "Opening line"))

        val state = calculateLyricPlaybackState(lines, 0L)

        assertEquals(0, state.currentLineIndex)
        assertEquals(setOf(0), state.activeLineIndices)
    }

    @Test
    fun ordinaryConsecutiveLinesDoNotOverlap() {
        val lines = listOf(
            line(0L, 5_000L, "First"),
            line(3_000L, 6_000L, "Second")
        )

        val state = calculateLyricPlaybackState(lines, 3_500L)

        assertEquals(setOf(1), state.activeLineIndices)
        assertEquals(1, state.currentLineIndex)
    }

    @Test
    fun oppositeDuetVoicesRemainActiveDuringOverlap() {
        val lines = listOf(
            line(0L, 5_000L, "Voice one"),
            line(3_000L, 6_000L, "Voice two", alignedRight = true)
        )

        val state = calculateLyricPlaybackState(lines, 3_500L)

        assertEquals(linkedSetOf(0, 1), state.activeLineIndices)
        assertEquals(1, state.currentLineIndex)
    }

    @Test
    fun longInstrumentalGapHasNoActiveLyricAndAnchorsNextLine() {
        val lines = listOf(
            line(0L, 1_000L, "Before gap"),
            line(10_000L, 12_000L, "After gap")
        )

        val state = calculateLyricPlaybackState(lines, 5_000L)

        assertEquals(-1, state.currentLineIndex)
        assertTrue(state.activeLineIndices.isEmpty())
        assertEquals(1, state.anchorLineIndex)
        assertNotNull(state.activeInterlude)
        assertEquals(1_000L, state.activeInterlude?.startMs)
        assertEquals(10_000L, state.activeInterlude?.endMs)
    }

    @Test
    fun backgroundWordTimingExtendsTheOwningLine() {
        val lines = listOf(
            RichLyricLine(
                begin = 0L,
                end = 1_000L,
                text = "Lead",
                secondary = "Background",
                secondaryWords = listOf(
                    LyricWord(begin = 500L, end = 2_500L, duration = 2_000L, text = "Background")
                )
            )
        )

        val state = calculateLyricPlaybackState(lines, 2_000L)

        assertEquals(setOf(0), state.activeLineIndices)
    }

    @Test
    fun punctuationAndTrailingCharactersFollowThePreviousTimedWord() {
        val words = listOf(
            LyricWord(begin = 0L, end = 500L, text = "Hello"),
            LyricWord(begin = 500L, end = 1_000L, text = "world")
        )

        val slices = buildTimedLyricSlices("Hello, world!", words)

        assertEquals(listOf("Hello, ", "world!"), slices.map { it.text })
        assertEquals(listOf(0, 1), slices.map { it.wordIndex })
    }

    @Test
    fun providerWordMismatchStillProducesTimedSlices() {
        val words = listOf(
            LyricWord(begin = 0L, end = 500L, text = "A&apos;"),
            LyricWord(begin = 500L, end = 1_000L, text = "B")
        )

        val slices = buildTimedLyricSlices("A'B", words)

        assertTrue(slices.any { it.wordIndex != null })
        assertEquals("A'B", slices.joinToString(separator = "") { it.text })
    }

    private fun line(
        begin: Long,
        end: Long,
        text: String,
        alignedRight: Boolean = false
    ) = RichLyricLine(
        begin = begin,
        end = end,
        duration = end - begin,
        text = text,
        isAlignedRight = alignedRight
    )
}
