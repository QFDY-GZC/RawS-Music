package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 专辑图加载器。
 *
 * 修复点：
 * 1. 同一 cacheKey 正在加载时，后续请求加入等待队列，不再吞 callback。
 * 2. 未 init 时不会永久卡在 inFlight。
 * 3. 内存缓存改成按字节限制。
 * 4. 源图解码阶段不再使用 inBitmap，避免尺寸不匹配导致 decode 失败。
 * 5. 缩放输出阶段才使用 BitmapPool。
 * 6. 支持 file://、content://、真实文件路径、音频文件内嵌封面。
 * 7. 失败缓存有 TTL，避免列表滚动时反复重试。
 */
object BitmapProvider {

    private const val TAG = "BitmapProvider"
    private const val TRACE_TAG = "BitmapProviderTrace"
    private const val ART_LOG_TAG = "RawArt"
    private const val ENABLE_BITMAP_TRACE = false

    private const val MSG_LOAD = 1
    private const val MSG_CANCEL = 2

    private const val WORKER_COUNT = 3
    private const val FAILED_CACHE_TTL_MS = 5 * 60 * 1000L
    // Keep disk thumbnails as a small-list warm cache only. The design keeps source artwork and
    // low/high wrappers in memory instead of persisting every UI target size to disk. Writing
    // playback/fullscreen tiers here multiplied files for the same song and made bitmap_v4 grow
    // during normal scrolling.
    private const val DISK_THUMB_MAX_SIZE = AlbumArtTiers.LOW_RES_NORMAL_CAP
    private const val DISK_THUMB_MAX_BYTES = 24L * 1024L * 1024L
    private const val DISK_THUMB_MAX_FILES = 256
    private const val DISK_THUMB_DIR = "bitmap_thumbs_file_v7"
    private const val LEGACY_DISK_THUMB_DIR = "bitmap_thumbs_file_v6"
    private val INDEXER_COALESCE_SIDES = intArrayOf(
        AlbumArtTiers.LIST_SMALL_MAX_SIDE,
        AlbumArtTiers.LOW_RES_MIN_SIDE,
        AlbumArtTiers.LOW_RES_NORMAL_CAP
    )
    private val ART_INVALIDATE_SIZES = intArrayOf(
        AlbumArtTiers.LOW_RES_MIN_SIDE,
        AlbumArtTiers.LOW_RES_NORMAL_CAP,
        AlbumArtTiers.HI_RES_SIDE,
        AlbumArtTiers.FULL_RES_SIDE,
        96, 128, 192, 256, 384, 512, 768, 1024, 1440
    )

    private val initLock = Any()
    private var legacyDiskThumbCleanupDone = false

    @Volatile
    private var appContext: Context? = null

    private val memoryCache = SizeSlotCache()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var workerHandlers: Array<WorkerHandler> = emptyArray()

    private var workerIndex = 0

    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPriorities = ConcurrentHashMap<String, BitmapRequest.Priority>()
    private val inFlightTokens = ConcurrentHashMap<String, ArtworkAcceptToken>()
    private val promotedInFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val waitingRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<BitmapRequest>>()
    private val failedCache = ConcurrentHashMap<String, Long>()
    private val failedSourceCache = ConcurrentHashMap<String, Long>()
    private val failedLogLastAt = ConcurrentHashMap<String, Long>()
    private val traceSeq = AtomicLong(0L)

    /**
     * Bumped when library scan or manual artwork edit makes visible items re-check their cover.
     * This is intentionally UI-observable but very cheap: existing bitmaps stay cached; only stale
     * no-art decisions / LaunchedEffect keys are refreshed.
     */
    var artworkRevision by mutableLongStateOf(0L)
        private set

    /**
     * Cache-readiness signal for PowerList cells whose request callback was detached by recycling.
     * This deliberately does not participate in artwork accept tokens: cache publication must not
     * invalidate other in-flight requests just to make a visible holder re-peek its bitmap.
     */
    var powerListCacheRevision by mutableLongStateOf(0L)
        private set
    private val powerListCacheRevisionPending = AtomicBoolean(false)

    private const val MAX_POWER_LIST_VIEWPORT_KEYS = 80

    private val powerListViewportGeneration = AtomicLong(0L)
    private val powerListViewportCacheKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var powerListViewportActive: Boolean = false

    @Volatile
    private var powerListViewportLastKeysSignature: Int = 0

    @Volatile
    private var powerListViewportLastKeyCount: Int = 0

    @Volatile
    private var powerListViewportLastGeneration: Long = 0L

    @Volatile
    private var powerListDecodeSuspended: Boolean = false

    @Volatile
    private var powerListIndexerAllowed: Boolean = false

    // v7c: 复用解码相关对象，减少高频滚动时的 GC 抖动
    private val threadLocalDecodeOptions = object : ThreadLocal<BitmapFactory.Options>() {
        override fun initialValue(): BitmapFactory.Options {
            return BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        }
    }
    private val threadLocalCanvas = object : ThreadLocal<Canvas>() {
        override fun initialValue(): Canvas = Canvas()
    }
    private val threadLocalMatrix = object : ThreadLocal<Matrix>() {
        override fun initialValue(): Matrix = Matrix()
    }
    private val threadLocalPaint = object : ThreadLocal<Paint>() {
        override fun initialValue(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }

    // v7c: 磁盘缩略图写入放到低优先级单线程，避免阻塞解码 worker
    private val diskWriterExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawSMusic-DiskWriter").apply { priority = Thread.MIN_PRIORITY }
    }
    private val diskWriterPendingKeys =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Project-style artwork provider phase 1: list/mini surfaces never probe audio files directly.
    // They schedule one background index request per source+bucket instead, which prepares the
    // low-res disk thumbnail and bumps artworkRevision for visible rows to re-peek later.
    private val artworkIndexerKeys = ConcurrentHashMap.newKeySet<String>()
    private val artworkRevisionCoalescePending = AtomicBoolean(false)

    // The design uses a serial img-load handler plus a separate album-art downloader lane. RawSMusic
    // keeps several bitmap workers for cheap cache/image-file work, but heavyweight audio-source
    // probes must not run in parallel: three concurrent TagLib/FFmpeg/MMR opens are enough to make
    // dense 4-column scrolling hitch even when the UI path itself only asked for thumbnails.
    private val sourceExtractionGate = java.util.concurrent.Semaphore(1, true)

    /**
     * Keep album art as software bitmaps.
     *
     * Project-style artwork pipelines reuse/crop/sample bitmaps and also read pixels for palette
     * extraction. Android HARDWARE bitmaps render fast on some devices, but they cannot be read with
     * getPixels(), cannot be reused by BitmapPool, and force an extra software copy before disk-cache
     * JPEG compression. On MIUI 14 / Mi 10S this showed up as a player crash in Palette plus a long
     * low-res placeholder window. Keep this provider software-only and let Compose upload textures.
     */
    val useHardwareBitmap: Boolean by lazy { false }

    fun init(context: Context) {
        synchronized(initLock) {
            appContext = context.applicationContext

            // v4 was an unbounded per-size store. It is no longer read, so remove it once after
            // upgrading rather than carrying its old power/space cost forever.
            if (!legacyDiskThumbCleanupDone) {
                runCatching {
                    File(appContext!!.cacheDir, LEGACY_DISK_THUMB_DIR).deleteRecursively()
                }
                legacyDiskThumbCleanupDone = true
                Log.i(
                    ART_LOG_TAG,
                    "ART_PROVIDER_CACHE_POLICY diskDir=$DISK_THUMB_DIR maxFiles=$DISK_THUMB_MAX_FILES maxBytes=$DISK_THUMB_MAX_BYTES maxSide=$DISK_THUMB_MAX_SIZE legacyRemoved=$LEGACY_DISK_THUMB_DIR"
                )
            }

            if (workerHandlers.isNotEmpty()) {
                if (ENABLE_BITMAP_TRACE) Log.d(TAG, "Already initialized")
                return
            }

            workerHandlers = Array(WORKER_COUNT) { index ->
                val thread = HandlerThread(
                    "BitmapWorker-$index",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
                thread.start()
                WorkerHandler(thread.looper)
            }

            Log.w(
                ART_LOG_TAG,
                "ART_PROVIDER_INIT workers=$WORKER_COUNT useHardwareBitmap=$useHardwareBitmap cacheMax=${memoryCache.maxSizeBytes}"
            )
            Log.d(
                TAG,
                "Initialized workers=$WORKER_COUNT, useHardwareBitmap=$useHardwareBitmap, cacheMax=${memoryCache.maxSizeBytes}"
            )
            PowerTraceLogger.bitmapProviderInit(WORKER_COUNT, memoryCache.maxSizeBytes.toLong())
        }
    }

    fun load(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
        providerAliasKey: String = "",
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            surface = surface,
            callback = callback,
            allowHiRes = true,
            providerAliasKey = providerAliasKey
        )
    }

