package com.rawsmusic.core.ui.scene.pages

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rawsmusic.core.common.utils.AppLogger
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.HomeFullCoverSourceAnchor
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.NativePlayerArtworkSwitchEasing
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.player.PORTRAIT_DIAL_VISIBLE_RADIUS
import com.rawsmusic.core.ui.widget.player.HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.resolvePortraitDialLaneTransform
import com.rawsmusic.core.ui.widget.player.resolvePortraitDialCardBoundsInRoot
import com.rawsmusic.core.ui.widget.player.resolvePortraitDialMetrics
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

private const val HomeCarouselHostIndexGuardMs = 4_000L
private const val HomeCarouselFirstTranslationFraction = 0.56f
private val HomeCarouselHostSwitchEasing = NativePlayerArtworkSwitchEasing
private const val HomeCarouselTraceTag = "HOME_CAROUSEL_TRACE"

private fun carouselQueueTrace(songs: List<AudioFile>): String =
    songs.mapIndexed { index, song ->
        "$index:${carouselSongIdentity(song).orEmpty().takeLast(28)}"
    }.joinToString(separator = "|", limit = 9, truncated = "...")

private fun sameCarouselQueue(left: List<AudioFile>, right: List<AudioFile>): Boolean =
    left.size == right.size && left.indices.all { index ->
        carouselSongIdentity(left[index]) == carouselSongIdentity(right[index])
    }

internal enum class HomeArtworkCarouselStyle(val value: Int) {
    CurrentCarousel(0),
    VerticalDial(1);

    companion object {
        fun from(value: Int): HomeArtworkCarouselStyle =
            entries.firstOrNull { it.value == value } ?: CurrentCarousel
    }
}

@Stable
class HomeArtworkCarouselState internal constructor(
    initialSongs: List<AudioFile>,
    initialCenterIndex: Int
) {
    internal var renderSongs by mutableStateOf(initialSongs)
    internal var centerIndex by mutableIntStateOf(initialCenterIndex.coerceAtLeast(0))
    internal var progress by mutableFloatStateOf(0f)
    internal var interactionActive by mutableStateOf(false)
    internal var hostTransitionActive by mutableStateOf(false)
    internal var fullCoverTransitionActive by mutableStateOf(false)
    internal var awaitingQueueIndex by mutableIntStateOf(-1)
    internal var awaitingSongIdentity by mutableStateOf<String?>(null)
    internal var hostIndexGuardUntilMs by mutableLongStateOf(0L)
    internal var transactionId by mutableLongStateOf(0L)
}

