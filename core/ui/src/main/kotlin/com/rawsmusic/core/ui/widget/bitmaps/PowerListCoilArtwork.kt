package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.taglib.TagLibBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.util.LruCache

private object CoilArtworkRevision {
    private val global = AtomicLong(0L)
    private val sources = ConcurrentHashMap<String, AtomicLong>()

    fun value(sourceKey: String): String =
        "${global.get()}-${sources[sourceKey]?.get() ?: 0L}"

    fun invalidate(sourceKey: String?) {
        if (sourceKey.isNullOrBlank()) {
            global.incrementAndGet()
            sources.clear()
        } else {
            sources.getOrPut(sourceKey) { AtomicLong(0L) }.incrementAndGet()
        }
    }
}

/**
 * The only runtime artwork model used by Compose surfaces.
 *
 * Coil owns request cancellation, lifecycle, memory/disk caching and size reuse. The fetcher only
 * resolves RawSMusic's audio-file identity into an image. The legacy [BitmapProvider] remains in
 * the source tree for rollback and diagnostics, but is not part of this request path.
 */
data class CoilArtworkModel(
    val coverKey: String,
    val targetWidth: Int,
    val targetHeight: Int,
    val defaultArtworkEnabled: Boolean,
    val surface: ArtworkSurface = ArtworkSurface.List
) {
    val id: FileArtworkId = FileArtworkId.fromCoverKey(coverKey)
    val width: Int = targetWidth.coerceAtLeast(1)
    val height: Int = targetHeight.coerceAtLeast(1)
    val cacheKey: String = buildString {
        append("raws-coil-art-v1:")
        append(id.value)
        append(':')
        append(width)
        append('x')
        append(height)
        append(":default=")
        append(defaultArtworkEnabled)
        append(":source=")
        // Cache identity must stay side-effect free: this value is built during Compose binding.
        append(CoilArtworkRevision.value(id.value))
    }
}

/** Compatibility model retained so PowerList call sites do not need a layout rewrite. */
data class PowerListCoilArtworkModel(
    val coverKey: String,
    val targetSide: Int,
    val modeLabel: String,
    val defaultArtworkEnabled: Boolean
) {
    val id: FileArtworkId = FileArtworkId.fromCoverKey(coverKey)
    val side: Int = targetSide.coerceAtLeast(1)
    val cacheKey: String = CoilArtworkModel(
        coverKey = coverKey,
        targetWidth = side,
        targetHeight = side,
        defaultArtworkEnabled = defaultArtworkEnabled,
        surface = ArtworkSurface.List
    ).cacheKey

    fun asCoilModel(): CoilArtworkModel = CoilArtworkModel(
        coverKey = coverKey,
        targetWidth = side,
        targetHeight = side,
        defaultArtworkEnabled = defaultArtworkEnabled,
        surface = ArtworkSurface.List
    )
}

object CoilArtworkRuntime {
    private const val SOURCE_CACHE_BYTES = 256L * 1024L * 1024L
    private const val DECODE_PARALLELISM = 2
    private const val NO_ART_TTL_MS = 10L * 60L * 1000L
    private const val LIST_THUMB_MEMORY_BYTES = 24L * 1024L * 1024L
    private const val TAG = "CoilArtwork"
    private const val SLOW_DECODE_MS = 180L

    private val decodeSemaphore = Semaphore(DECODE_PARALLELISM)
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val noArtworkUntil = ConcurrentHashMap<String, Long>()
    private val defaultArtworkCache = ConcurrentHashMap<Int, Bitmap>()
    // Custom Fetcher results are not encoded by Coil's disk cache. Keep bounded list thumbnails
    // so recycled viewport holders do not decode the source audio again.
    private val listThumbnailMemory = object : LruCache<String, Bitmap>(LIST_THUMB_MEMORY_BYTES.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(value.byteCount)
    }
    @Volatile private var applicationContext: Context? = null

