package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * 专辑图双层缓存（低分辨率 + 高分辨率）。
 *
 * 缓存策略：
 * - 低分辨率（128x128）：快速显示，占用内存少
 * - 高分辨率（1080x1080）：最终显示质量
 * - 同一 key 的低分辨率先显示，高分辨率到达后交叉淡入
 */
object AlbumArtCache {

    /** 缓存条目 */
    data class CacheEntry(
        val lowRes: Bitmap? = null,
        val hiRes: Bitmap? = null,
        val lowResLoading: Boolean = false,
        val hiResLoading: Boolean = false
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    const val LOW_RES_SIZE = 128
    const val HI_RES_SIZE = 1080

    fun get(key: String): CacheEntry? = cache[key]

    fun putLowRes(key: String, bitmap: Bitmap) {
        val existing = cache[key] ?: CacheEntry()
        cache[key] = existing.copy(lowRes = bitmap, lowResLoading = false)
    }

    fun putHiRes(key: String, bitmap: Bitmap) {
        val existing = cache[key] ?: CacheEntry()
        cache[key] = existing.copy(hiRes = bitmap, hiResLoading = false)
    }

    fun markLowResLoading(key: String) {
        val existing = cache[key] ?: CacheEntry()
        cache[key] = existing.copy(lowResLoading = true)
    }

    fun markHiResLoading(key: String) {
        val existing = cache[key] ?: CacheEntry()
        cache[key] = existing.copy(hiResLoading = true)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.clear()
    }

    /**
     * 获取最佳可用 Bitmap：
     * 1. 优先返回高分辨率
     * 2. 其次返回低分辨率
     * 3. 都没有返回 null
     */
    fun getBestAvailable(key: String): Bitmap? {
        val entry = cache[key] ?: return null
        val hiRes = entry.hiRes
        if (hiRes != null && !hiRes.isRecycled) return hiRes
        val lowRes = entry.lowRes
        if (lowRes != null && !lowRes.isRecycled) return lowRes
        return null
    }

    fun hasLowRes(key: String): Boolean {
        val entry = cache[key] ?: return false
        return entry.lowRes != null && !entry.lowRes!!.isRecycled
    }

    fun hasHiRes(key: String): Boolean {
        val entry = cache[key] ?: return false
        return entry.hiRes != null && !entry.hiRes!!.isRecycled
    }

    fun isLowResLoading(key: String): Boolean = cache[key]?.lowResLoading == true
    fun isHiResLoading(key: String): Boolean = cache[key]?.hiResLoading == true
}
