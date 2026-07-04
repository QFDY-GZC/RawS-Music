package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences

@Composable
fun CrossfadeAlbumArt(
    key: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showPlaceholder: Boolean = true,
    fadeMillis: Int = 200
) {
    val context = LocalContext.current

    // 兜底：防止某些页面比全局 init 更早渲染
    remember(context) {
        BitmapProvider.init(context)
        true
    }

    if (key.isBlank()) {
        if (showPlaceholder) {
            AlbumArtPlaceholder(modifier = modifier)
        }
        return
    }

    val animEnabled = remember {
        runCatching {
            AppPreferences.AlbumArt.coverAnimation
        }.getOrDefault(true)
    }

    val cachedLowRes = remember(key) {
        peekBitmap(
            key = key,
            targetSize = LOW_RES_SIZE
        )
    }

    val cachedHiRes = remember(key) {
        peekBitmap(
            key = key,
            targetSize = HI_RES_SIZE
        )
    }

    val initialBitmap = cachedHiRes ?: cachedLowRes ?: remember(key) {
        BitmapProvider.peekAny(key)
    }

    var currentBitmap by remember(key) {
        mutableStateOf<Bitmap?>(initialBitmap)
    }

    var incomingBitmap by remember(key) {
        mutableStateOf<Bitmap?>(null)
    }

    var currentVersion by remember(key) {
        mutableIntStateOf(
            if (initialBitmap != null && !initialBitmap.isRecycled) 1 else 0
        )
    }

    val firstAlpha = remember(key) {
        Animatable(0f)
    }

    val crossfadeAlpha = remember(key) {
        Animatable(0f)
    }

    fun acceptLoaded(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap === currentBitmap) return
        if (bitmap === incomingBitmap) return

        incomingBitmap = bitmap
    }

    // 第一张图也淡入
    LaunchedEffect(currentVersion, fadeMillis, animEnabled) {
        if (currentVersion <= 0) return@LaunchedEffect

        if (!animEnabled || fadeMillis <= 0) {
            firstAlpha.snapTo(1f)
        } else {
            firstAlpha.snapTo(0f)
            firstAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = fadeMillis)
            )
        }
    }

    LaunchedEffect(incomingBitmap) {
        val incoming = incomingBitmap
        val current = currentBitmap

        when {
            incoming == null || incoming.isRecycled -> Unit

            current == null || current.isRecycled -> {
                currentBitmap = incoming
                incomingBitmap = null
                currentVersion += 1
                crossfadeAlpha.snapTo(0f)
            }

            incoming !== current -> {
                Log.d(
                    TAG,
                    "XFADE key=${key.takeLast(40)} old=${current.width}x${current.height} new=${incoming.width}x${incoming.height}"
                )

                if (!animEnabled || fadeMillis <= 0) {
                    currentBitmap = incoming
                    incomingBitmap = null
                    crossfadeAlpha.snapTo(0f)
                    firstAlpha.snapTo(1f)
                } else {
                    crossfadeAlpha.snapTo(0f)
                    crossfadeAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = fadeMillis)
                    )

                    currentBitmap = incoming
                    incomingBitmap = null
                    currentVersion += 1
                    firstAlpha.snapTo(1f)
                    crossfadeAlpha.snapTo(0f)
                }
            }
        }
    }

    DisposableEffect(key) {
        var lowReq: BitmapRequest? = null
        var hiReq: BitmapRequest? = null

        val needLow = cachedLowRes == null && cachedHiRes == null
        val needHi = cachedHiRes == null

        if (needLow) {
            lowReq = BitmapProvider.load(
                key = key,
                targetWidth = LOW_RES_SIZE,
                targetHeight = LOW_RES_SIZE,
                priority = BitmapRequest.Priority.LOADING_LIST
            ) { bitmap ->
                acceptLoaded(bitmap)
            }
        }

        if (needHi) {
            hiReq = BitmapProvider.load(
                key = key,
                targetWidth = HI_RES_SIZE,
                targetHeight = HI_RES_SIZE,
                priority = BitmapRequest.Priority.LOADING_LIST_DELAYED
            ) { bitmap ->
                acceptLoaded(bitmap)
            }
        }

        onDispose {
            lowReq?.let(BitmapProvider::cancel)
            hiReq?.let(BitmapProvider::cancel)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        val current = currentBitmap
        val incoming = incomingBitmap

        if (current != null && !current.isRecycled) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = firstAlpha.value.coerceIn(0f, 1f)
                    },
                contentScale = contentScale
            )
        } else if (showPlaceholder) {
            AlbumArtPlaceholder(
                modifier = Modifier.fillMaxSize()
            )
        }

        if (
            incoming != null &&
            !incoming.isRecycled &&
            incoming !== current
        ) {
            Image(
                bitmap = incoming.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = crossfadeAlpha.value.coerceIn(0f, 1f)
                    },
                contentScale = contentScale
            )
        }
    }
}

@Composable
fun AlbumArtPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "♪",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            fontSize = 36.sp
        )
    }
}

private fun peekBitmap(
    key: String,
    targetSize: Int
): Bitmap? {
    return try {
        BitmapProvider.peek(
            key = key,
            targetWidth = targetSize,
            targetHeight = targetSize
        )?.takeIf {
            !it.isRecycled
        }
    } catch (_: Exception) {
        null
    }
}

private const val LOW_RES_SIZE = 256
private const val HI_RES_SIZE = 1440
private const val TAG = "AlbumArt"