    fun newImageLoader(context: Context): ImageLoader {
        val app = context.applicationContext
        applicationContext = app
        return ImageLoader.Builder(app)
            .components {
                add(CoilArtworkFetcher.Factory())
                add(PowerListCoilArtworkFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizePercent(0.12)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "coil_artwork"))
                    .maxSizeBytes(SOURCE_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    fun imageLoader(context: Context): ImageLoader = context.applicationContext.imageLoader

    fun request(
        context: Context,
        model: CoilArtworkModel,
        crossfadeMillis: Int,
        placeholderMemoryCacheKey: String? = null,
        allowHardware: Boolean = true
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(model)
            .size(model.width, model.height)
            .memoryCacheKey(model.cacheKey)
            .diskCacheKey(model.cacheKey)
            .apply {
                if (!placeholderMemoryCacheKey.isNullOrBlank()) {
                    placeholderMemoryCacheKey(placeholderMemoryCacheKey)
                }
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .allowHardware(allowHardware)
            .crossfade(crossfadeMillis.coerceAtLeast(0))
            .build()
    }

    suspend fun executeBitmap(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback
    ): Bitmap? {
        if (key.isBlank()) return null
        val model = CoilArtworkModel(
            coverKey = key,
            targetWidth = width,
            targetHeight = height,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
            surface = surface
        )
        val request = request(context, model, crossfadeMillis = 0, allowHardware = false)
        val result = imageLoader(context).execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    suspend fun executeBitmap(
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback
    ): Bitmap? {
        val context = applicationContext ?: return null
        return executeBitmap(context, key, width, height, surface)
    }

    fun prefetch(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback
    ) {
        if (key.isBlank()) return
        val model = CoilArtworkModel(
            coverKey = key,
            targetWidth = width,
            targetHeight = height,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
            surface = surface
        )
        imageLoader(context).enqueue(request(context, model, crossfadeMillis = 0))
    }

    fun isKnownNoArtwork(key: String): Boolean {
        val until = noArtworkUntil[key] ?: return false
        if (until > android.os.SystemClock.elapsedRealtime()) return true
        noArtworkUntil.remove(key, until)
        return false
    }

    fun peekBitmap(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback
    ): Bitmap? {
        if (key.isBlank()) return null
        val model = CoilArtworkModel(
            coverKey = key,
            targetWidth = width,
            targetHeight = height,
            defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
            surface = surface
        )
        if (surface == ArtworkSurface.List) {
            val listKey = listThumbnailKey(model.id.value, model.width, model.height)
            synchronized(listThumbnailMemory) {
                listThumbnailMemory.get(listKey)?.takeIf { !it.isRecycled }?.let { return it }
            }
        }
        val cache = imageLoader(context).memoryCache ?: return null
        cache[MemoryCache.Key(model.cacheKey)]?.bitmap?.let { return it }
        val prefix = "raws-coil-art-v1:${model.id.value}:"
        return cache.keys.asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { cache[it]?.bitmap }
            .filterNot(Bitmap::isRecycled)
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    fun invalidate(context: Context, key: String? = null) {
        val sourceKey = key?.takeIf(String::isNotBlank)?.let { FileArtworkId.fromCoverKey(it).value }
        CoilArtworkRevision.invalidate(sourceKey)
        if (key.isNullOrBlank()) {
            noArtworkUntil.clear()
        } else {
            noArtworkUntil.remove(sourceKey)
        }
        // Artwork can be edited in place while retaining its path. The revision changes the disk
        // key without touching storage from the Compose/main thread.
        imageLoader(context).memoryCache?.clear()
    }

    fun invalidate(key: String? = null) {
        applicationContext?.let { invalidate(it, key) }
    }

    fun trimMemory(
        context: Context,
        level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    ) {
        // Coil's level-aware trim keeps the same pressure semantics as Reference's bitmap
        // manager. Clearing the whole cache on every notification forces visible holders to
        // decode again when the app returns to the foreground.
        imageLoader(context).memoryCache?.trimMemory(level)
        // Keep the newest list thumbnails as immediate placeholders. The cache is already
        // bounded independently from Coil, so a fair-memory callback should shrink it instead of
        // making every recycled PowerList holder blank and forcing a disk/source re-request.
        synchronized(listThumbnailMemory) {
            val targetBytes = when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 0L
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> LIST_THUMB_MEMORY_BYTES / 4L
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> LIST_THUMB_MEMORY_BYTES / 4L
                else -> LIST_THUMB_MEMORY_BYTES / 2L
            }
            listThumbnailMemory.trimToSize(targetBytes.toInt())
        }
    }

    fun trimMemory(level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
        applicationContext?.let { trimMemory(it, level) }
    }

    internal suspend fun decode(context: Context, model: CoilArtworkModel): Bitmap? {
        if (model.id.isBlank) return defaultArtwork(context, model)
        val sourceKey = model.id.value
        if (isKnownNoArtwork(sourceKey)) return defaultArtwork(context, model)

        val startedAt = SystemClock.elapsedRealtime()
        val bitmap = decodeSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                val lock = sourceLocks.getOrPut(sourceKey) { Mutex() }
                lock.withLock {
                    decodeSource(context, sourceKey, model.width, model.height, model.surface)
                }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed >= SLOW_DECODE_MS) {
            Log.w(
                TAG,
                "slow_decode elapsed_ms=$elapsed surface=${model.surface} size=${model.width}x${model.height} key=${sourceKey.takeLast(72)} result=${bitmap != null}"
            )
        }
        if (bitmap != null && !bitmap.isRecycled) {
            noArtworkUntil.remove(sourceKey)
            return bitmap
        }
        noArtworkUntil[sourceKey] = android.os.SystemClock.elapsedRealtime() + NO_ART_TTL_MS
        return defaultArtwork(context, model)
    }

    private fun defaultArtwork(context: Context, model: CoilArtworkModel): Bitmap? {
        if (!model.defaultArtworkEnabled) return null
        val side = defaultArtworkBucket(maxOf(model.width, model.height))
        defaultArtworkCache[side]?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = decodeDefaultAlbumArtwork(context.resources, side) ?: return null
        val existing = defaultArtworkCache.putIfAbsent(side, decoded)
        if (existing != null && existing !== decoded && !decoded.isRecycled) decoded.recycle()
        return existing?.takeIf { !it.isRecycled } ?: decoded
    }

    private fun decodeSource(
        context: Context,
        sourceKey: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface
    ): Bitmap? {
        if (surface == ArtworkSurface.List) {
            loadListThumbnail(context, sourceKey, width, height)?.let { return it }
        }

        val bitmap = decodeSourceUncached(context, sourceKey, width, height, surface)
        if (bitmap != null && !bitmap.isRecycled && surface == ArtworkSurface.List) {
            saveListThumbnail(context, sourceKey, width, height, bitmap)
        }
        return bitmap
    }

    private fun decodeSourceUncached(
        context: Context,
        sourceKey: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface
    ): Bitmap? {
        val normalized = sourceKey.trim()
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return decodeContentUri(context, Uri.parse(normalized), width, height)
        }
        val path = when {
            normalized.startsWith("audio://") -> normalized.removePrefix("audio://").substringBefore('|')
            normalized.startsWith("file://") -> normalized.removePrefix("file://").substringBefore('|')
            else -> normalized.substringBefore('|')
        }
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        if (isImageFile(path)) return decodeFile(path, width, height)

        EmbeddedArtworkRegion.find(path)?.let { region ->
            decodeStreamPair(region::openStream, width, height)?.let { return it }
        }

        val sourceDir = File(context.cacheDir, "coil_artwork_sources").apply { mkdirs() }
        val version = "$path|${file.length()}|${file.lastModified()}"
        val baseName = sha256(version)
        val nativeFile = File(sourceDir, "$baseName.native")
        if ((nativeFile.isFile && nativeFile.length() > 0L) ||
            TagLibBridge.extractEmbeddedArtworkToFile(path, nativeFile.absolutePath)
        ) {
            decodeFile(nativeFile.absolutePath, width, height)?.let { return it }
        } else {
            nativeFile.delete()
        }

        FolderArtworkLocator.find(path)?.let { folderArtwork ->
            decodeFile(folderArtwork.absolutePath, width, height)?.let { return it }
        }

        // A transient list holder must not launch two heavyweight native fallbacks after its
        // cheap region/TagLib/folder probes miss. Playback surfaces can still perform the full
        // recovery chain and warm Coil's shared cache for later list visits.
        if (surface == ArtworkSurface.List) return null

        val ffmpegFile = File(sourceDir, "$baseName.jpg")
        if ((ffmpegFile.isFile && ffmpegFile.length() > 0L) ||
            FFmpegBridge.extractCover(path, ffmpegFile.absolutePath) == 0
        ) {
            decodeFile(ffmpegFile.absolutePath, width, height)?.let { return it }
        } else {
            ffmpegFile.delete()
        }
        return null
    }

    private fun loadListThumbnail(
        context: Context,
        sourceKey: String,
        width: Int,
        height: Int
    ): Bitmap? {
        val cacheKey = listThumbnailKey(sourceKey, width, height)
        synchronized(listThumbnailMemory) {
            listThumbnailMemory.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        val file = listThumbnailFile(context, cacheKey)
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = decodeFile(file.absolutePath, width, height)
        if (bitmap == null || bitmap.isRecycled) {
            file.delete()
            return null
        }
        synchronized(listThumbnailMemory) { listThumbnailMemory.put(cacheKey, bitmap) }
        return bitmap
    }

    private fun saveListThumbnail(
        context: Context,
        sourceKey: String,
        width: Int,
        height: Int,
        bitmap: Bitmap
    ) {
        val cacheKey = listThumbnailKey(sourceKey, width, height)
        synchronized(listThumbnailMemory) { listThumbnailMemory.put(cacheKey, bitmap) }

        val file = listThumbnailFile(context, cacheKey)
        if (file.isFile && file.length() > 0L) return
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return

        // Write out of place so a cancelled/restarted fetch can never leave a partial thumbnail.
        val temp = File(parent, "${file.name}.tmp-${Thread.currentThread().id}")
        runCatching {
            FileOutputStream(temp).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 95, output)) {
                    temp.delete()
                    return
                }
                output.fd.sync()
            }
            if (!temp.renameTo(file)) {
                if (!file.exists()) temp.renameTo(file) else temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    private fun listThumbnailFile(context: Context, cacheKey: String): File =
        File(
            File(context.applicationContext.cacheDir, "coil_artwork/list_thumbnails"),
            "$cacheKey.webp"
        )

    private fun listThumbnailKey(sourceKey: String, width: Int, height: Int): String {
        val path = sourceKey.trim()
            .removePrefix("audio://")
            .removePrefix("file://")
            .substringBefore('|')
        val file = File(path)
        val version = if (file.isFile) {
            "${file.length()}|${file.lastModified()}"
        } else {
            "uri"
        }
        return sha256(
            "list-thumbnail-v2|$sourceKey|$version|${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}|" +
                CoilArtworkRevision.value(sourceKey)
        )
    }

    private fun decodeContentUri(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        return decodeStreamPair(
            openStream = { context.contentResolver.openInputStream(uri) },
            width = width,
            height = height
        )
    }

    private fun decodeFile(path: String, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = decodeOptions(bounds.outWidth, bounds.outHeight, width, height)
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeStreamPair(
        openStream: () -> InputStream?,
        width: Int,
        height: Int
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        openStream()?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                decodeOptions(bounds.outWidth, bounds.outHeight, width, height)
            )
        }
    }.getOrNull()

    private fun decodeOptions(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): BitmapFactory.Options {
        var sample = 1
        val requestedWidth = width.coerceAtLeast(1)
        val requestedHeight = height.coerceAtLeast(1)
        while (sourceWidth / (sample * 2) >= requestedWidth &&
            sourceHeight / (sample * 2) >= requestedHeight
        ) {
            sample *= 2
        }
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
    }

    private fun isImageFile(path: String): Boolean {
        return when (File(path).extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif" -> true
            else -> false
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun defaultArtworkBucket(requestedSide: Int): Int = when {
        requestedSide <= 128 -> 128
        requestedSide <= 192 -> 192
        requestedSide <= 256 -> 256
        requestedSide <= 384 -> 384
        requestedSide <= 512 -> 512
        requestedSide <= 768 -> 768
        else -> 1024
    }
}

object PowerListCoilArtwork {
    fun imageLoader(context: Context): ImageLoader = CoilArtworkRuntime.imageLoader(context)
    fun peekBitmap(context: Context, key: String, width: Int, height: Int): Bitmap? =
        CoilArtworkRuntime.peekBitmap(
            context = context,
            key = key,
            width = width,
            height = height,
            surface = ArtworkSurface.List
        )
    fun trimMemory(
        context: Context,
        level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    ) = CoilArtworkRuntime.trimMemory(context, level)

    fun trimMemory(level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) =
        CoilArtworkRuntime.trimMemory(level)
}

private class CoilArtworkFetcher(
    private val data: CoilArtworkModel,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bitmap = CoilArtworkRuntime.decode(options.context, data) ?: return null
        if (bitmap.isRecycled) return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = maxOf(bitmap.width, bitmap.height) > maxOf(data.width, data.height),
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<CoilArtworkModel> {
        override fun create(data: CoilArtworkModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return CoilArtworkFetcher(data, options)
        }
    }
}

private class PowerListCoilArtworkFetcher(
    private val data: PowerListCoilArtworkModel,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val model = data.asCoilModel()
        val bitmap = CoilArtworkRuntime.decode(options.context, model) ?: return null
        if (bitmap.isRecycled) return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = maxOf(bitmap.width, bitmap.height) > data.side,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<PowerListCoilArtworkModel> {
        override fun create(
            data: PowerListCoilArtworkModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = PowerListCoilArtworkFetcher(data, options)
    }
}
