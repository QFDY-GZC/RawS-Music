package com.rawsmusic.module.scanner

import com.rawsmusic.module.scanner.parser.parseQrcTimedWords
import org.junit.Assert.assertEquals
import org.junit.Test

class QrcWordTimelineParserTest {
    @Test
    fun standardQrcTagBelongsToPrecedingWord() {
        val words = parseQrcTimedWords(
            "Lately (358,1336)I've (1694,487)been(2181,673)",
        )

        assertEquals(listOf("Lately ", "I've ", "been"), words.map { it.text })
        assertEquals(listOf(358L, 1694L, 2181L), words.map { it.begin })
        assertEquals(listOf(1694L, 2181L, 2854L), words.map { it.end })
    }

    @Test
    fun legacyPrefixTaggedQrcRemainsReadable() {
        val words = parseQrcTimedWords(
            "(358,1336)Lately (1694,487)I've (2181,673)been",
        )

        assertEquals(listOf("Lately ", "I've ", "been"), words.map { it.text })
        assertEquals(listOf(358L, 1694L, 2181L), words.map { it.begin })
    }
}
