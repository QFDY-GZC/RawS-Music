package com.rawsmusic.module.scanner

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Read-only ID3v2 lyric fallback for malformed tags that strict TagLib/FFmpeg parsers reject.
 *
 * This deliberately does not rewrite the media file. It only scans ID3v2 frame headers and reads
 * lyric-bearing payloads (USLT/SYLT/TXXX). The parser tolerates common real-world deviations:
 *
 * - UTF-16 (encoding 0x01) empty descriptions written as 00 00 without a BOM.
 * - UTF-16BE (encoding 0x02) strings that still include FE FF / FF FE.
 * - UTF-16 bodies whose byte order can only be inferred from their bytes.
 * - ID3v2.2/2.3/2.4 frame sizes and tag/frame unsynchronisation.
 */
internal object Id3EmbeddedLyricReader {

    private const val MAX_TAG_BYTES = 32L * 1024L * 1024L
    private const val MAX_LYRIC_FRAME_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAME_COUNT = 4096

    data class Result(
        val text: String,
        val frameId: String,
        val encoding: String,
        val recoveredMalformedEncoding: Boolean
    )

    private data class Candidate(
        val result: Result,
        val score: Int
    )

    private data class DecodedString(
        val text: String,
        val nextOffset: Int,
        val charsetHint: Charset?,
        val recoveredMalformedEncoding: Boolean
    )

    fun read(filePath: String): Result? {
        val file = File(filePath)
        if (!file.isFile || !file.canRead()) return null
        if (!file.extension.equals("mp3", ignoreCase = true) &&
            !file.extension.equals("mp2", ignoreCase = true) &&
            !file.extension.equals("mp1", ignoreCase = true)
        ) {
            return null
        }

        return runCatching {
            RandomAccessFile(file, "r").use(::readTag)
        }.getOrNull()
    }

