package com.rawsmusic.core.ui.widget.player

import kotlin.math.max
import java.text.BreakIterator
import java.util.Locale

/** Split only oversized source tokens; timing remains owned by the original token. */
internal fun splitWideLyricText(text: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (text.isEmpty() || maxWidthPx <= 0 || maxWidthPx == Int.MAX_VALUE) return listOf(text)
    val result = mutableListOf<String>()
    var paragraphStart = 0
    while (paragraphStart < text.length) {
        val newline = text.indexOf('\n', paragraphStart)
        val end = if (newline < 0) text.length else newline + 1
        val paragraph = text.substring(paragraphStart, end)
        if (measure(paragraph) <= maxWidthPx) result += paragraph else {
            val iterator = BreakIterator.getLineInstance(Locale.ROOT)
            iterator.setText(paragraph)
            var start = iterator.first()
            var boundary = iterator.next()
            var pending = ""
            while (boundary != BreakIterator.DONE) {
                val unit = paragraph.substring(start, boundary)
                if (pending.isNotEmpty() && measure(pending + unit) > maxWidthPx) {
                    result += pending
                    pending = ""
                }
                if (measure(unit) <= maxWidthPx) pending += unit else {
                    // Emergency character wrapping for long Latin words/URLs, without splitting
                    // surrogate pairs or combining marks. Normal words and ruby stay grouped.
                    val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
                    characters.setText(unit)
                    var charStart = characters.first()
                    var charEnd = characters.next()
                    while (charEnd != BreakIterator.DONE) {
                        val cluster = unit.substring(charStart, charEnd)
                        if (pending.isNotEmpty() && measure(pending + cluster) > maxWidthPx) {
                            result += pending
                            pending = ""
                        }
                        pending += cluster
                        charStart = charEnd
                        charEnd = characters.next()
                    }
                }
                start = boundary
                boundary = iterator.next()
            }
            if (pending.isNotEmpty()) result += pending
        }
        paragraphStart = end
    }
    return result
}

/** A measured child in a timed lyric row. */
internal data class LyricWrapToken(
    val text: String,
    val widthPx: Int,
    val isSpace: Boolean,
)

/** Sequential word-group wrapping, as opposed to rebalancing already measured rows. */
internal fun flowLyricBreaks(tokens: List<LyricWrapToken>, maxWidthPx: Int): Set<Int> {
    if (maxWidthPx <= 0 || maxWidthPx == Int.MAX_VALUE) return emptySet()
    val breaks = linkedSetOf<Int>()
    var rowWidth = 0L
    tokens.forEachIndexed { index, token ->
        if (index > 0 && tokens[index - 1].text.endsWith('\n')) {
            breaks += index
            rowWidth = 0
        }
        if (index > 0 && rowWidth > 0 && !token.isSpace && rowWidth + token.widthPx > maxWidthPx) {
            breaks += index
            rowWidth = 0
        }
        rowWidth += token.widthPx.coerceAtLeast(0)
    }
    return breaks
}

/**
 * Finds visually balanced breaks for a measured lyric row.
 *
 * The renderer measures each timed word first, then this small dynamic-programming pass chooses
 * breaks. This matters for Chinese and glass-style layouts because a greedy break often
 * leaves a single short word on the last line even when a nearby break would be much cleaner.
 */
internal fun balancedLyricBreaks(
    tokens: List<LyricWrapToken>,
    maxWidthPx: Int,
): Set<Int> {
    if (tokens.size < 2 || maxWidthPx == Int.MAX_VALUE || maxWidthPx <= 0) return emptySet()
    val hardBreaks = tokens.indices.filter { it > 0 && tokens[it - 1].text.endsWith('\n') }
    if (hardBreaks.isNotEmpty()) {
        val boundaries = listOf(0) + hardBreaks + tokens.size
        return buildSet {
            addAll(hardBreaks)
            boundaries.zipWithNext().forEach { (start, end) ->
                addAll(balancedLyricBreaks(tokens.subList(start, end), maxWidthPx).map { it + start })
            }
        }
    }

    val count = tokens.size
    val prefixWidths = IntArray(count + 1)
    tokens.forEachIndexed { index, token ->
        prefixWidths[index + 1] = prefixWidths[index] + token.widthPx
    }

    val cost = DoubleArray(count + 1) { Double.POSITIVE_INFINITY }
    val nextBreak = IntArray(count + 1) { -1 }
    cost[count] = 0.0
    val widthScale = max(maxWidthPx, 1).toDouble()

    for (start in count - 1 downTo 0) {
        for (end in start + 1..count) {
            // Do not leave a separator at the beginning of the next visual row. A break after
            // this token (rather than before it) keeps normal Latin spacing intact.
            if (end < count && tokens[end].isSpace) continue
            val width = prefixWidths[end] - prefixWidths[start]
            if (width > maxWidthPx && end > start + 1) break

            val slack = (maxWidthPx - width).toDouble()
            val overflow = (width - maxWidthPx).coerceAtLeast(0).toDouble()
            var lineCost = if (overflow > 0) {
                overflow * overflow * 18.0
            } else {
                slack * slack
            }

            if (end < count) {
                val previous = tokens[end - 1]
                val breakQuality = when {
                    previous.isSpace -> -0.42
                    previous.text.lastOrNull()?.let(::isLyricPunctuation) == true -> -0.30
                    previous.text.any(::isCjkLyricCharacter) -> 0.10
                    else -> 0.34
                }
                lineCost += breakQuality * widthScale * widthScale
            }

            val candidate = lineCost + cost[end]
            if (candidate < cost[start]) {
                cost[start] = candidate
                nextBreak[start] = end
            }
        }
    }

    if (nextBreak[0] < 0) return emptySet()
    val breaks = linkedSetOf<Int>()
    var cursor = 0
    while (cursor < count) {
        val end = nextBreak[cursor]
        if (end <= cursor) return emptySet()
        if (end < count) breaks += end
        cursor = end
    }
    return breaks
}

private fun isLyricPunctuation(character: Char): Boolean = when (character) {
    ',', '.', '!', '?', ';', ':',
    '，', '。', '！', '？', '；', '：', '、', '…', '」', '』', '）', ')' -> true
    else -> false
}

private fun isCjkLyricCharacter(character: Char): Boolean =
    character.code in 0x2E80..0x9FFF ||
        character.code in 0xAC00..0xD7AF ||
        character.code in 0x3040..0x30FF