@Composable
internal fun rememberHomeArtworkCarouselState(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    reportedQueueIndex: Int
): HomeArtworkCarouselState {
    val hostSongs = songs.ifEmpty { listOfNotNull(currentSong) }
    val resolvedIndex = resolveCarouselCenterIndex(hostSongs, currentSong, reportedQueueIndex)
    val currentSongIdentity = carouselSongIdentity(currentSong)
    val state = remember { HomeArtworkCarouselState(hostSongs, resolvedIndex) }
    LaunchedEffect(
        resolvedIndex,
        hostSongs,
        currentSongIdentity,
        state.fullCoverTransitionActive,
        state.interactionActive
    ) {
        AppLogger.i(
            HomeCarouselTraceTag,
            "host_emit tx=${state.transactionId} resolved=$resolvedIndex reported=$reportedQueueIndex " +
                "current=${currentSongIdentity.orEmpty().takeLast(48)} awaiting=${state.awaitingSongIdentity?.takeLast(48)} " +
                "interaction=${state.interactionActive} renderCenter=${state.centerIndex} " +
                "hostQueue=${carouselQueueTrace(hostSongs)}"
        )
        val awaiting = state.awaitingQueueIndex
        val awaitingIdentity = state.awaitingSongIdentity
        if (awaiting >= 0 && awaitingIdentity != null) {
            val confirmedIndex = hostSongs.indexOfFirst {
                    carouselSongIdentity(it) == awaitingIdentity
                }
            val hostPointsAtConfirmedSong =
                confirmedIndex >= 0 &&
                    resolvedIndex == confirmedIndex &&
                    reportedQueueIndex == confirmedIndex
            if (currentSongIdentity == awaitingIdentity && hostPointsAtConfirmedSong) {
                // The gesture already rendered this song as the centre lane. Replace the frozen
                // queue only after both player identity and host queue cursor agree. The queue
                // briefly reports the old cursor during crossfade commit; accepting it causes the
                // visible B -> A -> B flash.
                Snapshot.withMutableSnapshot {
                    // A queue object is frequently republished while the player confirms a
                    // selection. Replacing an identity-equivalent list disposes and rebinds the
                    // seven Canvas bitmap holders at the exact commit frame, which is visible as
                    // the old cover flashing between two correct target frames.
                    if (!sameCarouselQueue(state.renderSongs, hostSongs)) {
                        state.renderSongs = hostSongs
                    }
                    state.awaitingQueueIndex = -1
                    state.awaitingSongIdentity = null
                    state.hostIndexGuardUntilMs = 0L
                    state.hostTransitionActive = false
                    state.centerIndex = confirmedIndex.coerceIn(
                        0,
                        hostSongs.lastIndex.coerceAtLeast(0)
                    )
                    state.progress = 0f
                }
                AppLogger.i(
                    HomeCarouselTraceTag,
                    "player_confirm tx=${state.transactionId} identity=${awaitingIdentity.takeLast(48)} " +
                        "confirmedIndex=$confirmedIndex queue=${carouselQueueTrace(hostSongs)}"
                )
                return@LaunchedEffect
            } else {
                if (currentSongIdentity == awaitingIdentity) {
                    AppLogger.i(
                        HomeCarouselTraceTag,
                        "player_identity_pending_host tx=${state.transactionId} " +
                            "expected=${awaitingIdentity.takeLast(48)} resolved=$resolvedIndex " +
                            "reported=$reportedQueueIndex confirmedIndex=$confirmedIndex"
                    )
                }
                val remaining = state.hostIndexGuardUntilMs - SystemClock.uptimeMillis()
                if (remaining > 0L) {
                    delay(remaining)
                    if (
                        state.awaitingQueueIndex == awaiting &&
                        state.awaitingSongIdentity == awaitingIdentity &&
                        !state.interactionActive
                    ) {
                        Snapshot.withMutableSnapshot {
                            state.awaitingQueueIndex = -1
                            state.awaitingSongIdentity = null
                            state.hostIndexGuardUntilMs = 0L
                            state.renderSongs = hostSongs
                            state.centerIndex = resolvedIndex.coerceIn(
                                0,
                                hostSongs.lastIndex.coerceAtLeast(0)
                            )
                            state.progress = 0f
                        }
                        AppLogger.w(
                            HomeCarouselTraceTag,
                            "player_timeout tx=${state.transactionId} expected=${awaitingIdentity.takeLast(48)} " +
                                "actual=${currentSongIdentity.orEmpty().takeLast(48)} fallback=$resolvedIndex"
                        )
                    }
                    return@LaunchedEffect
                }
                state.awaitingQueueIndex = -1
                state.awaitingSongIdentity = null
                state.hostIndexGuardUntilMs = 0L
            }
        }
        if (!state.interactionActive) {
            val previousIdentity = carouselSongIdentity(
                state.renderSongs.getOrNull(
                    state.centerIndex.coerceIn(0, state.renderSongs.lastIndex.coerceAtLeast(0))
                )
            )
            val previousIndexInHost = hostSongs.indexOfFirst {
                carouselSongIdentity(it) == previousIdentity
            }
            val targetIndex = resolvedIndex.coerceIn(0, hostSongs.lastIndex.coerceAtLeast(0))
            if (state.fullCoverTransitionActive) {
                // The home scene remains alive below the portrait dial. Keep its hidden centre lane
                // atomically aligned to the selected full-cover song so the final reveal cannot
                // rebind from an older queue item after the shared artwork reaches its anchor.
                Snapshot.withMutableSnapshot {
                    state.hostTransitionActive = false
                    state.renderSongs = hostSongs
                    state.centerIndex = targetIndex
                    state.progress = 0f
                }
                return@LaunchedEffect
            }
            if (previousIndexInHost < 0) {
                Snapshot.withMutableSnapshot {
                    state.renderSongs = hostSongs
                    state.centerIndex = targetIndex
                    state.progress = 0f
                }
                return@LaunchedEffect
            }
            state.renderSongs = hostSongs
            state.centerIndex = previousIndexInHost
            val direction = resolveCarouselHostDirection(
                oldIndex = previousIndexInHost,
                newIndex = targetIndex,
                size = hostSongs.size
            )
            if (direction == 0 || hostSongs.size <= 1) {
                Snapshot.withMutableSnapshot {
                    state.centerIndex = targetIndex
                    state.progress = 0f
                }
                return@LaunchedEffect
            }
            if (direction == Int.MIN_VALUE) {
                Snapshot.withMutableSnapshot {
                    state.centerIndex = targetIndex
                    state.progress = 0f
                }
                return@LaunchedEffect
            }

            state.hostTransitionActive = true
            try {
                val animation = Animatable(0f)
                animation.animateTo(
                    targetValue = direction.toFloat(),
                    animationSpec = tween(
                        durationMillis = MINI_PLAYER_SYNC_DURATION_MS,
                        easing = HomeCarouselHostSwitchEasing
                    )
                ) {
                    state.progress = value
                }
                Snapshot.withMutableSnapshot {
                    state.centerIndex = targetIndex
                    state.progress = 0f
                }
            } finally {
                state.hostTransitionActive = false
            }
        }
    }
    return state
}

