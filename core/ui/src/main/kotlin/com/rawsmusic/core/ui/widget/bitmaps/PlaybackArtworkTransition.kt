package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.PI

private const val ARTWORK_LOCAL_UPDATE_MS = 50
private const val ARTWORK_COMMIT_SETTLE_MS = 250
private const val AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS = 1_000
private const val AUTO_CROSSFADE_ARTWORK_FADE_IN_MS = 1_200
private const val ARTWORK_ROLLBACK_SETTLE_MS = 500
private const val ARTWORK_MAX_COMMANDS = 16
private const val ARTWORK_FRAME_HEADER = 4
private const val ARTWORK_COMMAND_STRIDE = 4
private const val ARTWORK_TARGET_SIDE = 1440

// Fade each card according to its distance from the selected center position.
private const val ARTWORK_ALPHA_ZERO_DISTANCE = 1.0f
private const val ARTWORK_ALPHA_FULL_DISTANCE = 0.0f

// Perspective player values applied by the AA item transform rather than a flat page translation.
private const val PERSPECTIVE_DENSE_FACTOR = 0.75f
private const val PERSPECTIVE_MIN_SCALE = 0.75f
private const val PERSPECTIVE_MAX_ROTATION_X_DEGREES = 5.0f
private const val PERSPECTIVE_MAX_ROTATION_Y_DEGREES = -10.0f
private const val PERSPECTIVE_MAX_ROTATION_Z_DEGREES = 5.5f
private const val PERSPECTIVE_ALPHA_CUTOFF = 0.05f
private const val PERSPECTIVE_ALPHA_NORMALIZER = 1.0526316f
private const val ANDROID_VIEW_DEFAULT_CAMERA_DISTANCE_DP = 1280f

// Optional retained carousel style.
private const val CAROUSEL_SIDE_DISTANCE = 0.78f
private const val CAROUSEL_ITEM_STRIDE_FACTOR = 0.90f
private const val CAROUSEL_MIN_SIDE_SCALE = 0.78f
private const val CAROUSEL_MAX_Z_ROTATION_DEGREES = 4.5f
private const val CAROUSEL_MAX_Y_ROTATION_DEGREES = 7.0f
private const val CAROUSEL_CAMERA_DISTANCE_FACTOR = 1.15f
private const val ARTWORK_SWIPE_COMMIT_RATIO = 0.30f
private const val ARTWORK_SWIPE_FLING_DP_PER_SECOND = 500f
private const val ARTWORK_COMMIT_VELOCITY_RATIO = 0.80f
private const val ARTWORK_ROLLBACK_VELOCITY_RATIO = 0.20f
private const val ARTWORK_MAX_NORMALIZED_SPEED = 8f
private const val ARTWORK_MIN_ROLLBACK_SPEED = 3.5f

/** Direction follows the player carousel: left drag is Next, right drag is Previous. */
enum class PlayerArtworkDirection(val sign: Int) {
    Previous(-1),
    Next(1)
}

/** Standard-player foreground artwork transition. Immersive mode does not use this setting. */
enum class PlayerArtworkAnimationStyle(val value: Int) {
    PerspectiveDepth(0),
    InwardCarousel(1),
    Slide(2);

    companion object {
        fun from(value: Int): PlayerArtworkAnimationStyle =
            entries.firstOrNull { it.value == value } ?: PerspectiveDepth
    }
}

internal enum class PlayerArtworkItemRole {
    Current,
    Target
}

private data class ArtworkVisual(
    val key: String,
    val bitmap: Bitmap,
    val quality: Int,
    val handle: ArtworkHandle?,
    val ownedBitmap: Boolean
) {
    fun release() {
        handle?.release()
        if (ownedBitmap && !bitmap.isRecycled) bitmap.recycle()
    }
}

private data class ArtworkLoad(
    val job: Job
)

private data class GestureArtworkSlot(
    val key: String,
    val token: Int,
    val quality: Int,
)

internal data class NativeArtworkDrawCommand(
    val token: Int,
    val alpha: Float,
    val lane: Int,
    val localProgress: Float
)

data class PlaybackArtworkBackgroundLayer(
    val token: Int,
    val key: String,
    val alpha: Float
)

/**
 * Artwork ownership is split into two layers:
 *
 * 1. Native code owns generation, physical lane parity, short per-entry fades, alpha ordering and
 *    commit handling for the fixed-position background blend.
 * 2. Kotlin owns foreground card geometry and touch-driven transition progress.
 *
 * Native lanes are intentionally independent from the horizontally transformed foreground cards.
 */
