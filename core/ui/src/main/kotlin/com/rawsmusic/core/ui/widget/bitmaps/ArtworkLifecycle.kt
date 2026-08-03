package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-style owner/surface model for album artwork.
 *
 * Priority decides queue order; surface decides lifecycle semantics.  The important rule is that
 * hot UI surfaces (lists, mini player and prefetch) may bind only already-prepared low-res art.
 * Expensive source probing (MMR/FFmpeg/native tag extraction) belongs to Playback/Fullscreen or to
 * the background Indexer lane, so a cache miss in a visible row never opens and parses the audio
 * file on the UI-driven path.
 */
enum class ArtworkSurface(
    val allowsSourceDecode: Boolean,
    val rememberNullAsNoArt: Boolean,
    val scheduleIndexerOnMiss: Boolean,
    val allowDiskThumbnailWrite: Boolean
) {
    List(
        // Correctness first for visible rows.  RawSMusic does not yet have the
        // stable media-library artwork type/id resolver; if a visible list row only schedules the background
        // indexer, loose files can oscillate between a stale placeholder and null.  Let admitted
        // visible rows decode their own file-version source directly; heavy stages remain serialized
        // by BitmapProvider.sourceExtractionGate, so 3/4-column scrolling does not spawn parallel
        // MMR/FFmpeg/TagLib probes.
        allowsSourceDecode = true,
        rememberNullAsNoArt = true,
        scheduleIndexerOnMiss = false,
        allowDiskThumbnailWrite = true
    ),
    MiniPlayer(
        // Only one mini-player artwork is active. Decode its current song directly instead of
        // waiting for a list/indexer request that may finish after the UI has already detached.
        allowsSourceDecode = true,
        rememberNullAsNoArt = false,
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
