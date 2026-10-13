package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import com.rawsmusic.core.common.model.rubySyllables
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

internal val LocalJapaneseLyricDocument = staticCompositionLocalOf { false }

private fun Char.isJapaneseKana(): Boolean =
    this in '\u3041'..'\u3096' || this in '\u309D'..'\u309F' ||
        this in '\u30A1'..'\u30FA' || this in '\u30FD'..'\u30FF' ||
        this in '\u31F0'..'\u31FF' || this in '\uFF66'..'\uFF9D'

/** Only original lanes and pronunciation are evidence; translated Japanese is not. */
internal fun isJapaneseLyricDocument(lines: List<IRichLyricLine>): Boolean = lines.any { line ->
    listOf("language", "lang", "xml:lang").any {
        line.metadata?.get(it)?.substringBefore('-')?.substringBefore('_')?.equals("ja", true) == true
    } || line.text.orEmpty().any { it.isJapaneseKana() } ||
        line.secondary.orEmpty().any { it.isJapaneseKana() } ||
        line.roma.orEmpty().any { it.isJapaneseKana() } ||
        (line.words.orEmpty() + line.secondaryWords.orEmpty()).any { word ->
            word.rubySyllables().any { ruby -> ruby.text.any { it.isJapaneseKana() } }
        }
}

internal fun lyricGlyphLanguage(text: String, japaneseDocument: Boolean): String? = when {
    text.any { it.isJapaneseKana() } -> "ja-JP"
    text.any { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' || it in '\u3131'..'\u318E' } -> "ko-KR"
    japaneseDocument -> "ja-JP"
    else -> null
}

private val JapaneseGlyphLocales = LocaleList("ja-JP")
private val KoreanGlyphLocales = LocaleList("ko-KR")

@Composable
internal fun lyricGlyphLocales(
    text: String,
    inheritOriginal: Boolean = true,
    japaneseDocument: Boolean = LocalJapaneseLyricDocument.current,
): LocaleList? = when (lyricGlyphLanguage(text, inheritOriginal && japaneseDocument)) {
    "ja-JP" -> JapaneseGlyphLocales
    "ko-KR" -> KoreanGlyphLocales
    else -> null
}

@Composable
internal fun lyricGlyphTextStyle(text: String, inheritOriginal: Boolean = true): TextStyle =
    TextStyle(localeList = lyricGlyphLocales(text, inheritOriginal))
