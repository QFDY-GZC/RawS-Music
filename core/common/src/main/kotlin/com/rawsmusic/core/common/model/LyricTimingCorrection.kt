package com.rawsmusic.core.common.model

/**
 * A continuous interval in which the separated vocal stem contains usable vocal energy.
 *
 * Times are absolute positions in the source track. The analyzer is allowed to produce
 * several spans for one lyric line; callers must not assume that a span covers a whole line.
 */
data class VoiceActivitySpan(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
) {
    init {
        require(startMs >= 0L) { "Voice activity start must be non-negative" }
        require(endMs > startMs) { "Voice activity end must be after start" }
        require(confidence in 0f..1f) { "Voice activity confidence must be within 0..1" }
    }
}

/**
 * Versioned output of the native vocal-activity pass.
 *
 * This is deliberately independent from [AiSeparationResult]. A separation result describes
 * audio files, while this map describes an analysis that may be discarded and regenerated when
 * the lyric text, source audio, or analyzer version changes.
 */
data class VoiceActivityMap(
    val sourceIdentity: String,
    val analyzerVersion: String,
    val sampleRate: Int,
    val hopMs: Long,
    val durationMs: Long,
    val spans: List<VoiceActivitySpan>,
    val generatedAtEpochMs: Long = 0L,
) {
    init {
        require(sourceIdentity.isNotBlank()) { "Voice activity source identity is required" }
        require(analyzerVersion.isNotBlank()) { "Voice activity analyzer version is required" }
        require(sampleRate > 0) { "Voice activity sample rate must be positive" }
        require(hopMs > 0L) { "Voice activity hop must be positive" }
        require(durationMs >= 0L) { "Voice activity duration must be non-negative" }
        require(spans.zipWithNext().all { (left, right) -> right.startMs >= left.startMs }) {
            "Voice activity spans must be ordered"
        }
    }

    val isUsable: Boolean
        get() = durationMs > 0L && spans.isNotEmpty()

    fun spansWithin(startMs: Long, endMs: Long): List<VoiceActivitySpan> {
        if (endMs <= startMs) return emptyList()
        return spans.filter { span -> span.endMs > startMs && span.startMs < endMs }
    }
}

enum class LyricTimingCorrectionMode {
    LINE_ACTIVITY_TRIM,
    WORD_FORCE_ALIGNMENT,
    WORD_RETIMING,
}

enum class LyricTimingCorrectionStatus {
    PREVIEW,
    ACCEPTED,
    REJECTED,
    FALLBACK,
}

data class LyricTimingChange(
    val lineIndex: Int,
    val originalStartMs: Long,
    val originalEndMs: Long,
    val correctedStartMs: Long,
    val correctedEndMs: Long,
    val confidence: Float,
) {
    init {
        require(lineIndex >= 0) { "Lyric line index must be non-negative" }
        require(originalEndMs > originalStartMs) {
            "Original lyric line end must be after start"
        }
        require(correctedEndMs > correctedStartMs) {
            "Corrected lyric line end must be after start"
        }
        require(confidence in 0f..1f) { "Lyric correction confidence must be within 0..1" }
    }
}

/** A single grapheme timing change produced by word-level forced alignment. */
data class LyricWordTimingChange(
    val lineIndex: Int,
    val wordIndex: Int,
    val text: String,
    val originalStartMs: Long?,
    val originalEndMs: Long?,
    val correctedStartMs: Long,
    val correctedEndMs: Long,
    val confidence: Float,
) {
    init {
        require(lineIndex >= 0) { "Lyric line index must be non-negative" }
        require(wordIndex >= 0) { "Lyric word index must be non-negative" }
        require(text.isNotEmpty()) { "Lyric word text must not be empty" }
        require((originalStartMs == null) == (originalEndMs == null)) {
            "Original lyric word timing must be fully present or absent"
        }
        if (originalStartMs != null && originalEndMs != null) {
            require(originalEndMs > originalStartMs) {
                "Original lyric word end must be after start"
            }
        }
        require(correctedEndMs > correctedStartMs) {
            "Corrected lyric word end must be after start"
        }
        require(confidence in 0f..1f) { "Lyric word confidence must be within 0..1" }
    }
}

