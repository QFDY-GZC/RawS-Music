package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap

/**
 * Project-style two-slot album-art cache owner.
 *
 * This cache is intentionally separate from the exact-size `SizeSlotCache`: it keeps the best
 * currently-known low/high artwork per source so player surfaces can bind immediately while an
 * exact bucket is still loading.  The important lifecycle rule is that this owner is bounded,
 * access-ordered and ref-aware: UI surfaces acquire an [ArtworkHandle] while drawing, then release
 * it on detach/replacement. Eviction may drop cache ownership, but it never recycles a bitmap that
 * may still be referenced by Compose, transitions, Palette extraction or exact-size caches.
 */
object AlbumArtCache {

    /** 缓存条目 */
    data class CacheEntry(
        val lowRes: Bitmap? = null,
        val hiRes: Bitmap? = null,
        val lowResLoading: Boolean = false,
        val hiResLoading: Boolean = false
    )

    const val LOW_RES_SIZE = AlbumArtTiers.LOW_RES_MIN_SIDE
    const val HI_RES_SIZE = AlbumArtTiers.HI_RES_SIDE
    const val FULL_RES_SIZE = AlbumArtTiers.FULL_RES_SIDE

    /**
     * Project-style shared owner. Multiple provider/entity keys may point at the same bitmap
     * wrapper, but the memory budget must count that bitmap once. This mirrors the artwork wrapper
     * model where several type/id aliases can attach to the same low/high artwork record.
     */
    private class SharedSlot(
        val bitmap: Bitmap,
        val tier: ArtworkTier,
        val bytes: Int,
        var refs: Int = 0,
        var owners: Int = 0
    ) {
        fun valid(): Boolean = !bitmap.isRecycled
        fun evictable(): Boolean = refs <= 0
    }

    private data class Entry(
        val lowRes: SharedSlot? = null,
        val hiRes: SharedSlot? = null,
        val lowResLoading: Boolean = false,
        val hiResLoading: Boolean = false
    ) {
        fun toPublic(): CacheEntry = CacheEntry(
            lowRes = lowRes?.bitmap?.takeIf { !it.isRecycled },
            hiRes = hiRes?.bitmap?.takeIf { !it.isRecycled },
            lowResLoading = lowResLoading,
            hiResLoading = hiResLoading
        )

        fun empty(): Boolean {
            return lowRes == null && hiRes == null && !lowResLoading && !hiResLoading
        }
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {}
    private val sharedSlots = java.util.IdentityHashMap<Bitmap, SharedSlot>()

    private val maxBytes: Int = defaultMaxBytes()
    private var currentBytes: Int = 0

    fun get(key: String): CacheEntry? = synchronized(lock) {
        cleanupRecycledLocked(key)
        cache[key]?.toPublic()
    }

    fun putLowRes(key: String, bitmap: Bitmap) {
        putSlot(key = key, bitmap = bitmap, tier = ArtworkTier.Low)
    }

    fun putHiRes(key: String, bitmap: Bitmap) {
        putSlot(key = key, bitmap = bitmap, tier = ArtworkTier.High)
    }

    fun acquireLowRes(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        cleanupRecycledLocked(key)
        val slot = cache[key]?.lowRes?.takeIf { it.valid() } ?: return@synchronized null
        acquireSlotLocked(key, ArtworkTier.Low, surface, slot)
    }

    fun acquireHiRes(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        cleanupRecycledLocked(key)
        val slot = cache[key]?.hiRes?.takeIf { it.valid() } ?: return@synchronized null
        acquireSlotLocked(key, ArtworkTier.High, surface, slot)
    }

    fun acquireBestAvailable(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        cleanupRecycledLocked(key)
        val entry = cache[key] ?: return@synchronized null
        val high = entry.hiRes?.takeIf { it.valid() }
        if (high != null) return@synchronized acquireSlotLocked(key, ArtworkTier.High, surface, high)
        val low = entry.lowRes?.takeIf { it.valid() }
        if (low != null) return@synchronized acquireSlotLocked(key, ArtworkTier.Low, surface, low)
        null
    }

    fun markLowResLoading(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            val existing = cache[key] ?: Entry()
            cache[key] = existing.copy(lowResLoading = true)
        }
    }

