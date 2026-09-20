package com.rawsmusic.core.common.model

data class LyricActivityTrimPolicy(
    val minimumConfidence: Float = 0.45f,
    // Activity onset is often later than the lyric timestamp. Do not trim short pauses.
    val minimumTrimMs: Long = 1_200L,
    val leadInTrimThresholdMs: Long = 700L,
    val maximumLeadInTrimMs: Long = 1_500L,
    // Keep the detected vocal tail inside the original lyric window instead of ending early.
    val tailAllowanceMs: Long = 500L,
    val minimumLineDurationMs: Long = 120L,
    val trimLeadIn: Boolean = false,
) {
    init {
        require(minimumConfidence in 0f..1f) { "Activity confidence must be within 0..1" }
        require(minimumTrimMs >= 0L) { "Minimum trim must be non-negative" }
        require(leadInTrimThresholdMs >= 0L) { "Lead-in threshold must be non-negative" }
        require(maximumLeadInTrimMs >= leadInTrimThresholdMs) {
            "Maximum lead-in trim must cover the lead-in threshold"
        }
        require(tailAllowanceMs >= 0L) { "Tail allowance must be non-negative" }
        require(minimumLineDurationMs > 0L) { "Minimum line duration must be positive" }
    }
}

/**
 * Builds a preview-only line timing correction from a separated vocal activity map.
 *
 * The lyric text, translation, pronunciation, word timestamps, background voices and agent
 * metadata are copied unchanged. The source [LyricData] is never mutated and remains available
 * through [LyricTimingCorrection.rollback].
 */
fun LyricData.previewActivityTrim(
    activityMap: VoiceActivityMap,
    sourceIdentity: String,
    originalLyricHash: String,
    correctionId: String,
    durationMs: Long = activityMap.durationMs,
    policy: LyricActivityTrimPolicy = LyricActivityTrimPolicy(),
): LyricTimingCorrection {
    require(sourceIdentity.isNotBlank()) { "Lyric correction source identity is required" }
    require(originalLyricHash.isNotBlank()) { "Original lyric hash is required" }
    require(correctionId.isNotBlank()) { "Lyric correction id is required" }
    require(activityMap.sourceIdentity == sourceIdentity) { "Voice activity source identity mismatch" }
    require(durationMs >= 0L) { "Lyric duration must be non-negative" }

    if (lines.isEmpty() || !activityMap.isUsable) {
        return LyricTimingCorrection(
            correctionId = correctionId,
            sourceIdentity = sourceIdentity,
            originalLyricHash = originalLyricHash,
            analyzerVersion = activityMap.analyzerVersion,
            mode = LyricTimingCorrectionMode.LINE_ACTIVITY_TRIM,
            status = LyricTimingCorrectionStatus.FALLBACK,
            original = this,
            corrected = this,
            changes = emptyList(),
            analyzedDurationMs = activityMap.durationMs,
            analyzedActivitySpans = activityMap.spans.size,
        )
    }

    val correctedLines = lines.toMutableList()
    val changes = mutableListOf<LyricTimingChange>()
    lines.forEachIndexed { index, line ->
        val originalStart = line.timeStamp.coerceAtLeast(0L)
        val nextStart = lines.getOrNull(index + 1)?.timeStamp
            ?.takeIf { it > originalStart }
        val originalEnd = when {
            line.endTime > originalStart -> line.endTime
            nextStart != null -> nextStart
            durationMs > originalStart -> durationMs
            else -> originalStart + policy.minimumLineDurationMs
        }.coerceAtLeast(originalStart + policy.minimumLineDurationMs)
        val boundedEnd = minOf(originalEnd, durationMs.takeIf { it > originalStart } ?: originalEnd)
        val spans = activityMap.spansWithin(originalStart, boundedEnd)
            .filter { it.confidence >= policy.minimumConfidence }
        if (spans.isEmpty()) return@forEachIndexed

        val activityStart = spans.minOf { it.startMs.coerceIn(originalStart, boundedEnd) }
        val activityEnd = spans.maxOf { it.endMs.coerceIn(originalStart, boundedEnd) }
        var correctedStart = originalStart
        var correctedEnd = originalEnd
        if (policy.trimLeadIn) {
            val leadIn = activityStart - originalStart
            if (leadIn in policy.leadInTrimThresholdMs..policy.maximumLeadInTrimMs) {
                correctedStart = activityStart
            }
        }
        val tail = originalEnd - activityEnd
        if (tail >= policy.minimumTrimMs) {
            correctedEnd = (activityEnd + policy.tailAllowanceMs).coerceAtMost(originalEnd)
        }
        if (nextStart != null) correctedEnd = correctedEnd.coerceAtMost(nextStart)
        correctedEnd = correctedEnd.coerceAtLeast(correctedStart + policy.minimumLineDurationMs)
        if (correctedStart == originalStart && correctedEnd == originalEnd) return@forEachIndexed

        val correctedLine = line.copy(
            timeStamp = correctedStart,
            endTime = correctedEnd,
        )
        correctedLines[index] = correctedLine
        changes += LyricTimingChange(
            lineIndex = index,
            originalStartMs = originalStart,
            originalEndMs = originalEnd,
            correctedStartMs = correctedStart,
            correctedEndMs = correctedEnd,
            confidence = spans.map { it.confidence }.average().toFloat().coerceIn(0f, 1f),
        )
    }

    val corrected = copy(lines = correctedLines)
    return LyricTimingCorrection(
        correctionId = correctionId,
        sourceIdentity = sourceIdentity,
        originalLyricHash = originalLyricHash,
        analyzerVersion = activityMap.analyzerVersion,
        mode = LyricTimingCorrectionMode.LINE_ACTIVITY_TRIM,
        status = if (changes.isEmpty()) LyricTimingCorrectionStatus.FALLBACK
        else LyricTimingCorrectionStatus.PREVIEW,
        original = this,
        corrected = corrected,
        changes = changes,
        analyzedDurationMs = activityMap.durationMs,
        analyzedActivitySpans = activityMap.spans.size,
    )
}