@Composable
internal fun HomeArtworkCarouselBackdrop(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    state: HomeArtworkCarouselState,
    modifier: Modifier = Modifier
) {
    val availableSongs = state.renderSongs
    val flowMode = LocalRawFlowMode.current
    val direction = state.progress.sign.toInt()
    val absoluteProgress = abs(state.progress)
    val completedSteps = floor(absoluteProgress).toInt()
    val baseIndex = if (availableSongs.isEmpty()) {
        0
    } else {
        wrapCarouselIndex(
            state.centerIndex + direction * completedSteps,
            availableSongs.size
        )
    }
    val centerSong = availableSongs.getOrNull(baseIndex)
    val targetSong = if (
        direction == 0 ||
        availableSongs.size <= 1 ||
        absoluteProgress == completedSteps.toFloat()
    ) {
        null
    } else {
        availableSongs[wrapCarouselIndex(baseIndex + direction, availableSongs.size)]
    }
    val baseKey = centerSong.resolvePlaybackArtworkKey(null)
    val targetKey = targetSong.resolvePlaybackArtworkKey(null)
    val sameBackdrop = targetSong != null && targetKey == baseKey
    val blend = (absoluteProgress - completedSteps).coerceIn(0f, 1f)
    val targetAlpha = blend
    val baseArtwork = baseKey?.let { key ->
        BitmapProvider.peekThumbnail(key, 256, 256) ?: BitmapProvider.peekAny(key)
    }
    val targetArtwork = targetKey?.let { key ->
        BitmapProvider.peekThumbnail(key, 256, 256) ?: BitmapProvider.peekAny(key)
    }
    val nativeBlend = NativePlayerArtworkSwitchEasing.transform(targetAlpha)

    Box(modifier = modifier.fillMaxSize()) {
        key("home-carousel-bg:${baseKey.orEmpty()}") {
            RawFlowBackground(
                mode = flowMode,
                sourceCoverKey = baseKey,
                sourceArtwork = baseArtwork,
                modifier = Modifier
                    .fillMaxSize()
                    // The current scene is the opaque fallback while the target texture is built.
                    .graphicsLayer { alpha = 1f }
            )
        }
        if (targetSong != null && targetKey != baseKey) {
            key("home-carousel-bg:${targetKey.orEmpty()}") {
                RawFlowBackground(
                    mode = flowMode,
                    sourceCoverKey = targetKey,
                    fallbackSourceCoverKey = baseKey,
                    sourceArtwork = targetArtwork,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = nativeBlend }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.03f),
                        0.56f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.12f)
                    )
                )
        )
    }
}

