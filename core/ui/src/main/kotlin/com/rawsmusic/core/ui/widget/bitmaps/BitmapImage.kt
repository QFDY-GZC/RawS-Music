package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Compatibility facade for existing layouts. Rendering and lifecycle are fully owned by Coil.
 *
 * The old provider-backed implementation remains available in Git history and the legacy bitmap
 * classes stay in the project, but this composable no longer acquires handles, starts worker
 * requests or retains ref-counted bitmaps.
 */
@Composable
fun BitmapImage(
    key: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetWidth: Int = 512,
    targetHeight: Int = 512,
    priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
    surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
    fadeInMillis: Int = RawArtworkPolicy.VIEW_FADE_MS,
    holdPreviousOnKeyChange: Boolean = false,
    fadeOnBitmapChange: Boolean = true,
    freezeBitmapUpdates: Boolean = false,
    filterQuality: FilterQuality = FilterQuality.Low,
    showDefaultArtwork: Boolean = DefaultAlbumArtworkPolicy.enabled,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var displayedKey by remember { mutableStateOf(key) }
    var previousMemoryKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key, freezeBitmapUpdates, targetWidth, targetHeight, surface) {
        if (!freezeBitmapUpdates && displayedKey != key) {
            if (holdPreviousOnKeyChange && displayedKey.isNotBlank()) {
                previousMemoryKey = CoilArtworkModel(
                    coverKey = displayedKey,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    defaultArtworkEnabled = DefaultAlbumArtworkPolicy.enabled,
                    surface = surface
                ).cacheKey
            } else {
                previousMemoryKey = null
            }
            displayedKey = key
        }
    }

    val model = remember(
        displayedKey,
        targetWidth,
        targetHeight,
        surface,
        showDefaultArtwork
    ) {
        CoilArtworkModel(
            coverKey = displayedKey,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            defaultArtworkEnabled = showDefaultArtwork,
            surface = surface
        )
    }
    val animationEnabled = runCatching { AppPreferences.AlbumArt.coverAnimation }.getOrDefault(true)
    val fade = if (animationEnabled && fadeOnBitmapChange) fadeInMillis else 0
    val request = remember(context, model, fade, previousMemoryKey) {
        CoilArtworkRuntime.request(
            context = context,
            model = model,
            crossfadeMillis = fade,
            placeholderMemoryCacheKey = previousMemoryKey
        )
    }

    AsyncImage(
        model = request,
        imageLoader = CoilArtworkRuntime.imageLoader(context),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        filterQuality = filterQuality,
        onSuccess = { onSuccess?.invoke() },
    )
}
