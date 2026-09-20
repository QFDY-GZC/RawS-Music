package com.rawsmusic.core.common.model

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Frame-level CTC scores emitted by a lightweight lyric alignment model.
 *
 * The model is intentionally not coupled to ONNX here. This keeps the alignment algorithm
 * deterministic and testable while allowing the Android inference adapter to change later.
 * Values may be raw logits or log probabilities, but must be laid out as frame-major data.
 */
data class CtcFrameScores(
    val frameCount: Int,
    val tokenCount: Int,
    val values: FloatArray,
    val valuesAreLogProbabilities: Boolean = false,
) {
    init {
        require(frameCount > 0) { "CTC frame count must be positive" }
        require(tokenCount > 1) { "CTC token count must include blank and a target token" }
        require(values.size == frameCount * tokenCount) {
            "CTC score buffer size does not match frame and token dimensions"
        }
        require(values.none { it.isNaN() }) { "CTC scores must not contain NaN" }
    }

    fun value(frame: Int, token: Int): Float = values[frame * tokenCount + token]

    fun sliceFrames(startInclusive: Int, endExclusive: Int): CtcFrameScores {
        val start = startInclusive.coerceIn(0, frameCount)
        val end = endExclusive.coerceIn(start, frameCount)
        require(end > start) { "CTC frame slice must not be empty" }
        return CtcFrameScores(
            frameCount = end - start,
            tokenCount = tokenCount,
            values = values.copyOfRange(start * tokenCount, end * tokenCount),
            valuesAreLogProbabilities = valuesAreLogProbabilities,
        )
    }
}

/** A lyric token and its position in the source lyric. */
data class CtcTargetToken(
    val tokenId: Int,
    val lineIndex: Int,
    val wordIndex: Int,
    val text: String,
) {
    init {
        require(tokenId >= 0) { "CTC token id must be non-negative" }
        require(lineIndex >= 0) { "CTC line index must be non-negative" }
        require(wordIndex >= 0) { "CTC word index must be non-negative" }
        require(text.isNotEmpty()) { "CTC target token text must not be empty" }
    }
}

data class CtcVocabulary(
    val tokenIds: Map<String, Int>,
) {
    init {
        require(tokenIds.isNotEmpty()) { "CTC vocabulary must not be empty" }
        require(tokenIds.values.all { it >= 0 }) { "CTC vocabulary contains a negative token id" }
    }

    fun idFor(text: String): Int? = tokenIds[text]
}

/** Tokenizes known lyrics into grapheme targets without trying to recognize unknown text. */
object CtcLyricTokenizer {
    fun tokenize(
        lyrics: LyricData,
        vocabulary: CtcVocabulary,
        maximumTextUnits: Int = 1_024,
    ): Result<List<CtcTargetToken>> = runCatching {
        require(maximumTextUnits > 0) { "CTC text unit limit must be positive" }
        val tokens = mutableListOf<CtcTargetToken>()
        lyrics.lines.forEachIndexed { lineIndex, line ->
            // LRC and line-level TTML do not have word entries. The parsed line text is
            // still the authoritative target and must be tokenized before alignment.
            val sourceText = line.text.ifBlank {
                line.words.joinToString(separator = "") { it.text }
            }
            val units = graphemeUnits(sourceText)
                .filterNot(String::isBlank)
            require(tokens.size + units.size <= maximumTextUnits) {
                "歌词超过 CTC 文本上限"
            }
            units.forEachIndexed { wordIndex, unit ->
                val tokenId = vocabulary.idFor(unit)
                    ?: error("CTC 词表不包含歌词字符：$unit")
                tokens += CtcTargetToken(
                    tokenId = tokenId,
                    lineIndex = lineIndex,
                    wordIndex = wordIndex,
                    text = unit,
                )
            }
        }
        require(tokens.isNotEmpty()) { "歌词没有可用于 CTC 对齐的文本" }
        tokens
    }

    private fun graphemeUnits(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        return buildList {
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                add(text.substring(start, end))
                start = end
                end = iterator.next()
            }
        }
    }
}