@Stable
class PlaybackArtworkTransitionState internal constructor(
    private val scope: CoroutineScope,
    private val context: Context
) : AutoCloseable {
    private val nativeHandle = NativePlayerArtworkBridge.create()
    private val nativeFrame = FloatArray(
        ARTWORK_FRAME_HEADER + ARTWORK_MAX_COMMANDS * ARTWORK_COMMAND_STRIDE
    )
    private val visuals = mutableStateMapOf<Int, ArtworkVisual>()
    private val loads = LinkedHashMap<String, ArtworkLoad>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tokenCounter = 1
    private var generation = 1
    private var boundSongKey = ""
    private var currentKey = ""
    private var currentToken = 0
    private var currentQuality = 0
    private var targetKey = ""
    private var targetToken = 0
    private var targetQuality = 0
    private var targetAutoSettle = false
    private var pendingCommit = false
    private var transitionJob: Job? = null
    private var nativePumpJob: Job? = null
    private var closed = false
    private var directionHint: PlayerArtworkDirection? = null
    private var directionHintDeadlineMs = 0L
    private var expectedHintKey: String? = null
    private var expectedBoundKey = ""
    private var expectedBoundIndex = -1
    private var awaitingPlayerConfirmation = false
    private var expectedBoundDeadlineMs = 0L
    private var lastQueueIndex = -1
    private var queueSize = 0
    private var lastNativeFrameNs = 0L
    private var gesturePreviousKey = ""
    private var gestureNextKey = ""
    private var gesturePreviousSlot: GestureArtworkSlot? = null
    private var gestureNextSlot: GestureArtworkSlot? = null
    private var committedGestureKey = ""
    private var committedGestureDirection: PlayerArtworkDirection? = null
    // Once the outgoing lane has completed its auto fade it may never become visible again, even
    // if song binding/commit state flips for one Compose frame. This token latch is independent
    // from automaticFadeOnly so a transient flag reset cannot resurrect the old cover.
    private var suppressedAutomaticOutgoingToken by mutableIntStateOf(0)

    internal var direction by mutableStateOf(PlayerArtworkDirection.Next)
        private set
    internal var ratio by mutableFloatStateOf(0f)
        private set
    private var gestureBaseRatio = 0f
    private var gestureOriginSignedPosition = 0f
    private var pendingGestureCommit: (() -> Unit)? = null
    internal var parity by mutableStateOf(false)
        private set
    internal var drawCommands by mutableStateOf<List<NativeArtworkDrawCommand>>(emptyList())
        private set
    internal var frameVersion by mutableIntStateOf(0)
        private set
    internal var isGestureActive by mutableStateOf(false)
        private set
    internal var isSettling by mutableStateOf(false)
        private set
    /** Natural renderer commit uses a plain alpha crossfade; gestures/buttons keep their style. */
    internal var automaticFadeOnly by mutableStateOf(false)
        private set
    /** Long asymmetric artwork dissolve used only for natural Auto Crossfade commits. */
    internal var automaticCrossfadeArtworkFade by mutableStateOf(false)
        private set
    private var automaticCrossfadeVisualEnabled = false

    internal fun setAutomaticCrossfadeVisualEnabled(enabled: Boolean) {
        automaticCrossfadeVisualEnabled = enabled
    }

    /**
     * Button/queue preparation. Each request keeps its exact song identity. A new logical slot is
     * never filled from whichever pixels happen to be visible; it either reuses an exact slot or
     * loads the requested artwork identity into the other lane.
     */
    fun prepare(
        direction: PlayerArtworkDirection,
        expectedKey: String? = null,
        expectedQueueIndex: Int = -1
    ) {
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 1_000L
        expectedHintKey = expectedKey?.takeIf { it.isNotBlank() }
        val key = expectedHintKey ?: return
        if (key == currentKey && targetKey.isBlank()) {
            clearExpectedPlayerBinding()
            return
        }
        expectPlayerBinding(key, expectedQueueIndex)
        if (key == currentKey) return
        if (key == targetKey) {
            targetAutoSettle = true
            if (!isGestureActive && !pendingCommit && !isSettling && targetToken != 0) {
                settleTo(1f, 0f)
            }
            return
        }
        beginTarget(direction, key, autoSettle = true)
    }

    /**
     * The player can report intermediate queue positions after several rapid commands. Keep the
     * newest exact key/index binding and ignore older identities while that request is pending.
     */
    internal fun expectPlayerBinding(key: String, queueIndex: Int) {
        if (key.isBlank()) return
        expectedBoundKey = key
        expectedBoundIndex = queueIndex
        awaitingPlayerConfirmation = true
        expectedBoundDeadlineMs = SystemClock.uptimeMillis() + 4_000L
    }

    fun expectConfirmedNavigation(direction: PlayerArtworkDirection) {
        // A newer transport command must not remain blocked by an exact binding expected by the
        // preceding swipe. The next player emission is now the authoritative transaction.
        clearExpectedPlayerBinding()
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 1_000L
        expectedHintKey = null
    }

    internal fun setGestureTargetKeys(previousKey: String?, nextKey: String?) {
        gesturePreviousKey = previousKey.orEmpty()
        gestureNextKey = nextKey.orEmpty()
        if (isGestureActive) {
            gesturePreviousSlot = retainMatchingGestureSlot(
                slot = gesturePreviousSlot,
                key = gesturePreviousKey,
            )
            gestureNextSlot = retainMatchingGestureSlot(
                slot = gestureNextSlot,
                key = gestureNextKey,
            )
        } else {
            // Neighbour tokens belong to exactly one pointer session. Queue binding can lag a
            // committed swipe by one Compose frame, so retaining them while idle lets an old
            // previous/next cover briefly impersonate the neighbour of the new centre item.
            clearGestureSessionSlots()
        }
        releaseUnusedVisuals()
    }

    internal fun prefetchGestureTargets(previousKey: String?, nextKey: String?) {
        sequenceOf(previousKey, nextKey)
            .filterNotNull()
            .filter { it.isNotBlank() && it != currentKey }
            .distinct()
            .forEach { key ->
                CoilArtworkRuntime.prefetch(
                    context = context,
                    key = key,
                    width = ARTWORK_TARGET_SIDE,
                    height = ARTWORK_TARGET_SIDE,
                    surface = ArtworkSurface.Playback
                )
            }
    }

    internal fun gestureTargetKey(direction: PlayerArtworkDirection): String? = when (direction) {
        PlayerArtworkDirection.Previous -> gesturePreviousKey
        PlayerArtworkDirection.Next -> gestureNextKey
    }.takeIf { it.isNotBlank() }

    /** Starts a user-controlled artwork drag. */
    fun beginGesture(direction: PlayerArtworkDirection, expectedKey: String?): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return false
        if (key == currentKey) return false
        val resumesPreparedTarget = key == targetKey && targetKey.isNotBlank()
        val resumedSignedPosition = if (resumesPreparedTarget) {
            -this.direction.sign.toFloat() * ratio
        } else {
            0f
        }
        transitionJob?.cancel()
        isSettling = false
        isGestureActive = true
        pendingCommit = false
        beginTarget(direction, key, autoSettle = false)
        // An interrupted settle remains the exact same two-slot transition. Preserve its rendered
        // progress so the finger takes over the current frame instead of restarting from the cover.
        gestureBaseRatio = if (resumesPreparedTarget) ratio else 0f
        gestureOriginSignedPosition = resumedSignedPosition
        if (!resumesPreparedTarget) setRatioFromUi(0f)
        return true
    }

    fun updateGesture(progress: Float) {
        if (!isGestureActive || closed) return
        val nextRatio = (gestureBaseRatio + progress).coerceIn(0f, 1f)
        setRatioFromUi(nextRatio)
    }

    /**
     * Updates the player track with one continuous signed coordinate.
     *
     * Positive positions expose the previous item and negative positions expose the next item,
     * matching C0889.q. Crossing zero changes the adjacent slot while the current item is fully
     * centred, so a B -> C drag can reverse through B and continue naturally toward A.
     */
    fun updateContinuousGesture(
        signedDragPosition: Float,
        previousKey: String? = gesturePreviousKey,
        nextKey: String? = gestureNextKey,
    ) {
        if (!isGestureActive || closed) return
        val signedPosition = (gestureOriginSignedPosition + signedDragPosition).coerceIn(-1f, 1f)
        if (abs(signedPosition) <= 0.0015f) {
            setRatioFromUi(0f)
            return
        }

        val requestedDirection = if (signedPosition > 0f) {
            PlayerArtworkDirection.Previous
        } else {
            PlayerArtworkDirection.Next
        }
        if (requestedDirection != direction) {
            val requestedKey = when (requestedDirection) {
                PlayerArtworkDirection.Previous -> previousKey
                PlayerArtworkDirection.Next -> nextKey
            }?.takeIf { it.isNotBlank() && it != currentKey } ?: run {
                setRatioFromUi(0f)
                return
            }
            switchGestureTarget(requestedDirection, requestedKey)
        }
        setRatioFromUi(abs(signedPosition))
    }

    /**
     * Poweramp keeps three AA item holders alive for the complete pointer sequence. Compose only
     * draws the centre and the currently exposed neighbour, but the hidden neighbour must retain
     * its exact bitmap token while the finger crosses zero. Rebuilding the secondary slot here
     * caused the visible B -> C -> B flash and prevented the same drag from continuing toward A.
     */
    private fun switchGestureTarget(
        newDirection: PlayerArtworkDirection,
        key: String,
    ) {
        if (closed || key.isBlank() || key == currentKey || newDirection == direction) return
        transitionJob?.cancel()
        isSettling = false
        parkActiveGestureTarget()
        NativePlayerArtworkBridge.setRatio(nativeHandle, 0f)

        direction = newDirection
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        targetAutoSettle = false
        pendingCommit = false
        targetKey = key
        targetQuality = 0
        targetToken = takeGestureSlot(newDirection, key)?.also { slot ->
            targetQuality = slot.quality
        }?.token ?: 0
        ratio = 0f

        if (targetToken != 0) {
            NativePlayerArtworkBridge.setArtwork(
                nativeHandle,
                targetToken,
                primary = false,
                requestGeneration = generation,
                durationMs = 0,
            )
        } else {
            installCachedTarget(key)
            requestArtwork(key)
            if (targetToken == 0) installPlaceholder(key, primary = false)
        }
        NativePlayerArtworkBridge.setRatio(nativeHandle, 0f)
        publishFrame(0L)
    }

    /** Returns whether the gesture committed to a song change. */
    fun endGesture(
        progress: Float,
        velocityPxPerSecond: Float,
        artworkWidthPx: Float,
        density: Float,
        onCommit: () -> Unit
    ): Boolean {
        if (!isGestureActive || closed) return false
        val safeWidth = artworkWidthPx.coerceAtLeast(1f)
        val velocityFractionPerSecond = velocityPxPerSecond / safeWidth
        val velocityDpPerSecond = velocityPxPerSecond / density.coerceAtLeast(0.1f)
        val towardTargetSpeed = -velocityFractionPerSecond * direction.sign
        val effectiveProgress = ratio.coerceIn(0f, 1f)
        val hasFling = abs(velocityDpPerSecond) >= ARTWORK_SWIPE_FLING_DP_PER_SECOND
        val commit = if (hasFling) {
            towardTargetSpeed > 0f
        } else {
            effectiveProgress >= ARTWORK_SWIPE_COMMIT_RATIO
        }
        isGestureActive = false
        pendingCommit = commit
        pendingGestureCommit = null
        if (commit) {
            // Commit transport ownership as soon as the pointer decision is final, while the visual
            // lane continues settling independently. Waiting until ratio == 1 left the queue one
            // full animation behind and let a rapid follow-up drag reuse stale neighbours.
            committedGestureKey = targetKey
            committedGestureDirection = direction
            settleTo(1f, towardTargetSpeed)
            onCommit()
        } else {
            settleTo(0f, towardTargetSpeed)
        }
        return commit
    }

    fun cancelGesture() {
        if (!isGestureActive) return
        isGestureActive = false
        pendingCommit = false
        pendingGestureCommit = null
        settleTo(0f, 0f)
    }

    /**
     * Runs a button-triggered change through the same two-slot transaction as a committed swipe.
     * Playback is submitted only after the visual target reaches the centre, so player emissions
     * cannot replace the current slot halfway through the animation.
     */
    fun animateNavigation(
        direction: PlayerArtworkDirection,
        expectedKey: String?,
        expectedQueueIndex: Int = -1,
        onCommit: () -> Unit
    ): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return false
        if (closed || key == currentKey || isGestureActive) return false

        transitionJob?.cancel()
        isSettling = false
        pendingCommit = true
        pendingGestureCommit = onCommit
        // Button navigation is authoritative even when shuffle traversal jumps to a physically
        // earlier/later queue index. Keep the requested direction attached to the exact artwork
        // key until the player confirms it; inferring from queue indexes can reverse Next/Previous.
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 4_000L
        expectedHintKey = key
        expectPlayerBinding(key, expectedQueueIndex)
        beginTarget(direction, key, autoSettle = true)
        return targetKey == key
    }

    internal fun bindSong(key: String, queueIndex: Int, newQueueSize: Int) {
        queueSize = newQueueSize.coerceAtLeast(0)
        if (closed || key.isBlank()) return

        if (awaitingPlayerConfirmation && SystemClock.uptimeMillis() > expectedBoundDeadlineMs) {
            clearExpectedPlayerBinding()
        }
        if (awaitingPlayerConfirmation) {
            val indexMatches = expectedBoundIndex < 0 || queueIndex < 0 || queueIndex == expectedBoundIndex
            val exactNewestBinding = key == expectedBoundKey && indexMatches
            if (exactNewestBinding) {
                clearExpectedPlayerBinding()
            } else if (key != currentKey && key != targetKey) {
                // Late confirmation from an earlier rapid command. Do not rebuild either exact AA
                // slot from this older UI identity; wait for the newest requested key/index.
                return
            }
        }

        val oldIndex = lastQueueIndex
        lastQueueIndex = queueIndex
        boundSongKey = key

        if (currentToken == 0) {
            currentKey = key
            requestArtwork(key)
            if (currentToken == 0) installPlaceholder(key, primary = true)
            return
        }
        if (key == currentKey) return
        if (key == targetKey) {
            // The player confirmed the exact prepared item. Do not rebuild it from the currently
            // displayed bitmap; finish the existing exact-key slot.
            targetAutoSettle = true
            requestArtwork(key)
            if (!isGestureActive && !isSettling && targetToken != 0) settleTo(1f, 0f)
            return
        }

        val hinted = directionHint?.takeIf {
            SystemClock.uptimeMillis() <= directionHintDeadlineMs &&
                (expectedHintKey == null || expectedHintKey == key)
        }
        val resolvedDirection = hinted ?: inferDirection(oldIndex, queueIndex, queueSize)
        directionHint = null
        directionHintDeadlineMs = 0L
        expectedHintKey = null

        // Natural end-of-track changes arrive without prepare(). Create the exact adjacent slot and
        // run the same settle animation; only a same-position identity refresh (cover edit, metadata
        // refresh or cold restore) is rebound immediately.
        val isAutomaticQueueAdvance = oldIndex >= 0 && queueIndex >= 0 && oldIndex != queueIndex && queueSize > 1
        if (isAutomaticQueueAdvance) {
            beginTarget(
                resolvedDirection,
                key,
                autoSettle = true,
                fadeOnly = hinted == null,
                autoCrossfadeFade = hinted == null && automaticCrossfadeVisualEnabled,
            )
        } else {
            direction = resolvedDirection
            resetToBoundSong(key)
        }
    }

    internal fun visual(token: Int): Bitmap? = visuals[token]?.bitmap?.takeUnless { it.isRecycled }

    fun backgroundLayers(): List<PlaybackArtworkBackgroundLayer> = buildList {
        val current = visuals[currentToken]?.takeUnless { it.bitmap.isRecycled }
        val target = visuals[targetToken]?.takeUnless { it.bitmap.isRecycled }
        val progress = ratio.coerceIn(0f, 1f)

        // Artwork uses an opaque dominant lane while it moves. The background must not copy that
        // rule: explicitly crossfade both textures so a song change is visible frame by frame.
        current?.let {
            // Keep an opaque source underneath the incoming background. A newly prepared target
            // may still be extracting its palette/native texture, so fading this layer would expose
            // the transparent window before the target can draw.
            add(PlaybackArtworkBackgroundLayer(currentToken, it.key, 1f))
        }
        target?.let {
            if (progress > 0f) {
                val targetAlpha = if (automaticCrossfadeArtworkFade) {
                    smoothArtworkFadeProgress(progress)
                } else {
                    progress
                }
                add(PlaybackArtworkBackgroundLayer(targetToken, it.key, targetAlpha))
            }
        }
        if (isEmpty() && current != null) {
            add(PlaybackArtworkBackgroundLayer(currentToken, current.key, 1f))
        }
    }

    internal fun foregroundCurrentToken(): Int = currentToken
    internal fun foregroundTargetToken(): Int = targetToken
    internal fun isAutomaticOutgoingSuppressed(token: Int): Boolean =
        token != 0 && token == suppressedAutomaticOutgoingToken
    /**
     * Returns the artwork currently owned by the foreground transition.
     *
     * A popup must not start a second bitmap request while the playback artwork is settling. The
     * current slot is preferred, with the target slot as a safe fallback during a fast switch.
     */
    internal fun foregroundArtworkBitmap(): Bitmap? =
        visual(currentToken) ?: visual(targetToken)

    internal fun foregroundCurrentKey(): String = currentKey
    internal fun foregroundTargetKey(): String = targetKey
    internal fun hasPendingNavigation(): Boolean =
        targetKey.isNotBlank() || isSettling || isGestureActive || pendingCommit ||
            awaitingPlayerConfirmation

    private fun clearExpectedPlayerBinding() {
        expectedBoundKey = ""
        expectedBoundIndex = -1
        awaitingPlayerConfirmation = false
        expectedBoundDeadlineMs = 0L
    }

    fun pendingGestureTarget(requestedDirection: PlayerArtworkDirection): String? {
        if (committedGestureDirection == requestedDirection && committedGestureKey.isNotBlank()) {
            val key = committedGestureKey
            committedGestureKey = ""
            committedGestureDirection = null
            return key
        }
        return targetKey.takeIf {
            pendingCommit && direction == requestedDirection && it.isNotBlank()
        }
    }

    private fun beginTarget(
        newDirection: PlayerArtworkDirection,
        key: String,
        autoSettle: Boolean,
        fadeOnly: Boolean = false,
        autoCrossfadeFade: Boolean = false,
    ) {
        if (closed || key.isBlank() || key == currentKey) return
        if (targetKey == key) {
            direction = newDirection
            automaticFadeOnly = automaticFadeOnly || fadeOnly
            automaticCrossfadeArtworkFade = automaticCrossfadeArtworkFade || autoCrossfadeFade
            targetAutoSettle = targetAutoSettle || autoSettle
            requestArtwork(key)
            if (targetToken != 0 && targetAutoSettle && !isGestureActive && !isSettling) settleTo(1f, 0f)
            return
        }

        resolveInterruptedTargetForNewRequest()
        direction = newDirection
        automaticFadeOnly = fadeOnly
        automaticCrossfadeArtworkFade = autoCrossfadeFade
        suppressedAutomaticOutgoingToken = 0
        targetKey = key
        targetToken = takeGestureSlot(newDirection, key)?.also { slot ->
            targetQuality = slot.quality
        }?.token ?: 0
        lastNativeFrameNs = SystemClock.elapsedRealtimeNanos()
        if (targetToken == 0) targetQuality = 0
        targetAutoSettle = autoSettle
        pendingCommit = false
        if (targetToken != 0) {
            NativePlayerArtworkBridge.setArtwork(
                nativeHandle,
                targetToken,
                primary = false,
                requestGeneration = generation,
                durationMs = 0,
            )
            NativePlayerArtworkBridge.setRatio(nativeHandle, ratio)
            publishFrame(0L)
        } else {
            installCachedTarget(key)
        }
        requestArtwork(key)
        if (targetToken == 0 && !autoSettle) {
            // Keep interactive dragging responsive. Button-driven switching waits for the exact
            // target artwork instead of animating a placeholder and refreshing it a second time.
            installPlaceholder(key, primary = false)
        }
        if (targetAutoSettle && targetToken != 0 && !isGestureActive) settleTo(1f, 0f)
    }

    private fun installCachedTarget(key: String) {
        val cached = CoilArtworkRuntime.peekBitmap(
            context = context,
            key = key,
            width = ARTWORK_TARGET_SIDE,
            height = ARTWORK_TARGET_SIDE,
            surface = ArtworkSurface.Playback
        )?.takeUnless(Bitmap::isRecycled) ?: return
        targetToken = nextToken()
        targetQuality = ArtworkDisplayResolver.QUALITY_ANY
        visuals[targetToken] = ArtworkVisual(
            key = key,
            bitmap = cached,
            quality = ArtworkDisplayResolver.QUALITY_ANY,
            handle = null,
            ownedBitmap = false
        )
        NativePlayerArtworkBridge.setArtwork(
            nativeHandle,
            targetToken,
            primary = false,
            requestGeneration = generation,
            durationMs = 0
        )
        NativePlayerArtworkBridge.setRatio(nativeHandle, ratio)
        publishFrame(0L)
    }

    /**
     * Compare exact artwork identities in the two logical slots. A later request may promote an
     * exact secondary slot, but the currently dominant pixels never become the identity of another
     * song. Rapid button navigation commits the exact prepared slot; a cancelled gesture discards it.
     */
    private fun resolveInterruptedTargetForNewRequest() {
        transitionJob?.cancel()
        isSettling = false
        if (targetKey.isBlank()) {
            ratio = 0f
            return
        }
        if (targetAutoSettle && !isGestureActive && targetToken != 0) {
            forceCommitPreparedTarget()
        } else {
            discardTarget()
        }
    }

    private fun forceCommitPreparedTarget() {
        if (targetToken == 0 || targetKey.isBlank()) {
            discardTarget()
            return
        }
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        generation += 1
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        currentKey = targetKey
        currentToken = targetToken
        currentQuality = targetQuality
        ratio = 0f
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        targetAutoSettle = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        pendingCommit = false
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    private fun gestureSlot(direction: PlayerArtworkDirection): GestureArtworkSlot? =
        when (direction) {
            PlayerArtworkDirection.Previous -> gesturePreviousSlot
            PlayerArtworkDirection.Next -> gestureNextSlot
        }

    private fun setGestureSlot(
        direction: PlayerArtworkDirection,
        slot: GestureArtworkSlot?,
    ) {
        when (direction) {
            PlayerArtworkDirection.Previous -> gesturePreviousSlot = slot
            PlayerArtworkDirection.Next -> gestureNextSlot = slot
        }
    }

    private fun retainMatchingGestureSlot(
        slot: GestureArtworkSlot?,
        key: String,
    ): GestureArtworkSlot? {
        if (slot == null) return null
        return slot.takeIf {
            key.isNotBlank() && it.key == key && it.key != currentKey && visuals.containsKey(it.token)
        }
    }

    private fun takeGestureSlot(
        direction: PlayerArtworkDirection,
        key: String,
    ): GestureArtworkSlot? {
        val slot = gestureSlot(direction)?.takeIf {
            it.key == key && visuals.containsKey(it.token)
        } ?: return null
        setGestureSlot(direction, null)
        return slot
    }

    private fun parkGestureSlot(
        direction: PlayerArtworkDirection,
        key: String,
        token: Int,
        quality: Int,
    ) {
        if (key.isBlank() || token == 0 || token == currentToken || !visuals.containsKey(token)) return
        setGestureSlot(direction, GestureArtworkSlot(key, token, quality))
    }

    private fun parkActiveGestureTarget() {
        parkGestureSlot(direction, targetKey, targetToken, targetQuality)
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        targetAutoSettle = false
    }

    private fun parkedGestureSlotForKey(key: String): Pair<PlayerArtworkDirection, GestureArtworkSlot>? {
        gesturePreviousSlot?.takeIf { it.key == key }?.let {
            return PlayerArtworkDirection.Previous to it
        }
        gestureNextSlot?.takeIf { it.key == key }?.let {
            return PlayerArtworkDirection.Next to it
        }
        return null
    }

    private fun clearGestureSlotByToken(token: Int) {
        if (token == 0) return
        if (gesturePreviousSlot?.token == token) gesturePreviousSlot = null
        if (gestureNextSlot?.token == token) gestureNextSlot = null
    }

    private fun clearGestureSessionSlots() {
        gesturePreviousSlot = null
        gestureNextSlot = null
    }

    private fun discardTarget() {
        transitionJob?.cancel()
        isSettling = false
        NativePlayerArtworkBridge.setRatio(nativeHandle, 0f)
        NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
        targetToken.takeIf { it != 0 }?.let { visuals.remove(it)?.release() }
        clearLoad(targetKey)
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        targetAutoSettle = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        pendingCommit = false
        ratio = 0f
        suppressedAutomaticOutgoingToken = 0
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    private fun resetToBoundSong(key: String) {
        val oldCurrentToken = currentToken
        val oldCurrentKey = currentKey
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        isSettling = false
        isGestureActive = false
        pendingCommit = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        clearExpectedPlayerBinding()
        discardTarget()

        clearLoad(oldCurrentKey)
        generation += 1
        currentKey = key
        currentToken = 0
        currentQuality = 0
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        requestArtwork(key)
        if (currentToken == 0) installPlaceholder(key, primary = true)
        if (oldCurrentToken != 0 && oldCurrentToken != currentToken) {
            visuals.remove(oldCurrentToken)?.release()
        }
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    private fun requestArtwork(key: String) {
        if (key.isBlank() || loads.containsKey(key) || closed) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = CoilArtworkRuntime.executeBitmap(
                context = context,
                key = key,
                width = ARTWORK_TARGET_SIDE,
                height = ARTWORK_TARGET_SIDE,
                surface = ArtworkSurface.Playback
            )
            if (bitmap != null && !bitmap.isRecycled) {
                acceptHandle(
                    key = key,
                    handle = ArtworkHandle(
                        sourceKey = key,
                        tier = ArtworkTier.Full,
                        surface = ArtworkSurface.Playback,
                        bitmap = bitmap,
                        onRelease = {}
                    ),
                    quality = ArtworkDisplayResolver.QUALITY_HIGH
                )
            } else {
                acceptTerminalNoArtwork(key)
            }
        }
        loads[key] = ArtworkLoad(job)
        job.start()
    }

    private fun acceptHandle(key: String, handle: ArtworkHandle?, quality: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { acceptHandle(key, handle, quality) }
            return
        }
        if (closed || handle?.isValid != true) {
            handle?.release()
            return
        }
        if (quality >= ArtworkDisplayResolver.QUALITY_HIGH) {
            loads.remove(key)
        }

        when (key) {
            currentKey -> {
                if (currentToken != 0 && quality <= currentQuality) {
                    handle.release()
                    return
                }
                if (currentToken == 0) {
                    currentToken = nextToken()
                    currentQuality = quality
                    visuals[currentToken] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                    NativePlayerArtworkBridge.setArtwork(nativeHandle, currentToken, true, generation, 0)
                } else {
                    replaceVisual(currentToken, ArtworkVisual(key, handle.bitmap, quality, handle, false))
                    currentQuality = quality
                }
                publishFrame(0L)
            }
            targetKey -> {
                if (targetToken != 0 && quality <= targetQuality) {
                    handle.release()
                    return
                }
                if (targetToken == 0) {
                    targetToken = nextToken()
                    targetQuality = quality
                    visuals[targetToken] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                    NativePlayerArtworkBridge.setArtwork(
                        nativeHandle,
                        targetToken,
                        primary = false,
                        requestGeneration = generation,
                        durationMs = ARTWORK_LOCAL_UPDATE_MS
                    )
                    NativePlayerArtworkBridge.setRatio(nativeHandle, ratio)
                    publishFrame(0L)
                    pumpNativeEntryFade()
                } else {
                    replaceVisual(targetToken, ArtworkVisual(key, handle.bitmap, quality, handle, false))
                    targetQuality = quality
                    publishFrame(0L)
                }
                if ((targetAutoSettle || pendingCommit) && !isGestureActive && !isSettling) settleTo(1f, 0f)
            }
            boundSongKey -> {
                // External track change reached us before prepare/bind could establish a target.
                direction = directionHint ?: PlayerArtworkDirection.Next
                beginTarget(direction, key, autoSettle = true)
                acceptHandle(key, handle, quality)
            }
            else -> {
                val parked = parkedGestureSlotForKey(key)
                if (parked == null || quality <= parked.second.quality) {
                    handle.release()
                } else {
                    replaceVisual(
                        parked.second.token,
                        ArtworkVisual(key, handle.bitmap, quality, handle, false),
                    )
                    setGestureSlot(
                        parked.first,
                        parked.second.copy(quality = quality),
                    )
                    publishFrame(0L)
                }
            }
        }
    }

    private fun installPlaceholder(key: String, primary: Boolean, side: Int = 512) {
        if (closed || key.isBlank()) return
        val bitmap = createPlaceholderBitmap(side.coerceIn(256, 768))
        val token = nextToken()
        visuals[token] = ArtworkVisual(
            key = key,
            bitmap = bitmap,
            quality = ArtworkDisplayResolver.QUALITY_ANY,
            handle = null,
            ownedBitmap = true
        )
        if (primary) {
            currentToken = token
            currentQuality = ArtworkDisplayResolver.QUALITY_ANY
            NativePlayerArtworkBridge.setArtwork(nativeHandle, token, true, generation, 0)
        } else {
            targetToken = token
            targetQuality = ArtworkDisplayResolver.QUALITY_ANY
            NativePlayerArtworkBridge.setArtwork(
                nativeHandle,
                token,
                false,
                generation,
                ARTWORK_LOCAL_UPDATE_MS
            )
            NativePlayerArtworkBridge.setRatio(nativeHandle, ratio)
            pumpNativeEntryFade()
        }
        publishFrame(0L)
    }

    private fun acceptTerminalNoArtwork(key: String, side: Int = 768) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { acceptTerminalNoArtwork(key, side) }
            return
        }
        if (closed || (key != currentKey && key != targetKey)) return
        loads.remove(key)
        if (DefaultAlbumArtworkPolicy.enabled) {
            installDefaultArtwork(key, primary = key == currentKey, side = side)
        } else if (key == currentKey && currentToken == 0) {
            installPlaceholder(key, primary = true, side = side)
        } else if (key == targetKey && targetToken == 0) {
            installPlaceholder(key, primary = false, side = side)
            if ((targetAutoSettle || pendingCommit) && !isGestureActive && !isSettling) settleTo(1f, 0f)
        }
    }

    private fun installDefaultArtwork(key: String, primary: Boolean, side: Int) {
        val bitmap = decodeDefaultAlbumArtwork(context.resources, side) ?: return
        val visual = ArtworkVisual(
            key = key,
            bitmap = bitmap,
            quality = ArtworkDisplayResolver.QUALITY_ANY,
            handle = null,
            ownedBitmap = true
        )
        val existingToken = if (primary) currentToken else targetToken
        if (existingToken != 0) {
            replaceVisual(existingToken, visual)
        } else {
            val token = nextToken()
            visuals[token] = visual
            if (primary) {
                currentToken = token
                currentQuality = ArtworkDisplayResolver.QUALITY_ANY
                NativePlayerArtworkBridge.setArtwork(nativeHandle, token, true, generation, 0)
            } else {
                targetToken = token
                targetQuality = ArtworkDisplayResolver.QUALITY_ANY
                NativePlayerArtworkBridge.setArtwork(nativeHandle, token, false, generation, ARTWORK_LOCAL_UPDATE_MS)
                NativePlayerArtworkBridge.setRatio(nativeHandle, ratio)
                pumpNativeEntryFade()
            }
        }
        publishFrame(0L)
        if (!primary && (targetAutoSettle || pendingCommit) && !isGestureActive && !isSettling) {
            settleTo(1f, 0f)
        }
    }

    private fun settleTo(end: Float, towardTargetSpeed: Float) {
        transitionJob?.cancel()
        if (closed) return
        val target = end.coerceIn(0f, 1f)
        val start = ratio.coerceIn(0f, 1f)
        val distance = abs(target - start)
        if (distance <= 0.0005f) {
            setRatioFromUi(target)
            finishSettle(target)
            return
        }

        isSettling = true
        val velocityDrivenSpeed = when {
            target >= 1f && start > ARTWORK_COMMIT_VELOCITY_RATIO && towardTargetSpeed > 0f ->
                towardTargetSpeed.coerceIn(
                    1000f / ARTWORK_COMMIT_SETTLE_MS,
                    ARTWORK_MAX_NORMALIZED_SPEED
                )
            target <= 0f && start < ARTWORK_ROLLBACK_VELOCITY_RATIO && towardTargetSpeed < 0f ->
                (-towardTargetSpeed).coerceIn(
                    ARTWORK_MIN_ROLLBACK_SPEED,
                    ARTWORK_MAX_NORMALIZED_SPEED
                )
            else -> 0f
        }
        val velocityDriven = velocityDrivenSpeed > 0f
        val durationMs = if (velocityDriven) {
            ((distance / velocityDrivenSpeed) * 1000f).roundToInt().coerceAtLeast(1)
        } else if (target >= 1f && automaticCrossfadeArtworkFade) {
            AUTO_CROSSFADE_ARTWORK_FADE_IN_MS
        } else if (target >= 1f) {
            ARTWORK_COMMIT_SETTLE_MS
        } else {
            ARTWORK_ROLLBACK_SETTLE_MS
        }

        transitionJob = scope.launch {
            val startNs = withFrameNanos { it }
            var previousNs = startNs
            while (true) {
                val now = withFrameNanos { it }
                val linear = ((now - startNs).toDouble() /
                    (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
                val eased = if (velocityDriven || automaticCrossfadeArtworkFade) {
                    // Auto Crossfade artwork owns its easing per lane below. Keep the shared ratio
                    // as a linear 0..1 clock so 1000ms out / 1200ms in are physically accurate.
                    linear
                } else {
                    // Android's AccelerateDecelerateInterpolator used by PowerList transitions.
                    ((cos((linear + 1f) * PI) / 2.0) + 0.5).toFloat()
                }
                val value = start + (target - start) * eased
                NativePlayerArtworkBridge.setRatio(nativeHandle, value)
                publishFrame((now - previousNs).coerceAtLeast(0L))
                previousNs = now
                if (linear >= 1f) break
            }
            setRatioFromUi(target)
            finishSettle(target)
        }
    }

    private fun finishSettle(end: Float) {
        isSettling = false
        if (end >= 1f && targetToken != 0) {
            if (
                pendingGestureCommit == null &&
                boundSongKey.isNotBlank() &&
                boundSongKey != targetKey &&
                !awaitingPlayerConfirmation
            ) {
                // The player has already confirmed a different song. Never let a late visual
                // settle commit its stale identity over the current playback source of truth.
                NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
                targetToken.takeIf { it != 0 }?.let { visuals.remove(it)?.release() }
                clearLoad(targetKey)
                targetKey = ""
                targetToken = 0
                targetQuality = 0
                targetAutoSettle = false
                automaticFadeOnly = false
                automaticCrossfadeArtworkFade = false
                pendingCommit = false
                pendingGestureCommit = null
                ratio = 0f
                suppressedAutomaticOutgoingToken = 0
                publishFrame(0L)
                releaseUnusedVisuals()
                return
            }
            val committedKey = targetKey
            val promotedToken = targetToken
            generation += 1
            NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
            currentKey = targetKey
            currentToken = targetToken
            currentQuality = targetQuality
            clearGestureSlotByToken(promotedToken)
            clearGestureSessionSlots()
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
            automaticFadeOnly = false
            automaticCrossfadeArtworkFade = false
            pendingCommit = false
            ratio = 0f
            val gestureCommit = pendingGestureCommit
            pendingGestureCommit = null
            publishFrame(0L)
            releaseUnusedVisuals()
            if (gestureCommit != null) {
                committedGestureKey = committedKey
                committedGestureDirection = direction
                gestureCommit.invoke()
            }
        } else if (end <= 0f) {
            NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
            val rolledBackToken = targetToken
            clearLoad(targetKey)
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
            clearGestureSessionSlots()
            rolledBackToken.takeIf { it != 0 && it != currentToken }?.let {
                visuals.remove(it)?.release()
            }
            automaticFadeOnly = false
            automaticCrossfadeArtworkFade = false
            pendingCommit = false
            pendingGestureCommit = null
            ratio = 0f
            suppressedAutomaticOutgoingToken = 0
            publishFrame(0L)
            releaseUnusedVisuals()
        }
    }

    private fun setRatioFromUi(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (
            automaticCrossfadeArtworkFade &&
            currentToken != 0 &&
            clamped * AUTO_CROSSFADE_ARTWORK_FADE_IN_MS >= AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS
        ) {
            suppressedAutomaticOutgoingToken = currentToken
        }
        NativePlayerArtworkBridge.setRatio(nativeHandle, clamped)
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (lastNativeFrameNs == 0L) 0L else (now - lastNativeFrameNs).coerceAtLeast(0L)
        lastNativeFrameNs = now
        publishFrame(delta)
    }

    private fun pumpNativeEntryFade() {
        nativePumpJob?.cancel()
        nativePumpJob = scope.launch {
            var previous = withFrameNanos { it }
            repeat(8) {
                val now = withFrameNanos { it }
                publishFrame((now - previous).coerceAtLeast(0L))
                previous = now
            }
        }
    }

    private fun publishFrame(deltaNs: Long) {
        if (closed) return
        if (nativeHandle == 0L) {
            publishFallbackFrame()
            return
        }
        NativePlayerArtworkBridge.advance(nativeHandle, deltaNs, nativeFrame)
        ratio = nativeFrame[1].coerceIn(0f, 1f)
        parity = nativeFrame[2] >= 0.5f
        val count = nativeFrame[3].roundToInt().coerceIn(0, ARTWORK_MAX_COMMANDS)
        drawCommands = buildList(count) {
            repeat(count) { index ->
                val base = ARTWORK_FRAME_HEADER + index * ARTWORK_COMMAND_STRIDE
                add(
                    NativeArtworkDrawCommand(
                        token = nativeFrame[base].roundToInt(),
                        alpha = nativeFrame[base + 1].coerceIn(0f, 1f),
                        lane = nativeFrame[base + 2].roundToInt().coerceIn(0, 1),
                        localProgress = nativeFrame[base + 3].coerceIn(0f, 1f)
                    )
                )
            }
        }
        frameVersion += 1
    }

    private fun publishFallbackFrame() {
        val commands = ArrayList<NativeArtworkDrawCommand>(2)
        if (currentToken != 0) commands += NativeArtworkDrawCommand(currentToken, 1f, 1, 1f)
        if (targetToken != 0) commands += NativeArtworkDrawCommand(targetToken, ratio, 0, 1f)
        drawCommands = commands
        parity = false
        frameVersion += 1
    }

    private fun clearLoad(key: String) {
        if (key.isBlank()) return
        loads.remove(key)?.job?.cancel()
    }

    private fun replaceVisual(token: Int, visual: ArtworkVisual) {
        val previous = visuals.put(token, visual)
        if (previous?.bitmap !== visual.bitmap) previous?.release()
    }

    private fun releaseUnusedVisuals() {
        val retained = drawCommands.mapTo(mutableSetOf()) { it.token }
        if (currentToken != 0) retained += currentToken
        if (targetToken != 0) retained += targetToken
        gesturePreviousSlot?.token?.takeIf { it != 0 }?.let(retained::add)
        gestureNextSlot?.token?.takeIf { it != 0 }?.let(retained::add)
        val stale = visuals.keys.filterNot { it in retained }
        stale.forEach { token -> visuals.remove(token)?.release() }
    }

    private fun nextToken(): Int {
        tokenCounter = if (tokenCounter >= 0x00FFFFFE) 1 else tokenCounter + 1
        return tokenCounter
    }

    private fun inferDirection(oldIndex: Int, newIndex: Int, size: Int): PlayerArtworkDirection {
        if (oldIndex < 0 || newIndex < 0 || oldIndex == newIndex) return PlayerArtworkDirection.Next
        if (size > 1) {
            if (oldIndex == size - 1 && newIndex == 0) return PlayerArtworkDirection.Next
            if (oldIndex == 0 && newIndex == size - 1) return PlayerArtworkDirection.Previous
        }
        return if (newIndex > oldIndex) PlayerArtworkDirection.Next else PlayerArtworkDirection.Previous
    }

    override fun close() {
        if (closed) return
        closed = true
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        loads.values.forEach { it.job.cancel() }
        loads.clear()
        visuals.values.forEach { it.release() }
        visuals.clear()
        gesturePreviousSlot = null
        gestureNextSlot = null
        drawCommands = emptyList()
        NativePlayerArtworkBridge.destroy(nativeHandle)
    }
}

@Composable
fun rememberPlaybackArtworkTransitionState(
    currentKey: String?,
    queueCurrentIndex: Int,
    queueSize: Int,
    automaticCrossfadeEnabled: Boolean = false,
): PlaybackArtworkTransitionState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val state = remember(context) { PlaybackArtworkTransitionState(scope, context) }
    val key = currentKey.orEmpty()

    LaunchedEffect(key, queueCurrentIndex, queueSize, automaticCrossfadeEnabled) {
        state.setAutomaticCrossfadeVisualEnabled(automaticCrossfadeEnabled)
        state.bindSong(key, queueCurrentIndex, queueSize)
    }
    DisposableEffect(Unit) {
        onDispose { state.close() }
    }
    return state
}

