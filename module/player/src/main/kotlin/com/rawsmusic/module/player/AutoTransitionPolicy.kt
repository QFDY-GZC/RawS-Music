package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import kotlin.math.log10
import kotlin.math.max
import java.util.ArrayDeque

/**
 * Lyrics-first automatic track transition policy.
 *
 * The controller builds a recipe from semantic timing (lyrics) when it is trustworthy. The audio
 * thread then owns the real-time envelope fallback and final gain automation. This keeps
 * split between a composer recipe and renderer automation, while using RawSMusic's richer lyric
 * timing as the primary structural feature.
 */
internal object AutoTransitionPolicy {
    const val MIN_MEANINGFUL_LYRIC_LINES = 4
    const val MAX_LYRIC_PREROLL_MS = 30_000L
    const val ENVELOPE_LOOKAHEAD_MS = 30_000L
    /** Tracks shorter than one complete analysis window are kept gapless. */
    const val MIN_TRACK_DURATION_MS = ENVELOPE_LOOKAHEAD_MS
    const val HARD_END_SAFETY_REMAINING_MS = 15_000L
    /** Smart transitions use the available musical tail instead of a fixed 8 s cap. */
    const val MIN_HANDOVER_MS = 6_000
    const val DEFAULT_HANDOVER_MS = 12_000
    const val MAX_HANDOVER_MS = 15_000
    /** The nominal point where lead/follow ownership is equal. */
    const val SMART_PIVOT_PERCENT = 55
    /** Compatibility name used by the quiet-tail retimer. */
    const val LEAD_FADE_WINDOW_MS = MAX_HANDOVER_MS

    enum class Source {
        LYRICS,
        ENVELOPE
    }

    data class Recipe(
        val targetPath: String,
        val source: Source,
        /** Absolute current-track position where the next decoder may be prepared. */
        val triggerPositionMs: Long?,
        /** Absolute current-track position where the formal handover should start. */
        val handoverPositionMs: Long?,
        val handoverDurationMs: Int,
        val envelopeLookaheadMs: Long = ENVELOPE_LOOKAHEAD_MS,
        val confidence: Float,
        val incomingEntryAllowed: Boolean = true,
        val outgoingTailAllowed: Boolean = true,
        /** Start of the protected final-phrase overlap; formal gain exchange still follows vocal end. */
        val gentleStartPositionMs: Long? = null,
        val vocalEndKnown: Boolean = true,
    )

    data class Decision(
        val recipe: Recipe?,
        val forceGapless: Boolean,
        val reason: String,
    )

