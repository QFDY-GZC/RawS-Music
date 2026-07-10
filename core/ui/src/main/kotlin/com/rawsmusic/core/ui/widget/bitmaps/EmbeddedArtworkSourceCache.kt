package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-style source-art owner.
 *
 * The design separates the expensive source probe from individual UI bitmap tiers: once an embedded
 * picture is found for a source record, low/high/full requests can sample from that same source
 * handle instead of re-opening the audio file and re-extracting the embedded picture. This cache is
 * the RawSMusic bridge toward that model.
 *
 * It is intentionally source-version keyed. For local files the caller passes the same canonical
 * key used by ArtworkRecordRegistry/BitmapProvider: path + length + lastModified. If the user edits
 * embedded artwork, the source key changes naturally; explicit invalidation also deletes matching
 * handles so late workers cannot resurrect stale source art.
 */
object EmbeddedArtworkSourceCache {
    private const val TAG = "EmbeddedArtSource"
    private const val MIN_ART_BYTES = 1024L
    private const val DIR_NAME = "albumart_sources"

    data class Handle(
        val sourceKey: String,
        val audioPath: String,
        val file: File,
        val bytes: Long,
        val mime: String?,
        val reused: Boolean
    ) {
        val filePath: String get() = file.absolutePath
    }

    private val locks = ConcurrentHashMap<String, Any>()

    fun prepare(
        context: Context,
        audioPath: String,
        sourceKey: String,
        extractor: (audioPath: String, outputPath: String) -> Boolean
    ): Handle? {
        if (audioPath.isBlank() || sourceKey.isBlank()) return null
        val sourceFile = File(audioPath)
        if (!sourceFile.exists() || !sourceFile.canRead()) return null

        val dir = sourceDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, "src_${stableDigest(sourceKey)}.art")

        existingHandle(sourceKey, audioPath, target, reused = true)?.let { return it }

        val lock = locks.getOrPut(sourceKey) { Any() }
        return synchronized(lock) {
            try {
                existingHandle(sourceKey, audioPath, target, reused = true)?.let { return@synchronized it }

                val tmp = File(target.parentFile, "${target.name}.tmp")
                val nativeTmp = File(target.parentFile, "${target.name}.tmp.tmp")
                if (tmp.exists()) tmp.delete()
                if (nativeTmp.exists()) nativeTmp.delete()
                if (target.exists() && target.length() <= MIN_ART_BYTES) target.delete()

                val ok = extractor(audioPath, tmp.absolutePath)
                if (!ok || !tmp.exists() || tmp.length() <= MIN_ART_BYTES) {
                    tmp.delete()
                    nativeTmp.delete()
                    null
                } else {
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        null
                    } else {
                        existingHandle(sourceKey, audioPath, target, reused = false)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "prepare failed: ${audioPath.takeLast(80)}, ${e.message}")
                null
            } finally {
                locks.remove(sourceKey, lock)
            }
        }
    }

    fun removeForSources(context: Context?, sourceKeys: Collection<String>): Int {
        if (context == null || sourceKeys.isEmpty()) return 0
        val dir = sourceDir(context)
        var removed = 0
        for (sourceKey in sourceKeys) {
            if (sourceKey.isBlank()) continue
            locks.remove(sourceKey)
            val file = File(dir, "src_${stableDigest(sourceKey)}.art")
            val tmp = File(dir, "${file.name}.tmp")
            val nativeTmp = File(dir, "${file.name}.tmp.tmp")
            if (file.exists() && file.delete()) removed++
            if (tmp.exists() && tmp.delete()) removed++
            if (nativeTmp.exists() && nativeTmp.delete()) removed++
        }
        return removed
    }

    fun clear(context: Context?) {
        locks.clear()
        if (context == null) return
        val dir = sourceDir(context)
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.startsWith("src_") || file.name.endsWith(".tmp"))) {
                runCatching { file.delete() }
            }
        }
    }

    private fun existingHandle(
        sourceKey: String,
        audioPath: String,
        file: File,
        reused: Boolean
    ): Handle? {
        if (!file.exists() || file.length() <= MIN_ART_BYTES) return null
        return Handle(
            sourceKey = sourceKey,
            audioPath = audioPath,
            file = file,
            bytes = file.length(),
            mime = sniffMime(file),
            reused = reused
        )
    }

    private fun sourceDir(context: Context): File = File(context.cacheDir, DIR_NAME)

    private fun sniffMime(file: File): String? {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read < 4) return null
                when {
                    header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "image/jpeg"
                    header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "image/png"
                    header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() -> "image/gif"
                    header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte() -> "image/webp"
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stableDigest(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
