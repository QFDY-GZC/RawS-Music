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
    fun previousLineDoesNotRemainHighlightedAfterBoundary() {
        val lines = listOf(
            line(0L, 1_000L, "First"),
            line(1_200L, 2_500L, "Second")
        )

        val state = calculateLyricPlaybackState(lines, 1_250L)

        assertEquals(setOf(1), state.activeLineIndices)
        assertEquals(setOf(1), state.highlightedLineIndices)
    }

    @Test
    fun seekToExactLineStartHighlightsOnlyTheNewLine() {
        val lines = listOf(
            line(0L, 1_000L, "First"),
            line(1_000L, 2_000L, "Second")
        )

        val state = calculateLyricPlaybackState(lines, 1_000L)

        assertEquals(1, state.currentLineIndex)
        assertEquals(setOf(1), state.activeLineIndices)
        assertEquals(setOf(1), state.highlightedLineIndices)
    }

    @Test
    fun oppositeAlignedNonOverlappingLinesDoNotGetSyntheticOverlap() {
        val lines = listOf(
            line(0L, 1_000L, "First", alignedRight = true),
            line(1_100L, 2_000L, "Second", alignedRight = false)
        )

        val state = calculateLyricPlaybackState(lines, 1_150L)

        assertEquals(setOf(1), state.highlightedLineIndices)
    }

    @Test
    fun duetOverlapDoesNotAddAnUnrelatedPreviousLineToTheHighlightSet() {
        val lines = listOf(
            line(0L, 1_000L, "Lead"),
            line(1_050L, 2_000L, "Backing", alignedRight = true),
            line(1_100L, 2_100L, "Next lead")
        )

        val state = calculateLyricPlaybackState(lines, 1_150L)

        assertEquals(setOf(1, 2), state.activeLineIndices)
        assertEquals(setOf(1, 2), state.highlightedLineIndices)
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
        assertTrue(state.highlightedLineIndices.isEmpty())
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
    fun karaokeLineDoesNotActivateBeforeItsFirstTimedWord() {
        val lines = listOf(
            RichLyricLine(
                begin = 10_000L,
                end = 13_000L,
                text = "Delayed karaoke",
                words = listOf(
                    LyricWord(begin = 10_800L, end = 11_400L, text = "Delayed"),
                    LyricWord(begin = 11_400L, end = 12_500L, text = " karaoke")
                )
            )
        )

        val early = calculateLyricPlaybackState(lines, 10_400L)
        assertEquals(-1, early.currentLineIndex)
        assertEquals(0, early.anchorLineIndex)
        assertTrue(early.activeLineIndices.isEmpty())

        val started = calculateLyricPlaybackState(lines, 10_800L)
        assertEquals(0, started.currentLineIndex)
        assertEquals(setOf(0), started.activeLineIndices)
        assertEquals(0, started.currentWordIndex)
    }

    @Test
    fun currentWordIsUnsetUntilTheTimedWordActuallyBegins() {
        val lines = listOf(
            RichLyricLine(
                begin = 1_000L,
                end = 4_000L,
                text = "Lead in",
                secondary = "early background",
                secondaryWords = listOf(
                    LyricWord(begin = 1_000L, end = 1_800L, text = "early background")
                ),
                words = listOf(
                    LyricWord(begin = 2_000L, end = 3_000L, text = "Lead in")
                )
            )
        )

        val state = calculateLyricPlaybackState(lines, 1_500L)

        assertEquals(0, state.currentLineIndex)
        assertEquals(-1, state.currentWordIndex)
        assertEquals(0f, state.wordProgress)
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

    @Test
    fun layoutPlaybackStateStaysEqualAcrossWordProgressOnlyUpdates() {
        val lines = listOf(
            RichLyricLine(
                begin = 1_000L,
                end = 4_000L,
                text = "Hello world",
                words = listOf(
                    LyricWord(begin = 1_000L, end = 2_000L, text = "Hello"),
                    LyricWord(begin = 2_000L, end = 4_000L, text = " world")
                )
            )
        )
        val timeline = LyricTimelineIndex(lines)

        val first = timeline.layoutPlaybackStateAt(1_250L)
        val laterSameLayout = timeline.layoutPlaybackStateAt(1_750L)

        assertEquals(first, laterSameLayout)
        assertEquals(first.hashCode(), laterSameLayout.hashCode())
        assertTrue(first.isActive(0))
        assertTrue(first.isHighlighted(0))
    }

    @Test
    fun layoutPlaybackStateChangesWhenLineOwnershipChanges() {
        val lines = listOf(
            line(0L, 1_000L, "First"),
            line(1_500L, 3_000L, "Second")
        )
        val timeline = LyricTimelineIndex(lines)

        val first = timeline.layoutPlaybackStateAt(500L)
        val second = timeline.layoutPlaybackStateAt(1_700L)

        assertTrue(first != second)
        assertTrue(first.isActive(0))
        assertTrue(second.isActive(1))
    }

    @Test
    fun playbackAfterTheLastLineDoesNotIndexPastTheTimeline() {
        val lines = listOf(
            line(0L, 1_000L, "First"),
            line(2_000L, 3_000L, "Last")
        )

        val state = calculateLyricPlaybackState(lines, 4_000L)

        assertEquals(-1, state.currentLineIndex)
        assertTrue(state.activeLineIndices.isEmpty())
        assertTrue(state.highlightedLineIndices.isEmpty())
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
