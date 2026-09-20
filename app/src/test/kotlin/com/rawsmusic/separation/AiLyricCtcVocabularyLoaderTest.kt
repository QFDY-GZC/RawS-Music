package com.rawsmusic.separation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLyricCtcVocabularyLoaderTest {
    @Test
    fun loadsJsonVocabularyAndKeepsTokenIds() {
        val file = temporaryVocabulary("\uFEFF{\"a\": 1, \"b\": 2}")

        try {
            val vocabulary = AiLyricCtcVocabularyLoader.load(file).getOrThrow()

            assertEquals(1, vocabulary.idFor("a"))
            assertEquals(2, vocabulary.idFor("b"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun loadsTabSeparatedVocabularyWithComments() {
        val file = temporaryVocabulary(
            "# blank is provided by the model contract\n" +
                "0\t<blank>\n" +
                "1 a\n" +
                "b,2",
        )

        try {
            val vocabulary = AiLyricCtcVocabularyLoader.load(file).getOrThrow()

            assertEquals(0, vocabulary.idFor("<blank>"))
            assertEquals(1, vocabulary.idFor("a"))
            assertEquals(2, vocabulary.idFor("b"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMalformedVocabularyLine() {
        val file = temporaryVocabulary("not-a-token-id")

        try {
            val result = AiLyricCtcVocabularyLoader.load(file)

            assertTrue(result.isFailure)
        } finally {
            file.delete()
        }
    }

    private fun temporaryVocabulary(content: String): File =
        File.createTempFile("rawsmusic-ctc-vocab-", ".txt").apply {
            writeText(content, Charsets.UTF_8)
        }
}