    fun markHiResLoading(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            val existing = cache[key] ?: Entry()
            cache[key] = existing.copy(hiResLoading = true)
        }
    }

    fun remove(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            val removed = cache.remove(key) ?: return@synchronized
            detachOwnerLocked(removed.lowRes)
            detachOwnerLocked(removed.hiRes)
            if (currentBytes < 0) currentBytes = 0
        }
    }

    fun clear() = synchronized(lock) {
        cache.clear()
        sharedSlots.clear()
        currentBytes = 0
    }

    /**
     * 获取最佳可用 Bitmap：
     * 1. 优先返回高分辨率
     * 2. 其次返回低分辨率
     * 3. 都没有返回 null
     *
     * Legacy callers receive a raw Bitmap for compatibility. New UI code should prefer
     * [acquireBestAvailable] so attach/detach is explicit.
     */
    fun getBestAvailable(key: String): Bitmap? = synchronized(lock) {
        cleanupRecycledLocked(key)
        val entry = cache[key] ?: return null
        val hiRes = entry.hiRes?.bitmap
        if (hiRes != null && !hiRes.isRecycled) return hiRes
        val lowRes = entry.lowRes?.bitmap
        if (lowRes != null && !lowRes.isRecycled) return lowRes
        null
    }

    fun hasLowRes(key: String): Boolean = synchronized(lock) {
        cleanupRecycledLocked(key)
        cache[key]?.lowRes?.valid() == true
    }

    fun hasHiRes(key: String): Boolean = synchronized(lock) {
        cleanupRecycledLocked(key)
        cache[key]?.hiRes?.valid() == true
    }

    fun isLowResLoading(key: String): Boolean = synchronized(lock) { cache[key]?.lowResLoading == true }
    fun isHiResLoading(key: String): Boolean = synchronized(lock) { cache[key]?.hiResLoading == true }

    val size: Int get() = synchronized(lock) { cache.size }
    val bytes: Int get() = synchronized(lock) { currentBytes }
    val maxSizeBytes: Int get() = maxBytes

    private fun putSlot(key: String, bitmap: Bitmap, tier: ArtworkTier) {
        if (key.isBlank() || bitmap.isRecycled) return
        synchronized(lock) {
            cleanupRecycledLocked(key)
            val existing = cache[key] ?: Entry()
            val currentSlot = when (tier) {
                ArtworkTier.Low -> existing.lowRes
                ArtworkTier.High,
                ArtworkTier.Full,
                ArtworkTier.Any -> existing.hiRes
            }
            val shared = if (currentSlot?.bitmap === bitmap && currentSlot.valid()) {
                currentSlot
            } else {
                attachOwnerLocked(bitmap, tier) ?: return@synchronized
            }
            val updated = when (tier) {
                ArtworkTier.Low -> {
                    if (existing.lowRes !== shared) detachOwnerLocked(existing.lowRes)
                    existing.copy(lowRes = shared, lowResLoading = false)
                }
                ArtworkTier.High,
                ArtworkTier.Full,
                ArtworkTier.Any -> {
                    if (existing.hiRes !== shared) detachOwnerLocked(existing.hiRes)
                    existing.copy(hiRes = shared, hiResLoading = false)
                }
            }
            cache[key] = updated
            trimLocked()
        }
    }

    private fun acquireSlotLocked(
        key: String,
        tier: ArtworkTier,
        surface: ArtworkSurface,
        slot: SharedSlot
    ): ArtworkHandle? {
        if (!slot.valid()) return null
        slot.refs++
        return ArtworkHandle(
            sourceKey = key,
            tier = tier,
            surface = surface,
            bitmap = slot.bitmap
        ) {
            releaseSlot(key, slot)
        }
    }

    private fun releaseSlot(key: String, slot: SharedSlot) {
        synchronized(lock) {
            if (slot.refs > 0) slot.refs--
            // After release, try a cheap trim. This lets ref-protected slots survive while attached,
            // then become evictable as soon as the surface detaches.
            trimLocked()
            val entry = cache[key]
            if (entry != null && entry.empty()) cache.remove(key)
        }
    }

    private fun cleanupRecycledLocked(key: String) {
        val entry = cache[key] ?: return
        val low = entry.lowRes?.takeIf { it.valid() }
        val high = entry.hiRes?.takeIf { it.valid() }
        if (low === entry.lowRes && high === entry.hiRes) return

        if (low !== entry.lowRes) detachOwnerLocked(entry.lowRes)
        if (high !== entry.hiRes) detachOwnerLocked(entry.hiRes)
        val cleaned = entry.copy(lowRes = low, hiRes = high)

        if (cleaned.empty()) {
            cache.remove(key)
        } else {
            cache[key] = cleaned
        }
        if (currentBytes < 0) currentBytes = 0
    }

    private fun trimLocked() {
        if (currentBytes <= maxBytes) return
        val iterator = cache.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val mapEntry = iterator.next()
            val entry = mapEntry.value
            val low = entry.lowRes
            val high = entry.hiRes
            val keepLow = low?.takeIf { !it.evictable() }
            val keepHigh = high?.takeIf { !it.evictable() }

            if (keepLow === low && keepHigh === high) {
                // Both present slots are attached by UI. Skip; temporary over-budget is safer than
                // detaching a bitmap still being drawn.
                continue
            }

            if (keepLow !== low) detachOwnerLocked(low)
            if (keepHigh !== high) detachOwnerLocked(high)

            val trimmed = entry.copy(lowRes = keepLow, hiRes = keepHigh)
            if (trimmed.empty()) {
                iterator.remove()
            } else {
                mapEntry.setValue(trimmed)
            }
        }
        if (currentBytes < 0) currentBytes = 0
    }

    private fun attachOwnerLocked(bitmap: Bitmap, tier: ArtworkTier): SharedSlot? {
        if (bitmap.isRecycled) return null
        val existing = sharedSlots[bitmap]
        val slot = if (existing != null && existing.valid()) {
            existing
        } else {
            if (existing != null) sharedSlots.remove(bitmap)
            val bytes = byteCount(bitmap)
            if (bytes <= 0) return null
            SharedSlot(bitmap = bitmap, tier = tier, bytes = bytes).also {
                sharedSlots[bitmap] = it
                currentBytes += bytes
            }
        }
        slot.owners++
        return slot
    }

    private fun detachOwnerLocked(slot: SharedSlot?) {
        if (slot == null) return
        if (slot.owners > 0) slot.owners--
        if (slot.owners <= 0) {
            sharedSlots.remove(slot.bitmap)
            currentBytes -= slot.bytes
        }
    }

    private fun byteCount(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled) return 0
        return try { bitmap.allocationByteCount } catch (_: Throwable) { bitmap.byteCount }
    }

    private fun defaultMaxBytes(): Int {
        val maxMem = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (maxMem / 10).coerceAtLeast(24 * 1024 * 1024).coerceAtMost(72 * 1024 * 1024)
    }
}