/**
 * Horizontal track gesture used by the immersive clear-art area. The signed drag remains continuous
 * across the centre item, allowing one pointer sequence to move between both adjacent songs.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.playbackArtworkSwipeGesture(
    state: PlaybackArtworkTransitionState,
    previousKey: String?,
    nextKey: String?,
    enabled: Boolean = true,
    onGestureActiveChange: (Boolean) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier = composed {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val latestPreviousKey by rememberUpdatedState(previousKey)
    val latestNextKey by rememberUpdatedState(nextKey)
    val latestOnGestureActiveChange by rememberUpdatedState(onGestureActiveChange)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    val longPressInteractionSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current.density
    if (!enabled) return@composed this

    val gestureModifier = if (onLongPress != null) {
        this.combinedClickable(
            interactionSource = longPressInteractionSource,
            indication = null,
            onClick = {},
            onLongClick = { latestOnLongPress?.invoke() },
        )
    } else {
        this
    }

    gestureModifier
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        // Native transition frames recompose this modifier while the finger is down. Keep the
        // pointer coroutine stable and read adjacent keys/callbacks through updated state so a
        // frame update cannot cancel the gesture before its up event commits the track change.
        .pointerInput(state) {
            var dragX = 0f
            var direction: PlayerArtworkDirection? = null
            var gestureStarted = false
            var velocityTracker = VelocityTracker()

            fun signedPosition(): Float = (dragX / widthPx).coerceIn(-1f, 1f)

            detectHorizontalDragGestures(
                onDragStart = {
                    dragX = 0f
                    direction = null
                    gestureStarted = false
                    velocityTracker = VelocityTracker()
                    latestOnGestureActiveChange(true)
                },
                onHorizontalDrag = { change, amount ->
                    dragX += amount
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (direction == null && dragX != 0f) {
                        val candidate = if (dragX < 0f) {
                            PlayerArtworkDirection.Next
                        } else {
                            PlayerArtworkDirection.Previous
                        }
                        val key = when (candidate) {
                            PlayerArtworkDirection.Previous -> latestPreviousKey
                            PlayerArtworkDirection.Next -> latestNextKey
                        }
                        if (state.beginGesture(candidate, key)) {
                            direction = candidate
                            gestureStarted = true
                        }
                    }
                    if (gestureStarted) {
                        state.updateContinuousGesture(
                            signedDragPosition = signedPosition(),
                            previousKey = latestPreviousKey,
                            nextKey = latestNextKey,
                        )
                        direction = state.direction
                        change.consume()
                    }
                },
                onDragEnd = {
                    if (gestureStarted) {
                        val velocityX = velocityTracker.calculateVelocity().x
                        val commitDirection = state.direction
                        state.endGesture(
                            progress = state.ratio,
                            velocityPxPerSecond = velocityX,
                            artworkWidthPx = widthPx,
                            density = density
                        ) {
                            when (commitDirection) {
                                PlayerArtworkDirection.Previous -> latestOnPrevious()
                                PlayerArtworkDirection.Next -> latestOnNext()
                                null -> Unit
                            }
                        }
                    }
                    latestOnGestureActiveChange(false)
                },
                onDragCancel = {
                    if (gestureStarted) state.cancelGesture()
                    latestOnGestureActiveChange(false)
                }
            )
        }
}

/** Geometry for one standard-player foreground artwork item. */
private fun plainArtworkAlphaTransform(alpha: Float) = ForegroundItemTransform(
    translationX = 0f,
    scaleX = 1f,
    scaleY = 1f,
    rotationZ = 0f,
    rotationX = 0f,
    rotationY = 0f,
    alpha = alpha.coerceIn(0f, 1f),
    cameraDistance = null,
    pivotFractionX = 0.5f,
    pivotFractionY = 0.5f,
)

