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
import androidx.compose.foundation.layout.offset
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
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.NativePlayerArtworkSwitchEasing
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.player.PORTRAIT_DIAL_VISIBLE_RADIUS
import com.rawsmusic.core.ui.widget.player.HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
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

enum class HomeArtworkCarouselStyle(val value: Int) {
    CurrentCarousel(0),
    VerticalDial(1);

    companion object {
        fun from(value: Int): HomeArtworkCarouselStyle =
            entries.firstOrNull { it.value == value } ?: CurrentCarousel
    }
}

private fun homeArtworkCarouselHorizontalLyricOffset(
    style: HomeArtworkCarouselStyle,
    showLyrics: Boolean,
    fontScale: Float,
): androidx.compose.ui.unit.Dp = if (
    showLyrics && style == HomeArtworkCarouselStyle.CurrentCarousel
) {
    28.dp + (40.dp * (fontScale - 1f).coerceIn(0f, 0.5f))
} else {
    0.dp
}

internal fun homeArtworkCarouselHeight(
    style: HomeArtworkCarouselStyle,
    showLyrics: Boolean,
    fontScale: Float,
): androidx.compose.ui.unit.Dp = 340.dp + homeArtworkCarouselHorizontalLyricOffset(
    style = style,
    showLyrics = showLyrics,
    fontScale = fontScale,
)

