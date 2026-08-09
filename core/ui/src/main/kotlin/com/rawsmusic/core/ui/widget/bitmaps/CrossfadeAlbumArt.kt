package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Coil-backed compatibility facade for hero/playback artwork.
 *
 * Coil performs the drawable transition and keeps the previous memory-cache entry as the request
 * placeholder. The historical low/high provider stages are intentionally not started here.
 */
@Composable
fun CrossfadeAlbumArt(
    key: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showPlaceholder: Boolean = true,
    fadeMillis: Int = RawArtworkPolicy.VIEW_FADE_MS,
    lowResSize: Int = AlbumArtTiers.LOW_RES_NORMAL_CAP,
    hiResSize: Int = 1280,
    holdPreviousOnKeyChange: Boolean = true,
    priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_WIDGET,
    surface: ArtworkSurface = ArtworkSurface.Playback,
    freezeBitmapUpdates: Boolean = false,
    skipLowResPlaceholder: Boolean = false,
    forceTargetHighRequest: Boolean = false
) {
    // Keep the public parameters while the old provider implementation remains available for
    // rollback. A single final-size Coil request avoids a second quality-upgrade decode/crossfade.
    @Suppress("UNUSED_VARIABLE")
    val compatibility = Triple(lowResSize, skipLowResPlaceholder, forceTargetHighRequest)
    BitmapImage(
        key = key,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        targetWidth = hiResSize,
        targetHeight = hiResSize,
        priority = priority,
        surface = surface,
        fadeInMillis = fadeMillis,
        holdPreviousOnKeyChange = holdPreviousOnKeyChange,
        fadeOnBitmapChange = true,
        freezeBitmapUpdates = freezeBitmapUpdates,
        showDefaultArtwork = showPlaceholder
    )
}
