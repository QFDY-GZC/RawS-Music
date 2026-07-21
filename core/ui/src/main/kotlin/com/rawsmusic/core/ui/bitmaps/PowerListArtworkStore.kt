package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.LinkedHashMap

/**
 * Project-style file-identity artwork id for the PowerList path.
 *
 * This deliberately does not invent album/folder/entity aliases.  Until RawSMusic has a scanner
 * backed artwork type/id resolver, the only stable identity for list/grid artwork is the song's own
 * versioned cover key.  The same id is used by the cell, record, provider request, and cache probe.
 */
data class FileArtworkId(val value: String) {
    val isBlank: Boolean get() = value.isBlank()

    companion object {
        fun fromCoverKey(key: String): FileArtworkId = FileArtworkId(key.trim())
    }
}

/**
 * Minimal artwork-provider-style record store for PowerList cells.
 *
 * The design keeps one owned artwork record per file identity, and the view binds only that record. This store gives
 * RawSMusic the same shape for the current file-identity mode: one ArtworkRecord per FileArtworkId,
 * with optional low/high owners.  Compose cells may acquire a drawable handle from the record, but
 * they do not become their own source/album cache and they do not reuse artwork across identities.
 */
object PowerListArtworkRecords {
    private const val MAX_RECORDS = 768
    private const val NOT_FOUND_TTL_MS = 30 * 60 * 1000L

    private val lock = Any()
    private val records = object : LinkedHashMap<String, ArtworkRecord>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkRecord>?): Boolean {
            val shouldRemove = size > MAX_RECORDS
            if (shouldRemove) eldest?.value?.release()
            return shouldRemove
        }
    }

    fun acquire(
        id: FileArtworkId,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.List,
        providerAliasKey: String = ""
    ): ArtworkHandle? {
        if (id.isBlank) return null
        synchronized(lock) {
            records[id.value]?.acquire(targetWidth, targetHeight, surface)?.let { return it }
        }

        val exact = BitmapProvider.acquireThumbnail(
            key = id.value,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            surface = surface,
            providerAliasKey = providerAliasKey
        )
        if (exact != null && exact.isValid) {
            publishHandle(id, exact, targetWidth, targetHeight)
            return synchronized(lock) {
                records[id.value]?.acquire(targetWidth, targetHeight, surface)
            }
        }
        exact?.release()

        val any = BitmapProvider.acquireAny(
            key = id.value,
            surface = surface,
            providerAliasKey = providerAliasKey
        )
        if (any != null && any.isValid) {
            publishHandle(id, any, targetWidth, targetHeight)
            return synchronized(lock) {
                records[id.value]?.acquire(targetWidth, targetHeight, surface)
            }
        }
        any?.release()
        return null
    }

    fun publishBitmap(
        id: FileArtworkId,
        bitmap: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        high: Boolean = false
    ): ArtworkHandle? {
        if (id.isBlank || bitmap == null || bitmap.isRecycled) return null
        val owner = BitmapProvider.acquireLoaded(
            key = id.value,
            bitmap = bitmap,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            surface = ArtworkSurface.List
        ) ?: return null
        publishHandle(id, owner, targetWidth, targetHeight, high)
        return synchronized(lock) {
            records[id.value]?.acquire(targetWidth, targetHeight, ArtworkSurface.List)
        }
    }

    fun markNotFound(id: FileArtworkId) {
        if (id.isBlank) return
        synchronized(lock) {
            recordForLocked(id).markNotFound()
        }
    }

    fun clear(id: FileArtworkId) {
        if (id.isBlank) return
        synchronized(lock) {
            records.remove(id.value)?.release()
        }
    }

    private fun publishHandle(
        id: FileArtworkId,
        handle: ArtworkHandle,
        targetWidth: Int,
        targetHeight: Int,
        high: Boolean = false
    ) {
        if (id.isBlank || !handle.isValid) {
            handle.release()
            return
        }
        synchronized(lock) {
            recordForLocked(id).publish(handle, targetWidth, targetHeight, high)
        }
    }

    private fun recordForLocked(id: FileArtworkId): ArtworkRecord {
        return records.getOrPut(id.value) { ArtworkRecord(id) }
    }

    private class ArtworkRecord(private val id: FileArtworkId) {
        private var lowOwner: ArtworkHandle? = null
        private var lowSide: Int = 0
        private var highOwner: ArtworkHandle? = null
        private var highSide: Int = 0
        private var notFoundAt: Long = 0L

        fun publish(handle: ArtworkHandle, targetWidth: Int, targetHeight: Int, forceHigh: Boolean) {
            if (!handle.isValid) {
                handle.release()
                return
            }
            // Classify low/high quality from the bitmap owner that actually exists,
            // not from the size that the current view asked for.  The old RawSMusic record used
            // max(requestedSide, bitmapSide), so a 256px placeholder acquired for a 640/1024px cell
            // could be recorded as a large/high wrapper.  That made some grid cells stop upgrading
            // while neighboring cells decoded a real larger thumbnail, producing the mixed low/high
            // resolution look.  Track the real bitmap side only.
            val side = maxOf(handle.bitmap.width, handle.bitmap.height).coerceAtLeast(1)
            notFoundAt = 0L
            val high = forceHigh || side >= 768
            if (high) {
                val existing = highOwner?.takeIf { it.isValid }
                if (existing?.bitmap === handle.bitmap) {
                    handle.release()
                    highOwner = existing
                } else {
                    highOwner?.release()
                    highOwner = handle
                }
                highSide = side
            } else {
                val existing = lowOwner?.takeIf { it.isValid }
                if (existing?.bitmap === handle.bitmap) {
                    handle.release()
                    lowOwner = existing
                } else {
                    lowOwner?.release()
                    lowOwner = handle
                }
                lowSide = side
            }
        }

        fun acquire(targetWidth: Int, targetHeight: Int, surface: ArtworkSurface): ArtworkHandle? {
            if (isNotFoundFresh()) return null
            val requested = maxOf(targetWidth, targetHeight).coerceAtLeast(1)
            val high = highOwner?.takeIf { it.isValid }
            if (high != null && highSide.toFloat() >= requested * 0.72f) {
                return BitmapProvider.acquireLoaded(id.value, high.bitmap, targetWidth, targetHeight, surface)
            }
            val low = lowOwner?.takeIf { it.isValid }
            if (low != null) {
                return BitmapProvider.acquireLoaded(id.value, low.bitmap, targetWidth, targetHeight, surface)
            }
            return high?.let { BitmapProvider.acquireLoaded(id.value, it.bitmap, targetWidth, targetHeight, surface) }
        }

        fun markNotFound() {
            release()
            notFoundAt = System.currentTimeMillis()
        }

        private fun isNotFoundFresh(): Boolean {
            val at = notFoundAt
            return at > 0L && System.currentTimeMillis() - at < NOT_FOUND_TTL_MS
        }

        fun release() {
            lowOwner?.release()
            lowOwner = null
            lowSide = 0
            highOwner?.release()
            highOwner = null
            highSide = 0
        }
    }
}
