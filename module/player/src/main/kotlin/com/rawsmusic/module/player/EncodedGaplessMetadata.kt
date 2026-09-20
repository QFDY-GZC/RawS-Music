package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import java.io.RandomAccessFile
import java.util.Locale

/** Encoded-domain gapless declaration, independent from the FFmpeg decoder ABI. */
internal data class EncodedGaplessMetadata(
    val codec: Codec,
    val sourceSampleRate: Int,
    val encodedFrames: Long,
    val declaredDelayFrames: Int,
    val declaredPaddingFrames: Int,
    val expectedAudibleFrames: Long,
    val source: String,
) {
    enum class Codec { MP3_LAME, AAC_ITUNSMPB }

    val totalDeclaredTrimFrames: Long
        get() = declaredDelayFrames.toLong() + declaredPaddingFrames.toLong()

    fun expectedOutputFrames(outputSampleRate: Int): Long =
        rescaleFrames(expectedAudibleFrames, sourceSampleRate, outputSampleRate)

    fun encodedOutputFrames(outputSampleRate: Int): Long =
        rescaleFrames(encodedFrames, sourceSampleRate, outputSampleRate)

    private fun rescaleFrames(frames: Long, sourceRate: Int, outputRate: Int): Long {
        if (frames <= 0L || sourceRate <= 0 || outputRate <= 0) return 0L
        return ((frames.toDouble() * outputRate.toDouble()) / sourceRate.toDouble()).toLong()
    }
}

/**
 * Reads gapless declarations without depending on private fields in librawsmusic_ffmpeg.so.
 * The decoder remains the trim owner; this metadata is used to verify its actual output.
 */
internal object EncodedGaplessMetadataProbe {
    private const val MAX_MP3_SYNC_SCAN_BYTES = 1024 * 1024

