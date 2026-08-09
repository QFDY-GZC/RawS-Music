package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-style owner/surface model for album artwork.
 *
 * Priority decides queue order; surface decides lifecycle semantics.  Visible holders only bind
 * through this provider; source probing and decoding stay on its worker lane, never on Compose's
 * UI thread.  A list holder may therefore request a source record directly, while the provider
 * owns coalescing, caching, and the rule that an already-started decode survives holder movement.
 */
enum class ArtworkSurface(
    val allowsSourceDecode: Boolean,
    val rememberNullAsNoArt: Boolean,
    val scheduleIndexerOnMiss: Boolean,
    val allowDiskThumbnailWrite: Boolean
) {
    List(
        // Poweramp's list holder asks the central provider for the source record. The provider
        // performs source probing on its background lane, so this does not decode on Compose's
        // thread and avoids the second List -> Indexer request that could lose the holder callback.
        allowsSourceDecode = true,
        // A list holder is a cold, transient observer. A single null while the media provider,
        // native extractor, or storage mount is settling must not become a provider-wide
        // no-art decision. Poweramp only commits its not-found wrapper after the source record
        // has completed its probe; list rows retry through the same source record instead.
        rememberNullAsNoArt = false,
        scheduleIndexerOnMiss = false,
        // A visible holder must not start JPEG compression while a fling is decoding rows. The
        // provider still reads an existing thumbnail; persistent warming is owned by Playback or
        // Indexer, just like Poweramp's source wrapper cache is independent of a row holder.
        allowDiskThumbnailWrite = false
    ),
    MiniPlayer(
        // Only one mini-player artwork is active. Decode its current song directly instead of
        // waiting for a list/indexer request that may finish after the UI has already detached.
        allowsSourceDecode = true,
        // The provider's terminal marker is source-level knowledge. Retain the previous frame
        // during a transient miss, but let a confirmed no-art result clear it so a song without
        // artwork can never inherit the previous song's cover.
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = false
    ),
    Notification(
        allowsSourceDecode = false,
        rememberNullAsNoArt = false,
        scheduleIndexerOnMiss = true,
        allowDiskThumbnailWrite = false
    ),
    Prefetch(
        allowsSourceDecode = false,
        rememberNullAsNoArt = false,
        scheduleIndexerOnMiss = true,
        allowDiskThumbnailWrite = false
    ),
    Playback(
        allowsSourceDecode = true,
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = true
    ),
    Fullscreen(
        allowsSourceDecode = true,
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = true
    ),
    Widget(
        allowsSourceDecode = true,
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = true
    ),
    Indexer(
        allowsSourceDecode = true,
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = true
    );

    companion object {
        fun fromPriority(priority: BitmapRequest.Priority): ArtworkSurface {
            return when (priority) {
                BitmapRequest.Priority.LOADING_LIST -> List
                BitmapRequest.Priority.LOADING_LIST_DELAYED -> List
                BitmapRequest.Priority.LOADING_PREFETCH -> Prefetch
                BitmapRequest.Priority.LOADING_NOTIFICATION -> Notification
                BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH -> Playback
                BitmapRequest.Priority.LOADING_WIDGET -> Playback
                BitmapRequest.Priority.LOADED -> Widget
                BitmapRequest.Priority.IDLE -> Prefetch
            }
        }
    }
}

/** Artwork quality tier owned by the album-art provider. */
enum class ArtworkTier {
    Low,
    High,
    Full,
    Any
}

/**
 * Ref-counted bind handle for Compose/View surfaces.
 *
 * This is the safe intermediate step toward the artwork wrapper lifecycle: UI surfaces should hold
 * a handle while drawing and release it on detach/replacement. Release removes only the surface
 * reference; it never recycles the Bitmap directly because exact-size caches, transitions or Palette
 * extraction may still own the same object.
 */
class ArtworkHandle internal constructor(
    val sourceKey: String,
    val tier: ArtworkTier,
    val surface: ArtworkSurface,
    val bitmap: Bitmap,
    private val onRelease: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isValid: Boolean
        get() = !closed.get() && !bitmap.isRecycled

    fun release() {
        if (closed.compareAndSet(false, true)) {
            onRelease()
        }
    }

    override fun close() = release()
}

internal data class ArtworkDecodeResult(
    val bitmap: Bitmap?,
    val terminalNoArt: Boolean,
    val coalesceSourceTiers: Boolean = false
) {
    companion object {
        val LightweightMiss = ArtworkDecodeResult(bitmap = null, terminalNoArt = false)
        val NoArt = ArtworkDecodeResult(bitmap = null, terminalNoArt = true)
    }
}
