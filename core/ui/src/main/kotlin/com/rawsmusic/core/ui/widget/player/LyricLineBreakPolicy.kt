package com.rawsmusic.core.ui.widget.player

import kotlin.math.max

/** A measured child in a timed lyric row. */
internal data class LyricWrapToken(
    val text: String,
    val widthPx: Int,
    val isSpace: Boolean,
)

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
