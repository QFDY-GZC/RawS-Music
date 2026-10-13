package com.rawsmusic.module.scanner

import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSLyricsParserApi5TtmlTest {
    @Test
    fun singleSpanCoveringWholeLineDoesNotInventWordTiming() {
        val data = RawSLyricsParser.parse(
            """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                  <p begin="00:20.149" end="00:21.370">
                    <span begin="00:20.149" end="00:21.370">视线所及如同</span>
                  </p>
                  <p begin="00:21.380" end="00:23.194">
                    <span begin="00:21.380" end="00:23.194">荒诞的谎言</span>
                  </p>
                </div></body></tt>
            """.trimIndent()
        )

        assertEquals(listOf("视线所及如同", "荒诞的谎言"), data.lines.map { it.text })
        assertEquals(listOf(20_149L, 21_380L), data.lines.map { it.timeStamp })
        assertTrue(data.lines.all { it.words.isEmpty() && !it.hasWordTiming })
    }

    @Test
    fun independentlyTimedSpansStillProvideWordTiming() {
        val data = RawSLyricsParser.parse(
            """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                  <p begin="00:20.000" end="00:22.000">
                    <span begin="00:20.000" end="00:21.000">逐</span>
                    <span begin="00:21.000" end="00:22.000">字</span>
                  </p>
                </div></body></tt>
            """.trimIndent()
        )

        assertEquals("逐字", data.lines.single().text)
        assertEquals(listOf("逐", "字"), data.lines.single().words.map { it.text })
        assertTrue(data.lines.single().hasWordTiming)
    }

    @Test
    fun rawTtmlPreservesApi5RubyMetadataLanguagesAndExtensions() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                xmlns:x="urn:rawsmusic:test"
                xml:lang="ja"
                itunes:timing="Word">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person">
                    <ttm:name type="full">Singer</ttm:name>
                  </ttm:agent>
                </metadata>
                <metadata>
                  <x:custom data-id="42">value</x:custom>
                </metadata>
                <metadata>
                  <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                    <translations>
                      <translation xml:lang="zh-Hans">
                        <text for="L1">假名</text>
                      </translation>
                    </translations>
                    <transliterations>
                      <transliteration xml:lang="ja-Latn">
                        <text for="L1">
                          <span begin="00:01.000" end="00:02.000">ka</span>
                          <span begin="00:02.000" end="00:03.000">na</span>
                        </text>
                      </transliteration>
                    </transliterations>
                  </iTunesMetadata>
                </metadata>
              </head>
              <body dur="00:03.000">
                <div>
                  <p begin="00:01.000" end="00:03.000" itunes:key="L1" ttm:agent="v1" itunes:song-part="Verse">
                    <span tts:ruby="container">
                      <span tts:ruby="base">仮名</span>
                      <span tts:ruby="textContainer">
                        <span tts:ruby="text" begin="00:01.000" end="00:02.000">か</span>
                        <span tts:ruby="text" begin="00:02.000" end="00:03.000">な</span>
                      </span>
                    </span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val data = RawSLyricsParser.parse(ttml)

        assertEquals("ja", data.language)
        assertEquals("Word", data.ttmlTiming)
        assertEquals("zh-Hans", data.translatedLanguage)
        assertEquals("ja-Latn", data.romanizationLanguage)
        assertEquals("00:03.000", data.bodyDur)

        assertEquals(1, data.ttmlAgents.size)
        assertEquals("v1", data.ttmlAgents.single().id)
        assertEquals("person", data.ttmlAgents.single().type)
        assertEquals("Singer", data.ttmlAgents.single().name)

        assertEquals(1, data.ttmlMetadata.size)
        assertEquals("x:custom", data.ttmlMetadata.single().name)
        assertEquals("urn:rawsmusic:test", data.ttmlMetadata.single().namespace)
        assertEquals("42", data.ttmlMetadata.single().attributes["data-id"])
        assertEquals("value", data.ttmlMetadata.single().text)

        val line = data.lines.single()
        assertEquals("仮名", line.text)
        assertEquals("假名", line.translation)
        assertEquals("ka na", line.romanization)
        assertEquals("v1", line.agent)
        assertEquals("Singer", line.agentName)
        assertEquals("L1", line.extensions["itunes:key"])
        assertEquals("v1", line.extensions["ttm:agent"])
        assertEquals("Verse", line.extensions["itunes:song-part"])

        assertEquals(1, line.words.size)
        val word = line.words.single()
        assertEquals("仮名", word.text)
        assertEquals(1_000L, word.begin)
        assertEquals(3_000L, word.end)
        assertEquals(listOf("か", "な"), word.ruby.map { it.text })
        assertEquals(1_000L, word.ruby[0].begin)
        assertEquals(2_000L, word.ruby[0].end)
        assertEquals(2_000L, word.ruby[1].begin)
        assertEquals(3_000L, word.ruby[1].end)

        assertEquals(listOf("ka", "na"), line.pronunciationWords.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), line.pronunciationWords.map { it.begin })
        assertEquals(listOf(2_000L, 3_000L), line.pronunciationWords.map { it.end })
    }

    @Test
    fun invalidBodyDurationIsDroppedWithoutDroppingLyrics() {
        val data = RawSLyricsParser.parse(
            """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body dur="invalid">
                    <div><p begin="00:01.000" end="00:02.000"><span>ok</span></p></div>
                  </body>
                </tt>
            """.trimIndent()
        )

        assertEquals("ok", data.lines.single().text)
        assertNull(data.bodyDur)
        assertTrue(data.ttmlMetadata.isEmpty())
    }
}
