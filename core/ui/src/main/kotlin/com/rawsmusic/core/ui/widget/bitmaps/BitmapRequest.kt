package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap

/**
 * Bitmap 加载请求
 *
 * 状态机：EMPTY → CHECKING_MEMORY → DECODING_FILES → AVAILABLE
 *                                    ↕
 *                                  NETWORK
 */
class BitmapRequest(
    /** 唯一标识（albumId 或 filePath） */
    val key: String,
    /** 目标宽度 */
    val targetWidth: Int,
    /** 目标高度 */
    val targetHeight: Int,
    /** 优先级（越小越优先） */
    var priority: Priority = Priority.LOADING_LIST,
    /** 加载完成回调 */
    val callback: ((Bitmap?) -> Unit)? = null
) {
    /** 优先级 */
    enum class Priority(val level: Int) {
        LOADING_NOTIFICATION_HIGH(0),
        LOADING_NOTIFICATION(1),
        LOADING_WIDGET(2),
        LOADING_LIST(3),           // 列表项（可见区域）
        LOADING_LIST_DELAYED(4),   // 列表项（延迟加载）
        LOADING_PREFETCH(5),       // 预取
        LOADED(6),                 // 已加载
        IDLE(7)                    // 空闲
    }

    /** 请求状态 */
    enum class State {
        EMPTY,
        CHECKING_MEMORY,
        DECODING_FILES,
        DECODING_NETWORK,
        AVAILABLE,
        CANCELLED
    }

    @Volatile
    var state: State = State.EMPTY
        private set

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    var traceSeq: Long = 0L

    @Volatile
    internal var inFlightOwner: Boolean = false

    @Volatile
    internal var promotedInFlight: Boolean = false

    @Volatile
    internal var keepAliveOnCancel: Boolean = false

    /** 计算后的 size-slot bucket */
    val bucket: Int by lazy {
        SizeSlotCache.computeBucket(targetWidth, targetHeight)
    }

    /** 缓存 key（key + bucket） */
    val cacheKey: String by lazy {
        "${key}_${bucket}"
    }

    /**
     * 状态转换
     */
    fun transitionTo(newState: State): Boolean {
        if (isCancelled && newState != State.CANCELLED) return false
        state = newState
        return true
    }

    /**
     * 取消请求
     */
    fun cancel(keepAlive: Boolean = false) {
        keepAliveOnCancel = keepAlive
        isCancelled = true
        state = State.CANCELLED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitmapRequest) return false
        return key == other.key && targetWidth == other.targetWidth && targetHeight == other.targetHeight
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + targetWidth
        result = 31 * result + targetHeight
        return result
    }
}