    private fun readTag(input: RandomAccessFile): Result? {
        if (input.length() < 10L) return null

        val header = ByteArray(10)
        input.readFully(header)
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return null
        }

        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion !in 2..4) return null

        val tagFlags = header[5].toInt() and 0xFF
        val declaredTagSize = syncSafeInt(header, 6).toLong()
        if (declaredTagSize <= 0L || declaredTagSize > MAX_TAG_BYTES) return null

        val tagEnd = (10L + declaredTagSize).coerceAtMost(input.length())
        val tagUnsynchronised = tagFlags and 0x80 != 0

        // ID3v2.2 tag-level compression is not safely recoverable here.
        if (majorVersion == 2 && tagFlags and 0x40 != 0) return null

        var position = 10L
        if (majorVersion >= 3 && tagFlags and 0x40 != 0) {
            position = skipExtendedHeader(input, position, tagEnd, majorVersion) ?: return null
        }

        val candidates = ArrayList<Candidate>()
        var frameCount = 0
        while (position < tagEnd && frameCount++ < MAX_FRAME_COUNT) {
            val frameHeaderSize = if (majorVersion == 2) 6 else 10
            if (tagEnd - position < frameHeaderSize) break

            input.seek(position)
            val frameHeader = ByteArray(frameHeaderSize)
            input.readFully(frameHeader)

            val rawFrameId = if (majorVersion == 2) {
                ascii(frameHeader, 0, 3)
            } else {
                ascii(frameHeader, 0, 4)
            }
            if (rawFrameId.all { it == '\u0000' }) break
            if (!rawFrameId.isValidFrameId()) break

            val frameSize = when (majorVersion) {
                2 -> unsignedInt24(frameHeader, 3)
                3 -> unsignedInt32(frameHeader, 4)
                else -> syncSafeInt(frameHeader, 4).toLong()
            }
            if (frameSize <= 0L) {
                position += frameHeaderSize
                continue
            }

            val payloadStart = position + frameHeaderSize
            val payloadEnd = payloadStart + frameSize
            if (payloadEnd > tagEnd || payloadEnd < payloadStart) break

            val canonicalFrameId = canonicalFrameId(rawFrameId, majorVersion)
            if (canonicalFrameId == "USLT" || canonicalFrameId == "SYLT" || canonicalFrameId == "TXXX") {
                if (frameSize <= MAX_LYRIC_FRAME_BYTES) {
                    val rawPayload = ByteArray(frameSize.toInt())
                    input.seek(payloadStart)
                    input.readFully(rawPayload)
                    preparePayload(rawPayload, frameHeader, majorVersion, tagUnsynchronised)?.let { payload ->
                        decodeCandidate(canonicalFrameId, payload)?.let(candidates::add)
                    }
                }
            }

            position = payloadEnd
        }

        return candidates.maxByOrNull { it.score }?.result
    }

    private fun skipExtendedHeader(
        input: RandomAccessFile,
        position: Long,
        tagEnd: Long,
        majorVersion: Int
    ): Long? {
        if (tagEnd - position < 4L) return null
        input.seek(position)
        val sizeBytes = ByteArray(4)
        input.readFully(sizeBytes)

        val totalSize = if (majorVersion == 3) {
            // ID3v2.3 stores the bytes following the four-byte size field.
            4L + unsignedInt32(sizeBytes, 0)
        } else {
            // ID3v2.4 stores a sync-safe size including the size field itself.
            syncSafeInt(sizeBytes, 0).toLong()
        }
        if (totalSize < 4L) return null
        val next = position + totalSize
        return next.takeIf { it in (position + 4L)..tagEnd }
    }

    private fun preparePayload(
        original: ByteArray,
        frameHeader: ByteArray,
        majorVersion: Int,
        tagUnsynchronised: Boolean
    ): ByteArray? {
        var payload = original
        var offset = 0
        var frameUnsynchronised = false

        if (majorVersion == 3) {
            val formatFlags = frameHeader[9].toInt() and 0xFF
            if (formatFlags and 0x80 != 0 || formatFlags and 0x40 != 0) return null // compressed/encrypted
            if (formatFlags and 0x20 != 0) offset += 1 // grouping identity
        } else if (majorVersion == 4) {
            val formatFlags = frameHeader[9].toInt() and 0xFF
            if (formatFlags and 0x08 != 0 || formatFlags and 0x04 != 0) return null // compressed/encrypted
            if (formatFlags and 0x40 != 0) offset += 1 // grouping identity
            frameUnsynchronised = formatFlags and 0x02 != 0
            if (formatFlags and 0x01 != 0) offset += 4 // data length indicator
        }

        if (offset > payload.size) return null
        if (offset > 0) payload = payload.copyOfRange(offset, payload.size)
        if (tagUnsynchronised || frameUnsynchronised) payload = removeUnsynchronisation(payload)
        return payload
    }

    private fun decodeCandidate(frameId: String, payload: ByteArray): Candidate? {
        return when (frameId) {
            "USLT" -> decodeUslt(payload)
            "TXXX" -> decodeTxxx(payload)
            "SYLT" -> decodeSylt(payload)
            else -> null
        }
    }

    private fun decodeUslt(payload: ByteArray): Candidate? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        if (encoding !in 0..3) return null

        val description = decodeTerminatedString(payload, 4, encoding, null)
        val lyrics = decodeRemainingString(payload, description.nextOffset, encoding, description.charsetHint)
        val text = sanitiseText(lyrics.text)
        if (!text.looksLikeLyrics()) return null

        val result = Result(
            text = text,
            frameId = "USLT",
            encoding = encodingName(encoding, lyrics.charsetHint ?: description.charsetHint),
            recoveredMalformedEncoding = description.recoveredMalformedEncoding || lyrics.recoveredMalformedEncoding
        )
        return Candidate(result, score(text, framePriority = 300))
    }

    private fun decodeTxxx(payload: ByteArray): Candidate? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xFF
        if (encoding !in 0..3) return null

        val description = decodeTerminatedString(payload, 1, encoding, null)
        val normalisedDescription = description.text
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
            .uppercase(Locale.ROOT)
        if (normalisedDescription !in TXXX_LYRIC_DESCRIPTIONS && !normalisedDescription.contains("LYRIC")) {
            return null
        }

        val value = decodeRemainingString(payload, description.nextOffset, encoding, description.charsetHint)
        val text = sanitiseText(value.text)
        if (!text.looksLikeLyrics()) return null

        val result = Result(
            text = text,
            frameId = "TXXX:${description.text.ifBlank { "LYRICS" }}",
            encoding = encodingName(encoding, value.charsetHint ?: description.charsetHint),
            recoveredMalformedEncoding = description.recoveredMalformedEncoding || value.recoveredMalformedEncoding
        )
        return Candidate(result, score(text, framePriority = 220))
    }

    private fun decodeSylt(payload: ByteArray): Candidate? {
        if (payload.size < 7) return null
        val encoding = payload[0].toInt() and 0xFF
        if (encoding !in 0..3) return null
        val timestampFormat = payload[4].toInt() and 0xFF
        if (timestampFormat != 2) return null // only millisecond timestamps can be represented safely

        val description = decodeTerminatedString(payload, 6, encoding, null)
        var offset = description.nextOffset
        var charsetHint = description.charsetHint
        var recovered = description.recoveredMalformedEncoding
        val lines = ArrayList<String>()

        while (offset < payload.size) {
            val value = decodeTerminatedString(payload, offset, encoding, charsetHint)
            offset = value.nextOffset
            charsetHint = value.charsetHint ?: charsetHint
            recovered = recovered || value.recoveredMalformedEncoding
            if (offset + 4 > payload.size) break

            val timestampMs = unsignedInt32(payload, offset).coerceAtMost(Int.MAX_VALUE.toLong())
            offset += 4
            val text = sanitiseText(value.text).trim()
            if (text.isNotEmpty()) {
                lines += "${formatLrcTimestamp(timestampMs)}$text"
            }
        }

        val text = lines.joinToString("\n")
        if (!text.looksLikeLyrics()) return null
        val result = Result(
            text = text,
            frameId = "SYLT",
            encoding = encodingName(encoding, charsetHint),
            recoveredMalformedEncoding = recovered
        )
        return Candidate(result, score(text, framePriority = 160))
    }

    private fun decodeTerminatedString(
        bytes: ByteArray,
        start: Int,
        encoding: Int,
        inheritedCharset: Charset?
    ): DecodedString {
        if (start >= bytes.size) return DecodedString("", bytes.size, inheritedCharset, false)

        if (encoding == 0 || encoding == 3) {
            val end = bytes.indexOfZero(start)
            val charset = if (encoding == 0) Charsets.ISO_8859_1 else Charsets.UTF_8
            return DecodedString(
                text = decodeStrictThenReplace(bytes, start, end, charset),
                nextOffset = (end + 1).coerceAtMost(bytes.size),
                charsetHint = charset,
                recoveredMalformedEncoding = false
            )
        }

        // Real-world ID3v2.3 files often encode an empty UTF-16 descriptor as bare 00 00,
        // despite encoding 0x01 requiring a BOM. Treat it as an empty descriptor.
        if (start + 1 < bytes.size && bytes[start] == 0.toByte() && bytes[start + 1] == 0.toByte()) {
            return DecodedString("", start + 2, inheritedCharset, encoding == 1)
        }

        val bomCharset = charsetFromBom(bytes, start)
        val charset = when (encoding) {
            1 -> bomCharset ?: inheritedCharset ?: detectUtf16Charset(bytes, start) ?: Charsets.UTF_16LE
            2 -> bomCharset ?: Charsets.UTF_16BE
            else -> inheritedCharset ?: Charsets.UTF_16LE
        }
        val contentStart = if (bomCharset != null) start + 2 else start
        val end = bytes.indexOfUtf16Terminator(contentStart)
        val safeEnd = end - ((end - contentStart) and 1)
        val decoded = decodeStrictThenReplace(bytes, contentStart, safeEnd, charset)

        return DecodedString(
            text = decoded,
            nextOffset = (end + 2).coerceAtMost(bytes.size),
            charsetHint = charset,
            recoveredMalformedEncoding =
                (encoding == 1 && bomCharset == null) ||
                    (encoding == 2 && bomCharset != null)
        )
    }

    private fun decodeRemainingString(
        bytes: ByteArray,
        start: Int,
        encoding: Int,
        inheritedCharset: Charset?
    ): DecodedString {
        if (start >= bytes.size) return DecodedString("", bytes.size, inheritedCharset, false)

        if (encoding == 0 || encoding == 3) {
            val charset = if (encoding == 0) Charsets.ISO_8859_1 else Charsets.UTF_8
            return DecodedString(
                text = decodeStrictThenReplace(bytes, start, bytes.size, charset),
                nextOffset = bytes.size,
                charsetHint = charset,
                recoveredMalformedEncoding = false
            )
        }

        val bomCharset = charsetFromBom(bytes, start)
        val charset = when (encoding) {
            1 -> bomCharset ?: inheritedCharset ?: detectUtf16Charset(bytes, start) ?: Charsets.UTF_16LE
            2 -> bomCharset ?: Charsets.UTF_16BE
            else -> inheritedCharset ?: Charsets.UTF_16LE
        }
        val contentStart = if (bomCharset != null) start + 2 else start
        val safeEnd = bytes.size - ((bytes.size - contentStart) and 1)
        return DecodedString(
            text = decodeStrictThenReplace(bytes, contentStart, safeEnd, charset),
            nextOffset = bytes.size,
            charsetHint = charset,
            recoveredMalformedEncoding =
                (encoding == 1 && bomCharset == null) ||
                    (encoding == 2 && bomCharset != null)
        )
    }

    private fun decodeStrictThenReplace(bytes: ByteArray, start: Int, end: Int, charset: Charset): String {
        if (start >= end || start !in 0..bytes.size || end !in 0..bytes.size) return ""
        val buffer = ByteBuffer.wrap(bytes, start, end - start)
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(buffer)
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, end - start, charset)
        }
    }

    private fun charsetFromBom(bytes: ByteArray, start: Int): Charset? {
        if (start + 1 >= bytes.size) return null
        return when {
            bytes[start] == 0xFF.toByte() && bytes[start + 1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes[start] == 0xFE.toByte() && bytes[start + 1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun detectUtf16Charset(bytes: ByteArray, start: Int): Charset? {
        val sampleEnd = (start + 128).coerceAtMost(bytes.size)
        val sampleLength = sampleEnd - start
        if (sampleLength < 8) return null

        var evenNulls = 0
        var oddNulls = 0
        var pairs = 0
        var index = start
        while (index + 1 < sampleEnd) {
            if (bytes[index] == 0.toByte()) evenNulls++
            if (bytes[index + 1] == 0.toByte()) oddNulls++
            pairs++
            index += 2
        }
        return when {
            oddNulls >= pairs / 3 -> Charsets.UTF_16LE
            evenNulls >= pairs / 3 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun removeUnsynchronisation(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val output = ByteArray(bytes.size)
        var read = 0
        var write = 0
        while (read < bytes.size) {
            val value = bytes[read]
            output[write++] = value
            if (value == 0xFF.toByte() && read + 1 < bytes.size && bytes[read + 1] == 0.toByte()) {
                read++
            }
            read++
        }
        return if (write == bytes.size) bytes else output.copyOf(write)
    }

    private fun sanitiseText(value: String): String {
        return value
            .trimStart('\u0000', '\uFEFF', '\uFFFE')
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun String.looksLikeLyrics(): Boolean {
        if (isBlank()) return false
        if (length < 4) return false
        return any { it == '\n' || it == '\r' } ||
            LRC_TIMESTAMP.containsMatchIn(this) ||
            length >= 16
    }

    private fun score(text: String, framePriority: Int): Int {
        val timestampCount = LRC_TIMESTAMP.findAll(text).take(500).count()
        val lineCount = text.lineSequence().take(500).count { it.isNotBlank() }
        return framePriority + timestampCount * 20 + lineCount.coerceAtMost(200) + text.length.coerceAtMost(200_000) / 100
    }

    private fun formatLrcTimestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return "[%02d:%02d.%03d]".format(Locale.US, minutes, seconds, millis)
    }

    private fun encodingName(encoding: Int, charset: Charset?): String {
        return when (encoding) {
            0 -> "ISO-8859-1"
            1 -> charset?.name()?.let { "UTF-16/$it" } ?: "UTF-16"
            2 -> charset?.name()?.let { "UTF-16BE/$it" } ?: "UTF-16BE"
            3 -> "UTF-8"
            else -> charset?.name().orEmpty()
        }
    }

    private fun canonicalFrameId(frameId: String, majorVersion: Int): String {
        if (majorVersion != 2) return frameId
        return when (frameId) {
            "ULT" -> "USLT"
            "SLT" -> "SYLT"
            "TXX" -> "TXXX"
            else -> frameId
        }
    }

    private fun String.isValidFrameId(): Boolean {
        return isNotEmpty() && all { it in 'A'..'Z' || it in '0'..'9' }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        return String(bytes, offset, length, Charsets.ISO_8859_1)
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun unsignedInt24(bytes: ByteArray, offset: Int): Long {
        if (offset + 2 >= bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 2].toLong() and 0xFFL)
    }

    private fun unsignedInt32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun ByteArray.indexOfZero(start: Int): Int {
        var index = start.coerceAtLeast(0)
        while (index < size) {
            if (this[index] == 0.toByte()) return index
            index++
        }
        return size
    }

    private fun ByteArray.indexOfUtf16Terminator(start: Int): Int {
        var index = start.coerceAtLeast(0)
        // UTF-16 terminators must be checked on code-unit boundaries.
        if ((index - start) and 1 != 0) index++
        while (index + 1 < size) {
            if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) return index
            index += 2
        }
        return size
    }

    private val LRC_TIMESTAMP = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
    private val TXXX_LYRIC_DESCRIPTIONS = setOf(
        "LYRICS",
        "UNSYNCEDLYRICS",
        "UNSYNCEDLYRIC",
        "SYNCEDLYRICS",
        "SYNCEDLYRIC",
        "LYRIC",
        "LRC"
    )
}