    fun build(
        current: AudioFile?,
        next: AudioFile?,
        lyrics: LyricData?,
        allowSameItemCrossfade: Boolean = false,
    ): Decision {
        if (current == null || next == null || next.path.isBlank()) {
            return Decision(null, forceGapless = false, reason = "missing_track")
        }
        if (
            !allowSameItemCrossfade &&
            current.path == next.path &&
            current.cueTrackIndex == next.cueTrackIndex &&
            current.cueOffsetMs == next.cueOffsetMs
        ) {
            return Decision(null, forceGapless = true, reason = "same_item_repeat_gapless")
        }
        if (isKnownShortTrack(current) || isKnownShortTrack(next)) {
            return Decision(null, forceGapless = true, reason = "short_track_gapless")
        }
        if (isSequentialAlbumPair(current, next)) {
            return Decision(null, forceGapless = true, reason = "sequential_album_gapless")
        }
        if (current.isDsdFormat || next.isDsdFormat || current.bitsPerSample == 1 || next.bitsPerSample == 1) {
            return Decision(null, forceGapless = true, reason = "dsd_gapless")
        }
        if (current.path == next.path && current.cueTrackIndex > 0 && next.cueTrackIndex == current.cueTrackIndex + 1) {
            return Decision(null, forceGapless = true, reason = "cue_gapless")
        }

        val durationMs = current.duration.coerceAtLeast(0L)
        val lyricRecipe = buildLyricsRecipe(
            currentDurationMs = durationMs,
            targetPath = next.path,
            lyrics = lyrics,
        )
        if (lyricRecipe != null) {
            return Decision(lyricRecipe.copy(incomingEntryAllowed =
                next.cueTrackIndex <= 0 && next.cueOffsetMs <= 0L,
                outgoingTailAllowed = current.cueTrackIndex <= 0 && current.cueOffsetMs <= 0L),
                forceGapless = false, reason = "lyrics")
        }

        return Decision(
            recipe = Recipe(
                targetPath = next.path,
                source = Source.ENVELOPE,
                triggerPositionMs = null,
                handoverPositionMs = null,
                handoverDurationMs = DEFAULT_HANDOVER_MS,
                // Sparse text is not reliable vocal structure. Give its audio-only detector a
                // bounded final-fifth window instead of trusting the two text timestamps.
                envelopeLookaheadMs = if ((lyrics?.lines?.count { it.text.isNotBlank() } ?: 0) in 1..3) {
                    (durationMs / 5L).coerceIn(ENVELOPE_LOOKAHEAD_MS, 60_000L)
                } else ENVELOPE_LOOKAHEAD_MS,
                confidence = 0.45f,
                incomingEntryAllowed = next.cueTrackIndex <= 0 && next.cueOffsetMs <= 0L,
                outgoingTailAllowed = current.cueTrackIndex <= 0 && current.cueOffsetMs <= 0L,
            ),
            forceGapless = false,
            reason = "envelope_fallback",
        )
    }

    private fun buildLyricsRecipe(
        currentDurationMs: Long,
        targetPath: String,
        lyrics: LyricData?,
    ): Recipe? {
        if (currentDurationMs <= 0L || lyrics == null || lyrics.lines.isEmpty()) return null
        val lyricOffsetMs = lyrics.offset
        val meaningful = lyrics.lines.filter { line ->
            val effectiveStart = line.timeStamp + lyricOffsetMs
            TransitionLyricContent.isPerformed(line.text) && effectiveStart in 0 until currentDurationMs
        }.sortedBy { it.timeStamp }
        if (meaningful.size < MIN_MEANINGFUL_LYRIC_LINES) return null

        val last = meaningful.last()
        val lastStart = (last.timeStamp + lyricOffsetMs).coerceIn(0L, currentDurationMs)
        val remainingAfterLastStart = currentDurationMs - lastStart
        // A file ending near the middle may be incomplete. Its last line alone is not an outro.
        if (lastStart < (currentDurationMs * 0.55).toLong()) {
            return null
        }

        val wordEnd = last.words.maxOfOrNull { it.end + lyricOffsetMs }?.takeIf { it > lastStart }
        val explicitEnd = (last.endTime + lyricOffsetMs).takeIf {
            !last.endTimeIsEstimated && last.endTime > 0L && it > lastStart
        }
        val endingMarker = TransitionLyricContent.isEnding(last.text)
        val confirmedEnd = wordEnd ?: explicitEnd ?: if (endingMarker) lastStart else null
        val matchedWindow = confirmedEnd?.let { end -> AutoTransitionVocalWindow.select(
            meaningful.filterNot { TransitionLyricContent.isEnding(it.text) && it.words.isEmpty() }
                .map { it.timeStamp + lyricOffsetMs }, end,
        ) }
        val gentleStart = matchedWindow ?: if (endingMarker) lastStart else lastStart + 2000L
        if (gentleStart >= currentDurationMs - 1L) return null
        // An unconfirmed end no longer blocks low-gain overlap. Keep the final main window
        // for the main exchange; an independently measured tail may choose an earlier boundary.
        val lastEnd = when {
            endingMarker && matchedWindow == null && wordEnd == null && explicitEnd == null -> lastStart + 1000L
            confirmedEnd != null -> if (confirmedEnd >= currentDurationMs)
                max(gentleStart + 1L, currentDurationMs - AutoTransitionVocalWindow.MAIN_HANDOVER_MS)
                else confirmedEnd
            else -> max(gentleStart + 1000L, currentDurationMs - AutoTransitionVocalWindow.MAIN_HANDOVER_MS)
        }.coerceIn(gentleStart + 1L, currentDurationMs - 1L)
        val confidence = if (wordEnd != null) 1.0f else if (confirmedEnd != null) 0.82f else 0.5f
        if (remainingAfterLastStart > MAX_LYRIC_PREROLL_MS) {
            // Preserve the audio-envelope source for long outros while also carrying the
            // final-line gentle entry, so a loud tail no longer blocks all overlap.
            return Recipe(
                targetPath = targetPath,
                source = Source.ENVELOPE,
                triggerPositionMs = gentleStart,
                handoverPositionMs = lastEnd,
                handoverDurationMs = DEFAULT_HANDOVER_MS,
                confidence = confidence,
                gentleStartPositionMs = gentleStart,
                vocalEndKnown = confirmedEnd != null,
            )
        }
        val handoverStart = lastEnd
        val handoverMs = adaptiveHandoverMs(currentDurationMs - handoverStart)
        // Pre-open before the protected final-phrase overlap, bounded by the policy window.
        val trigger = max(gentleStart ?: lastStart, handoverStart - MAX_LYRIC_PREROLL_MS)
            .coerceAtLeast(0L)
        if (trigger >= currentDurationMs) return null

        return Recipe(
            targetPath = targetPath,
            source = Source.LYRICS,
            triggerPositionMs = trigger,
            handoverPositionMs = handoverStart,
            handoverDurationMs = handoverMs,
            confidence = confidence,
            gentleStartPositionMs = gentleStart,
            vocalEndKnown = confirmedEnd != null,
        )
    }

