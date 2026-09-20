package com.rawsmusic.core.ui.widget.bitmaps

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin coroutine/runtime facade over the single RawSMusic bitmap provider.
 *
 * This exists only for non-holder consumers such as Palette/background/widget extraction. It owns
 * no image cache, decoder, request table or lifecycle of its own. All decoded pixels and source
 * records are owned by [BitmapProvider], matching the reference player AA provider architecture.
 */
object ArtworkBitmapRuntime {
    suspend fun executePixelAnalysisBitmap(
        context: Context,
        key: String,
        targetSide: Int = HardwareArtworkPipelinePolicy.PIXEL_ANALYSIS_SIDE,
    ): Bitmap? {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        if (key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            BitmapProvider.executePixelAnalysis(key, targetSide)
        }
    }

    suspend fun executeBitmap(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback,
    ): Bitmap? {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        if (key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            BitmapProvider.execute(key, width, height, surface)
        }
    }

    suspend fun executeBitmap(
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback,
    ): Bitmap? {
        if (key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            BitmapProvider.execute(key, width, height, surface)
        }
    }

    fun prefetch(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        if (key.isBlank()) return
        BitmapProvider.load(
            key = key,
            targetWidth = width,
            targetHeight = height,
            priority = when (surface) {
                ArtworkSurface.List -> BitmapRequest.Priority.LOADING_LIST
                ArtworkSurface.Prefetch -> BitmapRequest.Priority.LOADING_PREFETCH
                ArtworkSurface.Notification -> BitmapRequest.Priority.LOADING_NOTIFICATION
                else -> BitmapRequest.Priority.LOADING_WIDGET
            },
            surface = surface,
        )
    }

    fun peekBitmap(
        context: Context,
        key: String,
        width: Int,
        height: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback,
    ): Bitmap? {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        return if (surface == ArtworkSurface.List) {
            BitmapProvider.peekThumbnail(key, width, height)
        } else {
            BitmapProvider.peek(key, width, height)
        }
    }

    fun isKnownNoArtwork(key: String): Boolean = BitmapProvider.isKnownNoArtwork(key)

    fun invalidate(context: Context, key: String? = null) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        invalidate(key)
    }

    fun invalidate(key: String? = null) {
        if (key.isNullOrBlank()) {
            BitmapProvider.notifyLibraryArtworkChanged("runtime_invalidate_all")
        } else {
            BitmapProvider.invalidateArtwork(key)
        }
    }

    fun trimMemory(
        context: Context,
        level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        @Suppress("UNUSED_VARIABLE")
        val requestedLevel = level
        BitmapProvider.trimMemory()
    }

    fun trimMemory(level: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
        @Suppress("UNUSED_VARIABLE")
        val requestedLevel = level
        BitmapProvider.trimMemory()
    }

    fun clearMemoryForArtworkPolicyChange() = BitmapProvider.trimMemory()

    fun clearArtworkCaches(context: Context) = BitmapProvider.clearArtworkCaches(context)
}
