package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

data class LyricPlaybackState(
    val currentLineIndex: Int = -1,
    val currentWordIndex: Int = -1,
    val lineProgress: Float = 0f,
    val wordProgress: Float = 0f,
    val lineStarted: Boolean = false,
    val lineEnded: Boolean = false
)

fun calculateLyricPlaybackState(
    lines: List<IRichLyricLine>,
    positionMs: Long
): LyricPlaybackState {
    if (lines.isEmpty()) return LyricPlaybackState()

    val lineIndex = findCurrentLineIndexBinary(lines, positionMs)
    if (lineIndex < 0) {
        return LyricPlaybackState(
            currentLineIndex = 0,
            currentWordIndex = -1,
            lineStarted = false,
            lineEnded = false
        )
    }

    val line = lines[lineIndex]
    val lineEnd = effectiveLineEnd(lines, lineIndex)

    val lineProgress = normalizedProgress(positionMs, line.begin, lineEnd)

    val words = line.words.orEmpty()
    val wordState = calculateCurrentWordState(words, lineEnd, positionMs)

    return LyricPlaybackState(
        currentLineIndex = lineIndex,
        currentWordIndex = wordState.index,
        lineProgress = lineProgress,
        wordProgress = wordState.progress,
        lineStarted = positionMs >= line.begin,
        lineEnded = positionMs >= lineEnd
    )
}

private data class WordState(val index: Int, val progress: Float)

private fun calculateCurrentWordState(
    words: List<LyricWord>,
    lineEndMs: Long,
    positionMs: Long
): WordState {
    if (words.isEmpty()) return WordState(-1, 0f)

    for (index in words.indices) {
        val word = words[index]
        val begin = word.begin
        val end = effectiveWordEnd(words, index, lineEndMs)

        if (positionMs < begin) return WordState(index, 0f)
        if (positionMs in begin until end) {
            return WordState(index, normalizedProgress(positionMs, begin, end))
        }
    }
    return WordState(words.lastIndex, 1f)
}

private fun findCurrentLineIndexBinary(
    lines: List<IRichLyricLine>,
    positionMs: Long
): Int {
    if (lines.isEmpty()) return -1
    if (positionMs < lines.first().begin) return -1

    var low = 0
    var high = lines.lastIndex
    var result = 0

    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].begin <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

fun effectiveLineEnd(lines: List<IRichLyricLine>, index: Int): Long {
    val line = lines[index]
    val begin = line.begin

    val explicitEnd = when {
        line.end > begin -> line.end
        line.duration > 0L -> begin + line.duration
        else -> -1L
    }

    val nextBegin = lines.getOrNull(index + 1)?.begin

    val fallbackEnd = when {
        explicitEnd > begin -> explicitEnd
        nextBegin != null && nextBegin > begin -> nextBegin
        else -> begin + 3000L
    }

    return if (nextBegin != null && nextBegin > begin) {
        minOf(fallbackEnd, nextBegin).coerceAtLeast(begin + 1L)
    } else {
        fallbackEnd.coerceAtLeast(begin + 1L)
    }
}

private fun effectiveWordEnd(
    words: List<LyricWord>,
    index: Int,
    lineEndMs: Long
): Long {
    val word = words[index]
    val begin = word.begin
    val explicitEnd = word.end
    val nextBegin = words.getOrNull(index + 1)?.begin

    val fallbackEnd = when {
        nextBegin != null && nextBegin > begin -> nextBegin
        lineEndMs > begin -> lineEndMs
        else -> begin + 240L
    }

    val candidateEnd = if (explicitEnd > begin) explicitEnd else fallbackEnd

    return if (nextBegin != null && nextBegin > begin) {
        minOf(candidateEnd, nextBegin).coerceAtLeast(begin + 1L)
    } else {
        minOf(candidateEnd, lineEndMs).coerceAtLeast(begin + 1L)
    }
}

private fun normalizedProgress(positionMs: Long, beginMs: Long, endMs: Long): Float {
    val duration = (endMs - beginMs).coerceAtLeast(1L)
    return ((positionMs - beginMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

fun effectiveSingleLineEnd(line: IRichLyricLine, words: List<LyricWord>): Long {
    val begin = line.begin
    val explicitLineEnd = when {
        line.end > begin -> line.end
        line.duration > 0L -> begin + line.duration
        else -> -1L
    }
    val lastWordEnd = words.lastOrNull()?.let { w ->
        if (w.end > w.begin) w.end else -1L
    } ?: -1L
    return maxOf(explicitLineEnd, lastWordEnd, begin + 1L)
}
