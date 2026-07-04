package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 按 size-slot 分桶的 Bitmap 内存缓存。
 * 按 allocationByteCount 限制，避免高清封面 OOM。
 * access-order LRU，淘汰时不主动 recycle。
 */
class SizeSlotCache(
    private val maxBytes: Int = defaultMaxBytes()
) {
    companion object {
        private val SIZE_SLOTS = intArrayOf(16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048)

        fun computeBucket(width: Int, height: Int): Int {
            val size = if (width >= 2 * height) width / 2 else maxOf(width, height)
            if (size <= 0) return SIZE_SLOTS[0]
            val powerOf2 = Integer.highestOneBit(size)
            return SIZE_SLOTS.minByOrNull { abs(it - powerOf2) } ?: powerOf2
        }

        fun defaultMaxBytes(): Int {
            val maxMem = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return (maxMem / 8).coerceAtLeast(24 * 1024 * 1024).coerceAtMost(96 * 1024 * 1024)
        }
    }

    private data class Entry(val key: String, val bitmap: Bitmap, val bucket: Int, val byteCount: Int)

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {}
    private var currentBytes: Int = 0

    fun get(key: String): Bitmap? = synchronized(lock) {
        val entry = map[key] ?: return null
        if (entry.bitmap.isRecycled) { removeLocked(key); return null }
        entry.bitmap
    }

    fun getAnyForSource(sourceKey: String): Bitmap? = synchronized(lock) {
        val prefix = "${sourceKey}_"
        val entry = map.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .filter { !it.bitmap.isRecycled }
            .maxByOrNull { it.bucket }
            ?: return null
        entry.bitmap
    }

    fun put(key: String, bitmap: Bitmap, bucket: Int) {
        if (bitmap.isRecycled) return
        val bc = safeByteCount(bitmap)
        if (bc <= 0) return
        synchronized(lock) {
            removeLocked(key)
            if (bc > maxBytes) return
            map[key] = Entry(key, bitmap, bucket, bc)
            currentBytes += bc
            trimToSize(maxBytes)
        }
    }

    fun remove(key: String) = synchronized(lock) { removeLocked(key) }
    fun clear() = synchronized(lock) { map.clear(); currentBytes = 0 }
    val size: Int get() = synchronized(lock) { map.size }
    val bytes: Int get() = synchronized(lock) { currentBytes }
    val maxSizeBytes: Int get() = maxBytes

    private fun trimToSize(targetBytes: Int) {
        val iter = map.entries.iterator()
        while (currentBytes > targetBytes && iter.hasNext()) {
            currentBytes -= iter.next().value.byteCount
            iter.remove()
        }
        if (currentBytes < 0) currentBytes = 0
    }

    private fun removeLocked(key: String) {
        val removed = map.remove(key) ?: return
        currentBytes -= removed.byteCount
        if (currentBytes < 0) currentBytes = 0
    }

    private fun safeByteCount(bitmap: Bitmap): Int {
        return try { bitmap.allocationByteCount } catch (_: Throwable) { bitmap.byteCount }
    }
}
