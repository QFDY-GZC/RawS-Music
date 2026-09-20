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
import com.rawsmusic.core.common.artwork.ArtworkResolutionPolicy
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.PlayerSwitchTrace
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.core.ui.perf.TransitionPerfEvent
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    // Temporary diagnostics for dense-grid fling analysis. Remove after the trace capture.
    private const val ENABLE_BITMAP_TRACE = false

    private const val MSG_LOAD = 1
    private const val MSG_CANCEL = 2

    // Keep one provider dispatch lane. The lane serializes source probing while the holder
    // callbacks are posted asynchronously. As in reference player, the lane is created lazily when the
    // first artwork request is admitted, not during Application.onCreate().
    private const val WORKER_COUNT = 1
    private const val ARTWORK_WORKER_THREAD_PRIORITY = 4
    private const val FAILED_CACHE_TTL_MS = 5 * 60 * 1000L
    // MediaStore/native extractors can briefly return no embedded art while a track is being
    // mounted or the player page is being opened. Keep reference player's live request table alive for
    // two cheap retries instead of completing the holder with a permanent-looking placeholder.
    private const val MAX_TRANSIENT_SOURCE_RETRIES = 2
    private val TRANSIENT_SOURCE_RETRY_DELAYS_MS = longArrayOf(90L, 260L)
    // Keep disk thumbnails as a small-list warm cache only. The design keeps source artwork and
    // low/high wrappers in memory instead of persisting every UI target size to disk. Writing
    // playback/fullscreen tiers here multiplied files for the same song and made bitmap_v4 grow
    // during normal scrolling.
    private const val DISK_THUMB_MAX_SIZE = AlbumArtTiers.LOW_RES_NORMAL_CAP
    private const val DISK_THUMB_MAX_BYTES = 24L * 1024L * 1024L
    private const val DISK_THUMB_MAX_FILES = 256
    // v8 starts after source-epoch ownership moved from provider aliases to the concrete file
    // version. Do not reuse thumbnails that may have been published by a stale alias flight under
    // the old rule; positive embedded source artifacts remain version-keyed and are restored.
    private const val DISK_THUMB_DIR = "bitmap_thumbs_file_v8"
    private const val LEGACY_DISK_THUMB_DIR = "bitmap_thumbs_file_v7"
    private const val REMOTE_SOURCE_DIR = "bitmap_remote_sources_v1"
    private const val REMOTE_SOURCE_MAX_BYTES = 32L * 1024L * 1024L
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
        96, 128, 192, 256, 384, 512, 768, 1024, 1536
    )

    private val initLock = Any()
    // baseline implementation BitmapProvider.O() protects the provider request/source table with one monitor.
    // Keep Raw's multi-map flight registry coherent under the same single ownership boundary: a
    // flight key must never be observable without its owner/token/priority, and a cancelled/orphaned
    // owner must not leave same-size ArtworkImageNode-equivalent waiters joined forever.
    private val inFlightRegistryLock = Any()
    private var legacyDiskThumbCleanupDone = false

    @Volatile
    private var appContext: Context? = null

    private val memoryCache = SizeSlotCache()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var workerHandlers: Array<WorkerHandler> = emptyArray()

    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPriorities = ConcurrentHashMap<String, BitmapRequest.Priority>()
    private val inFlightTokens = ConcurrentHashMap<String, ArtworkAcceptToken>()
    /** Owner request for each source flight; waiters must never become decode owners. */
    private val inFlightOwnerRequests = ConcurrentHashMap<String, BitmapRequest>()
    private val promotedInFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val waitingRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<BitmapRequest>>()
    private val failedCache = ConcurrentHashMap<String, Long>()
    private val failedSourceCache = ConcurrentHashMap<String, Long>()
    private val failedLogLastAt = ConcurrentHashMap<String, Long>()
    private val traceSeq = AtomicLong(0L)
    private val activeWorkerDecodeCount = AtomicLong(0L)
    @Volatile private var activeWorkerDecodeStartedAtMs = 0L
    @Volatile private var activeWorkerDecodeKeyTag = "-"

    /** Low-overhead scene diagnostic; called only at transition boundaries. */
    internal fun transitionDiagnosticsSummary(): String {
        val active = activeWorkerDecodeCount.get().coerceAtLeast(0L)
        val owners = inFlightOwnerRequests.size
        val queuedApprox = (owners.toLong() - active).coerceAtLeast(0L)
        val elapsed = if (active > 0L && activeWorkerDecodeStartedAtMs > 0L) {
            (android.os.SystemClock.uptimeMillis() - activeWorkerDecodeStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return "bitmapWorker(active=$active pending~=$queuedApprox owners=$owners waiters=${waitingRequests.size} " +
            "key=$activeWorkerDecodeKeyTag elapsed=${elapsed}ms) " +
            "bitmapCache(${memoryCache.transitionDiagnosticsSummary()}) hardwareGate=$useHardwareBitmap"
    }


    /**
     * Bumped when library scan or manual artwork edit makes visible items re-check their cover.
     * This is intentionally UI-observable but very cheap: existing bitmaps stay cached; only stale
     * no-art decisions / LaunchedEffect keys are refreshed.
     */
    var artworkRevision by mutableLongStateOf(0L)
        private set

    // v7c: 复用解码相关对象，减少高频滚动时的 GC 抖动
    private val threadLocalDecodeOptions = object : ThreadLocal<BitmapFactory.Options>() {
        override fun initialValue(): BitmapFactory.Options {
            return BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        }
    }
    // CPU Palette/getPixels work uses a tiny software raster decoded from the encoded source.
    // Keep that decision local to the synchronous decoder call so foreground provider wrappers
    // remain HARDWARE and no GPU -> CPU copy is needed merely to inspect colors.
    private val threadLocalPreferredConfigOverride = ThreadLocal<Bitmap.Config?>()
    private val threadLocalBypassProviderBitmapReuse = ThreadLocal<Boolean>()
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

    // A visible list holder requests the provider's source record directly. Source extraction is
    // still confined to this worker lane; the holder never performs file work on the UI thread and
    // an already-started decode is allowed to finish after the holder moves away.
    private val artworkIndexerKeys = ConcurrentHashMap.newKeySet<String>()
    private val artworkIndexerCallbacks =
        ConcurrentHashMap<String, CopyOnWriteArrayList<(Bitmap?) -> Unit>>()
    private val artworkRevisionCoalescePending = AtomicBoolean(false)

    /**
     * reference player build 1026 AA provider config gate:
     * - API 28+
     * - non-vivo manufacturer
     * - heap >= 128 MiB
     * - aa_8888 enabled
     *
     * Under that gate both authoritative low/high provider wrappers are HARDWARE bitmaps. Readback,
     * palette and disk-encode paths already create explicit software copies when they need pixels.
     */
    val useHardwareBitmap: Boolean
        get() {
            val requested8888 = runCatching { AppPreferences.AlbumArt.forceArgb8888 }.getOrDefault(false)
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !Build.MANUFACTURER.equals("vivo", ignoreCase = true) &&
                ArtworkResolutionPolicy.qualityFeatureSupported(Runtime.getRuntime().maxMemory()) &&
                requested8888
        }

    fun init(context: Context) {
        synchronized(initLock) {
            appContext = context.applicationContext

            if (workerHandlers.isNotEmpty()) {
                if (ENABLE_BITMAP_TRACE) Log.d(TAG, "Already initialized")
                return
            }

            // Match reference player's provider lifetime: construction only installs the application
            // context. The image handler starts from ensureWorkersStarted() on the first live
            // request, so cold boot does not pay for an idle artwork thread.
            if (ENABLE_BITMAP_TRACE) Log.d(TAG, "Provider context installed; workers are lazy")
        }
    }

    private fun ensureWorkersStarted(): Array<WorkerHandler> {
        workerHandlers.takeIf { it.isNotEmpty() }?.let { return it }
        synchronized(initLock) {
            workerHandlers.takeIf { it.isNotEmpty() }?.let { return it }
            if (appContext == null) return emptyArray()
            val started = Array(WORKER_COUNT) { index ->
                val thread = HandlerThread(
                    "BitmapWorker-$index",
                    // Artwork source extraction may overlap a player-page settle. Keep it below
                    // the UI/render lane while still ahead of ordinary background maintenance.
                    ARTWORK_WORKER_THREAD_PRIORITY
                )
                thread.start()
                WorkerHandler(thread.looper)
            }
            workerHandlers = started
            scheduleLegacyDiskThumbCleanup(appContext!!)
            Log.d(
                TAG,
                "Initialized workers=$WORKER_COUNT, useHardwareBitmap=$useHardwareBitmap, cacheMax=${memoryCache.maxSizeBytes}"
            )
            PowerTraceLogger.bitmapProviderInit(WORKER_COUNT, memoryCache.maxSizeBytes.toLong())
            return started
        }
    }

    private fun scheduleLegacyDiskThumbCleanup(context: Context) {
        synchronized(initLock) {
            if (legacyDiskThumbCleanupDone) return
            legacyDiskThumbCleanupDone = true
        }
        // The old cache is reconstructable. Defer its removal until the artwork provider is
        // actually used so an idle cold launch does not create a disk-writer thread at all.
        diskWriterExecutor.execute {
            runCatching {
                File(context.cacheDir, LEGACY_DISK_THUMB_DIR).deleteRecursively()
            }
        }
    }

    fun load(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
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
            providerAliasKey = providerAliasKey,
            aspectPolicy = aspectPolicy,
        )
    }

    /**
     * Provider-wrapper request used by attached UI holders.
     *
     * This mirrors reference player retained artwork view -> bitmap provider ownership: the holder binds an artwork
     * identity and receives the provider-owned wrapper directly, with no painter/image-loader
     * cache inserted between the UI and the provider record.
     */
    fun loadHandle(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        exactTarget: Boolean = false,
        callback: (ArtworkHandle?) -> Unit,
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            surface = surface,
            callback = null,
            wrapperCallback = callback,
            allowHiRes = true,
            providerAliasKey = providerAliasKey,
            aspectPolicy = aspectPolicy,
            exactTarget = exactTarget,
        )
    }

    fun loadThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
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
            providerAliasKey = providerAliasKey,
            aspectPolicy = aspectPolicy,
        )
    }

    fun thumbnailCacheKey(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): String {
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = false,
            priority = BitmapRequest.Priority.LOADING_LIST
        )
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return "${artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)}_${bucket}"
    }

    fun hasRecentThumbnailFailure(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = "",
        synchronousCacheMissConfirmed: Boolean = false,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Boolean {
        if (key.isBlank()) return false
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = false,
            priority = BitmapRequest.Priority.LOADING_LIST
        )
        if (!synchronousCacheMissConfirmed) {
            val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
            val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
            val validCached = memoryCache.getAnyForSource(providerKey, minimumSide = target.maxSide)
                ?.let { reusableSourceBitmap(it, target.width, target.height, aspectPolicy) }
                ?: memoryCache.getAnyForSource(rawKey, minimumSide = target.maxSide)
                    ?.let { reusableSourceBitmap(it, target.width, target.height, aspectPolicy) }
            if (validCached != null && !validCached.isRecycled) return false
        }
        // Keep no-art decisions on the file-version source. A broad album/folder alias should not
        // suppress probing another track unless the scanner later proves the whole entity is no-art.
        val sourceKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return hasRecentFailure(sourceKey, "${sourceKey}_${bucket}")
    }

    fun loadViewportThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        providerAliasKey: String = "",
        externalArtworkPath: String = "",
        synchronousMissConfirmed: Boolean = false,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        callback: ((ArtworkHandle?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            surface = ArtworkSurface.List,
            callback = null,
            wrapperCallback = callback,
            allowHiRes = false,
            providerAliasKey = providerAliasKey,
            externalArtworkPath = externalArtworkPath,
            synchronousMissConfirmed = synchronousMissConfirmed,
            sourceDecodeAllowed = true,
            aspectPolicy = aspectPolicy,
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
        wrapperCallback: ((ArtworkHandle?) -> Unit)? = null,
        allowHiRes: Boolean,
        providerAliasKey: String = "",
        externalArtworkPath: String = "",
        synchronousMissConfirmed: Boolean = false,
        sourceDecodeAllowed: Boolean = surface.allowsSourceDecode,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        exactTarget: Boolean = false,
    ): BitmapRequest {
        val admissionStartedNs = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        fun completeAdmission(result: BitmapRequest): BitmapRequest {
            if (admissionStartedNs != 0L) {
                TransitionPerfTrace.recordDuration(
                    TransitionPerfStage.BITMAP_PROVIDER_ADMISSION,
                    System.nanoTime() - admissionStartedNs,
                )
            }
            return result
        }
        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val target = if (exactTarget) {
            AlbumArtTiers.Target(baseWidth, baseHeight)
        } else {
            resolveAlbumArtTarget(
                baseWidth = baseWidth,
                baseHeight = baseHeight,
                allowHiRes = allowHiRes,
                priority = priority
            )
        }
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = stableArtworkCacheSourceKey(key, providerAliasKey)
        val cacheProviderKey = artworkAspectCacheSourceKey(providerKey, aspectPolicy)
        val failureSourceKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${cacheProviderKey}_${bucket}"
        // Provider/entity aliases are storage identities, not source epochs. Tie stale-result
        // rejection to the concrete file-version key that produced the pixels so an artwork
        // rewrite cannot leave an alias flight valid and republish stale art into a rebound holder.
        val tokenSourceVersionKey = artworkAspectCacheSourceKey(failureSourceKey, aspectPolicy)
        val acceptToken = ArtworkRecordRegistry.tokenFor(
            sourceVersionKey = tokenSourceVersionKey,
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
            wrapperCallback = wrapperCallback,
            surface = surface,
            sourceDecodeAllowed = sourceDecodeAllowed,
            aspectPolicy = aspectPolicy,
            externalArtworkPath = externalArtworkPath.trim(),
            artworkToken = acceptToken,
        )
        val seq = traceSeq.incrementAndGet()
        request.traceSeq = seq
        trace("REQUEST seq=$seq surface=$surface priority=$priority aspect=$aspectPolicy allowHiRes=$allowHiRes input=${targetWidth}x${targetHeight} actual=${actualWidth}x${actualHeight} bucket=$bucket provider=${providerKey.tailForTrace()} decode=${key.tailForTrace()}")
        if (PlayerSwitchTrace.isActive()) {
            PlayerSwitchTrace.mark(
                "provider_request",
                "seq=$seq surface=$surface priority=$priority size=${actualWidth}x${actualHeight} key=${Integer.toHexString(key.hashCode())}",
            )
        }

        if (key.isBlank()) {
            trace("SKIP_BLANK seq=$seq size=${actualWidth}x${actualHeight} priority=$priority")
            PowerTraceLogger.bitmapRequest(
                state = "skip_blank",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            postNull(request, terminalNoArt = true)
            return completeAdmission(request)
        }

        // Resolve the source record before consulting its not-found sentinel. A prior
        // transient probe can leave a failure entry behind while another surface has already
        // decoded the same source. Checking the sentinel first would hide that valid wrapper and
        // make the cover disappear until the TTL expires.
        if (!synchronousMissConfirmed) {
            val exactCached = memoryCache.get(request.cacheKey)
                ?.takeIf { it.isValidArtworkBitmap() }
            val cached = exactCached ?: memoryCache.getAnyForSource(
                cacheProviderKey,
                minimumSide = maxOf(actualWidth, actualHeight),
                allowHighFallback = surface != ArtworkSurface.List,
            )?.let { reusableSourceBitmap(it, actualWidth, actualHeight, aspectPolicy) }
            if (cached != null && !cached.isRecycled &&
                !needsPlaybackWrapperNormalization(surface, cached, actualWidth, actualHeight, aspectPolicy)) {
                val tier = if (exactCached === cached) "exact" else "source"
                trace("CACHE_HIT seq=$seq tier=$tier size=${actualWidth}x${actualHeight} priority=$priority key=${key.tailForTrace()}")
                if (PlayerSwitchTrace.isActive()) {
                    PlayerSwitchTrace.mark(
                        "provider_cache_hit",
                        "seq=$seq tier=$tier bitmap=${cached.width}x${cached.height} key=${Integer.toHexString(key.hashCode())}",
                    )
                }
                PowerTraceLogger.bitmapRequest(
                    state = "cache_hit",
                    priority = priority.name,
                    size = "${actualWidth}x${actualHeight}",
                    key = key
                )
                request.transitionTo(BitmapRequest.State.AVAILABLE)
                if (wrapperCallback != null && Looper.myLooper() == Looper.getMainLooper()) {
                    // baseline implementation BitmapProvider.O() calls the ArtworkImageNode callback directly for an
                    // already-available provider wrapper. Keep the same synchronous cache-hit path
                    // so a cache rebind is not misclassified as a fresh asynchronous reveal.
                    if (!request.isCancelled && isArtworkAcceptTokenCurrent(request)) {
                        deliverCallback(request, cached)
                    }
                } else {
                    mainHandler.post {
                        if (!request.isCancelled && isArtworkAcceptTokenCurrent(request)) {
                            deliverCallback(request, cached)
                        }
                    }
                }
                return completeAdmission(request)
            } else if (cached != null && !cached.isRecycled) {
                trace("CACHE_DEFER_PLAYBACK_RESIZE seq=$seq bitmap=${cached.width}x${cached.height} target=${actualWidth}x${actualHeight} key=${key.tailForTrace()}")
            }

            if (surface.rememberNullAsNoArt && hasRecentFailure(failureSourceKey, "${failureSourceKey}_${bucket}")) {
                logFailCacheThrottled(failureSourceKey)
                trace("NOT_FOUND_SENTINEL_HIT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority key=${key.tailForTrace()}")
                PowerTraceLogger.bitmapRequest(
                    state = "not_found_sentinel",
                    priority = priority.name,
                    size = "${actualWidth}x${actualHeight}",
                    key = key
                )
                postNull(request, terminalNoArt = true)
                return completeAdmission(request)
            }
        }

        val handlers = ensureWorkersStarted()
        if (handlers.isEmpty()) {
            Log.e(TAG, "BitmapProvider is not initialized")
            PowerTraceLogger.bitmapRequest(
                state = "provider_not_ready",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            // 不写 failedCache，否则 init 后同尺寸封面 5 分钟内继续失败
            postNull(request, terminalNoArt = false)
            return completeAdmission(request)
        }

        request.transitionTo(BitmapRequest.State.CHECKING_MEMORY)

        val flightKey = request.inFlightKey
        var isOwner = registerProviderWaiterAndClaimFlight(request)
        if (!isOwner) {
            // The admission transaction returned a live owner, but a source invalidation can retire
            // it immediately afterwards. Re-check under the same provider-table monitor before
            // committing to JOIN_IN_FLIGHT; a missing/cancelled non-started owner is reclaimed here
            // instead of leaving this same-pixel waiter parked until a pinch changes the bucket.
            val ownerNeedsReclaim = synchronized(inFlightRegistryLock) {
                val owner = inFlightOwnerRequests[flightKey]
                shouldReclaimOrphanProviderFlight(
                    flightKeyPresent = inFlightKeys.contains(flightKey),
                    ownerPresent = owner != null,
                    ownerCancelled = owner?.isCancelled == true,
                    ownerSourceWorkStarted = owner?.sourceWorkStarted == true,
                )
            }
            if (ownerNeedsReclaim) isOwner = claimProviderFlight(request)
        }
        if (!isOwner) {
            val ownerToken = inFlightTokens[flightKey]
            // flightKey already contains source/global record revisions. If the owner token is
            // temporarily absent while the owner publishes its maps, or the whole record becomes
            // stale before source work starts, keep this artwork waiter attached. The provider owner
            // either dispatches it normally or requeues the complete stale waiter set; ArtworkImageNode
            // is never completed with a synthetic transient-null miss.
            if (ownerToken != null && !ArtworkRecordRegistry.canShareInFlight(ownerToken, request.artworkToken)) {
                trace("JOIN_STALE_FLIGHT_PENDING_REQUEUE seq=$seq size=${actualWidth}x${actualHeight} priority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
                return completeAdmission(request)
            }
            val ownerPriority = inFlightPriorities[flightKey]
            if (shouldPromoteInFlight(ownerPriority, priority) && promotedInFlightKeys.add(flightKey)) {
                trace("PROMOTE_IN_FLIGHT seq=$seq ownerPriority=$ownerPriority newPriority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
                val ownerRequest = inFlightOwnerRequests[flightKey]
                if (ownerRequest != null && !ownerRequest.sourceWorkStarted) {
                    ownerRequest.priority = priority
                    handlers.forEach { handler ->
                        handler.removeMessages(MSG_LOAD, ownerRequest)
                    }
                    enqueueRequest(ownerRequest, handlers, forceFront = true)
                }
                return completeAdmission(request)
            }
            trace("JOIN_IN_FLIGHT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
            if (PlayerSwitchTrace.isActive()) {
                PlayerSwitchTrace.mark(
                    "provider_join_in_flight",
                    "seq=$seq priority=$priority key=${Integer.toHexString(key.hashCode())}",
                )
            }
            TransitionPerfTrace.count(TransitionPerfEvent.ARTWORK_JOINED)
            PowerTraceLogger.bitmapRequest(
                state = "join_in_flight",
                priority = priority.name,
                size = "${actualWidth}x${actualHeight}",
                key = key
            )
            if (ENABLE_BITMAP_TRACE) Log.d(TAG, "IN_FLIGHT_JOIN key=${key.takeLast(40)} cacheKey=${request.cacheKey.takeLast(60)}")
            return completeAdmission(request)
        }
        TransitionPerfTrace.count(TransitionPerfEvent.ARTWORK_QUEUED)
        if (PlayerSwitchTrace.isActive()) {
            PlayerSwitchTrace.mark(
                "provider_queued",
                "seq=$seq priority=$priority key=${Integer.toHexString(key.hashCode())}",
            )
        }
        PowerTraceLogger.bitmapRequest(
            state = "queued",
            priority = priority.name,
            size = "${actualWidth}x${actualHeight}",
            key = key
        )
        enqueueRequest(
            request,
            handlers,
            // Bound playback artwork follows reference player's active image lane. List requests remain
            // FIFO, while the front lane can still promote a not-yet-started list owner.
            forceFront = priority.level <= BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH.level,
        )

        return completeAdmission(request)
    }

    /**
     * Add the artwork observer and resolve its provider-flight ownership in one monitor transaction.
     * This is the Raw equivalent of baseline implementation BitmapProvider.O(): no cancellation or same-size
     * join can observe the request-table entry between waiter publication and owner publication.
     */
    private fun registerProviderWaiterAndClaimFlight(request: BitmapRequest): Boolean =
        synchronized(inFlightRegistryLock) {
            addWaitingRequestLocked(request)
            claimProviderFlightLocked(request)
        }

    private fun claimProviderFlight(request: BitmapRequest): Boolean = synchronized(inFlightRegistryLock) {
        claimProviderFlightLocked(request)
    }

    private fun claimProviderFlightLocked(request: BitmapRequest): Boolean {
        val flightKey = request.inFlightKey
        if (inFlightKeys.contains(flightKey)) {
            val owner = inFlightOwnerRequests[flightKey]
            if (!shouldReclaimOrphanProviderFlight(
                    flightKeyPresent = true,
                    ownerPresent = owner != null,
                    ownerCancelled = owner?.isCancelled == true,
                    ownerSourceWorkStarted = owner?.sourceWorkStarted == true,
                )) return false

            // Recover an impossible/orphaned table entry left by an older non-atomic registration
            // or cancellation race. Reference never exposes a request-table key without an owner.
            clearInFlightKeyLocked(flightKey, ownerRequest = null)
            trace("FLIGHT_RECLAIM_ORPHAN seq=${request.traceSeq} key=${request.key.tailForTrace()}")
        }

        if (!inFlightKeys.add(flightKey)) return false
        request.inFlightOwner = true
        inFlightPriorities[flightKey] = request.priority
        inFlightTokens[flightKey] = request.artworkToken
        inFlightOwnerRequests[flightKey] = request
        return true
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
        forceFront: Boolean,
        delayMs: Long = 0L,
    ) {
        // There is intentionally one dispatch lane.  Priority is expressed by message position,
        // not by starting another decoder thread; this is the source-dispatch boundary for
        // source-record request table.
        val selectedWorkerIndex = 0
        val handler = handlers[selectedWorkerIndex]
        val msg = handler.obtainMessage(MSG_LOAD, request)

        if (ENABLE_BITMAP_TRACE) {
            Log.d(
                TAG,
                "ENQUEUE key=${request.key.takeLast(40)} size=${request.targetWidth}x${request.targetHeight} worker=$selectedWorkerIndex"
            )
        }
        // Ordinary requests stay FIFO on the single Handler. Active playback requests and
        // explicit promotions go to the front, matching the bound-holder priority in reference player.
        val front = forceFront || request.priority == BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH
        val delayed = request.priority.level >= BitmapRequest.Priority.LOADING_PREFETCH.level
        trace("ENQUEUE seq=${request.traceSeq} worker=$selectedWorkerIndex front=$front size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} key=${request.key.tailForTrace()}")

        if (front) {
            handler.sendMessageAtFrontOfQueue(msg)
        } else if (delayMs > 0L) {
            handler.sendMessageDelayed(msg, delayMs)
        } else if (delayed) {
            // Keep the handler responsive to newly visible rows without creating another decoder.
            handler.sendMessageDelayed(msg, 24L)
        } else {
            handler.sendMessage(msg)
        }
    }

    fun execute(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.Widget,
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
            surface = surface
        ).bitmap

        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
        }

        return bitmap
    }

    /**
     * Small software raster for CPU-only color/pixel analysis.
     *
     * This intentionally does not reuse a resident provider bitmap: when the foreground wrapper is
     * HARDWARE, reusing it would force RenderProxy#copyHWBitmapInto readback. The encoded/indexed
     * source is sampled directly at 96px instead, matching the reference palette lane.
     */
    fun executePixelAnalysis(
        key: String,
        targetSide: Int = HardwareArtworkPipelinePolicy.PIXEL_ANALYSIS_SIDE,
    ): Bitmap? {
        if (key.isBlank()) return null
        val side = targetSide.coerceIn(32, 256)
        val providerKey = stableArtworkCacheSourceKey(key)
        val previousConfig = threadLocalPreferredConfigOverride.get()
        val previousBypass = threadLocalBypassProviderBitmapReuse.get()
        threadLocalPreferredConfigOverride.set(Bitmap.Config.ARGB_8888)
        threadLocalBypassProviderBitmapReuse.set(true)
        return try {
            decodeBitmap(
                storageKey = providerKey,
                decodeKey = key,
                targetWidth = side,
                targetHeight = side,
                surface = ArtworkSurface.Playback,
                aspectPolicy = ArtworkAspectPolicy.KeepAspect,
            ).bitmap?.takeUnless { it.isRecycled }
        } finally {
            if (previousConfig == null) threadLocalPreferredConfigOverride.remove()
            else threadLocalPreferredConfigOverride.set(previousConfig)
            if (previousBypass == null) threadLocalBypassProviderBitmapReuse.remove()
            else threadLocalBypassProviderBitmapReuse.set(previousBypass)
        }
    }

    /** Provider-owned not-found sentinel query used by default-artwork presentation. */
    fun isKnownNoArtwork(
        key: String,
        targetWidth: Int = AlbumArtTiers.LOW_RES_NORMAL_CAP,
        targetHeight: Int = AlbumArtTiers.LOW_RES_NORMAL_CAP,
    ): Boolean {
        if (key.isBlank()) return true
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = true,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
        )
        val sourceKey = stableArtworkCacheSourceKey(key)
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return hasRecentFailure(sourceKey, "${sourceKey}_${bucket}")
    }

    /**
     * Exact-size peek for the Coil VirtualList lane.
     *
     * The legacy LOADING_LIST resolver clamps normal list thumbnails to 384..512. That is correct
     * for the old callback lane, but the Coil branch now deliberately asks for mode-specific visual
     * targets (small list 192, normal list 384, zoomed list 512, grid 384/512/784). Keep this path
     * file-identity only and avoid broad album aliases.
     */
    fun peekVirtualListThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (key.isBlank()) return null
        val target = resolveVirtualListThumbnailTarget(targetWidth, targetHeight)
        val providerKey = stableArtworkCacheSourceKey(key)
        val cacheKey = virtualListThumbnailCacheKey(providerKey, target.width, target.height)

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled && bitmapCoversVirtualListTarget(cached, target.width, target.height)) {
                return cached
            }
        }

        return memoryCache.getAnyForSource(
            providerKey,
            minimumSide = maxOf(target.width, target.height),
        )?.takeIf { !it.isRecycled && bitmapCoversVirtualListTarget(it, target.width, target.height) }
    }

    /**
     * Stable first-frame fallback for the Coil VirtualList lane.
     *
     * Coil's Compose painter can legitimately have an empty state on the first composition even
     * when the backend has a smaller/previous thumbnail already cached.  That is fine for normal
     * image loading, but it shows up as a one-frame blink when VirtualList leaves the pinch/zoom
     * transition layer and rebinds the settled list/grid cells.  Prefer an exact VirtualList thumb;
     * if it is not ready yet, return the best file-identity bitmap already known by the shared
     * artwork-style cache.  The real Coil request still runs on top and upgrades the image silently.
     */
    fun peekVirtualListFallbackThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val exact = peekVirtualListThumbnail(key, targetWidth, targetHeight)
        if (exact != null && !exact.isRecycled) return exact
        if (key.isBlank()) return null
        val providerKey = stableArtworkCacheSourceKey(key)
        val minimumSide = maxOf(targetWidth, targetHeight).coerceAtLeast(1)
        return memoryCache.getAnyForSource(providerKey, minimumSide = minimumSide)
            ?.takeIf { bitmapCoversVirtualListTarget(it, targetWidth, targetHeight) }
            ?: memoryCache.getAnyForSource(key, minimumSide = minimumSide)
                ?.takeIf { bitmapCoversVirtualListTarget(it, targetWidth, targetHeight) }
    }

    /**
     * Synchronous exact-size decode entry used by the Coil VirtualList lane.
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

        val target = resolveVirtualListThumbnailTarget(targetWidth, targetHeight)
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = stableArtworkCacheSourceKey(key)
        val bucket = maxOf(actualWidth, actualHeight)
        val cacheKey = virtualListThumbnailCacheKey(providerKey, actualWidth, actualHeight)
        val failureSourceKey = stableArtworkCacheSourceKey(key)
        val failureCacheKey = cacheKey

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled && bitmapCoversVirtualListTarget(cached, actualWidth, actualHeight)) return cached
        }
        memoryCache.getAnyForSource(providerKey, minimumSide = maxOf(actualWidth, actualHeight))?.let { cached ->
            if (!cached.isRecycled && bitmapCoversVirtualListTarget(cached, actualWidth, actualHeight)) return cached
        }
        if (hasRecentFailure(failureSourceKey, failureCacheKey)) return null

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
            return bitmap
        }
        if (decodeResult.terminalNoArt) {
            rememberFailure(failureSourceKey, failureCacheKey)
        }
        return null
    }

    private fun resolveVirtualListThumbnailTarget(
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        val side = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
            .coerceAtMost(AlbumArtTiers.HI_RES_SIDE)
        return Size(side, side)
    }

    private fun virtualListThumbnailCacheKey(
        providerKey: String,
        targetWidth: Int,
        targetHeight: Int
    ): String {
        val side = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        return "${providerKey}_pl${side}"
    }

    private fun bitmapCoversVirtualListTarget(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        if (!bitmap.isValidArtworkBitmap()) return false
        val actual = maxOf(bitmap.width, bitmap.height)
        val requested = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        return actual >= requested
    }

    private fun needsPlaybackWrapperNormalization(
        surface: ArtworkSurface,
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Boolean {
        if (surface != ArtworkSurface.Playback || !bitmap.isValidArtworkBitmap()) return false
        val hardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        if (!HardwareArtworkPipelinePolicy.allowPlaybackWrapperNormalization(hardwareBitmap)) return false
        val requested = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        val actual = maxOf(bitmap.width, bitmap.height)
        // Player cards never need an arbitrary original-size cache entry during page motion. Keep
        // Fullscreen separate; for Playback use the provider's requested low/high wrapper size.
        return requested <= AlbumArtTiers.HI_RES_SIDE && actual > requested
    }

    /** Reuse one source record across list buckets instead of decoding once per holder size. */
    private fun reusableSourceBitmap(
        bitmap: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        val requested = maxOf(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        return bitmap?.takeIf {
            it.isValidArtworkBitmap() &&
                if (aspectPolicy == ArtworkAspectPolicy.KeepAspect) {
                    maxOf(it.width, it.height) >= requested
                } else {
                    minOf(it.width, it.height) >= requested
                }
        }
    }

    private fun Bitmap.isValidArtworkBitmap(): Boolean {
        return !isRecycled && width > 0 && height > 0
    }

    fun peek(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = true, providerAliasKey = providerAliasKey, aspectPolicy = aspectPolicy)
    }

    fun peekThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = false, providerAliasKey = providerAliasKey, aspectPolicy = aspectPolicy)
    }

    private fun peekInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        allowHiRes: Boolean,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
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

        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"

        // Do not hand a smaller bucket to a larger VirtualList cell.  The old path returned the
        // first source bitmap regardless of its dimensions, so a reused 192px/list bitmap could
        // be painted into a 384px/512px grid cell for one frame and then be replaced by a second
        // decode. Keep the source record separate from the drawable tier so
        // boundary by accepting only a bitmap that covers this request's minimum tier.
        return memoryCache.get(cacheKey)
            ?.let { reusableSourceBitmap(it, actualWidth, actualHeight, aspectPolicy) }
            ?: memoryCache.getAnyForSource(providerKey, minimumSide = maxOf(actualWidth, actualHeight))
                ?.let { reusableSourceBitmap(it, actualWidth, actualHeight, aspectPolicy) }
            ?: memoryCache.getAnyForSource(rawKey, minimumSide = maxOf(actualWidth, actualHeight))
                ?.let { reusableSourceBitmap(it, actualWidth, actualHeight, aspectPolicy) }
            ?: if (aspectPolicy == ArtworkAspectPolicy.Crop) {
                memoryCache.getAnyForSource(key, minimumSide = maxOf(actualWidth, actualHeight))
                    ?.let { reusableSourceBitmap(it, actualWidth, actualHeight, aspectPolicy) }
            } else null
    }

    fun peekAny(
        key: String,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        if (key.isBlank()) return null
        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        return memoryCache.getAnyForSource(providerKey)?.takeIf { !it.isRecycled }
            ?: memoryCache.getAnyForSource(rawKey)
            ?: if (aspectPolicy == ArtworkAspectPolicy.Crop) memoryCache.getAnyForSource(key) else null
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
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        exactTarget: Boolean = false,
    ): ArtworkHandle? {
        return acquireInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            allowHiRes = true,
            surface = surface,
            providerAliasKey = providerAliasKey,
            aspectPolicy = aspectPolicy,
            exactTarget = exactTarget,
        )
    }

    fun acquireThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.List,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): ArtworkHandle? {
        return acquireInternal(key, targetWidth, targetHeight, allowHiRes = false, surface = surface, providerAliasKey = providerAliasKey, aspectPolicy = aspectPolicy)
    }

    /**
     * VirtualList holder fast path. Resolve the exact target bucket and the best already-published
     * fallback wrapper under a single cache lock, instead of chaining exact/any/raw peeks.
     */
    fun acquireBestThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.List,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        val target = resolveAlbumArtTarget(
            baseWidth = targetWidth.coerceAtLeast(1),
            baseHeight = targetHeight.coerceAtLeast(1),
            allowHiRes = false,
            priority = BitmapRequest.Priority.LOADING_LIST,
        )
        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val bucket = SizeSlotCache.computeBucket(target.width, target.height)
        return memoryCache.acquireBestForSource(
            exactKey = "${providerKey}_${bucket}",
            sourceKey = providerKey,
            surface = surface,
            minimumFallbackSide = 1,
            allowHighFallback = false,
        )
    }

    fun acquireAny(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        providerAliasKey: String = "",
        minimumSide: Int = 1,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        val requiredSide = minimumSide.coerceAtLeast(1)
        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        // Phase 3d: exact-size memory slots are ref-aware too. Prefer them when present so list
        // and player surfaces protect the exact bucket they are drawing, then fall back to the
        // low/high owner for instant placeholders.
        return memoryCache.acquireAnyForSource(providerKey, surface, requiredSide)
            ?: memoryCache.acquireAnyForSource(rawKey, surface, requiredSide)
            ?: if (aspectPolicy == ArtworkAspectPolicy.Crop) {
                memoryCache.acquireAnyForSource(key, surface, requiredSide)
            } else null
    }

    /**
     * Exact selected playback-tier fast path.
     *
     * A hit here means the provider has already published the authoritative wrapper for the
     * current playback bucket. The bitmap's physical dimensions may still be smaller than the
     * requested target when the embedded source itself is smaller; callers must not reject an
     * exact-tier wrapper solely by comparing bitmap.width/height with preferredPlaybackTargetSide().
     *
     * Callers that miss this exact tier may still bind an acquireAny() same-source wrapper while
     * the high-tier request is in flight, matching ArtworkProvider's low/high retained-wrapper
     * behavior instead of presenting an empty holder.
     */
    fun acquirePreferredPlayback(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Playback,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.KeepAspect,
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        return acquireExactArtworkTier(
            key = key,
            targetSide = preferredPlaybackTargetSide(),
            surface = surface,
            providerAliasKey = providerAliasKey,
            aspectPolicy = aspectPolicy,
        )
    }

    /** True when the exact currently-selected playback target request has completed in memory. */
    fun hasPreferredPlaybackWrapper(
        key: String,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.KeepAspect,
    ): Boolean {
        if (key.isBlank()) return false
        val targetSide = preferredPlaybackTargetSide()
        val providerKey = artworkAspectCacheSourceKey(
            stableArtworkCacheSourceKey(key, providerAliasKey),
            aspectPolicy,
        )
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        val bucket = SizeSlotCache.computeBucket(targetSide, targetSide)
        if (memoryCache.get("${providerKey}_${bucket}") != null) return true
        return rawKey != providerKey && memoryCache.get("${rawKey}_${bucket}") != null
    }

    private fun acquireExactArtworkTier(
        key: String,
        targetSide: Int,
        surface: ArtworkSurface,
        providerAliasKey: String,
        aspectPolicy: ArtworkAspectPolicy,
    ): ArtworkHandle? {
        val providerKey = artworkAspectCacheSourceKey(
            stableArtworkCacheSourceKey(key, providerAliasKey),
            aspectPolicy,
        )
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        val bucket = SizeSlotCache.computeBucket(targetSide, targetSide)
        memoryCache.acquire("${providerKey}_${bucket}", surface)?.let { return it }
        if (rawKey != providerKey) {
            memoryCache.acquire("${rawKey}_${bucket}", surface)?.let { return it }
        }
        return null
    }

    fun preferredPlaybackTargetSide(): Int {
        appContext?.let { return artworkHighTargetSide(it) }
        return AlbumArtTiers.playbackTargetSide(
            increaseResolution = readHighResPreference(),
            displayShortSidePx = AlbumArtTiers.LARGE_DISPLAY_MIN_SIDE_PX,
            maxMemoryBytes = Runtime.getRuntime().maxMemory(),
        )
    }

    fun acquireLoaded(
        key: String,
        bitmap: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): ArtworkHandle? {
        if (key.isBlank() || bitmap == null || bitmap.isRecycled) return null
        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        val resolvedTarget = if (surface == ArtworkSurface.List) {
            resolveAlbumArtTarget(
                baseWidth = targetWidth.coerceAtLeast(1),
                baseHeight = targetHeight.coerceAtLeast(1),
                allowHiRes = false,
                priority = BitmapRequest.Priority.LOADING_LIST
            )
        } else {
            AlbumArtTiers.Target(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        }
        val bucket = SizeSlotCache.computeBucket(resolvedTarget.width, resolvedTarget.height)
        val cacheKey = "${providerKey}_${bucket}"
        return memoryCache.putAndAcquire(
            key = cacheKey,
            bitmap = bitmap,
            bucket = bucket,
            sourceKey = providerKey,
            surface = surface
        ) ?: acquireHandleForBucket(providerKey, rawKey, bucket, surface, maxOf(resolvedTarget.width, resolvedTarget.height))
    }

    private fun acquireInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        allowHiRes: Boolean,
        surface: ArtworkSurface,
        providerAliasKey: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        exactTarget: Boolean = false,
    ): ArtworkHandle? {
        if (key.isBlank()) return null
        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val target = if (exactTarget) {
            AlbumArtTiers.Target(baseWidth, baseHeight)
        } else {
            resolveAlbumArtTarget(
                baseWidth = baseWidth,
                baseHeight = baseHeight,
                allowHiRes = allowHiRes,
                priority = if (allowHiRes) BitmapRequest.Priority.LOADING_WIDGET else BitmapRequest.Priority.LOADING_LIST
            )
        }
        val actualWidth = target.width
        val actualHeight = target.height
        val providerKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key, providerAliasKey), aspectPolicy)
        val rawKey = artworkAspectCacheSourceKey(stableArtworkCacheSourceKey(key), aspectPolicy)
        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${providerKey}_${bucket}"

        memoryCache.acquire(cacheKey, surface)?.let { exact ->
            return exact
        }

        return acquireHandleForBucket(providerKey, rawKey, bucket, surface, maxOf(actualWidth, actualHeight))
    }

    private fun acquireHandleForBucket(
        providerKey: String,
        rawKey: String,
        bucket: Int,
        surface: ArtworkSurface,
        minimumSide: Int
    ): ArtworkHandle? {
        val primary = memoryCache.acquireAnyForSource(providerKey, surface, minimumSide)
        if (primary != null) return primary
        if (rawKey == providerKey) return null
        return memoryCache.acquireAnyForSource(rawKey, surface, minimumSide)
    }

    /**
     * Warm one playback wrapper request per physical neighbour, matching ArtworkImageNode.image provider callback -> provider.
     * Existing list/mini wrappers remain reusable through acquireAny()/source records, but the parked
     * player holder does not launch a second explicit low-tier request before its playback request.
     */
    fun warmPlaybackArt(key: String) {
        if (key.isBlank()) return
        val targetSide = preferredPlaybackTargetSide()
        if (hasPreferredPlaybackWrapper(key)) return
        loadInternal(
            key = key,
            targetWidth = targetSide,
            targetHeight = targetSide,
            priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
            surface = ArtworkSurface.Playback,
            callback = null,
            allowHiRes = true,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        )
    }


    /**
     * Full-cover zoom is opened by a gesture. Pre-warm the large target separately so the gesture
     * layer does not swap from empty -> bitmap while the user's fingers are still on screen.
     */
    fun warmFullCoverArt(key: String) {
        if (key.isBlank()) return
        warmPlaybackArt(key)
        val targetSide = preferredPlaybackTargetSide()
        loadInternal(
            key = key,
            targetWidth = targetSide,
            targetHeight = targetSide,
            priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
            surface = ArtworkSurface.Fullscreen,
            callback = null,
            allowHiRes = true
        )
    }

    fun cancel(request: BitmapRequest) = synchronized(inFlightRegistryLock) {
        // Reference O()/request removal uses the same provider-table monitor as admission. Remove the
        // observer, inspect remaining waiters and retire/cancel the owner atomically so a returning
        // same-pixel artwork holder can never join a key whose source job was removed one instruction ago.
        removeWaitingRequestLocked(request)
        val hasOtherLiveWaiters = hasWaitingRequestsLocked(request.inFlightKey)
        when (resolveProviderCancelAction(request.inFlightOwner, hasOtherLiveWaiters)) {
            ProviderCancelAction.DETACH_OBSERVER_ONLY -> {
                trace("CANCEL_DETACH_OWNER_OBSERVER seq=${request.traceSeq} waiters=true key=${request.key.tailForTrace()}")
            }
            ProviderCancelAction.CANCEL_OBSERVER_ONLY -> {
                request.cancel()
                trace("CANCEL_DETACH_JOINER seq=${request.traceSeq} waiters=true key=${request.key.tailForTrace()}")
            }
            ProviderCancelAction.CANCEL_FLIGHT -> {
                request.cancel()
                val sourceOwner = inFlightOwnerRequests[request.inFlightKey]
                if (sourceOwner != null && sourceOwner !== request) sourceOwner.cancel()
                val target = sourceOwner ?: request
                workerHandlers.forEach { handler ->
                    handler.removeMessages(MSG_LOAD, target)
                    handler.obtainMessage(MSG_CANCEL, target).sendToTarget()
                }
                clearInFlightKeyLocked(target.inFlightKey, target)
            }
        }
    }



    fun notifyLibraryArtworkChanged(reason: String = "library_changed") {
        // A scan completion is only a library snapshot notification. It does not prove that
        // every source changed. Keep source records and their decoded wrappers alive;
        // only an explicit file/artwork mutation calls invalidateArtworkKeys(). Clearing all
        // records here made every visible holder lose its bitmap and re-decode at once.
        failedLogLastAt.clear()
        trace("ARTWORK_REVISION reason=$reason revision=$artworkRevision preserved=true")
        PowerTraceLogger.bitmapRequest(
            state = "artwork_revision",
            priority = BitmapRequest.Priority.LOADING_WIDGET.name,
            size = "-",
            key = reason
        )
    }

    fun clear() {
        memoryCache.clear()
        DecodedArtworkSourceCache.clear()
        EmbeddedArtworkSourceCache.clear(appContext)
        EmbeddedArtworkRegion.clear()
        ArtworkSourceIndex.clear()
        BitmapPool.clear()
        waitingRequests.clear()
        failedCache.clear()
        failedSourceCache.clear()
        failedLogLastAt.clear()
        artworkIndexerKeys.clear()
        artworkIndexerCallbacks.clear()
        artworkRevisionCoalescePending.set(false)
        synchronized(inFlightRegistryLock) {
            inFlightKeys.clear()
            inFlightPriorities.clear()
            inFlightTokens.clear()
            inFlightOwnerRequests.clear()
            promotedInFlightKeys.clear()
        }
        ArtworkRecordRegistry.clear()
    }

    /** Drop only reconstructable in-memory artwork state, preserving disk caches and workers. */
    fun trimMemory() {
        memoryCache.clear()
        DecodedArtworkSourceCache.clear()
        EmbeddedArtworkRegion.clear()
        BitmapPool.clear()
    }

    /**
     * Clear generated album-art caches without touching user-selected/custom artwork files.
     * Pending requests are invalidated before disk removal so a late worker cannot republish the
     * just-cleared generation. Current UI holders may keep their own lease until the next bind.
     */
    fun clearArtworkCaches(context: Context) {
        val app = context.applicationContext
        workerHandlers.forEach { handler ->
            handler.removeMessages(MSG_LOAD)
            handler.removeMessages(MSG_CANCEL)
        }
        clear()
        listOf(
            File(app.cacheDir, DISK_THUMB_DIR),
            File(app.cacheDir, LEGACY_DISK_THUMB_DIR),
            File(app.cacheDir, "albumart"),
            File(app.cacheDir, REMOTE_SOURCE_DIR),
        ).forEach { directory ->
            runCatching { directory.deleteRecursively() }
        }
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

        val cacheCandidates = linkedSetOf<String>().apply {
            addAll(candidates)
            candidates.forEach { add(artworkAspectCacheSourceKey(it, ArtworkAspectPolicy.KeepAspect)) }
        }
        ArtworkRecordRegistry.invalidateSources(cacheCandidates)

        var removedMemory = 0
        for (candidate in cacheCandidates) {
            removedMemory += memoryCache.removeForSource(candidate)
        }

        val sourceKey = firstSourceKey.ifBlank { candidates.firstOrNull().orEmpty() }
        // Extracted source files may have a different cache path from the audio key. Clear the
        // small decoded-source layer on an explicit artwork mutation so an old embedded picture
        // cannot survive a metadata rewrite under an unchanged provider alias.
        DecodedArtworkSourceCache.clear()
        val removedFailed = removeFailureSentinelsFor(candidates, sourceKey)
        val removedWaiting = removeQueuedStateFor(cacheCandidates)
        val removedDisk = deleteDiskThumbnailsFor(cacheCandidates)
        val removedSourceArt = EmbeddedArtworkSourceCache.removeForSources(appContext, candidates)
        val removedSourceIndex = ArtworkSourceIndex.removeAll(candidates)
        candidates.forEach { EmbeddedArtworkRegion.invalidate(pathPartFromArtworkKey(it)) }

        artworkRevision++

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

    private fun addWaitingRequest(request: BitmapRequest) = synchronized(inFlightRegistryLock) {
        addWaitingRequestLocked(request)
    }

    private fun addWaitingRequestLocked(request: BitmapRequest) {
        waitingRequests
            .getOrPut(request.inFlightKey) { CopyOnWriteArrayList() }
            .addIfAbsent(request)
    }

    private fun removeWaitingRequest(request: BitmapRequest) = synchronized(inFlightRegistryLock) {
        removeWaitingRequestLocked(request)
    }

    private fun removeWaitingRequestLocked(request: BitmapRequest) {
        val list = waitingRequests[request.inFlightKey] ?: return
        list.remove(request)
        if (list.isEmpty()) waitingRequests.remove(request.inFlightKey, list)
    }

    private fun hasWaitingRequests(flightKey: String): Boolean = synchronized(inFlightRegistryLock) {
        hasWaitingRequestsLocked(flightKey)
    }

    private fun hasWaitingRequestsLocked(flightKey: String): Boolean =
        waitingRequests[flightKey]?.any { !it.isCancelled } == true

    private fun isArtworkAcceptTokenCurrent(request: BitmapRequest): Boolean {
        return ArtworkRecordRegistry.isCurrent(request.artworkToken, artworkRevision)
    }

    private fun clearInFlightForRequest(request: BitmapRequest) {
        clearInFlightKey(request.inFlightKey, request)
    }

    private fun clearInFlightKey(flightKey: String, ownerRequest: BitmapRequest? = null) = synchronized(inFlightRegistryLock) {
        clearInFlightKeyLocked(flightKey, ownerRequest)
    }

    private fun clearInFlightKeyLocked(flightKey: String, ownerRequest: BitmapRequest? = null) {
        if (ownerRequest != null) {
            val liveOwner = inFlightOwnerRequests[flightKey]
            if (liveOwner !== ownerRequest) return
        }
        inFlightKeys.remove(flightKey)
        inFlightPriorities.remove(flightKey)
        inFlightTokens.remove(flightKey)
        if (ownerRequest != null) inFlightOwnerRequests.remove(flightKey, ownerRequest)
        else inFlightOwnerRequests.remove(flightKey)
        promotedInFlightKeys.remove(flightKey)
    }

    /**
     * Move every live artwork waiter from a stale provider record to the current record without
     * completing its UI callback. baseline implementation keeps the request inside BitmapProvider's request
     * table when provider/source state changes; ArtworkImageNode is not told to invent a second retry
     * lifecycle. The same BitmapRequest objects remain the cancellation/callback owners.
     */
    private fun requeueStaleProviderFlight(
        ownerRequest: BitmapRequest,
        staleFlightKey: String,
        reason: String,
    ) {
        val staleWaiters = waitingRequests.remove(staleFlightKey).orEmpty()
        clearInFlightKey(staleFlightKey, ownerRequest)

        val live = LinkedHashSet<BitmapRequest>()
        // The waiter table is the observer ownership boundary. An in-flight source owner may have
        // already detached its ArtworkImageNode while another waiter kept the provider job alive; never
        // resurrect that detached callback merely because the source owner object still exists.
        staleWaiters.forEach { request -> if (!request.isCancelled) live += request }
        if (live.isEmpty()) {
            trace("STALE_FLIGHT_DROP_EMPTY reason=$reason seq=${ownerRequest.traceSeq} key=${ownerRequest.key.tailForTrace()}")
            return
        }

        val byFlight = linkedMapOf<String, MutableList<BitmapRequest>>()
        live.forEach { request ->
            val nextToken = ArtworkRecordRegistry.tokenFor(
                sourceVersionKey = request.cacheSourceKey,
                cacheKey = request.cacheKey,
                bucket = request.bucket,
                uiRevision = artworkRevision,
            )
            if (request.moveToArtworkRecord(nextToken)) {
                byFlight.getOrPut(request.inFlightKey) { mutableListOf() }.add(request)
            }
        }

        val handlers = workerHandlers
        if (handlers.isEmpty()) return
        byFlight.forEach { (nextFlightKey, requests) ->
            if (requests.isEmpty()) return@forEach
            val waiters = waitingRequests.getOrPut(nextFlightKey) { CopyOnWriteArrayList() }
            requests.forEach(waiters::addIfAbsent)

            val nextOwner = requests.minByOrNull { it.priority.level } ?: return@forEach
            requests.forEach { it.inFlightOwner = false }
            if (claimProviderFlight(nextOwner)) {
                trace("STALE_FLIGHT_REQUEUE reason=$reason ownerSeq=${nextOwner.traceSeq} waiters=${requests.size} key=${nextOwner.key.tailForTrace()}")
                enqueueRequest(nextOwner, handlers, forceFront = nextOwner.priority.level <= BitmapRequest.Priority.LOADING_LIST.level)
            } else {
                // A request for the fresh record won the race. Keep these artwork waiters attached to its
                // provider-owned flight; that owner will dispatch their original callbacks.
                requests.forEach { it.inFlightOwner = false }
                trace("STALE_FLIGHT_JOIN_CURRENT reason=$reason waiters=${requests.size} key=${requests.first().key.tailForTrace()}")
            }
        }
    }

    private fun deliverResult(
        ownerRequest: BitmapRequest,
        bitmap: Bitmap?,
        providerSourceString: String = "",
    ) {
        val flightKey = ownerRequest.inFlightKey
        val tokenCurrent = isArtworkAcceptTokenCurrent(ownerRequest)
        if (bitmap != null && !bitmap.isRecycled && tokenCurrent && TransitionPerfTrace.isActive()) {
            TransitionPerfTrace.count(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                    TransitionPerfEvent.BITMAP_RESULT_HARDWARE
                } else {
                    TransitionPerfEvent.BITMAP_RESULT_SOFTWARE
                }
            )
            TransitionPerfTrace.mark(
                "bitmap_delivery",
                "seq=${ownerRequest.traceSeq} id=${System.identityHashCode(bitmap)} " +
                    "size=${bitmap.width}x${bitmap.height} config=${bitmap.config?.name ?: "null"} " +
                    "target=${ownerRequest.targetWidth}x${ownerRequest.targetHeight} bucket=${ownerRequest.bucket} " +
                    "priority=${ownerRequest.priority} surface=${ownerRequest.surface} key=${ownerRequest.key.tailForTrace()}",
            )
        }
        if (!tokenCurrent && (ownerRequest.wrapperCallback != null || hasWaitingRequests(flightKey))) {
            trace("DELIVER_REQUEUE_STALE ownerSeq=${ownerRequest.traceSeq} key=${ownerRequest.key.tailForTrace()}")
            requeueStaleProviderFlight(ownerRequest, flightKey, reason = "before_delivery")
            return
        }

        // Publish a successful current provider result before handing wrapper leases to listeners.
        // Cancelled owner-only work is dropped in WorkerHandler before reaching this point.
        if (bitmap != null && !bitmap.isRecycled && tokenCurrent) {
            memoryCache.put(
                key = ownerRequest.cacheKey,
                bitmap = bitmap,
                bucket = ownerRequest.bucket,
                sourceKey = ownerRequest.cacheSourceKey,
                sourceString = artworkAspectSourceString(providerSourceString, ownerRequest.aspectPolicy),
            )
        }

        // The provider must retain a published wrapper until main-thread artwork consumers have had a
        // chance to acquire their own leases.  Without this temporary provider lease, a fast
        // serial decode run can evict a zero-ref fresh entry before its callback is dispatched.
        val deliveryLease = if (bitmap != null && !bitmap.isRecycled && tokenCurrent &&
            waitingRequests[flightKey]?.any { !it.isCancelled && it.wrapperCallback != null } == true
        ) {
            memoryCache.acquireBestForSource(
                exactKey = ownerRequest.cacheKey,
                sourceKey = ownerRequest.cacheSourceKey,
                surface = ownerRequest.surface,
                minimumFallbackSide = 1,
            )
        } else {
            null
        }

        mainHandler.post {
            var requeued = false
            try {
                val pending = waitingRequests[flightKey].orEmpty()
                val staleAtDispatch = !isArtworkAcceptTokenCurrent(ownerRequest) ||
                    pending.any { !it.isCancelled && !isArtworkAcceptTokenCurrent(it) }
                if (staleAtDispatch && (ownerRequest.wrapperCallback != null || pending.isNotEmpty())) {
                    trace("CALLBACK_REQUEUE_STALE ownerSeq=${ownerRequest.traceSeq} waiters=${pending.size} key=${ownerRequest.key.tailForTrace()}")
                    requeueStaleProviderFlight(ownerRequest, flightKey, reason = "before_callback")
                    requeued = true
                    return@post
                }

                val requests = waitingRequests.remove(flightKey).orEmpty()
                requests.forEach { it.terminalNoArt = ownerRequest.terminalNoArt }
                trace("CALLBACK_DISPATCH ownerSeq=${ownerRequest.traceSeq} waiters=${requests.size} result=${bitmap != null} key=${ownerRequest.key.tailForTrace()}")

                for ((requestIndex, request) in requests.withIndex()) {
                    if (request.isCancelled) continue
                    // All live requests on this revisioned flight were checked above. If a source
                    // invalidation lands between two artwork callbacks, preserve the current + remaining
                    // callback owners and move them to the fresh record instead of dropping them.
                    if (!isArtworkAcceptTokenCurrent(request)) {
                        trace("CALLBACK_UNEXPECTED_STALE seq=${request.traceSeq} cacheKey=${request.cacheKey.tailForTrace()} key=${request.key.tailForTrace()}")
                        val pendingAgain = waitingRequests.getOrPut(flightKey) { CopyOnWriteArrayList() }
                        for (remainingIndex in requestIndex until requests.size) {
                            val remaining = requests[remainingIndex]
                            if (!remaining.isCancelled) pendingAgain.addIfAbsent(remaining)
                        }
                        requeueStaleProviderFlight(ownerRequest, flightKey, reason = "dispatch_race")
                        requeued = true
                        break
                    }

                    if (bitmap != null && !bitmap.isRecycled) {
                        request.transitionTo(BitmapRequest.State.AVAILABLE)
                    }
                    deliverCallback(request, bitmap)
                }
            } finally {
                deliveryLease?.release()
                if (!requeued) {
                    // Clear the exact old flight. ownerRequest.artworkToken is mutable when a stale
                    // provider record is requeued, so never derive this key again in the finally.
                    clearInFlightKey(flightKey, ownerRequest)
                }
            }
        }
    }

    /**
     * Complete list listeners waiting for an indexer result on the main thread.
     *
     * An indexer request can be invalidated before it reaches the worker (for example when a
     * fling replaces the viewport).  Those requests do not pass through deliverResult(), so the
     * callback registry must be drained explicitly or a recycled physical cell keeps a callback
     * alive until the same cache key is requested again.
     */
    private fun dispatchArtworkIndexerCallbacks(
        indexKey: String,
        bitmap: Bitmap?
    ) {
        val callbacks = artworkIndexerCallbacks.remove(indexKey).orEmpty()
        if (callbacks.isEmpty()) return
        mainHandler.post {
            callbacks.forEach { callback -> callback(bitmap) }
        }
    }

    private fun deliverCallback(request: BitmapRequest, bitmap: Bitmap?) {
        val wrapperCallback = request.wrapperCallback
        if (wrapperCallback != null) {
            if (bitmap == null || bitmap.isRecycled) {
                wrapperCallback(null)
                return
            }
            // Reference artwork consumers receive a provider wrapper, not a naked Bitmap that must be
            // looked up again later.  Publish+retain the exact successful result atomically here.
            // A busy main thread must not let the zero-ref cache entry be evicted between worker
            // completion and artwork holder delivery.
            val handle = memoryCache.acquire(request.cacheKey, request.surface)
                ?: memoryCache.acquireBestForSource(
                    exactKey = request.cacheKey,
                    sourceKey = request.cacheSourceKey,
                    surface = request.surface,
                    minimumFallbackSide = 1,
                )
                ?: memoryCache.putAndAcquire(
                    key = request.cacheKey,
                    bitmap = bitmap,
                    bucket = request.bucket,
                    sourceKey = request.cacheSourceKey,
                    surface = request.surface,
                )
            wrapperCallback(handle)
            return
        }
        request.callback?.invoke(bitmap)
    }

    private fun postNull(request: BitmapRequest, terminalNoArt: Boolean) {
        request.terminalNoArt = terminalNoArt
        mainHandler.post {
            if (!request.isCancelled && isArtworkAcceptTokenCurrent(request)) {
                deliverCallback(request, null)
            }
        }
    }

    private fun getPreferredConfig(): Bitmap.Config {
        threadLocalPreferredConfigOverride.get()?.let { return it }
        val requested24Bit = try {
            AppPreferences.AlbumArt.forceArgb8888
        } catch (_: Exception) {
            false
        }
        val use24Bit = ArtworkResolutionPolicy.use24BitRgbEffective(
            requested = requested24Bit,
            maxMemoryBytes = Runtime.getRuntime().maxMemory(),
        )

        // reference player build 1026 promotes both low/high provider configs to HARDWARE when its
        // API/vendor/heap/aa_8888 gate is satisfied. Otherwise aa_8888 selects software ARGB_8888;
        // with the preference disabled (or below the heap gate) the provider falls back to RGB_565.
        return when {
            use24Bit && useHardwareBitmap -> Bitmap.Config.HARDWARE
            use24Bit -> Bitmap.Config.ARGB_8888
            else -> Bitmap.Config.RGB_565
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
        val lowProviderRequest = priority == BitmapRequest.Priority.LOADING_LIST ||
            priority == BitmapRequest.Priority.LOADING_NOTIFICATION ||
            (priority == BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH && !allowHiRes)
        if (lowProviderRequest) {
            // reference player build 1026 owns one authoritative low wrapper side per provider: 512 on
            // displays whose short side is >=1000px, otherwise 256. Holder/card dimensions do not
            // create separate 192/384 wrapper tiers; the retained artwork view scales the retained wrapper.
            val lowSide = appContext?.let(::artworkLowTargetSide)
                ?: ArtworkResolutionPolicy.LARGE_DISPLAY_LOW_SIDE
            return AlbumArtTiers.Target(lowSide, lowSide)
        }
        return AlbumArtTiers.resolve(
            requestedWidth = baseWidth,
            requestedHeight = baseHeight,
            allowHiRes = allowHiRes,
            priority = priority,
            highResEnabled = readHighResPreference()
        )
    }

    private fun readHighResPreference(): Boolean {
        return try {
            AppPreferences.AlbumArt.useHigherRes
        } catch (_: Exception) {
            false
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
        sourceSurface: ArtworkSurface = ArtworkSurface.Indexer,
        onReady: ((Bitmap?) -> Unit)? = null
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
        memoryCache.get(indexKey)?.takeIf { !it.isRecycled }?.let { cached ->
            if (onReady != null) mainHandler.post { onReady(cached) }
            return
        }
        if (onReady != null) {
            artworkIndexerCallbacks
                .getOrPut(indexKey) { CopyOnWriteArrayList() }
                .addIfAbsent(onReady)
        }
        if (!artworkIndexerKeys.add(indexKey)) return
        val acceptToken = ArtworkRecordRegistry.tokenFor(
            sourceVersionKey = artworkAspectCacheSourceKey(
                failureSourceKey,
                ArtworkAspectPolicy.Crop,
            ),
            cacheKey = indexKey,
            bucket = bucket,
            uiRevision = artworkRevision
        )

        val handlers = workerHandlers
        if (handlers.isEmpty()) {
            artworkIndexerKeys.remove(indexKey)
            artworkIndexerCallbacks.remove(indexKey)
            return
        }

        trace("INDEX_SCHEDULE reason=$reason surface=$sourceSurface size=${side}x$side provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
        val request = BitmapRequest(
            key = storageKey,
            decodeKey = decodeKey,
            targetWidth = side,
            targetHeight = side,
            priority = BitmapRequest.Priority.LOADING_PREFETCH,
            callback = { bitmap ->
                dispatchArtworkIndexerCallbacks(indexKey, bitmap)
            },
            surface = ArtworkSurface.Indexer,
            artworkToken = acceptToken
        ).apply {
            this.traceSeq = this@BitmapProvider.traceSeq.incrementAndGet()
        }

        if (!claimProviderFlight(request)) {
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
                    sourceSurface = sourceSurface,
                    onReady = onReady
                )
            }, 80L)
            return
        }
        // claimProviderFlight() atomically published this indexer request as the owner. A visible
        // request may later promote it, but must never create a second decode owner for the bucket.
        enqueueRequest(request, handlers, forceFront = false)
    }

    private fun coalesceIndexerSiblingTiers(
        request: BitmapRequest,
        primaryBitmap: Bitmap?
    ) {
        if (request.surface != ArtworkSurface.Indexer) return
        if (primaryBitmap == null || primaryBitmap.isRecycled) return
        if (!isArtworkAcceptTokenCurrent(request)) return

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

            if (!isArtworkAcceptTokenCurrent(request)) {
                BitmapPool.recycle(bitmap)
                break
            }

            memoryCache.put(cacheKey, bitmap, bucket, sourceKey = providerKey)
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
    }

    private fun decodeBitmap(
        storageKey: String,
        decodeKey: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface,
        sourceDecodeAllowed: Boolean = surface.allowsSourceDecode,
        externalArtworkPath: String = "",
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
        onIndexerReady: ((Bitmap?) -> Unit)? = null
    ): ArtworkDecodeResult {
        return try {
            val cacheStorageKey = artworkAspectCacheSourceKey(storageKey, aspectPolicy)
            decodeDiskThumbnail(cacheStorageKey, targetWidth, targetHeight, aspectPolicy)?.let {
                return ArtworkDecodeResult(bitmap = it, terminalNoArt = false)
            }
            if (storageKey != decodeKey) {
                decodeDiskThumbnail(artworkAspectCacheSourceKey(decodeKey, aspectPolicy), targetWidth, targetHeight, aspectPolicy)?.let {
                    return ArtworkDecodeResult(bitmap = it, terminalNoArt = false)
                }
            }

            if (!sourceDecodeAllowed || !surface.allowsSourceDecode) {
                // A moving visible row is deliberately different from Prefetch: it may consume an
                // existing memory/disk wrapper immediately, but a cache miss must stay a placeholder
                // until the row is stable. Do not spawn an Indexer flight while motion owns the CPU.
                if (sourceDecodeAllowed && surface.scheduleIndexerOnMiss) {
                    scheduleArtworkIndex(
                        storageKey = storageKey,
                        decodeKey = decodeKey,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        reason = surface.name,
                        sourceSurface = surface,
                        onReady = onIndexerReady
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
                acceptedKinds = indexedKinds,
                aspectPolicy = aspectPolicy,
            ) ?: when {
                decodeKey.startsWith("https://", ignoreCase = true) ||
                    decodeKey.startsWith("http://", ignoreCase = true) -> {
                    if (ArtworkSourceSelectionPolicy.isAudioArtworkKey(decodeKey)) {
                        decodeRemoteAudioArtwork(
                            storageKey = storageKey,
                            url = decodeKey,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            aspectPolicy = aspectPolicy,
                        )
                    } else {
                        decodeRemoteImage(
                            storageKey = storageKey,
                            url = decodeKey,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            aspectPolicy = aspectPolicy,
                        )
                    }
                }

                decodeKey.startsWith("file://") -> {
                    val path = pathPartFromArtworkKey(decodeKey.removePrefix("file://"))
                    decodeFromAnyFilePath(storageKey, path, targetWidth, targetHeight, aspectPolicy)
                }

                decodeKey.startsWith("content://") -> {
                    val sourceString = canonicalFailureSourceKey(decodeKey)
                    providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)
                        ?.let { SourceDecodeResult.Found(it, sourceString) }
                        ?: decodeFromUri(Uri.parse(decodeKey), targetWidth, targetHeight, aspectPolicy)
                            ?.let { SourceDecodeResult.Found(it, sourceString) }
                        ?: SourceDecodeResult.TransientFailure
                }

                decodeKey.startsWith("audio://") -> {
                    val path = decodeKey.removePrefix("audio://").substringBefore("|")
                    decodeFromAnyFilePath(storageKey, path, targetWidth, targetHeight, aspectPolicy)
                }

                else -> {
                    decodeFromAnyFilePath(storageKey, pathPartFromArtworkKey(decodeKey), targetWidth, targetHeight, aspectPolicy)
                }
            }
            val withExternalFallback = if (
                (decoded is SourceDecodeResult.ConfirmedAbsent ||
                    decoded is SourceDecodeResult.TransientFailure) &&
                ArtworkSourceSelectionPolicy.isAudioArtworkKey(decodeKey) &&
                externalArtworkPath.isNotBlank()
            ) {
                decodeExternalArtwork(
                    storageKey = storageKey,
                    artworkPath = externalArtworkPath,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    aspectPolicy = aspectPolicy,
                ) ?: decoded
            } else {
                decoded
            }
            when (withExternalFallback) {
                is SourceDecodeResult.Found -> {
                    val decodedBitmap = withExternalFallback.bitmap
                    if (decodedBitmap.isRecycled) return ArtworkDecodeResult.LightweightMiss
                    if (surface.allowDiskThumbnailWrite) {
                        saveDiskThumbnailAsync(cacheStorageKey, targetWidth, targetHeight, decodedBitmap)
                    }
                    if (surface == ArtworkSurface.Indexer) {
                        // The row request callback and provider memory/disk caches are enough to make
                        // visible artwork appear.  A global revision per indexer completion makes all
                        // VirtualList rows rebind while scrolling, which is the flicker we are fixing.
                        trace("INDEXER_READY revision=$artworkRevision provider=${storageKey.tailForTrace()} decode=${decodeKey.tailForTrace()}")
                    }
                    return ArtworkDecodeResult(
                        bitmap = decodedBitmap,
                        terminalNoArt = false,
                        // Playback/fullscreen publish one authoritative wrapper. Expanding it into
                        // three sibling decode tiers after every song switch was the largest
                        // avoidable CPU/power spike in the Raw pipeline. reference player promotes the
                        // same low/high record instead; only the background indexer coalesces.
                        coalesceSourceTiers = surface == ArtworkSurface.Indexer,
                        providerSourceString = withExternalFallback.providerSourceString,
                    )
                }

                SourceDecodeResult.TransientFailure -> {
                    // Do not turn a source-open/native decoder race into the five-minute no-art
                    // sentinel. Retry the source record after a transient failure.
                    return ArtworkDecodeResult.LightweightMiss
                }

                SourceDecodeResult.ConfirmedAbsent -> {
                    return ArtworkDecodeResult.NoArt
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmap failed: ${decodeKey.takeLast(80)} surface=$surface", e)
            // A source-open/decode exception is transient, not proof that the file has no art.
            // Do not poison the source record and suppress later binds.
            ArtworkDecodeResult.LightweightMiss
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

    private sealed interface SourceDecodeResult {
        data class Found(
            val bitmap: Bitmap,
            val providerSourceString: String,
        ) : SourceDecodeResult
        data object ConfirmedAbsent : SourceDecodeResult
        data object TransientFailure : SourceDecodeResult
    }

    private fun decodeExternalArtwork(
        storageKey: String,
        artworkPath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): SourceDecodeResult? {
        val cleanPath = pathPartFromArtworkKey(artworkPath.trim())
        if (cleanPath.isBlank()) return null
        if (cleanPath.startsWith("content://", ignoreCase = true)) {
            val sourceString = canonicalFailureSourceKey(cleanPath)
            return providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)
                ?.let { SourceDecodeResult.Found(it, sourceString) }
                ?: decodeFromUri(Uri.parse(cleanPath), targetWidth, targetHeight, aspectPolicy)
                    ?.let { SourceDecodeResult.Found(it, sourceString) }
                ?: SourceDecodeResult.TransientFailure
        }
        val file = File(cleanPath)
        if (!file.isFile || !file.canRead()) return SourceDecodeResult.TransientFailure
        if (!isImageFile(cleanPath)) return SourceDecodeResult.ConfirmedAbsent
        val sourceString = canonicalFailureSourceKey(cleanPath)
        providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)?.let {
            return SourceDecodeResult.Found(it, sourceString)
        }
        val bitmap = decodeImageFile(cleanPath, targetWidth, targetHeight, aspectPolicy)
        if (bitmap == null || bitmap.isRecycled) return SourceDecodeResult.ConfirmedAbsent
        // The audio probe above keeps embedded art authoritative. This record is only a fallback
        // after that probe confirms absence, preserving source-record precedence.
        rememberReusableArtworkSource(
            storageKey = storageKey,
            sourcePath = cleanPath,
            kind = ArtworkSourceSelectionPolicy.IndexedSourceKind.DirectImage,
            shareAcrossEntity = false
        )
        return SourceDecodeResult.Found(
            bitmap = bitmap,
            providerSourceString = sourceString,
        )
    }

    /**
     * Resolve remote artwork into the same provider/source-record lifecycle as local artwork.
     * The encoded response is cached only as a provider backing source; decoded ownership remains
     * in [SizeSlotCache]/[ArtworkHandle], so there is no second image-loader memory or disk cache.
     */
    private fun decodeRemoteImage(
        storageKey: String,
        url: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): SourceDecodeResult {
        val context = appContext ?: return SourceDecodeResult.TransientFailure
        val sourceDir = File(context.cacheDir, REMOTE_SOURCE_DIR)
        if (!sourceDir.exists() && !sourceDir.mkdirs()) return SourceDecodeResult.TransientFailure
        val sourceFile = File(sourceDir, "${stableDigest(url)}.source")

        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            val temp = File(sourceDir, "${sourceFile.name}.tmp-${Thread.currentThread().id}")
            val downloaded = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RawSMusic")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) return@runCatching false
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > REMOTE_SOURCE_MAX_BYTES) return@runCatching false
                    var copied = 0L
                    connection.inputStream.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                copied += read
                                if (copied > REMOTE_SOURCE_MAX_BYTES) return@runCatching false
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (copied <= 0L) return@runCatching false
                    if (!temp.renameTo(sourceFile) && !sourceFile.isFile) return@runCatching false
                    true
                } finally {
                    connection.disconnect()
                    if (temp.exists() && sourceFile.exists()) temp.delete()
                }
            }.getOrElse {
                temp.delete()
                false
            }
            if (!downloaded || !sourceFile.isFile || sourceFile.length() <= 0L) {
                temp.delete()
                return SourceDecodeResult.TransientFailure
            }
        }

        providerSourceCacheHit(storageKey, url, targetWidth, targetHeight, aspectPolicy)?.let {
            return SourceDecodeResult.Found(it, url)
        }
        val bitmap = decodeImageFile(sourceFile.absolutePath, targetWidth, targetHeight, aspectPolicy)
            ?: return SourceDecodeResult.TransientFailure
        return SourceDecodeResult.Found(bitmap, url)
    }

    /**
     * Remote audio is not a remote image. Extract its embedded picture through the authenticated
     * FFmpeg lane registered by the source owner instead of downloading the whole audio object or
     * handing the URL to MediaMetadataRetriever/TagLib without credentials.
     */
    private fun decodeRemoteAudioArtwork(
        storageKey: String,
        url: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): SourceDecodeResult {
        providerSourceCacheHit(storageKey, url, targetWidth, targetHeight, aspectPolicy)?.let {
            return SourceDecodeResult.Found(it, url)
        }

        val remote = RemoteHttpStreamRegistry.lookup(url) ?: return SourceDecodeResult.TransientFailure
        val context = appContext ?: return SourceDecodeResult.TransientFailure
        val sourceDir = File(context.cacheDir, REMOTE_SOURCE_DIR)
        if (!sourceDir.exists() && !sourceDir.mkdirs()) return SourceDecodeResult.TransientFailure
        val sourceFile = File(sourceDir, "${stableDigest(url)}.embedded")

        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            sourceFile.delete()
            val result = runCatching {
                val resolvedUrl = remote.resolveUrl(url)
                FFmpegBridge.extractCover(
                    inputPath = resolvedUrl,
                    outputPath = sourceFile.absolutePath,
                    headers = remote.resolveHeaders(url),
                    userAgent = remote.userAgent,
                )
            }.getOrDefault(-1)
            if (result != 0 || !sourceFile.isFile || sourceFile.length() <= 0L) {
                sourceFile.delete()
                return SourceDecodeResult.ConfirmedAbsent
            }
        }

        val bitmap = decodeImageFile(sourceFile.absolutePath, targetWidth, targetHeight, aspectPolicy)
            ?: return SourceDecodeResult.ConfirmedAbsent
        rememberReusableArtworkSource(
            storageKey,
            sourceFile.absolutePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded,
            shareAcrossEntity = false,
        )
        return SourceDecodeResult.Found(bitmap, url)
    }

    private fun decodeFromAnyFilePath(
        storageKey: String,
        path: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): SourceDecodeResult {
        val cleanPath = pathPartFromArtworkKey(path)
        val file = File(cleanPath)
        if (!file.exists() || !file.canRead()) {
            Log.d(TAG, "FILE_NOT_READABLE key=${cleanPath.takeLast(80)} exists=${file.exists()} canRead=${file.canRead()}")
            return SourceDecodeResult.TransientFailure
        }

        if (isImageFile(cleanPath)) {
            val sourceString = canonicalFailureSourceKey(cleanPath)
            providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)?.let {
                return SourceDecodeResult.Found(it, sourceString)
            }
            val bitmap = timedDecodeStage(
                stage = RawArtworkPolicy.DecodeStage.ImageFile,
                key = cleanPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) {
                decodeImageFile(cleanPath, targetWidth, targetHeight, aspectPolicy)?.also {
                    rememberReusableArtworkSource(
                        storageKey,
                        cleanPath,
                        ArtworkSourceSelectionPolicy.IndexedSourceKind.DirectImage,
                        shareAcrossEntity = true
                    )
                }
            }
            return bitmap?.let { SourceDecodeResult.Found(it, sourceString) }
                ?: SourceDecodeResult.ConfirmedAbsent
        }

        return decodeAudioFileWithPolicyOrder(
            storageKey = storageKey,
            cleanPath = cleanPath,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            aspectPolicy = aspectPolicy,
        )
    }

    private fun decodeAudioFileWithPolicyOrder(
        storageKey: String,
        cleanPath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): SourceDecodeResult {
        val embeddedSourceString = canonicalFailureSourceKey(cleanPath)
        providerSourceCacheHit(storageKey, embeddedSourceString, targetWidth, targetHeight, aspectPolicy)?.let {
            return SourceDecodeResult.Found(it, embeddedSourceString)
        }

        restorePersistedEmbeddedArtworkSource(
            storageKey = storageKey,
            cleanPath = cleanPath,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            aspectPolicy = aspectPolicy,
        )?.let {
            return SourceDecodeResult.Found(it, embeddedSourceString)
        }

        return when (val result = AudioArtworkDecodeCoordinator.decode(
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
                            decodeEmbeddedWithRegionHandle(cleanPath, targetWidth, targetHeight, aspectPolicy)
                        RawArtworkPolicy.DecodeStage.NativeSource ->
                            decodeEmbeddedWithNativeTagLib(storageKey, cleanPath, targetWidth, targetHeight, aspectPolicy)
                        RawArtworkPolicy.DecodeStage.Ffmpeg ->
                            decodeCoverWithFfmpeg(cleanPath, targetWidth, targetHeight, aspectPolicy)
                        RawArtworkPolicy.DecodeStage.MediaMetadataRetriever ->
                            decodeEmbeddedWithMediaMetadataRetriever(cleanPath, targetWidth, targetHeight, aspectPolicy)
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
                        targetHeight = targetHeight,
                        aspectPolicy = aspectPolicy,
                    )
                }
            },
            discardRejectedFolder = { bitmap ->
                trace("FOLDER_FALLBACK_REJECTED provider=${storageKey.tailForTrace()} source=${cleanPath.tailForTrace()}")
                if (!bitmap.isRecycled) BitmapPool.recycle(bitmap)
            }
        )) {
            is AudioArtworkDecodeCoordinator.DecodeResult.Found -> {
                val folderSource = if (
                    ArtworkSourceIndex.embeddedStateFor(storageKey) == ArtworkSourceAuthority.EmbeddedState.Absent
                ) {
                    ArtworkSourceIndex.sourcePathFor(
                        storageKey,
                        ArtworkSourceSelectionPolicy.folderIndexedKinds,
                    )
                } else {
                    null
                }
                val sourceString = folderSource
                    ?.let(::canonicalFailureSourceKey)
                    ?: embeddedSourceString
                SourceDecodeResult.Found(result.value, sourceString)
            }
            AudioArtworkDecodeCoordinator.DecodeResult.ConfirmedAbsent ->
                SourceDecodeResult.ConfirmedAbsent
            AudioArtworkDecodeCoordinator.DecodeResult.TransientFailure ->
                SourceDecodeResult.TransientFailure
        }
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
            ArtworkSourceSelectionPolicy.allIndexedKinds,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): SourceDecodeResult.Found? {
        val sourcePath = ArtworkSourceIndex.sourcePathFor(storageKey, acceptedKinds) ?: return null
        val sourceString = canonicalFailureSourceKey(sourcePath)
        providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)?.let {
            return SourceDecodeResult.Found(it, sourceString)
        }
        val bitmap = timedDecodeStage(
            stage = RawArtworkPolicy.DecodeStage.ImageFile,
            key = sourcePath,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { decodeImageFile(sourcePath, targetWidth, targetHeight, aspectPolicy) } ?: return null
        return SourceDecodeResult.Found(bitmap, sourceString)
    }

    private inline fun timedFolderDecodeStage(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>?
    ): AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>? {
        return block()
    }

    private inline fun timedDecodeStage(
        stage: RawArtworkPolicy.DecodeStage,
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> Bitmap?
    ): Bitmap? {
        return withSourceExtractionPermit(stage, key, targetWidth, targetHeight) {
            block()
        }
    }

    private inline fun withSourceExtractionPermit(
        stage: RawArtworkPolicy.DecodeStage,
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        block: () -> Bitmap?
    ): Bitmap? {
        // WorkerHandler is the source serialization boundary.  Do not add a second semaphore
        // layer here: it would only create blocking waits inside the same queue and make a dense
        // grid appear to stall while no useful decode work is progressing.
        return block()
    }

    private fun decodeImageFile(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        return try {
            val opts = threadLocalDecodeOptions.get()!!
            val sourceCacheKey = DecodedArtworkSourceCache.keyFor(filePath)
            val cachedBounds = sourceCacheKey?.let { DecodedArtworkSourceCache.getBounds(it) }
            val origWidth: Int
            val origHeight: Int
            if (cachedBounds != null) {
                origWidth = cachedBounds.width
                origHeight = cachedBounds.height
            } else {
                // Keep dimensions alongside the decoded wrapper. Avoid
                // repeating the bounds pass for every physical list holder while still versioning
                // the record by canonical path, file length, and lastModified.
                opts.inJustDecodeBounds = true
                opts.inSampleSize = 1
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                opts.inMutable = false
                BitmapFactory.decodeFile(filePath, opts)
                origWidth = opts.outWidth
                origHeight = opts.outHeight
                if (sourceCacheKey != null && origWidth > 0 && origHeight > 0) {
                    DecodedArtworkSourceCache.putBounds(sourceCacheKey, origWidth, origHeight)
                }
            }
            if (origWidth <= 0 || origHeight <= 0) {
                return null
            }

            // Keep separate low-resolution and high-resolution source tiers. The source
            // record is upgraded by the actual request, not by an unconditional 512px decode:
            // small list cells stay cheap, while a later larger cell can promote the record.
            val requestedSide = maxOf(targetWidth, targetHeight)
            val sourceTierSide = when {
                requestedSide <= ARTWORK_SOURCE_SMALL_MAX_SIDE -> ARTWORK_SOURCE_SMALL_MAX_SIDE
                requestedSide <= ARTWORK_SOURCE_LOW_RES_MAX_SIDE -> ARTWORK_SOURCE_LOW_RES_MAX_SIDE
                else -> ARTWORK_SOURCE_HI_RES_MAX_SIDE
            }
            val sourceSide = minOf(
                maxOf(origWidth, origHeight),
                requestedSide,
                sourceTierSide
            )
            val config = getPreferredConfig()
            val useDecodedSourceRescaleCache =
                threadLocalBypassProviderBitmapReuse.get() != true &&
                    HardwareArtworkPipelinePolicy.allowDecodedSourceRescaleCache(
                        isHardwarePreferred = config == Bitmap.Config.HARDWARE,
                    )
            if (
                useDecodedSourceRescaleCache &&
                sourceCacheKey != null &&
                sourceSide >= maxOf(targetWidth, targetHeight)
            ) {
                val cachedScaled = DecodedArtworkSourceCache.withBitmap(
                    key = sourceCacheKey,
                    minimumSide = maxOf(targetWidth, targetHeight)
                ) { source ->
                    // A low-res source record may still be present when a higher tier arrives.
                    // Never turn that into an upscaled cover; let the source record upgrade.
                    if (maxOf(source.width, source.height) >= maxOf(targetWidth, targetHeight)) {
                        scaleBitmapDetachedFromSource(
                            source = source,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            aspectPolicy = aspectPolicy,
                        )
                    } else {
                        null
                    }
                }
                if (cachedScaled != null && !cachedScaled.isRecycled) return cachedScaled
            }

            // real decode pass
            opts.inJustDecodeBounds = false
            opts.inSampleSize = if (sourceCacheKey != null) {
                calculateSourceInSampleSize(origWidth, origHeight, sourceSide)
            } else {
                calculateInSampleSize(
                    origWidth = origWidth,
                    origHeight = origHeight,
                    reqWidth = targetWidth,
                    reqHeight = targetHeight
                )
            }
            opts.inPreferredConfig = config
            opts.inMutable = config != Bitmap.Config.HARDWARE

            val decoded = BitmapFactory.decodeFile(filePath, opts) ?: return null

            if (
                useDecodedSourceRescaleCache &&
                sourceCacheKey != null &&
                sourceSide >= maxOf(targetWidth, targetHeight)
            ) {
                val retained = DecodedArtworkSourceCache.put(sourceCacheKey, decoded)
                if (retained) {
                    val scaled = DecodedArtworkSourceCache.withBitmap(
                        key = sourceCacheKey,
                        minimumSide = maxOf(targetWidth, targetHeight),
                    ) { source ->
                        scaleBitmapDetachedFromSource(
                            source = source,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            aspectPolicy = aspectPolicy,
                        )
                    }
                    if (scaled != null && !scaled.isRecycled) return scaled
                    return null
                }
            }

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight, aspectPolicy)
        } catch (e: OutOfMemoryError) {
            // Artwork is optional.  Do not let one oversized/corrupt cover terminate playback or
            // the whole process; the caller will use its placeholder/fallback path.
            Log.e(TAG, "decodeImageFile out of memory: ${filePath.takeLast(120)}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "decodeImageFile failed: $filePath", e)
            return null
        }
    }

    private fun decodeEmbeddedWithRegionHandle(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        val cleanPath = pathPartFromArtworkKey(filePath)
        val region = EmbeddedArtworkRegion.find(cleanPath) ?: return null
        return decodeImageRegion(region, targetWidth, targetHeight, aspectPolicy)
    }

    private fun decodeImageRegion(
        region: EmbeddedArtworkRegion.Handle,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
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

        return normalizeDecodedBitmap(decoded, targetWidth, targetHeight, aspectPolicy)
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

    /**
     * Restore the positive embedded-source record from the previous process before probing the
     * audio container again. The backing artifact is keyed by the concrete file version, so this
     * path cannot resurrect artwork after the source file has changed.
     */
    private fun restorePersistedEmbeddedArtworkSource(
        storageKey: String,
        cleanPath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        val context = appContext ?: return null
        if (cleanPath.isBlank()) return null
        val sourceVersionKey = canonicalFailureSourceKey(cleanPath)
        val handle = EmbeddedArtworkSourceCache.findExisting(
            context = context,
            audioPath = cleanPath,
            sourceKey = sourceVersionKey,
        ) ?: return null

        val bitmap = decodeImageFile(
            filePath = handle.filePath,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            aspectPolicy = aspectPolicy,
        )
        if (bitmap == null || bitmap.isRecycled) {
            // A truncated cache file is not positive artwork evidence. Remove only this exact
            // source-version artifact and allow the normal extraction pipeline to rebuild it.
            EmbeddedArtworkSourceCache.removeForSources(context, listOf(sourceVersionKey))
            return null
        }

        rememberReusableArtworkSource(
            storageKey = storageKey,
            sourcePath = handle.filePath,
            kind = ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded,
            shareAcrossEntity = false,
        )
        trace(
            "SOURCE_RESTORE_COLD provider=${storageKey.tailForTrace()} source=${sourceVersionKey.tailForTrace()} bytes=${handle.bytes}"
        )
        return bitmap
    }

    private fun decodeFromReusableArtworkSource(
        storageKey: String,
        decodeKey: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
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
            acceptedKinds = indexedKinds,
            aspectPolicy = aspectPolicy,
        )?.let { return it.bitmap }

        val cleanPath = pathPartFromArtworkKey(decodeKey)
        if (cleanPath.isBlank()) return null
        val file = File(cleanPath)
        if (file.exists() && file.canRead() && isImageFile(cleanPath)) {
            return decodeImageFile(cleanPath, targetWidth, targetHeight, aspectPolicy)?.also {
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
            when (val result = decodeAudioFileWithPolicyOrder(
                storageKey = storageKey,
                cleanPath = cleanPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                aspectPolicy = aspectPolicy,
            )) {
                is SourceDecodeResult.Found -> result.bitmap
                SourceDecodeResult.ConfirmedAbsent,
                SourceDecodeResult.TransientFailure -> null
            }
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
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        return try {
            val sourceHandle = prepareNativeEmbeddedArtworkSource(filePath) ?: return null

            val t0 = android.os.SystemClock.uptimeMillis()
            val decoded = decodeImageFile(sourceHandle.filePath, targetWidth, targetHeight, aspectPolicy)
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
            throw e
        }
    }

    private fun decodeEmbeddedWithMediaMetadataRetriever(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(filePath)
                val data = retriever.embeddedPicture ?: return null

                // Let BitmapFactory decide whether the payload is a valid image. A byte-count
                // cutoff is not a source-record rule and can reject tiny legal covers.
                if (data.isEmpty()) return null

                val bitmap = decodeImageBytes(data, targetWidth, targetHeight, aspectPolicy)
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
            throw e
        }
    }

    private fun decodeImageBytes(
        data: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
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

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight, aspectPolicy)
        } catch (e: Exception) {
            Log.e(TAG, "decodeImageBytes failed", e)
            throw e
        }
    }

    private fun decodeFromUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        val isAlbumArtUri = uri.toString().contains("albumart", ignoreCase = true)

        // v6c: 不信任 MediaStore albumart URI，直接返回 null
        // 有内嵌封面的歌曲通过 audio:// key 自行提取
        if (isAlbumArtUri) {
            Log.d(TAG, "decodeFromUri: albumart URI disabled (v6c) — uri=${uri.toString().tailForTrace()}")
            return null
        }

        decodeImageContentUri(uri, targetWidth, targetHeight, aspectPolicy)?.let { return it }

        return null
    }

    private fun decodeImageContentUri(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
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

            normalizeDecodedBitmap(decoded, targetWidth, targetHeight, aspectPolicy)
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
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        val context = appContext ?: return null

        return try {
            val file = File(audioPath)
            if (!file.exists() || !file.canRead()) return null

            val coverDir = File(context.cacheDir, "albumart")
            if (!coverDir.exists()) coverDir.mkdirs()

            val cacheName = "cover_${audioPath.hashCode()}_${file.length()}_${file.lastModified()}.jpg"
            val coverFile = File(coverDir, cacheName)

            if (!coverFile.exists() || coverFile.length() <= 0) {
                val result = FFmpegBridge.extractCover(
                    audioPath,
                    coverFile.absolutePath
                )

                if (result != 0 || !coverFile.exists() || coverFile.length() <= 0) {
                    coverFile.delete()
                    return null
                }
            }

            decodeImageFile(coverFile.absolutePath, targetWidth, targetHeight, aspectPolicy)
        } catch (e: Exception) {
            Log.e(TAG, "decodeCoverWithFfmpeg failed: ${audioPath.takeLast(80)}", e)
            throw e
        }
    }

    private fun decodeFolderCoverCandidate(
        storageKey: String,
        audioPath: String?,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): AudioArtworkDecodeCoordinator.FolderCandidate<Bitmap>? {
        val indexedPath = ArtworkSourceIndex.sourcePathFor(
            storageKey,
            ArtworkSourceSelectionPolicy.folderIndexedKinds
        )
        val file = indexedPath?.let(::File) ?: FolderArtworkLocator.find(audioPath) ?: return null
        val sourceString = canonicalFailureSourceKey(file.absolutePath)
        val bitmap = providerSourceCacheHit(storageKey, sourceString, targetWidth, targetHeight, aspectPolicy)
            ?: decodeImageFile(file.absolutePath, targetWidth, targetHeight, aspectPolicy)
            ?: return null
        return AudioArtworkDecodeCoordinator.FolderCandidate(
            value = bitmap,
            sourcePath = file.absolutePath
        )
    }

    private fun decodeDiskThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        val file = diskThumbnailFile(key, targetWidth, targetHeight) ?: return null
        if (!file.isFile || file.length() <= 0) return null

        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val bitmap = decodeImageFile(file.absolutePath, targetWidth, targetHeight, aspectPolicy)
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
        val hardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        if (!HardwareArtworkPipelinePolicy.allowDiskThumbnailWrite(hardwareBitmap)) {
            // Never turn the reconstructable disk thumbnail cache into a GPU readback path.
            // HARDWARE wrappers stay resident and are regenerated from their encoded source on a
            // later cold miss; software results keep the existing low-priority JPEG writer.
            return
        }
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
        val hardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        if (!HardwareArtworkPipelinePolicy.allowDiskThumbnailWrite(hardwareBitmap)) return
        val file = diskThumbnailFile(key, targetWidth, targetHeight) ?: return
        if (file.isFile && file.length() > 0) return

        try {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
            if (file.length() <= 0) {
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
        trace("DISK_THUMB_PRUNE files=$remaining bytes=$totalBytes")
    }


    private fun normalizeDecodedBitmap(
        decoded: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        if (decoded.isRecycled) return null
        val hardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            decoded.config == Bitmap.Config.HARDWARE
        if (HardwareArtworkPipelinePolicy.keepDecoderGeometry(hardwareBitmap)) {
            // Keep the decoder-owned low/high wrapper and let the artwork view transform it.
            // Raw's View lane uses BitmapShader center-crop and Compose surfaces use ContentScale,
            // so forcing an exact provider size here only causes a GPU->CPU readback followed by
            // a software rescale. Keep the sampled decoder geometry for both Crop and KeepAspect.
            return decoded
        }
        val target = resolveArtworkScaledSize(
            sourceWidth = decoded.width,
            sourceHeight = decoded.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            policy = aspectPolicy,
        )
        if (decoded.width == target.width && decoded.height == target.height) return decoded

        val scaled = scaleBitmapForPolicy(decoded, targetWidth, targetHeight, aspectPolicy)
        if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
        return scaled
    }

    private fun scaleBitmapForPolicy(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        return if (aspectPolicy == ArtworkAspectPolicy.KeepAspect) {
            scaleBitmapFitCenter(source, targetWidth, targetHeight)
        } else {
            scaleBitmapCenterCrop(source, targetWidth, targetHeight)
        }
    }

    /** Scale a cache-owned source without ever returning the cache-owned instance to a caller. */
    private fun scaleBitmapDetachedFromSource(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy,
    ): Bitmap? {
        val scaled = scaleBitmapForPolicy(source, targetWidth, targetHeight, aspectPolicy)
        if (scaled !== source) return scaled
        return try {
            source.copy(softwareScaleTargetConfig(source), false)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "copy artwork source out of memory", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "copy artwork source failed", e)
            null
        }
    }

    private fun softwareScaleTargetConfig(source: Bitmap): Bitmap.Config =
        if (source.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    private fun scaleBitmapFitCenter(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        if (source.isRecycled || targetWidth <= 0 || targetHeight <= 0) return null
        val target = resolveArtworkScaledSize(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            policy = ArtworkAspectPolicy.KeepAspect,
        )
        if (source.width == target.width && source.height == target.height) return source

        val src = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else source
        val temporarySoftwareCopy = src !== source
        return try {
            val targetConfig = softwareScaleTargetConfig(src)
            val result = BitmapPool.obtain(target.width, target.height, targetConfig)
                ?: Bitmap.createBitmap(target.width, target.height, targetConfig)
            val canvas = threadLocalCanvas.get()!!
            canvas.setBitmap(result)
            val matrix = threadLocalMatrix.get()!!
            matrix.reset()
            matrix.setScale(target.width / src.width.toFloat(), target.height / src.height.toFloat())
            canvas.drawBitmap(src, matrix, threadLocalPaint.get()!!)
            result.setHasAlpha(src.hasAlpha())
            result
        } catch (e: Exception) {
            Log.e(TAG, "scaleBitmapFitCenter failed", e)
            null
        } finally {
            if (temporarySoftwareCopy && !src.isRecycled) src.recycle()
        }
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
        val temporarySoftwareCopy = src !== source
        var result: Bitmap? = null

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

            val targetConfig = softwareScaleTargetConfig(src)
            result = BitmapPool.obtain(
                width = targetWidth,
                height = targetHeight,
                config = targetConfig
            ) ?: Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                targetConfig
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
        } catch (e: OutOfMemoryError) {
            result?.let { if (!it.isRecycled) it.recycle() }
            Log.e(TAG, "scaleBitmapCenterCrop out of memory", e)
            null
        } catch (e: Exception) {
            result?.let { if (!it.isRecycled) it.recycle() }
            Log.e(TAG, "scaleBitmapCenterCrop failed", e)
            null
        } finally {
            if (temporarySoftwareCopy && !src.isRecycled) {
                src.recycle()
            }
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

    private fun calculateSourceInSampleSize(
        origWidth: Int,
        origHeight: Int,
        maxSide: Int
    ): Int {
        var sample = 1
        while (
            maxOf(origWidth / sample, origHeight / sample) > maxSide &&
            sample <= (1 shl 29)
        ) {
            sample = sample shl 1
        }
        return sample.coerceAtLeast(1)
    }

    private const val ARTWORK_SOURCE_LOW_RES_MAX_SIDE = 512
    private const val ARTWORK_SOURCE_SMALL_MAX_SIDE = 192
    private const val ARTWORK_SOURCE_HI_RES_MAX_SIDE = AlbumArtTiers.FULL_RES_SIDE

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

    private fun trace(@Suppress("UNUSED_PARAMETER") message: String) = Unit

    private fun stableArtworkCacheSourceKey(key: String): String {
        if (key.isBlank()) return key
        val normalized = key
            .removePrefix("audio://")
            .removePrefix("file://")
            .trim()
        if (normalized.isBlank()) return key
        if (normalized.startsWith("content://", ignoreCase = true)) return normalized

        // AudioFile.coverKey is already path|length|lastModified. Holder/cache lookup must remain
        // a pure string operation: do not split/parse the key and never call
        // File.exists/canonicalPath/length/lastModified from a holder bind. The source worker keeps
        // the slower canonical/invalidation path for the rare cases that actually need filesystem
        // metadata.
        return normalized
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
        if (ArtworkSourceIndex.remove(providerKey)) removed++
        trace("NO_ART_CLEAR_PROVIDER_ALIAS provider=${providerKey.tailForTrace()} source=${failureSourceKey.tailForTrace()} removed=$removed")
        return removed
    }

    /**
     * baseline implementation decoder-source lookup: before opening a concrete source path, ask the provider
     * source-string map whether another identity already owns a usable P.lowRes/P.hiRes wrapper.
     * A hit binds this request identity to that same record; no second cache owner is created.
     */
    private fun providerSourceCacheHit(
        storageKey: String,
        sourceString: String,
        targetWidth: Int,
        targetHeight: Int,
        aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    ): Bitmap? {
        if (storageKey.isBlank() || sourceString.isBlank()) return null
        if (threadLocalBypassProviderBitmapReuse.get() == true) return null
        val bitmap = memoryCache.getForSourceStringAndBind(
            sourceKey = artworkAspectCacheSourceKey(storageKey, aspectPolicy),
            sourceString = artworkAspectSourceString(sourceString, aspectPolicy),
            minimumSide = maxOf(targetWidth, targetHeight).coerceAtLeast(1),
        ) ?: return null
        trace(
            "SOURCE_STRING_HIT size=${targetWidth}x${targetHeight} provider=${storageKey.tailForTrace()} source=${sourceString.tailForTrace()}"
        )
        return bitmap
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
                val waiters = waitingRequests[cacheKey] ?: return@forEach
                // Attached ArtworkImageNode-equivalent requests remain provider-owned across a source
                // record invalidation. The queued/running owner will observe its stale token and
                // move these same callback owners to the fresh record. Non-artwork background/legacy
                // observers may be discarded and resubmitted by their own surface lifecycle.
                val discard = waiters.filter { it.wrapperCallback == null }
                discard.forEach { waiters.remove(it) }
                removed += discard.size
                if (waiters.isEmpty()) waitingRequests.remove(cacheKey, waiters)
            }
        }
        synchronized(inFlightRegistryLock) {
            inFlightKeys.toList().forEach { cacheKey ->
                if (prefixes.any { cacheKey.startsWith(it) }) {
                    if (inFlightKeys.remove(cacheKey)) removed++
                    inFlightPriorities.remove(cacheKey)
                    inFlightTokens.remove(cacheKey)
                    inFlightOwnerRequests.remove(cacheKey)
                    promotedInFlightKeys.remove(cacheKey)
                }
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

                    // Claim before any expensive probe. A stale/removed UI request no longer owns
                    // source work; later visible holders may submit or join a current provider flight.
                    if (!request.tryStartSourceWork()) {
                        trace("DECODE_SKIP_UNCLAIMED seq=${request.traceSeq} cancelled=${request.isCancelled} key=${request.key.tailForTrace()}")
                        return
                    }
                    trace("DECODE_START seq=${request.traceSeq} size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")
                    if (PlayerSwitchTrace.isActive()) {
                        PlayerSwitchTrace.mark(
                            "provider_decode_start",
                            "seq=${request.traceSeq} surface=${request.surface} priority=${request.priority} size=${request.targetWidth}x${request.targetHeight} key=${Integer.toHexString(request.decodeKey.hashCode())}",
                        )
                    }

                    if (request.isCancelled && !hasWaitingRequests(flightKey)) {
                        trace("DECODE_ABORT_CANCELLED seq=${request.traceSeq} cacheKey=${cacheKey.tailForTrace()}")
                        waitingRequests.remove(flightKey)
                        clearInFlightFor(request, flightKey)
                        if (request.surface == ArtworkSurface.Indexer) {
                            dispatchArtworkIndexerCallbacks(cacheKey, null)
                        }
                        return
                    }

                    if (!isArtworkAcceptTokenCurrent(request)) {
                        trace("DECODE_ABORT_STALE seq=${request.traceSeq} cacheKey=${cacheKey.tailForTrace()}")
                        if (request.wrapperCallback != null || hasWaitingRequests(flightKey)) {
                            requeueStaleProviderFlight(request, flightKey, reason = "before_decode")
                        } else {
                            waitingRequests.remove(flightKey)
                            clearInFlightFor(request, flightKey)
                            if (request.surface == ArtworkSurface.Indexer) {
                                dispatchArtworkIndexerCallbacks(cacheKey, null)
                            }
                        }
                        return
                    }

                    // Keep the source-record lookup ahead of the no-art sentinel. A low/high
                    // wrapper may have been published by another holder while this request was
                    // waiting in the serial lane.
                    val exactCached = memoryCache.get(cacheKey)
                        ?.takeIf { it.isValidArtworkBitmap() }
                    val cached = exactCached ?: memoryCache.getAnyForSource(
                        request.cacheSourceKey,
                        minimumSide = maxOf(request.targetWidth, request.targetHeight),
                        allowHighFallback = request.surface != ArtworkSurface.List,
                    )?.let {
                        reusableSourceBitmap(
                            it,
                            request.targetWidth,
                            request.targetHeight,
                            request.aspectPolicy,
                        )
                    }
                    if (cached != null && !cached.isRecycled) {
                        val tier = if (exactCached === cached) "exact" else "source"
                        if (needsPlaybackWrapperNormalization(
                                request.surface,
                                cached,
                                request.targetWidth,
                                request.targetHeight,
                                request.aspectPolicy,
                            )) {
                            val resizeStartedNs = System.nanoTime()
                            val resized = scaleBitmapForPolicy(
                                cached,
                                request.targetWidth,
                                request.targetHeight,
                                request.aspectPolicy,
                            )
                            trace("PLAYBACK_CACHE_RESIZE seq=${request.traceSeq} tier=$tier from=${cached.width}x${cached.height} to=${resized?.width}x${resized?.height} key=${request.key.tailForTrace()}")
                            if (resized != null && !resized.isRecycled) {
                                if (TransitionPerfTrace.isActive()) {
                                    TransitionPerfTrace.recordDuration(
                                        TransitionPerfStage.BITMAP_WORKER_DECODE,
                                        System.nanoTime() - resizeStartedNs,
                                    )
                                }
                                deliverResult(request, resized)
                                return
                            }
                        } else {
                            trace("DECODE_SKIP_CACHE_READY seq=${request.traceSeq} tier=$tier bitmap=${cached.width}x${cached.height} key=${request.key.tailForTrace()}")
                            if (PlayerSwitchTrace.isActive()) {
                                PlayerSwitchTrace.mark(
                                    "provider_worker_cache_hit",
                                    "seq=${request.traceSeq} tier=$tier bitmap=${cached.width}x${cached.height}",
                                )
                            }
                            deliverResult(request, cached)
                            return
                        }
                    }

                    val failureSourceKey = stableArtworkCacheSourceKey(request.decodeKey)
                    // A list holder is a transient observer. Its request must still probe the
                    // source record even when an older playback/indexer request remembered a
                    // no-art result for the same file. Keep that decision on the
                    // source wrapper; applying it here made every reused list holder inherit a
                    // stale sentinel and was the direct cause of covers disappearing in bulk.
                    if (request.surface.rememberNullAsNoArt &&
                        hasRecentFailure(failureSourceKey, "${failureSourceKey}_${request.bucket}")) {
                        trace("DECODE_SKIP_NO_ART_SENTINEL seq=${request.traceSeq} provider=${request.key.tailForTrace()} decode=${request.decodeKey.tailForTrace()}")
                        // deliverResult owns draining all observers, including list waiters that
                        // joined this source flight. Dropping them here strands their requests.
                        request.terminalNoArt = true
                        deliverResult(request, null)
                        return
                    }

                    val t0 = android.os.SystemClock.uptimeMillis()
                    val decodeStartedNs = System.nanoTime()
                    val transitionTraceActiveAtDecodeStart = TransitionPerfTrace.isActive()
                    activeWorkerDecodeCount.incrementAndGet()
                    activeWorkerDecodeStartedAtMs = t0
                    activeWorkerDecodeKeyTag = Integer.toHexString(request.key.hashCode())
                    var bitmap: Bitmap? = null
                    var providerSourceString = ""

                    try {
                        request.transitionTo(BitmapRequest.State.DECODING_FILES)

                        val decodeResult = decodeBitmap(
                            storageKey = request.key,
                            decodeKey = request.decodeKey,
                            targetWidth = request.targetWidth,
                            targetHeight = request.targetHeight,
                            surface = request.surface,
                            sourceDecodeAllowed = request.sourceDecodeAllowed,
                            externalArtworkPath = request.externalArtworkPath,
                            aspectPolicy = request.aspectPolicy,
                            onIndexerReady = null
                        )
                        bitmap = decodeResult.bitmap
                        providerSourceString = decodeResult.providerSourceString
                        request.terminalNoArt = decodeResult.terminalNoArt

                        if ((bitmap == null || bitmap.isRecycled) && request.surface.rememberNullAsNoArt && decodeResult.terminalNoArt) {
                            rememberFailure(failureSourceKey, "${failureSourceKey}_${request.bucket}")
                            clearProviderAliasForNoArt(request.key, failureSourceKey)
                        }

                        val elapsed = android.os.SystemClock.uptimeMillis() - t0

                        if (!isArtworkAcceptTokenCurrent(request)) {
                            trace("DECODE_DROP_STALE_TOKEN seq=${request.traceSeq} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} cacheKey=${cacheKey.tailForTrace()}")
                            bitmap?.let { BitmapPool.recycle(it) }
                            if (request.wrapperCallback != null || hasWaitingRequests(flightKey)) {
                                requeueStaleProviderFlight(request, flightKey, reason = "after_decode")
                            } else {
                                waitingRequests.remove(flightKey)
                                clearInFlightFor(request, flightKey)
                            }
                            return
                        }
                        if (request.isCancelled && !hasWaitingRequests(flightKey)) {
                            trace("DECODE_DROP_CANCELLED seq=${request.traceSeq} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} cacheKey=${cacheKey.tailForTrace()}")
                            bitmap?.let { BitmapPool.recycle(it) }
                            waitingRequests.remove(flightKey)
                            clearInFlightFor(request, flightKey)
                            return
                        }
                        if (decodeResult.coalesceSourceTiers) {
                            coalesceIndexerSiblingTiers(request, bitmap)
                        }

                        PowerTraceLogger.bitmapDecodeDone(
                            priority = request.priority.name,
                            size = "${request.targetWidth}x${request.targetHeight}",
                            result = bitmap != null,
                            elapsedMs = elapsed,
                            key = request.key
                        )
                        trace("DECODE_DONE seq=${request.traceSeq} result=${bitmap != null} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")
                        if (PlayerSwitchTrace.isActive()) {
                            PlayerSwitchTrace.mark(
                                "provider_decode_done",
                                "seq=${request.traceSeq} result=${bitmap != null} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} source=$providerSourceString",
                            )
                        }
                    } catch (e: Exception) {
                        // An exception is a transient provider failure, not proof that the file
                        // has no artwork. Install the not-found sentinel only after
                        // the complete source probe; poisoning the source here makes one codec,
                        // permission, or file race hide the cover for the whole TTL.
                        request.terminalNoArt = false
                        trace("DECODE_ERROR seq=${request.traceSeq} error=${e.javaClass.simpleName}:${e.message} provider=${request.key.tailForTrace()} decode=${request.decodeKey.tailForTrace()}")
                        if (PlayerSwitchTrace.isActive()) {
                            PlayerSwitchTrace.mark(
                                "provider_decode_error",
                                "seq=${request.traceSeq} type=${e.javaClass.simpleName} message=${e.message.orEmpty()}",
                            )
                        }
                        Log.e(TAG, "worker decode failed: ${request.decodeKey.takeLast(80)}", e)
                    } finally {
                        activeWorkerDecodeCount.decrementAndGet()
                        activeWorkerDecodeStartedAtMs = 0L
                        activeWorkerDecodeKeyTag = "-"
                        if (transitionTraceActiveAtDecodeStart) {
                            TransitionPerfTrace.recordDuration(
                                TransitionPerfStage.BITMAP_WORKER_DECODE,
                                System.nanoTime() - decodeStartedNs,
                            )
                        }
                    }

                    val bitmapMissing = bitmap == null || bitmap.isRecycled
                    if (bitmapMissing &&
                        !request.terminalNoArt &&
                        request.sourceDecodeAllowed &&
                        hasWaitingRequests(flightKey)
                    ) {
                        val retryNumber = request.tryPrepareSourceRetry(MAX_TRANSIENT_SOURCE_RETRIES)
                        if (retryNumber != null) {
                            val delayIndex = (retryNumber - 1)
                                .coerceIn(0, TRANSIENT_SOURCE_RETRY_DELAYS_MS.lastIndex)
                            val delayMs = TRANSIENT_SOURCE_RETRY_DELAYS_MS[delayIndex]
                            trace(
                                "DECODE_RETRY_TRANSIENT seq=${request.traceSeq} retry=$retryNumber " +
                                    "delay=${delayMs}ms priority=${request.priority} key=${request.key.tailForTrace()}"
                            )
                            enqueueRequest(
                                request,
                                workerHandlers,
                                forceFront = request.priority.level <= BitmapRequest.Priority.LOADING_LIST.level,
                                delayMs = delayMs,
                            )
                            return
                        }
                    }

                    bitmap?.takeIf { !it.isRecycled }?.let { preparedBitmap ->
                        // Prepare fresh artwork before handing it to the UI. Large playback bitmaps
                        // must not pay their first texture/draw preparation on a transition frame.
                        runCatching { preparedBitmap.prepareToDraw() }
                    }
                    trace("DELIVER seq=${request.traceSeq} result=${bitmap != null} waiters=${waitingRequests[flightKey]?.size ?: 0} key=${request.key.tailForTrace()}")
                    if (PlayerSwitchTrace.isActive()) {
                        PlayerSwitchTrace.mark(
                            "provider_deliver",
                            "seq=${request.traceSeq} result=${bitmap != null} waiters=${waitingRequests[flightKey]?.size ?: 0}",
                        )
                    }
                    deliverResult(request, bitmap, providerSourceString)
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
            if (request.inFlightOwner) clearInFlightKey(flightKey, request)
            else promotedInFlightKeys.remove(flightKey)
        }
    }
}