@Composable
internal fun HomeArtworkCarousel(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    state: HomeArtworkCarouselState,
    style: HomeArtworkCarouselStyle,
    showLyrics: Boolean,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    onSelectSong: (List<AudioFile>, AudioFile, Int) -> Unit,
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    hideCenterForFullscreenTransition: Boolean = false,
    centerReflectionAlpha: Float = 1f,
    centerReflectionArtworkKey: String = "",
    modifier: Modifier = Modifier
) {
    val availableSongs = state.renderSongs
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var currentArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    val currentCanvasArtworkBoundsHandle = remember { HomeCanvasArtworkBoundsHandle() }
    var carouselHostBounds by remember { mutableStateOf<Rect?>(null) }
    SideEffect {
        state.fullCoverTransitionActive = hideCenterForFullscreenTransition
    }
    val lyricPositionMs = rememberHomeCarouselTimelinePosition(
        anchorPositionMs = playbackPositionMs,
        isPlaying = isPlaying,
        durationMs = currentSong?.duration ?: 0L
    )

    LaunchedEffect(style) {
        settleGeneration += 1
        settleJob?.cancel()
        settleJob = null
        state.progress = 0f
        state.interactionActive = false
    }

    fun settle(commitDirection: Int, velocityFractionPerSecond: Float) {
        settleGeneration += 1
        val generation = settleGeneration
        settleJob?.cancel()
        settleJob = scope.launch {
            state.interactionActive = true
            try {
                val destination = commitDirection.toFloat()
                val animation = Animatable(state.progress)
                val remaining = destination - state.progress
                val requestedVelocity = velocityFractionPerSecond.coerceIn(-2.4f, 2.4f)
                val directedVelocity = if (requestedVelocity * remaining > 0f) requestedVelocity else 0f
                if (style == HomeArtworkCarouselStyle.VerticalDial) {
                    val durationMs = (250f + 90f * abs(remaining)).toInt().coerceIn(230, 340)
                    animation.animateTo(
                        targetValue = destination,
                        animationSpec = tween(
                            durationMillis = durationMs,
                            easing = HomeCarouselHostSwitchEasing,
                        ),
                    ) {
                        state.progress = value
                    }
                } else {
                    animation.animateTo(
                        targetValue = destination,
                        animationSpec = tween(
                            durationMillis = if (commitDirection == 0) 220 else 300,
                            easing = HomeCarouselHostSwitchEasing
                        )
                    ) {
                        state.progress = value
                    }
                }
                if (generation != settleGeneration) return@launch
                if (commitDirection != 0 && availableSongs.size > 1) {
                    val targetIndex = wrapCarouselIndex(
                        state.centerIndex + commitDirection,
                        availableSongs.size
                    )
                    val targetSong = availableSongs[targetIndex]
                    // The geometry at progress +/-1 is identical to the new center at progress 0.
                    // Updating both values together avoids the old two-step snap/rebind frame.
                    Snapshot.withMutableSnapshot {
                        state.centerIndex = targetIndex
                        state.progress = 0f
                        state.awaitingQueueIndex = targetIndex
                        state.awaitingSongIdentity = carouselSongIdentity(targetSong)
                        state.transactionId += 1L
                        state.hostIndexGuardUntilMs =
                            SystemClock.uptimeMillis() + HomeCarouselHostIndexGuardMs
                    }
                    AppLogger.i(
                        HomeCarouselTraceTag,
                        "drag_commit tx=${state.transactionId} direction=$commitDirection " +
                            "target=$targetIndex identity=${state.awaitingSongIdentity?.takeLast(48)} " +
                            "queue=${carouselQueueTrace(availableSongs)}"
                    )
                    onSelectSong(availableSongs, targetSong, targetIndex)
                } else {
                    state.progress = 0f
                }
            } finally {
                if (generation == settleGeneration) {
                    state.interactionActive = false
                    settleJob = null
                }
            }
        }
    }

    var dragExtentPx by remember { mutableFloatStateOf(1f) }
    val dragState = rememberDraggableState { deltaPx ->
        if (availableSongs.size <= 1) return@rememberDraggableState
        state.progress = (state.progress - deltaPx / dragExtentPx.coerceAtLeast(1f))
            .coerceIn(-1f, 1f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
            .onGloballyPositioned { coordinates ->
                carouselHostBounds = coordinates.boundsInRoot()
            }
            .draggable(
                state = dragState,
                orientation = if (style == HomeArtworkCarouselStyle.VerticalDial) {
                    Orientation.Vertical
                } else {
                    Orientation.Horizontal
                },
                enabled = !state.hostTransitionActive,
                onDragStarted = {
                    settleGeneration += 1
                    settleJob?.cancel()
                    settleJob = null
                    state.interactionActive = true
                },
                onDragStopped = { velocityPxPerSecond ->
                    val normalizedVelocity =
                        -velocityPxPerSecond / dragExtentPx.coerceAtLeast(1f)
                    val direction = when {
                        state.progress >= 0.20f -> 1
                        state.progress <= -0.20f -> -1
                        normalizedVelocity >= 0.62f -> 1
                        normalizedVelocity <= -0.62f -> -1
                        else -> 0
                    }
                    settle(direction, normalizedVelocity)
                }
            )
            .pointerInput(
                style,
                state.centerIndex,
                state.progress,
                currentSong,
                currentArtworkBounds,
            ) {
                detectTapGestures(
                    onLongPress = { position ->
                        if (abs(state.progress) > 0.001f || state.interactionActive || state.hostTransitionActive) {
                            return@detectTapGestures
                        }
                        val currentIndex = resolveCarouselCenterIndex(
                            songs = availableSongs,
                            currentSong = currentSong,
                            reportedQueueIndex = state.centerIndex,
                        )
                        if (currentIndex != state.centerIndex) return@detectTapGestures
                        val hostBounds = carouselHostBounds ?: return@detectTapGestures
                        val artworkBounds = if (style == HomeArtworkCarouselStyle.CurrentCarousel) {
                            currentCanvasArtworkBoundsHandle.resolveInRoot() ?: currentArtworkBounds
                        } else {
                            currentArtworkBounds
                        } ?: return@detectTapGestures
                        val rootPosition = androidx.compose.ui.geometry.Offset(
                            x = hostBounds.left + position.x,
                            y = hostBounds.top + position.y,
                        )
                        if (artworkBounds.contains(rootPosition)) {
                            onCurrentArtworkLongPress(
                                HomeFullCoverSourceAnchor(
                                    boundsInRoot = artworkBounds,
                                    cornerRadiusDp =
                                        resolveHomeArtworkSourceCornerRadiusDp(style),
                                )
                            )
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (availableSongs.isEmpty()) return@BoxWithConstraints
        val containerWidth = maxWidth
        val containerWidthPx = with(density) { containerWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        dragExtentPx = if (style == HomeArtworkCarouselStyle.VerticalDial) {
            resolvePortraitDialMetrics(containerWidthPx, containerHeightPx).stridePx
        } else {
            containerWidthPx * HomeCarouselFirstTranslationFraction
        }.coerceAtLeast(1f)

        AnimatedContent(
            targetState = style,
            transitionSpec = {
                (fadeIn(tween(280, easing = HomeCarouselHostSwitchEasing)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(320, easing = HomeCarouselHostSwitchEasing))) togetherWith
                    (fadeOut(tween(220, easing = HomeCarouselHostSwitchEasing)) +
                        scaleOut(targetScale = 1.035f, animationSpec = tween(260, easing = HomeCarouselHostSwitchEasing)))
            },
            contentAlignment = Alignment.Center,
            label = "home-carousel-style",
            modifier = Modifier.fillMaxSize()
        ) { targetStyle ->
            when (targetStyle) {
                HomeArtworkCarouselStyle.CurrentCarousel -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HomeArtworkCarouselCanvas(
                            songs = availableSongs,
                            centerIndex = state.centerIndex,
                            progress = state.progress,
                            hideCenterLane = hideCenterForFullscreenTransition,
                            centerReflectionAlpha = centerReflectionAlpha,
                            centerReflectionArtworkKey = centerReflectionArtworkKey,
                            centerArtworkBoundsHandle = currentCanvasArtworkBoundsHandle,
                            onCenterArtworkBoundsChanged = { resolvedBounds ->
                                currentArtworkBounds = resolvedBounds
                                val centerSong = availableSongs.getOrNull(
                                    wrapCarouselIndex(state.centerIndex, availableSongs.size)
                                )
                                if (centerSong != null) {
                                    onCurrentArtworkBoundsChanged(centerSong, resolvedBounds)
                                }
                            },
                            modifier = Modifier
                                .requiredWidth(containerWidth + 32.dp)
                                .fillMaxHeight()
                                .align(Alignment.TopCenter)
                        )
                        if (showLyrics) {
                            HomeHorizontalCarouselLyric(
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                fallbackPrimary = currentLyric,
                                fallbackTranslation = currentLyricTranslation,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .zIndex(100f)
                            )
                        }
                    }
                }

                HomeArtworkCarouselStyle.VerticalDial -> {
                    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                        HomeArtworkDial(
                            songs = availableSongs,
                            centerIndex = state.centerIndex,
                            progress = state.progress,
                            hideCenterLane = hideCenterForFullscreenTransition,
                            onCurrentArtworkBoundsChanged = { song, bounds ->
                                currentArtworkBounds = bounds
                                onCurrentArtworkBoundsChanged(song, bounds)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (showLyrics) {
                            HomeVerticalDialLyric(
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                fallbackPrimary = currentLyric,
                                fallbackTranslation = currentLyricTranslation,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(100f)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun rememberHomeCarouselTimelinePosition(
    anchorPositionMs: Long,
    isPlaying: Boolean,
    durationMs: Long
): Long {
    val safeAnchor = anchorPositionMs.coerceAtLeast(0L)
    val anchorRealtimeMs = remember(safeAnchor, isPlaying) { SystemClock.elapsedRealtime() }
    val animatedPosition by produceState(
        initialValue = safeAnchor,
        key1 = safeAnchor,
        key2 = isPlaying,
        key3 = durationMs
    ) {
        fun clamp(positionMs: Long): Long = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }

        value = clamp(safeAnchor)
        if (!isPlaying) return@produceState

        while (true) {
            withFrameNanos { }
            val elapsedMs = (SystemClock.elapsedRealtime() - anchorRealtimeMs).coerceAtLeast(0L)
            val next = clamp(safeAnchor + elapsedMs)
            if (next != value) value = next
            if (durationMs > 0L && next >= durationMs) break
        }
    }
    return animatedPosition
}

@Composable
private fun HomeArtworkDial(
    songs: List<AudioFile>,
    centerIndex: Int,
    progress: Float,
    hideCenterLane: Boolean,
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                if (songs.isNotEmpty() && abs(progress) < 0.02f) {
                    val rootBounds = coordinates.boundsInRoot()
                    val cardBounds = resolvePortraitDialCardBoundsInRoot(
                        containerLeftInRootPx = rootBounds.left,
                        containerTopInRootPx = rootBounds.top,
                        viewportWidthPx = coordinates.size.width.toFloat(),
                        viewportHeightPx = coordinates.size.height.toFloat(),
                    )
                    val songIndex = wrapCarouselIndex(centerIndex, songs.size)
                    val song = songs[songIndex]
                    onCurrentArtworkBoundsChanged(
                        song,
                        Rect(
                            left = cardBounds.leftPx,
                            top = cardBounds.topPx,
                            right = cardBounds.leftPx + cardBounds.widthPx,
                            bottom = cardBounds.topPx + cardBounds.heightPx,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (songs.isEmpty()) return@BoxWithConstraints
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val metrics = resolvePortraitDialMetrics(widthPx, heightPx)
        val cardSide = with(density) { metrics.cardSidePx.toDp() }
        val corner = HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP.dp

        for (logicalOffset in -PORTRAIT_DIAL_VISIBLE_RADIUS..PORTRAIT_DIAL_VISIBLE_RADIUS) {
            val position = logicalOffset.toFloat() - progress
            val songIndex = wrapCarouselIndex(centerIndex + logicalOffset, songs.size)
            val song = songs[songIndex]
            val artworkKey = song.resolvePlaybackArtworkKey(null).orEmpty()
            val transform = resolvePortraitDialLaneTransform(position, widthPx, heightPx)
            val virtualQueueIndex = centerIndex + logicalOffset
            val stableLaneKey =
                "home-dial:$virtualQueueIndex:$songIndex:${carouselSongIdentity(song)}"
            val hiddenCenter = hideCenterLane && abs(position) < 0.02f

            key(stableLaneKey) {
                Box(
                    modifier = Modifier
                        .size(cardSide)
                        .zIndex(transform.zIndex)
                        .graphicsLayer {
                            translationY = transform.translationYPx
                            translationX = transform.translationXPx
                            scaleX = transform.scale
                            scaleY = transform.scale
                            alpha = if (hiddenCenter) 0f else transform.alpha
                            rotationX = transform.rotationX
                            rotationZ = transform.rotationZ
                            cameraDistance = 36f * density.density
                            shape = RoundedCornerShape(corner)
                            clip = true
                        },
                ) {
                    if (artworkKey.isNotBlank()) {
                        BitmapImage(
                            key = artworkKey,
                            contentDescription = song.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            targetWidth = 1024,
                            targetHeight = 1024,
                            priority = BitmapRequest.Priority.LOADING_WIDGET,
                            holdPreviousOnKeyChange = false,
                            fadeInMillis = 0,
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
        }
    }
}

private const val MINI_PLAYER_SYNC_DURATION_MS = 360

private fun resolveCarouselHostDirection(
    oldIndex: Int,
    newIndex: Int,
    size: Int
): Int {
    if (size <= 1 || oldIndex == newIndex) return 0
    if ((oldIndex + 1) % size == newIndex) return 1
    if ((oldIndex - 1 + size) % size == newIndex) return -1
    return Int.MIN_VALUE
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun resolveCarouselCenterIndex(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    reportedQueueIndex: Int
): Int {
    if (songs.isEmpty()) return 0
    // Queue selection is committed before the decoder-owned currentSong changes. During a manual
    // switch the backend may briefly publish the retiring song again, so using currentSong first
    // makes the carousel render target -> old -> target. The queue index is the transport's
    // authoritative selection and remains stable throughout that hand-off.
    if (reportedQueueIndex in songs.indices) return reportedQueueIndex
    val exactIndex = currentSong?.let { current ->
        songs.indexOfFirst { candidate ->
            candidate.path == current.path &&
                candidate.cueOffsetMs == current.cueOffsetMs &&
                candidate.cueTrackIndex == current.cueTrackIndex
        }
    } ?: -1
    return when {
        exactIndex in songs.indices -> exactIndex
        else -> 0
    }
}

private fun carouselSongIdentity(song: AudioFile?): String? {
    if (song == null) return null
    return "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"
}

private fun wrapCarouselIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}
