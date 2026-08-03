package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.RawArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val FullCoverZoomEpsilon = 0.002f
private const val FullCoverCarouselCommitRatio = 0.33f
private const val FullCoverCarouselFlingPxPerSecond = 1_050f

private enum class FullCoverGestureMode {
    Undecided,
    Carousel,
    ZoomPan,
    VerticalPass,
    EdgeBack,
}

/**
 * Full-screen artwork carousel.
 *
 * The carousel keeps eleven logical lanes and exposes nine at rest. Pinch/pan remains isolated to
 * the current artwork: as soon as the current cover leaves 1x, side lanes fade and carousel
 * navigation stops owning the gesture until the cover returns to its neutral transform.
 */
@Composable
fun FullCoverPage(
    currentSong: AudioFile? = null,
    queueSongs: List<AudioFile> = emptyList(),
    queueCurrentIndex: Int = -1,
    coverPath: String?,
    title: String = "",
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
    lyricIsPlaying: Boolean = false,
    onLyricSeek: (Long) -> Unit = {},
    onQueueSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onCurrentArtworkLongPress: () -> Unit = {},
    onBack: () -> Unit,
    sceneRevealProgress: Float = 1f,
    hideCenterForSceneTransition: Boolean = false,
    sceneInteractionEnabled: Boolean = true,
    renderBackdrop: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val songs = remember(queueSongs, currentSong) {
        if (queueSongs.isNotEmpty()) queueSongs else listOfNotNull(currentSong)
    }
    fun resolveCurrentIndex(): Int {
        if (queueCurrentIndex in songs.indices) return queueCurrentIndex
        val current = currentSong ?: return 0
        return songs.indexOfFirst {
            it.path == current.path &&
                it.cueOffsetMs == current.cueOffsetMs &&
                it.cueTrackIndex == current.cueTrackIndex
        }.takeIf { it >= 0 } ?: 0
    }

    var visualCenterIndex by remember(songs) {
        mutableIntStateOf(resolveCurrentIndex().coerceIn(0, songs.lastIndex.coerceAtLeast(0)))
    }
    var queueVisible by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var gestureActive by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var carouselProgress by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    var settleVisualSong by remember { mutableStateOf<AudioFile?>(null) }
    val scope = rememberCoroutineScope()
    val latestScale by rememberUpdatedState(scale)
    val latestOffsetX by rememberUpdatedState(offsetX)
    val latestOffsetY by rememberUpdatedState(offsetY)
    val latestSongs by rememberUpdatedState(songs)
    val latestCenterIndex by rememberUpdatedState(visualCenterIndex)
    val latestOnQueueSongClick by rememberUpdatedState(onQueueSongClick)
    val latestOnCurrentArtworkLongPress by rememberUpdatedState(onCurrentArtworkLongPress)
    val latestOnBack by rememberUpdatedState(onBack)

    fun launchCarouselSettle(
        targetValue: Float,
        commitDirection: Int,
        durationMillis: Int,
    ) {
        val generation = settleGeneration + 1
        settleGeneration = generation
        settleJob?.cancel()
        settling = true
        settleJob = scope.launch {
            val animation = Animatable(carouselProgress)
            try {
                animation.animateTo(
                    targetValue = targetValue,
                    animationSpec = tween(durationMillis),
                ) {
                    carouselProgress = value
                }
                if (commitDirection != 0 && latestSongs.isNotEmpty()) {
                    val nextIndex = wrapFullscreenCarouselIndex(
                        latestCenterIndex + commitDirection,
                        latestSongs.size,
                    )
                    val selected = latestSongs[nextIndex]
                    visualCenterIndex = nextIndex
                    carouselProgress = 0f
                    latestOnQueueSongClick(selected, nextIndex)
                } else {
                    carouselProgress = targetValue
                }
            } finally {
                if (settleGeneration == generation) {
                    settling = false
                }
            }
        }
    }

    fun launchCarouselSelection(laneOffset: Int) {
        val distance = abs(laneOffset).coerceAtMost(FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS)
        if (distance == 0 || latestSongs.isEmpty()) return
        val direction = if (laneOffset < 0) -1 else 1
        val selectionSongs = latestSongs
        val startIndex = visualCenterIndex
        val targetIndex = wrapFullscreenCarouselIndex(
            startIndex + direction * distance,
            selectionSongs.size,
        )
        val generation = settleGeneration + 1
        settleGeneration = generation
        settleJob?.cancel()
        settleVisualSong = selectionSongs.getOrNull(startIndex) ?: currentSong
        settling = true
        queueVisible = false
        // Submit playback once at tap time. Visual metadata/backdrop stay frozen until the target
        // reaches centre, so engine state changes cannot flash intermediate carousel frames.
        latestOnQueueSongClick(selectionSongs[targetIndex], targetIndex)
        settleJob = scope.launch {
            val animation = Animatable(0f)
            try {
                animation.animateTo(
                    targetValue = distance.toFloat(),
                    animationSpec = tween(
                        durationMillis = 220 + (distance - 1) * 135,
                        easing = FastOutSlowInEasing,
                    ),
                ) {
                    if (settleGeneration != generation || selectionSongs.isEmpty()) return@animateTo
                    val frame = resolveFullscreenCarouselSelectionFrame(
                        startIndex = startIndex,
                        laneOffset = laneOffset,
                        travelledRails = value,
                        queueSize = selectionSongs.size,
                    )
                    visualCenterIndex = frame.centerIndex
                    carouselProgress = frame.progress
                }
                if (settleGeneration != generation) return@launch
                visualCenterIndex = targetIndex
                carouselProgress = 0f
            } finally {
                if (settleGeneration == generation) {
                    settleVisualSong = null
                    settling = false
                    settleJob = null
                }
            }
        }
    }

    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current

    LaunchedEffect(queueCurrentIndex, currentSong, songs) {
        if (!gestureActive && !settling && songs.isNotEmpty()) {
            visualCenterIndex = resolveCurrentIndex().coerceIn(0, songs.lastIndex)
            carouselProgress = 0f
        }
    }

    // Keep backdrop and metadata tied to the playing song while a far-lane tap traverses
    // intermediate queue positions. The Canvas still moves continuously, but background artwork
    // and text switch only once when the target actually reaches centre and playback is committed.
    val displayedSong = if (settling) {
        settleVisualSong ?: currentSong
    } else {
        songs.getOrNull(visualCenterIndex) ?: currentSong
    }
    val displayedCoverKey = displayedSong.resolvePlaybackArtworkKey(coverPath)
    LaunchedEffect(displayedCoverKey) {
        if (!displayedCoverKey.isNullOrBlank()) {
            BitmapProvider.warmFullCoverArt(displayedCoverKey)
        }
    }

    val neutralTransform = abs(scale - 1f) <= FullCoverZoomEpsilon &&
        abs(offsetX) <= 0.5f && abs(offsetY) <= 0.5f
    val sideLaneAlpha by animateFloatAsState(
        targetValue = if (neutralTransform) 1f else 0f,
        animationSpec = tween(150),
        label = "full-cover-side-lanes",
    )
    val resolvedSceneReveal = sceneRevealProgress.coerceIn(0f, 1f)
    val sceneChromeScale = 0.62f + 0.38f * resolvedSceneReveal

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize(),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val metrics = remember(widthPx, heightPx) {
            resolveFullscreenArtworkCarouselMetrics(widthPx, heightPx)
        }
        val dragExtentPx = max(metrics.laneStridePx * 1.35f, metrics.coverSidePx * 0.38f)
        val edgeZonePx = with(density) { 36.dp.toPx() }
        val edgeCommitPx = with(density) { 48.dp.toPx() }
        val coverSideDp = with(density) { metrics.coverSidePx.toDp() }
        val baseCenterTranslationY = metrics.centerYPx - heightPx * 0.5f

        fun isInsideCurrentCover(position: Offset): Boolean {
            val half = metrics.coverSidePx * 0.5f
            return position.x in (metrics.centerXPx - half)..(metrics.centerXPx + half) &&
                position.y in (metrics.centerYPx - half)..(metrics.centerYPx + half)
        }

        fun visibleLaneAt(position: Offset): Int? {
            if (!neutralTransform || settling || songs.size <= 1) return null
            return resolveFullscreenArtworkTappedLane(
                positionX = position.x,
                positionY = position.y,
                metrics = metrics,
                progress = carouselProgress,
            )
        }

        if (renderBackdrop) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = resolvedSceneReveal },
            ) {
                StandardPlayerBackdrop(
                    coverPath = displayedCoverKey,
                    accent = Color.Transparent,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.88f },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.18f),
                                0.48f to Color.Black.copy(alpha = 0.34f),
                                1f to Color.Black.copy(alpha = 0.68f),
                            )
                        ),
                )
            }
        }

        val gestureModifier = if (sceneInteractionEnabled && !settling) {
            Modifier
                .pointerInput(songs, widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (latestSongs.isEmpty()) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val startedAtLeftEdge = down.position.x <= edgeZonePx
                        val startedAtRightEdge = down.position.x >= widthPx - edgeZonePx
                        var mode = FullCoverGestureMode.Undecided
                        var accumulated = Offset.Zero
                        var previousCentroid = down.position
                        var sawMultiplePointers = false
                        var workingScale = latestScale
                        var workingOffsetX = latestOffsetX
                        var workingOffsetY = latestOffsetY
                        gestureActive = true
                        settleGeneration += 1
                        settleJob?.cancel()
                        settleJob = null
                        settling = false

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                val centroid = pressed
                                    .map { it.position }
                                    .fold(Offset.Zero) { sum, value -> sum + value } /
                                    pressed.size.toFloat()
                                val centroidDelta = centroid - previousCentroid
                                previousCentroid = centroid
                                tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                                accumulated += centroidDelta
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (pressed.size >= 2 || event.changes.size >= 2) {
                                    sawMultiplePointers = true
                                }

                                if (mode == FullCoverGestureMode.Undecided) {
                                    val zoomRequested = sawMultiplePointers || abs(zoomChange - 1f) > 0.006f
                                    val transformed = abs(workingScale - 1f) > FullCoverZoomEpsilon
                                    if (zoomRequested || transformed) {
                                        mode = FullCoverGestureMode.ZoomPan
                                        carouselProgress = 0f
                                    } else if (
                                        (startedAtLeftEdge && accumulated.x >= edgeCommitPx) ||
                                        (startedAtRightEdge && accumulated.x <= -edgeCommitPx)
                                    ) {
                                        mode = FullCoverGestureMode.EdgeBack
                                    } else if (accumulated.getDistance() >= viewConfiguration.touchSlop) {
                                        mode = if (abs(accumulated.x) > abs(accumulated.y) * 1.10f) {
                                            FullCoverGestureMode.Carousel
                                        } else {
                                            FullCoverGestureMode.VerticalPass
                                        }
                                    }
                                }

                                when (mode) {
                                    FullCoverGestureMode.ZoomPan -> {
                                        workingScale = (workingScale * zoomChange).coerceIn(0.5f, 5f)
                                        if (workingScale > 1f) {
                                            workingOffsetX += panChange.x
                                            workingOffsetY += panChange.y
                                        } else {
                                            workingOffsetX = 0f
                                            workingOffsetY = 0f
                                        }
                                        scale = workingScale
                                        offsetX = workingOffsetX
                                        offsetY = workingOffsetY
                                        event.changes.forEach { it.consume() }
                                    }

                                    FullCoverGestureMode.Carousel -> {
                                        if (abs(workingScale - 1f) <= FullCoverZoomEpsilon) {
                                            val next = (carouselProgress - centroidDelta.x / dragExtentPx)
                                                .coerceIn(-1f, 1f)
                                            carouselProgress = next
                                            event.changes.forEach { it.consume() }
                                        }
                                    }

                                    FullCoverGestureMode.EdgeBack -> {
                                        event.changes.forEach { it.consume() }
                                        latestOnBack()
                                        break
                                    }

                                    FullCoverGestureMode.Undecided,
                                    FullCoverGestureMode.VerticalPass -> Unit
                                }
                            }

                            if (mode == FullCoverGestureMode.Carousel) {
                                val velocityX = tracker.calculateVelocity().x
                                val progress = carouselProgress
                                val commitDirection = when {
                                    progress >= FullCoverCarouselCommitRatio -> 1
                                    progress <= -FullCoverCarouselCommitRatio -> -1
                                    velocityX <= -FullCoverCarouselFlingPxPerSecond -> 1
                                    velocityX >= FullCoverCarouselFlingPxPerSecond -> -1
                                    else -> 0
                                }
                                launchCarouselSettle(
                                    targetValue = commitDirection.toFloat(),
                                    commitDirection = commitDirection,
                                    durationMillis = if (commitDirection == 0) 190 else 220,
                                )
                            }
                        } finally {
                            gestureActive = false
                            if (mode != FullCoverGestureMode.Carousel && carouselProgress != 0f) {
                                launchCarouselSettle(
                                    targetValue = 0f,
                                    commitDirection = 0,
                                    durationMillis = 160,
                                )
                            }
                        }
                    }
                }
                .pointerInput(displayedCoverKey, widthPx, heightPx) {
                    detectTapGestures(
                        onDoubleTap = { position ->
                            if (isInsideCurrentCover(position)) latestOnBack()
                        },
                        onLongPress = { position ->
                            if (isInsideCurrentCover(position) &&
                                abs(latestScale - 1f) <= FullCoverZoomEpsilon &&
                                abs(carouselProgress) <= 0.001f
                            ) {
                                latestOnCurrentArtworkLongPress()
                            }
                        },
                        onTap = { position ->
                            when {
                                isInsideCurrentCover(position) && latestScale < 1f -> {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                visibleLaneAt(position) != null -> {
                                    val laneOffset = visibleLaneAt(position) ?: return@detectTapGestures
                                    launchCarouselSelection(laneOffset)
                                }
                                !isInsideCurrentCover(position) -> {
                                    queueVisible = !queueVisible
                                }
                            }
                        },
                    )
                }
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(gestureModifier),
        ) {
            FullscreenArtworkCarouselCanvas(
                songs = songs,
                centerIndex = visualCenterIndex,
                progress = carouselProgress,
                hideCenterLane = !neutralTransform || hideCenterForSceneTransition,
                sideLaneAlpha = sideLaneAlpha,
                sceneRevealProgress = resolvedSceneReveal,
                modifier = Modifier.fillMaxSize(),
            )

            if (!neutralTransform && !displayedCoverKey.isNullOrBlank()) {
                BitmapImage(
                    key = displayedCoverKey,
                    contentDescription = displayedSong?.displayName ?: title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(coverSideDp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = baseCenterTranslationY + offsetY
                            shape = RoundedCornerShape(22.dp)
                            clip = true
                        },
                    contentScale = ContentScale.Crop,
                    targetWidth = 1440,
                    targetHeight = 1440,
                    surface = ArtworkSurface.Fullscreen,
                    priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                    fadeInMillis = RawArtworkPolicy.HERO_FADE_MS,
                    holdPreviousOnKeyChange = true,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 28.dp)
                .graphicsLayer {
                    alpha = resolvedSceneReveal
                    scaleX = sceneChromeScale
                    scaleY = sceneChromeScale
                },
        ) {
            IconButton(
                onClick = onBack,
                enabled = sceneInteractionEnabled,
                modifier = Modifier.size(44.dp),
            ) {
                Text("←", fontSize = 20.sp, color = Color.White)
            }
            if (displayedSong != null) {
                Column(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .widthIn(max = 320.dp),
                ) {
                    Text(
                        text = displayedSong.displayName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayedSong.artist.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = displayedSong.artist,
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = queueVisible && sceneInteractionEnabled,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 30.dp),
        ) {
            FullCoverQueuePosition(
                currentIndex = visualCenterIndex,
                totalCount = songs.size,
            )
        }

        if (lyricSong != null) {
            FullCoverLyricPreview(
                song = lyricSong,
                positionMs = lyricPositionMs,
                isPlaying = lyricIsPlaying,
                durationMs = currentSong?.duration ?: 0L,
                onSeek = onLyricSeek,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = 24.dp)
                    .widthIn(max = 430.dp)
                    .fillMaxWidth(0.46f)
                    .graphicsLayer {
                        alpha = resolvedSceneReveal
                        scaleX = sceneChromeScale
                        scaleY = sceneChromeScale
                    },
            )
        }
    }
}

@Composable
private fun FullCoverQueuePosition(
    currentIndex: Int,
    totalCount: Int,
) {
    Text(
        text = "${if (totalCount == 0) 0 else currentIndex + 1}/$totalCount",
        color = Color.White.copy(alpha = 0.90f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