data class CtcForcedAlignmentPolicy(
    val blankTokenId: Int = 0,
    val frameDurationMs: Long = 20L,
    val startOffsetMs: Long = 0L,
    val minimumTokenDurationMs: Long = 1L,
) {
    init {
        require(blankTokenId >= 0) { "CTC blank token id must be non-negative" }
        require(frameDurationMs > 0L) { "CTC frame duration must be positive" }
        require(startOffsetMs >= 0L) { "CTC start offset must be non-negative" }
        require(minimumTokenDurationMs > 0L) { "CTC token duration must be positive" }
    }
}

data class CtcAlignedToken(
    val targetIndex: Int,
    val lineIndex: Int,
    val wordIndex: Int,
    val text: String,
    val beginMs: Long,
    val endMs: Long,
    val confidence: Float,
) {
    init {
        require(targetIndex >= 0) { "CTC target index must be non-negative" }
        require(lineIndex >= 0) { "CTC line index must be non-negative" }
        require(wordIndex >= 0) { "CTC word index must be non-negative" }
        require(text.isNotEmpty()) { "CTC aligned token text must not be empty" }
        require(endMs > beginMs) { "CTC aligned token end must be after begin" }
        require(confidence in 0f..1f) { "CTC token confidence must be within 0..1" }
    }
}

/**
 * Viterbi forced alignment for a known lyric token sequence.
 *
 * This is deliberately a forced aligner, not an ASR decoder: the lyric text is known and the
 * model only needs to score its tokens. The expanded CTC state sequence keeps blank states and
 * prevents adjacent equal tokens from collapsing into one character.
 */
object CtcForcedAligner {
    private const val NEGATIVE_INFINITY = -1.0e30f

    /**
     * Derives the effective output stride from the analyzed audio instead of trusting a model
     * manifest blindly. This prevents a 10 ms/20 ms contract mismatch from accumulating drift
     * over a full song. The fallback is used when a caller has no reliable duration yet.
     */
    fun effectiveFrameDurationMs(
        scores: CtcFrameScores,
        durationMs: Long,
        fallbackMs: Long,
    ): Long = if (durationMs > 0L && scores.frameCount > 0) {
        (durationMs.toDouble() / scores.frameCount.toDouble())
            .roundToLong()
            .coerceAtLeast(1L)
    } else {
        fallbackMs.coerceAtLeast(1L)
    }

    fun align(
        scores: CtcFrameScores,
        target: List<CtcTargetToken>,
        policy: CtcForcedAlignmentPolicy = CtcForcedAlignmentPolicy(),
    ): List<CtcAlignedToken> {
        require(target.isNotEmpty()) { "CTC target sequence must not be empty" }
        require(policy.blankTokenId in 0 until scores.tokenCount) {
            "CTC blank token id is outside the model vocabulary"
        }
        require(target.all { it.tokenId in 0 until scores.tokenCount }) {
            "CTC target contains a token outside the model vocabulary"
        }
        require(scores.frameCount >= target.size) {
            "CTC model does not provide enough frames for the lyric target"
        }

        val stateCount = target.size * 2 + 1
        val stateLabels = IntArray(stateCount) { state ->
            if (state % 2 == 0) policy.blankTokenId else target[state / 2].tokenId
        }
        val frameScores = normalizedLogProbabilities(scores)
        val previous = FloatArray(stateCount) { NEGATIVE_INFINITY }
        val backPointers = Array(scores.frameCount) { IntArray(stateCount) { -1 } }
        previous[0] = frameScores[policy.blankTokenId]
        previous[1] = frameScores[target.first().tokenId]

        for (frame in 1 until scores.frameCount) {
            val current = FloatArray(stateCount) { NEGATIVE_INFINITY }
            for (state in 0 until stateCount) {
                var bestState = state
                var bestScore = previous[state]
                if (state > 0 && previous[state - 1] > bestScore) {
                    bestState = state - 1
                    bestScore = previous[state - 1]
                }
                if (state > 1 && stateLabels[state] != policy.blankTokenId &&
                    stateLabels[state] != stateLabels[state - 2] &&
                    previous[state - 2] > bestScore
                ) {
                    bestState = state - 2
                    bestScore = previous[state - 2]
                }
                if (bestScore > NEGATIVE_INFINITY / 2f) {
                    current[state] = bestScore + frameScores[frame * scores.tokenCount + stateLabels[state]]
                    backPointers[frame][state] = bestState
                }
            }
            previous.indices.forEach { state -> previous[state] = current[state] }
        }

        var state = when {
            previous[stateCount - 1] >= previous[stateCount - 2] -> stateCount - 1
            else -> stateCount - 2
        }
        require(previous[state] > NEGATIVE_INFINITY / 2f) {
            "CTC target cannot be aligned to the supplied audio frames"
        }

        val path = IntArray(scores.frameCount)
        for (frame in scores.frameCount - 1 downTo 0) {
            path[frame] = state
            if (frame > 0) {
                state = backPointers[frame][state]
                require(state >= 0) { "CTC backtrace contains an invalid state" }
            }
        }

        return target.mapIndexed { targetIndex, token ->
            val tokenState = targetIndex * 2 + 1
            var firstFrame = -1
            var lastFrame = -1
            var confidenceSum = 0.0
            var confidenceCount = 0
            path.forEachIndexed { frame, pathState ->
                if (pathState == tokenState) {
                    if (firstFrame < 0) firstFrame = frame
                    lastFrame = frame
                    confidenceSum += exp(
                        frameScores[frame * scores.tokenCount + token.tokenId].toDouble(),
                    ).coerceIn(0.0, 1.0)
                    confidenceCount++
                }
            }
            require(firstFrame >= 0) { "CTC backtrace skipped target token $targetIndex" }
            val beginMs = policy.startOffsetMs + firstFrame * policy.frameDurationMs
            val endMs = maxOf(
                beginMs + policy.minimumTokenDurationMs,
                policy.startOffsetMs + (lastFrame + 1L) * policy.frameDurationMs,
            )
            CtcAlignedToken(
                targetIndex = targetIndex,
                lineIndex = token.lineIndex,
                wordIndex = token.wordIndex,
                text = token.text,
                beginMs = beginMs,
                endMs = endMs,
                confidence = (confidenceSum / confidenceCount.coerceAtLeast(1))
                    .toFloat()
                    .coerceIn(0f, 1f),
            )
        }
    }

