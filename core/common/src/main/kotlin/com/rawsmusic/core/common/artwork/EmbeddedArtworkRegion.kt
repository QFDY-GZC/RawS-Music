package com.rawsmusic.core.common.artwork

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-style embedded artwork region handle.
 *
 * Instead of extracting embedded artwork into a temporary file first, this parser returns the
 * original audio file + byte offset + byte length for formats where the picture payload is stored
 * as a contiguous image region. Callers can open a bounded stream over that region and let
 * BitmapFactory sample it directly.
 *
 * Direct regions are currently verified for FLAC METADATA_BLOCK_PICTURE, ordinary DSF/WAV
 * ID3v2.3/ID3v2.4 APIC frames, and APEv2 Cover Art binary items. ID3 frames that require transforms
 * such as unsynchronisation/compression/encryption deliberately stay on the TagLib/FFmpeg fallback;
 * formats whose atom/frame transforms are not yet verified (for example MP3/MP4 here) do the same.
 */
object EmbeddedArtworkRegion {
    private const val MIN_ART_BYTES = 1024L
    private const val FLAC_MAGIC = "fLaC"
    private const val FLAC_BLOCK_PICTURE = 6
    private const val MAX_FLAC_METADATA_SCAN_BYTES = 64L * 1024L * 1024L
    private const val DSF_MAGIC = "DSD "
    private const val RIFF_MAGIC = "RIFF"
    private const val RF64_MAGIC = "RF64"
    private const val WAVE_MAGIC = "WAVE"
    private const val WAV_ID3_CHUNK = "ID3 "
    private const val ID3_MAGIC = "ID3"
    private const val ID3_APIC = "APIC"
    private const val MAX_ID3_TAG_SCAN_BYTES = 64L * 1024L * 1024L
    private const val APE_MAGIC = "APETAGEX"
    private const val MAX_APE_TAG_SCAN_BYTES = 64L * 1024L * 1024L
    private const val MAX_APE_ITEMS = 4096
    private const val MAX_TEXT_FIELD_BYTES = 4096

    data class Handle(
        val audioPath: String,
        val sourceKey: String,
        val offset: Long,
        val length: Long,
        val mime: String?,
        val format: String
    ) {
        fun openStream(): InputStream {
            val input = FileInputStream(audioPath)
            try {
                skipFully(input, offset)
                return BoundedInputStream(input, length)
            } catch (t: Throwable) {
                try { input.close() } catch (_: Throwable) {}
                throw t
            }
        }
    }

