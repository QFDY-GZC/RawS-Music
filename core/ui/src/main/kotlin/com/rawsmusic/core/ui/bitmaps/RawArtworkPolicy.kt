package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Single source of truth for the project-style album-art lifecycle.
 *
 * The artwork view keeps artwork presentation simple: bind an already prepared wrapper,
 * animate only when a genuinely new wrapper is accepted, and let the provider/handler own decode
 * ordering. RawSMusic keeps the public BitmapProvider API for compatibility, but every display and
 * decode decision should flow through this policy so old call sites cannot invent a second order.
 */
object RawArtworkPolicy {
    /** The artwork view uses a 200ms transition for normal artwork swaps. */
    const val VIEW_FADE_MS = 200

    /** The design switches to a longer 300ms transition for large/hero artwork surfaces. */
    const val HERO_FADE_MS = 300

    /** Same-source low -> high upgrades should feel immediate, not like a full song change. */
    const val QUALITY_UPGRADE_MS = 160

    /** First visible low-res art in list/grid: short reveal, no shimmer/spinner. */
    const val LIST_FIRST_REVEAL_MS = 200

    /** Existing list row bitmap replacement during scroll should be instant. */
    const val LIST_SWAP_MS = 0

    /** Mini/notification first art reveal: quiet and short. */
    const val SMALL_SURFACE_FADE_MS = 110

    /** Stable viewport delay before source/indexer work is allowed. */
    const val VIEWPORT_SETTLE_MS = 120L

    /** Idle wait before optional offscreen prewarm. Kept disabled by default in ComposePowerList. */
    const val OFFSCREEN_PREWARM_IDLE_MS = 220L

    enum class DecodeStage {
        ImageFile,
        ContentImage,
        DiskThumbnail,
        FolderCover,
        RegionHandle,
        NativeSource,
        Ffmpeg,
        MediaMetadataRetriever
    }

    fun displayOrder(surface: ArtworkSurface): List<ArtworkTier> {
        return when (surface) {
            ArtworkSurface.Playback,
            ArtworkSurface.Fullscreen,
            ArtworkSurface.Widget -> listOf(ArtworkTier.Full, ArtworkTier.High, ArtworkTier.Low, ArtworkTier.Any)
            ArtworkSurface.List,
            ArtworkSurface.MiniPlayer,
            ArtworkSurface.Notification,
            ArtworkSurface.Prefetch,
            ArtworkSurface.Indexer -> listOf(ArtworkTier.Low, ArtworkTier.High, ArtworkTier.Full, ArtworkTier.Any)
        }
    }

    fun listFadeMillis(
        modeLabel: String,
        firstBitmap: Boolean,
        cacheHitAlreadyVisible: Boolean
    ): Int {
        if (cacheHitAlreadyVisible) return 0
        if (modeLabel == "TRANSITION") return 0
        if (!firstBitmap) return if (modeLabel == "TRANSITION") 0 else QUALITY_UPGRADE_MS
        return LIST_FIRST_REVEAL_MS
    }

    fun bindFadeMillis(
        surface: ArtworkSurface,
        hadPreviousBitmap: Boolean,
        sameSource: Boolean,
        requestedMillis: Int
    ): Int {
        if (requestedMillis <= 0) return 0
        return when (surface) {
            ArtworkSurface.List,
            ArtworkSurface.Prefetch,
            ArtworkSurface.Indexer -> 0
            ArtworkSurface.MiniPlayer,
            ArtworkSurface.Notification -> if (hadPreviousBitmap) 0 else SMALL_SURFACE_FADE_MS
            ArtworkSurface.Widget -> if (hadPreviousBitmap || sameSource) 0 else VIEW_FADE_MS.coerceAtMost(requestedMillis.coerceAtLeast(SMALL_SURFACE_FADE_MS))
            ArtworkSurface.Playback -> if (hadPreviousBitmap) 0 else VIEW_FADE_MS
            ArtworkSurface.Fullscreen -> if (hadPreviousBitmap) 0 else HERO_FADE_MS
        }
    }

    fun crossfadeMillis(
        surface: ArtworkSurface,
        sameSource: Boolean,
        qualityUpgrade: Boolean,
        requestedMillis: Int
    ): Int {
        if (requestedMillis <= 0) return 0
        return when (surface) {
            ArtworkSurface.List,
            ArtworkSurface.Prefetch,
            ArtworkSurface.Indexer -> 0
            ArtworkSurface.MiniPlayer,
            ArtworkSurface.Notification -> if (sameSource) 0 else SMALL_SURFACE_FADE_MS
            ArtworkSurface.Playback,
            ArtworkSurface.Widget -> when {
                sameSource && qualityUpgrade -> QUALITY_UPGRADE_MS
                sameSource -> 0
                else -> VIEW_FADE_MS
            }
            ArtworkSurface.Fullscreen -> when {
                sameSource && qualityUpgrade -> QUALITY_UPGRADE_MS
                sameSource -> 0
                else -> HERO_FADE_MS
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun decodeOrderForAudio(embeddedPreferred: Boolean): List<DecodeStage> {
        // Embedded artwork is authoritative for a versioned audio file. Keep the historical
        // parameter for source/binary call-site compatibility, but no longer let it move a folder
        // image ahead of an embedded source probe.
        return ArtworkSourceSelectionPolicy.audioDecodeOrder
    }

    fun shouldCallbackUpdateCache(surface: ArtworkSurface): Boolean {
        return surface != ArtworkSurface.Prefetch
    }

    fun shouldStartSourceWork(surface: ArtworkSurface): Boolean {
        return surface.allowsSourceDecode
    }
}
