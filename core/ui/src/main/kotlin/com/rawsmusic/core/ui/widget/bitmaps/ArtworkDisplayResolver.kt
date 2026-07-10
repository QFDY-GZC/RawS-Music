package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Centralized project-style artwork display policy.
 *
 * Decode/cache ownership lives in [BitmapProvider], [SizeSlotCache] and [AlbumArtCache]. This
 * resolver owns the presentation rules: which already-prepared handle should be displayed first,
 * whether a previous image should be retained, and whether a newly accepted handle deserves a fade.
 * Keeping this logic in one place prevents list rows, mini player and playback pages from each
 * inventing their own fallback/animation order.
 */
object ArtworkDisplayResolver {
    const val QUALITY_NONE = 0
    const val QUALITY_ANY = 1
    const val QUALITY_LOW = 2
    const val QUALITY_HIGH = 3
    const val QUALITY_FULL = 4

    enum class Reason {
        Exact,
        Low,
        High,
        Any,
        Empty
    }

    enum class AnimationKind {
        None,
        FadeIn,
        Crossfade
    }

    data class Candidate(
        val handle: ArtworkHandle,
        val reason: Reason,
        val quality: Int
    ) {
        val bitmap = handle.bitmap
        val isValid: Boolean get() = handle.isValid
        fun release() = handle.release()
    }

    data class AnimationDecision(
        val kind: AnimationKind,
        val durationMillis: Int
    ) {
        val shouldAnimate: Boolean get() = kind != AnimationKind.None && durationMillis > 0

        companion object {
            val None = AnimationDecision(AnimationKind.None, 0)
        }
    }

    fun acquireBest(
        key: String,
        targetWidth: Int,
        targetHeight: Int,
        surface: ArtworkSurface,
        allowHiRes: Boolean = true
    ): Candidate? {
        if (key.isBlank()) return null
        val exact = if (allowHiRes) {
            BitmapProvider.acquire(key, targetWidth, targetHeight, surface)
        } else {
            BitmapProvider.acquireThumbnail(key, targetWidth, targetHeight, surface)
        }
        if (exact?.isValid == true) {
            return Candidate(
                handle = exact,
                reason = if (allowHiRes) Reason.Exact else Reason.Low,
                quality = qualityForBitmap(exact, targetWidth, targetHeight, allowFull = allowHiRes)
            )
        }
        exact?.release()

        val any = BitmapProvider.acquireAny(key, surface)
        if (any?.isValid == true) {
            return Candidate(
                handle = any,
                reason = Reason.Any,
                quality = qualityForBitmap(any, targetWidth, targetHeight, allowFull = allowHiRes)
            )
        }
        any?.release()
        return null
    }

    fun acquirePlayback(
        key: String,
        lowResSize: Int,
        hiResSize: Int,
        surface: ArtworkSurface = ArtworkSurface.Playback
    ): Candidate? {
        if (key.isBlank()) return null

        val hi = BitmapProvider.acquire(
            key = key,
            targetWidth = hiResSize,
            targetHeight = hiResSize,
            surface = surface
        )
        if (hi?.isValid == true) {
            return Candidate(hi, Reason.High, qualityForSide(hi, lowResSize, hiResSize))
        }
        hi?.release()

        val low = BitmapProvider.acquireThumbnail(
            key = key,
            targetWidth = lowResSize,
            targetHeight = lowResSize,
            surface = surface
        )
        if (low?.isValid == true) {
            return Candidate(low, Reason.Low, qualityForSide(low, lowResSize, hiResSize))
        }
        low?.release()

        val any = BitmapProvider.acquireAny(key, surface)
        if (any?.isValid == true) {
            return Candidate(any, Reason.Any, qualityForSide(any, lowResSize, hiResSize))
        }
        any?.release()
        return null
    }

