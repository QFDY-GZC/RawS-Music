package com.rawsmusic.core.common.sacd

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Preserve every byte except container regions known to contain encoded audio. */
internal object SacdMetadataRegions {
    data class Region(val offset: Long, val length: Long)
    data class Plan(val size: Long, val extension: String, val regions: List<Region>)
    private const val MAX_REGIONS = 131_072

    fun plan(file: File): Plan = RandomAccessFile(file, "r").use { input ->
        val size = input.length()
        require(size > 0)
        val extension = file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9+]{1,12}")) }
            ?: throw IOException("Unsupported metadata snapshot extension")
        val excluded = runCatching { audioRegions(input, size, extension) }.getOrDefault(emptyList())
        // Unknown structures are retained in full; never infer that unknown bytes are disposable.
        val ordered = excluded.filter { it.length > 0 && it.offset >= 0 && it.offset <= size && it.length <= size - it.offset }
            .sortedBy { it.offset }
        val retained = ArrayList<Region>()
        var position = 0L
        for (range in ordered) {
            if (range.offset > position) retained.add(Region(position, range.offset - position))
            position = maxOf(position, range.offset + range.length)
        }
        if (position < size) retained.add(Region(position, size - position))
        Plan(size, extension, if (retained.size > MAX_REGIONS || retained.isEmpty()) listOf(Region(0, size)) else retained)
    }

    private fun four(input: RandomAccessFile, at: Long): String {
        input.seek(at); val bytes = ByteArray(4); input.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
    private fun u32(input: RandomAccessFile, at: Long, little: Boolean = false): Long {
        input.seek(at); val value = input.readInt()
        return (if (little) Integer.reverseBytes(value) else value).toLong() and 0xffff_ffffL
    }
    private fun u64(input: RandomAccessFile, at: Long, little: Boolean = false): Long {
        input.seek(at); val value = input.readLong()
        return if (little) java.lang.Long.reverseBytes(value) else value
    }
    private fun id3End(input: RandomAccessFile, size: Long): Long {
        if (size < 10 || !four(input, 0).startsWith("ID3")) return 0
        input.seek(6); var length = 0L
        repeat(4) { val byte = input.readUnsignedByte(); require(byte < 128); length = (length shl 7) or byte.toLong() }
        input.seek(5)
        val footer = if (input.readUnsignedByte() and 0x10 != 0) 10 else 0
        return (10 + length + footer).also { require(it <= size) }
    }
    private fun tailStart(input: RandomAccessFile, size: Long): Long {
        var end = size
        if (end >= 128 && four(input, end - 128).startsWith("TAG")) end -= 128
        if (end >= 32 && four(input, end - 32) == "APET") {
            input.seek(end - 32); val signature = ByteArray(8); input.readFully(signature)
            if (String(signature, Charsets.US_ASCII) == "APETAGEX") {
                val bytes = u32(input, end - 20, true)
                if (bytes in 32..end) {
                    end -= bytes
                    if (end >= 32 && four(input, end - 32) == "APET") end -= 32
                }
            }
        }
        return end
    }

    private fun audioRegions(input: RandomAccessFile, size: Long, ext: String): List<Region> {
        val start = id3End(input, size)
        val signature = four(input, start)
        if (signature == "fLaC") {
            var cursor = start + 4
            repeat(MAX_REGIONS) {
                input.seek(cursor); val type = input.readUnsignedByte()
                val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                cursor += 4L + length
                require(cursor <= size)
                if (type and 128 != 0) return listOf(Region(cursor, (tailStart(input, size) - cursor).coerceAtLeast(0)))
            }
            throw IOException("FLAC metadata block limit")
        }
        if (signature == "DSD ") {
            require(u64(input, start + 4, true) == 28L && four(input, start + 28) == "fmt ")
            val fmtSize = u64(input, start + 32, true); require(fmtSize in 52..(size - start - 28))
            val data = start + 28 + fmtSize
            require(four(input, data) == "data")
            val length = u64(input, data + 4, true); require(length >= 12 && length <= size - data)
            return listOf(Region(data + 12, length - 12))
        }
        if (signature in listOf("RIFF", "RIFX", "RF64", "FORM")) {
            val little = signature != "FORM" && signature != "RIFX"
            var cursor = start + 12; var rf64Data = 0L
            val result = ArrayList<Region>()
            while (cursor + 8 <= size && result.size <= MAX_REGIONS) {
                val kind = four(input, cursor); var length = u32(input, cursor + 4, little)
                if (kind == "ds64" && signature == "RF64" && length >= 28) rf64Data = u64(input, cursor + 16, true)
                if (kind == "data" && length == 0xffff_ffffL && rf64Data > 0) length = rf64Data
                require(length <= size - cursor - 8)
                if (kind == "data") result.add(Region(cursor + 8, length))
                if (kind == "SSND") {
                    require(length >= 8)
                    val offset = u32(input, cursor + 8); require(offset <= length - 8)
                    result.add(Region(cursor + 16 + offset, length - 8 - offset))
                }
                cursor += 8 + length + length % 2
            }
            return result
        }
        if (signature == "FRM8") {
            require(four(input, start + 12) == "DSD ")
            val result = ArrayList<Region>(); var cursor = start + 16
            while (cursor + 12 <= size && result.size <= MAX_REGIONS) {
                val kind = four(input, cursor); val length = u64(input, cursor + 4)
                require(length >= 0 && length <= size - cursor - 12)
                if (kind == "DSD ") result.add(Region(cursor + 12, length))
                if (kind == "DST ") {
                    var child = cursor + 12; val end = child + length
                    while (child + 12 <= end) {
                        val childKind = four(input, child); val childLength = u64(input, child + 4)
                        require(childLength >= 0 && childLength <= end - child - 12)
                        if (childKind == "DSTF") result.add(Region(child + 12, childLength))
                        child += 12 + childLength + childLength % 2
                    }
                }
                cursor += 12 + length + length % 2
            }
            return result
        }
        if (size - start >= 8 && four(input, start + 4) == "ftyp") {
            val result = ArrayList<Region>(); var cursor = start
            while (cursor + 8 <= size && result.size <= MAX_REGIONS) {
                val kind = four(input, cursor + 4); val short = u32(input, cursor)
                val header = if (short == 1L) 16L else 8L
                val length = if (short == 1L) u64(input, cursor + 8) else if (short == 0L) size - cursor else short
                require(length >= header && length <= size - cursor)
                if (kind == "mdat") result.add(Region(cursor + header, length - header))
                cursor += length
            }
            return result
        }
        if (signature == "OggS" && ext in setOf("ogg", "opus")) {
            val headersNeeded = if (ext == "opus") 2 else 3
            val result = ArrayList<Region>(); var cursor = 0L; var headers = 0; var serial: Long? = null
            while (cursor + 27 <= size && result.size <= MAX_REGIONS) {
                require(four(input, cursor) == "OggS")
                val pageSerial = u32(input, cursor + 14, true)
                if (serial == null) serial = pageSerial
                input.seek(cursor + 26); val count = input.readUnsignedByte()
                val lacing = ByteArray(count); input.readFully(lacing)
                val bodySize = lacing.sumOf { it.toInt() and 255 }
                val body = cursor + 27 + count; require(bodySize <= size - body)
                if (pageSerial == serial) {
                    if (headers >= headersNeeded) result.add(Region(body, bodySize.toLong()))
                    else headers += lacing.count { (it.toInt() and 255) < 255 }
                }
                cursor = body + bodySize
            }
            return result
        }
        // MPEG frames aren't needed for metadata parsing, but preserve a first-frame window for
        // readers that sniff MPEG geometry even with readProperties=false.
        if (ext in setOf("mp3", "mp2", "mp1", "mpga", "aac")) {
            val audioStart = minOf(start + 4096, size)
            return listOf(Region(audioStart, (tailStart(input, size) - audioStart).coerceAtLeast(0)))
        }
        // ASF may multiplex timed metadata into Data packets; retain the entire container.
        return emptyList()
    }
}