/**
 * A preview/commit envelope for lyric timing changes.
 *
 * The original data is retained in the object on purpose. UI and persistence code must commit
 * [corrected] only after an explicit user confirmation; [rollback] always returns the original
 * timeline without reparsing or applying a hidden offset.
 */
data class LyricTimingCorrection(
    val correctionId: String,
    val sourceIdentity: String,
    val originalLyricHash: String,
    val analyzerVersion: String,
    val mode: LyricTimingCorrectionMode,
    val status: LyricTimingCorrectionStatus,
    val original: LyricData,
    val corrected: LyricData,
    val changes: List<LyricTimingChange>,
    /** Character-level changes. Empty for line-only analyzers. */
    val wordChanges: List<LyricWordTimingChange> = emptyList(),
    /** Duration covered by the audio analysis, in milliseconds. */
    val analyzedDurationMs: Long = 0L,
    /** Number of detected vocal activity spans used by the correction. */
    val analyzedActivitySpans: Int = 0,
) {
    init {
        require(correctionId.isNotBlank()) { "Lyric correction id is required" }
        require(sourceIdentity.isNotBlank()) { "Lyric correction source identity is required" }
        require(originalLyricHash.isNotBlank()) { "Original lyric hash is required" }
        require(analyzerVersion.isNotBlank()) { "Lyric correction analyzer version is required" }
        require(original.isTimelineCompatibleWith(corrected)) {
            "Lyric correction may change timing only, not lyric content"
        }
        require(analyzedDurationMs >= 0L) { "Analyzed duration must be non-negative" }
        require(analyzedActivitySpans >= 0) { "Analyzed activity span count must be non-negative" }
        require(changes.all { it.lineIndex in original.lines.indices }) {
            "Lyric correction contains an invalid line index"
        }
        require(wordChanges.all { it.lineIndex in original.lines.indices }) {
            "Lyric correction contains an invalid word line index"
        }
    }

    val changed: Boolean
        get() = original != corrected

    fun accept(): LyricTimingCorrection = copy(status = LyricTimingCorrectionStatus.ACCEPTED)

    fun reject(): LyricTimingCorrection = copy(status = LyricTimingCorrectionStatus.REJECTED)

    fun rollback(): LyricData = original
}

/**
 * Checks that a correction preserves all user-visible lyric content and only changes timeline
 * fields. This prevents an analyzer result from silently replacing translated, romanized,
 * background, or duet metadata.
 */
fun LyricData.isTimelineCompatibleWith(other: LyricData): Boolean {
    if (offset != other.offset || lines.size != other.lines.size) return false
    return lines.zip(other.lines).all { (left, right) ->
        left.text == right.text &&
            left.translation == right.translation &&
            left.romanization == right.romanization &&
            left.agent == right.agent &&
            left.agentName == right.agentName &&
            left.backgroundText == right.backgroundText &&
            left.backgroundTranslation == right.backgroundTranslation &&
            left.backgroundStartTime == right.backgroundStartTime &&
            left.backgroundEndTime == right.backgroundEndTime &&
            compatibleMainWords(left, right) &&
            left.pronunciationWords.map { it.text } == right.pronunciationWords.map { it.text } &&
            left.backgroundWords.map { it.text } == right.backgroundWords.map { it.text }
    }
}

private fun compatibleMainWords(left: LyricLine, right: LyricLine): Boolean {
    // `line.text` is the authoritative lyric content. Imported TTML/LRC can contain a stale or
    // differently grouped word list, while CTC intentionally rebuilds that list per grapheme.
    // Requiring both word lists to retain the same grouping prevents a valid line/word conversion.
    val authoritativeText = left.text.normalizedTimelineText()
    if (right.words.isEmpty()) return true
    return right.words.joinToString("") { it.text }.normalizedTimelineText() == authoritativeText
}

private fun String.normalizedTimelineText(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)
        .filterNot(Char::isWhitespace)
