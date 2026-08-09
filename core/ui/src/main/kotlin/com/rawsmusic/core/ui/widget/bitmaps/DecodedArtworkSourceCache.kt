package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.io.File
import java.util.LinkedHashMap

/**
 * Keeps low/high decoded software records for a versioned artwork source.
 *
 * Target-size bitmaps remain owned by SizeSlotCache. This cache only owns decoded source tiers and
 * is used as a process-local provider source record. A source bitmap is
 * never returned directly to a UI holder, so it can safely be reused for several target sizes.
 */
internal object DecodedArtworkSourceCache {

    private const val MAX_BYTES = 12L * 1024L * 1024L
    private const val MAX_ENTRIES = 4
    private const val MAX_BOUNDS_ENTRIES = 64
    private const val LOW_SOURCE_MAX_SIDE = 512

    data class Bounds(
        val width: Int,
        val height: Int
    )

    private class Entry(
        val key: String,
        var low: Bitmap? = null,
        var high: Bitmap? = null
    ) {
        val bytes: Long
            get() = byteCount(low) + byteCount(high)

        fun bitmapFor(minimumSide: Int): Bitmap? {
            val required = minimumSide.coerceAtLeast(1)
            val highBitmap = high
            if (highBitmap != null && !highBitmap.isRecycled && maxSide(highBitmap) >= required) {
                return highBitmap
            }
            val lowBitmap = low
            if (lowBitmap != null && !lowBitmap.isRecycled && maxSide(lowBitmap) >= required) {
                return lowBitmap
            }
            return null
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true)
    private val bounds = LinkedHashMap<String, Bounds>(MAX_BOUNDS_ENTRIES, 0.75f, true)
    private var bytes: Long = 0L

    fun keyFor(path: String): String? {
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
        val absolute = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return "$absolute|${file.length()}|${file.lastModified()}"
    }

    fun get(key: String, minimumSide: Int = 1): Bitmap? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.low?.isRecycled == true) entry.low = null
        if (entry.high?.isRecycled == true) entry.high = null
        if (entry.low == null && entry.high == null) {
            removeLocked(key)
            return@synchronized null
        }
        entry.bitmapFor(minimumSide)
    }

    fun getBounds(key: String): Bounds? = synchronized(lock) {
        bounds[key]
    }

    fun putBounds(key: String, width: Int, height: Int) {
        if (key.isBlank() || width <= 0 || height <= 0) return
        synchronized(lock) {
            bounds[key] = Bounds(width, height)
            while (bounds.size > MAX_BOUNDS_ENTRIES) {
                val iterator = bounds.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    /** Returns true when the cache took ownership of [bitmap]. */
    fun put(key: String, bitmap: Bitmap): Boolean {
        if (key.isBlank() || bitmap.isRecycled || !bitmap.isMutable) return false
        val allocationBytes = runCatching { bitmap.allocationByteCount.toLong() }
            .getOrElse { bitmap.byteCount.toLong() }
        if (allocationBytes <= 0L || allocationBytes > MAX_BYTES) return false

        return synchronized(lock) {
            val entry = entries[key] ?: Entry(key).also { entries[key] = it }
            val isHigh = maxSide(bitmap) > LOW_SOURCE_MAX_SIDE
            val previous = if (isHigh) entry.high else entry.low
            if (previous === bitmap) return@synchronized true
            val previousBytes = byteCount(previous)
            if (isHigh) entry.high = bitmap else entry.low = bitmap
            if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
            bytes += allocationBytes
            bytes -= previousBytes
            trimLocked()
            entry.low === bitmap || entry.high === bitmap
        }
    }

    fun clear() = synchronized(lock) {
        entries.values.forEach(::recycleEntry)
        entries.clear()
        bounds.clear()
        bytes = 0L
    }

    val size: Int get() = synchronized(lock) { entries.size }

    private fun trimLocked() {
        val iterator = entries.entries.iterator()
        while ((bytes > MAX_BYTES || entries.size > MAX_ENTRIES) && iterator.hasNext()) {
            val entry = iterator.next().value
            iterator.remove()
            bytes -= entry.bytes
            recycleEntry(entry)
        }
        if (bytes < 0L) bytes = 0L
    }

    private fun removeLocked(key: String) {
        val old = entries.remove(key) ?: return
        bytes -= old.bytes
        recycleEntry(old)
    }

    private fun recycleEntry(entry: Entry) {
        val low = entry.low
        val high = entry.high
        if (low != null && !low.isRecycled) low.recycle()
        if (high != null && high !== low && !high.isRecycled) high.recycle()
        entry.low = null
        entry.high = null
    }

    private fun byteCount(bitmap: Bitmap?): Long {
        if (bitmap == null || bitmap.isRecycled) return 0L
        return runCatching { bitmap.allocationByteCount.toLong() }
            .getOrElse { bitmap.byteCount.toLong() }
    }

    private fun maxSide(bitmap: Bitmap): Int = maxOf(bitmap.width, bitmap.height)
}