    /**
     * Aligns each lyric line inside its own coarse timestamp window. A single whole-song CTC
     * pass can let a long line consume frames belonging to the next line, especially when the
     * source lyrics contain long instrumental gaps. Keeping the operation here makes it language
     * agnostic: the caller only supplies the already-tokenized target sequence.
     */
    fun alignByLyricLineWindows(
        scores: CtcFrameScores,
        target: List<CtcTargetToken>,
        lyrics: LyricData,
        durationMs: Long,
        policy: CtcForcedAlignmentPolicy = CtcForcedAlignmentPolicy(),
        windowPaddingMs: Long = 240L,
    ): List<CtcAlignedToken> {
        require(target.isNotEmpty()) { "CTC target sequence must not be empty" }
        require(durationMs >= 0L) { "CTC duration must be non-negative" }
        require(windowPaddingMs >= 0L) { "CTC window padding must be non-negative" }

        val byLine = target.withIndex()
            .groupBy { it.value.lineIndex }
            .toSortedMap()
        if (byLine.size <= 1 || lyrics.lines.isEmpty()) {
            return align(scores, target, policy)
        }

        val aligned = mutableListOf<CtcAlignedToken>()
        byLine.forEach { (lineIndex, lineItems) ->
            val lineTarget = lineItems.map { it.value }
            val line = lyrics.lines.getOrNull(lineIndex)
                ?: return@forEach
            val lineStart = line.timeStamp.coerceAtLeast(0L)
            val nextStart = lyrics.lines.getOrNull(lineIndex + 1)?.timeStamp
                ?.takeIf { it > lineStart }
            val lineEnd = when {
                line.endTime > lineStart -> line.endTime
                nextStart != null -> nextStart
                durationMs > lineStart -> durationMs
                else -> lineStart + policy.frameDurationMs
            }
            if (lineEnd <= lineStart) return@forEach

            val windowStartMs = (lineStart - windowPaddingMs).coerceAtLeast(policy.startOffsetMs)
            val windowEndMs = (lineEnd + windowPaddingMs).coerceAtMost(
                durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
            )
            val firstFrame = ((windowStartMs - policy.startOffsetMs) /
                policy.frameDurationMs).toInt().coerceIn(0, scores.frameCount - 1)
            val lastFrameExclusive = ceil(
                (windowEndMs - policy.startOffsetMs).toDouble() / policy.frameDurationMs,
            ).toInt().coerceIn(firstFrame + 1, scores.frameCount)
            val frameWindow = scores.sliceFrames(firstFrame, lastFrameExclusive)
            if (frameWindow.frameCount < lineTarget.size) return@forEach

            val local = align(
                scores = frameWindow,
                target = lineTarget,
                policy = policy.copy(
                    startOffsetMs = policy.startOffsetMs + firstFrame * policy.frameDurationMs,
                ),
            )
            local.forEachIndexed { localIndex, item ->
                aligned += item.copy(targetIndex = lineItems[localIndex].index)
            }
        }

        // A malformed or untimed lyric line must not silently discard the rest of the result.
        // Returning the global path preserves the previous behavior for that edge case.
        return if (aligned.size == target.size) aligned else align(scores, target, policy)
    }

