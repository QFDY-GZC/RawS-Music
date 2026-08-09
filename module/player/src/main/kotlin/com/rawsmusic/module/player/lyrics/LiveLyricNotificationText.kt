package com.rawsmusic.module.player.lyrics

import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.data.prefs.AppPreferences.Lyrics

internal const val LIVE_UPDATE_COMPACT_MAX_CODE_POINTS = 7

internal data class LiveLyricNotificationText(
    val lyric: String,
    val fullLyric: String,
    val compactLyric: String,
    val wordIndex: Int,
    val allowLongCompactLyric: Boolean
)

internal fun buildLiveLyricNotificationText(
    line: LyricLine,
    mode: Int,
    positionMs: Long
): LiveLyricNotificationText? {
    val sourceText: String
    val sourceWords: List<LyricWord>
    when (mode) {
        Lyrics.LIVE_UPDATE_LYRIC_MODE_TRANSLATION -> {
            sourceText = line.translation.ifBlank { line.text }
            sourceWords = emptyList()
        }
        Lyrics.LIVE_UPDATE_LYRIC_MODE_PRONUNCIATION -> {
            sourceText = line.romanization.ifBlank { line.text }
            sourceWords = line.pronunciationWords
        }
        else -> {
            sourceText = line.text
            sourceWords = line.words
        }
    }
    val fullLyric = sourceText.cleanLiveLyric().takeIf { it.isNotBlank() } ?: return null
    val words = sourceWords
        .filter { it.text.cleanLiveLyric().isNotBlank() && it.end > it.begin }
    val wordIndex = words.indexOfLast { positionMs >= it.begin && positionMs < it.end }
    val displayLyric = if (wordIndex >= 0) {
        buildLiveLyricWindow(words.map { it.text }, wordIndex)
    } else {
        fullLyric
    }
    return LiveLyricNotificationText(
        lyric = displayLyric,
        fullLyric = fullLyric,
        compactLyric = compactLiveLyricText(if (wordIndex >= 0) words[wordIndex].text else fullLyric),
        wordIndex = wordIndex,
        allowLongCompactLyric = wordIndex >= 0
    )
}

internal fun buildLiveLyricSecondaryText(line: LyricLine, mode: Int): String? = when (mode) {
    Lyrics.LIVE_UPDATE_LYRIC_SECONDARY_MODE_TRANSLATION -> line.translation.cleanLiveLyric().takeIf { it.isNotBlank() }
    Lyrics.LIVE_UPDATE_LYRIC_SECONDARY_MODE_PRONUNCIATION -> line.romanization.cleanLiveLyric().takeIf { it.isNotBlank() }
    else -> null
}

internal fun compactLiveLyricText(text: String, preserveLongToken: Boolean = false): String {
    val normalized = text.cleanLiveLyric()
    if (normalized.isBlank()) return ""
    if (normalized.codePointCount(0, normalized.length) <= LIVE_UPDATE_COMPACT_MAX_CODE_POINTS) {
        return normalized
    }
    if (preserveLongToken && normalized.none(Char::isWhitespace)) return normalized
    val visible = normalized.offsetByCodePoints(0, LIVE_UPDATE_COMPACT_MAX_CODE_POINTS - 1)
    return normalized.substring(0, visible) + "..."
}

internal fun buildLiveLyricWindow(
    words: List<String>,
    currentWordIndex: Int,
    maxCodePoints: Int = 40
): String {
    val tokens = words.map { it.cleanLiveLyric() }.filter { it.isNotBlank() }
    if (tokens.isEmpty() || currentWordIndex !in tokens.indices) return tokens.joinToString(" ")
    var start = currentWordIndex
    var end = currentWordIndex
    while (true) {
        val left = if (start > 0) formatLiveLyricWindow(tokens, start - 1, end) else null
        val right = if (end < tokens.lastIndex) formatLiveLyricWindow(tokens, start, end + 1) else null
        val leftFits = left != null && left.codePointCount(0, left.length) <= maxCodePoints
        val rightFits = right != null && right.codePointCount(0, right.length) <= maxCodePoints
        if (!leftFits && !rightFits) break
        when {
            leftFits && !rightFits -> start--
            rightFits && !leftFits -> end++
            currentWordIndex - start <= end - currentWordIndex -> start--
            else -> end++
        }
    }
    return formatLiveLyricWindow(tokens, start, end)
}

private fun formatLiveLyricWindow(tokens: List<String>, start: Int, end: Int): String = buildString {
    if (start > 0) append("...")
    append(tokens.subList(start, end + 1).joinToString(" "))
    if (end < tokens.lastIndex) append("...")
}

private fun String.cleanLiveLyric(): String = replace(Regex("\\s+"), " ").trim()
