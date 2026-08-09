package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
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
    const val HARD_END_SAFETY_REMAINING_MS = 1_500L
    /** Once a real handover starts, the old track gets an eight-second fade window. */
    const val LEAD_FADE_WINDOW_MS = 8_000
    /** The follow track reaches unity one second before the old decoder is physically retired. */
    const val POST_DOMINANCE_TAIL_MS = 1_000
    /** Start the formal fade only when the final lyric is essentially finished. */
    const val FINAL_LYRIC_END_APPROACH_MS = 500L
    /** Incoming track always begins extremely low, then rises linearly in dB. */
    const val INCOMING_START_DB = -50f
    /** While the lead remains untouched, keep the follow underlay no louder than this. */
    const val INCOMING_UNDERLAY_CEILING_DB = -36f
    /** The old track is quiet but still continuous at the dominance point; its final second finishes the tail. */
    const val LEAD_DB_AT_DOMINANCE = -36f
    const val LEAD_SILENCE_DB = -90f
    // Kept in Recipe/StartPlan for source compatibility with the current policy surface. The
    // controller no longer derives an audible floor from source RMS; INCOMING_START_DB owns it.
    const val DEFAULT_INCOMING_FLOOR_DB = INCOMING_START_DB
    const val MIN_INCOMING_FLOOR_DB = -60f
    const val MAX_INCOMING_FLOOR_DB = INCOMING_START_DB
    const val INCOMING_SAFETY_MARGIN_DB = 15f
    const val DEFAULT_HANDOVER_MS = LEAD_FADE_WINDOW_MS

    enum class Source {
        LYRICS,
        ENVELOPE
    }

    data class Recipe(
        val targetPath: String,
        val source: Source,
        /** Absolute current-track position where underlay is allowed to start. */
        val triggerPositionMs: Long?,
        /** Absolute current-track position where the formal handover should start. */
        val handoverPositionMs: Long?,
        val handoverDurationMs: Int,
        val envelopeLookaheadMs: Long = ENVELOPE_LOOKAHEAD_MS,
        val defaultIncomingFloorDb: Float = DEFAULT_INCOMING_FLOOR_DB,
        val minIncomingFloorDb: Float = MIN_INCOMING_FLOOR_DB,
        val maxIncomingFloorDb: Float = MAX_INCOMING_FLOOR_DB,
        val incomingSafetyMarginDb: Float = INCOMING_SAFETY_MARGIN_DB,
        val confidence: Float,
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
    ): Decision {
        if (current == null || next == null || next.path.isBlank()) {
            return Decision(null, forceGapless = false, reason = "missing_track")
        }
        if (
            current.path == next.path &&
            current.cueTrackIndex == next.cueTrackIndex &&
            current.cueOffsetMs == next.cueOffsetMs
        ) {
            return Decision(null, forceGapless = true, reason = "same_item_repeat_gapless")
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
            return Decision(lyricRecipe, forceGapless = false, reason = "lyrics")
        }

        return Decision(
            recipe = Recipe(
                targetPath = next.path,
                source = Source.ENVELOPE,
                triggerPositionMs = null,
                handoverPositionMs = null,
                handoverDurationMs = DEFAULT_HANDOVER_MS,
                confidence = 0.45f,
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
            line.text.isNotBlank() && effectiveStart in 0 until currentDurationMs
        }
        if (meaningful.size < MIN_MEANINGFUL_LYRIC_LINES) return null

        val last = meaningful.last()
        val lastStart = (last.timeStamp + lyricOffsetMs).coerceIn(0L, currentDurationMs)
        val remainingAfterLastStart = currentDurationMs - lastStart
        // A lyric file whose last actual line occurs around the middle of the track usually belongs
        // to a long instrumental outro or is incomplete. Do not let that semantic marker start the
        // next song tens of seconds early; fall back to the live envelope meter instead.
        if (lastStart < (currentDurationMs * 0.55).toLong() || remainingAfterLastStart > 30_000L) {
            return null
        }

        val wordEnd = last.words.maxOfOrNull { it.end + lyricOffsetMs }?.takeIf { it > lastStart }
        val explicitEnd = (last.endTime + lyricOffsetMs).takeIf { last.endTime > 0L && it > lastStart }
        val estimatedEnd = minOf(currentDurationMs, lastStart + 4_500L)
        val lastEnd = (wordEnd ?: explicitEnd ?: estimatedEnd).coerceIn(lastStart + 1L, currentDurationMs)

        // The 30 s threshold is analysis/preparation headroom only. Do not derive the lead fade by
        // subtracting eight seconds from a later retirement point: that made the outgoing song duck
        // while the final lyric was still being sung. The formal handover now begins only as the
        // final timed lyric approaches its end. Word timing is preferred, then explicit line end,
        // with the estimated end as the conservative fallback.
        val handoverStart = (lastEnd - FINAL_LYRIC_END_APPROACH_MS)
            .coerceIn(lastStart, currentDurationMs - 1L)
        val plannedRetirePosition = (handoverStart + LEAD_FADE_WINDOW_MS)
            .coerceAtMost(currentDurationMs)
            .coerceAtLeast(handoverStart + 1L)
        val handoverMs = (plannedRetirePosition - handoverStart)
            .coerceAtLeast(1L)
            .toInt()
        // Arm/pre-open from the last meaningful line (bounded by the 30 s policy window), but the
        // mixer keeps the lead at unity and the follow pinned at -50 dB until handoverStart.
        val trigger = max(lastStart, handoverStart - MAX_LYRIC_PREROLL_MS)
            .coerceAtLeast(0L)
        if (trigger >= currentDurationMs) return null

        val confidence = when {
            wordEnd != null -> 1.0f
            explicitEnd != null -> 0.82f
            else -> 0.68f
        }
        return Recipe(
            targetPath = targetPath,
            source = Source.LYRICS,
            triggerPositionMs = trigger,
            handoverPositionMs = handoverStart,
            handoverDurationMs = handoverMs,
            confidence = confidence,
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
}

/** Start parameters consumed by [CrossfadeTransitionController]. */
internal data class AutoTransitionStartPlan(
    val preRollMs: Int,
    val handoverMs: Int,
    val defaultIncomingFloorDb: Float,
    val minIncomingFloorDb: Float,
    val maxIncomingFloorDb: Float,
    val incomingSafetyMarginDb: Float,
    val source: AutoTransitionPolicy.Source,
)

/**
 * Audio-thread state for deciding when an automatic recipe becomes active.
 *
 * It deliberately has no coroutines/Flows. All calls happen from the renderer loop, so the meter
 * can remain allocation-light and deterministic.
 */
internal class AutoTransitionRuntime(private val tag: String) {
    @Volatile
    private var recipe: AutoTransitionPolicy.Recipe? = null
    private val envelope = AutoTransitionEnvelopeTracker()

    fun updateRecipe(value: AutoTransitionPolicy.Recipe?) {
        recipe = value
        envelope.reset()
    }

    fun currentRecipe(): AutoTransitionPolicy.Recipe? = recipe

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
        if (durationMs <= 0L || activeRecipe.targetPath.isBlank()) return null
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        if (remainingMs <= 0L) return null

        return when (activeRecipe.source) {
            AutoTransitionPolicy.Source.LYRICS -> {
                val trigger = activeRecipe.triggerPositionMs ?: return null
                if (positionMs < trigger) return null
                val handoverAt = activeRecipe.handoverPositionMs ?: positionMs
                val preRoll = (handoverAt - positionMs)
                    .coerceIn(0L, AutoTransitionPolicy.MAX_LYRIC_PREROLL_MS)
                    .toInt()
                AutoTransitionStartPlan(
                    preRollMs = preRoll,
                    handoverMs = activeRecipe.handoverDurationMs.coerceAtLeast(900),
                    defaultIncomingFloorDb = activeRecipe.defaultIncomingFloorDb,
                    minIncomingFloorDb = activeRecipe.minIncomingFloorDb,
                    maxIncomingFloorDb = activeRecipe.maxIncomingFloorDb,
                    incomingSafetyMarginDb = activeRecipe.incomingSafetyMarginDb,
                    source = activeRecipe.source,
                )
            }
            AutoTransitionPolicy.Source.ENVELOPE -> {
                val inLookahead = remainingMs <= activeRecipe.envelopeLookaheadMs
                val quietTail = inLookahead && envelope.isQuietTail()
                val fadeTail = inLookahead && !quietTail && envelope.looksLikeFadeOut()
                val hardEndSafety = remainingMs <= AutoTransitionPolicy.HARD_END_SAFETY_REMAINING_MS
                if (!quietTail && !fadeTail && !hardEndSafety) return null

                // A quiet tail is already musically expendable: start the eight-second takeover
                // immediately and physically retire the old decoder after it. For a normal fade-out,
                // allow a short low-level underlay first when enough file remains, but still reserve
                // a full eight-second lead fade. Hard endings use whatever time is left.
                val desiredHandover = minOf(
                    AutoTransitionPolicy.LEAD_FADE_WINDOW_MS.toLong(),
                    remainingMs.coerceAtLeast(900L),
                ).toInt()
                val preRoll: Int
                val handover: Int
                when {
                    quietTail -> {
                        preRoll = 0
                        handover = desiredHandover
                    }
                    fadeTail -> {
                        preRoll = (remainingMs - desiredHandover)
                            .coerceIn(0L, AutoTransitionPolicy.LEAD_FADE_WINDOW_MS.toLong())
                            .toInt()
                        handover = desiredHandover
                    }
                    else -> {
                        preRoll = 0
                        handover = desiredHandover
                    }
                }
                AutoTransitionStartPlan(
                    preRollMs = preRoll,
                    handoverMs = handover,
                    defaultIncomingFloorDb = activeRecipe.defaultIncomingFloorDb,
                    minIncomingFloorDb = activeRecipe.minIncomingFloorDb,
                    maxIncomingFloorDb = activeRecipe.maxIncomingFloorDb,
                    incomingSafetyMarginDb = activeRecipe.incomingSafetyMarginDb,
                    source = activeRecipe.source,
                )
            }
        }
    }

    fun resetAfterTrackBoundary() {
        envelope.reset()
        recipe = null
    }
}

private class AutoTransitionEnvelopeTracker {
    private data class Sample(val positionMs: Long, val db: Float)
    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    fun observe(positionMs: Long, db: Float) {
        if (!db.isFinite()) return
        samples.addLast(Sample(positionMs, db.coerceIn(-90f, 0f)))
        val cutoff = positionMs - 3_000L
        while (samples.firstOrNull()?.positionMs?.let { it < cutoff } == true) {
            samples.removeFirst()
        }
        while (samples.size > 90) samples.removeFirst()
    }

    fun isQuietTail(): Boolean {
        val recent = samples.takeLastCompat(6)
        return recent.size >= 3 && recent.map { it.db }.average() <= -38.0
    }

    fun looksLikeFadeOut(): Boolean {
        if (samples.size < 8) return false
        val list = samples.toList()
        val third = (list.size / 3).coerceAtLeast(2)
        val early = list.take(third).map { it.db }.average()
        val late = list.takeLast(third).map { it.db }.average()
        val drop = early - late
        val latest = list.last().db
        return late <= -18.0 && latest <= -16f && drop >= 5.0
    }

    private fun <T> ArrayDeque<T>.takeLastCompat(count: Int): List<T> {
        if (isEmpty()) return emptyList()
        val n = count.coerceAtMost(size)
        return toList().takeLast(n)
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
    ): Float {
        val bytesPerSample = when {
            outputIsFloat -> 4
            outputIsPacked24 -> 3
            bitsPerSample > 16 -> 4
            else -> 2
        }
        if (length < bytesPerSample) return MIN_DB
        val sampleCount = length / bytesPerSample
        val stride = max(1, sampleCount / 2048)
        var sum = 0.0
        var count = 0
        var index = 0
        while (index < sampleCount) {
            val offset = index * bytesPerSample
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

    data class IncomingUnderlayGains(
        val floorGain: Float,
        val bedGain: Float,
        val floorTargetDb: Float,
        val bedTargetDb: Float,
    )

    /**
     * Resolve audible incoming levels from target output loudness.
     *
     * The previous implementation clamped the *gain* itself to -27..-18 dB. A quiet intro at
     * -30 dBFS would therefore be attenuated to roughly -48 dBFS and become effectively inaudible.
     * Here the policy's low-dB range describes the desired output layer instead. We only attenuate as much as the
     * incoming material actually needs, never amplify above unity, and keep it subordinate to the
     * outgoing tail.
     */
    fun resolveIncomingUnderlayGains(
        outgoingDb: Float,
        incomingDb: Float,
        defaultFloorDb: Float,
        minFloorDb: Float,
        maxFloorDb: Float,
        safetyMarginDb: Float,
    ): IncomingUnderlayGains {
        val safeOutgoing = outgoingDb.takeIf { it.isFinite() } ?: -10f
        val safeIncoming = incomingDb.takeIf { it.isFinite() } ?: -18f
        val floorTarget = (safeOutgoing - safetyMarginDb)
            .coerceIn(minFloorDb, maxFloorDb)
            .takeIf { it.isFinite() }
            ?: defaultFloorDb
        // The underlay is ambience, not an equal-power crossfade yet. Keep the follow track
        // clearly behind the lead until the formal handover starts. This also follows the
        // independent lead/follow automation: the lead does not get ducked merely because the
        // follow renderer has become audible.
        val bedSafetyDb = max(10f, safetyMarginDb - 4f)
        val bedTarget = (safeOutgoing - bedSafetyDb)
            .coerceIn(floorTarget, -18f)
        fun gainForTarget(targetDb: Float): Float {
            val attenuationDb = (targetDb - safeIncoming).coerceIn(-30f, 0f)
            return 10.0.pow(attenuationDb / 20.0).toFloat().coerceIn(0f, 1f)
        }
        return IncomingUnderlayGains(
            floorGain = gainForTarget(floorTarget),
            bedGain = gainForTarget(bedTarget),
            floorTargetDb = floorTarget,
            bedTargetDb = bedTarget,
        )
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
