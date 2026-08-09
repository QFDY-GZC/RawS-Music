package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * Owns the small, mutable state needed while an AudioTrack crossfade is active.
 *
 * The main playback loop still decides when to switch decoder ownership. This
 * helper only tracks crossfade progress, decodes the next chunk, converts it to
 * the current output container when needed, and mixes it into the current buffer.
 */
internal class CrossfadeTransitionController(
    private val tag: String,
    private val convertS32ToS16: (ByteArray, Int, ByteArray, Int) -> Int =
        { source, length, destination, _ ->
            PcmSampleConverter.s32ToS16Pcm(source, length, destination)
        },
    private val convertS32ToS24: (ByteArray, Int, ByteArray, Int) -> Int =
        { source, length, destination, _ ->
            PcmSampleConverter.s32ToS24PackedPcm(source, length, destination)
        }
) {
    data class MixResult(
        val nextRead: Int,
        val mixedBytes: Int,
        val progressBeforeMix: Float,
        val completed: Boolean
    )

    @Volatile
    var active: Boolean = false
        private set

    private var totalFrames: Long = 0L
    private var bytesMixed: Long = 0L
    private var autoPlan: AutoTransitionStartPlan? = null
    private var autoSampleRate: Int = 0
    private var quietTailFrames: Long = 0L
    private var acceleratedStartFrame: Long = -1L
    private var acceleratedDurationFrames: Long = 0L
    private var acceleratedStartOut: Float = 1f
    private var acceleratedStartIn: Float = 0f
    private var lastAutoOut: Float = 1f
    private var lastAutoIn: Float = 0f
    @Volatile
    private var activeTargetPath: String? = null
    private var nextReadBuffer = ByteArray(0)
    private var nextMixBuffer = ByteArray(0)

    val mixedBytesSoFar: Long
        get() = bytesMixed

    val targetPath: String?
        get() = activeTargetPath

    fun reset(reason: String) {
        if (active || bytesMixed != 0L || totalFrames != 0L) {
            AppLogger.d(tag, "Crossfade: reset reason=$reason bytesMixed=$bytesMixed totalFrames=$totalFrames")
        }
        active = false
        totalFrames = 0L
        bytesMixed = 0L
        autoPlan = null
        autoSampleRate = 0
        quietTailFrames = 0L
        acceleratedStartFrame = -1L
        acceleratedDurationFrames = 0L
        acceleratedStartOut = 1f
        acceleratedStartIn = 0f
        lastAutoOut = 1f
        lastAutoIn = 0f
        activeTargetPath = null
    }

    fun start(targetPath: String, durationMs: Int, sampleRate: Int, bufferSize: Int, remainingMs: Long): Boolean {
        if (durationMs <= 0 || sampleRate <= 0) return false
        totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        bytesMixed = 0L
        autoPlan = null
        autoSampleRate = 0
        quietTailFrames = 0L
        acceleratedStartFrame = -1L
        acceleratedDurationFrames = 0L
        lastAutoOut = 1f
        lastAutoIn = 0f
        ensureBuffers(bufferSize)
        activeTargetPath = targetPath
        active = true
        AppLogger.d(tag, "Crossfade: START target=$targetPath remaining=${remainingMs}ms totalFrames=$totalFrames")
        return true
    }


    fun startAuto(
        targetPath: String,
        plan: AutoTransitionStartPlan,
        sampleRate: Int,
        bufferSize: Int,
        remainingMs: Long,
    ): Boolean {
        if (sampleRate <= 0 || plan.handoverMs <= 0) return false
        val preRollFrames = plan.preRollMs.toLong() * sampleRate.toLong() / 1000L
        val handoverFrames = plan.handoverMs.toLong() * sampleRate.toLong() / 1000L
        // Incoming audio starts directly at -50 dB. There is no 0 -> floor entry stage.
        totalFrames = (preRollFrames.coerceAtLeast(0L) + handoverFrames.coerceAtLeast(1L))
            .coerceAtLeast(1L)
        bytesMixed = 0L
        autoPlan = plan
        autoSampleRate = sampleRate
        quietTailFrames = 0L
        acceleratedStartFrame = -1L
        acceleratedDurationFrames = 0L
        acceleratedStartOut = 1f
        acceleratedStartIn = 0f
        lastAutoOut = 1f
        lastAutoIn = 0f
        ensureBuffers(bufferSize)
        activeTargetPath = targetPath
        active = true
        AppLogger.i(
            tag,
            "AutoCrossfade: START target=$targetPath source=${plan.source} remaining=${remainingMs}ms " +
                "preRoll=${plan.preRollMs}ms handover=${plan.handoverMs}ms " +
                "leadFadeWindow=${AutoTransitionPolicy.LEAD_FADE_WINDOW_MS}ms totalFrames=$totalFrames"
        )
        return true
    }

    /** True only for the policy-driven automatic mixer, not an explicit manual crossfade. */
    fun isAutomaticActive(): Boolean = active && autoPlan != null

    fun elapsedMs(bytesPerMs: Double): Long {
        return if (bytesPerMs > 0.0) (bytesMixed.toDouble() / bytesPerMs).toLong() else 0L
    }

    fun mixNextIntoCurrent(
        currentBuf: ByteArray,
        currentRead: Int,
        next: GaplessNextDecoder.Prepared,
        frameSize: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        bitsPerSample: Int
    ): MixResult {
        if (!active || currentRead <= 0) {
            return MixResult(nextRead = 0, mixedBytes = 0, progressBeforeMix = 0f, completed = false)
        }
        val alignedCurrent = PcmFrameAligner.alignDown(currentRead, frameSize)
        val outputBytesPerSample = when {
            outputIsFloat -> 4
            outputIsPacked24 -> 3
            bitsPerSample <= 16 -> 2
            else -> 4
        }
        val channels = (frameSize / outputBytesPerSample).coerceAtLeast(1)
        val currentFrames = (alignedCurrent / frameSize).coerceAtLeast(0)
        // FFmpegBridge outputs 24/32-bit PCM as S32LE.  When the Android output
        // container is packed24/S16, the next decoder therefore needs more source
        // bytes than the current output buffer length.  Under-reading here causes
        // partial-frame mixes and Direct-mode motor-like noise.
        val nextBytesPerSample = if (next.bitsPerSample <= 16) 2 else 4
        val desiredNextRead = (currentFrames * channels * nextBytesPerSample).coerceAtLeast(0)
        ensureBuffers(maxOf(alignedCurrent, desiredNextRead).coerceAtLeast(frameSize).coerceAtMost(currentBuf.size * 2))

        val decodeLimit = desiredNextRead.coerceAtMost(nextReadBuffer.size)
        val nextRead = try {
            FFmpegBridge.decodeChunk(next.handle, nextReadBuffer, 0, decodeLimit)
        } catch (t: Throwable) {
            AppLogger.e(tag, "Crossfade: next decoder read failed path=${next.path}", t)
            -1
        }
        if (nextRead <= 0) {
            AppLogger.w(tag, "Crossfade: next decoder returned $nextRead path=${next.path}; continuing current buffer without mix")
            return MixResult(
                nextRead = nextRead,
                mixedBytes = 0,
                progressBeforeMix = progress(frameSize),
                completed = false
            )
        }

        val totalBytes = totalBytes(frameSize)
        val progressBefore = if (totalBytes > 0L) {
            (bytesMixed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        var mixNextLen = nextRead
        val mixNextBuffer = when {
            outputIsFloat && next.bitsPerSample > 16 -> {
                mixNextLen = PcmSampleConverter.s32ToFloatPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            outputIsFloat && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToFloatPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            outputIsPacked24 && next.bitsPerSample > 16 -> {
                mixNextLen = convertS32ToS24(nextReadBuffer, nextRead, nextMixBuffer, next.bitsPerSample)
                nextMixBuffer
            }
            outputIsPacked24 && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToS24PackedPcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            bitsPerSample <= 16 && next.bitsPerSample > 16 -> {
                mixNextLen = convertS32ToS16(nextReadBuffer, nextRead, nextMixBuffer, next.bitsPerSample)
                nextMixBuffer
            }
            bitsPerSample > 16 && next.bitsPerSample <= 16 -> {
                mixNextLen = PcmSampleConverter.s16ToS32Pcm(nextReadBuffer, nextRead, nextMixBuffer)
                nextMixBuffer
            }
            else -> nextReadBuffer
        }

        val alignedNext = PcmFrameAligner.alignDown(mixNextLen, frameSize)
        val mixLen = minOf(alignedCurrent, alignedNext)
        val gains = if (autoPlan != null && mixLen > 0) {
            resolveAutoGains(
                currentBuf = currentBuf,
                nextBuf = mixNextBuffer,
                mixLen = mixLen,
                frameSize = frameSize,
                outputIsFloat = outputIsFloat,
                outputIsPacked24 = outputIsPacked24,
                bitsPerSample = bitsPerSample,
            )
        } else {
            PcmCrossfadeMixer.gainOut(progressBefore) to PcmCrossfadeMixer.gainIn(progressBefore)
        }
        val gainOut = gains.first
        val gainIn = gains.second
        if (mixLen > 0) {
            PcmCrossfadeMixer.mixInPlace(
                currentBuf = currentBuf,
                currentLen = mixLen,
                nextBuf = mixNextBuffer,
                nextLen = mixLen,
                gainOut = gainOut,
                gainIn = gainIn,
                outputIsFloat = outputIsFloat,
                bitsPerSample = bitsPerSample,
                outputIsPacked24 = outputIsPacked24
            )
        }
        bytesMixed += alignedCurrent.coerceAtLeast(0)
        return MixResult(
            nextRead = nextRead,
            mixedBytes = mixLen,
            progressBeforeMix = progressBefore,
            // Automatic transitions intentionally keep the nearly-silent old tail alive for the
            // final second after the follow has reached unity. Do not commit at the old 5%/95%
            // dominance threshold; physical decoder retirement happens at the timeline end.
            completed = isComplete(frameSize)
        )
    }

    private fun resolveAutoGains(
        currentBuf: ByteArray,
        nextBuf: ByteArray,
        mixLen: Int,
        frameSize: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
        bitsPerSample: Int,
    ): Pair<Float, Float> {
        autoPlan ?: return 1f to 0f
        val outgoingDb = PcmLevelMeter.rmsDb(
            currentBuf, mixLen, outputIsFloat, outputIsPacked24, bitsPerSample
        )
        val frameProgress = if (frameSize > 0) bytesMixed / frameSize.toLong() else 0L
        val bufferFrames = if (frameSize > 0) (mixLen / frameSize).toLong().coerceAtLeast(1L) else 1L
        val sampleRate = autoSampleRate.coerceAtLeast(1)

        fun gainsForTimeline(
            localFrame: Long,
            durationFrames: Long,
            startOutGain: Float,
            startInGain: Float,
        ): Pair<Float, Float> {
            val duration = durationFrames.coerceAtLeast(1L)
            val fadeWindowFrames = minOf(
                duration,
                sampleRate.toLong() * AutoTransitionPolicy.LEAD_FADE_WINDOW_MS / 1000L,
            ).coerceAtLeast(1L)
            val tailFrames = minOf(
                duration,
                sampleRate.toLong() * AutoTransitionPolicy.POST_DOMINANCE_TAIL_MS / 1000L,
            ).coerceAtLeast(1L)
            val fadeStart = (duration - fadeWindowFrames).coerceAtLeast(0L)
            val dominance = (duration - tailFrames).coerceAtLeast(fadeStart)
            val local = localFrame.coerceIn(0L, duration)

            // Follow: begin at -50 dB and rise linearly in dB. If a quiet-tail retime happens
            // after the follow is already above -50 dB, continue from its current level instead of
            // restarting or jumping. It reaches unity one second before physical retirement.
            val startInDb = gainToDb(startInGain.coerceAtLeast(dbToGain(AutoTransitionPolicy.INCOMING_START_DB)))
                .coerceIn(AutoTransitionPolicy.INCOMING_START_DB, 0f)
            val incomingDb = when {
                local >= dominance -> 0f
                local < fadeStart && fadeStart > 0L -> {
                    // Pre-roll is preparation, not an audible early crossfade. Keep the incoming
                    // renderer pinned at its -50 dB floor until the final lyric is essentially at
                    // end; the actual linear dB fade-in starts together with the eight-second
                    // handover window.
                    startInDb
                }
                else -> {
                    val fadeSpan = (dominance - fadeStart).coerceAtLeast(1L)
                    val p = ((local - fadeStart).coerceAtLeast(0L).toDouble() / fadeSpan.toDouble())
                        .coerceIn(0.0, 1.0).toFloat()
                    val windowStartDb = if (fadeStart > 0L) {
                        maxOf(startInDb, AutoTransitionPolicy.INCOMING_UNDERLAY_CEILING_DB)
                    } else startInDb
                    lerp(windowStartDb, 0f, p)
                }
            }
            val incoming = if (local >= dominance) 1f else dbToGain(incomingDb)

            // Lead: stay untouched throughout the long underlay. During the final eight-second
            // window fade in the linear-amplitude domain with a raised-cosine envelope. This keeps
            // the first half of the fade gentle instead of dropping tens of dB too early. At the
            // dominance point the old track is around -36 dB (quiet but continuous); the final one
            // second then completes another raised-cosine tail toward digital near-silence while the
            // follow remains at unity.
            val startOut = startOutGain.coerceIn(0f, 1f)
            val dominanceFloor = dbToGain(AutoTransitionPolicy.LEAD_DB_AT_DOMINANCE)
            val dominanceGain = minOf(startOut, dominanceFloor)
            val tailFloor = dbToGain(AutoTransitionPolicy.LEAD_SILENCE_DB)
            val outgoing = when {
                local < fadeStart -> startOut
                local < dominance -> {
                    val denom = (dominance - fadeStart).coerceAtLeast(1L)
                    val p = ((local - fadeStart).toDouble() / denom.toDouble()).coerceIn(0.0, 1.0).toFloat()
                    val remaining = raisedCosineRemaining(p)
                    dominanceGain + (startOut - dominanceGain) * remaining
                }
                else -> {
                    val denom = (duration - dominance).coerceAtLeast(1L)
                    val p = ((local - dominance).toDouble() / denom.toDouble()).coerceIn(0.0, 1.0).toFloat()
                    val remaining = raisedCosineRemaining(p)
                    tailFloor + (dominanceGain - tailFloor) * remaining
                }
            }
            return outgoing.coerceIn(0f, 1f) to incoming.coerceIn(0f, 1f)
        }

        // Envelope fallback may retime a genuinely empty tail. A lyrics-authored recipe is never
        // accelerated here: its handover anchor is the final lyric end and must not be pulled
        // forward by a quiet phrase/gap before that semantic endpoint.
        if (
            acceleratedStartFrame < 0L &&
            autoSampleRate > 0 &&
            autoPlan?.source == AutoTransitionPolicy.Source.ENVELOPE
        ) {
            quietTailFrames = when {
                outgoingDb <= -52f -> quietTailFrames + bufferFrames * 5L
                outgoingDb <= -48f -> quietTailFrames + bufferFrames * 3L
                outgoingDb <= -40f -> quietTailFrames + bufferFrames
                outgoingDb >= -33f -> 0L
                else -> (quietTailFrames - bufferFrames / 3L).coerceAtLeast(0L)
            }
            val quietNeededFrames = sampleRate.toLong() * 340L / 1000L
            val desiredRetimeFrames = sampleRate.toLong() * AutoTransitionPolicy.LEAD_FADE_WINDOW_MS / 1000L
            val scheduledFramesLeft = (totalFrames - frameProgress).coerceAtLeast(0L)
            if (
                quietTailFrames >= quietNeededFrames &&
                scheduledFramesLeft > desiredRetimeFrames + bufferFrames
            ) {
                acceleratedStartFrame = frameProgress
                acceleratedDurationFrames = desiredRetimeFrames.coerceAtLeast(1L)
                acceleratedStartOut = lastAutoOut.coerceIn(0f, 1f)
                acceleratedStartIn = lastAutoIn
                    .coerceAtLeast(dbToGain(AutoTransitionPolicy.INCOMING_START_DB))
                    .coerceIn(0f, 1f)
                totalFrames = frameProgress + acceleratedDurationFrames
                AppLogger.i(
                    tag,
                    "AutoCrossfade: quiet-tail retime outgoing=${"%.1f".format(outgoingDb)}dB " +
                        "remainingFrames=$scheduledFramesLeft -> ${acceleratedDurationFrames} " +
                        "lead=${"%.4f".format(acceleratedStartOut)} follow=${"%.4f".format(acceleratedStartIn)}"
                )
            }
        }

        val gains = if (acceleratedStartFrame >= 0L) {
            gainsForTimeline(
                localFrame = (frameProgress - acceleratedStartFrame).coerceAtLeast(0L),
                durationFrames = acceleratedDurationFrames.coerceAtLeast(1L),
                startOutGain = acceleratedStartOut,
                startInGain = acceleratedStartIn,
            )
        } else {
            gainsForTimeline(
                localFrame = frameProgress,
                durationFrames = totalFrames.coerceAtLeast(1L),
                startOutGain = 1f,
                startInGain = dbToGain(AutoTransitionPolicy.INCOMING_START_DB),
            )
        }
        lastAutoOut = gains.first
        lastAutoIn = gains.second
        return gains
    }

    private fun smoothstep(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /** 1 -> 0 with zero slope at both ends; used for the old-track amplitude envelope. */
    private fun raisedCosineRemaining(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return (0.5 * (1.0 + cos(Math.PI * p.toDouble()))).toFloat()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)

    private fun gainToDb(gain: Float): Float {
        val safe = gain.coerceAtLeast(dbToGain(AutoTransitionPolicy.LEAD_SILENCE_DB))
        return (20.0 * ln(safe.toDouble()) / ln(10.0)).toFloat()
    }

    private fun dbToGain(db: Float): Float = 10.0.pow(db.toDouble() / 20.0).toFloat()

    private fun ensureBuffers(size: Int) {
        val safeSize = size.coerceAtLeast(1)
        if (nextReadBuffer.size < safeSize) nextReadBuffer = ByteArray(safeSize)
        if (nextMixBuffer.size < safeSize) nextMixBuffer = ByteArray(safeSize)
    }

    private fun totalBytes(frameSize: Int): Long {
        return if (frameSize > 0) totalFrames * frameSize.toLong() else 0L
    }

    private fun progress(frameSize: Int): Float {
        val total = totalBytes(frameSize)
        return if (total > 0L) (bytesMixed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    }

    private fun isComplete(frameSize: Int): Boolean {
        val total = totalBytes(frameSize)
        return total > 0L && bytesMixed >= total
    }
}
