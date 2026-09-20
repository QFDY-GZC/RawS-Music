package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.module.data.source.playback.MusicSourceArtworkRepository
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SourceArtwork(
    item: RawSourceMediaItem?,
    localPath: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetSize: Int = 512,
    cornerRadiusDp: Int = 16,
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val remoteUrl = item?.artworkUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> if (value.startsWith("//")) "https:$value" else value }
    val hasLocalArtwork = !localPath.isNullOrBlank()

    LaunchedEffect(item?.stableIdentity, item?.artworkUrl) {
        MusicSourceArtworkRepository.prefetch(context, item)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(scheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        // Keep the already visible provider-backed remote image underneath while the local source
        // is warming. Both lanes share BitmapProvider ownership; there is no second painter cache.
        if (remoteUrl != null) {
            BitmapImage(
                key = remoteUrl,
                contentDescription = item?.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                targetWidth = targetSize,
                targetHeight = targetSize,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Playback,
                showDefaultArtwork = false,
            )
        }

        if (hasLocalArtwork) {
            BitmapImage(
                key = localPath.orEmpty(),
                contentDescription = item?.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                targetWidth = targetSize,
                targetHeight = targetSize,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Playback,
                fadeInMillis = 120,
                holdPreviousOnKeyChange = true,
            )
        }

        if (remoteUrl == null && !hasLocalArtwork) {
            Image(
                painter = painterResource(R.drawable.ic_music_2_fill),
                contentDescription = null,
                colorFilter = ColorFilter.tint(scheme.primary),
                modifier = Modifier.fillMaxSize(0.46f),
            )
        }
    }
}
