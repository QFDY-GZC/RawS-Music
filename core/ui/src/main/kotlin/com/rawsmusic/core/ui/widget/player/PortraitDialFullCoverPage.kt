package com.rawsmusic.core.ui.widget.player

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val PortraitDialMotionEasing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)
private const val PortraitDialCommitRatio = 0.22f
private const val PortraitDialFlingFractionPerSecond = 0.58f
private const val PortraitDialPendingSelectionTimeoutMs = 4_000L

/** Portrait-only full-screen dial opened from the home artwork long press. */
@Composable
internal fun PortraitDialFullCoverPage(
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onBackgroundTransitionPrepare: (AudioFile, Int, Int) -> Unit = { _, _, _ -> },
    onCenterArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    sceneRevealProgress: Float = 1f,
    sceneContentScale: Float = 1f,
    hideCenterForSceneTransition: Boolean = false,
    sceneInteractionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val songs = remember(queueSongs, currentSong) {
        queueSongs.ifEmpty { listOfNotNull(currentSong) }
    }
    val queueIdentity = remember(songs) {
        songs.joinToString(separator = "\u0001") { portraitDialSongIdentity(it) }
    }
    val currentSongIdentity = currentSong?.let(::portraitDialSongIdentity)
    fun resolveCurrentIndex(): Int {
        val current = currentSong
        val identity = current?.let(::portraitDialSongIdentity)
        if (
            identity != null &&
            queueCurrentIndex in songs.indices &&
            portraitDialSongIdentity(songs[queueCurrentIndex]) == identity
        ) {
            return queueCurrentIndex
        }
        if (identity != null) {
            val identityIndex = songs.indexOfFirst { portraitDialSongIdentity(it) == identity }
            if (identityIndex >= 0) return identityIndex
        }
        if (queueCurrentIndex in songs.indices) return queueCurrentIndex
        return 0
    }

    var centerVirtualIndex by remember(queueIdentity) {
        mutableIntStateOf(resolveCurrentIndex().coerceIn(0, songs.lastIndex.coerceAtLeast(0)))
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var interactionActive by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    var pendingSelectionIndex by remember(queueIdentity) { mutableIntStateOf(-1) }
    var pendingSelectionIdentity by remember(queueIdentity) { mutableStateOf<String?>(null) }
    var pendingSelectionDeadlineMs by remember(queueIdentity) { mutableStateOf(0L) }
    val latestSongs by rememberUpdatedState(songs)
    val latestCenterVirtualIndex by rememberUpdatedState(centerVirtualIndex)
    val latestOnQueueSongClick by rememberUpdatedState(onQueueSongClick)
    val latestOnBackgroundTransitionPrepare by rememberUpdatedState(onBackgroundTransitionPrepare)
    val scope = rememberCoroutineScope()
    var dragExtentPx by remember { mutableFloatStateOf(1f) }
    var pageBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    fun launchSettle(
        targetDelta: Int,
        initialVelocityFraction: Float = 0f,
        dispatchPlayback: Boolean = true,
    ) {
        if (latestSongs.isEmpty()) return
        val targetVirtualIndex = latestCenterVirtualIndex + targetDelta
        val targetIndex = wrapFullscreenCarouselIndex(targetVirtualIndex, latestSongs.size)
        val generation = settleGeneration + 1
        settleGeneration = generation
        settleJob?.cancel()
        interactionActive = true
        settleJob = scope.launch {
            val target = targetDelta.toFloat()
            val start = progress
            val distance = abs(target - start).coerceAtLeast(0.01f)
            val duration = (250f + 78f * distance).roundToInt().coerceIn(220, 430)
            val animation = Animatable(start)
            try {
                animation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = duration, easing = PortraitDialMotionEasing),
                    initialVelocity = initialVelocityFraction,
                ) {
                    progress = value
                }
                if (generation != settleGeneration) return@launch
                if (targetDelta != 0 && latestSongs.size > 1) {
                    // Keep an unwrapped virtual centre so cards crossing the queue boundary retain the
                    // same composition identity and preloaded outer lanes do not disappear at commit.
                    centerVirtualIndex = targetVirtualIndex
                    progress = 0f
                    if (dispatchPlayback) {
                        val targetSong = latestSongs[targetIndex]
                        pendingSelectionIndex = targetIndex
                        pendingSelectionIdentity = portraitDialSongIdentity(targetSong)
                        pendingSelectionDeadlineMs =
                            SystemClock.uptimeMillis() + PortraitDialPendingSelectionTimeoutMs
                        // Prepare the native artwork transition at the same transaction boundary as
                        // the actual playback command. Step88 prepared it before the visual settle,
                        // allowing player emissions to race the dial and repeatedly recenter it.
                        latestOnBackgroundTransitionPrepare(
                            targetSong,
                            targetIndex,
                            targetDelta.compareTo(0),
                        )
                        latestOnQueueSongClick(targetSong, targetIndex)
                    }
                } else {
                    progress = 0f
                }
            } finally {
                if (generation == settleGeneration) {
                    interactionActive = false
                    settleJob = null
                }
            }
        }
    }

    LaunchedEffect(
        currentSongIdentity,
        queueCurrentIndex,
        queueIdentity,
        interactionActive,
    ) {
        if (interactionActive || songs.isEmpty()) return@LaunchedEffect
        val resolved = resolveCurrentIndex().coerceIn(0, songs.lastIndex)
        val currentWrapped = wrapFullscreenCarouselIndex(centerVirtualIndex, songs.size)
        when (
            val action = resolvePortraitDialExternalSyncAction(
                currentWrappedIndex = currentWrapped,
                resolvedPlayerIndex = resolved,
                queueSize = songs.size,
                currentSongIdentity = currentSongIdentity,
                pendingSelectionIndex = pendingSelectionIndex,
                pendingSelectionIdentity = pendingSelectionIdentity,
                pendingSelectionDeadlineMs = pendingSelectionDeadlineMs,
                nowMs = SystemClock.uptimeMillis(),
            )
        ) {
            PortraitDialExternalSyncAction.None,
            PortraitDialExternalSyncAction.IgnoreStaleEmission -> return@LaunchedEffect

            PortraitDialExternalSyncAction.ConfirmPending -> {
                pendingSelectionIndex = -1
                pendingSelectionIdentity = null
                pendingSelectionDeadlineMs = 0L
            }

            is PortraitDialExternalSyncAction.Animate -> {
                if (pendingSelectionIndex >= 0) {
                    pendingSelectionIndex = -1
                    pendingSelectionIdentity = null
                    pendingSelectionDeadlineMs = 0L
                }
                launchSettle(action.delta, dispatchPlayback = false)
            }

            is PortraitDialExternalSyncAction.Snap -> {
                if (pendingSelectionIndex >= 0) {
                    pendingSelectionIndex = -1
                    pendingSelectionIdentity = null
                    pendingSelectionDeadlineMs = 0L
                }
                centerVirtualIndex = nearestPortraitDialVirtualIndex(
                    currentVirtualIndex = centerVirtualIndex,
                    targetWrappedIndex = action.wrappedIndex,
                    size = songs.size,
                )
                progress = 0f
            }
        }
    }

    val dragState = rememberDraggableState { deltaPx ->
        if (!sceneInteractionEnabled || songs.size <= 1) return@rememberDraggableState
        progress = (progress - deltaPx / dragExtentPx.coerceAtLeast(1f)).coerceIn(-1.15f, 1.15f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                pageBoundsInRoot = coordinates.boundsInRoot()
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = sceneInteractionEnabled && songs.size > 1,
                onDragStarted = {
                    settleGeneration += 1
                    settleJob?.cancel()
                    settleJob = null
                    interactionActive = true
                },
                onDragStopped = { velocityPxPerSecond ->
                    val velocityFraction = -velocityPxPerSecond / dragExtentPx.coerceAtLeast(1f)
                    val direction = when {
                        progress >= PortraitDialCommitRatio -> 1
                        progress <= -PortraitDialCommitRatio -> -1
                        velocityFraction >= PortraitDialFlingFractionPerSecond -> 1
                        velocityFraction <= -PortraitDialFlingFractionPerSecond -> -1
                        else -> 0
                    }
                    launchSettle(direction, velocityFraction.coerceIn(-2.4f, 2.4f))
                },
            ),
    ) {
        if (songs.isEmpty()) return@BoxWithConstraints
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val metrics = resolvePortraitDialMetrics(widthPx, heightPx)
        val sceneReveal = sceneRevealProgress.coerceIn(0f, 1f)
        dragExtentPx = metrics.stridePx
        val cardSide = with(density) { metrics.cardSidePx.toDp() }
        val centerIndex = wrapFullscreenCarouselIndex(centerVirtualIndex, songs.size)
        val visibleSong = songs[centerIndex]
        val pageRoot = pageBoundsInRoot
        LaunchedEffect(pageRoot, visibleSong, widthPx, heightPx) {
            val root = pageRoot ?: return@LaunchedEffect
            val half = metrics.cardSidePx * 0.5f
            onCenterArtworkBoundsChanged(
                visibleSong,
                Rect(
                    left = root.left + metrics.centerXPx - half,
                    top = root.top + metrics.centerYPx - half,
                    right = root.left + metrics.centerXPx + half,
                    bottom = root.top + metrics.centerYPx + half,
                ),
            )
        }
        val tapTargets = remember(centerVirtualIndex, progress, songs.size, widthPx, heightPx) {
            (-PORTRAIT_DIAL_VISIBLE_RADIUS..PORTRAIT_DIAL_VISIBLE_RADIUS).mapNotNull { logicalOffset ->
                val position = logicalOffset.toFloat() - progress
                val transform = resolvePortraitDialFullscreenLaneTransform(position, widthPx, heightPx)
                val half = metrics.cardSidePx * transform.scale * 0.5f
                val center = Offset(
                    widthPx * 0.5f + transform.translationXPx,
                    heightPx * 0.5f + transform.translationYPx,
                )
                val index = wrapFullscreenCarouselIndex(centerVirtualIndex + logicalOffset, songs.size)
                PortraitDialTapTarget(logicalOffset, index, center, half)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val contentScale = sceneContentScale.coerceIn(0.62f, 1f)
                    scaleX = contentScale
                    scaleY = contentScale
                }
                .pointerInput(sceneInteractionEnabled, tapTargets, interactionActive) {
                    detectTapGestures { point ->
                        if (!sceneInteractionEnabled || interactionActive) return@detectTapGestures
                        val target = tapTargets
                            .filter { it.logicalOffset != 0 && it.contains(point) }
                            .minByOrNull { abs(it.logicalOffset) }
                            ?: return@detectTapGestures
                        launchSettle(target.logicalOffset)
                    }
                },
        ) {
            for (logicalOffset in -PORTRAIT_DIAL_RENDER_RADIUS..PORTRAIT_DIAL_RENDER_RADIUS) {
                val position = logicalOffset.toFloat() - progress
                val virtualQueueIndex = centerVirtualIndex + logicalOffset
                val songIndex = wrapFullscreenCarouselIndex(virtualQueueIndex, songs.size)
                val song = songs[songIndex]
                val baseTransform = resolvePortraitDialFullscreenLaneTransform(position, widthPx, heightPx)
                val centreLane = abs(position) < 0.55f
                val transform = if (centreLane) {
                    baseTransform
                } else {
                    resolvePortraitDialSceneLaneTransform(
                        position = position,
                        viewportWidthPx = widthPx,
                        viewportHeightPx = heightPx,
                        revealProgress = sceneReveal,
                    )
                }
                val artworkKey = song.resolvePlaybackArtworkKey(null).orEmpty()
                val artworkTier = resolvePortraitDialArtworkTier(position)
                val artworkSurface = if (artworkTier == PortraitDialArtworkTier.Center) {
                    ArtworkSurface.Fullscreen
                } else {
                    ArtworkSurface.List
                }
                val artworkPriority = when (artworkTier) {
                    PortraitDialArtworkTier.Center -> BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH
                    PortraitDialArtworkTier.Near -> BitmapRequest.Priority.LOADING_WIDGET
                    PortraitDialArtworkTier.Outer -> BitmapRequest.Priority.LOADING_LIST
                    PortraitDialArtworkTier.Preload -> BitmapRequest.Priority.LOADING_LIST_DELAYED
                    PortraitDialArtworkTier.Dormant -> BitmapRequest.Priority.IDLE
                }
                val hideCentre = hideCenterForSceneTransition && centreLane
                key("portrait-dial:$virtualQueueIndex:$songIndex:$artworkKey") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(cardSide)
                            .zIndex(transform.zIndex)
                            .graphicsLayer {
                                translationX = transform.translationXPx
                                translationY = transform.translationYPx
                                scaleX = transform.scale
                                scaleY = transform.scale
                                alpha = if (hideCentre) 0f else transform.alpha
                                rotationX = transform.rotationX
                                rotationZ = transform.rotationZ
                                cameraDistance = 36f * density.density
                                shape = RoundedCornerShape(FULLSCREEN_PORTRAIT_DIAL_CORNER_RADIUS_DP.dp)
                                clip = true
                            },
                    ) {
                        if (artworkTier.shouldLoad) {
                            // A default layer is always present while the real request is pending or
                            // when the media item has no usable artwork key. Side lanes therefore
                            // never become transparent holes in the full-screen rail.
                            DefaultAlbumArtwork(
                                modifier = Modifier.fillMaxSize(),
                                contentDescription = song.displayName,
                                contentScale = ContentScale.Crop,
                            )
                            if (artworkKey.isNotBlank()) {
                                BitmapImage(
                                    key = artworkKey,
                                    contentDescription = song.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    targetWidth = artworkTier.targetSidePx,
                                    targetHeight = artworkTier.targetSidePx,
                                    surface = artworkSurface,
                                    priority = artworkPriority,
                                    holdPreviousOnKeyChange = false,
                                    fadeInMillis = 0,
                                    fadeOnBitmapChange = false,
                                    filterQuality = if (
                                        artworkTier == PortraitDialArtworkTier.Center
                                    ) {
                                        FilterQuality.High
                                    } else {
                                        FilterQuality.Medium
                                    },
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onBack,
                enabled = sceneInteractionEnabled,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp)
                    .graphicsLayer { alpha = sceneReveal }
                    .zIndex(80f),
            ) {
                Text(text = "‹", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
            }

        }
    }
}

private fun portraitDialSongIdentity(song: AudioFile): String =
    "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"

private data class PortraitDialTapTarget(
    val logicalOffset: Int,
    val songIndex: Int,
    val center: Offset,
    val halfSidePx: Float,
) {
    fun contains(point: Offset): Boolean =
        point.x in (center.x - halfSidePx)..(center.x + halfSidePx) &&
            point.y in (center.y - halfSidePx)..(center.y + halfSidePx)
}
