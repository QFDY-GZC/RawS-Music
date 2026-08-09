package com.rawsmusic.module.player.dsp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * WAV impulse-response reader kept outside the realtime DSP/controller classes.
 *
 * Supported input:
 * - RIFF/WAVE
 * - PCM 8/16/24/32-bit
 * - IEEE float 32-bit
 * - mono or stereo
 *
 * The file is decoded on Dispatchers.IO. Resampling to the current DSP sample
 * rate is performed by [FftConvolverController] so an engine rebuild can reuse
 * the original source IR without reopening the document.
 */
internal object FftConvolverIrLoader {
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_SOURCE_FRAMES = 262_144

    data class IrData(
        val samples: FloatArray,
        val frames: Int,
        val channels: Int,
        val sampleRate: Int,
        val displayName: String,
        val uri: String
    )

    suspend fun load(context: Context, uri: Uri, displayName: String): Result<IrData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readLimited(context, uri)
                decodeWave(bytes, displayName.ifBlank { "Impulse response.wav" }, uri.toString())
            }
        }

    private fun readLimited(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open the selected IR file")
        return input.use { stream ->
            val output = ByteArrayOutputStream(minOf(MAX_FILE_BYTES, 256 * 1024))
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MAX_FILE_BYTES) { "IR file is larger than 32 MB" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun decodeWave(bytes: ByteArray, displayName: String, uri: String): IrData {
        require(bytes.size >= 44) { "The selected file is not a valid WAV IR" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Only RIFF/WAVE impulse responses are supported"
        }

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val chunkId = ascii(bytes, cursor, 4)
            val declaredSize = littleInt(bytes, cursor + 4).toLong() and 0xFFFF_FFFFL
            val chunkData = cursor + 8
            if (chunkData > bytes.size) break
            val available = bytes.size - chunkData
            val safeSize = minOf(declaredSize, available.toLong()).toInt()

            when (chunkId) {
                "fmt " -> {
                    require(safeSize >= 16) { "Invalid WAV format chunk" }
                    formatTag = littleShort(bytes, chunkData)
                    channels = littleShort(bytes, chunkData + 2)
                    sampleRate = littleInt(bytes, chunkData + 4)
                    blockAlign = littleShort(bytes, chunkData + 12)
                    bitsPerSample = littleShort(bytes, chunkData + 14)

                    // WAVE_FORMAT_EXTENSIBLE stores the real format tag at the
                    // beginning of the SubFormat GUID.
                    if (formatTag == 0xFFFE && safeSize >= 40) {
                        formatTag = littleShort(bytes, chunkData + 24)
                    }
                }

                "data" -> {
                    dataOffset = chunkData
                    dataSize = safeSize
                }
            }

            val padded = declaredSize + (declaredSize and 1L)
            val next = chunkData.toLong() + padded
            if (next <= cursor || next > Int.MAX_VALUE) break
            cursor = next.toInt()
        }

        require(formatTag == 1 || formatTag == 3) {
            "Unsupported WAV encoding (format $formatTag)"
        }
        require(channels in 1..2) { "IR must be mono or stereo" }
        require(sampleRate in 8_000..768_000) { "Invalid IR sample rate" }
        require(dataOffset >= 0 && dataSize > 0) { "WAV file does not contain audio data" }

        val bytesPerSample = max(1, (bitsPerSample + 7) / 8)
        val expectedBlockAlign = channels * bytesPerSample
        val safeBlockAlign = if (blockAlign >= expectedBlockAlign) blockAlign else expectedBlockAlign
        require(safeBlockAlign > 0) { "Invalid WAV block alignment" }

        val supported = when (formatTag) {
            1 -> bitsPerSample in setOf(8, 16, 24, 32)
            3 -> bitsPerSample == 32
            else -> false
        }
        require(supported) { "Unsupported WAV bit depth: $bitsPerSample-bit" }

        val availableFrames = dataSize / safeBlockAlign
        val frames = minOf(availableFrames, MAX_SOURCE_FRAMES)
        require(frames > 0) { "IR file contains no samples" }

        val samples = FloatArray(frames * channels)
        var frameOffset = dataOffset
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val sampleOffset = frameOffset + channel * bytesPerSample
                val value = when {
                    formatTag == 3 -> Float.fromBits(littleInt(bytes, sampleOffset))
                    bitsPerSample == 8 -> ((bytes[sampleOffset].toInt() and 0xFF) - 128) / 128f
                    bitsPerSample == 16 -> littleSigned16(bytes, sampleOffset) / 32768f
                    bitsPerSample == 24 -> littleSigned24(bytes, sampleOffset) / 8_388_608f
                    else -> littleInt(bytes, sampleOffset) / 2_147_483_648f
                }
                samples[frame * channels + channel] =
                    if (value.isFinite()) value.coerceIn(-8f, 8f) else 0f
            }
            frameOffset += safeBlockAlign
        }

        return IrData(
            samples = samples,
            frames = frames,
            channels = channels,
            sampleRate = sampleRate,
            displayName = displayName,
            uri = uri
        )
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Unexpected end of WAV file" }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun littleSigned16(bytes: ByteArray, offset: Int): Int =
        littleShort(bytes, offset).toShort().toInt()

    private fun littleSigned24(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 3 <= bytes.size) { "Unexpected end of WAV file" }
        var value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        if ((value and 0x0080_0000) != 0) value = value or -0x0100_0000
        return value
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Unexpected end of WAV file" }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
