package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    surface: ArtworkSurface = ArtworkSurface.fromPriority(priority),
    fadeInMillis: Int = RawArtworkPolicy.VIEW_FADE_MS,
    holdPreviousOnKeyChange: Boolean = false,
    fadeOnBitmapChange: Boolean = true,
    freezeBitmapUpdates: Boolean = false
) {
    val context = LocalContext.current
    val freezeUpdatesState = rememberUpdatedState(freezeBitmapUpdates)

    // 兜底：防止某些页面比全局 init 更早渲染
    remember(context) {
        BitmapProvider.init(context)
        true
    }

    val initialCandidate = remember(key, targetWidth, targetHeight, surface) {
        ArtworkDisplayResolver.acquireBest(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            surface = surface,
            allowHiRes = true
        )
    }
    val initialHandle = initialCandidate?.handle

    var requestedKey by remember { mutableStateOf(key) }
    var bitmapKey by remember { mutableStateOf(if (initialHandle?.isValid == true) key else "") }
    var handle by remember { mutableStateOf<ArtworkHandle?>(initialHandle) }
    var pendingHandle by remember { mutableStateOf<ArtworkHandle?>(null) }
    var pendingBitmapKey by remember { mutableStateOf("") }
    var bitmapVersion by remember { mutableIntStateOf(if (initialHandle?.isValid == true) 1 else 0) }
    var resolvedFadeMillis by remember { mutableIntStateOf(0) }
    var terminalNoArtwork by remember { mutableStateOf(false) }

    val alpha = remember { Animatable(if (initialHandle?.isValid == true) 1f else 0f) }
    var skipNextFade by remember { mutableStateOf(initialHandle?.isValid == true) }

    fun replaceHandle(newHandle: ArtworkHandle?, newKey: String) {
        val old = handle
        if (old !== newHandle) old?.release()
        handle = newHandle
        bitmapKey = if (newHandle?.isValid == true) newKey else ""
        bitmapVersion = if (newHandle?.isValid == true) bitmapVersion + 1 else 0
    }

    fun clearPending() {
        pendingHandle?.release()
        pendingHandle = null
        pendingBitmapKey = ""
    }

    fun acceptHandle(requestKey: String, loaded: ArtworkHandle?) {
        if (requestKey != requestedKey) {
            loaded?.release()
            return
        }
        if (loaded?.isValid != true) {
            loaded?.release()
            return
        }
        if (loaded.bitmap === handle?.bitmap && bitmapKey == requestKey) {
            loaded.release()
            return
        }

        val hadBitmap = handle?.isValid == true
        if (freezeUpdatesState.value && hadBitmap) {
            pendingHandle?.release()
            pendingHandle = loaded
            pendingBitmapKey = requestKey
            return
        }

        val sameSource = bitmapKey.isNotBlank() && bitmapKey == requestKey
        val anim = ArtworkDisplayResolver.bindAnimation(
            surface = surface,
            hadPreviousBitmap = hadBitmap,
            sameSource = sameSource,
            fadeOnBitmapChange = fadeOnBitmapChange,
            requestedMillis = fadeInMillis
        )
        resolvedFadeMillis = anim.durationMillis
        skipNextFade = !anim.shouldAnimate
        replaceHandle(loaded, requestKey)
        clearPending()
    }

    fun acceptBitmap(requestKey: String, loaded: android.graphics.Bitmap?) {
        terminalNoArtwork = loaded == null && shouldShowDefaultAlbumArtwork(
            requestKey,
            targetWidth,
            targetHeight
        )
        val acquired = BitmapProvider.acquireLoaded(
            key = requestKey,
            bitmap = loaded,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            surface = surface
        )
        acceptHandle(requestKey, acquired)
    }

    LaunchedEffect(key, targetWidth, targetHeight, holdPreviousOnKeyChange, surface) {
        requestedKey = key
        terminalNoArtwork = shouldShowDefaultAlbumArtwork(key, targetWidth, targetHeight)
        if (key.isNotBlank()) {
            when (surface) {
                ArtworkSurface.Fullscreen -> BitmapProvider.warmFullCoverArt(key)
                ArtworkSurface.Playback, ArtworkSurface.Widget -> BitmapProvider.warmPlaybackArt(key)
                else -> Unit
            }
        }
        val cachedCandidate = ArtworkDisplayResolver.acquireBest(
            key = key,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            surface = surface,
            allowHiRes = true
        )
        val cached = cachedCandidate?.handle

        if (cached?.isValid == true) {
            acceptHandle(key, cached)
        } else {
            cached?.release()
            if (!ArtworkDisplayResolver.shouldRetainPrevious(surface, holdPreviousOnKeyChange)) {
                replaceHandle(null, "")
                clearPending()
                alpha.snapTo(0f)
            }
        }
    }

    LaunchedEffect(freezeBitmapUpdates, requestedKey, pendingHandle, pendingBitmapKey) {
        if (!freezeUpdatesState.value) {
            val pending = pendingHandle
            if (pending?.isValid == true && pendingBitmapKey == requestedKey) {
                pendingHandle = null
                pendingBitmapKey = ""
                acceptHandle(requestedKey, pending)
            }
        }
    }

    LaunchedEffect(bitmapVersion, resolvedFadeMillis, fadeInMillis, fadeOnBitmapChange) {
        if (bitmapVersion <= 0) return@LaunchedEffect

        val enableAnim = runCatching {
            AppPreferences.AlbumArt.coverAnimation
        }.getOrDefault(true)

        val duration = resolvedFadeMillis.takeIf { it > 0 } ?: fadeInMillis
        if (skipNextFade || alpha.value >= 1f || !enableAnim || duration <= 0 || !fadeOnBitmapChange) {
            skipNextFade = false
            alpha.snapTo(1f)
        } else {
            alpha.snapTo(0f)
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = duration)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            handle?.release()
            pendingHandle?.release()
            handle = null
            pendingHandle = null
        }
    }

    DisposableEffect(
        key,
        targetWidth,
        targetHeight,
        priority,
        surface
    ) {
        if (key.isBlank()) {
            onDispose { }
        } else {
            val request = BitmapProvider.load(
                key = key,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                priority = priority,
                surface = surface
            ) { loaded ->
                acceptBitmap(key, loaded)
            }
            onDispose {
                val keep = surface == ArtworkSurface.Playback || surface == ArtworkSurface.Fullscreen || surface == ArtworkSurface.Widget
                BitmapProvider.cancel(request, keepDecoding = keep)
            }
        }
    }

    val current = handle?.takeIf { it.isValid }?.bitmap

    if (current != null && !current.isRecycled) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.graphicsLayer {
                this.alpha = alpha.value.coerceIn(0f, 1f)
            },
            contentScale = contentScale
        )
    } else if (terminalNoArtwork || shouldShowDefaultAlbumArtwork(key, targetWidth, targetHeight)) {
        DefaultAlbumArtwork(
            modifier = modifier,
            contentDescription = contentDescription,
            contentScale = contentScale
        )
    }
}
