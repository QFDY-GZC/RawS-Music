package com.rawsmusic.core.ui.widget.bitmaps

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences

private fun isStrictPlaybackHighCandidate(
    candidate: ArtworkDisplayResolver.Candidate,
    targetSide: Int
): Boolean {
    if (!candidate.isValid || candidate.bitmap.isRecycled) return false
    val side = maxOf(candidate.bitmap.width, candidate.bitmap.height)
    // Do not treat the 1024 playback warm slot as the final ordinary-player hero image when
    // this surface asked for 1280/1440. That was the reason the normal player opened blurry
    // and only became sharp after lyrics/fullscreen loaded a larger tier.
    val strictSide = (targetSide * 0.90f).toInt().coerceAtLeast(AlbumArtTiers.HI_RES_SIDE)
    return side >= strictSide
}

/**
 * Project-style album art presenter.
 *
 * The presenter now holds [ArtworkHandle]s instead of raw Bitmap state. That gives playback pages
 * the same attach/detach lifecycle the artwork view has: bind a wrapper while visible,
 * release it when replaced/detached, and never recycle from the UI layer.
 */
@Composable
fun CrossfadeAlbumArt(
    key: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showPlaceholder: Boolean = true,
    fadeMillis: Int = RawArtworkPolicy.VIEW_FADE_MS,
    lowResSize: Int = LOW_RES_SIZE,
    hiResSize: Int = HI_RES_SIZE,
    holdPreviousOnKeyChange: Boolean = true,
    priority: BitmapRequest.Priority = BitmapRequest.Priority.LOADING_WIDGET,
    surface: ArtworkSurface = ArtworkSurface.Playback,
    freezeBitmapUpdates: Boolean = false,
    skipLowResPlaceholder: Boolean = false,
    forceTargetHighRequest: Boolean = false
) {
    val animEnabled = remember {
        runCatching { AppPreferences.AlbumArt.coverAnimation }.getOrDefault(true)
    }
    val freezeUpdatesState = rememberUpdatedState(freezeBitmapUpdates)

    val initialCandidate = remember(key, lowResSize, hiResSize, surface, skipLowResPlaceholder) {
        ArtworkDisplayResolver.acquirePlayback(
            key = key,
            lowResSize = lowResSize,
            hiResSize = hiResSize,
            surface = surface
        )?.let { candidate ->
            if (skipLowResPlaceholder && !isStrictPlaybackHighCandidate(candidate, hiResSize)) {
                candidate.release()
                null
            } else {
                candidate
            }
        }
    }
    val initialHandle = initialCandidate?.handle
    val initialQuality = remember(initialCandidate) {
        initialCandidate?.quality ?: ArtworkDisplayResolver.QUALITY_NONE
    }

    var requestedKey by remember { mutableStateOf(key) }
    var currentKey by remember { mutableStateOf(if (initialHandle?.isValid == true) key else "") }
    var currentHandle by remember { mutableStateOf<ArtworkHandle?>(initialHandle) }
    var currentQuality by remember { mutableIntStateOf(initialQuality) }

    var incomingKey by remember { mutableStateOf("") }
    var incomingHandle by remember { mutableStateOf<ArtworkHandle?>(null) }
    var incomingQuality by remember { mutableIntStateOf(0) }

    var pendingKey by remember { mutableStateOf("") }
    var pendingHandle by remember { mutableStateOf<ArtworkHandle?>(null) }
    var pendingQuality by remember { mutableIntStateOf(0) }
    var terminalNoArtwork by remember { mutableStateOf(false) }

    val crossfadeAlpha = remember { Animatable(0f) }

    fun clearIncoming() {
        incomingHandle?.release()
        incomingHandle = null
        incomingKey = ""
        incomingQuality = 0
    }

    fun clearPending() {
        pendingHandle?.release()
        pendingHandle = null
        pendingKey = ""
        pendingQuality = 0
    }

    fun storePending(loadedKey: String, loaded: ArtworkHandle, quality: Int) {
        val pending = pendingHandle
        if (pending?.isValid == true && pendingKey == loadedKey && pendingQuality >= quality) {
            loaded.release()
            return
        }
        pending?.release()
        pendingHandle = loaded
        pendingKey = loadedKey
        pendingQuality = quality
    }

    fun replaceCurrent(newHandle: ArtworkHandle?, newKey: String, newQuality: Int) {
        val old = currentHandle
        if (old !== newHandle) old?.release()
        currentHandle = newHandle
        currentKey = if (newHandle?.isValid == true) newKey else ""
        currentQuality = if (newHandle?.isValid == true) newQuality else 0
    }

    fun acceptHandle(loadedKey: String, loaded: ArtworkHandle?, quality: Int) {
        if (loadedKey != requestedKey) {
            loaded?.release()
            return
        }
        if (loaded?.isValid != true) {
            loaded?.release()
            return
        }

        val current = currentHandle
        if (currentKey == loadedKey && current?.isValid == true && currentQuality >= quality) {
            loaded.release()
            return
        }
        if (loaded.bitmap === current?.bitmap || loaded.bitmap === incomingHandle?.bitmap || loaded.bitmap === pendingHandle?.bitmap) {
            loaded.release()
            return
        }

        if (freezeUpdatesState.value && current?.isValid == true) {
            storePending(loadedKey, loaded, quality)
            return
        }

        if (current?.isValid != true) {
            replaceCurrent(loaded, loadedKey, quality)
            clearIncoming()
            clearPending()
            return
        }

        incomingHandle?.release()
        incomingHandle = loaded
        incomingKey = loadedKey
        incomingQuality = quality
    }

    fun acceptLoadedBitmap(loadedKey: String, bitmap: android.graphics.Bitmap?, quality: Int, targetSize: Int) {
        terminalNoArtwork = bitmap == null && shouldShowDefaultAlbumArtwork(loadedKey, targetSize, targetSize)
        val handle = BitmapProvider.acquireLoaded(
            key = loadedKey,
            bitmap = bitmap,
            targetWidth = targetSize,
            targetHeight = targetSize,
            surface = surface
        )
        acceptHandle(loadedKey, handle, quality)
    }

    LaunchedEffect(key, lowResSize, hiResSize, holdPreviousOnKeyChange, surface, skipLowResPlaceholder) {
        requestedKey = key
        terminalNoArtwork = shouldShowDefaultAlbumArtwork(key, lowResSize, lowResSize)
        clearIncoming()
        if (!freezeUpdatesState.value) clearPending()
        crossfadeAlpha.snapTo(0f)

        if (key.isBlank()) {
            replaceCurrent(null, "", 0)
            return@LaunchedEffect
        }

        val cachedCandidate = ArtworkDisplayResolver.acquirePlayback(
            key = key,
            lowResSize = lowResSize,
            hiResSize = hiResSize,
            surface = surface
        )
        val usableCachedCandidate = cachedCandidate?.let { candidate ->
            if (skipLowResPlaceholder && !isStrictPlaybackHighCandidate(candidate, hiResSize)) {
                candidate.release()
                null
            } else {
                candidate
            }
        }
        val cached = usableCachedCandidate?.handle
        if (cached?.isValid == true && usableCachedCandidate != null) {
            acceptHandle(key, cached, usableCachedCandidate.quality)
        } else {
            cached?.release()
            if (!ArtworkDisplayResolver.shouldRetainPrevious(surface, holdPreviousOnKeyChange)) {
                replaceCurrent(null, "", 0)
                clearPending()
            }
        }
    }

    LaunchedEffect(freezeBitmapUpdates, requestedKey, pendingHandle, pendingKey, pendingQuality) {
        if (!freezeUpdatesState.value) {
            val pending = pendingHandle
            if (pending?.isValid == true && pendingKey == requestedKey) {
                pendingHandle = null
                val keyToCommit = pendingKey
                val qualityToCommit = pendingQuality
                pendingKey = ""
                pendingQuality = 0
                acceptHandle(keyToCommit, pending, qualityToCommit)
            } else if (pending != null) {
                clearPending()
            }
        }
    }

    LaunchedEffect(incomingHandle, incomingKey, incomingQuality, fadeMillis, animEnabled, freezeBitmapUpdates) {
        val incoming = incomingHandle
        if (incoming?.isValid != true) return@LaunchedEffect
        if (incomingKey != requestedKey) return@LaunchedEffect
        if (freezeUpdatesState.value && currentHandle?.isValid == true) {
            incomingHandle = null
            val keyToStore = incomingKey
            val qualityToStore = incomingQuality
            incomingKey = ""
            incomingQuality = 0
            storePending(keyToStore, incoming, qualityToStore)
            return@LaunchedEffect
        }

        val current = currentHandle
        val anim = ArtworkDisplayResolver.crossfadeAnimation(
            surface = surface,
            sameSource = currentKey.isNotBlank() && currentKey == incomingKey,
            qualityUpgrade = incomingQuality > currentQuality,
            requestedMillis = fadeMillis
        )
        if (current?.isValid != true || !animEnabled || !anim.shouldAnimate) {
            incomingHandle = null
            replaceCurrent(incoming, incomingKey, incomingQuality)
            incomingKey = ""
            incomingQuality = 0
            crossfadeAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        crossfadeAlpha.snapTo(0f)
        crossfadeAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = anim.durationMillis)
        )
        incomingHandle = null
        replaceCurrent(incoming, incomingKey, incomingQuality)
        incomingKey = ""
        incomingQuality = 0
        crossfadeAlpha.snapTo(0f)
    }

    DisposableEffect(Unit) {
        onDispose {
            currentHandle?.release()
            incomingHandle?.release()
            pendingHandle?.release()
            currentHandle = null
            incomingHandle = null
            pendingHandle = null
        }
    }

    DisposableEffect(key, lowResSize, hiResSize, priority, surface, skipLowResPlaceholder, forceTargetHighRequest) {
        var lowReq: BitmapRequest? = null
        var hiReq: BitmapRequest? = null

        if (key.isNotBlank()) {
            when (surface) {
                ArtworkSurface.Fullscreen -> BitmapProvider.warmFullCoverArt(key)
                ArtworkSurface.Playback, ArtworkSurface.Widget -> BitmapProvider.warmPlaybackArt(key)
                else -> Unit
            }
            val cachedCandidate = ArtworkDisplayResolver.acquirePlayback(
                key = key,
                lowResSize = lowResSize,
                hiResSize = hiResSize,
                surface = surface
            )
            val hasCachedLow = (cachedCandidate?.quality ?: ArtworkDisplayResolver.QUALITY_NONE) >= ArtworkDisplayResolver.QUALITY_LOW
            val hasCachedHi = if (forceTargetHighRequest || skipLowResPlaceholder) {
                cachedCandidate?.let { isStrictPlaybackHighCandidate(it, hiResSize) } == true
            } else {
                (cachedCandidate?.quality ?: ArtworkDisplayResolver.QUALITY_NONE) >= ArtworkDisplayResolver.QUALITY_HIGH
            }
            cachedCandidate?.release()

            if (!hasCachedLow && !skipLowResPlaceholder) {
                // Policy order: request a small wrapper first so UI can bind quickly.
                val fastLowPriority = when (surface) {
                    ArtworkSurface.Playback, ArtworkSurface.Fullscreen, ArtworkSurface.Widget -> BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH
                    else -> BitmapRequest.Priority.LOADING_LIST
                }
                lowReq = BitmapProvider.loadThumbnail(
                    key = key,
                    targetWidth = lowResSize,
                    targetHeight = lowResSize,
                    priority = fastLowPriority,
                    surface = surface
                ) { bitmap ->
                    acceptLoadedBitmap(key, bitmap, ArtworkDisplayResolver.QUALITY_LOW, lowResSize)
                }
            }

            if (!hasCachedHi) {
                // Playback/fullscreen high-res must not sit behind list-delayed work.  This mirrors
                // the current-view artwork request: low-res can appear first, but hero art is a
                // foreground provider request and the callback is accepted only if the key is still
                // current.
                hiReq = BitmapProvider.load(
                    key = key,
                    targetWidth = hiResSize,
                    targetHeight = hiResSize,
                    priority = priority,
                    surface = surface
                ) { bitmap ->
                    acceptLoadedBitmap(key, bitmap, ArtworkDisplayResolver.QUALITY_HIGH, hiResSize)
                }
            }
        }

        onDispose {
            val keep = surface == ArtworkSurface.Playback || surface == ArtworkSurface.Fullscreen || surface == ArtworkSurface.Widget
            lowReq?.let { BitmapProvider.cancel(it, keepDecoding = keep) }
            hiReq?.let { BitmapProvider.cancel(it, keepDecoding = keep) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val current = currentHandle?.takeIf { it.isValid }?.bitmap
        val incoming = incomingHandle?.takeIf { it.isValid }?.bitmap

        if (current != null && !current.isRecycled) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (showPlaceholder) {
            if (terminalNoArtwork || shouldShowDefaultAlbumArtwork(key, lowResSize, lowResSize)) {
                DefaultAlbumArtwork(modifier = Modifier.fillMaxSize())
            } else {
                AlbumArtPlaceholder(modifier = Modifier.fillMaxSize())
            }
        }

        if (incoming != null && !incoming.isRecycled && incoming !== current && incomingKey == requestedKey) {
            Image(
                bitmap = incoming.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = crossfadeAlpha.value.coerceIn(0f, 1f) },
                contentScale = contentScale
            )
        }
    }
}

@Composable
private fun AlbumArtPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\u266B",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 48.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Light
        )
    }
}

private const val LOW_RES_SIZE = AlbumArtTiers.LOW_RES_NORMAL_CAP
private const val HI_RES_SIZE = 1280
