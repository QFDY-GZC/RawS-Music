package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap


internal enum class ProviderCancelAction {
    /** Detach this UI observer only; the provider flight is still required by another live waiter. */
    DETACH_OBSERVER_ONLY,
    /** Cancel only this non-owner observer; another waiter still keeps the source flight alive. */
    CANCEL_OBSERVER_ONLY,
    /** No live waiters remain, so the provider source flight may be cancelled. */
    CANCEL_FLIGHT,
}

internal fun resolveProviderCancelAction(
    requestIsFlightOwner: Boolean,
    hasOtherLiveWaiters: Boolean,
): ProviderCancelAction = when {
    requestIsFlightOwner && hasOtherLiveWaiters -> ProviderCancelAction.DETACH_OBSERVER_ONLY
    hasOtherLiveWaiters -> ProviderCancelAction.CANCEL_OBSERVER_ONLY
    else -> ProviderCancelAction.CANCEL_FLIGHT
}

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
    /** Legacy bitmap callback for non-VirtualList surfaces. */
    val callback: ((Bitmap?) -> Unit)? = null,
    /** Reference-style provider-wrapper callback used by VirtualList artwork holders. */
    val wrapperCallback: ((ArtworkHandle?) -> Unit)? = null,
    /** Lifecycle surface: Project-style owner deciding whether source probing is allowed. */
    val surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
    /**
     * Per-request source capability. Cache lookup always runs first; callers may disable source
     * probing for never-visible/prefetch work, but an attached visible VirtualList holder remains
     * source-capable while drag/fling motion is active.
     */
    val sourceDecodeAllowed: Boolean = surface.allowsSourceDecode,
    /** Source geometry policy. KeepAspect uses a separate provider/cache record from legacy Crop. */
    val aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    /** Source/version/bucket token used to reject stale async artwork callbacks. */
    artworkToken: ArtworkAcceptToken,
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

    /** True only after the provider has claimed the queued source probe. */
    @Volatile
    internal var sourceWorkStarted: Boolean = false
        private set

    /** Number of bounded source retries kept inside the provider flight. */
    @Volatile
    internal var sourceRetryCount: Int = 0

    /** Null is terminal only when the provider has confirmed that no artwork exists. */
    @Volatile
    var terminalNoArt: Boolean = false

    /** 计算后的 size-slot bucket */
    val bucket: Int by lazy {
        SizeSlotCache.computeBucket(targetWidth, targetHeight)
    }

    /** Aspect-specific provider/cache source identity. */
    val cacheSourceKey: String by lazy { artworkAspectCacheSourceKey(key, aspectPolicy) }

    /** 缓存 key（aspect-specific key + bucket） */
    val cacheKey: String by lazy {
        "${cacheSourceKey}_${bucket}"
    }

    /** Provider record identity follows the provider-owned request when a stale source record is
     * replaced. artwork holders keep the same BitmapRequest/callback ownership; only the provider record
     * token changes, matching Reference's request-table requeue rather than a UI-level retry. */
    @Volatile
    var artworkToken: ArtworkAcceptToken = artworkToken
        private set

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

    /** Claim this request's source probe at most once. */
    internal fun tryStartSourceWork(): Boolean = synchronized(this) {
        if (isCancelled || sourceWorkStarted) return false
        sourceWorkStarted = true
        true
    }

    /**
     * Keep the same provider owner/waiters alive for a short transient-source retry.
     * The retry number is claimed under the request monitor so a cancellation/rebind cannot
     * accidentally schedule more than the bounded number of source probes.
     */
    internal fun tryPrepareSourceRetry(maxRetries: Int): Int? = synchronized(this) {
        if (isCancelled || terminalNoArt || sourceRetryCount >= maxRetries) return null
        sourceRetryCount += 1
        sourceWorkStarted = false
        state = State.CHECKING_MEMORY
        sourceRetryCount
    }

    internal fun moveToArtworkRecord(nextToken: ArtworkAcceptToken): Boolean = synchronized(this) {
        if (isCancelled) return false
        artworkToken = nextToken
        inFlightOwner = false
        sourceWorkStarted = false
        sourceRetryCount = 0
        terminalNoArt = false
        state = State.CHECKING_MEMORY
        true
    }

    /** 取消请求。 */
    fun cancel() {
        isCancelled = true
        state = State.CANCELLED
    }

    // A request owns one observer/cancellation lifecycle, so equality is object identity.
    // Decode deduplication uses inFlightKey, independently of observer identity. Value equality
    // here made addIfAbsent drop another visible holder requesting the same album and let
    // remove(request) detach the wrong holder; neither received a later binding refresh at rest.
}
