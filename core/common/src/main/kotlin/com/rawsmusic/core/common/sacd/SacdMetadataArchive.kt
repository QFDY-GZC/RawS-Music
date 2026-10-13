package com.rawsmusic.core.common.sacd

import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** Raw metadata, including unsupported native tags, embedded outside the SACD audio area. */
object SacdMetadataArchive {
    private val MAGIC = "RAWSMETA".toByteArray(Charsets.US_ASCII)
    private val SNAPSHOT = "TAGSNAP1".toByteArray(Charsets.US_ASCII)
    private const val FOOTER_BYTES = 64L
    private const val BUFFER_BYTES = 65536
    private const val CACHE_BUDGET = 256L * 1024 * 1024
    data class Descriptor(val payloadOffset: Long, val payloadBytes: Long, val sha256: ByteArray)

    fun estimatedPayloadBytes(source: File): Long {
        val plan = SacdMetadataRegions.plan(source)
        return 32L + plan.regions.sumOf { 16 + it.length } + 2048
    }
    private fun checkCancelled(test: () -> Boolean) {
        if (test()) throw CancellationException("SACD metadata migration cancelled")
    }
    private fun emit(source: File, output: OutputStream, cancelled: () -> Boolean) {
        val plan = SacdMetadataRegions.plan(source)
        val beforeModified = source.lastModified()
        val data = DataOutputStream(output)
        data.write(SNAPSHOT); data.writeLong(plan.size); data.writeUTF(plan.extension); data.writeInt(plan.regions.size)
        val buffer = ByteArray(BUFFER_BYTES)
        RandomAccessFile(source, "r").use { input ->
            for (region in plan.regions) {
                checkCancelled(cancelled)
                data.writeLong(region.offset); data.writeLong(region.length)
                input.seek(region.offset); var remaining = region.length
                while (remaining > 0) {
                    checkCancelled(cancelled)
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count); data.write(buffer, 0, count); remaining -= count
                }
            }
        }
        data.flush()
        if (source.length() != plan.size || source.lastModified() != beforeModified) throw IOException("Source metadata changed during migration")
    }

    fun append(source: File, iso: File, isCancelled: () -> Boolean = { false }): Descriptor {
        require(source.canonicalFile != iso.canonicalFile)
        checkCancelled(isCancelled)
        RandomAccessFile(iso, "rw").use { target ->
            val originalSize = target.length()
            require(originalSize >= 511 * 2048L && originalSize % 2048 == 0L)
            target.seek(510 * 2048L); val signature = ByteArray(8); target.readFully(signature)
            require(String(signature, Charsets.US_ASCII) == "SACDMTOC")
            require(describe(iso) == null) { "SACD metadata already present" }
            try {
                target.seek(originalSize)
                val digest = MessageDigest.getInstance("SHA-256")
                val stream = object : OutputStream() {
                    override fun write(value: Int) { target.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { target.write(bytes, offset, length) }
                }
                emit(source, DigestOutputStream(stream, digest), isCancelled)
                val length = target.filePointer - originalSize
                val hash = digest.digest()
                val padding = ((2048 - (target.filePointer + FOOTER_BYTES) % 2048) % 2048).toInt()
                target.write(ByteArray(padding)); target.write(MAGIC); target.writeInt(1)
                target.writeLong(originalSize); target.writeLong(length); target.write(hash); target.writeInt(0)
                return Descriptor(originalSize, length, hash)
            } catch (error: Throwable) {
                target.setLength(originalSize)
                throw error
            }
        }
    }

    fun describe(iso: File): Descriptor? = RandomAccessFile(iso, "r").use { input ->
        if (input.length() < FOOTER_BYTES) return null
        input.seek(input.length() - FOOTER_BYTES)
        val magic = ByteArray(8); input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        if (input.readInt() != 1) throw IOException("Unsupported SACD metadata version")
        val offset = input.readLong(); val length = input.readLong(); val hash = ByteArray(32); input.readFully(hash)
        if (offset < 511 * 2048L || length < 24 || offset > input.length() - FOOTER_BYTES ||
            length > input.length() - FOOTER_BYTES - offset) throw IOException("Invalid SACD metadata bounds")
        Descriptor(offset, length, hash)
    }

    fun verify(source: File, iso: File): Boolean = runCatching {
        val descriptor = describe(iso) ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        emit(source, DigestOutputStream(object : OutputStream() { override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {} }, digest), { false })
        if (!digest.digest().contentEquals(descriptor.sha256)) return false
        val actual = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER_BYTES)
        RandomAccessFile(iso, "r").use { input ->
            input.seek(descriptor.payloadOffset); var remaining = descriptor.payloadBytes
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count); actual.update(buffer, 0, count); remaining -= count
            }
        }
        actual.digest().contentEquals(descriptor.sha256)
    }.getOrDefault(false)

    @Synchronized fun restore(iso: File, cacheDirectory: File): File? {
        val descriptor = describe(iso) ?: return null
        RandomAccessFile(iso, "r").use { input ->
            input.seek(descriptor.payloadOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            var consumed = 0L
            val raw = object : InputStream() {
                override fun read(): Int {
                    if (consumed >= descriptor.payloadBytes) return -1
                    val value = input.read(); if (value < 0) throw EOFException()
                    digest.update(value.toByte()); consumed++; return value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (consumed >= descriptor.payloadBytes) return -1
                    val count = minOf(length.toLong(), descriptor.payloadBytes - consumed).toInt()
                    input.readFully(bytes, offset, count); digest.update(bytes, offset, count); consumed += count; return count
                }
            }
            val data = DataInputStream(raw)
            val magic = ByteArray(8); data.readFully(magic)
            if (!magic.contentEquals(SNAPSHOT)) throw IOException("Invalid SACD metadata snapshot")
            val originalSize = data.readLong(); val extension = data.readUTF(); val count = data.readInt()
            if (originalSize <= 0 || !extension.matches(Regex("[a-z0-9+]{1,12}")) || count !in 1..131072)
                throw IOException("Invalid SACD metadata source layout")
            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw IOException("Cannot create SACD metadata cache")
            val hex = descriptor.sha256.joinToString("") { "%02x".format(it.toInt() and 255) }
            val restored = File(cacheDirectory, "$hex-${descriptor.payloadBytes.toString(16)}.$extension")
            if (restored.isFile && restored.length() == originalSize) {
                restored.setLastModified(System.currentTimeMillis())
                return restored
            }
            val partial = File(cacheDirectory, ".${UUID.randomUUID()}.part")
            try {
                RandomAccessFile(partial, "rw").use { target ->
                    target.setLength(originalSize) // Sparse holes represent omitted encoded audio.
                    val buffer = ByteArray(BUFFER_BYTES); var previousEnd = 0L
                    repeat(count) {
                        val offset = data.readLong(); val length = data.readLong()
                        if (offset < previousEnd || offset > originalSize || length <= 0 || length > originalSize - offset ||
                            length > descriptor.payloadBytes - consumed) throw IOException("Invalid metadata region")
                        target.seek(offset); var remaining = length
                        while (remaining > 0) {
                            val read = minOf(remaining, buffer.size.toLong()).toInt()
                            data.readFully(buffer, 0, read); target.write(buffer, 0, read); remaining -= read
                        }
                        previousEnd = offset + length
                    }
                    if (consumed != descriptor.payloadBytes || !digest.digest().contentEquals(descriptor.sha256))
                        throw IOException("SACD metadata checksum mismatch")
                }
                if (!partial.renameTo(restored)) throw IOException("Cannot publish SACD metadata cache")
                trimCache(cacheDirectory, restored)
                return restored
            } finally { partial.delete() }
        }
    }

    private fun trimCache(directory: File, current: File) {
        val files = directory.listFiles().orEmpty().filter { it != current && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
        fun weight(file: File) = file.nameWithoutExtension.substringAfterLast('-').toLongOrNull(16) ?: file.length()
        var bytes = weight(current); var count = 1
        for (file in files) {
            if (count >= 16 || weight(file) > (CACHE_BUDGET - bytes).coerceAtLeast(0)) file.delete()
            else { count++; bytes += weight(file) }
        }
    }
}
