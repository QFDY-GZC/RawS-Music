package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricMetadata
import io.github.proify.lyricon.lyric.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricGlyphLocaleTest {
    @Test fun japaneseSongKeepsKanjiOnlyLinesJapanese() {
        val lines = listOf(RichLyricLine(text = "続く夢"), RichLyricLine(text = "絆"))
        assertTrue(isJapaneseLyricDocument(lines))
        assertEquals("ja-JP", lyricGlyphLanguage("絆", true))
    }
    @Test fun translationCannotClassifyChineseOriginalAsJapanese() {
        assertFalse(isJapaneseLyricDocument(listOf(RichLyricLine(text = "继续前行", translation = "歩き続ける"))))
        assertNull(lyricGlyphLanguage("繼續前行", false))
    }
    @Test fun kanaAndHalfWidthKanaSelectJapanese() {
        assertEquals("ja-JP", lyricGlyphLanguage("骨を結ぶ", false))
        assertEquals("ja-JP", lyricGlyphLanguage("ｷｽ", false))
        assertNull(lyricGlyphLanguage("Hello", false))
    }
    @Test fun koreanLineDoesNotInheritJapaneseSongLanguage() {
        assertEquals("ko-KR", lyricGlyphLanguage("사랑", true))
    }
    @Test fun backgroundVocalsCanIdentifyJapaneseDocument() {
        assertTrue(isJapaneseLyricDocument(listOf(RichLyricLine(text = "LOVE", secondary = "続いて"))))
    }
    @Test fun explicitJapaneseLanguageCoversEntirelyKanjiLyrics() {
        assertTrue(isJapaneseLyricDocument(listOf(RichLyricLine(text = "絆", metadata = LyricMetadata(mapOf("xml:lang" to "ja"))))))
    }
    @Test fun kanaRubyIdentifiesKanjiOnlyOriginal() {
        val word = LyricWord(text = "絆", metadata = LyricMetadata(mapOf(
            "raws.ruby.count" to "1", "raws.ruby.0.text" to "きずな",
        )))
        assertTrue(isJapaneseLyricDocument(listOf(RichLyricLine(text = "絆", words = listOf(word)))))
    }
}