    private fun normalizedLogProbabilities(scores: CtcFrameScores): FloatArray {
        if (scores.valuesAreLogProbabilities) return scores.values.copyOf()
        val normalized = FloatArray(scores.values.size)
        repeat(scores.frameCount) { frame ->
            val offset = frame * scores.tokenCount
            var maximum = NEGATIVE_INFINITY
            repeat(scores.tokenCount) { token ->
                maximum = maxOf(maximum, scores.values[offset + token])
            }
            var exponentialSum = 0.0
            repeat(scores.tokenCount) { token ->
                exponentialSum += exp((scores.values[offset + token] - maximum).toDouble())
            }
            val logSum = ln(exponentialSum.coerceAtLeast(Double.MIN_VALUE)).toFloat()
            repeat(scores.tokenCount) { token ->
                normalized[offset + token] = scores.values[offset + token] - maximum - logSum
            }
        }
        return normalized
    }
}

/**
 * Converts known CTC word timestamps into a preview-only lyric correction.
 *
 * Lines without a complete token mapping are left untouched. This is important for a partial
 * model result: a failed/low-confidence line must not erase existing word timing or metadata.
 */
fun LyricData.previewCtcWordRetiming(
    alignedTokens: List<CtcAlignedToken>,
    sourceIdentity: String,
    originalLyricHash: String,
    correctionId: String,
    durationMs: Long,
    analyzerVersion: String = "ctc-lite-v1",
    lineStartAllowanceMs: Long = 240L,
    lineTailAllowanceMs: Long = 1_000L,
    boundaryOverlapAllowanceMs: Long = 360L,
    minimumConfidence: Float = 0.55f,
    maxAccompanimentGapMs: Long = 3_000L,
    minimumLineDurationMs: Long = 120L,
): LyricTimingCorrection {
    require(sourceIdentity.isNotBlank()) { "Lyric correction source identity is required" }
    require(originalLyricHash.isNotBlank()) { "Original lyric hash is required" }
    require(correctionId.isNotBlank()) { "Lyric correction id is required" }
    require(analyzerVersion.isNotBlank()) { "CTC analyzer version is required" }
    require(durationMs >= 0L) { "Lyric duration must be non-negative" }
    require(lineStartAllowanceMs >= 0L) { "Line start allowance must be non-negative" }
    require(lineTailAllowanceMs >= 0L) { "Line tail allowance must be non-negative" }
    require(boundaryOverlapAllowanceMs >= 0L) { "Boundary overlap allowance must be non-negative" }
    require(minimumConfidence in 0f..1f) { "Minimum confidence must be within 0..1" }
    require(maxAccompanimentGapMs >= 0L) { "Maximum accompaniment gap must be non-negative" }

    val correctedLines = lines.toMutableList()
    val alignedLineIndexes = mutableSetOf<Int>()
    val changes = mutableListOf<LyricTimingChange>()
    val wordChanges = mutableListOf<LyricWordTimingChange>()
    alignedTokens.groupBy { it.lineIndex }.forEach { (lineIndex, lineTokens) ->
        val line = lines.getOrNull(lineIndex) ?: return@forEach
        val sortedTokens = lineTokens.sortedBy { it.wordIndex }
        val expectedText = line.text.filterNot(Char::isWhitespace)
        val actualText = sortedTokens.joinToString(separator = "") { it.text }
            .filterNot(Char::isWhitespace)
        if (expectedText != actualText || sortedTokens.isEmpty()) return@forEach
        val confidence = sortedTokens.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
        if (confidence < minimumConfidence) return@forEach

        val originalStart = line.timeStamp.coerceAtLeast(0L)
        val nextStart = lines.getOrNull(lineIndex + 1)?.timeStamp
            ?.takeIf { it > originalStart }
        val originalEnd = when {
            line.endTime > originalStart -> line.endTime
            nextStart != null -> nextStart
            durationMs > originalStart -> durationMs
            else -> originalStart + minimumLineDurationMs
        }.coerceAtLeast(originalStart + minimumLineDurationMs)
        // CTC frame timestamps can lag the first audible phoneme by one or two frames. Do not
        // move a known lyric line later than its source timestamp; a small lead-in keeps the
        // first character from arriving after the singer. Likewise, the source line end is a
        // floor, not a ceiling: the old next-line clamp cut off sustained final syllables.
        val correctedStart = (sortedTokens.minOf { it.beginMs } - lineStartAllowanceMs)
            .coerceAtLeast(0L)
            .coerceAtMost(originalStart)
        val minimumEnd = correctedStart + minimumLineDurationMs
        val alignedEndWithTail = sortedTokens.maxOf { it.endMs } + lineTailAllowanceMs
        // Keep the source boundary for a normal line. Only trim a clearly excessive gap,
        // which is the accompaniment-only case this preview is meant to remove. Never use the
        // next line's timestamp as a hard ceiling: sustained vocals may cross that boundary.
        val desiredEnd = if (originalEnd - alignedEndWithTail > maxAccompanimentGapMs) {
            alignedEndWithTail
        } else {
            maxOf(originalEnd, alignedEndWithTail)
        }
        val correctedEnd = desiredEnd.coerceAtMost(
            durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        ).coerceAtLeast(minimumEnd)
        if (correctedStart == originalStart && correctedEnd == originalEnd && line.words.isNotEmpty()) {
            return@forEach
        }

        // Character CTC indices refer to grapheme clusters, not the provider's existing word
        // entries. Reusing those entries can assign one timestamp to several characters.
        val correctedWords = normalizeCharacterTokenTimeline(
            tokens = sortedTokens.mapIndexed { index, token ->
                token.copy(
                    // Keep word onset at the acoustic frame. The line itself receives the
                    // lead-in; moving the first word would create a false early highlight.
                    beginMs = token.beginMs,
                    endMs = if (index == sortedTokens.lastIndex) {
                        token.endMs + lineTailAllowanceMs
                    } else token.endMs,
                )
            },
            lineStartMs = correctedStart,
            lineEndMs = correctedEnd,
        )
        val correctedLine = line.copy(
            timeStamp = correctedStart,
            endTime = correctedEnd,
            words = correctedWords,
        )
        correctedLines[lineIndex] = correctedLine
        alignedLineIndexes += lineIndex
        val originalWords = originalCharacterTimeline(line)
        correctedWords.forEachIndexed { wordIndex, correctedWord ->
            val token = sortedTokens[wordIndex]
            val originalWord = originalWords.getOrNull(wordIndex)
                ?.takeIf { it.text == correctedWord.text }
            wordChanges += LyricWordTimingChange(
                lineIndex = lineIndex,
                wordIndex = wordIndex,
                text = correctedWord.text,
                originalStartMs = originalWord?.begin,
                originalEndMs = originalWord?.end,
                correctedStartMs = correctedWord.begin,
                correctedEndMs = correctedWord.end,
                confidence = token.confidence,
            )
        }
        changes += LyricTimingChange(
            lineIndex = lineIndex,
            originalStartMs = originalStart,
            originalEndMs = originalEnd,
            correctedStartMs = correctedStart,
            correctedEndMs = correctedEnd,
            confidence = confidence,
        )
    }

    // A word-timed result is only meaningful when every non-empty lyric line was aligned.
    // Keeping the original words for missed lines creates a mixed line/word file and makes the
    // preview appear successful even though playback will use two different timing formats.
    val requiredLineIndexes = lines.indices.filter { lines[it].text.isNotBlank() }.toSet()
    if (alignedLineIndexes != requiredLineIndexes) {
        return LyricTimingCorrection(
            correctionId = correctionId,
            sourceIdentity = sourceIdentity,
            originalLyricHash = originalLyricHash,
            analyzerVersion = analyzerVersion,
            mode = LyricTimingCorrectionMode.WORD_FORCE_ALIGNMENT,
            status = LyricTimingCorrectionStatus.FALLBACK,
            original = this,
            corrected = this,
            changes = emptyList(),
            analyzedDurationMs = durationMs,
        )
    }

    val corrected = copy(lines = correctedLines)
    return LyricTimingCorrection(
        correctionId = correctionId,
        sourceIdentity = sourceIdentity,
        originalLyricHash = originalLyricHash,
        analyzerVersion = analyzerVersion,
        mode = LyricTimingCorrectionMode.WORD_FORCE_ALIGNMENT,
        status = if (changes.isEmpty()) LyricTimingCorrectionStatus.FALLBACK
        else LyricTimingCorrectionStatus.PREVIEW,
        original = this,
        corrected = corrected,
        changes = changes,
        wordChanges = wordChanges,
        analyzedDurationMs = durationMs,
    )
}