    internal fun isSequentialAlbumPair(current: AudioFile, next: AudioFile): Boolean {
        val sameAlbum = when {
            current.albumId >= 0L && next.albumId >= 0L -> current.albumId == next.albumId
            current.album.isNotBlank() && next.album.isNotBlank() ->
                current.album.equals(next.album, ignoreCase = true) &&
                    current.albumArtist.equals(next.albumArtist, ignoreCase = true)
            else -> false
        }
        if (!sameAlbum) return false
        if (current.discNumber > 0 && next.discNumber > 0 && current.discNumber != next.discNumber) return false
        return current.trackNumber > 0 && next.trackNumber == current.trackNumber + 1
    }

    internal fun adaptiveHandoverMs(availableMs: Long): Int = availableMs
        .coerceAtLeast(1L)
        .coerceAtMost(MAX_HANDOVER_MS.toLong())
        .toInt()

    internal fun pivotMs(handoverMs: Int): Int =
        (handoverMs.coerceAtLeast(1).toLong() * SMART_PIVOT_PERCENT / 100L)
            .coerceIn(1L, handoverMs.coerceAtLeast(1).toLong())
            .toInt()

    private fun isKnownShortTrack(track: AudioFile): Boolean =
        track.duration in 1 until MIN_TRACK_DURATION_MS
}

/** Start parameters consumed by [CrossfadeTransitionController]; preRoll protects final vocals at low overlap gain. */
internal data class AutoTransitionStartPlan(
    val preRollMs: Int,
    val handoverMs: Int,
    val pivotMs: Int,
    val source: AutoTransitionPolicy.Source,
)

/**
 * Audio-thread state for deciding when an automatic recipe becomes active.
 *
 * It deliberately has no coroutines/Flows. All calls happen from the renderer loop, so the meter
 * can remain allocation-light and deterministic.
 */
internal class AutoTransitionRuntime(private val tag: String) {
    private companion object {
        const val MIN_MAIN_FADE_MS = 6000L
    }
    @Volatile
    private var recipe: AutoTransitionPolicy.Recipe? = null
    @Volatile private var tailPlan: OutgoingTailPlan? = null
    private val envelope = AutoTransitionEnvelopeTracker()

