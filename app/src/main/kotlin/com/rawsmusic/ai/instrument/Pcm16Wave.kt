package com.rawsmusic.ai.instrument

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

internal data class Pcm16WaveData(
    val sampleRate: Int,
    val channels: Int,
    val frames: FloatArray,
) {
    init {
        require(sampleRate > 0 && channels in 1..2)
        require(frames.size % channels == 0)
    }
    val frameCount: Int get() = frames.size / channels
}

/** Minimal strict RIFF/WAVE PCM16 reader used for trusted instrument packs. */
internal object Pcm16WaveReader {
    fun read(file: File, expectedSampleRate: Int, expectedChannels: Int): Pcm16WaveData {
        DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER_BYTES)).use { input ->
            require(readAscii(input, 4) == "RIFF") { "sample is not RIFF" }
            readLeInt(input) // RIFF size
            require(readAscii(input, 4) == "WAVE") { "sample is not WAVE" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var data: ByteArray? = null
            while (data == null) {
                val chunk = runCatching { readAscii(input, 4) }.getOrNull() ?: break
                val size = readLeInt(input)
                require(size >= 0 && size <= MAX_SAMPLE_BYTES) { "sample WAV chunk is invalid" }
                when (chunk) {
                    "fmt " -> {
                        require(size >= 16) { "sample WAV fmt chunk is too short" }
                        format = readLeShort(input)
                        channels = readLeShort(input)
                        sampleRate = readLeInt(input)
                        readLeInt(input) // byte rate
                        readLeShort(input) // block align
                        bits = readLeShort(input)
                        skipFully(input, size - 16)
                    }
                    "data" -> {
                        data = ByteArray(size)
                        input.readFully(data)
                    }
                    else -> skipFully(input, size)
                }
                if (size and 1 != 0) input.readByte()
            }
            require(format == 1 && bits == 16) { "instrument sample must be PCM16 WAV" }
            require(sampleRate == expectedSampleRate) { "instrument sample rate mismatch" }
            require(channels == expectedChannels) { "instrument sample channel mismatch" }
            val bytes = requireNotNull(data) { "instrument sample has no data chunk" }
            require(bytes.size % (channels * 2) == 0) { "instrument sample data is misaligned" }
            require(bytes.size >= channels * 2 * 2) { "instrument sample is too short" }
            val pcm = FloatArray(bytes.size / 2)
            var source = 0
            for (i in pcm.indices) {
                val value = (bytes[source].toInt() and 0xff) or (bytes[source + 1].toInt() shl 8)
                pcm[i] = value.toShort() / 32768f
                source += 2
            }
            return Pcm16WaveData(sampleRate, channels, pcm)
        }
    }

    private fun readAscii(input: DataInputStream, length: Int): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readLeShort(input: DataInputStream): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun readLeInt(input: DataInputStream): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        val c = input.readUnsignedByte()
        val d = input.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    private fun skipFully(input: DataInputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            require(skipped > 0) { "truncated WAV chunk" }
            remaining -= skipped
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
}

class Pcm16WaveWriter(
    private val file: File,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
) : AutoCloseable {
    private val output = BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)
    private var writtenFrames = 0L
    private var closed = false

    init {
        require(sampleRate > 0 && channels in 1..2 && frameCount >= 0)
        val dataBytes = frameCount * channels * 2L
        require(dataBytes <= 0xfffffff0L) { "rendered lead exceeds RIFF WAV size" }
        writeHeader(dataBytes.toInt())
    }

    fun writeInterleaved(samples: FloatArray, frames: Int = samples.size / channels) {
        check(!closed)
        require(frames >= 0 && frames * channels <= samples.size)
        require(writtenFrames + frames <= frameCount) { "too many rendered WAV frames" }
        val bytes = ByteArray(frames * channels * 2)
        var target = 0
        for (i in 0 until frames * channels) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[target++] = (value and 0xff).toByte()
            bytes[target++] = ((value ushr 8) and 0xff).toByte()
        }
        output.write(bytes)
        writtenFrames += frames
    }

    override fun close() {
        if (closed) return
        closed = true
        output.flush()
        output.close()
        require(writtenFrames == frameCount) { "rendered WAV is incomplete: $writtenFrames/$frameCount" }
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() == 44L + frameCount * channels * 2L) { "rendered WAV size mismatch" }
        }
    }

    private fun writeHeader(dataBytes: Int) {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(output, 36 + dataBytes)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(output, 16)
        writeLeShort(output, 1)
        writeLeShort(output, channels)
        writeLeInt(output, sampleRate)
        writeLeInt(output, sampleRate * channels * 2)
        writeLeShort(output, channels * 2)
        writeLeShort(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(output, dataBytes)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        private fun writeLeShort(output: BufferedOutputStream, value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }

        private fun writeLeInt(output: BufferedOutputStream, value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            output.write((value ushr 16) and 0xff)
            output.write((value ushr 24) and 0xff)
        }
    }
}
