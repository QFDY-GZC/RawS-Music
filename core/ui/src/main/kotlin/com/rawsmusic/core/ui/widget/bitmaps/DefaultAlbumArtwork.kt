package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.prefs.AppPreferences
import android.util.Log

/** Shared policy and renderer for songs that have no real embedded or external artwork. */
object DefaultAlbumArtworkPolicy {
    var enabled by mutableStateOf(AppPreferences.AlbumArt.useDefaultArtwork)
        private set

    fun updateEnabled(value: Boolean) {
        enabled = value
        AppPreferences.AlbumArt.useDefaultArtwork = value
        Log.i("RawArt", "DEFAULT_ARTWORK_PREF enabled=$value")
    }
}

fun shouldShowDefaultAlbumArtwork(key: String?, targetWidth: Int, targetHeight: Int): Boolean {
    if (!DefaultAlbumArtworkPolicy.enabled) return false
    val normalizedKey = key.orEmpty()
    return normalizedKey.isBlank() || BitmapProvider.hasRecentThumbnailFailure(
        key = normalizedKey,
        targetWidth = targetWidth,
        targetHeight = targetHeight
    )
}

@Composable
fun DefaultAlbumArtwork(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (!DefaultAlbumArtworkPolicy.enabled) return
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        Log.d("RawArt", "DEFAULT_ARTWORK_SHOW")
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = RawArtworkPolicy.VIEW_FADE_MS)
        )
    }
    Image(
        painter = painterResource(R.drawable.default_album_art),
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        contentScale = contentScale
    )
}
