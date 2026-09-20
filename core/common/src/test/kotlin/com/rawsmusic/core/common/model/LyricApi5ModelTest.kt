package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricApi5ModelTest {
    @Test
    fun api5TtmlFieldsSurviveDataClassCopies() {
        val ruby = LyricRubySyllable(
            begin = null,
            end = 1_250L,
            text = "かな",
        )
        val word = LyricWord(
            text = "仮名",
            begin = 1_000L,
            end = 1_500L,
            ruby = listOf(ruby),
        )
        val metadata = LyricTtmlMetadataElement(
            name = "ttm:title",
            namespace = "http://www.w3.org/ns/ttml#metadata",
            attributes = mapOf("xml:lang" to "ja"),
            text = "API5 fixture",
            children = listOf(
                LyricTtmlMetadataElement(
                    name = "itunes:source",
                    text = "unit-test",
                )
            ),
        )
        val data = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 2_000L,
                    text = "仮名",
                    words = listOf(word),
                    extensions = mapOf("itunes:key" to "L1"),
                    isTtml = true,
                )
            ),
            ttmlAgents = listOf(
                LyricTtmlAgent(
                    id = "v1",
                    type = "person",
                    name = "Singer",
                )
            ),
            ttmlMetadata = listOf(metadata),
            ttmlTiming = "clock",
            language = "ja",
            translatedLanguage = "zh-Hans",
            romanizationLanguage = "ja-Latn",
            bodyDur = "00:03.000",
        )

        val copied = data.copy(lines = data.lines.map { it.copy() })

        assertEquals(data, copied)
        assertEquals(ruby, copied.lines.single().words.single().ruby.single())
        assertNull(copied.lines.single().words.single().ruby.single().begin)
        assertEquals("L1", copied.lines.single().extensions["itunes:key"])
        assertEquals("Singer", copied.ttmlAgents.single().name)
        assertEquals(metadata, copied.ttmlMetadata.single())
        assertEquals("http://www.w3.org/ns/ttml#metadata", copied.ttmlMetadata.single().namespace)
        assertEquals("clock", copied.ttmlTiming)
        assertEquals("ja", copied.language)
        assertEquals("zh-Hans", copied.translatedLanguage)
        assertEquals("ja-Latn", copied.romanizationLanguage)
        assertEquals("00:03.000", copied.bodyDur)
    }

    @Test
    fun legacyConstructorsKeepApi5FieldsEmptyByDefault() {
        val data = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    text = "legacy",
                    words = listOf(LyricWord("legacy", 1_000L, 2_000L)),
                )
            )
        )

        assertEquals(emptyList<LyricRubySyllable>(), data.lines.single().words.single().ruby)
        assertEquals(emptyMap<String, String>(), data.lines.single().extensions)
        assertEquals(emptyList<LyricTtmlAgent>(), data.ttmlAgents)
        assertEquals(emptyList<LyricTtmlMetadataElement>(), data.ttmlMetadata)
        assertNull(data.ttmlTiming)
        assertNull(data.language)
        assertNull(data.translatedLanguage)
        assertNull(data.romanizationLanguage)
        assertNull(data.bodyDur)
    }
}