private fun originalCharacterTimeline(line: LyricLine): List<LyricWord> =
    line.words.flatMap { word ->
        val units = characterUnits(word.text).filterNot(String::isBlank)
        if (units.isEmpty()) return@flatMap emptyList()
        if (units.size == 1) return@flatMap listOf(word.copy(text = units.single()))

        val start = word.begin
        val duration = (word.end - start).coerceAtLeast(units.size.toLong())
        units.mapIndexed { index, unit ->
            val begin = start + duration * index / units.size
            val end = start + duration * (index + 1L) / units.size
            LyricWord(
                text = unit,
                begin = begin,
                end = end.coerceAtLeast(begin + 1L),
                duration = (end - begin).coerceAtLeast(1L),
            )
        }
    }

private fun characterUnits(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
    }
}

private fun normalizeCharacterTokenTimeline(
    tokens: List<CtcAlignedToken>,
    lineStartMs: Long,
    lineEndMs: Long,
): List<LyricWord> {
    if (tokens.isEmpty() || lineEndMs <= lineStartMs) return emptyList()
    val minimumDurationMs = 1L
    val requiredDuration = tokens.size * minimumDurationMs
    val safeEnd = maxOf(lineEndMs, lineStartMs + requiredDuration)
    var cursor = lineStartMs

    return tokens.mapIndexed { index, token ->
        val remaining = tokens.lastIndex - index
        val latestBegin = safeEnd - (remaining + 1L) * minimumDurationMs
        val begin = token.beginMs
            .coerceAtLeast(cursor)
            .coerceAtMost(latestBegin)
        val nextRawBegin = tokens.getOrNull(index + 1)?.beginMs
        val latestEnd = safeEnd - remaining * minimumDurationMs
        val end = minOf(
            token.endMs.coerceAtLeast(begin + minimumDurationMs),
            nextRawBegin?.coerceAtLeast(begin + minimumDurationMs) ?: latestEnd,
            latestEnd,
        ).coerceAtLeast(begin + minimumDurationMs)
        cursor = end
        LyricWord(
            text = token.text,
            begin = begin,
            end = end,
            duration = end - begin,
        )
    }
}

