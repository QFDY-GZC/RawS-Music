package com.rawsmusic.core.ui.scene.pages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.ArtistArtworkViewerRuntime
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SharedCoverOverlay
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.scene.SharedTransitionSpec
import com.rawsmusic.core.ui.scene.awaitSharedActorReady
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.data.artist.ArtistImageCandidateRepository
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

private val ArtistViewerTopControlsHeight = 116.dp
private val ArtistViewerBottomControlsHeight = 126.dp
private const val ArtistViewerMotionMs = 230

/**
 * Fullscreen artist-image viewer hosted inside ARTIST_DETAIL itself.
 *
 * It deliberately does not create a NavScene/Dialog. The hero artwork remains the source endpoint,
 * while this layer animates the same provider identity into a fullscreen Fit target. A temporary
 * replacement changes only the current ArtistDetail composition session and therefore naturally
 * disappears after leaving/re-entering the artist page.
 */
@Composable
internal fun ArtistArtworkViewer(
    artistName: String,
    originalArtworkKey: String,
    currentArtworkKey: String,
    sharedElementId: String,
    heroBoundsInWindow: Rect?,
    onSelectArtwork: (String) -> Unit,
    onRestoreArtwork: () -> Unit,
    onSharedOwnershipChanged: (Boolean) -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val coverRegistry = LocalSharedCoverRegistry.current
    val entryArtworkKey = remember { currentArtworkKey }
    val transitionKey = remember(sharedElementId) {
        "artist-artwork-viewer:$sharedElementId:${System.identityHashCode(Any())}"
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var currentBitmap by remember(currentArtworkKey) { mutableStateOf<Bitmap?>(null) }
    var showGallery by remember { mutableStateOf(false) }
    var sharedTransitionActive by remember { mutableStateOf(false) }
    var stableArtworkVisible by remember { mutableStateOf(false) }

    val remoteCandidates by produceState(initialValue = emptyList<String>(), artistName) {
        value = ArtistImageCandidateRepository.fetchCandidates(artistName).map { it.url }
    }
    val candidateKeys = remember(originalArtworkKey, remoteCandidates) {
        buildList {
            originalArtworkKey.takeIf(String::isNotBlank)?.let(::add)
            addAll(remoteCandidates)
        }.distinct()
    }
    val replaced = currentArtworkKey.isNotBlank() && currentArtworkKey != originalArtworkKey

    DisposableEffect(Unit) {
        ArtistArtworkViewerRuntime.updateVisible(true)
        onDispose {
            coverRegistry.clearEphemeralTransition(transitionKey)
            onSharedOwnershipChanged(false)
            ArtistArtworkViewerRuntime.updateVisible(false)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = if (closing) 1f - progress.coerceIn(0f, 1f)
                    else progress.coerceIn(0f, 1f)
                )
            )
            // This is an in-scene fullscreen layer, so it must own the whole pointer surface.
            // Consume only leftovers in Final pass: child buttons/gallery keep their normal taps,
            // while empty black letterbox areas cannot fall through to the ArtistDetail list.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (!change.isConsumed) change.consume()
                        }
                    }
                }
            }
            .onGloballyPositioned { overlayOrigin = it.positionInWindow() },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val targetSidePx = minOf(widthPx, heightPx).coerceAtLeast(1f)
        val targetLeftPx = ((widthPx - targetSidePx) * 0.5f).coerceAtLeast(0f)
        val targetTopPx = ((heightPx - targetSidePx) * 0.5f).coerceAtLeast(0f)
        val target = Rect(
            left = targetLeftPx,
            top = targetTopPx,
            right = targetLeftPx + targetSidePx,
            bottom = targetTopPx + targetSidePx,
        )
        val targetInWindow = Rect(
            left = target.left + overlayOrigin.x,
            top = target.top + overlayOrigin.y,
            right = target.right + overlayOrigin.x,
            bottom = target.bottom + overlayOrigin.y,
        )
        val topBlackHeight = with(density) { target.top.toDp() }
        val bottomBlackHeight = with(density) { (heightPx - target.bottom).coerceAtLeast(0f).toDp() }

        LaunchedEffect(entryArtworkKey, sharedElementId, targetInWindow, heroBoundsInWindow) {
            if (closing || stableArtworkVisible || entryArtworkKey.isBlank()) return@LaunchedEffect
            val source = coverRegistry.get(NavScene.ARTIST_DETAIL.name, sharedElementId)
                ?: heroBoundsInWindow?.takeIf { it.width > 1f && it.height > 1f }?.let { bounds ->
                    SharedCoverSnapshot(
                        sceneId = NavScene.ARTIST_DETAIL.name,
                        elementId = sharedElementId,
                        boundsInWindow = bounds,
                        coverKey = entryArtworkKey,
                        radiusDp = 16f,
                    )
                }
                ?: return@LaunchedEffect
            val targetSnapshot = SharedCoverSnapshot(
                sceneId = NavScene.ARTIST_DETAIL.name,
                elementId = sharedElementId,
                boundsInWindow = targetInWindow,
                coverKey = entryArtworkKey,
                radiusDp = 0f,
            )
            coverRegistry.prepareEphemeralTransition(transitionKey, source, targetSnapshot)
            sharedTransitionActive = true
            progress = 0f
            withFrameNanos { }
            val sharedReady = awaitSharedActorReady(coverRegistry, transitionKey)
            if (sharedReady) onSharedOwnershipChanged(true)
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(ArtistViewerMotionMs, easing = FastOutSlowInEasing),
            ) { value, _ -> progress = value }
            stableArtworkVisible = true
            if (!sharedReady) onSharedOwnershipChanged(true)
            withFrameNanos { }
            coverRegistry.clearEphemeralTransition(transitionKey)
            sharedTransitionActive = false
        }

        suspend fun runClose() {
            if (closing) return
            closing = true
            showGallery = false
            val liveHero = coverRegistry.get(NavScene.ARTIST_DETAIL.name, sharedElementId)
                ?: heroBoundsInWindow?.takeIf { it.width > 1f && it.height > 1f }?.let { bounds ->
                    SharedCoverSnapshot(
                        sceneId = NavScene.ARTIST_DETAIL.name,
                        elementId = sharedElementId,
                        boundsInWindow = bounds,
                        coverKey = currentArtworkKey,
                        radiusDp = 16f,
                    )
                }
            if (liveHero == null || currentArtworkKey.isBlank()) {
                onSharedOwnershipChanged(false)
                onDismissed()
                return
            }
            val fullscreen = SharedCoverSnapshot(
                sceneId = NavScene.ARTIST_DETAIL.name,
                elementId = sharedElementId,
                boundsInWindow = targetInWindow,
                coverKey = currentArtworkKey,
                radiusDp = 0f,
                promotedBitmap = currentBitmap?.takeIf { !it.isRecycled },
            )
            val targetHero = liveHero.copy(coverKey = currentArtworkKey)
            coverRegistry.prepareEphemeralTransition(transitionKey, fullscreen, targetHero)
            sharedTransitionActive = true
            progress = 0f
            withFrameNanos { }
            val sharedReady = awaitSharedActorReady(coverRegistry, transitionKey)
            if (sharedReady) stableArtworkVisible = false
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(ArtistViewerMotionMs, easing = FastOutSlowInEasing),
            ) { value, _ -> progress = value }
            coverRegistry.clearEphemeralTransition(transitionKey)
            sharedTransitionActive = false
            onSharedOwnershipChanged(false)
            onDismissed()
        }

        BackHandler { scope.launch { runClose() } }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(target.left.roundToInt(), target.top.roundToInt())
                }
                .requiredSize(
                    width = with(density) { target.width.toDp() },
                    height = with(density) { target.height.toDp() },
                )
                .graphicsLayer { alpha = if (stableArtworkVisible) 1f else 0f },
            contentAlignment = Alignment.Center,
        ) {
            BitmapImage(
                key = currentArtworkKey,
                contentDescription = artistName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                targetWidth = 3072,
                targetHeight = 3072,
                surface = ArtworkSurface.Fullscreen,
                aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                exactTarget = false,
                fadeInMillis = 0,
                showDefaultArtwork = true,
                onBitmapReady = { currentBitmap = it },
            )
        }

        if (sharedTransitionActive) {
            SharedCoverOverlay(
                registry = coverRegistry,
                spec = SharedTransitionSpec(
                    active = true,
                    fromSceneId = NavScene.ARTIST_DETAIL.name,
                    toSceneId = NavScene.ARTIST_DETAIL.name,
                    activeSceneId = NavScene.ARTIST_DETAIL.name,
                    progressProvider = { progress.coerceIn(0f, 1f) },
                    transitionKey = transitionKey,
                    ownedElementId = sharedElementId.takeIf {
                        coverRegistry.isOverlayReady(transitionKey)
                    }.orEmpty(),
                    sharedCoverOverlayOwnsAnchor = coverRegistry.isOverlayReady(transitionKey),
                ),
                overlayOriginInWindow = overlayOrigin,
            )
        }

        Row(
            modifier = Modifier
                .height(topBlackHeight)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 14.dp, top = 10.dp)
                .graphicsLayer {
                    alpha = if (closing) 1f - progress else progress
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            ArtistViewerBareAction(
                iconRes = R.drawable.ic_back,
                contentDescription = stringResource(R.string.artist_artwork_viewer_back),
                onClick = { scope.launch { runClose() } },
            )

            Column(horizontalAlignment = Alignment.End) {
                if (replaced) {
                    ArtistViewerBareAction(
                        iconRes = R.drawable.ic_retry,
                        contentDescription = stringResource(R.string.artist_artwork_viewer_restore),
                        onClick = {
                            currentBitmap = null
                            onRestoreArtwork()
                        },
                    )
                }
                currentBitmap?.let { bitmap ->
                    Text(
                        text = "${bitmap.width} × ${bitmap.height}",
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = if (replaced) 3.dp else 14.dp, end = 2.dp),
                    )
                }
            }
        }

        if (showGallery && candidateKeys.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .height(bottomBlackHeight)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Color.Black)
                    .padding(horizontal = 14.dp)
                    .padding(top = 12.dp, bottom = 66.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(candidateKeys, key = { it }) { key ->
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (key == currentArtworkKey) 2.dp else 0.5.dp,
                                color = if (key == currentArtworkKey) Color.White else Color.White.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                currentBitmap = null
                                onSelectArtwork(key)
                                showGallery = false
                            },
                    ) {
                        BitmapImage(
                            key = key,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            targetWidth = 256,
                            targetHeight = 256,
                            aspectPolicy = ArtworkAspectPolicy.Crop,
                            fadeInMillis = 0,
                            showDefaultArtwork = true,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(bottomBlackHeight)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = if (closing) 1f - progress else progress
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtistViewerBareAction(
                iconRes = R.drawable.ic_source_download,
                contentDescription = stringResource(R.string.artist_artwork_viewer_save),
                onClick = {
                    val bitmap = currentBitmap ?: return@ArtistViewerBareAction
                    scope.launch {
                        val saved = saveArtistBitmap(context, artistName, bitmap)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(
                                if (saved) R.string.artist_artwork_viewer_saved
                                else R.string.artist_artwork_viewer_save_failed
                            ),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            ArtistViewerBareAction(
                iconRes = R.drawable.ic_share,
                contentDescription = stringResource(R.string.artist_artwork_viewer_share),
                onClick = {
                    val bitmap = currentBitmap ?: return@ArtistViewerBareAction
                    scope.launch {
                        if (!shareArtistBitmap(context, artistName, bitmap)) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.artist_artwork_viewer_share_failed,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
            ArtistViewerBareAction(
                iconRes = R.drawable.ic_nav_custom_albums,
                contentDescription = stringResource(R.string.artist_artwork_viewer_gallery),
                onClick = { showGallery = !showGallery },
            )
        }
    }
}

@Composable
private fun ArtistViewerBareAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(25.dp),
        )
    }
}

private fun lerpRect(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float): Float = a + (b - a) * p
    return Rect(
        left = lerp(from.left, to.left),
        top = lerp(from.top, to.top),
        right = lerp(from.right, to.right),
        bottom = lerp(from.bottom, to.bottom),
    )
}

private suspend fun saveArtistBitmap(context: Context, artistName: String, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${safeArtistFileName(artistName)}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RawSMusic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    withExportableBitmap(bitmap) { export ->
                        check(export.compress(Bitmap.CompressFormat.PNG, 100, out))
                    }
                } ?: return@runCatching false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }.getOrDefault(false)
    }

private suspend fun shareArtistBitmap(context: Context, artistName: String, bitmap: Bitmap): Boolean {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared_artist_images").apply { mkdirs() }
            val file = File(dir, "${safeArtistFileName(artistName)}.png")
            FileOutputStream(file).use { out ->
                withExportableBitmap(bitmap) { export ->
                    check(export.compress(Bitmap.CompressFormat.PNG, 100, out))
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    } ?: return false
    return withContext(Dispatchers.Main) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, artistName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

private inline fun <T> withExportableBitmap(source: Bitmap, block: (Bitmap) -> T): T {
    val copy = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        null
    }
    val export = copy ?: source
    try {
        return block(export)
    } finally {
        copy?.takeIf { !it.isRecycled }?.recycle()
    }
}

private fun safeArtistFileName(value: String): String = value
    .trim()
    .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
    .take(80)
    .ifBlank { "artist" }
