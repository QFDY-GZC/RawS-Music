package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Immutable
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

const val LYRIC_INTERLUDE_MIN_GAP_MS = 7_000L

@Immutable
data class LyricInterlude(
    val startMs: Long,
    val endMs: Long,
    val nextLineIndex: Int
) {
    fun isActiveAt(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/**
 * The lyric layout only needs ownership semantics, not word/line progress.
 *
 * Keeping this object content-equal across audio-block position publications lets LazyColumn item
 * subcompositions stay skipped while the draw-only karaoke clock advances independently.
 */
@Immutable
internal class LyricLayoutPlaybackState(
    private val activeIndices: IntArray,
    private val highlightedIndices: IntArray,
    val anchorLineIndex: Int,
    val activeInterlude: LyricInterlude?,
) {
    val activeLineIndicesList: List<Int> = activeIndices.toList()

    fun isActive(index: Int): Boolean = activeIndices.binarySearch(index) >= 0
    fun isHighlighted(index: Int): Boolean = highlightedIndices.binarySearch(index) >= 0
    fun anyActive(predicate: (Int) -> Boolean): Boolean = activeIndices.any(predicate)

    override fun equals(other: Any?): Boolean =
        other is LyricLayoutPlaybackState &&
            anchorLineIndex == other.anchorLineIndex &&
            activeInterlude == other.activeInterlude &&
            activeIndices.contentEquals(other.activeIndices) &&
            highlightedIndices.contentEquals(other.highlightedIndices)

    override fun hashCode(): Int {
        var result = activeIndices.contentHashCode()
        result = 31 * result + highlightedIndices.contentHashCode()
        result = 31 * result + anchorLineIndex
        result = 31 * result + (activeInterlude?.hashCode() ?: 0)
        return result
    }
}

data class LyricPlaybackState(
    val currentLineIndex: Int = -1,
    val activeLineIndices: Set<Int> = emptySet(),
    /** Lines that own highlight at the current half-open timeline position. */
    val highlightedLineIndices: Set<Int> = emptySet(),
    val anchorLineIndex: Int = -1,
    val activeInterlude: LyricInterlude? = null,
    val currentWordIndex: Int = -1,
    val lineProgress: Float = 0f,
    val wordProgress: Float = 0f,
    val lineStarted: Boolean = false,
    val lineEnded: Boolean = false
)

/**
 * Immutable, playback-independent lyric timeline index.
 *
 * Layout data is stable while the playback position is not. This index keeps that separation on
 * the Android side and uses a prefix maximum of end times so overlapping duet / backing-vocal
 * lines remain correct without a full-list scan.
 */
internal class LyricTimelineIndex(
    private val lines: List<IRichLyricLine>,
    val interludes: List<LyricInterlude> = calculateLyricInterludes(lines),
) {
    private val bounds = buildLyricTimelineBounds(lines)
    val visibleIndices: IntArray = bounds.visibleIndices
    private val starts: LongArray = bounds.starts
    private val ends: LongArray = bounds.ends
    private val prefixMaxEnd = LongArray(visibleIndices.size)
    private var lastInterludePosition = 0

    init {
        ends.forEachIndexed { position, end ->
            prefixMaxEnd[position] = if (position == 0) {
                end
            } else {
                maxOf(prefixMaxEnd[position - 1], end)
            }
        }
    }

    fun layoutPlaybackStateAt(positionMs: Long): LyricLayoutPlaybackState {
        if (visibleIndices.isEmpty()) {
            return LyricLayoutPlaybackState(IntArray(0), IntArray(0), -1, null)
        }
        val activePositions = activePositionsAt(positionMs)
        val active = IntArray(activePositions.size) { position -> visibleIndices[activePositions[position]] }
        val currentPosition = activePositions.lastOrNull() ?: -1
        val interlude = findActiveInterlude(positionMs)
        val nextPosition = firstPositionAfter(positionMs)
        val anchor = when {
            currentPosition >= 0 -> visibleIndices[currentPosition]
            interlude != null -> interlude.nextLineIndex
            nextPosition < visibleIndices.size -> visibleIndices[nextPosition]
            else -> visibleIndices.last()
        }
        val highlighted = highlightedIndicesAt(activePositions).toIntArray().sortedArray()
        return LyricLayoutPlaybackState(
            activeIndices = active.sortedArray(),
            highlightedIndices = highlighted,
            anchorLineIndex = anchor,
            activeInterlude = interlude,
        )
    }

    fun playbackStateAt(positionMs: Long): LyricPlaybackState {
        if (visibleIndices.isEmpty()) return LyricPlaybackState()

        val activePositions = activePositionsAt(positionMs)
        val activeIndices = linkedSetOf<Int>()
        activePositions.forEach { activeIndices += visibleIndices[it] }
        val currentPosition = activePositions.lastOrNull() ?: -1
        val currentIndex = currentPosition.takeIf { it >= 0 }?.let(visibleIndices::get) ?: -1
        val interlude = findActiveInterlude(positionMs)
        val nextPosition = firstPositionAfter(positionMs)
        val anchorIndex = when {
            currentPosition >= 0 -> currentIndex
            interlude != null -> interlude.nextLineIndex
            nextPosition < visibleIndices.size -> visibleIndices[nextPosition]
            else -> visibleIndices.last()
        }

        val highlightedIndices = highlightedIndicesAt(activePositions)

        if (currentIndex < 0) {
            return LyricPlaybackState(
                activeLineIndices = activeIndices,
                highlightedLineIndices = highlightedIndices,
                anchorLineIndex = anchorIndex,
                activeInterlude = interlude,
            )
        }

        val line = lines[currentIndex]
        val lineStart = starts[currentPosition]
        val lineEnd = ends[currentPosition]
        val timedWords = line.words.orEmpty().ifEmpty { line.secondaryWords.orEmpty() }
        val wordState = calculateCurrentWordState(timedWords, lineEnd, positionMs)
        return LyricPlaybackState(
            currentLineIndex = currentIndex,
            activeLineIndices = activeIndices,
            highlightedLineIndices = highlightedIndices,
            anchorLineIndex = anchorIndex,
            activeInterlude = interlude,
            currentWordIndex = wordState.index,
            lineProgress = normalizedProgress(positionMs, lineStart, lineEnd),
            wordProgress = wordState.progress,
            lineStarted = true,
            lineEnded = positionMs >= lineEnd,
        )
    }

    private fun findActiveInterlude(positionMs: Long): LyricInterlude? {
        if (interludes.isEmpty()) return null
        val start = lastInterludePosition.coerceIn(0, interludes.lastIndex)
        var candidate = start
        while (candidate > 0 && interludes[candidate].startMs > positionMs) candidate--
        while (candidate + 1 < interludes.size && interludes[candidate + 1].startMs <= positionMs) candidate++
        lastInterludePosition = candidate.coerceIn(0, interludes.lastIndex)
        return interludes[lastInterludePosition].takeIf { it.isActiveAt(positionMs) }
    }

    private fun activePositionsAt(positionMs: Long): List<Int> {
        val upperBound = firstPositionAfter(positionMs)
        if (upperBound == 0) return emptyList()

        val result = ArrayList<Int>(2)
        var position = upperBound - 1
        while (position >= 0) {
            // If every line up to this point has ended, no earlier line can be active. This is
            // the fast path for ordinary non-overlapping lyrics while preserving long overlaps.
            if (prefixMaxEnd[position] <= positionMs) break
            if (positionMs < ends[position]) result += position
            position--
        }
        result.reverse()
        return result
    }

    private fun firstPositionAfter(positionMs: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= positionMs) low = middle + 1 else high = middle
        }
        return low
    }

    private fun highlightedIndicesAt(activePositions: List<Int>): Set<Int> {
        // Highlight ownership follows the half-open active interval exactly. Keeping the
        // previous row bright for a synthetic hand-off frame makes seek and line boundaries
        // briefly show two ordinary rows. Genuine duet overlaps remain in activePositions.
        return activePositions.mapTo(linkedSetOf()) { visibleIndices[it] }
    }
}

