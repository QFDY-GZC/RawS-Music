package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniLyricStateTest {
    @Test
    fun endedLineIsNotKeptActiveDuringInstrumentalGap() {
        val lines = listOf(
            line(0L, 1_000L, "Before gap"),
            line(10_000L, 12_000L, "After gap")
        )

        val preview = resolveMiniLyricPresentation(
            lines = lines,
            positionMs = 5_000L,
            displayTranslation = true,
            displayRoma = true,
            maxPrimaryRows = 3
        )

        assertNull(preview.currentLine)
        assertTrue(preview.isInterlude)
        assertEquals(1, preview.anchorLineIndex)
        assertFalse(preview.lines.any { it.primary == "Before gap" && it.active })
    }

    @Test
    fun pronunciationTranslationAndBackgroundCanAppearTogether() {
        val lines = listOf(
            RichLyricLine(
                begin = 0L,
                end = 2_000L,
                text = "Original",
                roma = "Pronunciation",
                translation = "Translation",
                secondary = "Background",
                backgroundTranslation = "Background translation"
            )
        )

        val current = resolveMiniLyricPresentation(
            lines = lines,
            positionMs = 500L,
            displayTranslation = true,
            displayRoma = true,
            maxPrimaryRows = 1
        ).currentLine

        assertEquals("Original", current?.primary)
        assertEquals(
            listOf("Pronunciation", "Translation", "Background", "Background translation"),
            current?.secondaryParts
        )
    }

    @Test
    fun musicSymbolOnlyLinesAreExcluded() {
        val preview = resolveMiniLyricPresentation(
            lines = listOf(
                line(0L, 1_000L, "♪ ♫ …"),
                line(1_000L, 2_000L, "Real lyric")
            ),
            positionMs = 1_500L,
            displayTranslation = false,
            displayRoma = false,
            maxPrimaryRows = 3
        )

        assertEquals(listOf("Real lyric"), preview.lines.map { it.primary })
    }

    @Test
    fun singleRowPreviewKeepsNonFirstCurrentLine() {
        val preview = resolveMiniLyricPresentation(
            lines = listOf(
                line(0L, 1_000L, "First"),
                line(1_000L, 2_000L, "Second"),
                line(2_000L, 3_000L, "Third")
            ),
            positionMs = 1_500L,
            displayTranslation = false,
            displayRoma = false,
            maxPrimaryRows = 1
        )

        assertEquals("Second", preview.currentLine?.primary)
        assertEquals(listOf("Second"), preview.lines.map { it.primary })
    }

    @Test
    fun distantTranslationDoesNotShrinkCurrentPlainWindow() {
        val lines = (0 until 6).map { index ->
            line(index * 1_000L, (index + 1) * 1_000L, "Line $index").apply {
                if (index == 5) translation = "Only the last line has translation"
            }
        }

        val preview = resolveMiniLyricPresentation(
            lines = lines,
            positionMs = 2_500L,
            displayTranslation = true,
            displayRoma = false,
            maxPrimaryRows = 5
        )

        assertEquals(5, preview.lines.size)
        assertEquals(2, preview.activeLineIndex)
    }

    private fun line(begin: Long, end: Long, text: String) = RichLyricLine(
        begin = begin,
        end = end,
        duration = end - begin,
        text = text
    )
}
