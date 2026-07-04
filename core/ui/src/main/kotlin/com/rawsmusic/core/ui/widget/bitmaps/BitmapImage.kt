package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun BitmapImage(
    key: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetWidth: Int = 512,
    targetHeight: Int = 512,
    priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_LIST,
    fadeInMillis: Int = 200
) {
    val context = LocalContext.current

    // 兜底：防止某些页面比全局 init 更早渲染
    remember(context) {
        BitmapProvider.init(context)
        true
    }

    val initialBitmap = remember(key, targetWidth, targetHeight) {
        BitmapProvider.peek(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) ?: BitmapProvider.peekAny(key)
    }

    var bitmap by remember(key, targetWidth, targetHeight) {
        mutableStateOf<Bitmap?>(initialBitmap)
    }

    var bitmapVersion by remember(key, targetWidth, targetHeight) {
        mutableIntStateOf(
            if (initialBitmap != null && !initialBitmap.isRecycled) 1 else 0
        )
    }

    val alpha = remember(key, targetWidth, targetHeight) {
        Animatable(0f)
    }

    var skipNextFade by remember(key, targetWidth, targetHeight) {
        mutableStateOf(initialBitmap != null && !initialBitmap.isRecycled)
    }

    fun acceptBitmap(loaded: Bitmap?) {
        if (loaded == null || loaded.isRecycled) return
        if (loaded === bitmap) return

        val hadBitmap = bitmap != null && bitmap?.isRecycled == false
        bitmap = loaded
        bitmapVersion += 1
        if (hadBitmap) skipNextFade = false
    }

    LaunchedEffect(key, targetWidth, targetHeight) {
        acceptBitmap(
            BitmapProvider.peek(
                key = key,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) ?: BitmapProvider.peekAny(key)
        )
    }

    LaunchedEffect(bitmapVersion, fadeInMillis) {
        if (bitmapVersion <= 0) return@LaunchedEffect

        val enableAnim = runCatching {
            AppPreferences.AlbumArt.coverAnimation
        }.getOrDefault(true)

        if (skipNextFade || alpha.value >= 1f || !enableAnim || fadeInMillis <= 0) {
            skipNextFade = false
            alpha.snapTo(1f)
        } else {
            alpha.snapTo(0f)
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = fadeInMillis)
            )
        }
    }

    DisposableEffect(
        key,
        targetWidth,
        targetHeight,
        priority
    ) {
        if (key.isBlank()) {
            onDispose { }
        } else {
            val request = BitmapProvider.load(
                key = key,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                priority = priority
            ) { loaded ->
                acceptBitmap(loaded)
            }

            onDispose {
                BitmapProvider.cancel(request)
            }
        }
    }

    val current = bitmap

    if (current != null && !current.isRecycled) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.graphicsLayer {
                this.alpha = alpha.value.coerceIn(0f, 1f)
            },
            contentScale = contentScale
        )
    }
}
