package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.LinkedList

/**
 * Bitmap 复用池
 *
 * 核心机制：
 * - 双向链表存储可复用的 Bitmap
 * - 按可用内存匹配：优先返回尺寸一致的 Bitmap，否则复用兼容的 allocation
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
        if (width <= 0 || height <= 0) return null
        synchronized(lock) {
            var compatible: Bitmap? = null
            var compatibleBytes = Int.MAX_VALUE
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (bitmap.isRecycled) {
                    iterator.remove()
                    continue
                }
                if (!bitmap.isMutable || bitmap.config != config) continue
                if (bitmap.width == width && bitmap.height == height) {
                    iterator.remove()
                    return prepareForReuse(bitmap, width, height, config)
                }

                // Bitmap.reconfigure() can reuse the native allocation when the requested pixel
                // storage fits. Prefer the smallest compatible allocation for scrolling cells.
                val requiredBytes = requiredByteCount(width, height, config)
                val allocationBytes = try {
                    bitmap.allocationByteCount
                } catch (_: Throwable) {
                    bitmap.byteCount
                }
                if (allocationBytes >= requiredBytes && allocationBytes < compatibleBytes) {
                    compatible = bitmap
                    compatibleBytes = allocationBytes
                }
            }

            val bitmap = compatible ?: return null
            pool.remove(bitmap)
            return try {
                bitmap.reconfigure(width, height, config)
                prepareForReuse(bitmap, width, height, config)
            } catch (_: Throwable) {
                if (!bitmap.isRecycled) bitmap.recycle()
                null
            }
        }
    }

    private fun prepareForReuse(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        config: Bitmap.Config
    ): Bitmap? {
        return try {
            if (bitmap.width != width || bitmap.height != height || bitmap.config != config) {
                bitmap.reconfigure(width, height, config)
            }
            bitmap.eraseColor(0)
            bitmap
        } catch (_: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }

    private fun requiredByteCount(width: Int, height: Int, config: Bitmap.Config): Int {
        val bytesPerPixel = when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.RGBA_F16 -> 8
            Bitmap.Config.HARDWARE -> 4
            else -> 4
        }
        return (width.toLong() * height.toLong() * bytesPerPixel)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
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
