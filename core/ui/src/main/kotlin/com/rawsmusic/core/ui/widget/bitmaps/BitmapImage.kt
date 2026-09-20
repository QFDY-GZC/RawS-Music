package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import com.rawsmusic.core.ui.perf.TransitionPerfTrace

private class BitmapImageTransitionDrawTrace {
    var sessionId: Long = 0L
    var bitmapIdentity: Int = 0
}

/**
 * Provider-backed artwork surface.
 *
 * The holder owns an ArtworkHandle just like the View artwork lane: BitmapProvider owns decoding,
 * cache residency and source ordering; Compose only draws the accepted bitmap and releases the
 * wrapper when this surface leaves composition.
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
    aspectPolicy: ArtworkAspectPolicy = ArtworkAspectPolicy.Crop,
    exactTarget: Boolean = false,
    onSuccess: (() -> Unit)? = null,
    onBitmapReady: ((Bitmap) -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE")
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val presentationCompatibility = Triple(fadeInMillis, holdPreviousOnKeyChange, fadeOnBitmapChange)
    // `freezeBitmapUpdates` is a request-admission flag, not artwork ownership.  The accepted
    // wrapper must stay attached while a scene/shared-element transition owns the pixels; otherwise
    // toggling freeze disposes the current handle, exposes the placeholder for one frame, then
    // reacquires the same bitmap at the endpoint.  reference player keeps retained artwork view's current wrapper
    // attached during the transition and only suppresses replacement/admission work.
    var handle by remember(key, targetWidth, targetHeight, surface, aspectPolicy, exactTarget) {
        mutableStateOf(
            if (key.isBlank()) null else BitmapProvider.acquire(
                key = key,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                surface = surface,
                aspectPolicy = aspectPolicy,
                exactTarget = exactTarget,
            )
        )
    }
    val freezeUpdates by rememberUpdatedState(freezeBitmapUpdates)
    val requestSlot = remember(key, targetWidth, targetHeight, priority, surface, aspectPolicy, exactTarget) {
        arrayOfNulls<BitmapRequest>(1)
    }

    // Request lifetime may restart when freeze toggles, but handle lifetime deliberately does not.
    // Cancelling an in-flight request is enough to make the frozen transition cheap and stable.
    DisposableEffect(key, targetWidth, targetHeight, priority, surface, freezeBitmapUpdates, aspectPolicy, exactTarget) {
        requestSlot[0]?.let(BitmapProvider::cancel)
        requestSlot[0] = if (!freezeBitmapUpdates && key.isNotBlank()) {
            BitmapProvider.loadHandle(
                key = key,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                priority = priority,
                surface = surface,
                aspectPolicy = aspectPolicy,
                exactTarget = exactTarget,
            ) { delivered ->
                // A completion already queued on the main thread may race with the freeze edge.
                // Never let that late result replace the pixels owned by the active transition.
                if (freezeUpdates) {
                    if (delivered !== handle) delivered?.release()
                    return@loadHandle
                }
                if (delivered !== handle) {
                    handle?.release()
                    handle = delivered
                }
            }
        } else {
            null
        }
        onDispose {
            requestSlot[0]?.let(BitmapProvider::cancel)
            requestSlot[0] = null
        }
    }

    // Actual wrapper ownership follows the artwork identity/surface lifetime only.  In particular,
    // a transition freeze must never run this cleanup path.
    DisposableEffect(key, targetWidth, targetHeight, surface, aspectPolicy, exactTarget) {
        onDispose {
            handle?.release()
            handle = null
        }
    }

    val bitmap = handle?.bitmap
    if (bitmap != null && !bitmap.isRecycled) {
        val transitionDrawTrace = remember { BitmapImageTransitionDrawTrace() }
        val tracedModifier = if (maxOf(bitmap.width, bitmap.height) >= 768) {
            modifier.drawWithContent {
                if (TransitionPerfTrace.isActive()) {
                    val session = TransitionPerfTrace.currentSessionId()
                    val identity = System.identityHashCode(bitmap)
                    if (
                        session != 0L &&
                        (transitionDrawTrace.sessionId != session || transitionDrawTrace.bitmapIdentity != identity)
                    ) {
                        transitionDrawTrace.sessionId = session
                        transitionDrawTrace.bitmapIdentity = identity
                        TransitionPerfTrace.mark(
                            "bitmap_compose_first_draw",
                            "id=$identity size=${bitmap.width}x${bitmap.height} config=${bitmap.config?.name ?: "null"} " +
                                "target=${targetWidth}x${targetHeight} handle=${handle?.isValid == true} " +
                                "tier=${handle?.tier?.name ?: "-"} surface=$surface key=${key.takeLast(24)}",
                        )
                    }
                }
                drawContent()
            }
        } else {
            modifier
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = tracedModifier,
        contentScale = contentScale,
        filterQuality = filterQuality,
        )
        LaunchedEffect(bitmap) {
            onSuccess?.invoke()
            onBitmapReady?.invoke(bitmap)
        }
    } else if (showDefaultArtwork) {
        DefaultAlbumArtwork(
            modifier = modifier,
            contentDescription = contentDescription,
        )
    }
}
