package com.rawsmusic.core.ui.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.player.FULLSCREEN_PORTRAIT_DIAL_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.PortraitDialFullCoverPage
import com.rawsmusic.core.ui.widget.player.StandardPlayerBackdrop
import com.rawsmusic.core.ui.widget.player.resolveFullCoverPredictiveProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the portrait dial opened from the home artwork long press.
 *
 * The transition intentionally mirrors the proven landscape player/full-cover path: one real shared
 * artwork lane interpolates between the measured source holder and the exact portrait-dial centre;
 * source-only and target-only scene content scale/fade around it; target side lanes reveal along
 * their PowerList depth track. The shared lane exists only while the scene is moving, so settled
 * states return ownership atomically to the real home/full-screen holders.
 */
@Composable
internal fun HomeFullCoverTransitionHost(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    queueCurrentIndex: Int,
    onSelectSong: (List<AudioFile>, AudioFile, Int) -> Unit,
    onActiveChange: (Boolean) -> Unit = {},
    onExternalOpen: ((Rect) -> Boolean)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (
        foregroundModifier: Modifier,
        fullCoverActive: Boolean,
        homeCenterReflectionAlpha: Float,
        homeCenterReflectionArtworkKey: String,
        onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit,
        onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit,
    ) -> Unit,
) {
    val availableSongs = remember(songs, currentSong) {
        songs.ifEmpty { listOfNotNull(currentSong) }
    }
    val dialArtworkTransitionState = rememberPlaybackArtworkTransitionState(
        currentKey = currentSong.resolvePlaybackArtworkKey(null),
        queueCurrentIndex = queueCurrentIndex,
        queueSize = availableSongs.size,
    )
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var fullCoverVisible by remember { mutableStateOf(false) }
    var transitionRunning by remember { mutableStateOf(false) }
    var transitionProgress by remember { mutableFloatStateOf(0f) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackStartProgress by remember { mutableFloatStateOf(0f) }
    var hostBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var sourceBoundsInHost by remember { mutableStateOf<Rect?>(null) }
    var sourceCornerRadiusDp by remember { mutableFloatStateOf(0f) }
    var targetBoundsInHost by remember { mutableStateOf<Rect?>(null) }
    var pendingOpenAnimation by remember { mutableStateOf(false) }
    var sharedSceneArtworkKey by remember { mutableStateOf("") }
    var fullscreenCenterArtworkKey by remember { mutableStateOf("") }
    var fullscreenCenterArtworkDescription by remember { mutableStateOf<String?>(null) }
    var returningToHome by remember { mutableStateOf(false) }
    val backOwner = remember { Any() }

    fun finishClosed() {
        transitionProgress = 0f
        predictiveBackActive = false
        transitionRunning = false
        fullCoverVisible = false
        sourceBoundsInHost = null
        sourceCornerRadiusDp = 0f
        targetBoundsInHost = null
        pendingOpenAnimation = false
        sharedSceneArtworkKey = ""
        fullscreenCenterArtworkKey = ""
        fullscreenCenterArtworkDescription = null
        returningToHome = false
        onActiveChange(false)
    }

    fun animateSceneTo(target: Float, baseDurationMs: Int, onFinished: () -> Unit = {}) {
        val startValue = transitionProgress.coerceIn(0f, 1f)
        transitionJob?.cancel()
        transitionRunning = true
        transitionJob = scope.launch {
            val ownerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            val animation = Animatable(startValue)
            var completed = false
            try {
                val distance = abs(target - startValue).coerceIn(0f, 1f)
                animation.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = (baseDurationMs * distance)
                            .roundToInt()
                            .coerceIn(72, baseDurationMs),
                        easing = FastOutSlowInEasing,
                    ),
                ) {
                    transitionProgress = value
                }
                transitionProgress = target
                completed = true
            } finally {
                if (transitionJob == ownerJob) {
                    // Release transition ownership before mutating the settled scene. Step95 cleared
                    // the source/full-cover state while transitionRunning was still true, so the
                    // shared overlay disappeared in a different frame from the real holder reveal.
                    transitionRunning = false
                    transitionJob = null
                    if (completed) onFinished()
                }
            }
        }
    }

    fun open(sourceAnchor: HomeFullCoverSourceAnchor) {
        val sourceBoundsInRoot = sourceAnchor.boundsInRoot
        if (
            fullCoverVisible || currentSong == null ||
            sourceBoundsInRoot.width <= 1f || sourceBoundsInRoot.height <= 1f
        ) {
            return
        }
        if (onExternalOpen?.invoke(sourceBoundsInRoot) == true) return
        val hostRoot = hostBoundsInRoot ?: return
        val sourceLocal = Rect(
            left = sourceBoundsInRoot.left - hostRoot.left,
            top = sourceBoundsInRoot.top - hostRoot.top,
            right = sourceBoundsInRoot.right - hostRoot.left,
            bottom = sourceBoundsInRoot.bottom - hostRoot.top,
        )
        if (sourceLocal.width <= 1f || sourceLocal.height <= 1f) return

        val sceneArtworkKey = currentSong.resolvePlaybackArtworkKey(null).orEmpty()
        transitionJob?.cancel()
        transitionJob = null
        sourceBoundsInHost = sourceLocal
        sourceCornerRadiusDp = sourceAnchor.cornerRadiusDp.coerceAtLeast(0f)
        targetBoundsInHost = null
        pendingOpenAnimation = true
        sharedSceneArtworkKey = sceneArtworkKey
        fullscreenCenterArtworkKey = sceneArtworkKey
        fullscreenCenterArtworkDescription = currentSong.displayName
        returningToHome = false
        // Start the fullscreen decode before ownership moves to the overlay. The home holder may
        // only have a low/medium cache entry when the user long-presses immediately after launch.
        if (homePortraitDialSharedArtworkPolicy.prewarmFullCover) {
            BitmapProvider.warmFullCoverArt(sceneArtworkKey)
        }
        predictiveBackActive = false
        transitionProgress = 0f
        // Own the centre lane immediately, but keep it exactly on the measured home holder until
        // the real full-screen centre holder reports its host-local rectangle. This avoids the
        // one-frame synthetic target correction that caused the visible right/down nudge.
        transitionRunning = true
        fullCoverVisible = true
        onActiveChange(true)
    }

    fun updateFullscreenCenterBounds(song: AudioFile, boundsInRoot: Rect) {
        if (!fullCoverVisible || boundsInRoot.width <= 1f || boundsInRoot.height <= 1f) return
        // The full-screen dial commits its visual centre before PlayerController is guaranteed to
        // emit the new currentSong. Treat this holder as the source of truth for the return artwork;
        // filtering it against currentSong preserved the entry track and produced the wrong cover.
        fullscreenCenterArtworkKey = song.resolvePlaybackArtworkKey(null).orEmpty()
        fullscreenCenterArtworkDescription = song.displayName
        val hostRoot = hostBoundsInRoot ?: return
        val local = Rect(
            left = boundsInRoot.left - hostRoot.left,
            top = boundsInRoot.top - hostRoot.top,
            right = boundsInRoot.right - hostRoot.left,
            bottom = boundsInRoot.bottom - hostRoot.top,
        )
        if (local.width <= 1f || local.height <= 1f) return
        targetBoundsInHost = local
        if (pendingOpenAnimation && transitionProgress <= 0.001f) {
            pendingOpenAnimation = false
            animateSceneTo(target = 1f, baseDurationMs = 360)
        }
    }

    fun close() {
        if (!fullCoverVisible && transitionProgress <= 0.001f) return
        val sceneArtworkKey = resolveHomePortraitDialReturnArtworkKey(
            fullscreenCenterArtworkKey = fullscreenCenterArtworkKey,
            currentArtworkKey = currentSong.resolvePlaybackArtworkKey(null).orEmpty(),
        )
        sharedSceneArtworkKey = sceneArtworkKey
        returningToHome = true
        if (homePortraitDialSharedArtworkPolicy.prewarmFullCover) {
            BitmapProvider.warmFullCoverArt(sceneArtworkKey)
        }
        pendingOpenAnimation = false
        predictiveBackActive = false
        animateSceneTo(target = 0f, baseDurationMs = 320, onFinished = ::finishClosed)
    }

    fun beginPredictiveBack() {
        if (!fullCoverVisible && transitionProgress <= 0.001f) return
        if (!predictiveBackActive) {
            val sceneArtworkKey = resolveHomePortraitDialReturnArtworkKey(
                fullscreenCenterArtworkKey = fullscreenCenterArtworkKey,
                currentArtworkKey = currentSong.resolvePlaybackArtworkKey(null).orEmpty(),
            )
            sharedSceneArtworkKey = sceneArtworkKey
            returningToHome = true
            if (homePortraitDialSharedArtworkPolicy.prewarmFullCover) {
                BitmapProvider.warmFullCoverArt(sceneArtworkKey)
            }
            transitionJob?.cancel()
            transitionJob = null
            predictiveBackStartProgress = transitionProgress.coerceIn(0f, 1f)
            predictiveBackActive = true
            transitionRunning = true
        }
    }

    fun updatePredictiveBack(backProgress: Float) {
        beginPredictiveBack()
        transitionProgress = resolveFullCoverPredictiveProgress(
            predictiveBackStartProgress,
            backProgress,
        )
    }

    fun cancelPredictiveBack() {
        val current = transitionProgress
        predictiveBackActive = false
        returningToHome = false
        transitionProgress = current
        animateSceneTo(target = 1f, baseDurationMs = 320)
    }

    fun completePredictiveBack() {
        predictiveBackActive = false
        close()
    }

    val showFullscreenScene = fullCoverVisible || transitionRunning || predictiveBackActive ||
        transitionProgress > 0.001f
    val sharedArtworkOwned = transitionRunning || predictiveBackActive

    DisposableEffect(backOwner) {
        HomeFullCoverBackRuntime.register(
            owner = backOwner,
            callbacks = HomeFullCoverBackRuntime.Callbacks(
                onStarted = ::beginPredictiveBack,
                onProgressed = ::updatePredictiveBack,
                onCancelled = ::cancelPredictiveBack,
                onCompleted = ::completePredictiveBack,
            ),
        )
        onDispose {
            HomeFullCoverBackRuntime.unregister(backOwner)
            transitionJob?.cancel()
            onActiveChange(false)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                hostBoundsInRoot = coordinates.boundsInRoot()
            },
    ) {
        val sceneFrame = sourceBoundsInHost?.let { source ->
            // Before the target holder is laid out, source is also the temporary target. The shared
            // lane therefore stays exactly over the pressed home holder instead of jumping toward a
            // viewport-derived approximation.
            val target = targetBoundsInHost ?: source
            resolveHomePortraitDialSceneFrame(
                sourceLeftPx = source.left,
                sourceTopPx = source.top,
                sourceWidthPx = source.width,
                sourceHeightPx = source.height,
                targetLeftPx = target.left,
                targetTopPx = target.top,
                targetWidthPx = target.width,
                targetHeightPx = target.height,
                sourceCornerRadiusDp = sourceCornerRadiusDp,
                targetCornerRadiusDp = FULLSCREEN_PORTRAIT_DIAL_CORNER_RADIUS_DP,
                progress = transitionProgress,
            )
        }
        val homeAlpha = sceneFrame?.homeForegroundAlpha ?: 1f
        val homeScale = sceneFrame?.homeForegroundScale ?: 1f
        val fullscreenBackdropAlpha = sceneFrame?.fullscreenBackdropAlpha
            ?: transitionProgress.coerceIn(0f, 1f)
        val fullscreenContentAlpha = sceneFrame?.fullscreenContentAlpha
            ?: transitionProgress.coerceIn(0f, 1f)
        val fullscreenContentScale = sceneFrame?.fullscreenContentScale ?: 1f
        val targetLaneReveal = sceneFrame?.targetLaneRevealProgress
            ?: transitionProgress.coerceIn(0f, 1f)
        val homeCenterReflectionAlpha = if (showFullscreenScene) {
            resolveHomePortraitDialReturnReflectionAlpha(
                sceneProgress = transitionProgress,
                returningToHome = returningToHome,
            )
        } else {
            1f
        }

        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = homeAlpha
                    scaleX = homeScale
                    scaleY = homeScale
                },
            showFullscreenScene,
            homeCenterReflectionAlpha,
            if (returningToHome) sharedSceneArtworkKey else "",
            ::open,
            { _, _ ->
                // The source rectangle is captured once by the long-press event. Closing reuses the
                // exact same host-local rectangle, matching the landscape player/full-cover path.
            },
        )

        if (showFullscreenScene) {
            StandardPlayerBackdrop(
                coverPath = currentSong.resolvePlaybackArtworkKey(null),
                accent = Color.Transparent,
                artworkTransitionState = dialArtworkTransitionState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fullscreenBackdropAlpha },
            )
            PortraitDialFullCoverPage(
                currentSong = currentSong,
                queueSongs = availableSongs,
                queueCurrentIndex = queueCurrentIndex,
                onQueueSongClick = { song, index ->
                    onSelectSong(availableSongs, song, index)
                },
                onBackgroundTransitionPrepare = { song, index, directionSign ->
                    dialArtworkTransitionState.prepare(
                        direction = if (directionSign >= 0) {
                            PlayerArtworkDirection.Next
                        } else {
                            PlayerArtworkDirection.Previous
                        },
                        expectedKey = song.resolvePlaybackArtworkKey(null),
                        expectedQueueIndex = index,
                    )
                },
                onCenterArtworkBoundsChanged = ::updateFullscreenCenterBounds,
                onBack = ::close,
                sceneRevealProgress = targetLaneReveal,
                sceneContentScale = fullscreenContentScale,
                hideCenterForSceneTransition = sharedArtworkOwned,
                sceneInteractionEnabled = !transitionRunning &&
                    !predictiveBackActive && transitionProgress >= 0.999f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Keep the page root unscaled so its settled centre rectangle remains the
                        // target geometry. The restored target-scene scale is applied inside the
                        // page after layout and therefore cannot feed back into endpoint capture.
                        alpha = fullscreenContentAlpha
                    },
            )
        }

        if (sharedArtworkOwned && sceneFrame != null) {
            val sharedWidth = with(density) { sceneFrame.sharedWidthPx.toDp() }
            val sharedHeight = with(density) { sceneFrame.sharedHeightPx.toDp() }
            val sharedArtworkKey = sharedSceneArtworkKey
            val sharedCorner = sceneFrame.sharedCornerRadiusDp.dp

            // Scene motion owns the shared lane geometry. Do not render it with
            // PlaybackArtworkTransition: that composable also applies the current track-change
            // foreground transform (horizontal translation / scale / perspective) inside the
            // moving rectangle. Reusing it here made the cover drift to the right before the
            // scene flight and again during the final return handoff. The scene lane is one
            // artwork identity captured from the actual full-screen centre holder; native/foreground
            // track switching remains owned by the settled page and StandardPlayerBackdrop.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            sceneFrame.sharedLeftPx.roundToInt(),
                            sceneFrame.sharedTopPx.roundToInt(),
                        )
                    }
                    .width(sharedWidth)
                    .height(sharedHeight)
                    .zIndex(200f)
                    .clip(RoundedCornerShape(sharedCorner)),
            ) {
                if (sharedArtworkKey.isNotBlank()) {
                    key("home-full-cover-shared:$sharedArtworkKey") {
                        BitmapImage(
                            key = sharedArtworkKey,
                            contentDescription = fullscreenCenterArtworkDescription
                                ?: currentSong?.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            // Match the already-visible home/player high tier for an immediate sharp
                            // first frame, while warmFullCoverArt prepares the settled 1440px holder.
                            targetWidth = homePortraitDialSharedArtworkPolicy.movingTargetSidePx,
                            targetHeight = homePortraitDialSharedArtworkPolicy.movingTargetSidePx,
                            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                            surface = ArtworkSurface.Fullscreen,
                            fadeInMillis = 0,
                            holdPreviousOnKeyChange = false,
                            fadeOnBitmapChange = false,
                            // Never freeze a low placeholder for the duration of the scene flight.
                            // A later 1024px result replaces it immediately without a fade.
                            freezeBitmapUpdates =
                                homePortraitDialSharedArtworkPolicy.freezeBitmapUpdatesDuringMotion,
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
            }
        }
    }
}