/**
 * Applies phone-level alignment without pretending that phone symbols are lyric characters.
 * Several acoustic models emit multiple phones for one lyric word; this path groups them back
 * into the original word before building the preview correction.
 */
fun LyricData.previewPhoneWordRetiming(
    alignedTokens: List<CtcAlignedToken>,
    wordTexts: Map<Pair<Int, Int>, String>,
    sourceIdentity: String,
    originalLyricHash: String,
    correctionId: String,
    durationMs: Long,
    analyzerVersion: String,
    lineStartAllowanceMs: Long = 240L,
    lineTailAllowanceMs: Long = 1_000L,
    boundaryOverlapAllowanceMs: Long = 360L,
    minimumConfidence: Float = 0.55f,
    maxAccompanimentGapMs: Long = 3_000L,
    minimumLineDurationMs: Long = 120L,
): LyricTimingCorrection {
    require(sourceIdentity.isNotBlank()) { "Lyric correction source identity is required" }
    require(originalLyricHash.isNotBlank()) { "Original lyric hash is required" }
    require(correctionId.isNotBlank()) { "Lyric correction id is required" }
    require(analyzerVersion.isNotBlank()) { "Phone aligner version is required" }
    require(durationMs >= 0L) { "Lyric duration must be non-negative" }
    require(lineStartAllowanceMs >= 0L) { "Line start allowance must be non-negative" }
    require(lineTailAllowanceMs >= 0L) { "Line tail allowance must be non-negative" }
    require(boundaryOverlapAllowanceMs >= 0L) { "Boundary overlap allowance must be non-negative" }
    require(minimumConfidence in 0f..1f) { "Minimum confidence must be within 0..1" }
    require(maxAccompanimentGapMs >= 0L) { "Maximum accompaniment gap must be non-negative" }

    val correctedLines = lines.toMutableList()
    val alignedLineIndexes = mutableSetOf<Int>()
    val changes = mutableListOf<LyricTimingChange>()
    val wordChanges = mutableListOf<LyricWordTimingChange>()
    alignedTokens.groupBy { it.lineIndex }.forEach { (lineIndex, lineTokens) ->
        val line = lines.getOrNull(lineIndex) ?: return@forEach
        val sortedTokens = lineTokens.sortedWith(compareBy<CtcAlignedToken> { it.wordIndex }.thenBy { it.beginMs })
        if (sortedTokens.isEmpty()) return@forEach
        val confidence = sortedTokens.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
        if (confidence < minimumConfidence) return@forEach
        val originalStart = line.timeStamp.coerceAtLeast(0L)
        val nextStart = lines.getOrNull(lineIndex + 1)?.timeStamp
            ?.takeIf { it > originalStart }
        val originalEnd = when {
            line.endTime > originalStart -> line.endTime
            nextStart != null -> nextStart
            durationMs > originalStart -> durationMs
            else -> originalStart + minimumLineDurationMs
        }.coerceAtLeast(originalStart + minimumLineDurationMs)
        val correctedStart = (sortedTokens.minOf { it.beginMs } - lineStartAllowanceMs)
            .coerceAtLeast(0L)
            .coerceAtMost(originalStart)
        val minimumEnd = correctedStart + minimumLineDurationMs
        val alignedEndWithTail = sortedTokens.maxOf { it.endMs } + lineTailAllowanceMs
        val desiredEnd = if (originalEnd - alignedEndWithTail > maxAccompanimentGapMs) {
            alignedEndWithTail
        } else {
            maxOf(originalEnd, alignedEndWithTail)
        }
        val correctedEnd = desiredEnd.coerceAtMost(
            durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        ).coerceAtLeast(minimumEnd)

        val byWord = sortedTokens.groupBy { it.wordIndex }.toSortedMap()
        val alignedWords = normalizePhoneWordTimeline(
            words = byWord.mapNotNull { (wordIndex, phones) ->
                val text = wordTexts[lineIndex to wordIndex].orEmpty()
                if (text.isEmpty()) null else PhoneAlignedWord(
                    text = text,
                    beginMs = phones.minOf { it.beginMs },
                    endMs = phones.maxOf { it.endMs } +
                        if (wordIndex == byWord.lastKey()) lineTailAllowanceMs else 0L,
                    confidence = phones.map { it.confidence }.average().toFloat().coerceIn(0f, 1f),
                )
            },
            lineStartMs = correctedStart,
            lineEndMs = correctedEnd,
        )
        val alignedText = alignedWords.joinToString("") { it.text }.filterNot(Char::isWhitespace)
        val expectedText = line.text.filterNot(Char::isWhitespace)
        val usesAlignedWords = alignedWords.isNotEmpty() && alignedText == expectedText
        val correctedWords = if (usesAlignedWords || line.words.isEmpty()) {
            alignedWords
        } else {
            line.words.mapIndexed { wordIndex, word ->
                val phones = byWord[wordIndex]
                if (phones.isNullOrEmpty()) word else word.copy(
                    begin = phones.minOf { it.beginMs },
                    end = phones.maxOf { it.endMs },
                    duration = phones.maxOf { it.endMs } - phones.minOf { it.beginMs },
                )
            }
        }
        correctedLines[lineIndex] = line.copy(
            timeStamp = correctedStart,
            endTime = correctedEnd,
            words = correctedWords,
        )
        if (correctedWords.isNotEmpty()) alignedLineIndexes += lineIndex
        val originalWords = originalCharacterTimeline(line)
        val previewWords = correctedWords.flatMap(::splitWordForPreview)
        previewWords.forEachIndexed { wordIndex, correctedWord ->
            val originalWord = originalWords.getOrNull(wordIndex)
                ?.takeIf { it.text == correctedWord.text }
            val confidence = byWord[wordIndex]
                ?.map { it.confidence }
                ?.average()
                ?.toFloat()
                ?.coerceIn(0f, 1f)
                ?: sortedTokens.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
            wordChanges += LyricWordTimingChange(
                lineIndex = lineIndex,
                wordIndex = wordIndex,
                text = correctedWord.text,
                originalStartMs = originalWord?.begin,
                originalEndMs = originalWord?.end,
                correctedStartMs = correctedWord.begin,
                correctedEndMs = correctedWord.end,
                confidence = confidence,
            )
        }
        changes += LyricTimingChange(
            lineIndex = lineIndex,
            originalStartMs = originalStart,
            originalEndMs = originalEnd,
            correctedStartMs = correctedStart,
            correctedEndMs = correctedEnd,
            confidence = confidence,
        )
    }

    val requiredLineIndexes = lines.indices.filter { lines[it].text.isNotBlank() }.toSet()
    if (alignedLineIndexes != requiredLineIndexes) {
        return LyricTimingCorrection(
            correctionId = correctionId,
            sourceIdentity = sourceIdentity,
            originalLyricHash = originalLyricHash,
            analyzerVersion = analyzerVersion,
            mode = LyricTimingCorrectionMode.WORD_FORCE_ALIGNMENT,
            status = LyricTimingCorrectionStatus.FALLBACK,
            original = this,
            corrected = this,
            changes = emptyList(),
            analyzedDurationMs = durationMs,
        )
    }

    return LyricTimingCorrection(
        correctionId = correctionId,
        sourceIdentity = sourceIdentity,
        originalLyricHash = originalLyricHash,
        analyzerVersion = analyzerVersion,
        mode = LyricTimingCorrectionMode.WORD_FORCE_ALIGNMENT,
        status = if (changes.isEmpty()) LyricTimingCorrectionStatus.FALLBACK
        else LyricTimingCorrectionStatus.PREVIEW,
        original = this,
        corrected = copy(lines = correctedLines),
        changes = changes,
        wordChanges = wordChanges,
        analyzedDurationMs = durationMs,
    )
}