    fun loadThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
        providerAliasKey: String = "",
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            surface = surface,
            callback = callback,
            allowHiRes = false,
            providerAliasKey = providerAliasKey
        )
    }

    fun updatePowerListViewport(
        cacheKeys: Collection<String>,
        active: Boolean = true,
        suspendDecoding: Boolean = false,
        allowIndexer: Boolean = active && !suspendDecoding
    ): Long {
        val limitedKeys = if (!suspendDecoding && active && cacheKeys.isNotEmpty()) {
            cacheKeys.take(MAX_POWER_LIST_VIEWPORT_KEYS)
        } else {
            emptyList()
        }
        val nextActive = !suspendDecoding && active && limitedKeys.isNotEmpty()
        val nextIndexerAllowed = nextActive && allowIndexer
        var signature = 17
        for (key in limitedKeys) signature = signature * 31 + key.hashCode()

        if (
            powerListViewportActive == nextActive &&
            powerListDecodeSuspended == suspendDecoding &&
            powerListIndexerAllowed == nextIndexerAllowed &&
            powerListViewportLastKeyCount == limitedKeys.size &&
            powerListViewportLastKeysSignature == signature &&
            (limitedKeys.isEmpty() || powerListViewportCacheKeys.containsAll(limitedKeys))
        ) {
            return powerListViewportLastGeneration
        }

        powerListViewportCacheKeys.clear()
        powerListDecodeSuspended = suspendDecoding
        if (limitedKeys.isNotEmpty()) {
            powerListViewportCacheKeys.addAll(limitedKeys)
        }
        powerListViewportActive = nextActive
        powerListIndexerAllowed = nextIndexerAllowed
        powerListViewportLastKeysSignature = signature
        powerListViewportLastKeyCount = limitedKeys.size
        purgeObsoleteViewportWaiters()
        val generation = powerListViewportGeneration.incrementAndGet()
        powerListViewportLastGeneration = generation
        trace("VIEWPORT_UPDATE generation=$generation active=$powerListViewportActive suspended=$powerListDecodeSuspended indexer=$powerListIndexerAllowed keys=${powerListViewportCacheKeys.size}/${cacheKeys.size}")
        return generation
    }

    fun thumbnailCacheKey(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = ""
    ): String {
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = false,
            priority = BitmapRequest.Priority.LOADING_LIST
        )
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return "${stableArtworkCacheSourceKey(key, providerAliasKey)}_${bucket}"
    }

    fun hasRecentThumbnailFailure(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = ""
    ): Boolean {
        if (key.isBlank()) return false
        // Keep no-art decisions on the file-version source. A broad album/folder alias should not
        // suppress probing another track unless the scanner later proves the whole entity is no-art.
        val sourceKey = stableArtworkCacheSourceKey(key)
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = false,
            priority = BitmapRequest.Priority.LOADING_LIST
        )
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return hasRecentFailure(sourceKey, "${sourceKey}_${bucket}")
    }

    private fun isCurrentPowerListViewportKey(cacheKey: String): Boolean {
        if (cacheKey.isBlank()) return false
        if (powerListDecodeSuspended) return false
        if (!powerListViewportActive) return false
        return powerListViewportCacheKeys.contains(cacheKey)
    }

    private fun isPowerListIndexerAllowedFor(cacheKey: String): Boolean {
        if (!powerListIndexerAllowed) return false
        return isCurrentPowerListViewportKey(cacheKey)
    }

    fun loadViewportThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        providerAliasKey: String = "",
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            surface = ArtworkSurface.List,
            callback = callback,
            allowHiRes = false,
            viewportRequired = true,
            providerAliasKey = providerAliasKey
        )
    }

    fun loadPrefetchThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = "",
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = BitmapRequest.Priority.LOADING_PREFETCH,
            surface = ArtworkSurface.Prefetch,
            callback = callback,
            allowHiRes = false,
            providerAliasKey = providerAliasKey
        )
    }

    private fun loadInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority,
        surface: ArtworkSurface,
        callback: ((Bitmap?) -> Unit)?,
        allowHiRes: Boolean,
        viewportRequired: Boolean = false,
        providerAliasKey: String = ""
    ): BitmapRequest {
        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val target = resolveAlbumArtTarget(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            allowHiRes = allowHiRes,
            priority = priority
        )
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val failureSourceKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"
        val acceptToken = ArtworkRecordRegistry.tokenFor(
            sourceVersionKey = providerKey,
            cacheKey = cacheKey,
            bucket = bucket,
            uiRevision = artworkRevision
        )

        val request = BitmapRequest(
            key = providerKey,
            decodeKey = key,
            targetWidth = actualWidth,
            targetHeight = actualHeight,
            priority = priority,
            callback = callback,
            surface = surface,
            artworkToken = acceptToken
        ).apply {
            this.viewportRequired = viewportRequired
        }
        // Viewport requests are admitted only by the parent-published visible key set.  Do not let
        // individual cells arm themselves here: during fast scroll a recycled/off-screen cell can
        // otherwise make its own stale request look visible and keep decoding in the background.
        request.viewportGeneration = powerListViewportGeneration.get()
        val seq = traceSeq.incrementAndGet()
        request.traceSeq = seq
        trace("REQUEST seq=$seq surface=$surface priority=$priority viewportRequired=$viewportRequired allowHiRes=$allowHiRes input=${targetWidth}x${targetHeight} actual=${actualWidth}x${actualHeight} bucket=$bucket provider=${providerKey.tailForTrace()} decode=${key.tailForTrace()}")

        if (key.isBlank()) {
            trace("SKIP_BLANK seq=$seq size=${actualWidth}x${actualHeight} priority=$priority")
            PowerTraceLogger.bitmapRequest(
                state = "skip_blank",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            postNull(request)
            return request
        }

        if (hasRecentFailure(failureSourceKey, "${failureSourceKey}_${bucket}")) {
            logFailCacheThrottled(failureSourceKey)
            trace("NOT_FOUND_SENTINEL_HIT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority key=${key.tailForTrace()}")
            PowerTraceLogger.bitmapRequest(
                state = "not_found_sentinel",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            postNull(request)
            return request
        }

        val cached = memoryCache.get(request.cacheKey)
        if (cached != null && !cached.isRecycled) {
            trace("CACHE_HIT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority key=${key.tailForTrace()}")
            PowerTraceLogger.bitmapRequest(
                state = "cache_hit",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            request.transitionTo(BitmapRequest.State.AVAILABLE)
            mainHandler.post {
                if (!request.isCancelled && isArtworkAcceptTokenCurrent(request)) {
                    callback?.invoke(cached)
                }
            }
            return request
        }

        if (viewportRequired && !isCurrentPowerListViewportKey(request.cacheKey)) {
            trace("SKIP_VIEWPORT seq=$seq cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
            PowerTraceLogger.bitmapRequest(
                state = "skip_viewport",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            postNull(request)
            return request
        }

        val handlers = workerHandlers
        if (handlers.isEmpty()) {
            Log.e(TAG, "BitmapProvider is not initialized")
            PowerTraceLogger.bitmapRequest(
                state = "provider_not_ready",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            // 不写 failedCache，否则 init 后同尺寸封面 5 分钟内继续失败
            postNull(request)
            return request
        }

        request.transitionTo(BitmapRequest.State.CHECKING_MEMORY)
        addWaitingRequest(request)

        val flightKey = request.inFlightKey
        val isOwner = inFlightKeys.add(flightKey)
        if (!isOwner) {
            val ownerToken = inFlightTokens[flightKey]
            if (!ArtworkRecordRegistry.canShareInFlight(ownerToken, request.artworkToken)) {
                removeWaitingRequest(request)
                trace("REJECT_JOIN_STALE_FLIGHT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
                postNull(request)
                return request
            }
            val ownerPriority = inFlightPriorities[flightKey]
            if (shouldPromoteInFlight(ownerPriority, priority) && promotedInFlightKeys.add(flightKey)) {
                request.promotedInFlight = true
                trace("PROMOTE_IN_FLIGHT seq=$seq ownerPriority=$ownerPriority newPriority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
                enqueueRequest(request, handlers, forceFront = true)
                return request
            }
            trace("JOIN_IN_FLIGHT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
            PowerTraceLogger.bitmapRequest(
                state = "join_in_flight",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            if (ENABLE_BITMAP_TRACE) Log.d(TAG, "IN_FLIGHT_JOIN key=${key.takeLast(40)} cacheKey=${request.cacheKey.takeLast(60)}")
            return request
        }
        request.inFlightOwner = true
        inFlightPriorities[flightKey] = priority
        inFlightTokens[flightKey] = request.artworkToken

        PowerTraceLogger.bitmapRequest(
            state = "queued",
            priority = priority.name,
            size = "${actualWidth}x${actualHeight}",
            key = key
        )
        enqueueRequest(request, handlers, forceFront = false)

        return request
    }

    private fun shouldPromoteInFlight(
        ownerPriority: BitmapRequest.Priority?,
        newPriority: BitmapRequest.Priority
    ): Boolean {
        return ownerPriority != null &&
                ownerPriority.level > BitmapRequest.Priority.LOADING_LIST.level &&
                newPriority.level <= BitmapRequest.Priority.LOADING_LIST.level
    }

    private fun enqueueRequest(
        request: BitmapRequest,
        handlers: Array<WorkerHandler>,
        forceFront: Boolean
    ) {
        val idx = workerIndex++ and 0x7FFFFFFF
        val selectedWorkerIndex = when (request.priority) {
            // Keep current viewport artwork on a dedicated serial lane. This preserves visible-order
            // loading and prevents old idle prefetch messages from blocking the first screen.
            BitmapRequest.Priority.LOADING_LIST -> 0
            BitmapRequest.Priority.LOADING_LIST_DELAYED,
            BitmapRequest.Priority.LOADING_PREFETCH -> if (handlers.size > 1) handlers.lastIndex else 0
            else -> idx % handlers.size
        }.coerceIn(0, handlers.lastIndex)
        val handler = handlers[selectedWorkerIndex]
        val msg = handler.obtainMessage(MSG_LOAD, request)

        if (ENABLE_BITMAP_TRACE) {
            Log.d(
                TAG,
                "ENQUEUE key=${request.key.takeLast(40)} size=${request.targetWidth}x${request.targetHeight} worker=$selectedWorkerIndex"
            )
        }
        val front = forceFront || request.priority.level < BitmapRequest.Priority.LOADING_LIST.level
        trace("ENQUEUE seq=${request.traceSeq} worker=$selectedWorkerIndex front=$front viewport=${request.viewportRequired} size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} key=${request.key.tailForTrace()}")

        if (front) {
            handler.sendMessageAtFrontOfQueue(msg)
        } else {
            handler.sendMessage(msg)
        }
    }

    fun execute(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (key.isBlank()) return null

        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = true,
            priority = BitmapRequest.Priority.LOADING_WIDGET
        )
        val actualWidth = target.width
        val actualHeight = target.height

        val providerKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"
        if (hasRecentFailure(providerKey, cacheKey)) return null

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val bitmap = decodeBitmap(
            storageKey = providerKey,
            decodeKey = key,
            targetWidth = actualWidth,
            targetHeight = actualHeight,
            surface = ArtworkSurface.Widget
        ).bitmap

        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
            putTwoSlotAlbumArtCache(providerKey, key, bitmap, bucket)
        }

        return bitmap
    }

    /**
     * Exact-size peek for the Coil PowerList lane.
     *
     * The legacy LOADING_LIST resolver clamps normal list thumbnails to 384..512. That is correct
     * for the old callback lane, but the Coil branch now deliberately asks for mode-specific visual
     * targets (small list 192, normal list 384, zoomed list 512, grid 384/512/784). Keep this path
     * file-identity only and avoid broad album aliases.
     */
    fun peekPowerListThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (key.isBlank()) return null
        val target = resolvePowerListThumbnailTarget(targetWidth, targetHeight)
        val providerKey = stableArtworkCacheSourceKey(key)
        val cacheKey = powerListThumbnailCacheKey(providerKey, target.width, target.height)

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled && bitmapCoversPowerListTarget(cached, target.width, target.height)) {
                return cached
            }
        }

        return AlbumArtCache.getBestAvailable(providerKey)
            ?.takeIf { !it.isRecycled && bitmapCoversPowerListTarget(it, target.width, target.height) }
    }

    /**
     * Stable first-frame fallback for the Coil PowerList lane.
     *
     * Coil's Compose painter can legitimately have an empty state on the first composition even
     * when the backend has a smaller/previous thumbnail already cached.  That is fine for normal
     * image loading, but it shows up as a one-frame blink when PowerList leaves the pinch/zoom
     * transition layer and rebinds the settled list/grid cells.  Prefer an exact PowerList thumb;
     * if it is not ready yet, return the best file-identity bitmap already known by the shared
     * artwork-style cache.  The real Coil request still runs on top and upgrades the image silently.
     */
    fun peekPowerListFallbackThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val exact = peekPowerListThumbnail(key, targetWidth, targetHeight)
        if (exact != null && !exact.isRecycled) return exact
        if (key.isBlank()) return null
        val providerKey = stableArtworkCacheSourceKey(key)
        return AlbumArtCache.getBestAvailable(providerKey)
            ?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.getBestAvailable(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Synchronous exact-size decode entry used by the Coil PowerList lane.
     *
     * Coil owns request cancellation and painter state. BitmapProvider still owns RawSMusic's
     * source order, disk thumbnail writes, exact memory slots and no-art sentinel. This bypasses
     * the old viewport waiter/callback chain and also bypasses the 384..512 LOADING_LIST clamp so
     * grid-two can really request 784px instead of silently falling back to 512px.
     */
    fun executeThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (key.isBlank()) return null

        val target = resolvePowerListThumbnailTarget(targetWidth, targetHeight)
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = stableArtworkCacheSourceKey(key)
        val bucket = maxOf(actualWidth, actualHeight)
        val cacheKey = powerListThumbnailCacheKey(providerKey, actualWidth, actualHeight)
        val failureSourceKey = stableArtworkCacheSourceKey(key)
        val failureCacheKey = cacheKey

        if (hasRecentFailure(failureSourceKey, failureCacheKey)) return null

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled && bitmapCoversPowerListTarget(cached, actualWidth, actualHeight)) return cached
        }
        AlbumArtCache.getBestAvailable(providerKey)?.let { cached ->
            if (!cached.isRecycled && bitmapCoversPowerListTarget(cached, actualWidth, actualHeight)) return cached
        }

        val decodeResult = decodeBitmap(
            storageKey = providerKey,
            decodeKey = key,
            targetWidth = actualWidth,
            targetHeight = actualHeight,
            surface = ArtworkSurface.List
        )
        val bitmap = decodeResult.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
            putTwoSlotAlbumArtCache(providerKey, key, bitmap, bucket)
            return bitmap
        }
        if (decodeResult.terminalNoArt) {
            rememberFailure(failureSourceKey, failureCacheKey)
        }
        return null
    }

    private fun resolvePowerListThumbnailTarget(
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        val side = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
            .coerceAtMost(AlbumArtTiers.HI_RES_SIDE)
        return Size(side, side)
    }

    private fun powerListThumbnailCacheKey(
        providerKey: String,
        targetWidth: Int,
        targetHeight: Int
    ): String {
        val side = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        return "${providerKey}_pl${side}"
    }

    private fun bitmapCoversPowerListTarget(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        if (bitmap.isRecycled) return false
        val actual = maxOf(bitmap.width, bitmap.height)
        val requested = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        return actual >= (requested * 0.86f).roundToInt().coerceAtLeast(1)
    }

    fun peek(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = ""
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = true, providerAliasKey = providerAliasKey)
    }

    fun peekThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = ""
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = false, providerAliasKey = providerAliasKey)
    }

    private fun peekInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        allowHiRes: Boolean,
        providerAliasKey: String = ""
    ): Bitmap? {
        if (key.isBlank()) return null

        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val target = resolveAlbumArtTarget(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            allowHiRes = allowHiRes,
            priority = if (allowHiRes) BitmapRequest.Priority.LOADING_WIDGET else BitmapRequest.Priority.LOADING_LIST
        )
        val actualWidth = target.width
        val actualHeight = target.height

        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val rawKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"

        return memoryCache.get(cacheKey)
            ?: AlbumArtCache.getBestAvailable(providerKey)?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.getBestAvailable(rawKey)?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.getBestAvailable(key)?.takeIf { !it.isRecycled }
    }

    fun peekAny(key: String, providerAliasKey: String = ""): Bitmap? {
        if (key.isBlank()) return null
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val rawKey = stableArtworkCacheSourceKey(key)
        return AlbumArtCache.getBestAvailable(providerKey)?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.getBestAvailable(rawKey)?.takeIf { !it.isRecycled }
            ?: AlbumArtCache.getBestAvailable(key)?.takeIf { !it.isRecycled }
            ?: memoryCache.getAnyForSource(providerKey)
            ?: memoryCache.getAnyForSource(rawKey)
            ?: memoryCache.getAnyForSource(key)
    }

    /** Returns the full-resolution image source already selected for this artwork key, if known. */
    fun originalArtworkSourcePath(key: String, providerAliasKey: String = ""): String? {
        if (key.isBlank()) return null
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val rawKey = stableArtworkCacheSourceKey(key)
        val acceptedKinds = if (ArtworkSourceSelectionPolicy.isAudioArtworkKey(key)) {
            ArtworkSourceSelectionPolicy.embeddedIndexedKinds
        } else {
            ArtworkSourceSelectionPolicy.allIndexedKinds
        }
        return ArtworkSourceIndex.sourcePathFor(providerKey, acceptedKinds)
            ?: ArtworkSourceIndex.sourcePathFor(rawKey, acceptedKinds)
            ?: ArtworkSourceIndex.sourcePathFor(key, acceptedKinds)
    }

    /**
     * Project-style attach API for UI surfaces. Prefer this over raw peek() in components that
     * keep a bitmap across frames. The returned handle must be released on detach/replacement.
     */
    fun acquire(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        providerAliasKey: String = ""
    ): ArtworkHandle? {
        return acquireInternal(key, targetWidth, targetHeight, allowHiRes = true, surface = surface, providerAliasKey = providerAliasKey)
    }

    fun acquireThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.List,
        providerAliasKey: String = ""
    ): ArtworkHandle? {
        return acquireInternal(key, targetWidth, targetHeight, allowHiRes = false, surface = surface, providerAliasKey = providerAliasKey)
    }

    fun acquireAny(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        providerAliasKey: String = ""
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val rawKey = stableArtworkCacheSourceKey(key)
        // Phase 3d: exact-size memory slots are ref-aware too. Prefer them when present so list
        // and player surfaces protect the exact bucket they are drawing, then fall back to the
        // low/high owner for instant placeholders.
        return memoryCache.acquireAnyForSource(providerKey, surface)
            ?: memoryCache.acquireAnyForSource(rawKey, surface)
            ?: memoryCache.acquireAnyForSource(key, surface)
            ?: AlbumArtCache.acquireBestAvailable(providerKey, surface)
            ?: AlbumArtCache.acquireBestAvailable(rawKey, surface)
            ?: AlbumArtCache.acquireBestAvailable(key, surface)
    }

    fun acquireLoaded(
        key: String,
        bitmap: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? {
        if (key.isBlank() || bitmap == null || bitmap.isRecycled) return null
        val providerKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        val cacheKey = "${providerKey}_${bucket}"
        memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
        putTwoSlotAlbumArtCache(providerKey, key, bitmap, bucket)
        return memoryCache.acquire(cacheKey, surface)
            ?: acquireHandleForBucket(providerKey, key, bucket, surface)
    }

    private fun acquireInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        allowHiRes: Boolean,
        surface: ArtworkSurface,
        providerAliasKey: String = ""
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = allowHiRes,
            priority = if (allowHiRes) BitmapRequest.Priority.LOADING_WIDGET else BitmapRequest.Priority.LOADING_LIST
        )
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val rawKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"

        memoryCache.acquire(cacheKey, surface)?.let { exact ->
            putTwoSlotAlbumArtCache(providerKey, rawKey, exact.bitmap, bucket)
            return exact
        }

        return acquireHandleForBucket(providerKey, rawKey, bucket, surface)
    }

    private fun acquireHandleForBucket(
        providerKey: String,
        rawKey: String,
        bucket: Int,
        surface: ArtworkSurface
    ): ArtworkHandle? {
        val preferredHigh = bucket >= AlbumArtTiers.HI_RES_SIDE
        val primary = if (preferredHigh) {
            AlbumArtCache.acquireHiRes(providerKey, surface) ?: AlbumArtCache.acquireBestAvailable(providerKey, surface)
        } else {
            AlbumArtCache.acquireLowRes(providerKey, surface) ?: AlbumArtCache.acquireBestAvailable(providerKey, surface)
        }
        if (primary != null) return primary
        if (rawKey == providerKey) return null
        return if (preferredHigh) {
            AlbumArtCache.acquireHiRes(rawKey, surface) ?: AlbumArtCache.acquireBestAvailable(rawKey, surface)
        } else {
            AlbumArtCache.acquireLowRes(rawKey, surface) ?: AlbumArtCache.acquireBestAvailable(rawKey, surface)
        }
    }

    /**
     * Warm the current playback artwork in project-style tiers: bind one low-res wrapper quickly,
     * then request one high-res wrapper for the player.  Do not enqueue 384/512/1024 together for
     * every song change: the design keeps low/high slots per artwork record and lets high replace low when
     * it is ready, instead of spawning several independent size flights.
     */
    fun warmPlaybackArt(key: String) {
        if (key.isBlank()) return
        val providerKey = stableArtworkCacheSourceKey(key)
        val hasHigh = hasProviderTier(providerKey, key, minSide = AlbumArtTiers.HI_RES_SIDE)
        if (hasHigh) return

        val hasLow = hasProviderTier(providerKey, key, minSide = AlbumArtTiers.LOW_RES_MIN_SIDE)
        if (!hasLow) {
            loadInternal(
                key = key,
                targetWidth = AlbumArtTiers.LOW_RES_NORMAL_CAP,
                targetHeight = AlbumArtTiers.LOW_RES_NORMAL_CAP,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Playback,
                callback = null,
                allowHiRes = false
            )
        }

        loadInternal(
            key = key,
            targetWidth = AlbumArtTiers.HI_RES_SIDE,
            targetHeight = AlbumArtTiers.HI_RES_SIDE,
            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
            surface = ArtworkSurface.Playback,
            callback = null,
            allowHiRes = true
        )
    }

    private fun hasProviderTier(
        providerKey: String,
        rawKey: String,
        minSide: Int
    ): Boolean {
        fun Bitmap?.matches(): Boolean = this != null && !this.isRecycled && maxOf(this.width, this.height) >= minSide
        if (minSide >= AlbumArtTiers.HI_RES_SIDE) {
            if (AlbumArtCache.hasHiRes(providerKey)) return true
            if (rawKey != providerKey && AlbumArtCache.hasHiRes(rawKey)) return true
        } else {
            if (AlbumArtCache.hasLowRes(providerKey) || AlbumArtCache.hasHiRes(providerKey)) return true
            if (rawKey != providerKey && (AlbumArtCache.hasLowRes(rawKey) || AlbumArtCache.hasHiRes(rawKey))) return true
        }
        if (memoryCache.getAnyForSource(providerKey).matches()) return true
        if (rawKey != providerKey && memoryCache.getAnyForSource(rawKey).matches()) return true
        return false
    }

    /**
     * Full-cover zoom is opened by a gesture. Pre-warm the large target separately so the gesture
     * layer does not swap from empty -> bitmap while the user's fingers are still on screen.
     */
    fun warmFullCoverArt(key: String) {
        if (key.isBlank()) return
        warmPlaybackArt(key)
        loadInternal(
            key = key,
            targetWidth = AlbumArtTiers.FULL_RES_SIDE,
            targetHeight = AlbumArtTiers.FULL_RES_SIDE,
            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
            surface = ArtworkSurface.Fullscreen,
            callback = null,
            allowHiRes = true
        )
    }

    fun cancel(request: BitmapRequest, keepDecoding: Boolean = false) {
        // Visible artwork view requests are not gated by "scroll settled", but stale
        // queued work for cells that have already left the viewport must not block the serial list
        // lane. Keep only work that has actually entered DECODING_FILES; queued requests are cheap
        // to drop and will be re-peeked/requeued after the grid settles.
        val alreadyDecoding = request.state == BitmapRequest.State.DECODING_FILES
        val keepRunning = keepDecoding && alreadyDecoding
        request.cancel(keepAlive = keepRunning)
        removeWaitingRequest(request)

        if (!keepRunning) {
            val noUsefulWaiters = !hasWaitingRequests(request.inFlightKey)
            workerHandlers.forEach { handler ->
                if (noUsefulWaiters) {
                    handler.removeMessages(MSG_LOAD, request)
                }
                handler.obtainMessage(MSG_CANCEL, request).sendToTarget()
            }
            if (noUsefulWaiters) {
                clearInFlightForRequest(request)
            }
        }
    }


    fun notifyLibraryArtworkChanged(reason: String = "library_changed") {
        ArtworkRecordRegistry.invalidateAll()
        artworkRevision++
        failedCache.clear()
        failedSourceCache.clear()
        failedLogLastAt.clear()
        EmbeddedArtworkSourceCache.clear(appContext)
        EmbeddedArtworkRegion.clear()
        ArtworkSourceIndex.clear()
        powerListViewportGeneration.incrementAndGet()
        trace("ARTWORK_REVISION reason=$reason revision=$artworkRevision")
        PowerTraceLogger.bitmapRequest(
            state = "artwork_revision",
            priority = BitmapRequest.Priority.LOADING_WIDGET.name,
            size = "-",
            key = reason
        )
    }

    fun clear() {
        memoryCache.clear()
        AlbumArtCache.clear()
        EmbeddedArtworkSourceCache.clear(appContext)
        EmbeddedArtworkRegion.clear()
        ArtworkSourceIndex.clear()
        BitmapPool.clear()
        waitingRequests.clear()
        failedCache.clear()
        failedSourceCache.clear()
        failedLogLastAt.clear()
        artworkIndexerKeys.clear()
        artworkRevisionCoalescePending.set(false)
        inFlightKeys.clear()
        inFlightPriorities.clear()
        inFlightTokens.clear()
        promotedInFlightKeys.clear()
        ArtworkRecordRegistry.clear()
        powerListViewportCacheKeys.clear()
        powerListViewportActive = false
        powerListDecodeSuspended = false
        powerListIndexerAllowed = false
        powerListViewportGeneration.incrementAndGet()
    }

    /** Drop only reconstructable in-memory artwork state, preserving disk caches and workers. */
    fun trimMemory() {
        memoryCache.clear()
        AlbumArtCache.clear()
        EmbeddedArtworkRegion.clear()
        BitmapPool.clear()
        powerListViewportCacheKeys.clear()
        powerListViewportGeneration.incrementAndGet()
    }

    /**
     * Invalidate all artwork state for a file after the user manually embeds/changes album art, or
     * after an explicit rescan. This is the safe counterpart to the not-found sentinel: no-art is
     * remembered per file version, but an explicit artwork change must be allowed to probe again
     * immediately instead of waiting for the TTL.
     */
    fun invalidateArtwork(key: String) {
        if (key.isBlank()) return
        invalidateArtworkKeys(listOf(key), reason = "explicit_key")
    }

    /**
     * Invalidate artwork for an AudioFile-style identity.  Call this from every path that can
     * change album-art ownership: manual cover pick/restore, metadata writes that rewrite the
     * audio file, scanner refresh and external storage remount.  We include both the old DB
     * version and the current on-disk version so late workers from either epoch are rejected.
     */
    fun invalidateArtworkForSong(
        path: String,
        fileSize: Long = 0L,
        dateModified: Long = 0L,
        albumArtPath: String? = null,
        reason: String = "song_artwork_changed"
    ) {
        if (path.isBlank() && albumArtPath.isNullOrBlank()) return
        val keys = linkedSetOf<String>()
        if (path.isNotBlank()) {
            keys += path
            keys += "audio://$path"
            if (fileSize > 0L || dateModified > 0L) {
                keys += "$path|$fileSize|$dateModified"
                keys += "audio://$path|$fileSize|$dateModified"
            }
            val file = File(path)
            if (file.exists()) {
                val absolute = try { file.absolutePath } catch (_: Exception) { path }
                val canonical = try { file.canonicalPath } catch (_: Exception) { absolute }
                keys += absolute
                keys += canonical
                keys += "$canonical|${file.length()}|${file.lastModified()}"
                keys += "audio://$canonical|${file.length()}|${file.lastModified()}"
            }
        }
        val art = albumArtPath?.trim().orEmpty()
        if (art.isNotBlank()) {
            keys += art
            if (art.startsWith("file://")) keys += art.removePrefix("file://")
        }
        invalidateArtworkKeys(keys, reason = reason)
    }

    fun invalidateArtworkKeys(
        keys: Collection<String>,
        reason: String = "explicit_keys"
    ) {
        val candidates = linkedSetOf<String>()
        var firstSourceKey = ""
        for (key in keys) {
            if (key.isBlank()) continue
            val sourceKey = canonicalFailureSourceKey(key)
            if (firstSourceKey.isBlank()) firstSourceKey = sourceKey
            candidates += artworkKeyCandidates(key, sourceKey)
        }
        if (candidates.isEmpty()) return

        ArtworkRecordRegistry.invalidateSources(candidates)

        var removedMemory = 0
        for (candidate in candidates) {
            removedMemory += memoryCache.removeForSource(candidate)
            AlbumArtCache.remove(candidate)
        }

        val sourceKey = firstSourceKey.ifBlank { candidates.firstOrNull().orEmpty() }
        val removedFailed = removeFailureSentinelsFor(candidates, sourceKey)
        val removedWaiting = removeQueuedStateFor(candidates)
        val removedDisk = deleteDiskThumbnailsFor(candidates)
        val removedSourceArt = EmbeddedArtworkSourceCache.removeForSources(appContext, candidates)
        val removedSourceIndex = ArtworkSourceIndex.removeAll(candidates)
        candidates.forEach { EmbeddedArtworkRegion.invalidate(pathPartFromArtworkKey(it)) }

        artworkRevision++
        powerListViewportGeneration.incrementAndGet()

        Log.d(
            TAG,
            "INVALIDATE_ARTWORK reason=$reason source=${sourceKey.tailForTrace()} candidates=${candidates.size} memory=$removedMemory failed=$removedFailed queued=$removedWaiting disk=$removedDisk sourceArt=$removedSourceArt sourceIndex=$removedSourceIndex revision=$artworkRevision"
        )
        PowerTraceLogger.bitmapRequest(
            state = "invalidate_artwork",
            priority = BitmapRequest.Priority.LOADING_WIDGET.name,
            size = "-",
            key = "$reason:${sourceKey.tailForTrace()}"
        )
    }

    fun getPool(): BitmapPool = BitmapPool

    fun getMemoryCache(): SizeSlotCache = memoryCache

    private fun addWaitingRequest(request: BitmapRequest) {
        waitingRequests
            .getOrPut(request.inFlightKey) { CopyOnWriteArrayList() }
            .add(request)
    }

    private fun removeWaitingRequest(request: BitmapRequest) {
        val list = waitingRequests[request.inFlightKey] ?: return
        list.remove(request)

        if (list.isEmpty()) {
            waitingRequests.remove(request.inFlightKey, list)
        }
    }

    private fun hasWaitingRequests(flightKey: String): Boolean {
        return waitingRequests[flightKey]?.any { !it.isCancelled } == true
    }

    private fun isArtworkAcceptTokenCurrent(request: BitmapRequest): Boolean {
        return ArtworkRecordRegistry.isCurrent(request.artworkToken, artworkRevision)
    }

    private fun clearInFlightForRequest(request: BitmapRequest) {
        val flightKey = request.inFlightKey
        if (request.inFlightOwner) {
            inFlightKeys.remove(flightKey)
            inFlightPriorities.remove(flightKey)
            inFlightTokens.remove(flightKey)
        }
        if (request.promotedInFlight) {
            promotedInFlightKeys.remove(flightKey)
        }
    }

    private fun deliverResult(
        ownerRequest: BitmapRequest,
        bitmap: Bitmap?
    ) {
        val tokenCurrent = isArtworkAcceptTokenCurrent(ownerRequest)
        val stillUseful = isPowerListRequestStillUseful(ownerRequest) && tokenCurrent
        // Cache successful decodes even when the originating PowerList row has already been
        // detached.  In a 4-column fling Compose can dispose/rebind the whole visible window before
        // the worker posts back; dropping the bitmap here makes the next bind enqueue the same file
        // again, so the cell sits in DISPLAY_EMPTY_NEW_ID/loading forever.  The token still guards
        // explicit artwork changes; stale UI callbacks remain filtered below.
        if (bitmap != null && !bitmap.isRecycled && tokenCurrent) {
            memoryCache.put(
                key = ownerRequest.cacheKey,
                bitmap = bitmap,
                bucket = ownerRequest.bucket,
                sourceKey = ownerRequest.key
            )
            putTwoSlotAlbumArtCache(ownerRequest, bitmap)
            if (ownerRequest.surface == ArtworkSurface.List) {
                schedulePowerListCacheRebind()
            }
        }

        mainHandler.post {
            val requests = waitingRequests.remove(ownerRequest.inFlightKey).orEmpty()
            trace("CALLBACK_DISPATCH ownerSeq=${ownerRequest.traceSeq} waiters=${requests.size} result=${bitmap != null} key=${ownerRequest.key.tailForTrace()}")

            if (requests.isEmpty() &&
                !ownerRequest.isCancelled &&
                isPowerListRequestStillUseful(ownerRequest) &&
                isArtworkAcceptTokenCurrent(ownerRequest)
            ) {
                // Defensive project-style owner fallback.  During fast Compose rebinding the
                // waiting list can be removed by a detach/viewport purge after the worker has
                // already warmed provider caches but before the main callback is dispatched.
                // Without this fallback the row waits for a later scroll/recomposition to re-peek
                // the cache, which looks like "decoded, disappeared, then decoded/appeared again".
                if (bitmap != null && !bitmap.isRecycled) {
                    ownerRequest.transitionTo(BitmapRequest.State.AVAILABLE)
                }
                trace("CALLBACK_OWNER_FALLBACK seq=${ownerRequest.traceSeq} result=${bitmap != null} key=${ownerRequest.key.tailForTrace()}")
                ownerRequest.callback?.invoke(bitmap)
            }

            for (request in requests) {
                if (request.isCancelled) continue
                if (!isPowerListRequestStillUseful(request)) continue
                if (!isArtworkAcceptTokenCurrent(request)) {
                    trace("CALLBACK_DROP_STALE_TOKEN seq=${request.traceSeq} cacheKey=${request.cacheKey.tailForTrace()} key=${request.key.tailForTrace()}")
                    continue
                }

                if (bitmap != null && !bitmap.isRecycled) {
                    request.transitionTo(BitmapRequest.State.AVAILABLE)
                }

                request.callback?.invoke(bitmap)
            }
        }
    }

    private fun schedulePowerListCacheRebind() {
        if (!powerListCacheRevisionPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            powerListCacheRevisionPending.set(false)
            powerListCacheRevision++
            trace("POWER_LIST_CACHE_READY revision=$powerListCacheRevision")
        }, 80L)
    }

    private fun putTwoSlotAlbumArtCache(
        request: BitmapRequest,
        bitmap: Bitmap
    ) {
        val providerKey = request.key
        putTwoSlotAlbumArtCache(providerKey, request.decodeKey, bitmap, request.bucket)
    }

    private fun putTwoSlotAlbumArtCache(
        providerKey: String,
        rawKey: String,
        bitmap: Bitmap,
        bucket: Int
    ) {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val hiRes = maxSide >= AlbumArtTiers.HI_RES_SIDE || bucket >= AlbumArtTiers.HI_RES_SIDE
        if (hiRes) {
            AlbumArtCache.putHiRes(providerKey, bitmap)
            if (rawKey != providerKey) AlbumArtCache.putHiRes(rawKey, bitmap)
        } else {
            AlbumArtCache.putLowRes(providerKey, bitmap)
            if (rawKey != providerKey) AlbumArtCache.putLowRes(rawKey, bitmap)
        }
    }

    private fun postNull(request: BitmapRequest) {
        mainHandler.post {
            if (!request.isCancelled && isArtworkAcceptTokenCurrent(request)) {
                request.callback?.invoke(null)
            }
        }
    }

    private fun getPreferredConfig(): Bitmap.Config {
        val forceArgb = try {
            AppPreferences.AlbumArt.forceArgb8888
        } catch (_: Exception) {
            false
        }

        return if (forceArgb || !useHardwareBitmap) {
            Bitmap.Config.ARGB_8888
        } else {
            Bitmap.Config.HARDWARE
        }
    }

    fun getHiResSize(baseSize: Int): Int {
        val useHigher = readHighResPreference()
        return AlbumArtTiers.hiResTarget(
            requestedWidth = baseSize.coerceAtLeast(1),
            requestedHeight = baseSize.coerceAtLeast(1),
            highResEnabled = useHigher
        ).maxSide
    }

    private fun resolveAlbumArtTarget(
        baseWidth: Int,
        baseHeight: Int,
        allowHiRes: Boolean,
        priority: BitmapRequest.Priority
    ): AlbumArtTiers.Target {
        val foregroundArtwork = priority.level <= BitmapRequest.Priority.LOADING_WIDGET.level
        return AlbumArtTiers.resolve(
            requestedWidth = baseWidth,
            requestedHeight = baseHeight,
            allowHiRes = allowHiRes,
            priority = priority,
            highResEnabled = readHighResPreference() || foregroundArtwork
        )
    }

    private fun readHighResPreference(): Boolean {
        return try {
            AppPreferences.AlbumArt.useHigherRes
        } catch (_: Exception) {
            true
        }
    }

    private fun pathPartFromArtworkKey(key: String): String {
        return key
            .removePrefix("audio://")
            .removePrefix("file://")
            .substringBefore('|')
            .trim()
    }

    private fun stableDigest(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun scheduleArtworkIndex(
        storageKey: String,
        decodeKey: String,
        targetWidth: Int,
        targetHeight: Int,
        reason: String,
        sourceSurface: ArtworkSurface = ArtworkSurface.Indexer
    ) {
        if (storageKey.isBlank() || decodeKey.isBlank()) return
        val side = maxOf(targetWidth, targetHeight)
            .coerceAtLeast(AlbumArtTiers.LOW_RES_MIN_SIDE)
            .coerceAtMost(AlbumArtTiers.LOW_RES_NORMAL_CAP)
        val bucket = SizeSlotCache.computeBucket(side, side)
        val indexKey = "${storageKey}_${bucket}"
        val failureSourceKey = stableArtworkCacheSourceKey(decodeKey)
        if (hasRecentFailure(failureSourceKey, "${failureSourceKey}_${bucket}")) {
            trace("INDEX_SKIP_NO_ART_SENTINEL reason=$reason surface=$sourceSurface size=${side}x$side provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
            return
        }
        val viewportBound = sourceSurface == ArtworkSurface.List || sourceSurface == ArtworkSurface.Prefetch
        if (viewportBound && !isPowerListIndexerAllowedFor(indexKey)) {
            trace("INDEX_SKIP_UNSETTLED reason=$reason surface=$sourceSurface size=${side}x$side provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
            return
        }
        if (memoryCache.get(indexKey)?.takeIf { !it.isRecycled } != null) return
        if (!artworkIndexerKeys.add(indexKey)) return
        val acceptToken = ArtworkRecordRegistry.tokenFor(
            sourceVersionKey = storageKey,
            cacheKey = indexKey,
            bucket = bucket,
            uiRevision = artworkRevision
        )

        val handlers = workerHandlers
        if (handlers.isEmpty()) {
            artworkIndexerKeys.remove(indexKey)
            return
        }

        trace("INDEX_SCHEDULE reason=$reason surface=$sourceSurface viewportBound=$viewportBound size=${side}x$side provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
        val request = BitmapRequest(
            key = storageKey,
            decodeKey = decodeKey,
            targetWidth = side,
            targetHeight = side,
            priority = BitmapRequest.Priority.LOADING_PREFETCH,
            callback = null,
            surface = ArtworkSurface.Indexer,
            artworkToken = acceptToken
        ).apply {
            inFlightOwner = true
            viewportRequired = viewportBound
            viewportGeneration = powerListViewportGeneration.get()
            this.traceSeq = this@BitmapProvider.traceSeq.incrementAndGet()
        }

        if (!inFlightKeys.add(request.inFlightKey)) {
            // A lightweight visible request is probably finishing this same cache key now. Retry
            // after it clears inFlight so the background artwork indexer still runs once.
            artworkIndexerKeys.remove(indexKey)
            mainHandler.postDelayed({
                scheduleArtworkIndex(
                    storageKey = storageKey,
                    decodeKey = decodeKey,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    reason = "${reason}_retry",
                    sourceSurface = sourceSurface
                )
            }, 80L)
            return
        }
        inFlightPriorities[request.inFlightKey] = request.priority
        inFlightTokens[request.inFlightKey] = request.artworkToken
        enqueueRequest(request, handlers, forceFront = false)
    }

    private fun coalesceIndexerSiblingTiers(
        request: BitmapRequest,
        primaryBitmap: Bitmap?
    ) {
        if (request.surface != ArtworkSurface.Indexer) return
        if (primaryBitmap == null || primaryBitmap.isRecycled) return
        if (!isPowerListRequestStillUseful(request) || !isArtworkAcceptTokenCurrent(request)) return

        val providerKey = request.key
        var filled = 0
        for (side in INDEXER_COALESCE_SIDES) {
            val bucket = SizeSlotCache.computeBucket(side, side)
            if (bucket == request.bucket) continue
            val cacheKey = "${providerKey}_${bucket}"
            val existing = memoryCache.get(cacheKey)
            if (existing != null && !existing.isRecycled) continue

            if (!isArtworkAcceptTokenCurrent(request)) break
            val bitmap = decodeFromReusableArtworkSource(
                storageKey = providerKey,
                decodeKey = request.decodeKey,
                targetWidth = side,
                targetHeight = side
            )
            if (bitmap == null || bitmap.isRecycled) continue

            if (!isPowerListRequestStillUseful(request) || !isArtworkAcceptTokenCurrent(request)) {
                BitmapPool.recycle(bitmap)
                break
            }

            memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
            putTwoSlotAlbumArtCache(providerKey, request.decodeKey, bitmap, bucket)
            saveDiskThumbnailAsync(providerKey, side, side, bitmap)
            filled++
            trace("INDEX_COALESCE_FILL seq=${request.traceSeq} side=${side} bucket=${bucket} key=${request.key.tailForTrace()}")
        }

        if (filled > 0) {
            // Project-style artwork provider behavior: filling sibling low/normal tiers updates the
            // provider cache only.  Do not bump a global Compose revision for every row; that
            // rebinds the whole visible list and causes alpha restarts/flicker during fling.
            trace("INDEX_COALESCE_READY filled=$filled revision=$artworkRevision key=${request.key.tailForTrace()}")
        }
        if (primaryBitmap != null && !primaryBitmap.isRecycled) {
            trace("INDEX_COALESCE_PRIMARY seq=${request.traceSeq} size=${primaryBitmap.width}x${primaryBitmap.height} key=${request.key.tailForTrace()}")
        }
    }

    private fun scheduleArtworkRevisionBump(reason: String) {
        if (reason.startsWith("indexer_")) {
            trace("ARTWORK_REVISION_SKIP_PROVIDER_ONLY reason=$reason revision=$artworkRevision")
            return
        }
        if (!artworkRevisionCoalescePending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            artworkRevisionCoalescePending.set(false)
            artworkRevision++
            trace("ARTWORK_REVISION_COALESCED reason=$reason revision=$artworkRevision")
        }, 500L)
    }

    private fun decodeBitmap(
        storageKey: String,
        decodeKey: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface
    ): ArtworkDecodeResult {
        return try {
            decodeDiskThumbnail(storageKey, targetWidth, targetHeight)?.let {
                return ArtworkDecodeResult(bitmap = it, terminalNoArt = false)
            }
            if (storageKey != decodeKey) {
                decodeDiskThumbnail(decodeKey, targetWidth, targetHeight)?.let {
                    return ArtworkDecodeResult(bitmap = it, terminalNoArt = false)
                }
            }

            if (!surface.allowsSourceDecode) {
                if (surface.scheduleIndexerOnMiss) {
                    scheduleArtworkIndex(
                        storageKey = storageKey,
                        decodeKey = decodeKey,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        reason = surface.name,
                        sourceSurface = surface
                    )
                }
                trace("LIGHTWEIGHT_MISS surface=$surface size=${targetWidth}x${targetHeight} provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
                return ArtworkDecodeResult.LightweightMiss
            }

            // Audio requests may bind only an indexed embedded source here. Folder records must
            // pass through AudioArtworkDecodeCoordinator so the fallback permit is revalidated
            // after decode and before any bitmap/cache publication.
            val indexedKinds = if (ArtworkSourceSelectionPolicy.isAudioArtworkKey(decodeKey)) {
                ArtworkSourceSelectionPolicy.embeddedIndexedKinds
            } else {
                ArtworkSourceSelectionPolicy.allIndexedKinds
            }
            val decoded = decodeFromIndexedArtworkSource(
                storageKey = storageKey,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                acceptedKinds = indexedKinds
            ) ?: when {
                decodeKey.startsWith("file://") -> {
                    val path = pathPartFromArtworkKey(decodeKey.removePrefix("file://"))
                    decodeFromAnyFilePath(storageKey, path, targetWidth, targetHeight)
                }

                decodeKey.startsWith("content://") -> {
                    decodeFromUri(Uri.parse(decodeKey), targetWidth, targetHeight)
                }

                decodeKey.startsWith("audio://") -> {
                    val path = decodeKey.removePrefix("audio://").substringBefore("|")
                    decodeFromAnyFilePath(storageKey, path, targetWidth, targetHeight)
                }

                else -> {
                    decodeFromAnyFilePath(storageKey, pathPartFromArtworkKey(decodeKey), targetWidth, targetHeight)
                }
            }
            if (decoded != null && !decoded.isRecycled) {
                if (surface.allowDiskThumbnailWrite) {
                    saveDiskThumbnailAsync(storageKey, targetWidth, targetHeight, decoded)
                }
                if (surface == ArtworkSurface.Indexer) {
                    // The row request callback and provider memory/disk caches are enough to make
                    // visible artwork appear.  A global revision per indexer completion makes all
                    // PowerList rows rebind while scrolling, which is the flicker we are fixing.
                    trace("INDEXER_READY revision=$artworkRevision provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
                }
                ArtworkDecodeResult(
                    bitmap = decoded,
                    terminalNoArt = false,
                    coalesceSourceTiers = surface == ArtworkSurface.Indexer || surface == ArtworkSurface.Playback || surface == ArtworkSurface.Fullscreen
                )
            } else {
                ArtworkDecodeResult.NoArt
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmap failed: ${decodeKey.takeLast(80)} surface=$surface", e)
            ArtworkDecodeResult.NoArt
        } finally {
            if (surface == ArtworkSurface.Indexer) {
                val side = maxOf(targetWidth, targetHeight)
                    .coerceAtLeast(AlbumArtTiers.LOW_RES_MIN_SIDE)
                    .coerceAtMost(AlbumArtTiers.LOW_RES_NORMAL_CAP)
                val bucket = SizeSlotCache.computeBucket(side, side)
                artworkIndexerKeys.remove("${storageKey}_${bucket}")
            }
        }
    }

    private fun decodeFromAnyFilePath(
        storageKey: String,
        path: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val cleanPath = pathPartFromArtworkKey(path)
        val file = File(cleanPath)
        if (!file.exists() || !file.canRead()) {
            Log.d(TAG, "FILE_NOT_READABLE key=${cleanPath.takeLast(80)} exists=${file.exists()} canRead=${file.canRead()}")
            return null
        }

        if (isImageFile(cleanPath)) {
            return timedDecodeStage(
                stage = RawArtworkPolicy.DecodeStage.ImageFile,
                key = cleanPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) {
                decodeImageFile(cleanPath, targetWidth, targetHeight)?.also {
                    rememberReusableArtworkSource(
                        storageKey,
                        cleanPath,
                        ArtworkSourceSelectionPolicy.IndexedSourceKind.DirectImage,
                        shareAcrossEntity = true
                    )
                }
            }
        }

        return decodeAudioFileWithPolicyOrder(
            storageKey = storageKey,
            cleanPath = cleanPath,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    private fun decodeAudioFileWithPolicyOrder(
        storageKey: String,
        cleanPath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return AudioArtworkDecodeCoordinator.decode(
            providerKey = storageKey,
            decodeEmbedded = { stage ->
                timedDecodeStage(
                    stage = stage,
                    key = cleanPath,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                ) {
                    when (stage) {
                        RawArtworkPolicy.DecodeStage.RegionHandle ->
                            decodeEmbeddedWithRegionHandle(cleanPath, targetWidth, targetHeight)
                        RawArtworkPolicy.DecodeStage.NativeSource ->
                            decodeEmbeddedWithNativeTagLib(storageKey, cleanPath, targetWidth, targetHeight)
                        RawArtworkPolicy.DecodeStage.Ffmpeg ->
                            decodeCoverWithFfmpeg(cleanPath, targetWidth, targetHeight)
                        RawArtworkPolicy.DecodeStage.MediaMetadataRetriever ->
                            decodeEmbeddedWithMediaMetadataRetriever(cleanPath, targetWidth, targetHeight)
                        RawArtworkPolicy.DecodeStage.FolderCover,
                        RawArtworkPolicy.DecodeStage.ImageFile,
                        RawArtworkPolicy.DecodeStage.ContentImage,
                        RawArtworkPolicy.DecodeStage.DiskThumbnail -> null
                    }
                }
            },
            decodeFolder = {
                timedFolderDecodeStage(
                    key = cleanPath,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                ) {
                    decodeFolderCoverCandidate(
                        storageKey = storageKey,
                        audioPath = cleanPath,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }
            },
            discardRejectedFolder = { bitmap ->
                trace("FOLDER_FALLBACK_REJECTED provider=${storageKey.tailForTrace()} source=${cleanPath.tailForTrace()}")
                if (!bitmap.isRecycled) BitmapPool.recycle(bitmap)
            }
        )
    }

    private fun rememberReusableArtworkSource(
        storageKey: String,
        sourcePath: String,
        kind: ArtworkSourceSelectionPolicy.IndexedSourceKind,
        shareAcrossEntity: Boolean = true
    ) {
        // Folder/direct image sources are entity-safe. Extracted embedded artwork belongs to one
        // versioned audio file, so do not index it under a broad album/folder alias unless scanner
        // metadata later proves that alias owns that exact picture.
        if (!shareAcrossEntity && isEntityProviderKey(storageKey)) return
        ArtworkSourceIndex.rememberSource(storageKey, sourcePath, kind)
    }

    private fun isEntityProviderKey(key: String): Boolean {
        return key.startsWith("entity://")
    }

    private fun decodeFromIndexedArtworkSource(
        storageKey: String,
        targetWidth: Int,
        targetHeight: Int,
        acceptedKinds: Set<ArtworkSourceSelectionPolicy.IndexedSourceKind> =
            ArtworkSourceSelectionPolicy.allIndexedKinds
    ): Bitmap? {
        val sourcePath = ArtworkSourceIndex.sourcePathFor(storageKey, acceptedKinds) ?: return null
        return timedDecodeStage(
            stage = RawArtworkPolicy.DecodeStage.ImageFile,
            key = sourcePath,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { decodeImageFile(sourcePath, targetWidth, targetHeight) }
    }

    private inline fun timedFolderDecodeStage(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>?
    ): AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>? {
        val t0 = android.os.SystemClock.uptimeMillis()
        val candidate = block()
        val elapsed = android.os.SystemClock.uptimeMillis() - t0
        val bitmap = candidate?.value
        trace(
            "ARTWORK_STAGE stage=${RawArtworkPolicy.DecodeStage.FolderCover} result=${bitmap != null && !bitmap.isRecycled} elapsed=${elapsed}ms size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}"
        )
        if (ENABLE_BITMAP_TRACE) {
            Log.w(
                ART_LOG_TAG,
                "ARTWORK_STAGE stage=${RawArtworkPolicy.DecodeStage.FolderCover} result=${bitmap != null && !bitmap.isRecycled} elapsed=${elapsed}ms target=${targetWidth}x${targetHeight} key=${key.takeLast(60)}"
            )
        }
        return candidate
    }

    private inline fun timedDecodeStage(
        stage: RawArtworkPolicy.DecodeStage,
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> Bitmap?
    ): Bitmap? {
        val t0 = android.os.SystemClock.uptimeMillis()
        val bitmap = withSourceExtractionPermit(stage, key, targetWidth, targetHeight) {
            block()
        }
        val elapsed = android.os.SystemClock.uptimeMillis() - t0
        trace(
            "ARTWORK_STAGE stage=$stage result=${bitmap != null && !bitmap.isRecycled} elapsed=${elapsed}ms size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}"
        )
        if (ENABLE_BITMAP_TRACE) {
            Log.w(
                ART_LOG_TAG,
                "ARTWORK_STAGE stage=$stage result=${bitmap != null && !bitmap.isRecycled} elapsed=${elapsed}ms target=${targetWidth}x${targetHeight} key=${key.takeLast(60)}"
            )
        }
        return bitmap
    }

    private inline fun withSourceExtractionPermit(
        stage: RawArtworkPolicy.DecodeStage,
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> Bitmap?
    ): Bitmap? {
        if (!requiresSerialSourceExtraction(stage)) return block()
        val waitStart = android.os.SystemClock.uptimeMillis()
        var acquired = false
        return try {
            sourceExtractionGate.acquire()
            acquired = true
            val waited = android.os.SystemClock.uptimeMillis() - waitStart
            if (waited > 0L) {
                trace("SOURCE_GATE_ENTER stage=$stage waited=${waited}ms size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}")
            }
            block()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            trace("SOURCE_GATE_INTERRUPTED stage=$stage size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}")
            null
        } finally {
            if (acquired) sourceExtractionGate.release()
        }
    }

    private fun requiresSerialSourceExtraction(stage: RawArtworkPolicy.DecodeStage): Boolean {
        return when (stage) {
            RawArtworkPolicy.DecodeStage.NativeSource,
            RawArtworkPolicy.DecodeStage.Ffmpeg,
            RawArtworkPolicy.DecodeStage.MediaMetadataRetriever -> true
            RawArtworkPolicy.DecodeStage.ImageFile,
            RawArtworkPolicy.DecodeStage.ContentImage,
            RawArtworkPolicy.DecodeStage.DiskThumbnail,
            RawArtworkPolicy.DecodeStage.FolderCover,
            RawArtworkPolicy.DecodeStage.RegionHandle -> false
        }
    }

    private fun decodeImageFile(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val opts = threadLocalDecodeOptions.get()!!
            // bounds pass
            opts.inJustDecodeBounds = true
            opts.inSampleSize = 1
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inMutable = false

            BitmapFactory.decodeFile(filePath, opts)

            val origWidth = opts.outWidth
            val origHeight = opts.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                return null
            }

            // real decode pass
            val config = getPreferredConfig()
            opts.inJustDecodeBounds = false
            opts.inSampleSize = calculateInSampleSize(
                origWidth = origWidth,
                origHeight = origHeight,
                reqWidth = targetWidth,
                reqHeight = targetHeight
            )
            opts.inPreferredConfig = config
            opts.inMutable = config != Bitmap.Config.HARDWARE

            val decoded = BitmapFactory.decodeFile(filePath, opts) ?: return null

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.e(TAG, "decodeImageFile failed: $filePath", e)
            null
        }
    }

    private fun decodeEmbeddedWithRegionHandle(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val cleanPath = pathPartFromArtworkKey(filePath)
            val region = EmbeddedArtworkRegion.find(cleanPath) ?: return null
            val t0 = android.os.SystemClock.uptimeMillis()
            val decoded = decodeImageRegion(region, targetWidth, targetHeight)
            val elapsed = android.os.SystemClock.uptimeMillis() - t0
            Log.d(
                TAG,
                "REGION_TAG_ART key=${cleanPath.takeLast(40)} result=${decoded != null} format=${region.format} mime=${region.mime ?: "?"} offset=${region.offset} bytes=${region.length} ${elapsed}ms"
            )
            decoded
        } catch (e: Exception) {
            Log.d(TAG, "Region artwork failed: ${filePath.takeLast(80)}, ${e.message}")
            null
        }
    }

    private fun decodeImageRegion(
        region: EmbeddedArtworkRegion.Handle,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val opts = threadLocalDecodeOptions.get()!!
        opts.inJustDecodeBounds = true
        opts.inSampleSize = 1
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        opts.inMutable = false

        region.openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }

        val origWidth = opts.outWidth
        val origHeight = opts.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        val config = getPreferredConfig()
        opts.inJustDecodeBounds = false
        opts.inSampleSize = calculateInSampleSize(
            origWidth = origWidth,
            origHeight = origHeight,
            reqWidth = targetWidth,
            reqHeight = targetHeight
        )
        opts.inPreferredConfig = config
        opts.inMutable = config != Bitmap.Config.HARDWARE

        val decoded = region.openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        } ?: return null

        return normalizeDecodedBitmap(decoded, targetWidth, targetHeight)
    }

    private fun prepareNativeEmbeddedArtworkSource(filePath: String): EmbeddedArtworkSourceCache.Handle? {
        val context = appContext ?: return null
        if (!TagLibBridge.isLoaded()) return null
        val cleanPath = pathPartFromArtworkKey(filePath)
        if (cleanPath.isBlank()) return null
        return EmbeddedArtworkSourceCache.prepare(
            context = context,
            audioPath = cleanPath,
            sourceKey = canonicalFailureSourceKey(cleanPath)
        ) { audioPath, outputPath ->
            TagLibBridge.extractEmbeddedArtworkToFile(
                filePath = audioPath,
                outputPath = outputPath
            )
        }
    }

    private fun decodeFromReusableArtworkSource(
        storageKey: String,
        decodeKey: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val audioKey = ArtworkSourceSelectionPolicy.isAudioArtworkKey(decodeKey)
        val indexedKinds = if (audioKey) {
            ArtworkSourceSelectionPolicy.embeddedIndexedKinds
        } else {
            ArtworkSourceSelectionPolicy.allIndexedKinds
        }
        decodeFromIndexedArtworkSource(
            storageKey = storageKey,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            acceptedKinds = indexedKinds
        )?.let { return it }

        val cleanPath = pathPartFromArtworkKey(decodeKey)
        if (cleanPath.isBlank()) return null
        val file = File(cleanPath)
        if (file.exists() && file.canRead() && isImageFile(cleanPath)) {
            return decodeImageFile(cleanPath, targetWidth, targetHeight)?.also {
                rememberReusableArtworkSource(
                    storageKey,
                    cleanPath,
                    ArtworkSourceSelectionPolicy.IndexedSourceKind.DirectImage,
                    shareAcrossEntity = true
                )
            }
        }

        // Primary decode and sibling-tier coalescing now share the exact same embedded/folder
        // coordinator. This removes the old reduced coalescing order that skipped FFmpeg/MMR and
        // could publish folder.jpg while another tier was still probing embedded artwork.
        return if (audioKey) {
            decodeAudioFileWithPolicyOrder(
                storageKey = storageKey,
                cleanPath = cleanPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        } else {
            null
        }
    }

    /**
     * Project-style native artwork extraction lane. Native TagLib writes the embedded picture into
     * a cache file, then BitmapFactory samples from that file. This keeps large embedded art out of
     * Java byte[] and gives both Playback and Indexer a reusable source file.
     */
    private fun decodeEmbeddedWithNativeTagLib(
        storageKey: String,
        filePath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val sourceHandle = prepareNativeEmbeddedArtworkSource(filePath) ?: return null

            val t0 = android.os.SystemClock.uptimeMillis()
            val decoded = decodeImageFile(sourceHandle.filePath, targetWidth, targetHeight)
            val elapsed = android.os.SystemClock.uptimeMillis() - t0
            Log.d(
                TAG,
                "NATIVE_TAG_ART_SOURCE key=${filePath.takeLast(40)} result=${decoded != null} reused=${sourceHandle.reused} mime=${sourceHandle.mime ?: "?"} bytes=${sourceHandle.bytes} ${elapsed}ms"
            )
            if (decoded != null && !decoded.isRecycled) {
                rememberReusableArtworkSource(
                    storageKey,
                    sourceHandle.filePath,
                    ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded,
                    shareAcrossEntity = false
                )
            }
            decoded
        } catch (e: Exception) {
            Log.d(TAG, "Native TagLib artwork failed: ${filePath.takeLast(80)}, ${e.message}")
            null
        }
    }

    private fun decodeEmbeddedWithMediaMetadataRetriever(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(filePath)
                val data = retriever.embeddedPicture ?: return null

                if (data.size <= 1024) return null

                val bitmap = decodeImageBytes(data, targetWidth, targetHeight)
                val elapsed = android.os.SystemClock.uptimeMillis() - t0

                Log.d(
                    TAG,
                    "MMR_EMBED key=${filePath.takeLast(40)} result=${bitmap != null} data=${data.size} ${elapsed}ms"
                )

                bitmap
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MMR failed: ${filePath.takeLast(80)}, ${e.message}")
            null
        }
    }

    private fun decodeImageBytes(
        data: ByteArray,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val opts = threadLocalDecodeOptions.get()!!
            // bounds pass
            opts.inJustDecodeBounds = true
            opts.inSampleSize = 1
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inMutable = false

            BitmapFactory.decodeByteArray(data, 0, data.size, opts)

            val origWidth = opts.outWidth
            val origHeight = opts.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                return null
            }

            // real decode pass
            val config = getPreferredConfig()
            opts.inJustDecodeBounds = false
            opts.inSampleSize = calculateInSampleSize(
                origWidth = origWidth,
                origHeight = origHeight,
                reqWidth = targetWidth,
                reqHeight = targetHeight
            )
            opts.inPreferredConfig = config
            opts.inMutable = config != Bitmap.Config.HARDWARE

            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.e(TAG, "decodeImageBytes failed", e)
            null
        }
    }

    private fun decodeFromUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val isAlbumArtUri = uri.toString().contains("albumart", ignoreCase = true)

        // v6c: 不信任 MediaStore albumart URI，直接返回 null
        // 有内嵌封面的歌曲通过 audio:// key 自行提取
        if (isAlbumArtUri) {
            Log.d(TAG, "decodeFromUri: albumart URI disabled (v6c) — uri=${uri.toString().tailForTrace()}")
            return null
        }

        decodeImageContentUri(uri, targetWidth, targetHeight)?.let { return it }

        return null
    }

    private fun decodeContentThumbnailUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        val context = appContext ?: return null

        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val decoded = context.contentResolver.loadThumbnail(
                uri,
                Size(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1)),
                null
            ) ?: return null
            val bitmap = normalizeDecodedBitmap(decoded, targetWidth, targetHeight)
            val elapsed = android.os.SystemClock.uptimeMillis() - t0
            trace(
                "CONTENT_THUMB_DONE elapsed=${elapsed}ms result=${bitmap != null} bitmap=${bitmap?.width}x${bitmap?.height} uri=${uri.toString().tailForTrace()}"
            )
            bitmap
        } catch (e: Exception) {
            trace("CONTENT_THUMB_FAIL error=${e.javaClass.simpleName}:${e.message} uri=${uri.toString().tailForTrace()}")
            null
        }
    }

    private fun decodeImageContentUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val context = appContext ?: return null

        return try {
            val opts = threadLocalDecodeOptions.get()!!
            // bounds pass
            opts.inJustDecodeBounds = true
            opts.inSampleSize = 1
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inMutable = false

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }

            val origWidth = opts.outWidth
            val origHeight = opts.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                return null
            }

            // real decode pass
            opts.inJustDecodeBounds = false
            opts.inSampleSize = calculateInSampleSize(
                origWidth = origWidth,
                origHeight = origHeight,
                reqWidth = targetWidth,
                reqHeight = targetHeight
            )
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            opts.inMutable = true

            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.d(TAG, "decodeImageContentUri failed: $uri, ${e.message}")
            null
        }
    }

    private fun decodeFromAlbumArtUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        // v6c: 不再信任 MediaStore albumart URI，不查询同专辑其他歌曲的封面
        // 避免串图：同 albumId 的其他歌曲内嵌封面不应复用到当前歌曲
        // 有内嵌封面的歌曲会通过 audio:// key 自行提取，无需此 fallback
        Log.d(TAG, "decodeFromAlbumArtUri: DISABLED (v6c) — no cross-album fallback, uri=${uri.toString().tailForTrace()}")
        return null
    }

    private fun decodeCoverWithFfmpeg(
        audioPath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val context = appContext ?: return null

        return try {
            val file = File(audioPath)
            if (!file.exists() || !file.canRead()) return null

            val coverDir = File(context.cacheDir, "albumart")
            if (!coverDir.exists()) coverDir.mkdirs()

            val cacheName = "cover_${audioPath.hashCode()}_${file.length()}_${file.lastModified()}.jpg"
            val coverFile = File(coverDir, cacheName)

            if (!coverFile.exists() || coverFile.length() <= 1024) {
                val result = FFmpegBridge.extractCover(
                    audioPath,
                    coverFile.absolutePath
                )

                if (result != 0 || !coverFile.exists() || coverFile.length() <= 1024) {
                    coverFile.delete()
                    return null
                }
            }

            decodeImageFile(coverFile.absolutePath, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.e(TAG, "decodeCoverWithFfmpeg failed: ${audioPath.takeLast(80)}", e)
            null
        }
    }

    private fun decodeFolderCoverCandidate(
        storageKey: String,
        audioPath: String?,
        targetWidth: Int,
        targetHeight: Int
    ): AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>? {
        val indexedPath = ArtworkSourceIndex.sourcePathFor(
            storageKey,
            ArtworkSourceSelectionPolicy.folderIndexedKinds
        )
        val file = indexedPath?.let(::File) ?: FolderArtworkLocator.find(audioPath) ?: return null
        val bitmap = decodeImageFile(file.absolutePath, targetWidth, targetHeight) ?: return null
        return AudioArtworkDecodeCoordinator.FolderCandidate(
            value = bitmap,
            sourcePath = file.absolutePath
        )
    }

    private fun decodeDiskThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val file = diskThumbnailFile(key, targetWidth, targetHeight) ?: return null
        if (!file.exists() || file.length() <= 1024) return null

        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val bitmap = decodeImageFile(file.absolutePath, targetWidth, targetHeight)
            val elapsed = android.os.SystemClock.uptimeMillis() - t0
            if (bitmap != null && !bitmap.isRecycled) {
                trace("DISK_THUMB_HIT elapsed=${elapsed}ms size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}")
                bitmap
            } else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            file.delete()
            trace("DISK_THUMB_FAIL error=${e.javaClass.simpleName}:${e.message} key=${key.tailForTrace()}")
            null
        }
    }

    private fun saveDiskThumbnailAsync(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        bitmap: Bitmap
    ) {
        if (bitmap.isRecycled) return
        val dedupKey = "${key}_${targetWidth}x${targetHeight}"
        if (!diskWriterPendingKeys.add(dedupKey)) return // already queued
        diskWriterExecutor.execute {
            try {
                saveDiskThumbnailInternal(key, targetWidth, targetHeight, bitmap)
            } finally {
                diskWriterPendingKeys.remove(dedupKey)
            }
        }
    }

    private fun saveDiskThumbnailInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        bitmap: Bitmap
    ) {
        if (bitmap.isRecycled) return
        val file = diskThumbnailFile(key, targetWidth, targetHeight) ?: return
        if (file.exists() && file.length() > 1024) return

        try {
            val source = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } else {
                bitmap
            }
            file.outputStream().use { output ->
                source.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            if (file.length() <= 1024) {
                file.delete()
            } else {
                val savedBytes = file.length()
                trimDiskThumbnailCache(file.parentFile)
                trace("DISK_THUMB_SAVE bytes=$savedBytes size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}")
            }
        } catch (e: Exception) {
            file.delete()
            trace("DISK_THUMB_SAVE_FAIL error=${e.javaClass.simpleName}:${e.message} key=${key.tailForTrace()}")
        }
    }

    private fun diskThumbnailFile(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): File? {
        val context = appContext ?: return null
        val maxSize = maxOf(targetWidth, targetHeight)
        if (maxSize <= 0 || maxSize > DISK_THUMB_MAX_SIZE) return null

        val bucket = SizeSlotCache.computeBucket(targetWidth, targetHeight)
        val digest = stableDigest("${key}_${bucket}")
        val dir = File(context.cacheDir, DISK_THUMB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$digest.jpg")
    }

    private fun trimDiskThumbnailCache(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("jpg", true) }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var totalBytes = files.sumOf { it.length() }
        var remaining = files.size
        if (totalBytes <= DISK_THUMB_MAX_BYTES && remaining <= DISK_THUMB_MAX_FILES) return
        for (file in files) {
            if (totalBytes <= DISK_THUMB_MAX_BYTES && remaining <= DISK_THUMB_MAX_FILES) break
            val fileBytes = file.length()
            if (file.delete()) {
                totalBytes -= fileBytes
                remaining--
            }
        }
        Log.i(ART_LOG_TAG, "DISK_THUMB_PRUNE files=$remaining bytes=$totalBytes")
        trace("DISK_THUMB_PRUNE files=$remaining bytes=$totalBytes")
    }


    private fun normalizeDecodedBitmap(
        decoded: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (decoded.isRecycled) return null
        if (decoded.width == targetWidth && decoded.height == targetHeight) {
            return decoded
        }

        val scaled = scaleBitmapCenterCrop(decoded, targetWidth, targetHeight)
        if (scaled !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return scaled
    }

    private fun scaleBitmapCenterCrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (source.isRecycled) return null
        if (targetWidth <= 0 || targetHeight <= 0) return null

        val src = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }

        return try {
            val srcWidth = src.width.toFloat()
            val srcHeight = src.height.toFloat()

            if (srcWidth <= 0f || srcHeight <= 0f) return null

            val scale = maxOf(
                targetWidth / srcWidth,
                targetHeight / srcHeight
            )

            val scaledWidth = srcWidth * scale
            val scaledHeight = srcHeight * scale

            val dx = ((targetWidth - scaledWidth) / 2f)
            val dy = ((targetHeight - scaledHeight) / 2f)

            val result = BitmapPool.obtain(
                width = targetWidth,
                height = targetHeight,
                config = Bitmap.Config.ARGB_8888
            ) ?: Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = threadLocalCanvas.get()!!
            canvas.setBitmap(result)

            val matrix = threadLocalMatrix.get()!!
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)

            val paint = threadLocalPaint.get()!!

            canvas.drawBitmap(src, matrix, paint)
            result.setHasAlpha(src.hasAlpha())

            result
        } catch (e: Exception) {
            Log.e(TAG, "scaleBitmapCenterCrop failed", e)
            null
        }
    }

    private fun calculateInSampleSize(
        origWidth: Int,
        origHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (origHeight > reqHeight || origWidth > reqWidth) {
            val halfHeight = origHeight / 2
            val halfWidth = origWidth / 2

            while (
                halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun isImageFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()

        return ext in setOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "bmp",
            "gif"
        )
    }

    private fun isWavLike(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()

        return ext in setOf(
            "wav",
            "dsf",
            "dff",
            "aiff",
            "aif"
        )
    }

    private fun isMeizuDevice(): Boolean {
        return Build.MANUFACTURER.equals("meizu", ignoreCase = true) ||
                Build.FINGERPRINT.contains("Flyme", ignoreCase = true)
    }

    private fun isMtkChip(): Boolean {
        return Build.HARDWARE.startsWith("mt", ignoreCase = true)
    }

    private fun trace(message: String) {
        if (ENABLE_BITMAP_TRACE) {
            Log.w(ART_LOG_TAG, "ART_TRACE $message")
        }
    }

    private fun stableArtworkCacheSourceKey(key: String): String {
        val stable = canonicalFailureSourceKey(key)
        return stable.ifBlank { key }
    }

    private fun stableArtworkCacheSourceKey(key: String, providerAliasKey: String): String {
        val alias = providerAliasKey.trim()
        if (alias.isBlank()) return stableArtworkCacheSourceKey(key)
        return if (alias.startsWith("entity://")) alias else "entity://$alias"
    }

    private fun hasRecentFailure(key: String, cacheKey: String): Boolean {
        val now = System.currentTimeMillis()
        val sourceTime = failedSourceCache[key]
        if (sourceTime != null) {
            if (now - sourceTime < FAILED_CACHE_TTL_MS) return true
            failedSourceCache.remove(key, sourceTime)
        }
        val cacheTime = failedCache[cacheKey]
        if (cacheTime != null) {
            if (now - cacheTime < FAILED_CACHE_TTL_MS) return true
            failedCache.remove(cacheKey, cacheTime)
        }
        return false
    }

    private fun rememberFailure(key: String, cacheKey: String) {
        val now = System.currentTimeMillis()
        failedCache[cacheKey] = now
        if (key.isNotBlank()) failedSourceCache[key] = now
        trace("NOT_FOUND_SENTINEL_REMEMBER key=${key.tailForTrace()} cacheKey=${cacheKey.tailForTrace()} ttl=${FAILED_CACHE_TTL_MS}ms")
    }

    private fun clearProviderAliasForNoArt(providerKey: String, failureSourceKey: String): Int {
        if (providerKey.isBlank() || providerKey == failureSourceKey) return 0
        var removed = 0
        removed += memoryCache.removeForSource(providerKey)
        AlbumArtCache.remove(providerKey)
        if (ArtworkSourceIndex.remove(providerKey)) removed++
        trace("NO_ART_CLEAR_PROVIDER_ALIAS provider=${providerKey.tailForTrace()} source=${failureSourceKey.tailForTrace()} removed=$removed")
        return removed
    }

    /**
     * Project-style not-found sentinel key. Remember no-art by file *version*, not by path alone.
     * If a user later embeds album art manually, size/mtime changes and the old no-art sentinel no
     * longer blocks the next visible/playback request from probing once again.
     */
    private fun canonicalFailureSourceKey(key: String): String {
        if (key.isBlank()) return key
        val normalized = key
            .removePrefix("audio://")
            .removePrefix("file://")
            .trim()
        if (normalized.startsWith("content://", ignoreCase = true)) return normalized

        val pathPart = normalized.substringBefore('|').trim()
        if (pathPart.isBlank()) return normalized

        val file = File(pathPart)
        if (file.exists()) {
            val canonicalPath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            return "$canonicalPath|${file.length()}|${file.lastModified()}"
        }

        // Most RawSMusic audio keys are already versioned as path|length|lastModified. Keep that
        // stable identity even if the file is currently unavailable.
        val parts = normalized.split('|', limit = 3)
        if (parts.size >= 3 && parts[1].toLongOrNull() != null && parts[2].substringBefore('|').toLongOrNull() != null) {
            return normalized
        }

        return normalized
    }

    private fun artworkKeyCandidates(key: String, sourceKey: String): Set<String> {
        val normalized = key
            .removePrefix("audio://")
            .removePrefix("file://")
            .trim()
        val pathPart = normalized.substringBefore('|').trim()
        return linkedSetOf<String>().apply {
            add(key)
            if (normalized.isNotBlank()) add(normalized)
            if (sourceKey.isNotBlank()) add(sourceKey)
            if (pathPart.isNotBlank()) {
                add(pathPart)
                val file = File(pathPart)
                val absolute = try { file.absolutePath } catch (_: Exception) { pathPart }
                if (absolute.isNotBlank()) add(absolute)
                val canonical = try { file.canonicalPath } catch (_: Exception) { absolute }
                if (canonical.isNotBlank()) add(canonical)
            }
        }
    }

    private fun removeFailureSentinelsFor(candidates: Set<String>, sourceKey: String): Int {
        var removed = 0
        for (candidate in candidates) {
            if (failedSourceCache.remove(candidate) != null) removed++
            if (failedCache.remove(candidate) != null) removed++
        }
        if (sourceKey.isNotBlank() && failedSourceCache.remove(sourceKey) != null) removed++

        val prefixes = candidates.filter { it.isNotBlank() }.flatMap { candidate ->
            listOf(candidate, "${candidate}_")
        }
        failedCache.keys.toList().forEach { cacheKey ->
            if (prefixes.any { prefix -> cacheKey == prefix || cacheKey.startsWith(prefix) }) {
                if (failedCache.remove(cacheKey) != null) removed++
            }
        }
        failedLogLastAt.keys.toList().forEach { logKey ->
            if (prefixes.any { prefix -> logKey == prefix || logKey.startsWith(prefix) }) {
                failedLogLastAt.remove(logKey)
            }
        }
        return removed
    }

    private fun removeQueuedStateFor(candidates: Set<String>): Int {
        if (candidates.isEmpty()) return 0
        var removed = 0
        val prefixes = candidates.filter { it.isNotBlank() }.map { "${it}_" }
        waitingRequests.keys.toList().forEach { cacheKey ->
            if (prefixes.any { cacheKey.startsWith(it) }) {
                removed += waitingRequests.remove(cacheKey)?.size ?: 0
            }
        }
        inFlightKeys.toList().forEach { cacheKey ->
            if (prefixes.any { cacheKey.startsWith(it) }) {
                if (inFlightKeys.remove(cacheKey)) removed++
                inFlightPriorities.remove(cacheKey)
                inFlightTokens.remove(cacheKey)
                promotedInFlightKeys.remove(cacheKey)
            }
        }
        return removed
    }

    private fun deleteDiskThumbnailsFor(candidates: Set<String>): Int {
        var removed = 0
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            for (side in ART_INVALIDATE_SIZES.distinct()) {
                val file = diskThumbnailFile(candidate, side, side) ?: continue
                if (file.exists() && file.delete()) removed++
            }
        }
        return removed
    }

    private fun logFailCacheThrottled(key: String) {
        val now = System.currentTimeMillis()
        val last = failedLogLastAt[key] ?: 0L
        if (now - last >= 2_000L) {
            failedLogLastAt[key] = now
            Log.d(TAG, "FAIL_CACHE key=${key.takeLast(40)}")
        } else {
            trace("FAIL_CACHE_SUPPRESSED key=${key.tailForTrace()}")
        }
    }

    private fun isPowerListRequestStillUseful(request: BitmapRequest): Boolean {
        if (!request.viewportRequired) return true
        // A request that was visible and then detached by scroll should behave like the
        // artwork view temporary detach: the view callback is gone, but the provider/source work may
        // still finish and warm the cache.  Token invalidation still guards manual artwork edits.
        if (request.keepAliveOnCancel) return true
        return isCurrentPowerListViewportKey(request.cacheKey)
    }

    private fun purgeObsoleteViewportWaiters() {
        if (waitingRequests.isEmpty()) return
        for ((flightKey, requests) in waitingRequests.entries.toList()) {
            if (requests.isEmpty()) {
                waitingRequests.remove(flightKey, requests)
                inFlightKeys.remove(flightKey)
                inFlightPriorities.remove(flightKey)
                inFlightTokens.remove(flightKey)
                promotedInFlightKeys.remove(flightKey)
                continue
            }

            // CopyOnWriteArrayList.removeAll(predicate) is not safe enough here on some optimized
            // Android runtimes while Compose is cancelling/restarting item requests during a fling:
            // the predicate path can observe an already-empty backing array and crash on get(0).
            // Project-style viewport purging should be best-effort and never fatal, so operate on
            // a stable snapshot and remove individual stale requests from the live list.
            var removedAny = false
            var keptRunningAny = requests.any { it.keepAliveOnCancel && it.state == BitmapRequest.State.DECODING_FILES }
            for (request in requests.toList()) {
                val remove = request.viewportRequired && !isCurrentPowerListViewportKey(request.cacheKey)
                if (!remove) continue
                // Detach the stale view listener.  If this request is already decoding, let it
                // finish and warm the provider cache; if it is still only queued, remove the pending
                // message so old offscreen cells do not block the serial list lane ahead of the
                // current viewport.  This is the important distinction missing in step11.
                val keepRunning = request.state == BitmapRequest.State.DECODING_FILES
                if (keepRunning) keptRunningAny = true
                request.cancel(keepAlive = keepRunning)
                if (!keepRunning) {
                    workerHandlers.forEach { handler -> handler.removeMessages(MSG_LOAD, request) }
                }
                requests.remove(request)
                removedAny = true
            }

            if (requests.isEmpty()) {
                waitingRequests.remove(flightKey, requests)
                if (keptRunningAny) {
                    // Keep inFlight bookkeeping until the worker finishes.  Clearing it here lets a
                    // second request enqueue the same source while the first keep-alive decode is still
                    // running, which is exactly the repeated-decode flicker we are avoiding.
                } else {
                    inFlightKeys.remove(flightKey)
                    inFlightPriorities.remove(flightKey)
                    inFlightTokens.remove(flightKey)
                    promotedInFlightKeys.remove(flightKey)
                }
            } else if (removedAny) {
                trace("VIEWPORT_PURGE_PARTIAL flight=${flightKey.tailForTrace()} remaining=${requests.size}")
            }
        }
    }

    private fun String.tailForTrace(): String {
        return takeLast(72)
    }

    private class WorkerHandler(
        looper: Looper
    ) : Handler(looper) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_LOAD -> {
                    val request = msg.obj as? BitmapRequest ?: return
                    val cacheKey = request.cacheKey
                    val flightKey = request.inFlightKey
                    trace("DECODE_START seq=${request.traceSeq} size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")

                    if (request.isCancelled && !request.keepAliveOnCancel && !hasWaitingRequests(flightKey)) {
                        trace("DECODE_ABORT_CANCELLED seq=${request.traceSeq} cacheKey=${cacheKey.tailForTrace()}")
                        waitingRequests.remove(flightKey)
                        clearInFlightFor(request, flightKey)
                        return
                    }

                    if (!isPowerListRequestStillUseful(request) || !isArtworkAcceptTokenCurrent(request)) {
                        trace("DECODE_ABORT_STALE seq=${request.traceSeq} generation=${request.viewportGeneration} current=${powerListViewportGeneration.get()} cacheKey=${cacheKey.tailForTrace()}")
                        waitingRequests.remove(flightKey)
                        clearInFlightFor(request, flightKey)
                        return
                    }

                    val cached = memoryCache.get(cacheKey)
                    if (cached != null && !cached.isRecycled) {
                        trace("DECODE_SKIP_CACHE_READY seq=${request.traceSeq} bitmap=${cached.width}x${cached.height} key=${request.key.tailForTrace()}")
                        clearInFlightFor(request, flightKey)
                        deliverResult(request, cached)
                        return
                    }

                    val failureSourceKey = stableArtworkCacheSourceKey(request.decodeKey)
                    if (hasRecentFailure(failureSourceKey, "${failureSourceKey}_${request.bucket}")) {
                        trace("DECODE_SKIP_NO_ART_SENTINEL seq=${request.traceSeq} provider=${request.key.tailForTrace()} decode=${request.decodeKey.tailForTrace()}")
                        waitingRequests.remove(flightKey)
                        clearInFlightFor(request, flightKey)
                        deliverResult(request, null)
                        return
                    }

                    val t0 = android.os.SystemClock.uptimeMillis()
                    var bitmap: Bitmap? = null

                    try {
                        request.transitionTo(BitmapRequest.State.DECODING_FILES)

                        val decodeResult = decodeBitmap(
                            storageKey = request.key,
                            decodeKey = request.decodeKey,
                            targetWidth = request.targetWidth,
                            targetHeight = request.targetHeight,
                            surface = request.surface
                        )
                        bitmap = decodeResult.bitmap

                        if ((bitmap == null || bitmap.isRecycled) && request.surface.rememberNullAsNoArt && decodeResult.terminalNoArt) {
                            rememberFailure(failureSourceKey, "${failureSourceKey}_${request.bucket}")
                            clearProviderAliasForNoArt(request.key, failureSourceKey)
                        }

                        val elapsed = android.os.SystemClock.uptimeMillis() - t0

                        if (!isArtworkAcceptTokenCurrent(request)) {
                            trace("DECODE_DROP_STALE_TOKEN seq=${request.traceSeq} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} generation=${request.viewportGeneration} current=${powerListViewportGeneration.get()} cacheKey=${cacheKey.tailForTrace()}")
                            bitmap?.let { BitmapPool.recycle(it) }
                            waitingRequests.remove(flightKey)
                            clearInFlightFor(request, flightKey)
                            return
                        }
                        if (!isPowerListRequestStillUseful(request) && (bitmap == null || bitmap.isRecycled)) {
                            trace("DECODE_DROP_STALE_EMPTY seq=${request.traceSeq} elapsed=${elapsed}ms generation=${request.viewportGeneration} current=${powerListViewportGeneration.get()} cacheKey=${cacheKey.tailForTrace()}")
                            waitingRequests.remove(flightKey)
                            clearInFlightFor(request, flightKey)
                            return
                        }
                        if (!isPowerListRequestStillUseful(request)) {
                            trace("DECODE_KEEP_STALE_CACHE seq=${request.traceSeq} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} generation=${request.viewportGeneration} current=${powerListViewportGeneration.get()} cacheKey=${cacheKey.tailForTrace()}")
                        }

                        if (decodeResult.coalesceSourceTiers) {
                            coalesceIndexerSiblingTiers(request, bitmap)
                        }

                        if (ENABLE_BITMAP_TRACE) {
                            Log.w(
                                ART_LOG_TAG,
                                "DECODE_DONE seq=${request.traceSeq} surface=${request.surface} priority=${request.priority} result=${bitmap != null} elapsed=${elapsed}ms size=${request.targetWidth}x${request.targetHeight} thread=${Thread.currentThread().name} key=${request.key.takeLast(60)}"
                            )
                        }
                        PowerTraceLogger.bitmapDecodeDone(
                            priority = request.priority.name,
                            size = "${request.targetWidth}x${request.targetHeight}",
                            result = bitmap != null,
                            elapsedMs = elapsed,
                            key = request.key
                        )
                        trace("DECODE_DONE seq=${request.traceSeq} result=${bitmap != null} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")
                    } catch (e: Exception) {
                        val failureSourceKey = stableArtworkCacheSourceKey(request.decodeKey)
                        rememberFailure(failureSourceKey, "${failureSourceKey}_${request.bucket}")
                        trace("DECODE_ERROR seq=${request.traceSeq} error=${e.javaClass.simpleName}:${e.message} provider=${request.key.tailForTrace()} decode=${request.decodeKey.tailForTrace()}")
                        Log.e(TAG, "worker decode failed: ${request.decodeKey.takeLast(80)}", e)
                    } finally {
                        clearInFlightFor(request, flightKey)
                    }

                    trace("DELIVER seq=${request.traceSeq} result=${bitmap != null} waiters=${waitingRequests[flightKey]?.size ?: 0} key=${request.key.tailForTrace()}")
                    deliverResult(request, bitmap)
                }

                MSG_CANCEL -> {
                    val request = msg.obj as? BitmapRequest ?: return
                    request.cancel()
                    removeWaitingRequest(request)
                    if (!hasWaitingRequests(request.inFlightKey)) {
                        clearInFlightForRequest(request)
                    }
                }
            }
        }

        private fun clearInFlightFor(request: BitmapRequest, flightKey: String) {
            if (request.inFlightOwner) {
                inFlightKeys.remove(flightKey)
                inFlightPriorities.remove(flightKey)
                inFlightTokens.remove(flightKey)
            }
            if (request.promotedInFlight) {
                promotedInFlightKeys.remove(flightKey)
            }
        }
    }
}
