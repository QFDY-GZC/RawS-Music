package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 按 size-slot 分桶的 Bitmap 内存缓存。
 * 按 allocationByteCount 限制，避免高清封面 OOM。
 * access-order LRU，淘汰时不主动 recycle。
 *
 * v7d: 新增 sourceKey 反向索引（sourceIndex），getAnyForSource 优先查索引，
 * 避免 prefix 全表扫描；sourceKey 由 put 调用方传入。
 * v8: bucket 距离相同的时候选更大的槽位，避免 384/768 目标被 256/512 旧图命中后发糊。
 * v9 / design alignment phase 3d: exact-size entries are now ref-aware owners. UI
 * surfaces acquire an [ArtworkHandle] for the bucket they draw and release it on detach; LRU
 * trimming skips attached entries instead of silently dropping artwork that is still being painted.
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
            return SIZE_SLOTS.minWithOrNull(
                compareBy<Int> { abs(it - powerOf2) }.thenByDescending { it }
            ) ?: powerOf2
        }

        fun defaultMaxBytes(): Int {
            val maxMem = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // SizeSlotCache is no longer the only strong owner: AlbumArtCache and PowerList keep
            // short-lived handles as well. Keep this exact-bucket tier tighter, and let attached
            // entries exceed the budget briefly rather than forcing a bad recycle/drop.
            return (maxMem / 12).coerceAtLeast(20 * 1024 * 1024).coerceAtMost(64 * 1024 * 1024)
        }
    }

    private data class Entry(
        val key: String,
        val bitmap: Bitmap,
        val bucket: Int,
        val byteCount: Int,
        val sourceKey: String,
        var refs: Int = 0
    ) {
        fun valid(): Boolean = !bitmap.isRecycled
        fun evictable(): Boolean = refs <= 0
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {}
    private val sourceIndex = HashMap<String, MutableSet<String>>()
    private var currentBytes: Int = 0

    fun get(key: String): Bitmap? = synchronized(lock) {
        val entry = map[key] ?: return null
        if (entry.bitmap.isRecycled) { removeLocked(key); return null }
        entry.bitmap
    }

    fun getAnyForSource(sourceKey: String): Bitmap? = synchronized(lock) {
        // v7d: 优先查 sourceIndex，避免全表 prefix 扫描
        val indexed = sourceIndex[sourceKey]
        if (indexed != null && indexed.isNotEmpty()) {
            val entry = indexed.asSequence()
                .mapNotNull { map[it] }
                .filter { it.valid() }
                .maxByOrNull { it.bucket }
            if (entry != null) return entry.bitmap
        }
        // 回退：兼容未走 put(sourceKey) 的旧路径
        val prefix = "${sourceKey}_"
        val fallback = map.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .filter { it.valid() }
            .maxByOrNull { it.bucket }
            ?: return null
        fallback.bitmap
    }

    /**
     * Acquire an exact-size slot as a project-style artwork handle.
     *
     * Raw bitmap getters remain for legacy paths, but all UI surfaces that keep the result across
     * frames should use this so LRU eviction knows the slot is attached.
     */
    fun acquire(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        val entry = map[key] ?: return@synchronized null
        if (!entry.valid()) {
            removeLocked(key)
            return@synchronized null
        }
        acquireEntryLocked(entry, surface)
    }

    fun acquireAnyForSource(
        sourceKey: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        if (sourceKey.isBlank()) return@synchronized null
        val indexed = sourceIndex[sourceKey]
        if (indexed != null && indexed.isNotEmpty()) {
            val entry = indexed.asSequence()
                .mapNotNull { map[it] }
                .filter { it.valid() }
                .maxByOrNull { it.bucket }
            if (entry != null) return@synchronized acquireEntryLocked(entry, surface)
        }
        val prefix = "${sourceKey}_"
        val fallback = map.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .filter { it.valid() }
            .maxByOrNull { it.bucket }
            ?: return@synchronized null
        acquireEntryLocked(fallback, surface)
    }

    fun put(key: String, bitmap: Bitmap, bucket: Int, sourceKey: String) {
        if (bitmap.isRecycled) return
        val bc = safeByteCount(bitmap)
        if (bc <= 0) return
        synchronized(lock) {
            removeLocked(key)
            if (bc > maxBytes) return
            map[key] = Entry(key, bitmap, bucket, bc, sourceKey)
            sourceIndex.getOrPut(sourceKey) { HashSet() }.add(key)
            currentBytes += bc
            trimToSize(maxBytes)
        }
    }

    fun remove(key: String) = synchronized(lock) { removeLocked(key) }

    fun removeForSource(sourceKey: String): Int = synchronized(lock) {
        if (sourceKey.isBlank()) return@synchronized 0
        var removed = 0
        val indexedKeys = sourceIndex[sourceKey]?.toList().orEmpty()
        for (key in indexedKeys) {
            if (map.containsKey(key)) {
                removeLocked(key)
                removed++
            }
        }

        // Compatibility fallback for entries created before the source index existed, and for callers
        // that pass a versioned/canonical source while older entries used the raw request key.
        val prefix = "${sourceKey}_"
        val fallbackKeys = map.keys.filter { it.startsWith(prefix) }
        for (key in fallbackKeys) {
            if (map.containsKey(key)) {
                removeLocked(key)
                removed++
            }
        }
        removed
    }

    fun clear() = synchronized(lock) { map.clear(); sourceIndex.clear(); currentBytes = 0 }
    val size: Int get() = synchronized(lock) { map.size }
    val bytes: Int get() = synchronized(lock) { currentBytes }
    val maxSizeBytes: Int get() = maxBytes

    private fun acquireEntryLocked(
        entry: Entry,
        surface: ArtworkSurface
    ): ArtworkHandle? {
        if (!entry.valid()) return null
        entry.refs++
        return ArtworkHandle(
            sourceKey = entry.sourceKey,
            tier = tierForBucket(entry.bucket),
            surface = surface,
            bitmap = entry.bitmap
        ) {
            releaseEntry(entry)
        }
    }

    private fun releaseEntry(entry: Entry) {
        synchronized(lock) {
            if (entry.refs > 0) entry.refs--
            trimToSize(maxBytes)
        }
    }

    private fun tierForBucket(bucket: Int): ArtworkTier {
        return when {
            bucket >= AlbumArtTiers.FULL_RES_SIDE -> ArtworkTier.Full
            bucket >= AlbumArtTiers.HI_RES_SIDE -> ArtworkTier.High
            else -> ArtworkTier.Low
        }
    }

    private fun trimToSize(targetBytes: Int) {
        val iter = map.entries.iterator()
        while (currentBytes > targetBytes && iter.hasNext()) {
            val entry = iter.next().value
            if (!entry.evictable()) {
                continue
            }
            currentBytes -= entry.byteCount
            removeFromSourceIndex(entry.sourceKey, entry.key)
            iter.remove()
        }
        if (currentBytes < 0) currentBytes = 0
    }

    private fun removeLocked(key: String) {
        val removed = map.remove(key) ?: return
        removeFromSourceIndex(removed.sourceKey, removed.key)
        currentBytes -= removed.byteCount
        if (currentBytes < 0) currentBytes = 0
    }

    private fun removeFromSourceIndex(sourceKey: String, key: String) {
        val set = sourceIndex[sourceKey] ?: return
        set.remove(key)
        if (set.isEmpty()) sourceIndex.remove(sourceKey)
    }

    private fun safeByteCount(bitmap: Bitmap): Int {
        return try { bitmap.allocationByteCount } catch (_: Throwable) { bitmap.byteCount }
    }
}