internal data class ForegroundItemTransform(
    val translationX: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val alpha: Float,
    /** Null means keep the platform/Compose default camera distance, matching modern AAItemView. */
    val cameraDistance: Float?,
    val pivotFractionX: Float,
    val pivotFractionY: Float
)

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun inwardCarouselTransform(
    logicalDistance: Float,
    itemExtentPx: Float
): ForegroundItemTransform {
    val extent = itemExtentPx.coerceAtLeast(1f)
    val rawDistance = logicalDistance.coerceIn(-1f, 1f)
    val visualDistance = rawDistance * CAROUSEL_SIDE_DISTANCE
    val absoluteDistance = abs(visualDistance)
    val centreCrossDistance = CAROUSEL_SIDE_DISTANCE * 0.5f
    val sideAmount = smoothStep(
        (absoluteDistance / centreCrossDistance.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    )
    val scale = 1f - (1f - CAROUSEL_MIN_SIDE_SCALE) * sideAmount
    val sideSign = when {
        visualDistance < -0.0001f -> -1f
        visualDistance > 0.0001f -> 1f
        else -> 0f
    }
    val pivotX = when {
        sideSign < 0f -> 1f
        sideSign > 0f -> 0f
        else -> 0.5f
    }
    return ForegroundItemTransform(
        translationX = visualDistance * extent * CAROUSEL_ITEM_STRIDE_FACTOR,
        scaleX = scale,
        scaleY = scale,
        rotationZ = -sideSign * CAROUSEL_MAX_Z_ROTATION_DEGREES * sideAmount,
        rotationX = 0f,
        rotationY = sideSign * CAROUSEL_MAX_Y_ROTATION_DEGREES * sideAmount,
        alpha = foregroundItemAlpha(absoluteDistance),
        cameraDistance = extent * CAROUSEL_CAMERA_DISTANCE_FACTOR,
        pivotFractionX = pivotX,
        pivotFractionY = 1f
    )
}

/**
 * Perspective transform for the standard player.
 *
 * The preference name is retained for compatibility. Artwork and text use the same signed slot
 * distance so a reversal remains one continuous perspective track rather than two flat pages.
 */
private fun perspectiveDepthTransform(
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float,
    density: Float,
): ForegroundItemTransform {
    val p = progress.coerceIn(0f, 1f)
    val signedDistance = when (role) {
        PlayerArtworkItemRole.Current -> -direction.sign.toFloat() * p
        PlayerArtworkItemRole.Target -> direction.sign.toFloat() * (1f - p)
    }.coerceIn(-1f, 1f)
    val extent = itemExtentPx.coerceAtLeast(1f)
    val absoluteDistance = abs(signedDistance)
    val scale = 1f - (1f - PERSPECTIVE_MIN_SCALE) * absoluteDistance
    return ForegroundItemTransform(
        translationX = signedDistance * extent * PERSPECTIVE_DENSE_FACTOR,
        scaleX = scale,
        scaleY = scale,
        rotationZ = signedDistance * PERSPECTIVE_MAX_ROTATION_Z_DEGREES,
        rotationX = absoluteDistance * PERSPECTIVE_MAX_ROTATION_X_DEGREES,
        rotationY = signedDistance * PERSPECTIVE_MAX_ROTATION_Y_DEGREES,
        alpha = powerampPerspectiveAlpha(absoluteDistance),
        // Android View's default camera distance is 1280dp. Compose uses a much shorter default,
        // which exaggerates Poweramp's small rotations into a severe trapezoid on dense screens.
        cameraDistance = ANDROID_VIEW_DEFAULT_CAMERA_DISTANCE_DP * density.coerceAtLeast(0.1f),
        pivotFractionX = 0.5f,
        pivotFractionY = 0.5f
    )
}

private fun powerampPerspectiveAlpha(distanceFromCenter: Float): Float {
    val visibleAmount = (1f - distanceFromCenter.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    return if (visibleAmount < PERSPECTIVE_ALPHA_CUTOFF) {
        0f
    } else {
        ((visibleAmount - PERSPECTIVE_ALPHA_CUTOFF) * PERSPECTIVE_ALPHA_NORMALIZER)
            .coerceIn(0f, 1f)
    }
}

/** Pure page translation option. No alpha, scale, or 3D transform is applied. */
private fun slideTransform(
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float
): ForegroundItemTransform {
    val p = progress.coerceIn(0f, 1f)
    val logicalDistance = when (role) {
        PlayerArtworkItemRole.Current -> -direction.sign.toFloat() * p
        PlayerArtworkItemRole.Target -> direction.sign.toFloat() * (1f - p)
    }
    return ForegroundItemTransform(
        translationX = logicalDistance * itemExtentPx.coerceAtLeast(1f),
        scaleX = 1f,
        scaleY = 1f,
        rotationZ = 0f,
        rotationX = 0f,
        rotationY = 0f,
        alpha = 1f,
        cameraDistance = null,
        pivotFractionX = 0.5f,
        pivotFractionY = 0.5f
    )
}

internal fun playerArtworkForegroundTransform(
    style: PlayerArtworkAnimationStyle,
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float,
    density: Float,
): ForegroundItemTransform = when (style) {
    PlayerArtworkAnimationStyle.PerspectiveDepth -> perspectiveDepthTransform(
        role = role,
        direction = direction,
        progress = progress,
        itemExtentPx = itemExtentPx,
        density = density,
    )
    PlayerArtworkAnimationStyle.InwardCarousel -> {
        val logicalDistance = when (role) {
            PlayerArtworkItemRole.Current -> -direction.sign.toFloat() * progress.coerceIn(0f, 1f)
            PlayerArtworkItemRole.Target -> direction.sign.toFloat() * (1f - progress.coerceIn(0f, 1f))
        }
        inwardCarouselTransform(logicalDistance, itemExtentPx)
    }
    PlayerArtworkAnimationStyle.Slide -> slideTransform(
        role = role,
        direction = direction,
        progress = progress,
        itemExtentPx = itemExtentPx
    )
}

/** Title metadata remains attached to the same transformed slot as its artwork. */
internal fun playerArtworkTitleTransform(
    style: PlayerArtworkAnimationStyle,
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float,
    density: Float,
): ForegroundItemTransform = playerArtworkForegroundTransform(
    style = style,
    role = role,
    direction = direction,
    progress = progress,
    itemExtentPx = itemExtentPx,
    density = density,
)

internal fun foregroundItemAlpha(distanceFromCenter: Float): Float = when {
    distanceFromCenter >= ARTWORK_ALPHA_ZERO_DISTANCE -> 0f
    distanceFromCenter <= ARTWORK_ALPHA_FULL_DISTANCE -> 1f
    else -> (ARTWORK_ALPHA_ZERO_DISTANCE - distanceFromCenter) /
        (ARTWORK_ALPHA_ZERO_DISTANCE - ARTWORK_ALPHA_FULL_DISTANCE)
}.coerceIn(0f, 1f)

/**
 * Foreground clear-art viewport.
 *
 * The current and incoming images are two persistent logical slots. Neither slot is recreated from
 * the currently displayed bitmap during a transition. In the default perspective style, both cards
 * use the same compact center-pivot transform track. Foreground alpha changes from the first
 * non-zero progress frame; native code remains responsible only for the fixed-position background
 * blend.
 */
private fun smoothArtworkFadeProgress(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun automaticCrossfadeArtworkOutAlpha(progress: Float): Float {
    val elapsedMs = progress.coerceIn(0f, 1f) * AUTO_CROSSFADE_ARTWORK_FADE_IN_MS
    val outProgress = (elapsedMs / AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS).coerceIn(0f, 1f)
    return 1f - smoothArtworkFadeProgress(outProgress)
}

private fun automaticCrossfadeArtworkInAlpha(progress: Float): Float =
    smoothArtworkFadeProgress(progress)

@Composable
fun PlaybackArtworkTransition(
    state: PlaybackArtworkTransitionState,
    animationStyle: PlayerArtworkAnimationStyle = PlayerArtworkAnimationStyle.PerspectiveDepth,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Dp = 28.dp
) {
    @Suppress("UNUSED_VARIABLE")
    val redraw = state.frameVersion
    val currentToken = state.foregroundCurrentToken()
    val current = state.visual(currentToken)
    val target = state.visual(state.foregroundTargetToken())
    val progress = state.ratio.coerceIn(0f, 1f)
    val outgoingSuppressed = state.isAutomaticOutgoingSuppressed(currentToken)

    // Poweramp's player list explicitly disables child clipping. The side slot is therefore
    // allowed to keep its full perspective silhouette while crossing the artwork bounds instead
    // of being reduced to a narrow, page-like strip at the edge.
    BoxWithConstraints(modifier = modifier) {
        val itemExtentPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current.density
        val currentTransform = if (outgoingSuppressed) {
            // The old auto-crossfade token has already reached alpha zero. Keep it retired even
            // if automaticFadeOnly momentarily flips during the queue/slot commit boundary.
            plainArtworkAlphaTransform(0f)
        } else if (state.automaticFadeOnly) {
            val alpha = if (state.automaticCrossfadeArtworkFade) {
                automaticCrossfadeArtworkOutAlpha(progress)
            } else {
                1f - progress
            }
            plainArtworkAlphaTransform(alpha)
        } else {
            playerArtworkForegroundTransform(
                style = animationStyle,
                role = PlayerArtworkItemRole.Current,
                direction = state.direction,
                progress = progress,
                itemExtentPx = itemExtentPx,
                density = density,
            )
        }
        val targetTransform = if (state.automaticFadeOnly) {
            val alpha = if (state.automaticCrossfadeArtworkFade) {
                automaticCrossfadeArtworkInAlpha(progress)
            } else {
                progress
            }
            plainArtworkAlphaTransform(alpha)
        } else {
            playerArtworkForegroundTransform(
                style = animationStyle,
                role = PlayerArtworkItemRole.Target,
                direction = state.direction,
                progress = progress,
                itemExtentPx = itemExtentPx,
                density = density,
            )
        }

        if (current == null && target == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.Black.copy(alpha = 0.10f))
            )
        }

        @Composable
        fun ArtworkItem(bitmap: Bitmap?, transform: ForegroundItemTransform) {
            if (bitmap == null || bitmap.isRecycled || transform.alpha <= 0.001f) return
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = transform.translationX
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        rotationZ = transform.rotationZ
                        rotationX = transform.rotationX
                        rotationY = transform.rotationY
                        alpha = transform.alpha
                        transform.cameraDistance?.let { cameraDistance = it }
                        transformOrigin = TransformOrigin(
                            pivotFractionX = transform.pivotFractionX,
                            pivotFractionY = transform.pivotFractionY
                        )
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(cornerRadius))
                )
            }
        }

        // Recycler child order is stable for the whole Poweramp transition: the higher queue index
        // is drawn last. Do not swap Z order at 50%, because that creates a visible midpoint pop in
        // a perspective transition even when both transforms are otherwise identical.
        if (state.direction == PlayerArtworkDirection.Next) {
            ArtworkItem(current, currentTransform)
            ArtworkItem(target, targetTransform)
        } else {
            ArtworkItem(target, targetTransform)
            ArtworkItem(current, currentTransform)
        }
    }
}

private fun createPlaceholderBitmap(side: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(45, 45, 52) }
    canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), background)
    val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(185, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = side * 0.34f
    }
    canvas.drawText("♪", side * 0.5f, side * 0.62f, note)
    return bitmap
}