@Stable
class HomeArtworkCarouselState internal constructor(
    initialSongs: List<AudioFile>,
    initialCenterIndex: Int
) {
    internal var renderSongs by mutableStateOf(initialSongs)
    internal var centerIndex by mutableIntStateOf(initialCenterIndex.coerceAtLeast(0))
    // Visual preview may move arbitrarily far from playback during one uninterrupted gesture.
    // Keep the last player-confirmed queue slot separate so preview never becomes transport truth.
    internal var committedIndex by mutableIntStateOf(initialCenterIndex.coerceAtLeast(0))
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
    // Playback commit is authoritative. The queue cursor may move one emission before currentSong
    // (or vice versa), so never let either half of that pair alone move the committed carousel.
    // A preview gesture owns only centerIndex/progress; the player-confirmed song owns committedIndex.
    val queueCurrentSong = songs.getOrNull(reportedQueueIndex)
    val snapshotCurrentSong = currentSong ?: queueCurrentSong
    val hostSongs = songs.ifEmpty { listOfNotNull(snapshotCurrentSong) }
    val resolvedIndex = resolveCarouselCenterIndex(
        hostSongs,
        currentSong,
        reportedQueueIndex,
    )
    val currentSongIdentity = carouselSongIdentity(currentSong)
    val queueSongIdentity = carouselSongIdentity(queueCurrentSong)
    val committedSnapshotReady = currentSongIdentity == null ||
        reportedQueueIndex !in songs.indices ||
        queueSongIdentity == currentSongIdentity
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
            val confirmedIndex = reportedQueueIndex.takeIf { index ->
                carouselSongIdentity(hostSongs.getOrNull(index)) == awaitingIdentity
            } ?: hostSongs.indexOfFirst {
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
                    state.committedIndex = confirmedIndex.coerceIn(
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
                            if (!sameCarouselQueue(state.renderSongs, hostSongs)) {
                                state.renderSongs = hostSongs
                            }
                            state.centerIndex = resolvedIndex.coerceIn(
                                0,
                                hostSongs.lastIndex.coerceAtLeast(0)
                            )
                            state.committedIndex = resolvedIndex.coerceIn(
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
            // Ignore a half-published player snapshot. This is the remaining B -> A -> B source:
            // queueCurrentIndex can already point at B while currentSong is still A. The visual
            // centre/background must remain on the last committed owner until both agree.
            if (!committedSnapshotReady) return@LaunchedEffect
            val targetIndex = resolvedIndex.coerceIn(0, hostSongs.lastIndex.coerceAtLeast(0))
            // Gesture settle already animated the carousel before transport submission. Running a
            // second host animation when the queue confirms the same selection briefly resurrects
            // the retiring centre lane (target -> previous -> target). External button/natural
            // changes also arrive as a committed queue snapshot, so bind them atomically here.
            Snapshot.withMutableSnapshot {
                state.hostTransitionActive = false
                if (!sameCarouselQueue(state.renderSongs, hostSongs)) {
                    state.renderSongs = hostSongs
                }
                state.centerIndex = targetIndex
                state.committedIndex = targetIndex
                state.progress = 0f
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
    active: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val flowMode = LocalRawFlowMode.current
    // Carousel drag is a visual preview only. The old implementation blended the background from
    // state.progress, then the transport emitted its still-old current song, and finally committed
    // the target: new -> old -> new. Keep the backdrop on the authoritative player song for the
    // complete gesture/settle transaction. RawFlowBackground performs its own palette handoff once
    // currentSong actually commits.
    val committedSong = currentSong ?: state.renderSongs.getOrNull(state.committedIndex)
    val committedKey = committedSong.resolvePlaybackArtworkKey(null)
    val lastCommittedKey = remember { arrayOf<String?>(committedKey) }
    val fallbackKey = remember(committedKey) {
        val previous = lastCommittedKey[0]
        lastCommittedKey[0] = committedKey
        previous?.takeIf { it != committedKey }
    }

    Box(modifier = modifier.fillMaxSize()) {
        RawFlowBackground(
            mode = flowMode,
            sourceCoverKey = committedKey,
            fallbackSourceCoverKey = fallbackKey,
            sourceArtwork = null,
            modifier = Modifier.fillMaxSize(),
            active = active,
            motionEnabled = motionEnabled,
        )
        // the retained-view implementation's static static-artwork/artwork background is one continuous renderer surface.  Do not add a
        // HOME-only vignette in STATIC mode: removing that extra dark layer during HOME -> library
        // navigation is perceived as a full-screen white/brightness flash even when artwork pixels did
        // not change.
        if (!usesReferenceStaticForeground()) {
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
}

@Composable
internal fun HomeArtworkCarousel(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    state: HomeArtworkCarouselState,
    style: HomeArtworkCarouselStyle,
    showLyrics: Boolean,
    gestureLocked: Boolean = false,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    onNavigate: (Int) -> Unit,
    onSelectSong: (AudioFile, Int) -> Unit = { _, _ -> },
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    hideCenterForFullscreenTransition: Boolean = false,
    centerReflectionAlpha: Float = 1f,
    centerReflectionArtworkKey: String = "",
    hostFrozen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val availableSongs = state.renderSongs
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val horizontalLyricArtworkOffset = homeArtworkCarouselHorizontalLyricOffset(
        style = style,
        showLyrics = showLyrics,
        fontScale = fontScale,
    )
    val carouselHeight = homeArtworkCarouselHeight(
        style = style,
        showLyrics = showLyrics,
        fontScale = fontScale,
    )
    var currentArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    val currentCanvasArtworkBoundsHandle = remember { HomeCanvasArtworkBoundsHandle() }
    var carouselHostBounds by remember { mutableStateOf<Rect?>(null) }
    SideEffect {
        state.fullCoverTransitionActive = hideCenterForFullscreenTransition
    }
    val lyricPositionMs = playbackPositionMs
    val lyricRenderClock = com.rawsmusic.core.ui.widget.player.rememberLyricDirectRenderClock(
        positionMs = playbackPositionMs,
        isPlaying = isPlaying && !hostFrozen && frameAnimationActive,
        durationMs = currentSong?.duration ?: 0L
    )
    val lyricAlbumAccent = rememberCoverAccentColor(
        currentSong.resolvePlaybackArtworkKey(null),
    )

    LaunchedEffect(hostFrozen) {
        if (hostFrozen) {
            settleGeneration += 1
            settleJob?.cancel()
            settleJob = null
            state.interactionActive = false
        }
    }

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
                if (availableSongs.isNotEmpty()) {
                    val targetIndex = if (commitDirection != 0 && availableSongs.size > 1) {
                        wrapCarouselIndex(state.centerIndex + commitDirection, availableSongs.size)
                    } else {
                        state.centerIndex.coerceIn(0, availableSongs.lastIndex)
                    }
                    val targetSong = availableSongs[targetIndex]
                    val targetIdentity = carouselSongIdentity(targetSong)
                    val committedSong = availableSongs.getOrNull(state.committedIndex)
                    val committedIdentity = carouselSongIdentity(committedSong)
                    // progress +/-1 and the rebased next centre at progress 0 are exactly the same
                    // geometry. Rebase first, then submit only the final previewed item once.
                    Snapshot.withMutableSnapshot {
                        state.centerIndex = targetIndex
                        state.progress = 0f
                        if (targetIdentity != committedIdentity) {
                            state.awaitingQueueIndex = targetIndex
                            state.awaitingSongIdentity = targetIdentity
                            state.transactionId += 1L
                            state.hostIndexGuardUntilMs =
                                SystemClock.uptimeMillis() + HomeCarouselHostIndexGuardMs
                        }
                    }
                    if (targetIdentity != committedIdentity) {
                        AppLogger.i(
                            HomeCarouselTraceTag,
                            "drag_commit tx=${state.transactionId} direction=$commitDirection " +
                                "target=$targetIndex identity=${targetIdentity?.takeLast(48)} " +
                                "queue=${carouselQueueTrace(availableSongs)}"
                        )
                        onSelectSong(targetSong, targetIndex)
                    }
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
        var nextProgress = state.progress - deltaPx / dragExtentPx.coerceAtLeast(1f)
        if (gestureLocked) {
            // Locked mode preserves the centre identity for the entire pointer stream. The rail can
            // approach either adjacent item but never rebases while the finger is still down, so a
            // single long/fast swipe can commit at most one neighbour on release.
            state.progress = nextProgress.coerceIn(-0.999f, 0.999f)
            return@rememberDraggableState
        }
        // Rebase at every full rail without ending the pointer gesture. The centre identity changes
        // only in the visual carousel; playback/background remain on committedIndex/currentSong.
        // Because rail +/-1 is pixel-identical to the adjacent centre at progress 0, this rebase has
        // no visible seam and permits one continuous swipe across an arbitrary number of songs.
        while (nextProgress >= 1f) {
            state.centerIndex = wrapCarouselIndex(state.centerIndex + 1, availableSongs.size)
            nextProgress -= 1f
        }
        while (nextProgress <= -1f) {
            state.centerIndex = wrapCarouselIndex(state.centerIndex - 1, availableSongs.size)
            nextProgress += 1f
        }
        state.progress = nextProgress.coerceIn(-0.999f, 0.999f)
    }

    fun Modifier.carouselDrag(orientation: Orientation): Modifier = draggable(
        state = dragState,
        orientation = orientation,
        enabled = !state.hostTransitionActive && !hostFrozen,
        onDragStarted = {
            settleGeneration += 1
            settleJob?.cancel()
            settleJob = null
            state.interactionActive = true
        },
        onDragStopped = { velocityPxPerSecond ->
            val normalizedVelocity = -velocityPxPerSecond / dragExtentPx.coerceAtLeast(1f)
            val direction = when {
                state.progress >= 0.20f -> 1
                state.progress <= -0.20f -> -1
                normalizedVelocity >= 0.62f -> 1
                normalizedVelocity <= -0.62f -> -1
                else -> 0
            }
            settle(direction, normalizedVelocity)
        },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(carouselHeight)
            .onGloballyPositioned { coordinates ->
                carouselHostBounds = coordinates.boundsInRoot()
            }
            // The horizontal carousel intentionally owns its whole strip. The portrait dial does
            // not: a vertical drag starting in the lyric/empty area used to switch tracks and made
            // HOME feel globally swipe-sensitive. Its drag owner is attached to the centre artwork
            // hit region inside the VerticalDial branch below.
            .then(
                if (style == HomeArtworkCarouselStyle.VerticalDial) Modifier
                else Modifier.carouselDrag(Orientation.Horizontal)
            )
            .pointerInput(
                style,
                state.centerIndex,
                currentSong,
                currentArtworkBounds,
            ) {
                detectTapGestures(
                    onLongPress = { position ->
                        if (hostFrozen || abs(state.progress) > 0.001f || state.interactionActive ||
                            state.hostTransitionActive || state.awaitingQueueIndex >= 0
                        ) {
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
                            progressProvider = { state.progress },
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
                                .offset(y = horizontalLyricArtworkOffset)
                                .align(Alignment.TopCenter)
                        )
                        if (showLyrics) {
                            HomeHorizontalCarouselLyric(
                                renderClock = lyricRenderClock,
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                fallbackPrimary = currentLyric,
                                fallbackTranslation = currentLyricTranslation,
                                albumAccent = lyricAlbumAccent,
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
                        val dialGestureSide = with(density) {
                            resolvePortraitDialMetrics(containerWidthPx, containerHeightPx).cardSidePx.toDp()
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(dialGestureSide)
                                .zIndex(90f)
                                .carouselDrag(Orientation.Vertical),
                        )
                        if (showLyrics) {
                            HomeVerticalDialLyric(
                                renderClock = lyricRenderClock,
                                song = lyricSong,
                                positionMs = lyricPositionMs,
                                fallbackPrimary = currentLyric,
                                fallbackTranslation = currentLyricTranslation,
                                albumAccent = lyricAlbumAccent,
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
            val hiddenCenter = hideCenterLane && abs(position) < 0.02f

            // Keep the five visible physical lanes alive. The queue/song identity is bound to the
            // BitmapImage inside the lane; using it as the Compose key recreates every card on a
            // commit and releases its old artwork before the replacement arrives.
            key("home-dial-lane-$logicalOffset") {
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
                            // Keep the physical dial lane mounted while its source changes. The
                            // provider replaces the bitmap atomically, preserving a stable
                            // artwork holder instead of exposing an empty transition frame.
                    // Every physical lane is reused for another queue item while swiping. Keeping
                    // its previous request as a placeholder produces target -> old -> target
                    // frames even when the target is already cached.
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
    val currentIdentity = carouselSongIdentity(currentSong)
    if (currentIdentity != null) {
        // Fast path when both halves of the committed player snapshot already agree.
        if (
            reportedQueueIndex in songs.indices &&
            carouselSongIdentity(songs[reportedQueueIndex]) == currentIdentity
        ) {
            return reportedQueueIndex
        }
        // If queueIndex is one emission early/late, retain the committed song's logical slot.
        val identityIndex = songs.indexOfFirst { carouselSongIdentity(it) == currentIdentity }
        if (identityIndex >= 0) return identityIndex
    }
    if (reportedQueueIndex in songs.indices) return reportedQueueIndex
    return 0
}

private fun carouselSongIdentity(song: AudioFile?): String? {
    if (song == null) return null
    return "${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"
}

private fun wrapCarouselIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}
