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
    /** Provider/cache identity. In file-identity mode this is usually the same as [decodeKey]. */
    val key: String,
    /** Actual source/version key used to open/decode the artwork source. */
    val decodeKey: String = key,
    /** Optional non-embedded image fallback belonging to the same audio record. */
    val externalArtworkPath: String = "",
    /** 目标宽度 */
    val targetWidth: Int,
    /** 目标高度 */
    val targetHeight: Int,
    /** 优先级（越小越优先） */
    var priority: Priority = Priority.LOADING_LIST,
    /** 加载完成回调 */
    val callback: ((Bitmap?) -> Unit)? = null,
    /** Lifecycle surface: Project-style owner deciding whether source probing is allowed. */
    val surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
    /** Source/version/bucket token used to reject stale async artwork callbacks. */
    val artworkToken: ArtworkAcceptToken
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
    internal var keepAliveOnCancel: Boolean = false

    /**
     * True only after the provider has claimed the queued source probe.  A detached holder may
     * remove a request that is still waiting in the Handler queue, but an already-running source
     * probe is allowed to finish and warm the shared bitmap record.
     */
    @Volatile
    internal var sourceWorkStarted: Boolean = false
        private set

    /** Null is terminal only when the provider has confirmed that no artwork exists. */
    @Volatile
    var terminalNoArt: Boolean = false

    /** 计算后的 size-slot bucket */
    val bucket: Int by lazy {
        SizeSlotCache.computeBucket(targetWidth, targetHeight)
    }

    /** 缓存 key（key + bucket） */
    val cacheKey: String by lazy {
        "${key}_${bucket}"
    }

    /** Queue/in-flight key. Includes record revision so stale decodes cannot share callbacks. */
    val inFlightKey: String
        get() = artworkToken.flightKey

    /**
     * 状态转换
     */
    fun transitionTo(newState: State): Boolean {
        if (isCancelled && newState != State.CANCELLED) return false
        state = newState
        return true
    }

    /**
     * Claim the source probe once, without racing a holder detach.
     *
     * A detached owner may still be the only queued representation of a shared flight.  In that
     * case [keepAliveOnCancel] means "detach this listener", not "cancel the source"; a later
     * holder is waiting on this same request and must still receive its result.
     */
    internal fun tryStartSourceWork(): Boolean = synchronized(this) {
        if ((isCancelled && !keepAliveOnCancel) || sourceWorkStarted) return false
        sourceWorkStarted = true
        true
    }

    /** Cancel and report whether the source probe had already started. */
    internal fun cancelAndGetSourceWorkStarted(keepAlive: Boolean): Boolean = synchronized(this) {
        keepAliveOnCancel = keepAlive
        isCancelled = true
        state = State.CANCELLED
        sourceWorkStarted
    }

    /**
     * 取消请求
     */
    fun cancel(keepAlive: Boolean = false) {
        cancelAndGetSourceWorkStarted(keepAlive)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BitmapRequest) return false
        return key == other.key && decodeKey == other.decodeKey && targetWidth == other.targetWidth && targetHeight == other.targetHeight
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + decodeKey.hashCode()
        result = 31 * result + targetWidth
        result = 31 * result + targetHeight
        return result
    }
}