fun calculateLyricPlaybackState(
    lines: List<IRichLyricLine>,
    positionMs: Long,
    interludes: List<LyricInterlude> = calculateLyricInterludes(lines)
): LyricPlaybackState = LyricTimelineIndex(lines, interludes).playbackStateAt(positionMs)

internal fun calculateLyricPlaybackState(
    timeline: LyricTimelineIndex,
    positionMs: Long,
): LyricPlaybackState = timeline.playbackStateAt(positionMs)

fun calculateLyricInterludes(lines: List<IRichLyricLine>): List<LyricInterlude> {
    if (lines.isEmpty()) return emptyList()
    val bounds = buildLyricTimelineBounds(lines)
    val visibleIndices = bounds.visibleIndices.toList()
    if (visibleIndices.isEmpty()) return emptyList()

    return buildList {
        val firstIndex = visibleIndices.first()
        val firstStart = bounds.starts.first()
        if (firstStart >= LYRIC_INTERLUDE_MIN_GAP_MS) {
            add(LyricInterlude(0L, firstStart, firstIndex))
        }
        visibleIndices.dropLast(1).forEachIndexed { position, _ ->
            val nextIndex = visibleIndices[position + 1]
            val gapStart = bounds.ends[position]
            val gapEnd = bounds.starts[position + 1]
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
        if (positionMs < begin) return WordState(-1, 0f)
        if (positionMs in begin until end) {
            return WordState(index, normalizedProgress(positionMs, begin, end))
        }
    }
    return WordState(words.lastIndex, 1f)
}

/**
 * Visual start for a lyric row. Line and word events are scheduled independently; a line may be
 * known/layout-ready before its first karaoke syllable actually starts. For timed-word lines, do
 * not promote/center/highlight the row until the first real main/background word event.
 *
 * A begin earlier than line.begin is ignored here. That protects providers which encode word
 * timestamps relative to the line; LyricDataConverter normalizes the unambiguous relative form.
 */
fun effectiveLineStart(line: IRichLyricLine): Long {
    val sourceBegin = line.begin.coerceAtLeast(0L)
    val firstAbsoluteTimedWord = sequenceOf(
        line.words.orEmpty().asSequence(),
        line.secondaryWords.orEmpty().asSequence()
    )
        .flatten()
        .map { it.begin }
        .filter { it >= sourceBegin }
        .minOrNull()
    return firstAbsoluteTimedWord?.coerceAtLeast(sourceBegin) ?: sourceBegin
}

private data class LyricTimelineBounds(
    val visibleIndices: IntArray,
    val starts: LongArray,
    val ends: LongArray,
)

private fun buildLyricTimelineBounds(lines: List<IRichLyricLine>): LyricTimelineBounds {
    val visibleIndices = visibleLyricLineIndices(lines).toIntArray()
    val starts = LongArray(visibleIndices.size) { position ->
        effectiveLineStart(lines[visibleIndices[position]])
    }
    val ends = LongArray(visibleIndices.size) { position ->
        val lineIndex = visibleIndices[position]
        val nextLineIndex = visibleIndices.getOrNull(position + 1)
        effectiveLineEnd(
            lines = lines,
            index = lineIndex,
            nextLineIndex = nextLineIndex,
            nextBegin = starts.getOrNull(position + 1),
        )
    }
    return LyricTimelineBounds(visibleIndices, starts, ends)
}

fun effectiveLineEnd(lines: List<IRichLyricLine>, index: Int): Long {
    val nextLine = ((index + 1)..lines.lastIndex)
        .firstOrNull { lines[it].hasVisibleLyricText() }
    val nextBegin = nextLine?.let { effectiveLineStart(lines[it]) }
    return effectiveLineEnd(lines, index, nextLine, nextBegin)
}

private fun effectiveLineEnd(
    lines: List<IRichLyricLine>,
    index: Int,
    nextLineIndex: Int?,
    nextBegin: Long?,
): Long {
    val line = lines[index]
    val begin = effectiveLineStart(line)
    val nextLine = nextLineIndex?.let(lines::get)
    val safeNextBegin = nextBegin?.takeIf { it > begin }
    val explicitEnd = line.end.takeIf { it > begin }
    val mainWordEnd = line.words.orEmpty().maxOfOrNull { it.end }?.takeIf { it > begin }
    val backgroundWordEnd = line.secondaryWords.orEmpty().maxOfOrNull { it.end }?.takeIf { it > begin }
    val timedEnd = listOfNotNull(explicitEnd, mainWordEnd, backgroundWordEnd).maxOrNull()
    val candidate = timedEnd ?: safeNextBegin ?: (begin + 3_000L)

    // TTML duet voices can overlap. The converter maps distinct agents to opposite alignment,
    // which lets us preserve their explicit timing without allowing ordinary lines to overlap.
    val preserveDuetOverlap = nextLine != null &&
        safeNextBegin != null &&
        candidate > safeNextBegin &&
        line.isAlignedRight != nextLine.isAlignedRight
    return when {
        safeNextBegin != null && candidate > safeNextBegin && !preserveDuetOverlap -> safeNextBegin
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
