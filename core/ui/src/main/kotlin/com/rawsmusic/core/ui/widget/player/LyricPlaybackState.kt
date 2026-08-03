package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

const val LYRIC_INTERLUDE_MIN_GAP_MS = 7_000L

data class LyricInterlude(
    val startMs: Long,
    val endMs: Long,
    val nextLineIndex: Int
) {
    fun isActiveAt(positionMs: Long): Boolean = positionMs in startMs until endMs
}

data class LyricPlaybackState(
    val currentLineIndex: Int = -1,
    val activeLineIndices: Set<Int> = emptySet(),
    val anchorLineIndex: Int = -1,
    val activeInterlude: LyricInterlude? = null,
    val currentWordIndex: Int = -1,
    val lineProgress: Float = 0f,
    val wordProgress: Float = 0f,
    val lineStarted: Boolean = false,
    val lineEnded: Boolean = false
)

fun calculateLyricPlaybackState(
    lines: List<IRichLyricLine>,
    positionMs: Long,
    interludes: List<LyricInterlude> = calculateLyricInterludes(lines)
): LyricPlaybackState {
    if (lines.isEmpty()) return LyricPlaybackState()

    val visibleIndices = visibleLyricLineIndices(lines)
    if (visibleIndices.isEmpty()) return LyricPlaybackState()

    val activeIndices = visibleIndices.filterTo(linkedSetOf()) { index ->
        val line = lines[index]
        positionMs >= line.begin && positionMs < effectiveLineEnd(lines, index)
    }
    // The newest started voice owns the focus while every overlapping voice remains highlighted.
    val currentIndex = activeIndices.maxByOrNull { lines[it].begin } ?: -1
    val interlude = interludes.firstOrNull { it.isActiveAt(positionMs) }
    val anchorIndex = when {
        currentIndex >= 0 -> currentIndex
        interlude != null -> interlude.nextLineIndex
        else -> visibleIndices.firstOrNull { lines[it].begin > positionMs }
            ?: visibleIndices.last()
    }

    if (currentIndex < 0) {
        return LyricPlaybackState(
            activeLineIndices = activeIndices,
            anchorLineIndex = anchorIndex,
            activeInterlude = interlude
        )
    }

    val line = lines[currentIndex]
    val lineEnd = effectiveLineEnd(lines, currentIndex)
    val wordState = calculateCurrentWordState(line.words.orEmpty(), lineEnd, positionMs)
    return LyricPlaybackState(
        currentLineIndex = currentIndex,
        activeLineIndices = activeIndices,
        anchorLineIndex = anchorIndex,
        activeInterlude = interlude,
        currentWordIndex = wordState.index,
        lineProgress = normalizedProgress(positionMs, line.begin, lineEnd),
        wordProgress = wordState.progress,
        lineStarted = true,
        lineEnded = positionMs >= lineEnd
    )
}

fun calculateLyricInterludes(lines: List<IRichLyricLine>): List<LyricInterlude> {
    if (lines.isEmpty()) return emptyList()
    val visibleIndices = visibleLyricLineIndices(lines)
    if (visibleIndices.isEmpty()) return emptyList()

    return buildList {
        val firstIndex = visibleIndices.first()
        val firstStart = lines[firstIndex].begin
        if (firstStart >= LYRIC_INTERLUDE_MIN_GAP_MS) {
            add(LyricInterlude(0L, firstStart, firstIndex))
        }
        visibleIndices.zipWithNext().forEach { (previousIndex, nextIndex) ->
            val gapStart = effectiveLineEnd(lines, previousIndex)
            val gapEnd = lines[nextIndex].begin
            if (gapEnd - gapStart >= LYRIC_INTERLUDE_MIN_GAP_MS) {
                add(LyricInterlude(gapStart, gapEnd, nextIndex))
            }
        }
    }
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

fun effectiveLineEnd(lines: List<IRichLyricLine>, index: Int): Long {
    val line = lines[index]
    val begin = line.begin
    val nextLine = ((index + 1)..lines.lastIndex)
        .firstOrNull { lines[it].hasVisibleLyricText() }
        ?.let(lines::get)
    val nextBegin = nextLine?.begin?.takeIf { it > begin }
    val explicitEnd = line.end.takeIf { it > begin }
    val mainWordEnd = line.words.orEmpty().maxOfOrNull { it.end }?.takeIf { it > begin }
    val backgroundWordEnd = line.secondaryWords.orEmpty().maxOfOrNull { it.end }?.takeIf { it > begin }
    val timedEnd = listOfNotNull(explicitEnd, mainWordEnd, backgroundWordEnd).maxOrNull()
    val candidate = timedEnd ?: nextBegin ?: (begin + 3_000L)

    // TTML duet voices can overlap. The converter maps distinct agents to opposite alignment,
    // which lets us preserve their explicit timing without allowing ordinary lines to overlap.
    val preserveDuetOverlap = nextLine != null &&
        nextBegin != null &&
        candidate > nextBegin &&
        line.isAlignedRight != nextLine.isAlignedRight
    return when {
        nextBegin != null && candidate > nextBegin && !preserveDuetOverlap -> nextBegin
        else -> candidate
    }.coerceAtLeast(begin + 1L)
}

private fun effectiveWordEnd(
    words: List<LyricWord>,
    index: Int,
    lineEndMs: Long
): Long {
    val word = words[index]
    val begin = word.begin
    val nextBegin = words.getOrNull(index + 1)?.begin?.takeIf { it > begin }
    val candidate = word.end.takeIf { it > begin }
        ?: nextBegin
        ?: lineEndMs.takeIf { it > begin }
        ?: (begin + 240L)
    return (if (nextBegin != null) minOf(candidate, nextBegin) else minOf(candidate, lineEndMs))
        .coerceAtLeast(begin + 1L)
}

private fun normalizedProgress(positionMs: Long, beginMs: Long, endMs: Long): Float {
    val duration = (endMs - beginMs).coerceAtLeast(1L)
    return ((positionMs - beginMs).toFloat() / duration).coerceIn(0f, 1f)
}

fun effectiveSingleLineEnd(line: IRichLyricLine, words: List<LyricWord>): Long {
    val begin = line.begin
    val explicitLineEnd = line.end.takeIf { it > begin } ?: -1L
    val lastWordEnd = words.maxOfOrNull { it.end }?.takeIf { it > begin } ?: -1L
    return maxOf(explicitLineEnd, lastWordEnd, begin + 1L)
}

private fun IRichLyricLine.hasVisibleLyricText(): Boolean {
    val fields = listOf(text, secondary, translation, roma, backgroundTranslation)
    if (fields.any { it.hasDisplayableLyricText() }) return true
    return words.orEmpty().any { it.text.hasDisplayableLyricText() } ||
        secondaryWords.orEmpty().any { it.text.hasDisplayableLyricText() }
}

fun visibleLyricLineIndices(lines: List<IRichLyricLine>): List<Int> =
    lines.indices.filter { lines[it].hasVisibleLyricText() }

private fun String?.hasDisplayableLyricText(): Boolean {
    if (isNullOrBlank()) return false
    return !timestampOnlyLyricTextRegex.matches(trim().replace(',', '.'))
}

private val timestampOnlyLyricTextRegex = Regex(
    """^(?:(?:\[|<)\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|>))+${'$'}"""
)