private data class PhoneAlignedWord(
    val text: String,
    val beginMs: Long,
    val endMs: Long,
    val confidence: Float,
)

private fun normalizePhoneWordTimeline(
    words: List<PhoneAlignedWord>,
    lineStartMs: Long,
    lineEndMs: Long,
): List<LyricWord> {
    if (words.isEmpty() || lineEndMs <= lineStartMs) return emptyList()
    var cursor = lineStartMs
    return words.mapIndexed { index, word ->
        val remaining = words.lastIndex - index
        val latestBegin = lineEndMs - remaining - 1L
        val begin = word.beginMs.coerceAtLeast(cursor).coerceAtMost(latestBegin)
        val latestEnd = lineEndMs - remaining
        val nextBegin = words.getOrNull(index + 1)?.beginMs
        val end = minOf(
            word.endMs.coerceAtLeast(begin + 1L),
            nextBegin?.coerceAtLeast(begin + 1L) ?: latestEnd,
            latestEnd,
        ).coerceAtLeast(begin + 1L)
        cursor = end
        LyricWord(text = word.text, begin = begin, end = end, duration = end - begin)
    }
}

private fun splitWordForPreview(word: LyricWord): List<LyricWord> {
    val units = characterUnits(word.text).filterNot(String::isBlank)
    if (units.size <= 1) return if (units.isEmpty()) emptyList() else listOf(word.copy(text = units.single()))
    val duration = (word.end - word.begin).coerceAtLeast(units.size.toLong())
    return units.mapIndexed { index, unit ->
        val begin = word.begin + duration * index / units.size
        val end = word.begin + duration * (index + 1L) / units.size
        LyricWord(unit, begin, end.coerceAtLeast(begin + 1L), (end - begin).coerceAtLeast(1L))
    }
}
