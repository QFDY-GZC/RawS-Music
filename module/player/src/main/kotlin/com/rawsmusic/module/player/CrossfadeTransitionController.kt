package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

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
    private var autoScheduledFramesLeft: Long = 0L
    @Volatile
    private var activeTargetPath: String? = null
    private var nextReadBuffer = ByteArray(0)
    private var nextMixBuffer = ByteArray(0)
    private val manualProcessor: ManualCrossfadeProcessor =
        NativeManualCrossfadeProcessor.createOrNull(tag) ?: KotlinManualCrossfadeProcessor()
    private val automaticProcessor: AutomaticCrossfadeProcessor =
        NativeAutomaticCrossfadeProcessor.createOrNull(tag) ?: KotlinAutomaticCrossfadeProcessor()
    private val automaticRetimePolicy = AutomaticCrossfadeRetimePolicy()

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
        autoScheduledFramesLeft = 0L
        activeTargetPath = null
        manualProcessor.reset(reason)
        automaticProcessor.reset(reason)
        automaticRetimePolicy.reset()
    }

    fun start(targetPath: String, durationMs: Int, sampleRate: Int, bufferSize: Int, remainingMs: Long): Boolean {
        if (durationMs <= 0 || sampleRate <= 0) return false
        totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        bytesMixed = 0L
        autoPlan = null
        autoScheduledFramesLeft = 0L
        automaticProcessor.reset("manual_crossfade_start")
        automaticRetimePolicy.reset()
        ensureBuffers(bufferSize)
        if (!manualProcessor.start(durationMs, sampleRate)) {
            AppLogger.w(tag, "Crossfade: manual processor failed to arm target=$targetPath")
            return false
        }
        activeTargetPath = targetPath
        active = true
        AppLogger.d(
            tag,
            "Crossfade: START target=$targetPath remaining=${remainingMs}ms totalFrames=$totalFrames " +
                "native=${manualProcessor.isNativeBacked}",
        )
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
        // The decoder starts at the semantic boundary. The first PCM blocks resolve the
        // loudness-adaptive pivot while the independent ramps still begin at exact 1/0 gains.
        totalFrames = (preRollFrames.coerceAtLeast(0L) + handoverFrames.coerceAtLeast(1L))
            .coerceAtLeast(1L)
        bytesMixed = 0L
        autoPlan = plan
        autoScheduledFramesLeft = totalFrames
        ensureBuffers(bufferSize)
        manualProcessor.reset("automatic_crossfade_start")
        automaticRetimePolicy.reset()
        if (!automaticProcessor.start(plan, sampleRate)) {
            AppLogger.w(tag, "AutoCrossfade: processor failed to arm target=$targetPath")
            autoPlan = null
            autoScheduledFramesLeft = 0L
            return false
        }
        activeTargetPath = targetPath
        active = true
        AppLogger.i(
            tag,
                "AutoCrossfade: START target=$targetPath source=${plan.source} remaining=${remainingMs}ms " +
                "preRoll=${plan.preRollMs}ms handover=${plan.handoverMs}ms " +
                "pivot=${plan.pivotMs}ms totalFrames=$totalFrames"
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
        val processedFramesBefore = if (frameSize > 0) bytesMixed / frameSize.toLong() else 0L
        val remainingFramesBefore = (totalFrames - processedFramesBefore).coerceAtLeast(0L)
        val preFreezeFrames = (next.sampleRate.toLong() * PRODUCER_PREFREEZE_MS / 1000L)
            .coerceAtLeast(currentFrames.toLong())
        if (remainingFramesBefore <= preFreezeFrames + currentFrames.toLong()) {
            next.requestProducerFreezeForHandoff()
        }
        // FFmpegBridge outputs 24/32-bit PCM as S32LE.  When the Android output
        // container is packed24/S16, the next decoder therefore needs more source
        // bytes than the current output buffer length.  Under-reading here causes
        // partial-frame mixes and Direct-mode motor-like noise.
        val nextBytesPerSample = if (next.bitsPerSample <= 16) 2 else 4
        val desiredNextRead = (currentFrames * channels * nextBytesPerSample).coerceAtLeast(0)
        ensureBuffers(maxOf(alignedCurrent, desiredNextRead).coerceAtLeast(frameSize).coerceAtMost(currentBuf.size * 2))

        val decodeLimit = desiredNextRead.coerceAtMost(nextReadBuffer.size)
        val nextRead = try {
            next.readPcm(nextReadBuffer, 0, decodeLimit)
        } catch (t: Throwable) {
            AppLogger.e(tag, "Crossfade: next slot read failed path=${next.path}", t)
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
        val completed: Boolean
        if (autoPlan != null) {
            val plan = autoPlan ?: return MixResult(nextRead, 0, progressBefore, false)
            val bufferFrames = if (frameSize > 0 && mixLen > 0) {
                (mixLen / frameSize).toLong().coerceAtLeast(1L)
            } else {
                0L
            }
            if (mixLen > 0) {
                val outgoingDb = PcmLevelMeter.rmsDb(
                    currentBuf,
                    mixLen,
                    outputIsFloat,
                    outputIsPacked24,
                    bitsPerSample,
                )
                val retimeMs = automaticRetimePolicy.evaluate(
                    source = plan.source,
                    outgoingDb = outgoingDb,
                    bufferFrames = bufferFrames,
                    sampleRate = next.sampleRate,
                    scheduledFramesLeft = autoScheduledFramesLeft,
                )
                if (retimeMs != null && automaticProcessor.retime(retimeMs)) {
                    val retimeFrames = next.sampleRate.toLong() * retimeMs.toLong() / 1000L
                    autoScheduledFramesLeft = retimeFrames
                    totalFrames = bytesMixed / frameSize.coerceAtLeast(1) + retimeFrames
                    AppLogger.i(
                        tag,
                        "AutoCrossfade: quiet-tail native retime outgoing=${"%.1f".format(outgoingDb)}dB " +
                            "duration=${retimeMs}ms native=${automaticProcessor.isNativeBacked}",
                    )
                }
            }
            val automaticResult = if (mixLen > 0) {
                automaticProcessor.mixInPlace(
                    currentBuffer = currentBuf,
                    pendingBuffer = mixNextBuffer,
                    offset = 0,
                    length = mixLen,
                    sampleRate = next.sampleRate,
                    frameSize = frameSize,
                    bitsPerSample = bitsPerSample,
                    outputIsFloat = outputIsFloat,
                    outputIsPacked24 = outputIsPacked24,
                )
            } else {
                AutomaticCrossfadeProcessor.Result(
                    consumedFrames = bytesMixed / frameSize.coerceAtLeast(1),
                    completed = false,
                    active = true,
                    retimed = false,
                )
            }
            bytesMixed = automaticResult.consumedFrames * frameSize.toLong()
            autoScheduledFramesLeft = (autoScheduledFramesLeft - bufferFrames).coerceAtLeast(0L)
            completed = automaticResult.completed
        } else {
            val manualResult = if (mixLen > 0) {
                manualProcessor.mixInPlace(
                    currentBuffer = currentBuf,
                    pendingBuffer = mixNextBuffer,
                    offset = 0,
                    length = mixLen,
                    sampleRate = next.sampleRate,
                    frameSize = frameSize,
                    bitsPerSample = bitsPerSample,
                    outputIsFloat = outputIsFloat,
                    outputIsPacked24 = outputIsPacked24,
                )
            } else {
                ManualCrossfadeProcessor.Result(
                    processedFrames = bytesMixed / frameSize.coerceAtLeast(1),
                    completed = false,
                    active = true,
                )
            }
            bytesMixed = manualResult.processedFrames * frameSize.toLong()
            completed = manualResult.completed
        }
        return MixResult(
            nextRead = nextRead,
            mixedBytes = mixLen,
            progressBeforeMix = progressBefore,
            completed = completed,
        )
    }

    fun close() {
        manualProcessor.close()
        automaticProcessor.close()
    }

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

    private companion object {
        const val PRODUCER_PREFREEZE_MS = 100L
    }

    private fun isComplete(frameSize: Int): Boolean {
        val total = totalBytes(frameSize)
        return total > 0L && bytesMixed >= total
    }
}