    @Synchronized
    fun updateRecipe(value: AutoTransitionPolicy.Recipe?) {
        // Repeated queue/lyric publications must not erase the measured tail of the same plan.
        if (recipe == value) return
        recipe = value
        tailPlan = null
        envelope.reset()
    }

    fun currentRecipe(): AutoTransitionPolicy.Recipe? = recipe

    @Synchronized
    fun installTailPlan(expected: AutoTransitionPolicy.Recipe, plan: OutgoingTailPlan?) {
        if (recipe == expected) tailPlan = plan
    }

    fun observeCurrentPcm(
        positionMs: Long,
        buffer: ByteArray,
        length: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        bitsPerSample: Int,
    ) {
        if (recipe?.source != AutoTransitionPolicy.Source.ENVELOPE || length <= 0) return
        val db = PcmLevelMeter.rmsDb(
            buffer = buffer,
            length = length,
            outputIsFloat = outputIsFloat,
            outputIsPacked24 = outputIsPacked24,
            bitsPerSample = bitsPerSample,
        )
        envelope.observe(positionMs, db)
    }

    fun resolveStartPlan(positionMs: Long, durationMs: Long): AutoTransitionStartPlan? {
        val activeRecipe = recipe ?: return null
        val timeline = com.rawsmusic.core.common.utils.LyricPlaybackTimeline
        if (timeline.toLyricTime(durationMs) < AutoTransitionPolicy.MIN_TRACK_DURATION_MS || activeRecipe.targetPath.isBlank()) return null
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        if (remainingMs <= 0L) return null
        activeRecipe.gentleStartPositionMs?.let { sourceStart ->
            val start = timeline.toPlaybackTime(sourceStart)
            var vocalEnd = activeRecipe.handoverPositionMs?.let(timeline::toPlaybackTime) ?: return@let
            if (positionMs < start) return null
            if (!activeRecipe.vocalEndKnown) tailPlan?.let { measured ->
                if (measured.fadeStartMs >= start && measured.cutoffMs > measured.fadeStartMs) {
                    vocalEnd = minOf(vocalEnd, measured.fadeStartMs)
                }
            }
            val gentle = (vocalEnd - positionMs).coerceAtLeast(0L).toInt()
            // Late preparation gets a fresh adjustment window instead of inheriting a nearly
            // expired lyric/cue deadline that would drive both gains to their endpoints at once.
            val available = durationMs - max(positionMs, vocalEnd)
            if (available <= 0L) return null
            val handover = minOf(available,
                timeline.toPlaybackTime(AutoTransitionVocalWindow.MAIN_HANDOVER_MS))
                .coerceAtLeast(MIN_MAIN_FADE_MS).toInt()
            return AutoTransitionStartPlan(gentle, handover,
                AutoTransitionPolicy.pivotMs(handover), activeRecipe.source)
        }
        tailPlan?.let { tail ->
            if (positionMs < tail.fadeStartMs) return null
            // A detected low-energy boundary starts the ramp, but cannot compress the whole
            // handover to the analyzer's short 3–4 s cue (or to 250 ms after a late open).
            val handover = minOf(remainingMs,
                timeline.toPlaybackTime(AutoTransitionVocalWindow.MAIN_HANDOVER_MS))
                .coerceAtLeast(MIN_MAIN_FADE_MS).toInt()
            return AutoTransitionStartPlan(0, handover, AutoTransitionPolicy.pivotMs(handover), activeRecipe.source)
        }

        return when (activeRecipe.source) {
            AutoTransitionPolicy.Source.LYRICS -> {
                val handoverAt = activeRecipe.handoverPositionMs?.let(timeline::toPlaybackTime) ?: positionMs
                // Recipes without an eligible final-phrase window keep the formal semantic start.
                if (positionMs < handoverAt) return null
                val handoverMs = timeline.toPlaybackTime(
                    activeRecipe.handoverDurationMs.coerceAtMost(AutoTransitionPolicy.MAX_HANDOVER_MS).toLong())
                    .coerceIn(1L, remainingMs.coerceAtLeast(1L)).coerceAtLeast(MIN_MAIN_FADE_MS).toInt()
                AutoTransitionStartPlan(
                    preRollMs = 0,
                    handoverMs = handoverMs,
                    pivotMs = AutoTransitionPolicy.pivotMs(handoverMs),
                    source = activeRecipe.source,
                )
            }
            AutoTransitionPolicy.Source.ENVELOPE -> {
                val vocalEnd = activeRecipe.handoverPositionMs?.let(timeline::toPlaybackTime)
                if (vocalEnd != null && positionMs < vocalEnd) return null
                val inLookahead = remainingMs <= timeline.toPlaybackTime(activeRecipe.envelopeLookaheadMs)
                val quietTail = inLookahead && envelope.isQuietTail()
                val trustedOutro = vocalEnd != null && positionMs >= vocalEnd
                val fadeTail = (inLookahead || trustedOutro) && !quietTail && envelope.looksLikeFadeOut()
                val hardEndSafety = remainingMs <= timeline.toPlaybackTime(AutoTransitionPolicy.HARD_END_SAFETY_REMAINING_MS)
                if (!quietTail && !fadeTail && !hardEndSafety) return null

                // Preparation happens outside the mixer. Never consume the following song before
                // the real ramp because that permanently skips the same amount of its intro.
                val desiredHandover = timeline.toPlaybackTime(
                    AutoTransitionPolicy.adaptiveHandoverMs(
                        timeline.toLyricTime(remainingMs.coerceAtLeast(900L)),
                    ).toLong(),
                ).coerceIn(1L, remainingMs.coerceAtLeast(1L)).coerceAtLeast(MIN_MAIN_FADE_MS).toInt()
                AutoTransitionStartPlan(
                    preRollMs = 0,
                    handoverMs = desiredHandover,
                    pivotMs = AutoTransitionPolicy.pivotMs(desiredHandover),
                    source = activeRecipe.source,
                )
            }
        }
    }

