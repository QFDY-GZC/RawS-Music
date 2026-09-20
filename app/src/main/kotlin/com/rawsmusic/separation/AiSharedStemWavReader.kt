package com.rawsmusic.separation

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

/** Reads a time-aligned slice from the growing stereo float32 WAV published by separation. */
internal object AiSharedStemWavReader {
    private const val CHANNELS = 2
    private const val WAV_HEADER_BYTES = 44L
    private const val BYTES_PER_FRAME = CHANNELS * Float.SIZE_BYTES

    fun readStereoAt(
        stream: AiSeparationLiveStreamState,
        playbackPositionMs: Long,
        outputFrames: Int,
        outputSampleRate: Int,
    ): FloatArray? {
        if (!stream.active || !stream.ready || outputFrames <= 0 || outputSampleRate <= 0) return null
        val inputRate = stream.sampleRate
        if (inputRate <= 0) return null
        val file = File(stream.vocalsPath)
        if (!file.isFile || file.length() <= WAV_HEADER_BYTES) return null

        val startInputFrame = (playbackPositionMs.coerceAtLeast(0L) * inputRate / 1000L)
            .coerceAtLeast(0L)
        val inputFramesNeeded = if (inputRate == outputSampleRate) {
            outputFrames
        } else {
            // One extra source frame is required for linear interpolation at the right edge.
            ((outputFrames.toLong() * inputRate + outputSampleRate - 1L) / outputSampleRate + 2L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val physicalFrames = ((file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L) / BYTES_PER_FRAME)
        val publishedFrames = stream.availableFrames.takeIf { it > 0L } ?: physicalFrames
        val readableEnd = minOf(physicalFrames, publishedFrames)
        if (startInputFrame + inputFramesNeeded > readableEnd) return null

        val source = FloatArray(inputFramesNeeded * CHANNELS)
        val bytes = ByteArray(source.size * Float.SIZE_BYTES)
        RandomAccessFile(file, "r").use { input ->
            input.seek(WAV_HEADER_BYTES + startInputFrame * BYTES_PER_FRAME)
            input.readFully(bytes)
        }
        decodeLittleEndianFloat(bytes, source)
        if (inputRate == outputSampleRate) {
            return if (inputFramesNeeded == outputFrames) source else source.copyOf(outputFrames * CHANNELS)
        }
        return resampleStereoExact(source, inputRate, outputSampleRate, outputFrames)
    }

    private fun decodeLittleEndianFloat(source: ByteArray, destination: FloatArray) {
        for (index in destination.indices) {
            val offset = index * Float.SIZE_BYTES
            val bits = (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                (source[offset + 3].toInt() shl 24)
            destination[index] = Float.fromBits(bits).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        }
    }

    private fun resampleStereoExact(
        input: FloatArray,
        inputRate: Int,
        outputRate: Int,
        outputFrames: Int,
    ): FloatArray {
        val inputFrames = input.size / CHANNELS
        if (inputFrames <= 1) return FloatArray(outputFrames * CHANNELS)
        val output = FloatArray(outputFrames * CHANNELS)
        val step = inputRate.toDouble() / outputRate.toDouble()
        for (frame in 0 until outputFrames) {
            val position = (frame * step).coerceAtMost((inputFrames - 1).toDouble())
            val lower = floor(position).toInt()
            val upper = minOf(lower + 1, inputFrames - 1)
            val fraction = (position - lower).toFloat()
            for (channel in 0 until CHANNELS) {
                val a = input[lower * CHANNELS + channel]
                val b = input[upper * CHANNELS + channel]
                output[frame * CHANNELS + channel] = a + (b - a) * fraction
            }
        }
        return output
    }
}
