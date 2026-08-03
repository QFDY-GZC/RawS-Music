package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Album art resolution tiers used across the app.
 *
 * LowRes  – 384–512px: lists, notifications, mini covers.
 * HiRes   – 1024px: playback screen cover.
 * FullRes – 1440px: fullscreen / pinch-zoom cover.
 *
 * v5: extracted from BitmapProvider / AlbumArtCache so every caller uses
 * the same constants instead of ad-hoc magic numbers.
 */
object AlbumArtTiers {

    /** Smallest normal low-res tier; used for notification icon and normal thumbnails. */
    const val LOW_RES_MIN_SIDE = 384

    /** Small PowerList rows use a much smaller cover; keep this separate from playback tiers. */
    const val LIST_SMALL_MIN_SIDE = 96
    const val LIST_SMALL_MAX_SIDE = 192

    /** Upper bound for the low-res tier; lists and mini covers cap at this size. */
    const val LOW_RES_NORMAL_CAP = 512

    /** Hi-res tier for the playback screen. */
    const val HI_RES_SIDE = 1024

    /** Full-res tier for fullscreen / pinch-zoom covers. */
    const val FULL_RES_SIDE = 1440

    /** A resolved decode target. */
    data class Target(
        val width: Int,
        val height: Int
    ) {
        val maxSide: Int get() = maxOf(width, height)
    }

    /**
     * Returns the hi-res target side for a given request, honouring the
     * [highResEnabled] preference.  When hi-res is disabled the original
     * requested size is returned unchanged.
     */
    fun hiResTarget(
        requestedWidth: Int,
        requestedHeight: Int,
        highResEnabled: Boolean
    ): Target {
        if (!highResEnabled) {
            return Target(requestedWidth, requestedHeight)
        }
        val maxSide = maxOf(requestedWidth, requestedHeight)
        val target = when {
            maxSide <= LOW_RES_NORMAL_CAP -> LOW_RES_NORMAL_CAP
            maxSide <= HI_RES_SIDE -> HI_RES_SIDE
            else -> FULL_RES_SIDE
        }
        return Target(target, target)
    }

    /**
     * Resolves the final decode target for an album art request.
     *
     * – Notification / list priorities are clamped to the low-res tier.
     * – Widget / playback priorities can escalate to HiRes or FullRes when
     *   [allowHiRes] is true and the user has enabled high-res covers.
     * – Large grid requests (≥768) keep their larger size instead of being
     *   forced back down to 512.
     */
    fun resolve(
        requestedWidth: Int,
        requestedHeight: Int,
        allowHiRes: Boolean,
        priority: BitmapRequest.Priority,
        highResEnabled: Boolean
    ): Target {
        val maxSide = maxOf(requestedWidth, requestedHeight)

        // Notification and normal list priorities use the low-res tier.  LOADING_NOTIFICATION_HIGH
        // is only a queue priority: when a playback/fullscreen caller passes allowHiRes=true it must
        // still resolve to the hi-res tier, matching the separate low/high artwork wrapper paths.
        // The only exception is PowerList's 32dp small-row cover: decoding it at 384/512px creates a
        // large amount of wasted native heap with no visible benefit, so keep that tiny list tier tiny.
        if (priority == BitmapRequest.Priority.LOADING_NOTIFICATION ||
            priority == BitmapRequest.Priority.LOADING_LIST ||
            (priority == BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH && !allowHiRes)
        ) {
            if (priority == BitmapRequest.Priority.LOADING_LIST && maxSide <= LIST_SMALL_MAX_SIDE) {
                val smallTarget = maxSide.coerceIn(LIST_SMALL_MIN_SIDE, LIST_SMALL_MAX_SIDE)
                return Target(smallTarget, smallTarget)
            }
            val lowTarget = maxSide.coerceIn(LOW_RES_MIN_SIDE, LOW_RES_NORMAL_CAP)
            return Target(lowTarget, lowTarget)
        }

        // Widget / playback / fullscreen priorities.
        if (allowHiRes && highResEnabled) {
            return hiResTarget(requestedWidth, requestedHeight, highResEnabled = true)
        }

        // allowHiRes false: clamp to low-res tier but don't shrink below request
        // for large grids that explicitly asked for ≥768.
        val clamped = if (maxSide >= 768) {
            maxSide.coerceAtMost(HI_RES_SIDE)
        } else {
            maxSide.coerceIn(LOW_RES_MIN_SIDE, LOW_RES_NORMAL_CAP)
        }
        return Target(clamped, clamped)
    }
}
