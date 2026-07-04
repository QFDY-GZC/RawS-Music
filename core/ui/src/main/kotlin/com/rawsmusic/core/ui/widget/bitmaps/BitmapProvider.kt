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
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    private const val MSG_LOAD = 1
    private const val MSG_CANCEL = 2

    private const val WORKER_COUNT = 4
    private const val FAILED_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val DISK_THUMB_MAX_SIZE = 768

    private val initLock = Any()

    @Volatile
    private var appContext: Context? = null

    private val memoryCache = SizeSlotCache()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var workerHandlers: Array<WorkerHandler> = emptyArray()

    private var workerIndex = 0

    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPriorities = ConcurrentHashMap<String, BitmapRequest.Priority>()
    private val promotedInFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val waitingRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<BitmapRequest>>()
    private val failedCache = ConcurrentHashMap<String, Long>()
    private val traceSeq = AtomicLong(0L)

    val useHardwareBitmap: Boolean by lazy {
        Build.VERSION.SDK_INT >= 28 && !isMeizuDevice() && !isMtkChip()
    }

    fun init(context: Context) {
        synchronized(initLock) {
            appContext = context.applicationContext

            if (workerHandlers.isNotEmpty()) {
                Log.d(TAG, "Already initialized")
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

            Log.d(
                TAG,
                "Initialized workers=$WORKER_COUNT, useHardwareBitmap=$useHardwareBitmap, cacheMax=${memoryCache.maxSizeBytes}"
            )
        }
    }

    fun load(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            callback = callback,
            allowHiRes = true
        )
    }

    fun loadThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
        callback: ((Bitmap?) -> Unit)? = null
    ): BitmapRequest {
        return loadInternal(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            priority = priority,
            callback = callback,
            allowHiRes = false
        )
    }

    private fun loadInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        priority: BitmapRequest.Priority,
        callback: ((Bitmap?) -> Unit)?,
        allowHiRes: Boolean
    ): BitmapRequest {
        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val actualWidth = if (allowHiRes) getHiResSize(baseWidth) else baseWidth
        val actualHeight = if (allowHiRes) getHiResSize(baseHeight) else baseHeight

        val request = BitmapRequest(
            key = key,
            targetWidth = actualWidth,
            targetHeight = actualHeight,
            priority = priority,
            callback = callback
        )
        val seq = traceSeq.incrementAndGet()
        request.traceSeq = seq

        if (key.isBlank()) {
            trace("SKIP_BLANK seq=$seq size=${actualWidth}x${actualHeight} priority=$priority")
            postNull(request)
            return request
        }

        val cached = memoryCache.get(request.cacheKey)
        if (cached != null && !cached.isRecycled) {
            trace("CACHE_HIT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority key=${key.tailForTrace()}")
            request.transitionTo(BitmapRequest.State.AVAILABLE)
            mainHandler.post {
                if (!request.isCancelled) {
                    callback?.invoke(cached)
                }
            }
            return request
        }

        val failTime = failedCache[request.cacheKey]
        if (failTime != null) {
            if (System.currentTimeMillis() - failTime < FAILED_CACHE_TTL_MS) {
                Log.d(TAG, "FAIL_CACHE key=${key.takeLast(40)}")
                postNull(request)
                return request
            } else {
                failedCache.remove(request.cacheKey)
            }
        }

        val handlers = workerHandlers
        if (handlers.isEmpty()) {
            Log.e(TAG, "BitmapProvider is not initialized")
            // 不写 failedCache，否则 init 后同尺寸封面 5 分钟内继续失败
            postNull(request)
            return request
        }

        request.transitionTo(BitmapRequest.State.CHECKING_MEMORY)
        addWaitingRequest(request)

        val isOwner = inFlightKeys.add(request.cacheKey)
        if (!isOwner) {
            val ownerPriority = inFlightPriorities[request.cacheKey]
            if (shouldPromoteInFlight(ownerPriority, priority) && promotedInFlightKeys.add(request.cacheKey)) {
                request.promotedInFlight = true
                trace("PROMOTE_IN_FLIGHT seq=$seq ownerPriority=$ownerPriority newPriority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
                enqueueRequest(request, handlers, forceFront = true)
                return request
            }
            trace("JOIN_IN_FLIGHT seq=$seq size=${actualWidth}x${actualHeight} priority=$priority cacheKey=${request.cacheKey.tailForTrace()} key=${key.tailForTrace()}")
            Log.d(TAG, "IN_FLIGHT_JOIN key=${key.takeLast(40)} cacheKey=${request.cacheKey.takeLast(60)}")
            return request
        }
        request.inFlightOwner = true
        inFlightPriorities[request.cacheKey] = priority

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
        val selectedWorkerIndex = idx % handlers.size
        val handler = handlers[selectedWorkerIndex]
        val msg = handler.obtainMessage(MSG_LOAD, request)

        Log.d(
            TAG,
            "ENQUEUE key=${request.key.takeLast(40)} size=${request.targetWidth}x${request.targetHeight} worker=$selectedWorkerIndex"
        )
        val front = forceFront || request.priority.level <= BitmapRequest.Priority.LOADING_LIST.level
        trace("ENQUEUE seq=${request.traceSeq} worker=$selectedWorkerIndex front=$front size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} key=${request.key.tailForTrace()}")

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

        val actualWidth = getHiResSize(targetWidth.coerceAtLeast(1))
        val actualHeight = getHiResSize(targetHeight.coerceAtLeast(1))

        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${key}_${bucket}"

        memoryCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val bitmap = decodeBitmap(
            key = key,
            targetWidth = actualWidth,
            targetHeight = actualHeight
        )

        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(cacheKey, bitmap, bucket)
        }

        return bitmap
    }

    fun peek(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = true)
    }

    fun peekThumbnail(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return peekInternal(key, targetWidth, targetHeight, allowHiRes = false)
    }

    private fun peekInternal(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        allowHiRes: Boolean
    ): Bitmap? {
        if (key.isBlank()) return null

        val baseWidth = targetWidth.coerceAtLeast(1)
        val baseHeight = targetHeight.coerceAtLeast(1)
        val actualWidth = if (allowHiRes) getHiResSize(baseWidth) else baseWidth
        val actualHeight = if (allowHiRes) getHiResSize(baseHeight) else baseHeight

        val bucket = SizeSlotCache.computeBucket(actualWidth, actualHeight)
        val cacheKey = "${key}_${bucket}"

        return memoryCache.get(cacheKey)
    }

    fun peekAny(key: String): Bitmap? {
        if (key.isBlank()) return null
        return memoryCache.getAnyForSource(key)
    }

    fun cancel(request: BitmapRequest, keepDecoding: Boolean = false) {
        request.cancel(keepAlive = keepDecoding)
        removeWaitingRequest(request)

        if (!keepDecoding) {
            workerHandlers.forEach { handler ->
                handler.obtainMessage(MSG_CANCEL, request).sendToTarget()
            }
        }
    }

    fun clear() {
        memoryCache.clear()
        BitmapPool.clear()
        waitingRequests.clear()
        failedCache.clear()
        inFlightKeys.clear()
        inFlightPriorities.clear()
        promotedInFlightKeys.clear()
    }

    fun getPool(): BitmapPool = BitmapPool

    fun getMemoryCache(): SizeSlotCache = memoryCache

    private fun addWaitingRequest(request: BitmapRequest) {
        waitingRequests
            .getOrPut(request.cacheKey) { CopyOnWriteArrayList() }
            .add(request)
    }

    private fun removeWaitingRequest(request: BitmapRequest) {
        val list = waitingRequests[request.cacheKey] ?: return
        list.remove(request)

        if (list.isEmpty()) {
            waitingRequests.remove(request.cacheKey, list)
        }
    }

    private fun hasWaitingRequests(cacheKey: String): Boolean {
        return waitingRequests[cacheKey]?.any { !it.isCancelled } == true
    }

    private fun deliverResult(
        ownerRequest: BitmapRequest,
        bitmap: Bitmap?
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(
                key = ownerRequest.cacheKey,
                bitmap = bitmap,
                bucket = ownerRequest.bucket
            )
        }

        mainHandler.post {
            val requests = waitingRequests.remove(ownerRequest.cacheKey).orEmpty()
            trace("CALLBACK_DISPATCH ownerSeq=${ownerRequest.traceSeq} waiters=${requests.size} result=${bitmap != null} key=${ownerRequest.key.tailForTrace()}")

            for (request in requests) {
                if (request.isCancelled) continue

                if (bitmap != null && !bitmap.isRecycled) {
                    request.transitionTo(BitmapRequest.State.AVAILABLE)
                }

                request.callback?.invoke(bitmap)
            }
        }
    }

    private fun postNull(request: BitmapRequest) {
        mainHandler.post {
            if (!request.isCancelled) {
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
        val useHigher = try {
            AppPreferences.AlbumArt.useHigherRes
        } catch (_: Exception) {
            false
        }

        return if (useHigher) {
            (baseSize * 2).coerceAtMost(2400)
        } else {
            baseSize
        }
    }

    private fun decodeBitmap(
        key: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            decodeDiskThumbnail(key, targetWidth, targetHeight)?.let { return it }

            val decoded = when {
                key.startsWith("file://") -> {
                    val path = key.removePrefix("file://")
                    decodeFromAnyFilePath(path, targetWidth, targetHeight)
                }

                key.startsWith("content://") -> {
                    decodeFromUri(Uri.parse(key), targetWidth, targetHeight)
                }

                key.startsWith("audio://") -> {
                    val path = key.removePrefix("audio://").substringBefore("|")
                    decodeFromAnyFilePath(path, targetWidth, targetHeight)
                }

                else -> {
                    decodeFromAnyFilePath(key, targetWidth, targetHeight)
                }
            }
            if (decoded != null && !decoded.isRecycled) {
                saveDiskThumbnail(key, targetWidth, targetHeight, decoded)
            }
            decoded
        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmap failed: ${key.takeLast(80)}", e)
            null
        }
    }

    private fun decodeFromAnyFilePath(
        path: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            Log.d(TAG, "FILE_NOT_READABLE key=${path.takeLast(80)} exists=${file.exists()} canRead=${file.canRead()}")
            return null
        }

        if (isImageFile(path)) {
            decodeImageFile(path, targetWidth, targetHeight)?.let { return it }
        }

        if (isEmbeddedArtworkPreferredAudio(path)) {
            decodeCoverWithFfmpeg(path, targetWidth, targetHeight)?.let { return it }
            decodeEmbeddedWithMediaMetadataRetriever(path, targetWidth, targetHeight)?.let { return it }
            decodeFolderCover(path, targetWidth, targetHeight)?.let { return it }
            return null
        }

        decodeEmbeddedWithMediaMetadataRetriever(path, targetWidth, targetHeight)?.let { return it }
        decodeFolderCover(path, targetWidth, targetHeight)?.let { return it }
        decodeCoverWithFfmpeg(path, targetWidth, targetHeight)?.let { return it }

        return null
    }

    private fun decodeImageFile(
        filePath: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(filePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    origWidth = bounds.outWidth,
                    origHeight = bounds.outHeight,
                    reqWidth = targetWidth,
                    reqHeight = targetHeight
                )
                inPreferredConfig = getPreferredConfig()
                inJustDecodeBounds = false
                inMutable = inPreferredConfig != Bitmap.Config.HARDWARE
            }

            val decoded = BitmapFactory.decodeFile(filePath, options) ?: return null

            if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                scaleBitmapCenterCrop(decoded, targetWidth, targetHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeImageFile failed: $filePath", e)
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
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    origWidth = bounds.outWidth,
                    origHeight = bounds.outHeight,
                    reqWidth = targetWidth,
                    reqHeight = targetHeight
                )
                inPreferredConfig = getPreferredConfig()
                inJustDecodeBounds = false
                inMutable = inPreferredConfig != Bitmap.Config.HARDWARE
            }

            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

            if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                scaleBitmapCenterCrop(decoded, targetWidth, targetHeight)
            }
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

        if (isAlbumArtUri) {
            decodeContentThumbnailUri(uri, targetWidth, targetHeight)?.let {
                trace("ALBUMART_PROVIDER_THUMB uri=${uri.toString().tailForTrace()} size=${targetWidth}x${targetHeight}")
                return it
            }
            decodeFromAlbumArtUri(uri, targetWidth, targetHeight)?.let {
                trace("ALBUMART_SOURCE_DECODE uri=${uri.toString().tailForTrace()} size=${targetWidth}x${targetHeight}")
                return it
            }
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
            val bitmap = if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                scaleBitmapCenterCrop(decoded, targetWidth, targetHeight)
            }
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
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    origWidth = bounds.outWidth,
                    origHeight = bounds.outHeight,
                    reqWidth = targetWidth,
                    reqHeight = targetHeight
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inJustDecodeBounds = false
                inMutable = true
            }

            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                scaleBitmapCenterCrop(decoded, targetWidth, targetHeight)
            }
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
        val context = appContext ?: return null
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null

        val paths = ArrayList<String>()

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.ALBUM_ID} = ?",
                arrayOf(albumId.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext() && paths.size < 8) {
                    cursor.getString(0)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(paths::add)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query album source files failed: $uri", e)
        }

        val firstPath = paths.firstOrNull()

        decodeFolderCover(firstPath, targetWidth, targetHeight)?.let { return it }

        for (path in paths) {
            if (!isWavLike(path)) {
                decodeEmbeddedWithMediaMetadataRetriever(path, targetWidth, targetHeight)?.let { return it }
            }
        }

        for (path in paths.take(3)) {
            decodeCoverWithFfmpeg(path, targetWidth, targetHeight)?.let { return it }
        }

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

    private fun decodeFolderCover(
        audioPath: String?,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val dir = audioPath?.let { File(it).parentFile } ?: return null

        val candidates = listOf(
            "folder.jpg",
            "Folder.jpg",
            "cover.jpg",
            "Cover.jpg",
            "album.jpg",
            "Album.jpg",
            "front.jpg",
            "Front.jpg"
        )

        for (name in candidates) {
            val file = File(dir, name)
            if (file.exists() && file.canRead() && file.length() > 1024) {
                decodeImageFile(file.absolutePath, targetWidth, targetHeight)?.let {
                    return it
                }
            }
        }

        return null
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

    private fun saveDiskThumbnail(
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
                trace("DISK_THUMB_SAVE bytes=${file.length()} size=${targetWidth}x${targetHeight} key=${key.tailForTrace()}")
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
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${key}_${bucket}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "bitmap_thumbs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$digest.jpg")
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

            val canvas = Canvas(result)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }

            val paint = Paint(
                Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
            )

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

    private fun isEmbeddedArtworkPreferredAudio(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("dsf", "dff", "wav", "aiff", "aif", "ape")
    }

    private fun isMeizuDevice(): Boolean {
        return Build.MANUFACTURER.equals("meizu", ignoreCase = true) ||
                Build.FINGERPRINT.contains("Flyme", ignoreCase = true)
    }

    private fun isMtkChip(): Boolean {
        return Build.HARDWARE.startsWith("mt", ignoreCase = true)
    }

    private fun trace(message: String) {
        Log.d(TRACE_TAG, message)
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
                    trace("DECODE_START seq=${request.traceSeq} size=${request.targetWidth}x${request.targetHeight} priority=${request.priority} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")

                    if (request.isCancelled && !request.keepAliveOnCancel && !hasWaitingRequests(cacheKey)) {
                        trace("DECODE_ABORT_CANCELLED seq=${request.traceSeq} cacheKey=${cacheKey.tailForTrace()}")
                        waitingRequests.remove(cacheKey)
                        clearInFlightFor(request, cacheKey)
                        return
                    }

                    val cached = memoryCache.get(cacheKey)
                    if (cached != null && !cached.isRecycled) {
                        trace("DECODE_SKIP_CACHE_READY seq=${request.traceSeq} bitmap=${cached.width}x${cached.height} key=${request.key.tailForTrace()}")
                        clearInFlightFor(request, cacheKey)
                        deliverResult(request, cached)
                        return
                    }

                    val t0 = android.os.SystemClock.uptimeMillis()
                    var bitmap: Bitmap? = null

                    try {
                        request.transitionTo(BitmapRequest.State.DECODING_FILES)

                        bitmap = decodeBitmap(
                            key = request.key,
                            targetWidth = request.targetWidth,
                            targetHeight = request.targetHeight
                        )

                        if (bitmap == null || bitmap.isRecycled) {
                            failedCache[cacheKey] = System.currentTimeMillis()
                        }

                        val elapsed = android.os.SystemClock.uptimeMillis() - t0

                        Log.d(
                            TAG,
                            "DECODED key=${request.key.takeLast(40)} size=${request.targetWidth}x${request.targetHeight} result=${bitmap != null} ${elapsed}ms thread=${Thread.currentThread().name}"
                        )
                        trace("DECODE_DONE seq=${request.traceSeq} result=${bitmap != null} elapsed=${elapsed}ms bitmap=${bitmap?.width}x${bitmap?.height} thread=${Thread.currentThread().name} key=${request.key.tailForTrace()}")
                    } catch (e: Exception) {
                        failedCache[cacheKey] = System.currentTimeMillis()
                        trace("DECODE_ERROR seq=${request.traceSeq} error=${e.javaClass.simpleName}:${e.message} key=${request.key.tailForTrace()}")
                        Log.e(TAG, "worker decode failed: ${request.key.takeLast(80)}", e)
                    } finally {
                        clearInFlightFor(request, cacheKey)
                    }

                    trace("DELIVER seq=${request.traceSeq} result=${bitmap != null} waiters=${waitingRequests[cacheKey]?.size ?: 0} key=${request.key.tailForTrace()}")
                    deliverResult(request, bitmap)
                }

                MSG_CANCEL -> {
                    val request = msg.obj as? BitmapRequest ?: return
                    request.cancel()
                    removeWaitingRequest(request)
                }
            }
        }

        private fun clearInFlightFor(request: BitmapRequest, cacheKey: String) {
            if (request.inFlightOwner) {
                inFlightKeys.remove(cacheKey)
                inFlightPriorities.remove(cacheKey)
            }
            if (request.promotedInFlight) {
                promotedInFlightKeys.remove(cacheKey)
            }
        }
    }
}
