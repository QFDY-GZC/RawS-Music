package com.rawsmusic.module.scanner

import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSLyricsParserEmbeddedTranslationTest {
    @Test
    fun m4aEnhancedLrcKeepsRomanizationAndChineseTranslationAtSameTimestamp() {
        val lyrics = """
            [00:12.651] <00:12.651>か<00:12.884>弱<00:13.476>い<00:13.678>光<00:14.553>が<00:14.897>指<00:15.616>差<00:16.936>す<00:17.007>先<00:18.056>
            [00:12.651]ka yo e i hi ka ri ga yu bi sa su sa ki
            [00:12.651]追寻着那道微弱光线所指的方向
            [00:25.014] <00:25.014>6/8<00:26.056>の<00:26.459>リ<00:26.471>ズ<00:25.014>ム <00:25.014>騒<00:25.014>ぎ<00:27.572>乱<00:29.186>さ<00:29.450>れ<00:29.921>る<00:30.428>
            [00:25.014]6/8 no ri zu mu sa wa gi mi da sa re ru
            [00:25.014]八六原本平和的旋律开始被打乱
        """.trimIndent()

        val data = RawSLyricsParser.parse(lyrics)
        assertEquals(2, data.lines.size)

        val first = data.lines[0]
        assertEquals("ka yo e i hi ka ri ga yu bi sa su sa ki", first.romanization)
        val translation = "追寻着那道微弱光线所指的方向"
        assertEquals(translation, first.translation)
        assertTrue(first.words.isNotEmpty())

        val counted = data.lines[1]
        assertEquals("6/8 no ri zu mu sa wa gi mi da sa re ru", counted.romanization)
        assertEquals("八六原本平和的旋律开始被打乱", counted.translation)
        assertTrue(counted.words.isNotEmpty())
    }

    @Test
    fun rawsOverrideRestoresEmbeddedTranslationDespiteProviderTimingOffset() {
        val override = RawSLyricsParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:12.518" end="00:18.562"><span>か弱い光が指差す先</span></p>
              <p begin="00:24.849" end="00:30.568"><span>6/8(ハチロク)のリズム 掻き乱される</span></p>
            </div></body></tt>
            """.trimIndent()
        )
        val embedded = RawSLyricsParser.parse(
            """
            [00:12.651] <00:12.651>か<00:12.884>弱<00:13.476>い<00:13.678>光<00:14.553>が<00:14.897>指<00:15.616>差<00:16.936>す<00:17.007>先<00:18.056>
            [00:12.651]ka yo e i hi ka ri ga yu bi sa su sa ki
            [00:12.651]追寻着那道微弱光线所指的方向
            [00:25.014] <00:25.014>6/8<00:26.056>の<00:26.459>リ<00:26.471>ズ<00:27.014>ム <00:27.214>掻<00:27.572>き<00:28.100>乱<00:29.186>さ<00:29.450>れ<00:29.921>る<00:30.428>
            [00:25.014]6/8 no ri zu mu ka ki mi da sa re ru
            [00:25.014]八六原本平和的旋律开始被打乱
            """.trimIndent()
        )

        val merged = LyricReader.mergeSecondaryLyricLanes(override, embedded)

        assertEquals(2, merged.lines.size)
        assertEquals("追寻着那道微弱光线所指的方向", merged.lines[0].translation)
        assertEquals("ka yo e i hi ka ri ga yu bi sa su sa ki", merged.lines[0].romanization)
        // Text differs because the override carries a reading annotation; aligned order/timing
        // must still restore its secondary lanes.
        assertEquals("八六原本平和的旋律开始被打乱", merged.lines[1].translation)
    }
}
