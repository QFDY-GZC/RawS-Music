package com.rawsmusic.core.common.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPlaybackSpeedDecoderTest {
    @Test
    fun twoXConsumesSourceAtDoubleRateAcrossNativeChunks() {
        val source = s16Mono((0 until 20).map { it * 1000 })
        val reader = FakeReader(source, maximumChunkBytes = 10) // five frames per native call
        val decoder = PcmPlaybackSpeedDecoder(2f, channels = 1, bitsPerSample = 16)

        val result = drain(decoder, reader, outputChunkBytes = 8)
        val samples = readS16Mono(result)

        assertEquals(listOf(0, 2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000, 18000), samples)
    }

    @Test
    fun halfXInterpolatesContinuouslyAcrossNativeChunks() {
        val source = s16Mono(listOf(0, 1000, 2000, 3000, 4000))
        val reader = FakeReader(source, maximumChunkBytes = 4) // two frames per native call
        val decoder = PcmPlaybackSpeedDecoder(0.5f, channels = 1, bitsPerSample = 16)

        val result = drain(decoder, reader, outputChunkBytes = 6)
        val samples = readS16Mono(result)

        assertEquals(listOf(0, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4000), samples)
    }

    @Test
    fun resetDropsBufferedPreSeekPcm() {
        val source = s16Mono((0 until 12).map { it * 1000 })
        val reader = FakeReader(source, maximumChunkBytes = 24)
        val decoder = PcmPlaybackSpeedDecoder(0.5f, channels = 1, bitsPerSample = 16)
        val first = ByteArray(8)
        assertTrue(decoder.decode(first, 0, first.size, reader::read) > 0)

        decoder.reset()
        val newSource = s16Mono(listOf(20000, 21000, 22000, 23000))
        val postSeekReader = FakeReader(newSource, maximumChunkBytes = 8)
        val second = drain(decoder, postSeekReader, outputChunkBytes = 8)

        assertEquals(20000, readS16Mono(second).first())
    }

    @Test
    fun s32StereoPreservesChannels() {
        val frames = listOf(
            0 to 100,
            1000 to 1100,
            2000 to 2100,
            3000 to 3100,
            4000 to 4100,
        )
        val source = s32Stereo(frames)
        val reader = FakeReader(source, maximumChunkBytes = 16)
        val decoder = PcmPlaybackSpeedDecoder(2f, channels = 2, bitsPerSample = 24)

        val result = drain(decoder, reader, outputChunkBytes = 16)
        val decoded = readS32Stereo(result)
        assertEquals(listOf(0 to 100, 2000 to 2100, 4000 to 4100), decoded)
    }

    private fun drain(
        decoder: PcmPlaybackSpeedDecoder,
        reader: FakeReader,
        outputChunkBytes: Int,
    ): ByteArray {
        val output = ArrayList<Byte>()
        repeat(100) {
            val buffer = ByteArray(outputChunkBytes)
            val read = decoder.decode(buffer, 0, buffer.size, reader::read)
            if (read == -1) return output.toByteArray()
            assertTrue("unexpected decoder result=$read", read >= 0)
            repeat(read) { index -> output += buffer[index] }
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

    private fun s16Mono(samples: List<Int>): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { index, value ->
            out[index * 2] = value.toByte()
            out[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun readS16Mono(bytes: ByteArray): List<Int> =
        bytes.asList().chunked(2).map { chunk ->
            val raw = (chunk[0].toInt() and 0xFF) or ((chunk[1].toInt() and 0xFF) shl 8)
            raw.toShort().toInt()
        }

    private fun s32Stereo(frames: List<Pair<Int, Int>>): ByteArray = ByteArray(frames.size * 8).also { out ->
        frames.forEachIndexed { frame, pair ->
            writeS32(out, frame * 8, pair.first)
            writeS32(out, frame * 8 + 4, pair.second)
        }
    }

    private fun readS32Stereo(bytes: ByteArray): List<Pair<Int, Int>> =
        (bytes.indices step 8).map { offset -> readS32(bytes, offset) to readS32(bytes, offset + 4) }

    private fun writeS32(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value shr 8).toByte()
        destination[offset + 2] = (value shr 16).toByte()
        destination[offset + 3] = (value shr 24).toByte()
    }

    private fun readS32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            (source[offset + 3].toInt() shl 24)
}
