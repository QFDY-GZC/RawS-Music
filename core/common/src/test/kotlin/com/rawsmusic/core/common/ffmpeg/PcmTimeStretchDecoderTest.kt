package com.rawsmusic.core.common.ffmpeg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTimeStretchDecoderTest {
    @Test
    fun preservesPitchAcrossSupportedSpeedRange() {
        val sampleRate = 48_000
        val source = sineS16(sampleRate, 440.0, seconds = 2.0)
        listOf(0.25f, 0.5f, 0.75f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
            val decoder = PcmTimeStretchDecoder(speed, sampleRate, channels = 1, bitsPerSample = 16)
            val output = drain(decoder, FakeReader(source, maximumChunkBytes = 3333))
            val samples = readS16Mono(output)
            val pitchHz = estimatePositiveZeroCrossingHz(samples, sampleRate)
            assertTrue("speed=$speed pitch=$pitchHz", abs(pitchHz - 440.0) < 3.0)
            val expectedFrames = source.size / 2.0 / speed
            val ratioError = abs(samples.size - expectedFrames) / expectedFrames
            assertTrue("speed=$speed durationError=$ratioError", ratioError < 0.04)
        }
    }

    @Test
    fun resetDropsBufferedPreSeekAudio() {
        val sampleRate = 48_000
        val first = sineS16(sampleRate, 440.0, seconds = 0.25)
        val decoder = PcmTimeStretchDecoder(0.5f, sampleRate, channels = 1, bitsPerSample = 16)
        val firstReader = FakeReader(first, maximumChunkBytes = 2048)
        val scratch = ByteArray(4096)
        assertTrue(decoder.decode(scratch, 0, scratch.size, firstReader::read) > 0)

        decoder.reset()
        val second = constantS16(12_345, frames = sampleRate / 5)
        val output = drain(decoder, FakeReader(second, maximumChunkBytes = 3071))
        val samples = readS16Mono(output)
        assertTrue(samples.isNotEmpty())
        assertTrue("post-reset PCM leaked old source", samples.take(128).all { abs(it - 12_345) <= 1 })
    }

    @Test
    fun s32StereoKeepsChannelsAligned() {
        val sampleRate = 48_000
        val frames = sampleRate
        val source = ByteArray(frames * 8)
        repeat(frames) { frame ->
            val left = ((frame % 1024) - 512) * 100_000
            val right = -left
            writeS32(source, frame * 8, left)
            writeS32(source, frame * 8 + 4, right)
        }
        val decoder = PcmTimeStretchDecoder(1.5f, sampleRate, channels = 2, bitsPerSample = 24)
        val output = drain(decoder, FakeReader(source, maximumChunkBytes = 8191), outputChunkBytes = 8192)
        assertTrue(output.isNotEmpty())
        var offset = 0
        while (offset + 7 < output.size) {
            val left = readS32(output, offset)
            val right = readS32(output, offset + 4)
            assertEquals(-left, right)
            offset += 8
        }
    }

    private fun drain(
        decoder: PcmTimeStretchDecoder,
        reader: FakeReader,
        outputChunkBytes: Int = 4096,
    ): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        repeat(100_000) {
            val buffer = ByteArray(outputChunkBytes)
            when (val read = decoder.decode(buffer, 0, buffer.size, reader::read)) {
                -1 -> return ByteArray(total).also { out ->
                    var cursor = 0
                    chunks.forEach { chunk ->
                        chunk.copyInto(out, cursor)
                        cursor += chunk.size
                    }
                }
                -2 -> error("decoder returned terminal error")
                0 -> Unit
                else -> {
                    val chunk = buffer.copyOf(read)
                    chunks += chunk
                    total += read
                }
            }
        }
        error("decoder did not reach EOF")
    }

    private class FakeReader(
        private val bytes: ByteArray,
        private val maximumChunkBytes: Int,
    ) {
        private var offset = 0
        fun read(destination: ByteArray, requestedBytes: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(requestedBytes, maximumChunkBytes, bytes.size - offset, destination.size)
            bytes.copyInto(destination, 0, offset, offset + count)
            offset += count
            return count
        }
    }

    private fun sineS16(sampleRate: Int, hz: Double, seconds: Double): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        return ByteArray(frames * 2).also { out ->
            repeat(frames) { frame ->
                val sample = (sin(2.0 * PI * hz * frame / sampleRate) * 18_000.0).toInt()
                out[frame * 2] = sample.toByte()
                out[frame * 2 + 1] = (sample shr 8).toByte()
            }
        }
    }

    private fun constantS16(sample: Int, frames: Int): ByteArray = ByteArray(frames * 2).also { out ->
        repeat(frames) { frame ->
            out[frame * 2] = sample.toByte()
            out[frame * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun readS16Mono(bytes: ByteArray): IntArray = IntArray(bytes.size / 2) { frame ->
        val offset = frame * 2
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
    }

    private fun estimatePositiveZeroCrossingHz(samples: IntArray, sampleRate: Int): Double {
        if (samples.size < sampleRate / 2) return 0.0
        val trim = minOf(sampleRate / 5, samples.size / 5)
        var crossings = 0
        var previous = samples[trim]
        for (index in trim + 1 until samples.size - trim) {
            val current = samples[index]
            if (previous <= 0 && current > 0) crossings++
            previous = current
        }
        val seconds = (samples.size - trim * 2).toDouble() / sampleRate.toDouble()
        return crossings / seconds
    }

    private fun writeS32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun readS32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
