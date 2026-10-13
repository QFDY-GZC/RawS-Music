package com.rawsmusic.module.scanner

import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import org.junit.Assert.*
import org.junit.Test

class TtmlExplicitSpacingTest {
    @Test fun japaneseWordSeparatorsRemainInTheDisplayText() {
        val line = RawSLyricsParser.parse("""<tt><body><div><p begin="0:01" end="0:05"><span begin="0:01" end="0:02">So</span> <span begin="0:02" end="0:03">ベリーベリー</span> <span begin="0:03" end="0:04">桜</span> <span begin="0:04" end="0:05">海</span></p></div></body></tt>""").lines.single()
        assertEquals("So ベリーベリー 桜 海", line.text)
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test fun nestedSpanSpacesStayOrderedWithoutAddingIndentationToJapanese() {
        val line = RawSLyricsParser.parse("""<tt><body><div><p begin="0:01" end="0:04">
            <span><span begin="0:01" end="0:02">桜</span> <span begin="0:02" end="0:03">海</span></span>
            <span begin="0:03" end="0:04">紅葉</span>
        </p></div></body></tt>""").lines.single()
        assertEquals("桜 海紅葉", line.text)
    }
}