    @Synchronized
    fun resetAfterTrackBoundary() {
        envelope.reset()
        recipe = null
        tailPlan = null
    }
}

private class AutoTransitionEnvelopeTracker {
    private data class Sample(val positionMs: Long, var db: Float, var observations: Int = 1)
    private val samples = ArrayDeque<Sample>()
    private var fadeLatchedUntilMs = Long.MIN_VALUE

    fun reset() {
        samples.clear()
        fadeLatchedUntilMs = Long.MIN_VALUE
    }

    fun observe(positionMs: Long, db: Float) {
        if (!db.isFinite()) return
        val level = db.coerceIn(-90f, 0f)
        val last = samples.lastOrNull()
        if (last != null && positionMs - last.positionMs < 80L) {
            last.observations++
            last.db += (level - last.db) / last.observations.toFloat()
        } else {
            samples.addLast(Sample(positionMs, level))
        }
        val cutoff = positionMs - 10_000L
        while (samples.firstOrNull()?.positionMs?.let { it < cutoff } == true) {
            samples.removeFirst()
        }
        while (samples.size > 160) samples.removeFirst()
    }

    fun isQuietTail(): Boolean {
        val latestPosition = samples.lastOrNull()?.positionMs ?: return false
        val recent = samples.filter { it.positionMs >= latestPosition - 700L }.map { it.db }
        return recent.size >= 4 && recent.median() <= -38f
    }

