package com.rawsmusic.core.common.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricExplicitTest {
    private fun lyrics(vararg texts: String): LyricData =
        LyricData(texts.mapIndexed { index, text -> LyricLine(index * 1000L, text) })

    @Test
    fun recognizesExplicitWordsWithoutMatchingOrdinarySubstrings() {
        assertTrue(LocalLyricExplicit.detect(lyrics("I don't give a fuck about that")))
        assertTrue(LocalLyricExplicit.detect(lyrics("That's some bullshit!")))
        assertTrue(LocalLyricExplicit.detect(lyrics("你他妈的在说什么")))
        assertTrue(LocalLyricExplicit.detect(lyrics("我靠，你这傻逼")))
        assertFalse(LocalLyricExplicit.detect(lyrics("I work in a classroom with an assistant")))
        assertFalse(LocalLyricExplicit.detect(lyrics("Let's have a cocktail tonight")))
        assertFalse(LocalLyricExplicit.detect(lyrics("玫瑰花和妈妈都很好")))
    }

    @Test
    fun checksBackgroundAndTranslationButNotRomanizationOrMetadata() {
        assertTrue(LocalLyricExplicit.detect(LyricData(lines = listOf(
            LyricLine(0, "Hello", translation = "我操！"),
        ))))
        assertTrue(LocalLyricExplicit.detect(LyricData(lines = listOf(
            LyricLine(0, "Hello", backgroundText = "shit!"),
        ))))
        assertFalse(LocalLyricExplicit.detect(LyricData(lines = listOf(
            LyricLine(0, "Hello", romanization = "motherfucker"),
        ))))
    }

    @Test
    fun downloadedReplacementClearsMarkAndCueTracksStayIndependent() {
        val first = AudioFile(path = "/storage/emulated/0/Music/explicit-test.flac",
            cueTrackIndex = 1, cueOffsetMs = 0L)
        val second = first.copy(cueTrackIndex = 2, cueOffsetMs = 10000L)
        LocalLyricExplicit.publish(first, true)
        assertTrue(LocalLyricExplicit.isExplicit(first))
        assertFalse(LocalLyricExplicit.isExplicit(second))
        LocalLyricExplicit.publish(first, false)
        assertFalse(LocalLyricExplicit.isExplicit(first))
    }

    @Test
    fun onlineSourcesNeverReceiveLocalLyricExplicitMarks() {
        val url = AudioFile(path = "https://example.org/music/track.flac")
        val source = AudioFile(path = "rawsource://plugin/media/track")
        LocalLyricExplicit.publish(url, true)
        LocalLyricExplicit.publish(source, true)
        assertFalse(LocalLyricExplicit.isExplicit(url))
        assertFalse(LocalLyricExplicit.isExplicit(source))
    }
}
