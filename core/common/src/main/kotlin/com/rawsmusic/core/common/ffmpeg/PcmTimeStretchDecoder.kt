package com.rawsmusic.core.common.ffmpeg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stateful WSOLA-style time stretcher for the app-owned PCM playback-speed lane.
 *
 * Native FFmpeg remains at 1.00x. This processor changes tempo after decode while keeping the
 * decoder sample rate (and therefore musical pitch) unchanged. One alignment decision is shared
 * by all channels so stereo phase/coherence is preserved.
 *
 * The implementation intentionally keeps a small streaming window across native decodeChunk()
 * boundaries. It uses ~40 ms sequences, a ~20 ms synthesis hop and a +/-10 ms similarity search.
 * Correlation is decimated and searched coarse-to-fine so high sample rates stay bounded.
 */
internal class PcmTimeStretchDecoder(
    speed: Float,
    sampleRate: Int,
    private val channels: Int,
    bitsPerSample: Int,
) {
    private val speed = sanitizeSpeed(speed).toDouble()
    private val sampleRate = sampleRate.coerceAtLeast(8_000)
    private val bytesPerSample = when {
        bitsPerSample <= 1 -> 1
        bitsPerSample <= 16 -> 2
        else -> 4
    }
    private val safeChannels = channels.coerceAtLeast(1)
    private val frameSize = safeChannels * bytesPerSample
    private val enabled = bitsPerSample > 1 && abs(this.speed - 1.0) > SPEED_EPSILON

    private val sequenceFrames = max(32, (this.sampleRate * SEQUENCE_MS / 1000.0).roundToInt())
    private val synthesisHopFrames = max(16, (this.sampleRate * SYNTHESIS_HOP_MS / 1000.0).roundToInt())
        .coerceAtMost(sequenceFrames - 1)
    private val overlapFrames = sequenceFrames - synthesisHopFrames
    private val searchFrames = max(8, (this.sampleRate * SEARCH_MS / 1000.0).roundToInt())
    private val analysisHopFrames = synthesisHopFrames.toDouble() * this.speed

    private val correlationDecimation = max(1, this.sampleRate / CORRELATION_TARGET_RATE)
    private val coarseCandidateStep = max(1, this.sampleRate / COARSE_SEARCH_RATE)
    private val fineCandidateStep = max(1, coarseCandidateStep / 8)

    private var input = ByteArray(initialInputBytes())
    private var inputStart = 0
    private var inputEnd = 0
    private var output = ByteArray(initialOutputBytes())
    private var outputStart = 0
    private var outputEnd = 0
    private var nativeScratch = ByteArray(0)

    private var nativeEof = false
    private var terminalError = false
    private var initialized = false
    private var finalTailQueued = false
    private var analysisPositionFrames = 0.0
    private var previousTail = IntArray(overlapFrames * safeChannels)

    val isEnabled: Boolean get() = enabled
    val playbackSpeed: Float get() = speed.toFloat()

    fun reset() {
        inputStart = 0
        inputEnd = 0
        outputStart = 0
        outputEnd = 0
        nativeEof = false
        terminalError = false
        initialized = false
        finalTailQueued = false
        analysisPositionFrames = 0.0
        previousTail.fill(0)
    }

    /**
     * Fills [destination] with pitch-preserving, speed-adjusted PCM. [nativeRead] must provide
     * ordinary 1.00x interleaved PCM and follows FFmpegBridge's positive / -1 EOF / -2 error
     * convention.
     */
    fun decode(
        destination: ByteArray,
        offset: Int,
        maxBytes: Int,
        nativeRead: (ByteArray, Int) -> Int,
    ): Int {
        if (!enabled) return nativeReadDirect(destination, offset, maxBytes, nativeRead)
        if (offset < 0 || maxBytes <= 0 || offset >= destination.size) return 0
        val safeMax = min(maxBytes, destination.size - offset)
        val frameAlignedMax = safeMax - (safeMax % frameSize)
        if (frameAlignedMax <= 0) return 0

        var copied = copyQueuedOutput(destination, offset, frameAlignedMax)
        while (copied < frameAlignedMax) {
            if (generateOneBlock()) {
                copied += copyQueuedOutput(destination, offset + copied, frameAlignedMax - copied)
                continue
            }

            if (nativeEof || terminalError) {
                if (!initialized && inputFrameCount() > 0) {
                    // Tiny terminal streams shorter than one WSOLA sequence are uncommon. Preserve
                    // their samples/pitch instead of falling back to rate resampling.
                    queueShortTerminalInput()
                    copied += copyQueuedOutput(destination, offset + copied, frameAlignedMax - copied)
                    continue
                }
                if (initialized && !finalTailQueued) {
                    queueFinalTail()
                    copied += copyQueuedOutput(destination, offset + copied, frameAlignedMax - copied)
                    continue
                }
                break
            }

            readMoreNative(nativeRead, frameAlignedMax - copied)
        }

        if (copied > 0) return copied
        return when {
            terminalError -> -2
            nativeEof && outputByteCount() == 0 -> -1
            else -> 0
        }
    }

    private fun generateOneBlock(): Boolean {
        compactInputForNextSearch()
        val available = inputFrameCount()
        if (!initialized) {
            if (available < sequenceFrames) return false
            queueRawFrames(frameOffset = 0, frameCount = synthesisHopFrames)
            copyInputFramesToTail(
                sourceFrame = synthesisHopFrames,
                frameCount = overlapFrames,
            )
            initialized = true
            analysisPositionFrames = analysisHopFrames
            return true
        }

        val expected = analysisPositionFrames.roundToInt()
        val minCandidate = max(0, expected - searchFrames)
        val maxCandidate = min(available - sequenceFrames, expected + searchFrames)
        if (maxCandidate < minCandidate) return false

        val candidate = findBestCandidate(expected, minCandidate, maxCandidate)
        queueOverlap(previousTail, candidate)
        copyInputFramesToTail(
            sourceFrame = candidate + synthesisHopFrames,
            frameCount = overlapFrames,
        )
        // Keep the nominal analysis clock independent from the chosen similarity offset.
        // Otherwise a periodic waveform can accumulate the same phase correction every hop and
        // drift far away from the requested tempo.
        analysisPositionFrames += analysisHopFrames
        return true
    }

    private fun findBestCandidate(expected: Int, minCandidate: Int, maxCandidate: Int): Int {
        if (minCandidate == maxCandidate) return minCandidate
        var best = expected.coerceIn(minCandidate, maxCandidate)
        var bestScore = Double.NEGATIVE_INFINITY

        var candidate = minCandidate
        while (candidate <= maxCandidate) {
            val score = correlationScore(candidate) - distancePenalty(candidate, expected)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += coarseCandidateStep
        }
        if ((maxCandidate - minCandidate) % coarseCandidateStep != 0) {
            val score = correlationScore(maxCandidate) - distancePenalty(maxCandidate, expected)
            if (score > bestScore) {
                bestScore = score
                best = maxCandidate
            }
        }

        val fineRadius = coarseCandidateStep
        val fineMin = max(minCandidate, best - fineRadius)
        val fineMax = min(maxCandidate, best + fineRadius)
        candidate = fineMin
        while (candidate <= fineMax) {
            val score = correlationScore(candidate) - distancePenalty(candidate, expected)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += fineCandidateStep
        }
        return best
    }

    private fun distancePenalty(candidate: Int, expected: Int): Double {
        if (searchFrames <= 0) return 0.0
        return abs(candidate - expected).toDouble() / searchFrames.toDouble() * DISTANCE_PENALTY
    }

    private fun correlationScore(candidateFrame: Int): Double {
        var dot = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var frame = 0
        while (frame < overlapFrames) {
            val tailBase = frame * safeChannels
            val inputBase = inputStart + (candidateFrame + frame) * frameSize
            for (channel in 0 until safeChannels) {
                val left = previousTail[tailBase + channel].toDouble()
                val right = readInputSample(inputBase + channel * bytesPerSample).toDouble()
                dot += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            frame += correlationDecimation
        }
        val denom = kotlin.math.sqrt(leftEnergy * rightEnergy)
        return if (denom <= 1.0) 0.0 else dot / denom
    }

    private fun queueOverlap(tail: IntArray, candidateFrame: Int) {
        ensureOutputCapacity(outputByteCount() + overlapFrames * frameSize)
        var dst = outputEnd
        for (frame in 0 until overlapFrames) {
            // Linear overlap-add. Endpoints stay slightly inside (0,1), preventing an abrupt
            // one-sample ownership flip while still converging to the new sequence.
            val incomingWeight = (frame + 1).toDouble() / (overlapFrames + 1).toDouble()
            val outgoingWeight = 1.0 - incomingWeight
            val inputFrameOffset = inputStart + (candidateFrame + frame) * frameSize
            val tailBase = frame * safeChannels
            for (channel in 0 until safeChannels) {
                val a = tail[tailBase + channel]
                val b = readInputSample(inputFrameOffset + channel * bytesPerSample)
                val mixed = (a.toDouble() * outgoingWeight + b.toDouble() * incomingWeight).roundToInt()
                writeOutputSample(dst + channel * bytesPerSample, mixed)
            }
            dst += frameSize
        }
        outputEnd = dst
    }

    private fun queueRawFrames(frameOffset: Int, frameCount: Int) {
        if (frameCount <= 0) return
        val bytes = frameCount * frameSize
        ensureOutputCapacity(outputByteCount() + bytes)
        val source = inputStart + frameOffset * frameSize
        input.copyInto(output, outputEnd, source, source + bytes)
        outputEnd += bytes
    }

    private fun queueFinalTail() {
        if (finalTailQueued) return
        ensureOutputCapacity(outputByteCount() + previousTail.size * bytesPerSample)
        var dst = outputEnd
        for (sample in previousTail) {
            writeOutputSample(dst, sample)
            dst += bytesPerSample
        }
        outputEnd = dst
        finalTailQueued = true
        inputStart = inputEnd
    }

    private fun queueShortTerminalInput() {
        val frames = inputFrameCount()
        if (frames <= 0) return
        queueRawFrames(0, frames)
        inputStart += frames * frameSize
        finalTailQueued = true
    }

    private fun copyInputFramesToTail(sourceFrame: Int, frameCount: Int) {
        if (frameCount != overlapFrames) {
            previousTail = IntArray(frameCount * safeChannels)
        }
        var target = 0
        for (frame in 0 until frameCount) {
            val src = inputStart + (sourceFrame + frame) * frameSize
            for (channel in 0 until safeChannels) {
                previousTail[target++] = readInputSample(src + channel * bytesPerSample)
            }
        }
    }

    private fun compactInputForNextSearch() {
        if (!initialized) {
            compactInputBytesIfUseful()
            return
        }
        val keepFromFrame = max(0, analysisPositionFrames.toInt() - searchFrames - 2)
        if (keepFromFrame <= 0) {
            compactInputBytesIfUseful()
            return
        }
        val dropBytes = min(keepFromFrame * frameSize, inputByteCount() - (inputByteCount() % frameSize))
        if (dropBytes <= 0) return
        inputStart += dropBytes
        analysisPositionFrames -= dropBytes / frameSize.toDouble()
        compactInputBytesIfUseful()
    }

    private fun readMoreNative(nativeRead: (ByteArray, Int) -> Int, wantedOutputBytes: Int) {
        val wantedOutputFrames = max(1, wantedOutputBytes / frameSize)
        val speedInputFrames = (wantedOutputFrames * speed).roundToInt()
        val minimumWindowFrames = sequenceFrames + searchFrames * 2 + synthesisHopFrames
        val requestedFrames = max(speedInputFrames + minimumWindowFrames, minimumWindowFrames)
        val requestedBytes = requestedFrames.toLong()
            .times(frameSize.toLong())
            .coerceAtMost(MAX_NATIVE_READ_BYTES.toLong())
            .toInt()
            .coerceAtLeast(frameSize * 4)
        ensureNativeScratch(requestedBytes)
        val decoded = nativeRead(nativeScratch, requestedBytes)
        when {
            decoded > 0 -> appendNativePcm(nativeScratch, decoded)
            decoded == -1 -> nativeEof = true
            else -> terminalError = true
        }
    }

    private fun appendNativePcm(source: ByteArray, decodedBytes: Int) {
        val accepted = decodedBytes.coerceIn(0, source.size)
        if (accepted <= 0) return
        ensureInputCapacity(inputByteCount() + accepted)
        source.copyInto(input, inputEnd, 0, accepted)
        inputEnd += accepted
    }

    private fun copyQueuedOutput(destination: ByteArray, offset: Int, maxBytes: Int): Int {
        val available = outputByteCount()
        if (available <= 0 || maxBytes <= 0) return 0
        val count = min(available, maxBytes - (maxBytes % frameSize))
        if (count <= 0) return 0
        output.copyInto(destination, offset, outputStart, outputStart + count)
        outputStart += count
        if (outputStart == outputEnd) {
            outputStart = 0
            outputEnd = 0
        } else if (outputStart >= output.size / 2) {
            val remaining = outputByteCount()
            output.copyInto(output, 0, outputStart, outputEnd)
            outputStart = 0
            outputEnd = remaining
        }
        return count
    }

    private fun nativeReadDirect(
        destination: ByteArray,
        offset: Int,
        maxBytes: Int,
        nativeRead: (ByteArray, Int) -> Int,
    ): Int {
        val safeMax = min(maxBytes, destination.size - offset).coerceAtLeast(0)
        if (safeMax <= 0) return 0
        if (offset == 0) return nativeRead(destination, safeMax)
        ensureNativeScratch(safeMax)
        val read = nativeRead(nativeScratch, safeMax)
        if (read > 0) nativeScratch.copyInto(destination, offset, 0, min(read, safeMax))
        return read
    }

    private fun readInputSample(offset: Int): Int = when (bytesPerSample) {
        2 -> {
            val raw = (input[offset].toInt() and 0xFF) or ((input[offset + 1].toInt() and 0xFF) shl 8)
            raw.toShort().toInt()
        }
        4 -> (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            (input[offset + 3].toInt() shl 24)
        else -> input[offset].toInt()
    }

    private fun writeOutputSample(offset: Int, value: Int) {
        when (bytesPerSample) {
            2 -> {
                val safe = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[offset] = safe.toByte()
                output[offset + 1] = (safe shr 8).toByte()
            }
            4 -> {
                output[offset] = value.toByte()
                output[offset + 1] = (value shr 8).toByte()
                output[offset + 2] = (value shr 16).toByte()
                output[offset + 3] = (value shr 24).toByte()
            }
            else -> output[offset] = value.toByte()
        }
    }

    private fun ensureInputCapacity(requiredBytes: Int) {
        if (requiredBytes <= input.size - inputStart) {
            if (inputStart > 0) {
                val remaining = inputByteCount()
                input.copyInto(input, 0, inputStart, inputEnd)
                inputStart = 0
                inputEnd = remaining
            }
            return
        }
        val remaining = inputByteCount()
        var newSize = max(input.size * 2, requiredBytes + remaining).coerceAtLeast(initialInputBytes())
        while (newSize < remaining + requiredBytes) newSize *= 2
        val replacement = ByteArray(newSize)
        input.copyInto(replacement, 0, inputStart, inputEnd)
        input = replacement
        inputStart = 0
        inputEnd = remaining
    }

    private fun compactInputBytesIfUseful() {
        if (inputStart <= 0 || inputStart < input.size / 2) return
        val remaining = inputByteCount()
        input.copyInto(input, 0, inputStart, inputEnd)
        inputStart = 0
        inputEnd = remaining
    }

    private fun ensureOutputCapacity(requiredQueuedBytes: Int) {
        val queued = outputByteCount()
        if (requiredQueuedBytes <= output.size - outputStart) {
            if (outputStart > 0) {
                output.copyInto(output, 0, outputStart, outputEnd)
                outputStart = 0
                outputEnd = queued
            }
            return
        }
        var newSize = max(output.size * 2, requiredQueuedBytes).coerceAtLeast(initialOutputBytes())
        while (newSize < requiredQueuedBytes) newSize *= 2
        val replacement = ByteArray(newSize)
        output.copyInto(replacement, 0, outputStart, outputEnd)
        output = replacement
        outputStart = 0
        outputEnd = queued
    }

    private fun ensureNativeScratch(required: Int) {
        if (nativeScratch.size < required) nativeScratch = ByteArray(required)
    }

    private fun inputFrameCount(): Int = inputByteCount() / frameSize
    private fun inputByteCount(): Int = inputEnd - inputStart
    private fun outputByteCount(): Int = outputEnd - outputStart

    private fun initialInputBytes(): Int = max(DEFAULT_BUFFER_BYTES, sequenceFrames * frameSize * 4)
    private fun initialOutputBytes(): Int = max(DEFAULT_BUFFER_BYTES, sequenceFrames * frameSize * 2)

    companion object {
        private const val SEQUENCE_MS = 40.0
        private const val SYNTHESIS_HOP_MS = 20.0
        private const val SEARCH_MS = 10.0
        private const val SPEED_EPSILON = 0.0001
        private const val CORRELATION_TARGET_RATE = 12_000
        private const val COARSE_SEARCH_RATE = 2_000
        private const val DISTANCE_PENALTY = 0.0025
        private const val DEFAULT_BUFFER_BYTES = 64 * 1024
        private const val MAX_NATIVE_READ_BYTES = 512 * 1024

        private fun sanitizeSpeed(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0.25f, 3f) else 1f
    }
}
