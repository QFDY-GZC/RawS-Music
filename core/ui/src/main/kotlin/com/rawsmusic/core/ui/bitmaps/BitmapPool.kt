package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.LinkedList

/**
 * Bitmap 复用池
 *
 * 核心机制：
 * - 双向链表存储可复用的 Bitmap
 * - 按尺寸匹配：只返回 width/height 一致的 Bitmap
 * - HARDWARE Bitmap 永远不进池（isMutable=false，不能 reconfigure）
 * - 淘汰时 recycle 释放显存/内存
 */
object BitmapPool {

    private const val MAX_POOL_SIZE = 8

    private val pool = LinkedList<Bitmap>()
    private val lock = Any()

    /**
     * 从池中获取指定尺寸的可复用 Bitmap
     * @return 匹配的 Bitmap，或 null
     */
    fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        synchronized(lock) {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (bitmap.isRecycled) {
                    iterator.remove()
                    continue
                }
                if (bitmap.width == width && bitmap.height == height && bitmap.config == config) {
                    iterator.remove()
                    if (bitmap.hasAlpha()) {
                        bitmap.eraseColor(0)
                    }
                    return bitmap
                }
            }
            return null
        }
    }

    /**
     * 回收 Bitmap 到池中
     * - HARDWARE Bitmap 不回收（不可 reconfigure）
     * - 已回收的 Bitmap 不回收
     * - 池满时淘汰最旧的
     */
    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        // HARDWARE Bitmap 永远不可变，不能 reconfigure，不进池
        if (!bitmap.isMutable) return

        synchronized(lock) {
            if (pool.size >= MAX_POOL_SIZE) {
                pool.firstOrNull()?.recycle()
                pool.removeFirst()
            }
            pool.addLast(bitmap)
        }
    }

    /**
     * 清空池，回收所有 Bitmap
     */
    fun clear() {
        synchronized(lock) {
            for (bitmap in pool) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            pool.clear()
        }
    }

    /**
     * 池中当前 Bitmap 数量
     */
    val size: Int get() = synchronized(lock) { pool.size }
}
