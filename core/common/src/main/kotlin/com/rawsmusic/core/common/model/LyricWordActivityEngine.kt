package com.rawsmusic.core.common.model

import java.text.BreakIterator
import java.util.Locale

/**
 * Conservative policy for estimating word timing from a separated vocal activity map.
 *
 * This is not phoneme alignment. It only uses vocal-active windows and therefore stays behind
 * the explicit preview/accept boundary in [LyricTimingCorrection].
 */
data class LyricWordActivityPolicy(
    val minimumConfidence: Float = 0.45f,
    val minimumActiveDurationMs: Long = 120L,
    val minimumWordDurationMs: Long = 24L,
    val lineTailAllowanceMs: Long = 160L,
    val remapExistingWords: Boolean = true,
    val maximumUnitsPerLine: Int = 160,
) {
    init {
        require(minimumConfidence in 0f..1f) { "Activity confidence must be within 0..1" }
        require(minimumActiveDurationMs > 0L) { "Minimum active duration must be positive" }
        require(minimumWordDurationMs > 0L) { "Minimum word duration must be positive" }
        require(lineTailAllowanceMs >= 0L) { "Line tail allowance must be non-negative" }
        require(maximumUnitsPerLine > 0) { "Maximum lyric units must be positive" }
    }
}

/**
 * Estimates word timing from vocal activity while preserving lyric content and metadata.
 *
 * Lines without word timing are split into grapheme-sized CJK units and whitespace-preserving
 * Latin tokens. Existing word timing is left alone when it already fits the detected vocal
 * interval; it is remapped only when it extends beyond that interval.
 */
fun LyricData.previewWordActivityRetiming(
    activityMap: VoiceActivityMap,
    sourceIdentity: String,
    originalLyricHash: String,
    correctionId: String,
    durationMs: Long = activityMap.durationMs,
    policy: LyricWordActivityPolicy = LyricWordActivityPolicy(),
    correctionAnalyzerVersion: String = "${activityMap.analyzerVersion}:word",
): LyricTimingCorrection {
    require(sourceIdentity.isNotBlank()) { "Lyric correction source identity is required" }
    require(originalLyricHash.isNotBlank()) { "Original lyric hash is required" }
    require(correctionId.isNotBlank()) { "Lyric correction id is required" }
    require(correctionAnalyzerVersion.isNotBlank()) { "Lyric correction analyzer version is required" }
    require(activityMap.sourceIdentity == sourceIdentity) { "Voice activity source identity mismatch" }
    require(durationMs >= 0L) { "Lyric duration must be non-negative" }

    if (lines.isEmpty() || !activityMap.isUsable) {
        return fallbackWordCorrection(
            original = this,
            sourceIdentity = sourceIdentity,
            originalLyricHash = originalLyricHash,
            correctionId = correctionId,
            analyzerVersion = correctionAnalyzerVersion,
        )
    }

    val correctedLines = lines.toMutableList()
    val changes = mutableListOf<LyricTimingChange>()
    var previousCorrectedStart = Long.MIN_VALUE

    lines.forEachIndexed { index, line ->
        val originalStart = line.timeStamp.coerceAtLeast(0L)
        val nextStart = lines.getOrNull(index + 1)?.timeStamp
            ?.takeIf { it > originalStart }
        val originalEnd = effectiveLineEnd(
            line = line,
            nextStart = nextStart,
            durationMs = durationMs,
            minimumDurationMs = policy.minimumActiveDurationMs,
        )
        val boundedEnd = minOf(
            originalEnd,
            durationMs.takeIf { it > originalStart } ?: originalEnd,
        )
        val activeSpans = activityMap.spansWithin(originalStart, boundedEnd)
            .asSequence()
            .filter { it.confidence >= policy.minimumConfidence }
            .map { span ->
                span.copy(
                    startMs = span.startMs.coerceIn(originalStart, boundedEnd),
                    endMs = span.endMs.coerceIn(originalStart, boundedEnd),
                )
            }
            .filter { it.endMs > it.startMs }
            .toList()
        val activeDuration = activeSpans.sumOf { it.endMs - it.startMs }
        if (activeDuration < policy.minimumActiveDurationMs) return@forEachIndexed

        val activeStart = activeSpans.minOf { it.startMs }
        val activeEnd = activeSpans.maxOf { it.endMs }
        if (index > 0 && activeStart < previousCorrectedStart) return@forEachIndexed

        val originalWords = line.words
        val correctedWords = when {
            originalWords.isEmpty() -> {
                val units = lyricUnits(line.text).take(policy.maximumUnitsPerLine)
                if (units.isEmpty()) return@forEachIndexed
                allocateUnits(units, activeSpans, activeDuration, policy.minimumWordDurationMs)
            }
            policy.remapExistingWords && wordsNeedRemap(
                words = originalWords,
                activeStart = activeStart,
                activeEnd = activeEnd,
            ) -> remapWords(
                words = originalWords,
                originalStart = originalStart,
                originalEnd = originalEnd,
                activeStart = activeStart,
                activeEnd = activeEnd,
            )
            else -> originalWords
        }
        val correctedEnd = maxOf(
            activeEnd + policy.lineTailAllowanceMs,
            correctedWords.lastOrNull()?.end ?: activeEnd,
        ).coerceAtMost(originalEnd)
            .coerceAtLeast(activeStart + policy.minimumActiveDurationMs)
        val correctedLine = line.copy(
            timeStamp = activeStart,
            endTime = correctedEnd,
            words = correctedWords,
        )
        if (correctedLine == line) return@forEachIndexed

        correctedLines[index] = correctedLine
        previousCorrectedStart = activeStart
        changes += LyricTimingChange(
            lineIndex = index,
            originalStartMs = originalStart,
            originalEndMs = originalEnd,
            correctedStartMs = correctedLine.timeStamp,
            correctedEndMs = correctedLine.endTime,
            confidence = activeSpans.map { it.confidence }.average().toFloat().coerceIn(0f, 1f),
        )
    }

    val corrected = copy(lines = correctedLines)
    return LyricTimingCorrection(
        correctionId = correctionId,
        sourceIdentity = sourceIdentity,
        originalLyricHash = originalLyricHash,
        analyzerVersion = correctionAnalyzerVersion,
        mode = LyricTimingCorrectionMode.WORD_RETIMING,
        status = if (changes.isEmpty()) LyricTimingCorrectionStatus.FALLBACK
        else LyricTimingCorrectionStatus.PREVIEW,
        original = this,
        corrected = corrected,
        changes = changes,
        analyzedDurationMs = activityMap.durationMs,
        analyzedActivitySpans = activityMap.spans.size,
    )
}

