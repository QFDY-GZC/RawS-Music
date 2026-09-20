package com.rawsmusic.core.ui.widget.flow

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal object NativeStaticBackground {
    private const val WIDTH = 96
    private const val HEIGHT = 160
    private const val SCENE_CACHE_KB = 4 * 1024

    private val sceneCache = object : LruCache<String, Bitmap>(SCENE_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val sceneLoaderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND
                    )
                }
                runnable.run()
            },
            "RawStaticSceneLoader",
        )
    }
    private val sceneLoaderDispatcher = sceneLoaderExecutor.asCoroutineDispatcher()
    private val sceneLoaderGate = Mutex()

    private val available = runCatching {
        System.loadLibrary("rawscoreservice")
        true
    }.onFailure { error ->
        Log.e(TAG, "native_library_failed", error)
    }.getOrDefault(false)

    fun create(
        colors: IntArray,
        saturation: Float,
        brightness: Float
    ): Bitmap? {
        if (!available || colors.isEmpty()) return null
        return runCatching {
            val pixels = render(
                colors = colors,
                width = WIDTH,
                height = HEIGHT,
                saturation = saturation,
                brightness = brightness,
                textureStrength = 0f,
                blurRadius = 2
            )
            Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    fun sceneCacheKey(
        colors: IntArray,
        saturation: Float,
        brightness: Float,
    ): String = buildString {
        append(colors.contentHashCode())
        append('|').append(saturation.toBits())
        append('|').append(brightness.toBits())
    }

    fun peekScene(cacheKey: String): Bitmap? =
        sceneCache.get(cacheKey)?.takeUnless { it.isRecycled }

    suspend fun prepareScene(
        cacheKey: String,
        colors: IntArray,
        saturation: Float,
        brightness: Float,
    ): Bitmap? {
        peekScene(cacheKey)?.let { return it }
        return sceneLoaderGate.withLock {
            peekScene(cacheKey)?.let { return@withLock it }
            val created = withContext(sceneLoaderDispatcher) {
                create(
                    colors = colors,
                    saturation = saturation,
                    brightness = brightness,
                )
            }
            if (created != null && !created.isRecycled) {
                sceneCache.put(cacheKey, created)
            }
            created
        }
    }

    /**
     * Builds one prepared player artwork endpoint.
     *
     * Reference's static-artwork renderer blurs a small artwork source through repeated ping-pong shader passes and
     * only then presents that texture at the player surface. RawSMusic now returns that same small
     * square prepared artwork texture from JNI and lets the persistent Compose owner stretch it with
     * bilinear filtering. Current/secondary lifetime is owned by RawStaticArtworkLiveSlots.
     */
    fun createPlayer(
        artwork: Bitmap,
        saturation: Float,
        brightness: Float,
        gradient: Float,
        gradientColor: Int,
        blur: Float,
        detail: Float
    ): Bitmap? {
        if (!available || artwork.isRecycled) {
            Log.w(TAG, "player_skip available=$available recycled=${artwork.isRecycled}")
            return null
        }
        return runCatching {
            val detailStep = detail.toInt().coerceIn(0, 10)
            val sampleSize = if (detailStep < 10) 1 shl detailStep else Int.MAX_VALUE
            val longestEdge = maxOf(artwork.width, artwork.height)
            // StaticArtworkLoader reduces the artwork source before CPU pixel access. Never copy a 1024 HARDWARE
            // foreground wrapper to a 4MB software bitmap just to immediately shrink it to 32/64.
            val detailScaled = if (longestEdge > sampleSize) {
                Bitmap.createScaledBitmap(artwork, sampleSize, sampleSize, false)
            } else {
                artwork
            }
            val scaled = if (gradient > 0f && detailScaled.height < 32) {
                Bitmap.createScaledBitmap(detailScaled, 32, 32, false)
            } else {
                detailScaled
            }
            // Only the final artwork-sized source needs software pixels for JNI. Foreground artwork keeps
            // its original high-resolution/hardware wrapper untouched.
            val readable = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                scaled
            }
            val artworkPixels = IntArray(readable.width * readable.height)
            readable.getPixels(
                artworkPixels,
                0,
                readable.width,
                0,
                0,
                readable.width,
                readable.height
            )
            val aaSize = readable.width
            val output = renderPlayer(
                artwork = artworkPixels,
                artworkWidth = readable.width,
                artworkHeight = readable.height,
                width = aaSize,
                height = aaSize,
                saturation = saturation,
                brightness = brightness,
                gradient = gradient,
                gradientColor = gradientColor,
                blurLevel = blur
            )
            if (readable !== scaled && readable !== artwork) readable.recycle()
            if (scaled !== detailScaled && scaled !== artwork) scaled.recycle()
            if (detailScaled !== artwork) detailScaled.recycle()
            if (output.size != aaSize * aaSize) {
                error("player_render_size_mismatch expected=${aaSize * aaSize} actual=${output.size}")
            }
            Bitmap.createBitmap(output, aaSize, aaSize, Bitmap.Config.ARGB_8888)
        }.onFailure { error ->
            Log.e(TAG, "player_render_failed", error)
        }.getOrNull()
    }

    private external fun render(
        colors: IntArray,
        width: Int,
        height: Int,
        saturation: Float,
        brightness: Float,
        textureStrength: Float,
        blurRadius: Int
    ): IntArray

    private external fun renderPlayer(
        artwork: IntArray,
        artworkWidth: Int,
        artworkHeight: Int,
        width: Int,
        height: Int,
        saturation: Float,
        brightness: Float,
        gradient: Float,
        gradientColor: Int,
        blurLevel: Float
    ): IntArray

    private const val TAG = "RawStaticBackground"
}
