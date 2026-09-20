package com.rawsmusic.core.common.ffmpeg

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Stateful PCM rate adapter used around the shipped prebuilt FFmpeg decoder.
 *
 * The packaged librawsmusic_ffmpeg.so owns demux/decode, but it cannot be assumed to expose the
 * newer playback-speed ABI described by the reference ffmpeg_bridge.cpp.  This adapter therefore
 * keeps the native decoder at 1.00x and changes the number of PCM frames delivered to the player.
 * The result intentionally changes pitch with speed, matching RawSMusic's existing playback-speed
 * setting contract.
 *
 * Input/output are interleaved little-endian PCM at the decoder's advertised format.  FFmpegBridge
 * exposes <=16-bit PCM as 16-bit containers and >16-bit PCM as 32-bit containers.
 */
internal class PcmPlaybackSpeedDecoder(
    speed: Float,
    private val channels: Int,
    bitsPerSample: Int,
) {
    private val speed = sanitizeSpeed(speed).toDouble()
    private val bytesPerSample = when {
        bitsPerSample <= 1 -> 1
        bitsPerSample <= 16 -> 2
        else -> 4
    }
    private val frameSize = (channels.coerceAtLeast(1) * bytesPerSample).coerceAtLeast(1)
    private val enabled = bitsPerSample > 1 && kotlin.math.abs(this.speed - 1.0) > SPEED_EPSILON

    private var pending = ByteArray(DEFAULT_PENDING_BYTES.coerceAtLeast(frameSize * 4))
    private var pendingStart = 0
    private var pendingEnd = 0
    private var sourcePositionFrames = 0.0
    private var nativeEof = false
    private var terminalError = false
    private var nativeScratch = ByteArray(0)

    val isEnabled: Boolean get() = enabled
    val playbackSpeed: Float get() = speed.toFloat()

    fun reset() {
        pendingStart = 0
        pendingEnd = 0
        sourcePositionFrames = 0.0
        nativeEof = false
        terminalError = false
    }

    /**
     * Fills [destination] with speed-adjusted PCM. [nativeRead] must decode ordinary 1.00x PCM.
     * It follows FFmpegBridge semantics: positive byte count, -1 EOF, -2 error.
     */
    fun decode(
        destination: ByteArray,
        offset: Int,
        maxBytes: Int,
        nativeRead: (ByteArray, Int) -> Int,
    ): Int {
        if (!enabled) return nativeReadDirect(destination, offset, maxBytes, nativeRead)
        if (offset < 0 || maxBytes <= 0 || offset >= destination.size) return 0

        val safeMax = minOf(maxBytes, destination.size - offset)
        val outputCapacityFrames = safeMax / frameSize
        if (outputCapacityFrames <= 0) return 0

        var outputFrames = 0
        while (outputFrames < outputCapacityFrames) {
            compactConsumedFrames()
            val availableFrames = pendingFrameCount()
            val baseFrame = floor(sourcePositionFrames).toInt()
            val canRender = if (nativeEof) {
                baseFrame in 0 until availableFrames
            } else {
                baseFrame >= 0 && baseFrame + 1 < availableFrames
            }

            if (canRender) {
                val maxRenderable = outputCapacityFrames - outputFrames
                val rendered = renderAvailable(
                    destination = destination,
                    outputByteOffset = offset + outputFrames * frameSize,
                    maxOutputFrames = maxRenderable,
                )
                outputFrames += rendered
                if (rendered > 0) continue
            }

            if (nativeEof) break
            if (terminalError) break

            val requestedInputBytes = requestedNativeReadBytes(safeMax)
            ensureNativeScratch(requestedInputBytes)
            val decoded = nativeRead(nativeScratch, requestedInputBytes)
            when {
                decoded > 0 -> appendNativePcm(nativeScratch, decoded)
                decoded == -1 -> nativeEof = true
                else -> terminalError = true
            }
        }

        compactConsumedFrames()
        val outputBytes = outputFrames * frameSize
        if (outputBytes > 0) return outputBytes
        return when {
            terminalError -> -2
            nativeEof && pendingFrameCount() == 0 -> -1
            nativeEof && floor(sourcePositionFrames).toInt() >= pendingFrameCount() -> -1
            else -> 0
        }
    }

    private fun nativeReadDirect(
        destination: ByteArray,
        offset: Int,
        maxBytes: Int,
        nativeRead: (ByteArray, Int) -> Int,
    ): Int {
        // The direct callback writes from byte zero. FFmpegBridge bypasses this class entirely for
        // 1.00x in production, but keeping this path makes the class independently testable.
        if (offset != 0) {
            val safeMax = minOf(maxBytes, destination.size - offset).coerceAtLeast(0)
            if (safeMax <= 0) return 0
            ensureNativeScratch(safeMax)
            val read = nativeRead(nativeScratch, safeMax)
            if (read > 0) nativeScratch.copyInto(destination, offset, 0, minOf(read, safeMax))
            return read
        }
        return nativeRead(destination, minOf(maxBytes, destination.size))
    }

    private fun requestedNativeReadBytes(outputMaxBytes: Int): Int {
        val outputFrames = (outputMaxBytes / frameSize).coerceAtLeast(1)
        val wantedFrames = ceil(outputFrames * speed).toInt()
            .coerceAtLeast(2) + 4
        return wantedFrames * frameSize
    }

    private fun renderAvailable(
        destination: ByteArray,
        outputByteOffset: Int,
        maxOutputFrames: Int,
    ): Int {
        val availableFrames = pendingFrameCount()
        if (availableFrames <= 0 || maxOutputFrames <= 0) return 0

        var rendered = 0
        while (rendered < maxOutputFrames) {
            val base = floor(sourcePositionFrames).toInt()
            if (base < 0 || base >= availableFrames) break
            if (!nativeEof && base + 1 >= availableFrames) break

            val next = minOf(base + 1, availableFrames - 1)
            val fraction = (sourcePositionFrames - base.toDouble()).coerceIn(0.0, 1.0)
            val src0 = pendingStart + base * frameSize
            val src1 = pendingStart + next * frameSize
            val dst = outputByteOffset + rendered * frameSize
            when (bytesPerSample) {
                2 -> interpolateS16Frame(src0, src1, fraction, destination, dst)
                4 -> interpolateS32Frame(src0, src1, fraction, destination, dst)
                else -> return rendered
            }
            rendered++
            sourcePositionFrames += speed
        }
        return rendered
    }

    private fun interpolateS16Frame(
        src0: Int,
        src1: Int,
        fraction: Double,
        destination: ByteArray,
        dst: Int,
    ) {
        for (channel in 0 until channels) {
            val aOffset = src0 + channel * 2
            val bOffset = src1 + channel * 2
            val a = readS16(pending, aOffset)
            val b = readS16(pending, bOffset)
            val value = (a.toDouble() + (b.toDouble() - a.toDouble()) * fraction)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val out = dst + channel * 2
            destination[out] = value.toByte()
            destination[out + 1] = (value shr 8).toByte()
        }
    }

    private fun interpolateS32Frame(
        src0: Int,
        src1: Int,
        fraction: Double,
        destination: ByteArray,
        dst: Int,
    ) {
        for (channel in 0 until channels) {
            val aOffset = src0 + channel * 4
            val bOffset = src1 + channel * 4
            val a = readS32(pending, aOffset)
            val b = readS32(pending, bOffset)
            val value = (a.toDouble() + (b.toDouble() - a.toDouble()) * fraction)
                .roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
            val out = dst + channel * 4
            destination[out] = value.toByte()
            destination[out + 1] = (value shr 8).toByte()
            destination[out + 2] = (value shr 16).toByte()
            destination[out + 3] = (value shr 24).toByte()
        }
    }

    private fun appendNativePcm(source: ByteArray, decodedBytes: Int) {
        // Native FFmpeg normally returns whole PCM frames, but keep an incomplete tail instead of
        // dropping bytes if an older/prebuilt decoder ever splits a frame at the caller limit.
        val accepted = decodedBytes.coerceIn(0, source.size)
        if (accepted <= 0) return
        ensurePendingCapacity(pendingByteCount() + accepted)
        source.copyInto(pending, pendingEnd, 0, accepted)
        pendingEnd += accepted
    }

    private fun compactConsumedFrames() {
        val availableFrames = pendingFrameCount()
        if (availableFrames <= 0) {
            if (nativeEof) {
                // EOF may leave an incomplete frame from a defensive odd-byte native read.
                pendingStart = 0
                pendingEnd = 0
                sourcePositionFrames = 0.0
            }
            return
        }

        val whole = floor(sourcePositionFrames).toInt().coerceAtLeast(0)
        // Until EOF keep one source frame so interpolation across native chunk boundaries is
        // continuous. At EOF all fully-consumed frames may be retired.
        val maxDrop = if (nativeEof) availableFrames else (availableFrames - 1).coerceAtLeast(0)
        val dropFrames = whole.coerceAtMost(maxDrop)
        if (dropFrames <= 0) return
        pendingStart += dropFrames * frameSize
        sourcePositionFrames -= dropFrames.toDouble()
        if (pendingStart == pendingEnd) {
            pendingStart = 0
            pendingEnd = 0
        } else if (pendingStart >= pending.size / 2) {
            compactPendingBytes()
        }
    }

    private fun pendingFrameCount(): Int = pendingByteCount() / frameSize
    private fun pendingByteCount(): Int = pendingEnd - pendingStart

    private fun ensurePendingCapacity(requiredBytes: Int) {
        if (requiredBytes <= 0) return
        val bytesToAppend = (requiredBytes - pendingByteCount()).coerceAtLeast(0)
        if (pendingEnd + bytesToAppend <= pending.size) return
        compactPendingBytes()
        if (pendingEnd + bytesToAppend <= pending.size) return
        val needed = pendingEnd + bytesToAppend
        var next = pending.size.coerceAtLeast(frameSize * 4)
        while (next < needed) next = (next * 2).coerceAtLeast(needed)
        pending = pending.copyOf(next)
    }

    private fun compactPendingBytes() {
        if (pendingStart <= 0) return
        val count = pendingByteCount()
        if (count > 0) pending.copyInto(pending, 0, pendingStart, pendingEnd)
        pendingStart = 0
        pendingEnd = count
    }

    private fun ensureNativeScratch(requiredBytes: Int) {
        if (nativeScratch.size >= requiredBytes) return
        nativeScratch = ByteArray(requiredBytes)
    }

    companion object {
        private const val SPEED_EPSILON = 0.0001
        private const val DEFAULT_PENDING_BYTES = 64 * 1024

        fun sanitizeSpeed(speed: Float): Float =
            if (speed.isFinite()) speed.coerceIn(0.25f, 3f) else 1f

        private fun readS16(source: ByteArray, offset: Int): Int {
            val raw = (source[offset].toInt() and 0xFF) or
                ((source[offset + 1].toInt() and 0xFF) shl 8)
            return raw.toShort().toInt()
        }

        private fun readS32(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xFF) or
                ((source[offset + 1].toInt() and 0xFF) shl 8) or
                ((source[offset + 2].toInt() and 0xFF) shl 16) or
                (source[offset + 3].toInt() shl 24)
    }
}