    private data class CacheEntry(
        val fileLength: Long,
        val lastModified: Long,
        val handle: Handle?
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun find(audioPath: String): Handle? {
        if (audioPath.isBlank()) return null
        val file = File(audioPath)
        if (!file.exists() || !file.canRead()) return null
        val sourceKey = sourceVersionKey(file)
        cache[sourceKey]?.let { entry ->
            if (entry.fileLength == file.length() && entry.lastModified == file.lastModified()) {
                return entry.handle
            }
        }
        val handle = when (file.extension.lowercase()) {
            "flac" -> findFlacPicture(file, sourceKey)
            "dsf" -> findDsfPicture(file, sourceKey)
            "wav", "wave" -> findWavPicture(file, sourceKey)
            "ape" -> findApePicture(file, sourceKey)
            else -> null
        }
        cache[sourceKey] = CacheEntry(file.length(), file.lastModified(), handle)
        return handle
    }

    fun invalidate(audioPath: String) {
        if (audioPath.isBlank()) return
        val file = File(audioPath)
        cache.remove(sourceVersionKey(file))
    }

    fun clear() {
        cache.clear()
    }

    private fun findFlacPicture(file: File, sourceKey: String): Handle? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8L) return null
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != FLAC_MAGIC) return null

                var scanned = 4L
                var fallback: Handle? = null
                while (raf.filePointer + 4L <= raf.length() && scanned < MAX_FLAC_METADATA_SCAN_BYTES) {
                    val header = raf.readUnsignedByte()
                    val isLast = (header and 0x80) != 0
                    val blockType = header and 0x7F
                    val blockLength = raf.readUInt24()
                    val blockStart = raf.filePointer
                    scanned = blockStart + blockLength

                    if (blockLength < 0 || blockStart + blockLength > raf.length()) return null
                    if (blockType == FLAC_BLOCK_PICTURE) {
                        parseFlacPictureBlock(
                            file = file,
                            sourceKey = sourceKey,
                            raf = raf,
                            blockStart = blockStart,
                            blockLength = blockLength
                        )?.let { handle ->
                            if (handle.format == "flac-front") return handle
                            if (fallback == null) fallback = handle
                        }
                    }

                    raf.seek(blockStart + blockLength)
                    if (scanned >= MAX_FLAC_METADATA_SCAN_BYTES) break
                    if (isLast) break
                }
                fallback
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlacPictureBlock(
        file: File,
        sourceKey: String,
        raf: RandomAccessFile,
        blockStart: Long,
        blockLength: Int
    ): Handle? {
        val blockEnd = blockStart + blockLength
        raf.seek(blockStart)

        // FLAC METADATA_BLOCK_PICTURE:
        // type(4), mime_len(4), mime, desc_len(4), desc, width/height/depth/colors(16), data_len(4), data.
        if (blockLength < 32) return null
        val pictureType = raf.readIntSafe() ?: return null
        val mimeLength = raf.readIntSafe() ?: return null
        if (mimeLength < 0 || mimeLength > 256 || raf.filePointer + mimeLength > blockEnd) return null
        val mimeBytes = ByteArray(mimeLength)
        raf.readFully(mimeBytes)
        val mime = String(mimeBytes, Charsets.US_ASCII).takeIf { it.isNotBlank() }

        val descriptionLength = raf.readIntSafe() ?: return null
        if (descriptionLength < 0 || descriptionLength > blockLength || raf.filePointer + descriptionLength > blockEnd) return null
        raf.seek(raf.filePointer + descriptionLength)

        // width, height, color depth, indexed colors
        if (raf.filePointer + 16L > blockEnd) return null
        raf.seek(raf.filePointer + 16L)

        val dataLength = raf.readIntSafe() ?: return null
        val dataOffset = raf.filePointer
        if (dataLength <= MIN_ART_BYTES || dataOffset + dataLength > blockEnd) return null

        // Prefer front cover; findFlacPicture() scans all FLAC picture blocks and keeps a fallback.
        val format = if (pictureType == 3) "flac-front" else "flac-picture"
        return Handle(
            audioPath = file.absolutePath,
            sourceKey = sourceKey,
            offset = dataOffset,
            length = dataLength.toLong(),
            mime = mime,
            format = format
        )
    }

    /**
     * DSF keeps its optional ID3v2 tag at the absolute metadata pointer in the 28-byte DSD chunk.
     * APIC bytes are safe to map directly only when neither the tag nor the frame requests
     * unsynchronisation/compression/encryption transforms. Unsupported transformed variants stay
     * on the existing TagLib/FFmpeg fallback path instead of exposing a wrong byte region.
     */
    private fun findDsfPicture(file: File, sourceKey: String): Handle? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 28L) return null
                if (raf.readAscii(4) != DSF_MAGIC) return null

                val dsdChunkSize = raf.readUInt64Le() ?: return null
                val declaredFileSize = raf.readUInt64Le() ?: return null
                val metadataOffset = raf.readUInt64Le() ?: return null
                if (dsdChunkSize < 28L || declaredFileSize <= 0L) return null
                if (metadataOffset <= 0L || metadataOffset + 10L > raf.length()) return null

                raf.seek(metadataOffset)
                if (raf.readAscii(3) != ID3_MAGIC) return null
                val majorVersion = raf.readUnsignedByte()
                raf.readUnsignedByte() // revision
                val tagFlags = raf.readUnsignedByte()
                if (majorVersion !in 3..4) return null
                if ((tagFlags and 0x80) != 0 || (tagFlags and 0x40) != 0) return null

                val tagSize = raf.readSynchsafeInt() ?: return null
                if (tagSize <= 0 || tagSize.toLong() > MAX_ID3_TAG_SCAN_BYTES) return null
                val tagStart = raf.filePointer
                val tagEnd = tagStart + tagSize.toLong()
                if (tagEnd > raf.length()) return null

                var fallback: Handle? = null
                while (raf.filePointer + 10L <= tagEnd) {
                    val frameId = raf.readAscii(4)
                    if (frameId.all { it == '\u0000' }) break
                    if (!frameId.all { it.code in 0x20..0x7E }) return null

                    val frameSize = if (majorVersion == 4) {
                        raf.readSynchsafeInt() ?: return null
                    } else {
                        raf.readUInt32BeInt() ?: return null
                    }
                    raf.readUnsignedByte() // status flags
                    val formatFlags = raf.readUnsignedByte()
                    val frameStart = raf.filePointer
                    val frameEnd = frameStart + frameSize.toLong()
                    if (frameSize <= 0 || frameEnd > tagEnd) return null

                    if (frameId == ID3_APIC && isDirectId3Frame(majorVersion, formatFlags)) {
                        parseId3ApicFrame(
                            file = file,
                            sourceKey = sourceKey,
                            raf = raf,
                            frameStart = frameStart,
                            frameEnd = frameEnd,
                            frontFormat = "dsf-front",
                            fallbackFormat = "dsf-apic",
                        )?.let { handle ->
                            if (handle.format == "dsf-front") return handle
                            if (fallback == null) fallback = handle
                        }
                    }

                    raf.seek(frameEnd)
                }
                fallback
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDirectId3Frame(majorVersion: Int, formatFlags: Int): Boolean {
        return when (majorVersion) {
            3 -> (formatFlags and 0xE0) == 0 // compression/encryption/grouping
            4 -> (formatFlags and 0x4F) == 0 // grouping/compression/encryption/unsync/DLI
            else -> false
        }
    }

    private fun findWavPicture(file: File, sourceKey: String): Handle? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return null
                val container = raf.readAscii(4)
                if (container != RIFF_MAGIC && container != RF64_MAGIC) return null
                raf.readUInt32Le() ?: return null
                if (raf.readAscii(4) != WAVE_MAGIC) return null

                while (raf.filePointer + 8L <= raf.length()) {
                    val chunkId = raf.readAscii(4)
                    val chunkSize = raf.readUInt32Le() ?: return null
                    val chunkStart = raf.filePointer
                    val chunkEnd = chunkStart + chunkSize
                    if (chunkEnd < chunkStart || chunkEnd > raf.length()) return null

                    if (chunkId.equals(WAV_ID3_CHUNK, ignoreCase = true)) {
                        findId3ApicInWavChunk(
                            file = file,
                            sourceKey = sourceKey,
                            raf = raf,
                            chunkStart = chunkStart,
                            chunkEnd = chunkEnd,
                        )?.let { return it }
                    }

                    val paddedEnd = chunkEnd + (chunkSize and 1L)
                    if (paddedEnd < chunkEnd || paddedEnd > raf.length()) return null
                    raf.seek(paddedEnd)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findId3ApicInWavChunk(
        file: File,
        sourceKey: String,
        raf: RandomAccessFile,
        chunkStart: Long,
        chunkEnd: Long,
    ): Handle? {
        if (chunkEnd - chunkStart < 10L) return null
        raf.seek(chunkStart)
        if (raf.readAscii(3) != ID3_MAGIC) return null
        val majorVersion = raf.readUnsignedByte()
        raf.readUnsignedByte()
        val tagFlags = raf.readUnsignedByte()
        if (majorVersion !in 3..4) return null
        if ((tagFlags and 0xC0) != 0) return null

        val tagSize = raf.readSynchsafeInt() ?: return null
        if (tagSize <= 0 || tagSize.toLong() > MAX_ID3_TAG_SCAN_BYTES) return null
        val tagStart = raf.filePointer
        val tagEnd = tagStart + tagSize.toLong()
        if (tagEnd > chunkEnd) return null

        var fallback: Handle? = null
        while (raf.filePointer + 10L <= tagEnd) {
            val frameId = raf.readAscii(4)
            if (frameId.all { it == '\u0000' }) break
            if (!frameId.all { it.code in 0x20..0x7E }) return null

            val frameSize = if (majorVersion == 4) {
                raf.readSynchsafeInt() ?: return null
            } else {
                raf.readUInt32BeInt() ?: return null
            }
            raf.readUnsignedByte()
            val formatFlags = raf.readUnsignedByte()
            val frameStart = raf.filePointer
            val frameEnd = frameStart + frameSize.toLong()
            if (frameSize <= 0 || frameEnd > tagEnd) return null

            if (frameId == ID3_APIC && isDirectId3Frame(majorVersion, formatFlags)) {
                parseId3ApicFrame(
                    file = file,
                    sourceKey = sourceKey,
                    raf = raf,
                    frameStart = frameStart,
                    frameEnd = frameEnd,
                    frontFormat = "wav-front",
                    fallbackFormat = "wav-apic",
                )?.let { handle ->
                    if (handle.format == "wav-front") return handle
                    if (fallback == null) fallback = handle
                }
            }
            raf.seek(frameEnd)
        }
        return fallback
    }

    private fun parseId3ApicFrame(
        file: File,
        sourceKey: String,
        raf: RandomAccessFile,
        frameStart: Long,
        frameEnd: Long,
        frontFormat: String,
        fallbackFormat: String,
    ): Handle? {
        raf.seek(frameStart)
        if (raf.filePointer + 4L > frameEnd) return null
        val textEncoding = raf.readUnsignedByte()
        val declaredMime = raf.readNullTerminatedAscii(frameEnd, 256) ?: return null
        if (raf.filePointer >= frameEnd) return null
        val pictureType = raf.readUnsignedByte()

        val imageOffset = when (textEncoding) {
            0, 3 -> raf.skipSingleByteTerminatedField(frameEnd)
            1, 2 -> raf.skipUtf16TerminatedField(frameEnd)
            else -> null
        } ?: return null
        val imageLength = frameEnd - imageOffset
        if (imageLength <= MIN_ART_BYTES) return null
        val detectedMime = raf.sniffImageMime(imageOffset, imageLength) ?: return null

        return Handle(
            audioPath = file.absolutePath,
            sourceKey = sourceKey,
            offset = imageOffset,
            length = imageLength,
            mime = detectedMime.ifBlank { declaredMime.takeIf { it.isNotBlank() } ?: "application/octet-stream" },
            format = if (pictureType == 3) frontFormat else fallbackFormat,
        )
    }

    /**
     * APEv2 cover art is a binary item whose value is `filename\0<encoded image>`. The encoded
     * image portion is contiguous in the original .ape file, so list holders can sample it directly
     * just like FLAC instead of first extracting a temporary file.
     */
    private fun findApePicture(file: File, sourceKey: String): Handle? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val footerOffset = findApeFooterOffset(raf) ?: return null
                raf.seek(footerOffset)
                if (raf.readAscii(8) != APE_MAGIC) return null
                val version = raf.readUInt32Le() ?: return null
                val tagSize = raf.readUInt32Le() ?: return null
                val itemCount = raf.readUInt32Le() ?: return null
                raf.readUInt32Le() ?: return null // footer flags
                raf.seek(raf.filePointer + 8L) // reserved

                if (version < 1000L || tagSize < 32L || tagSize > MAX_APE_TAG_SCAN_BYTES) return null
                if (itemCount > MAX_APE_ITEMS.toLong()) return null
                val itemsStart = footerOffset + 32L - tagSize
                if (itemsStart < 0L || itemsStart > footerOffset) return null

                raf.seek(itemsStart)
                val maybeHeader = if (raf.filePointer + 8L <= footerOffset) raf.readAscii(8) else ""
                if (maybeHeader == APE_MAGIC) {
                    if (raf.filePointer + 24L > footerOffset) return null
                    raf.seek(raf.filePointer + 24L)
                } else {
                    raf.seek(itemsStart)
                }

                var fallback: Handle? = null
                repeat(itemCount.toInt()) {
                    if (raf.filePointer + 8L > footerOffset) return@repeat
                    val valueSize = raf.readUInt32Le() ?: return null
                    raf.readUInt32Le() ?: return null // item flags; key + payload validation is authoritative
                    if (valueSize <= 0L || valueSize > MAX_APE_TAG_SCAN_BYTES) return null

                    val key = raf.readNullTerminatedAscii(footerOffset, MAX_TEXT_FIELD_BYTES) ?: return null
                    val valueStart = raf.filePointer
                    val valueEnd = valueStart + valueSize
                    if (valueEnd > footerOffset) return null

                    if (key.startsWith("Cover Art", ignoreCase = true)) {
                        parseApeCoverItem(
                            file = file,
                            sourceKey = sourceKey,
                            raf = raf,
                            key = key,
                            valueStart = valueStart,
                            valueEnd = valueEnd,
                        )?.let { handle ->
                            if (key.equals("Cover Art (Front)", ignoreCase = true)) return handle
                            if (fallback == null) fallback = handle
                        }
                    }
                    raf.seek(valueEnd)
                }
                fallback
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findApeFooterOffset(raf: RandomAccessFile): Long? {
        if (raf.length() < 32L) return null
        val direct = raf.length() - 32L
        raf.seek(direct)
        if (raf.readAscii(8) == APE_MAGIC) return direct

        if (raf.length() >= 160L) {
            raf.seek(raf.length() - 128L)
            if (raf.readAscii(3) == "TAG") {
                val beforeId3v1 = raf.length() - 160L
                raf.seek(beforeId3v1)
                if (raf.readAscii(8) == APE_MAGIC) return beforeId3v1
            }
        }
        return null
    }

    private fun parseApeCoverItem(
        file: File,
        sourceKey: String,
        raf: RandomAccessFile,
        key: String,
        valueStart: Long,
        valueEnd: Long,
    ): Handle? {
        if (valueEnd - valueStart <= MIN_ART_BYTES) return null

        val directMime = raf.sniffImageMime(valueStart, valueEnd - valueStart)
        val imageOffset = if (directMime != null) {
            valueStart
        } else {
            raf.seek(valueStart)
            raf.skipSingleByteTerminatedField(valueEnd, MAX_TEXT_FIELD_BYTES) ?: return null
        }
        val imageLength = valueEnd - imageOffset
        if (imageLength <= MIN_ART_BYTES) return null
        val mime = raf.sniffImageMime(imageOffset, imageLength) ?: return null

        return Handle(
            audioPath = file.absolutePath,
            sourceKey = sourceKey,
            offset = imageOffset,
            length = imageLength,
            mime = mime,
            format = if (key.equals("Cover Art (Front)", ignoreCase = true)) "ape-front" else "ape-cover",
        )
    }

    private fun RandomAccessFile.readUInt24(): Int {
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun RandomAccessFile.readIntSafe(): Int? {
        return try {
            val value = readInt()
            if (value < 0) null else value
        } catch (_: EOFException) {
            null
        }
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun RandomAccessFile.readUInt64Le(): Long? {
        return try {
            var value = 0L
            repeat(8) { index ->
                value = value or (readUnsignedByte().toLong() shl (index * 8))
            }
            value.takeIf { it >= 0L }
        } catch (_: EOFException) {
            null
        }
    }

    private fun RandomAccessFile.readUInt32Le(): Long? {
        return try {
            var value = 0L
            repeat(4) { index ->
                value = value or (readUnsignedByte().toLong() shl (index * 8))
            }
            value
        } catch (_: EOFException) {
            null
        }
    }

    private fun RandomAccessFile.readUInt32BeInt(): Int? {
        return try {
            val value = readInt().toLong() and 0xFFFF_FFFFL
            value.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
        } catch (_: EOFException) {
            null
        }
    }

    private fun RandomAccessFile.readSynchsafeInt(): Int? {
        return try {
            val a = readUnsignedByte()
            val b = readUnsignedByte()
            val c = readUnsignedByte()
            val d = readUnsignedByte()
            if (((a or b or c or d) and 0x80) != 0) return null
            (a shl 21) or (b shl 14) or (c shl 7) or d
        } catch (_: EOFException) {
            null
        }
    }

    private fun RandomAccessFile.readNullTerminatedAscii(limit: Long, maxBytes: Int): String? {
        val out = ByteArray(maxBytes.coerceAtLeast(1))
        var count = 0
        while (filePointer < limit && count < out.size) {
            val value = readUnsignedByte()
            if (value == 0) return String(out, 0, count, Charsets.ISO_8859_1)
            out[count++] = value.toByte()
        }
        return null
    }

    private fun RandomAccessFile.skipSingleByteTerminatedField(
        limit: Long,
        maxBytes: Int = Int.MAX_VALUE,
    ): Long? {
        var count = 0
        while (filePointer < limit && count < maxBytes) {
            if (readUnsignedByte() == 0) return filePointer
            count++
        }
        return null
    }

    private fun RandomAccessFile.skipUtf16TerminatedField(limit: Long): Long? {
        var count = 0
        while (filePointer + 1L < limit && count < MAX_TEXT_FIELD_BYTES) {
            val first = readUnsignedByte()
            val second = readUnsignedByte()
            if (first == 0 && second == 0) return filePointer
            count += 2
        }
        return null
    }

    private fun RandomAccessFile.sniffImageMime(offset: Long, length: Long): String? {
        if (offset < 0L || length < 8L || offset + minOf(length, 12L) > this.length()) return null
        val saved = filePointer
        return try {
            seek(offset)
            val probeSize = minOf(length, 12L).toInt()
            val bytes = ByteArray(probeSize)
            readFully(bytes)
            when {
                probeSize >= 3 &&
                    (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xFF) == 0xD8 &&
                    (bytes[2].toInt() and 0xFF) == 0xFF -> "image/jpeg"
                probeSize >= 8 &&
                    bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                    bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
                    bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                    bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"
                probeSize >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).let {
                    it == "GIF87a" || it == "GIF89a"
                } -> "image/gif"
                probeSize >= 12 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            seek(saved)
        }
    }

    private fun sourceVersionKey(file: File): String {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) throw EOFException("Cannot skip to embedded artwork region")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private class BoundedInputStream(
        input: InputStream,
        private var remaining: Long
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val result = super.read()
            if (result >= 0) remaining--
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val max = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, max)
            if (read > 0) remaining -= read.toLong()
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, remaining))
            if (skipped > 0L) remaining -= skipped
            return skipped
        }
    }
}
