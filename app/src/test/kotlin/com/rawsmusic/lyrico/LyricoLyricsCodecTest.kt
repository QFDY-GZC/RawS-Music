package com.rawsmusic.lyrico

import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricoLyricsCodecTest {
    @Test
    fun api5StructuredPayloadPreservesExtendedTtmlSemantics() {
        val payload = """
            {
              "type":"structured",
              "original":[
                [1000,3000,
                  [
                    [1000,1800,"仮",[[null,1400,"か"]]],
                    [1800,3000,"名",[[1800,2500,"な"],[2500,null,"め"]]]
                  ],
                  {
                    "itunes:key":"L1",
                    "ttm:agent":"v1",
                    "plain":"ok",
                    "bad:value":"drop"
                  }
                ]
              ],
              "translated":[[1000,3000,"假名"]],
              "romanization":[
                [1000,3000,[[1000,1800,"ka"],[1800,3000,"na"]]]
              ],
              "agents":[{"id":"v1","type":"person","name":"Singer"}],
              "metadata":[
                {
                  "name":"songwriters",
                  "children":[{"name":"songwriter","text":"Alice"}]
                },
                {
                  "name":"x:custom",
                  "namespace":"urn:rawsmusic:test",
                  "attributes":{"data-id":"42"},
                  "text":"value"
                }
              ],
              "timing":"Word",
              "language":"ja",
              "translatedLang":"zh-Hans",
              "romanizationLang":"ja-Latn",
              "bodyDur":"00:03.000"
            }
        """.trimIndent()

        val parsed = LyricoLyricsCodec.parsePayload(payload)

        assertEquals(1, parsed.lines.size)
        val line = parsed.lines.single()
        assertEquals("仮名", line.text)
        assertEquals("假名", line.translation)
        assertEquals("kana", line.romanization)
        assertEquals(listOf("ka", "na"), line.pronunciationWords.map { it.text })
        assertEquals("か", line.words[0].ruby.single().text)
        assertNull(line.words[0].ruby.single().begin)
        assertEquals(1_400L, line.words[0].ruby.single().end)
        assertEquals("め", line.words[1].ruby[1].text)
        assertNull(line.words[1].ruby[1].end)
        assertEquals("L1", line.extensions["itunes:key"])
        assertEquals("v1", line.extensions["ttm:agent"])
        assertEquals("ok", line.extensions["plain"])
        assertFalse("bad:value" in line.extensions)

        assertEquals("v1", parsed.ttmlAgents.single().id)
        assertEquals("person", parsed.ttmlAgents.single().type)
        assertEquals("Singer", parsed.ttmlAgents.single().name)
        assertEquals("songwriters", parsed.ttmlMetadata[0].name)
        assertEquals("Alice", parsed.ttmlMetadata[0].children.single().text)
        assertEquals("x:custom", parsed.ttmlMetadata[1].name)
        assertEquals("urn:rawsmusic:test", parsed.ttmlMetadata[1].namespace)
        assertEquals("42", parsed.ttmlMetadata[1].attributes["data-id"])
        assertEquals("Word", parsed.ttmlTiming)
        assertEquals("ja", parsed.language)
        assertEquals("zh-Hans", parsed.translatedLanguage)
        assertEquals("ja-Latn", parsed.romanizationLanguage)
        assertEquals("00:03.000", parsed.bodyDur)
    }

    @Test
    fun legacyApi4StructuredPayloadAndCandidateContainersStillParse() {
        val payload = """
            {
              "results":[
                {
                  "lyrics":{
                    "type":"structured",
                    "original":[[1000,2000,"legacy"]],
                    "translated":[[1000,2000,"旧翻译"]],
                    "romanization":[[1000,2000,"legacy roma"]]
                  }
                }
              ]
            }
        """.trimIndent()

        val candidates = LyricoLyricsCodec.parsePayloadCandidates(payload)

        assertEquals(1, candidates.size)
        val line = candidates.single().lines.single()
        assertEquals("legacy", line.text)
        assertEquals("旧翻译", line.translation)
        assertEquals("legacy roma", line.romanization)
        assertTrue(line.pronunciationWords.isEmpty())
    }

    @Test
    fun malformedOptionalApi5FieldsDoNotDiscardUsableLyricLine() {
        val payload = """
            {
              "type":"structured",
              "original":[
                [1000,2000,[[1000,2000,"ok",{"not":"ruby"}]],123]
              ],
              "metadata":[
                {"name":"songwriters","children":[{"name":"wrong","text":"ignored"}]},
                {"name":"x:missingNamespace","text":"ignored"}
              ],
              "bodyDur":"definitely-not-a-time"
            }
        """.trimIndent()

        val parsed = LyricoLyricsCodec.parsePayload(payload)

        assertEquals("ok", parsed.lines.single().text)
        assertTrue(parsed.lines.single().extensions.isEmpty())
        assertTrue(parsed.lines.single().words.single().ruby.isEmpty())
        assertTrue(parsed.ttmlMetadata.isEmpty())
        assertNull(parsed.bodyDur)
    }

    @Test
    fun api5TtmlWriterEmitsPreservedSemanticStructures() {
        val parsed = LyricoLyricsCodec.parsePayload(
            """
                {
                  "type":"structured",
                  "original":[[1000,3000,[[1000,3000,"仮名",[[1000,2000,"か"],[2000,3000,"な"]]]],{"itunes:key":"L1"}]],
                  "translated":[[1000,3000,"假名"]],
                  "romanization":[[1000,3000,[[1000,1800,"ka"],[1800,3000,"na"]]]],
                  "agents":[{"id":"v1","type":"person","name":"Singer"}],
                  "metadata":[{"name":"x:custom","namespace":"urn:rawsmusic:test","text":"value"}],
                  "timing":"Word",
                  "language":"ja",
                  "translatedLang":"zh-Hans",
                  "romanizationLang":"ja-Latn",
                  "bodyDur":"00:03.000"
                }
            """.trimIndent()
        )

        val ttml = LyricoLyricsCodec.toTtml(parsed)

        assertTrue(ttml.contains("xmlns:tts=\"http://www.w3.org/ns/ttml#styling\""))
        assertTrue(ttml.contains("xmlns:x=\"urn:rawsmusic:test\""))
        assertTrue(ttml.contains("xml:lang=\"ja\""))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<ttm:agent xml:id=\"v1\" type=\"person\">"))
        assertTrue(ttml.contains("<ttm:name type=\"full\">Singer</ttm:name>"))
        assertTrue(ttml.contains("<x:custom>value</x:custom>"))
        assertTrue(ttml.contains("<body dur=\"00:03.000\">"))
        assertTrue(ttml.contains("tts:ruby=\"container\""))
        assertTrue(ttml.contains("tts:ruby=\"base\">仮名</span>"))
        assertTrue(ttml.contains("tts:ruby=\"text\" begin=\"00:01.000\" end=\"00:02.000\">か</span>"))
        assertTrue(ttml.contains("<translation xml:lang=\"zh-Hans\">"))
        assertTrue(ttml.contains("<transliteration xml:lang=\"ja-Latn\">"))
    }

    @Test
    fun rubyWriterEvenlyFillsMissingMultiSyllableTiming() {
        val parsed = LyricoLyricsCodec.parsePayload(
            """
                {
                  "type":"structured",
                  "original":[
                    [1000,3000,[[1000,3000,"仮名",[[null,null,"か"],[null,null,"な"]]]]]
                  ]
                }
            """.trimIndent()
        )

        val ttml = LyricoLyricsCodec.toTtml(parsed)

        assertTrue(ttml.contains("tts:ruby=\"text\" begin=\"00:01.000\" end=\"00:02.000\">か</span>"))
        assertTrue(ttml.contains("tts:ruby=\"text\" begin=\"00:02.000\" end=\"00:03.000\">な</span>"))
    }

    @Test
    fun structuredApi5RoundTripsThroughTtmlIntoTheSameSemanticModel() {
        val source = LyricoLyricsCodec.parsePayload(
            """
                {
                  "type":"structured",
                  "original":[[1000,3000,[[1000,3000,"仮名",[[1000,2000,"か"],[2000,3000,"な"]]]],{"itunes:key":"L1","ttm:agent":"v1"}]],
                  "translated":[[1000,3000,"假名"]],
                  "romanization":[[1000,3000,[[1000,2000,"ka"],[2000,3000,"na"]]]],
                  "agents":[{"id":"v1","type":"person","name":"Singer"}],
                  "metadata":[{"name":"x:custom","namespace":"urn:rawsmusic:test","attributes":{"data-id":"42"},"text":"value"}],
                  "timing":"Word",
                  "language":"ja",
                  "translatedLang":"zh-Hans",
                  "romanizationLang":"ja-Latn",
                  "bodyDur":"00:03.000"
                }
            """.trimIndent()
        )

        val reparsed = RawSLyricsParser.parse(LyricoLyricsCodec.toTtml(source))

        assertEquals(source.language, reparsed.language)
        assertEquals(source.ttmlTiming, reparsed.ttmlTiming)
        assertEquals(source.translatedLanguage, reparsed.translatedLanguage)
        assertEquals(source.romanizationLanguage, reparsed.romanizationLanguage)
        assertEquals(source.bodyDur, reparsed.bodyDur)
        assertEquals(source.ttmlAgents, reparsed.ttmlAgents)
        assertEquals(source.ttmlMetadata, reparsed.ttmlMetadata)
        assertEquals(source.lines.single().translation, reparsed.lines.single().translation)
        assertEquals(source.lines.single().romanization, reparsed.lines.single().romanization)
        assertEquals(source.lines.single().words.single().ruby, reparsed.lines.single().words.single().ruby)
        assertEquals(
            source.lines.single().pronunciationWords.map { it.text },
            reparsed.lines.single().pronunciationWords.map { it.text },
        )
        assertEquals("v1", reparsed.lines.single().extensions["ttm:agent"])
        assertEquals("L1", reparsed.lines.single().extensions["itunes:key"])
    }
}
