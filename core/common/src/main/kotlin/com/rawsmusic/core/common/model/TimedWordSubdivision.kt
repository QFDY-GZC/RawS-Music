package com.rawsmusic.core.common.model

import java.text.BreakIterator
import java.util.Locale

/**
 * Expands a CJK timed token into grapheme-sized timing slices when a provider only supplies one
 * timestamp for the whole token. English words stay grouped so shaping and natural word rhythm
 * are not destroyed; real syllable/word timings continue to pass through unchanged.
 */
internal fun subdivideCjkTimedWord(word: LyricWord): List<LyricWord> {
    val text = word.text
    if (text.isBlank() || text != text.trim() || text.any { it.isWhitespace() }) return listOf(word)

    val clusters = graphemeClusters(text)
    if (clusters.size < 2 || !clusters.any { it.any(::isCjkOrKanaOrHangul) }) return listOf(word)

    val begin = word.begin
    // A provider can technically give fewer milliseconds than visible graphemes. Keep every
    // slice non-empty in that malformed case; for normal data the original end is preserved.
    val end = maxOf(word.end, begin + clusters.size.toLong())
    val duration = end - begin
    return clusters.mapIndexed { index, cluster ->
        val childBegin = begin + duration * index / clusters.size
        val childEnd = if (index == clusters.lastIndex) {
            end
        } else {
            (begin + duration * (index + 1) / clusters.size)
                .coerceAtLeast(childBegin + 1L)
                .coerceAtMost(end - (clusters.lastIndex - index - 1).toLong())
        }
        LyricWord(
            begin = childBegin,
            end = childEnd,
            duration = childEnd - childBegin,
            text = cluster,
        )
    }
}

private fun graphemeClusters(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun isCjkOrKanaOrHangul(character: Char): Boolean =
    character.code in 0x2E80..0x9FFF ||
        character.code in 0xAC00..0xD7AF ||
        character.code in 0x3040..0x30FF