private fun fallbackWordCorrection(
    original: LyricData,
    sourceIdentity: String,
    originalLyricHash: String,
    correctionId: String,
    analyzerVersion: String,
): LyricTimingCorrection = LyricTimingCorrection(
    correctionId = correctionId,
    sourceIdentity = sourceIdentity,
    originalLyricHash = originalLyricHash,
    analyzerVersion = analyzerVersion,
    mode = LyricTimingCorrectionMode.WORD_RETIMING,
    status = LyricTimingCorrectionStatus.FALLBACK,
    original = original,
    corrected = original,
    changes = emptyList(),
)

private fun effectiveLineEnd(
    line: LyricLine,
    nextStart: Long?,
    durationMs: Long,
    minimumDurationMs: Long,
): Long {
    val start = line.timeStamp.coerceAtLeast(0L)
    return when {
        line.endTime > start -> line.endTime
        nextStart != null -> nextStart
        durationMs > start -> durationMs
        else -> start + minimumDurationMs
    }.coerceAtLeast(start + minimumDurationMs)
}

private fun wordsNeedRemap(
    words: List<LyricWord>,
    activeStart: Long,
    activeEnd: Long,
): Boolean {
    val first = words.minOfOrNull { it.begin } ?: return false
    val last = words.maxOfOrNull { it.end } ?: return false
    return first < activeStart || last > activeEnd
}

private fun remapWords(
    words: List<LyricWord>,
    originalStart: Long,
    originalEnd: Long,
    activeStart: Long,
    activeEnd: Long,
): List<LyricWord> {
    val sourceDuration = (originalEnd - originalStart).coerceAtLeast(1L)
    val activeDuration = (activeEnd - activeStart).coerceAtLeast(1L)
    return words.map { word ->
        val relativeStart = (word.begin - originalStart).coerceIn(0L, sourceDuration)
        val relativeEnd = (word.end - originalStart)
            .coerceIn(relativeStart + 1L, sourceDuration)
        val begin = activeStart + activeDuration * relativeStart / sourceDuration
        val end = (activeStart + activeDuration * relativeEnd / sourceDuration)
            .coerceAtLeast(begin + 1L)
            .coerceAtMost(activeEnd)
        word.copy(begin = begin, end = end, duration = end - begin)
    }
}

private fun allocateUnits(
    units: List<String>,
    spans: List<VoiceActivitySpan>,
    activeDuration: Long,
    minimumWordDurationMs: Long,
): List<LyricWord> {
    val unitCount = units.size
    val effectiveMinimum = minOf(
        minimumWordDurationMs,
        (activeDuration / unitCount.coerceAtLeast(1)).coerceAtLeast(1L),
    )
    return units.mapIndexed { index, text ->
        val startOffset = activeDuration * index / unitCount
        val endOffset = activeDuration * (index + 1L) / unitCount
        val begin = mapActivityOffset(spans, startOffset)
        val end = mapActivityOffset(spans, endOffset)
            .coerceAtLeast(begin + effectiveMinimum)
            .coerceAtMost(spans.last().endMs)
        LyricWord(
            text = text,
            begin = begin,
            end = end,
            duration = end - begin,
        )
    }
}

private fun mapActivityOffset(spans: List<VoiceActivitySpan>, offset: Long): Long {
    var remaining = offset.coerceAtLeast(0L)
    spans.forEach { span ->
        val duration = span.endMs - span.startMs
        if (remaining <= duration) return span.startMs + remaining
        remaining -= duration
    }
    return spans.last().endMs
}

private fun lyricUnits(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val clusters = buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
    }
    val result = mutableListOf<String>()
    val latinBuffer = StringBuilder()
    fun flushLatin() {
        if (latinBuffer.isNotEmpty()) {
            result += latinBuffer.toString()
            latinBuffer.clear()
        }
    }
    clusters.forEach { cluster ->
        when {
            cluster.isBlank() -> {
                flushLatin()
                result += cluster
            }
            cluster.any(::isCjkKanaOrHangul) -> {
                flushLatin()
                result += cluster
            }
            else -> latinBuffer.append(cluster)
        }
    }
    flushLatin()
    return result
}

private fun isCjkKanaOrHangul(character: Char): Boolean =
    character.code in 0x2E80..0x9FFF ||
        character.code in 0xAC00..0xD7AF ||
        character.code in 0x3040..0x30FF