    fun shouldRetainPrevious(
        surface: ArtworkSurface,
        requestedByCaller: Boolean
    ): Boolean {
        if (requestedByCaller) return true
        return when (surface) {
            ArtworkSurface.Playback,
            ArtworkSurface.Fullscreen,
            ArtworkSurface.MiniPlayer -> true
            ArtworkSurface.List,
            ArtworkSurface.Prefetch,
            ArtworkSurface.Notification,
            ArtworkSurface.Widget,
            ArtworkSurface.Indexer -> false
        }
    }

    fun bindAnimation(
        surface: ArtworkSurface,
        hadPreviousBitmap: Boolean,
        sameSource: Boolean,
        fadeOnBitmapChange: Boolean,
        requestedMillis: Int
    ): AnimationDecision {
        if (!fadeOnBitmapChange || requestedMillis <= 0) return AnimationDecision.None
        val duration = RawArtworkPolicy.bindFadeMillis(
            surface = surface,
            hadPreviousBitmap = hadPreviousBitmap,
            sameSource = sameSource,
            requestedMillis = requestedMillis
        )
        return if (duration <= 0) AnimationDecision.None else AnimationDecision(AnimationKind.FadeIn, duration)
    }

    fun crossfadeAnimation(
        surface: ArtworkSurface,
        sameSource: Boolean,
        qualityUpgrade: Boolean,
        requestedMillis: Int
    ): AnimationDecision {
        if (requestedMillis <= 0) return AnimationDecision.None
        val duration = RawArtworkPolicy.crossfadeMillis(
            surface = surface,
            sameSource = sameSource,
            qualityUpgrade = qualityUpgrade,
            requestedMillis = requestedMillis
        )
        return if (duration <= 0) AnimationDecision.None else AnimationDecision(AnimationKind.Crossfade, duration)
    }

    fun listFadeDurationMillis(
        modeLabel: String,
        firstBitmap: Boolean,
        cacheHitAlreadyVisible: Boolean
    ): Int {
        return RawArtworkPolicy.listFadeMillis(
            modeLabel = modeLabel,
            firstBitmap = firstBitmap,
            cacheHitAlreadyVisible = cacheHitAlreadyVisible
        )
    }

    fun listRevealStaggerMillis(modeLabel: String): Int {
        return when (modeLabel) {
            "GRID_4" -> 18
            "GRID_3" -> 24
            "GRID_2" -> 30
            else -> 0
        }
    }

    private fun qualityForBitmap(
        handle: ArtworkHandle,
        targetWidth: Int,
        targetHeight: Int,
        allowFull: Boolean
    ): Int {
        val bitmap = handle.bitmap
        if (!handle.isValid || bitmap.isRecycled) return QUALITY_NONE
        val side = maxOf(bitmap.width, bitmap.height)
        val targetSide = maxOf(targetWidth, targetHeight).coerceAtLeast(1)
        return when {
            allowFull && side >= (AlbumArtTiers.FULL_RES_SIDE * 0.70f).toInt() -> QUALITY_FULL
            side >= (targetSide * 0.70f).toInt() -> QUALITY_HIGH
            side >= (AlbumArtTiers.LOW_RES_MIN_SIDE * 0.70f).toInt() -> QUALITY_LOW
            else -> QUALITY_ANY
        }
    }

    private fun qualityForSide(
        handle: ArtworkHandle,
        lowResSize: Int,
        hiResSize: Int
    ): Int {
        val bitmap = handle.bitmap
        if (!handle.isValid || bitmap.isRecycled) return QUALITY_NONE
        val side = maxOf(bitmap.width, bitmap.height)
        return when {
            side >= (AlbumArtTiers.FULL_RES_SIDE * 0.70f).toInt() -> QUALITY_FULL
            side >= (hiResSize * 0.70f).toInt() -> QUALITY_HIGH
            side >= (lowResSize * 0.70f).toInt() -> QUALITY_LOW
            else -> QUALITY_ANY
        }
    }
}