    fun probe(path: String): EncodedGaplessMetadata? {
        if (!isLocalReadablePath(path)) return null
        val magic = runCatching {
            RandomAccessFile(path, "r").use { file ->
                val bytes = ByteArray(12)
                val read = file.read(bytes)
                if (read <= 0) ByteArray(0) else bytes.copyOf(read)
            }
        }.getOrNull() ?: return null

        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".mp3") || looksLikeMp3(magic) -> parseMp3(path)
            lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".aac") || looksLikeMp4(magic) ->
                parseItunSmpb(runCatching { FFmpegBridge.getMediaInfo(path) }.getOrNull())
            else -> null
        }
    }

    internal fun parseItunSmpb(metadata: Map<String, String>?): EncodedGaplessMetadata? {
        if (metadata.isNullOrEmpty()) return null
        val raw = metadata.entries.firstOrNull { it.key.equals("iTunSMPB", ignoreCase = true) }?.value
            ?: return null
        val effectiveRate = metadata.entries.firstNotNullOfOrNull { (key, value) ->
            if (key.endsWith("effective_sample_rate", ignoreCase = true)) value.toIntOrNull() else null
        }
        val declaredRate = metadata.entries.firstNotNullOfOrNull { (key, value) ->
            if (key.endsWith("sample_rate", ignoreCase = true)) value.toIntOrNull() else null
        }
        return parseItunSmpbValue(
            raw = raw,
            sourceSampleRate = effectiveRate ?: declaredRate ?: 0,
        )
    }

    internal fun parseItunSmpbValue(raw: String, sourceSampleRate: Int): EncodedGaplessMetadata? {
        if (sourceSampleRate <= 0) return null
        val fields = Regex("(?i)[0-9a-f]{8,16}").findAll(raw).map { it.value }.toList()
        if (fields.size < 4) return null
        val delay = fields[1].toLongOrNull(16)?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: return null
        val padding = fields[2].toLongOrNull(16)?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: return null
        val audibleFrames = fields[3].toLongOrNull(16)?.takeIf { it > 0L } ?: return null
        val encodedFrames = audibleFrames + delay.toLong() + padding.toLong()
        return EncodedGaplessMetadata(
            codec = EncodedGaplessMetadata.Codec.AAC_ITUNSMPB,
            sourceSampleRate = sourceSampleRate,
            encodedFrames = encodedFrames,
            declaredDelayFrames = delay,
            declaredPaddingFrames = padding,
            expectedAudibleFrames = audibleFrames,
            source = "iTunSMPB",
        )
    }

    internal fun parseMp3(path: String): EncodedGaplessMetadata? = runCatching {
        RandomAccessFile(path, "r").use { file ->
            if (file.length() < 64L) return null
            var searchOffset = readId3v2End(file)
            val searchEnd = minOf(file.length() - 4L, searchOffset + MAX_MP3_SYNC_SCAN_BYTES)
            val headerBytes = ByteArray(4)
            while (searchOffset <= searchEnd) {
                file.seek(searchOffset)
                if (file.read(headerBytes) != headerBytes.size) break
                val mp3 = parseMp3Header(headerBytes)
                if (mp3 == null || mp3.frameLength < 40 || searchOffset + mp3.frameLength > file.length()) {
                    searchOffset++
                    continue
                }
                val frame = ByteArray(mp3.frameLength)
                file.seek(searchOffset)
                val actual = file.read(frame)
                if (actual < 40) break

                val crcBytes = if (mp3.hasCrc) 2 else 0
                val sideInfoBytes = when (mp3.version) {
                    1 -> if (mp3.mono) 17 else 32
                    else -> if (mp3.mono) 9 else 17
                }
                val xingOffset = 4 + crcBytes + sideInfoBytes
                if (xingOffset + 8 > actual) {
                    searchOffset++
                    continue
                }
                val marker = frame.ascii(xingOffset, 4)
                if (marker != "Xing" && marker != "Info") {
                    searchOffset++
                    continue
                }
                val flags = frame.u32be(xingOffset + 4)
                var cursor = xingOffset + 8
                val frameCount = if ((flags and 0x1L) != 0L) {
                    if (cursor + 4 > actual) return null
                    frame.u32be(cursor).also { cursor += 4 }
                } else return null
                if ((flags and 0x2L) != 0L) cursor += 4
                if ((flags and 0x4L) != 0L) cursor += 100
                if ((flags and 0x8L) != 0L) cursor += 4
                if (cursor + 24 > actual) return null
                val encoder = frame.ascii(cursor, 9)
                if (!encoder.startsWith("LAME", ignoreCase = true) &&
                    !encoder.startsWith("Lavf", ignoreCase = true)
                ) return null
                val b21 = frame[cursor + 21].toInt() and 0xFF
                val b22 = frame[cursor + 22].toInt() and 0xFF
                val b23 = frame[cursor + 23].toInt() and 0xFF
                val delay = (b21 shl 4) or (b22 ushr 4)
                val padding = ((b22 and 0x0F) shl 8) or b23
                val encodedFrames = frameCount * mp3.samplesPerFrame.toLong()
                val audibleFrames = encodedFrames - delay.toLong() - padding.toLong()
                if (frameCount <= 0L || audibleFrames <= 0L) return null
                return EncodedGaplessMetadata(
                    codec = EncodedGaplessMetadata.Codec.MP3_LAME,
                    sourceSampleRate = mp3.sampleRate,
                    encodedFrames = encodedFrames,
                    declaredDelayFrames = delay,
                    declaredPaddingFrames = padding,
                    expectedAudibleFrames = audibleFrames,
                    source = "$marker/$encoder",
                )
            }
            null
        }
    }.getOrNull()

    private data class Mp3Header(
        val version: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameLength: Int,
        val mono: Boolean,
        val hasCrc: Boolean,
    )

    private fun parseMp3Header(bytes: ByteArray): Mp3Header? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val versionBits = (b1 ushr 3) and 0x03
        val version = when (versionBits) {
            3 -> 1
            2 -> 2
            0 -> 25
            else -> return null
        }
        val layerBits = (b1 ushr 1) and 0x03
        if (layerBits != 1) return null // Layer III only.
        val bitrateIndex = (b2 ushr 4) and 0x0F
        val sampleRateIndex = (b2 ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val baseRates = intArrayOf(44100, 48000, 32000)
        val sampleRate = when (version) {
            1 -> baseRates[sampleRateIndex]
            2 -> baseRates[sampleRateIndex] / 2
            else -> baseRates[sampleRateIndex] / 4
        }
        val v1Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val v2Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val bitrateKbps = if (version == 1) v1Bitrates[bitrateIndex] else v2Bitrates[bitrateIndex]
        val padding = (b2 ushr 1) and 0x01
        val frameLength = if (version == 1) {
            (144000 * bitrateKbps / sampleRate) + padding
        } else {
            (72000 * bitrateKbps / sampleRate) + padding
        }
        return Mp3Header(
            version = version,
            sampleRate = sampleRate,
            samplesPerFrame = if (version == 1) 1152 else 576,
            frameLength = frameLength,
            mono = ((b3 ushr 6) and 0x03) == 3,
            hasCrc = (b1 and 0x01) == 0,
        )
    }

    private fun readId3v2End(file: RandomAccessFile): Long {
        if (file.length() < 10L) return 0L
        val header = ByteArray(10)
        file.seek(0L)
        if (file.read(header) != header.size || header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()
        ) return 0L
        val size = ((header[6].toInt() and 0x7F) shl 21) or
            ((header[7].toInt() and 0x7F) shl 14) or
            ((header[8].toInt() and 0x7F) shl 7) or
            (header[9].toInt() and 0x7F)
        val footer = if ((header[5].toInt() and 0x10) != 0) 10 else 0
        return (10L + size.toLong() + footer.toLong()).coerceAtMost(file.length())
    }

    private fun ByteArray.u32be(offset: Int): Long =
        ((this[offset].toLong() and 0xFFL) shl 24) or
            ((this[offset + 1].toLong() and 0xFFL) shl 16) or
            ((this[offset + 2].toLong() and 0xFFL) shl 8) or
            (this[offset + 3].toLong() and 0xFFL)

    private fun ByteArray.ascii(offset: Int, count: Int): String =
        String(this, offset, count, Charsets.ISO_8859_1)

    private fun isLocalReadablePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("http://", true) && !path.startsWith("https://", true)

    private fun looksLikeMp3(bytes: ByteArray): Boolean =
        bytes.size >= 3 && (bytes.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
            (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0))

    private fun looksLikeMp4(bytes: ByteArray): Boolean =
        bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"
}
