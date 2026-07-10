package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-level artwork source index, mirroring the artwork resolver behavior where an artwork
 * record can point at an already-known source path and future requests do not have to reopen each
 * audio file just to rediscover the same cover.
 *
 * This is intentionally conservative: it only indexes reusable image-file sources such as
 * folder.jpg / cover.jpg or a native-extracted embedded-art cache file. Byte-array fallbacks are
 * still served by disk thumbnails, and terminal no-art is kept on file-version keys rather than a
 * broad album alias until the scanner can prove the whole entity is no-art.
 */
internal object ArtworkSourceIndex {
    private data class SourceRecord(
        val sourcePath: String,
        val updatedAtMs: Long
    )

    private val sourceByProviderKey = ConcurrentHashMap<String, SourceRecord>()

    fun sourcePathFor(providerKey: String): String? {
        if (providerKey.isBlank()) return null
        val record = sourceByProviderKey[providerKey] ?: return null
        val file = File(record.sourcePath)
        return if (file.exists() && file.canRead() && file.length() > 1024L) {
            record.sourcePath
        } else {
            sourceByProviderKey.remove(providerKey, record)
            null
        }
    }

    fun rememberSource(providerKey: String, sourcePath: String) {
        if (providerKey.isBlank() || sourcePath.isBlank()) return
        val file = File(sourcePath)
        if (!file.exists() || !file.canRead() || file.length() <= 1024L) return
        sourceByProviderKey[providerKey] = SourceRecord(
            sourcePath = file.absolutePath,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun remove(providerKey: String): Boolean {
        if (providerKey.isBlank()) return false
        return sourceByProviderKey.remove(providerKey) != null
    }

    fun removeAll(keys: Collection<String>): Int {
        var removed = 0
        keys.forEach { key -> if (remove(key)) removed++ }
        return removed
    }

    fun clear() {
        sourceByProviderKey.clear()
    }
}
