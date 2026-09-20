package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Reference ArtworkPagerMotion/ArtworkPager moves the bound ArtworkItemNode identity; wrapper decode quality belongs to the
 * child ArtworkImageNode/provider and must never gate the outer holder clock.
 */
internal fun referenceBoundArtworkHolderReadyForMotion(
    providerViewOwnerActive: Boolean,
    targetKeyBound: Boolean,
    targetToken: Int,
): Boolean = targetKeyBound && (providerViewOwnerActive || targetToken != 0)

/** ArtworkImageNode has already mutated its own shader; only non-View lanes need foreground republish. */
internal fun referenceShouldRepublishForegroundForArtworkCallback(
    providerViewOwnerActive: Boolean,
): Boolean = !providerViewOwnerActive