    fun looksLikeFadeOut(): Boolean {
        if (samples.size < 12) return false
        val list = samples.toList()
        val latestPosition = list.last().positionMs
        if (latestPosition <= fadeLatchedUntilMs) return true
        val span = latestPosition - list.first().positionMs
        if (span < 1_800L) return false

        val recent = list.filter { it.positionMs >= latestPosition - 700L }.map { it.db }
        val baseline = list.filter {
            it.positionMs in (latestPosition - 8_000L)..(latestPosition - 1_500L)
        }.map { it.db }
        if (recent.size < 4 || baseline.size < 5) return false

        val recentMedian = recent.median()
        val baselineUpper = baseline.percentile(0.7f)
        val suddenDrop = recentMedian <= -20f && baselineUpper - recentMedian >= 8f

        // Compare robust time slices rather than consecutive samples. One chorus hit or transient
        // may rise again without invalidating an otherwise clear long outro descent.
        val sectionSize = (list.size / 4).coerceAtLeast(2)
        val sections = (0 until 4).map { section ->
            val from = section * sectionSize
            val to = if (section == 3) list.size else minOf(list.size, from + sectionSize)
            list.subList(from.coerceAtMost(list.size), to).map { it.db }.median()
        }
        val netDrop = sections.first() - sections.last()
        val risingSteps = sections.zipWithNext().count { (before, after) -> after > before + 2.5f }
        val continuingDrop = sections.zipWithNext().count { (before, after) -> after <= before - 0.75f } >= 3
        val gradualDrop = netDrop >= 6f && risingSteps <= 1 && continuingDrop
        val detected = suddenDrop || gradualDrop
        if (detected) fadeLatchedUntilMs = latestPosition + 1_600L
        return detected
    }

    private fun List<Float>.median(): Float = percentile(0.5f)

    private fun List<Float>.percentile(fraction: Float): Float {
        if (isEmpty()) return -90f
        val sorted = sorted()
        val index = ((sorted.lastIndex) * fraction.coerceIn(0f, 1f)).toInt()
        return sorted[index]
    }
}

internal object PcmLevelMeter {
    private const val MIN_DB = -90f

    fun rmsDb(
        buffer: ByteArray,
        length: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        bitsPerSample: Int,
        startOffset: Int = 0,
    ): Float {
        val bytesPerSample = when {
            outputIsFloat -> 4
            outputIsPacked24 -> 3
            bitsPerSample > 16 -> 4
            else -> 2
        }
        val baseOffset = startOffset.coerceIn(0, buffer.size)
        val available = minOf(length.coerceAtLeast(0), buffer.size - baseOffset)
        if (available < bytesPerSample) return MIN_DB
        val sampleCount = available / bytesPerSample
        val stride = max(1, sampleCount / 2048)
        var sum = 0.0
        var count = 0
        var index = 0
        while (index < sampleCount) {
            val offset = baseOffset + index * bytesPerSample
            val normalized = when {
                outputIsFloat -> java.lang.Float.intBitsToFloat(readIntLE(buffer, offset)).toDouble()
                outputIsPacked24 -> readS24LE(buffer, offset).toDouble() / 8_388_608.0
                bitsPerSample > 16 -> readIntLE(buffer, offset).toDouble() / 2_147_483_648.0
                else -> readS16LE(buffer, offset).toDouble() / 32_768.0
            }
            if (normalized.isFinite()) {
                sum += normalized * normalized
                count++
            }
            index += stride
        }
        if (count <= 0) return MIN_DB
        val rms = kotlin.math.sqrt(sum / count.toDouble()).coerceAtLeast(0.0000316227766)
        return (20.0 * log10(rms)).toFloat().coerceIn(MIN_DB, 0f)
    }

    private fun readS16LE(buf: ByteArray, offset: Int): Int {
        val value = (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)
        return if ((value and 0x8000) != 0) value or -0x10000 else value
    }

    private fun readS24LE(buf: ByteArray, offset: Int): Int {
        var value = (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16)
        if ((value and 0x00800000) != 0) value = value or -0x01000000
        return value
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            (buf[offset + 3].toInt() shl 24)
}
