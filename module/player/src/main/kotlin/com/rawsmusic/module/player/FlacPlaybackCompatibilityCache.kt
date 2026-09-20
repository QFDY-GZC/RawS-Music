package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Runtime compatibility for malformed local FLAC files whose first metadata chain is already
 * marked "last", but is followed by another PICTURE/PADDING metadata chain before audio frames.
 *
 * Playback uses a prebuilt librawsmusic_ffmpeg.so, so changing the reference ffmpeg_bridge.cpp
 * does not affect the decoder actually shipped in the APK. This class instead materializes a
 * cache-only virtual copy which omits the orphan metadata range. The original media file is never
 * modified and the encoded FLAC audio bytes are copied unchanged.
 */
internal class FlacPlaybackCompatibilityCache(
    private val cacheDirectory: File,
    private val tag: String,
) {
    internal data class OrphanMetadataRange(
        val skipStart: Long,
        val skipEnd: Long,
        val orphanBlockCount: Int,
    ) {
        val skippedBytes: Long get() = skipEnd - skipStart
    }

    private data class SourceSnapshot(
        val identity: String,
        val length: Long,
        val lastModified: Long,
        val range: OrphanMetadataRange,
    )

    private val resolvedCache = HashMap<String, String>()

    @Synchronized
    fun resolve(sourcePath: String, sourceIdentity: String = sourcePath): String {
        if (sourcePath.isBlank() || isRemote(sourcePath)) return sourcePath

        val source = File(sourcePath)
        val sourceLength = runCatching { source.length() }.getOrDefault(0L)
        if (sourceLength < MIN_FLAC_SIZE) return sourcePath

        val memoryKey = "$sourceIdentity\u0000$sourcePath\u0000$sourceLength\u0000${source.lastModified()}"
        resolvedCache[memoryKey]?.let { cachedPath ->
            val cached = File(cachedPath)
            if (cached.isFile && cached.length() > 0L) {
                cached.setLastModified(System.currentTimeMillis())
                return cached.absolutePath
            }
            resolvedCache.remove(memoryKey)
        }

        val range = runCatching {
            RandomAccessFile(source, "r").use(::detectOrphanMetadataRange)
        }.onFailure { error ->
            AppLogger.w(tag, "FLAC_COMPAT detect failed path=$sourcePath", error)
        }.getOrNull() ?: return sourcePath

        val snapshot = SourceSnapshot(
            identity = sourceIdentity,
            length = sourceLength,
            lastModified = source.lastModified(),
            range = range,
        )
        val expectedLength = snapshot.length - range.skippedBytes
        if (expectedLength < MIN_FLAC_SIZE) return sourcePath

        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs() && !cacheDirectory.isDirectory) {
            AppLogger.w(tag, "FLAC_COMPAT cache directory unavailable: ${cacheDirectory.absolutePath}")
            return sourcePath
        }

        pruneCache(requiredBytes = expectedLength)
        if (cacheDirectory.usableSpace in 1 until (expectedLength + MIN_FREE_SPACE_BYTES)) {
            AppLogger.w(
                tag,
                "FLAC_COMPAT insufficient cache space required=$expectedLength usable=${cacheDirectory.usableSpace}"
            )
            return sourcePath
        }

        val target = File(cacheDirectory, "${cacheKey(snapshot)}.flac")
        if (isValidCachedCopy(target, expectedLength, range.skipStart)) {
            target.setLastModified(System.currentTimeMillis())
            resolvedCache[memoryKey] = target.absolutePath
            AppLogger.i(
                tag,
                "FLAC_COMPAT cache hit path=${source.name} skip=[${range.skipStart},${range.skipEnd}) " +
                    "blocks=${range.orphanBlockCount}"
            )
            return target.absolutePath
        }

        val temporary = File(cacheDirectory, ".${target.name}.${System.nanoTime()}.tmp")
        val created = runCatching {
            materializeVirtualCopy(source, temporary, range)
            if (!isValidCachedCopy(temporary, expectedLength, range.skipStart)) {
                error("generated compatibility copy failed validation")
            }
            if (target.exists() && !target.delete() && target.exists()) {
                error("could not replace stale compatibility cache")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                if (!temporary.delete()) temporary.deleteOnExit()
            }
            if (!isValidCachedCopy(target, expectedLength, range.skipStart)) {
                error("installed compatibility copy failed validation")
            }
            true
        }.onFailure { error ->
            AppLogger.e(
                tag,
                "FLAC_COMPAT materialize failed path=$sourcePath " +
                    "skip=[${range.skipStart},${range.skipEnd})",
                error,
            )
        }.getOrDefault(false)

        if (!created) {
            temporary.delete()
            target.takeIf { !isValidCachedCopy(it, expectedLength, range.skipStart) }?.delete()
            return sourcePath
        }

        target.setLastModified(System.currentTimeMillis())
        resolvedCache[memoryKey] = target.absolutePath
        AppLogger.i(
            tag,
            "FLAC_COMPAT materialized original=${source.name} cache=${target.name} " +
                "sourceBytes=${snapshot.length} virtualBytes=$expectedLength " +
                "skip=[${range.skipStart},${range.skipEnd}) blocks=${range.orphanBlockCount}"
        )
        return target.absolutePath
    }

    private fun pruneCache(requiredBytes: Long) {
        val files = cacheDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("flac", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var retainedBytes = files.sumOf { it.length() }
        var retainedFiles = files.size
        for (file in files) {
            val tooMany = retainedFiles >= MAX_CACHE_FILES
            val tooLarge = retainedBytes + requiredBytes > MAX_CACHE_BYTES
            if (!tooMany && !tooLarge) break
            val fileBytes = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                retainedBytes -= fileBytes
                retainedFiles -= 1
            }
        }
    }

    private fun cacheKey(snapshot: SourceSnapshot): String {
        val raw = buildString {
            append(snapshot.identity)
            append('\u0000')
            append(snapshot.length)
            append('\u0000')
            append(snapshot.lastModified)
            append('\u0000')
            append(snapshot.range.skipStart)
            append('\u0000')
            append(snapshot.range.skipEnd)
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(raw)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(32)
    }

    private fun isValidCachedCopy(file: File, expectedLength: Long, audioStart: Long): Boolean {
        if (!file.isFile || file.length() != expectedLength) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                hasFlacMarker(input) && isFlacFrameSyncAt(input, audioStart)
            }
        }.getOrDefault(false)
    }

    private fun materializeVirtualCopy(
        source: File,
        target: File,
        range: OrphanMetadataRange,
    ) {
        RandomAccessFile(source, "r").use { input ->
            FileOutputStream(target).use { fileOutput ->
                BufferedOutputStream(fileOutput, COPY_BUFFER_SIZE).use { output ->
                    copyRange(input, output, start = 0L, byteCount = range.skipStart)
                    copyRange(
                        input,
                        output,
                        start = range.skipEnd,
                        byteCount = input.length() - range.skipEnd,
                    )
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
    }

    private fun copyRange(
        input: RandomAccessFile,
        output: BufferedOutputStream,
        start: Long,
        byteCount: Long,
    ) {
        require(start >= 0L && byteCount >= 0L)
        input.seek(start)
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = byteCount
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, requested)
            if (read < 0) error("unexpected EOF while creating FLAC compatibility cache")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    companion object {
        private const val MIN_FLAC_SIZE = 8L
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val MAX_METADATA_BLOCKS = 512
        private const val MAX_ORPHAN_BLOCKS = 64
        private const val MAX_ORPHAN_BYTES = 128L * 1024L * 1024L
        private const val MAX_CACHE_FILES = 8
        private const val MAX_CACHE_BYTES = 768L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L

        internal fun detectOrphanMetadataRange(
            input: RandomAccessFile,
        ): OrphanMetadataRange? {
            val fileSize = input.length()
            if (fileSize < MIN_FLAC_SIZE || !hasFlacMarker(input)) return null

            var position = 4L
            var firstDeclaredEnd = -1L
            var orphanBlockCount = 0

            repeat(MAX_METADATA_BLOCKS) {
                val header = readMetadataHeader(input, position, fileSize) ?: return null
                val blockEnd = position + 4L + header.length

                if (firstDeclaredEnd < 0L) {
                    if (header.isLast) {
                        firstDeclaredEnd = blockEnd
                        if (isFlacFrameSyncAt(input, blockEnd)) return null
                    }
                } else {
                    // STREAMINFO is only valid as the first metadata block. Excluding it here makes
                    // the recovery deliberately narrow and avoids mistaking arbitrary audio bytes
                    // for a second stream.
                    if (header.type == 0) return null
                    orphanBlockCount += 1
                    val skippedBytes = blockEnd - firstDeclaredEnd
                    if (orphanBlockCount > MAX_ORPHAN_BLOCKS || skippedBytes > MAX_ORPHAN_BYTES) {
                        return null
                    }
                    if (header.isLast && isFlacFrameSyncAt(input, blockEnd)) {
                        return OrphanMetadataRange(
                            skipStart = firstDeclaredEnd,
                            skipEnd = blockEnd,
                            orphanBlockCount = orphanBlockCount,
                        )
                    }
                }
                position = blockEnd
            }
            return null
        }

        private data class MetadataHeader(
            val isLast: Boolean,
            val type: Int,
            val length: Long,
        )

        private fun readMetadataHeader(
            input: RandomAccessFile,
            offset: Long,
            fileSize: Long,
        ): MetadataHeader? {
            if (offset < 0L || offset + 4L > fileSize) return null
            input.seek(offset)
            val first = input.read()
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (first < 0 || b1 < 0 || b2 < 0 || b3 < 0) return null
            val type = first and 0x7f
            if (type !in 0..6) return null
            val length = ((b1.toLong() and 0xffL) shl 16) or
                ((b2.toLong() and 0xffL) shl 8) or
                (b3.toLong() and 0xffL)
            val end = offset + 4L + length
            if (end < offset || end > fileSize) return null
            return MetadataHeader(
                isLast = first and 0x80 != 0,
                type = type,
                length = length,
            )
        }

        private fun hasFlacMarker(input: RandomAccessFile): Boolean {
            if (input.length() < 4L) return false
            input.seek(0L)
            return input.read() == 'f'.code &&
                input.read() == 'L'.code &&
                input.read() == 'a'.code &&
                input.read() == 'C'.code
        }

        private fun isFlacFrameSyncAt(input: RandomAccessFile, offset: Long): Boolean {
            if (offset < 0L || offset + 2L > input.length()) return false
            input.seek(offset)
            val first = input.read()
            val second = input.read()
            return first == 0xff && second >= 0 && second and 0xfe == 0xf8
        }

        private fun isRemote(path: String): Boolean =
            path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true)
    }
}
