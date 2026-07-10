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
 * Phase 5e intentionally starts with FLAC, because FLAC METADATA_BLOCK_PICTURE stores the encoded
 * image bytes as a contiguous payload with a reliable offset/length. MP3/MP4/DSF stay on the
 * TagLib/source-art fallback until their frame/atom unsynchronisation and transform rules are fully
 * verified.
 */
object EmbeddedArtworkRegion {
    private const val MIN_ART_BYTES = 1024L
    private const val FLAC_MAGIC = "fLaC"
    private const val FLAC_BLOCK_PICTURE = 6
    private const val MAX_FLAC_METADATA_SCAN_BYTES = 64L * 1024L * 1024L

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
