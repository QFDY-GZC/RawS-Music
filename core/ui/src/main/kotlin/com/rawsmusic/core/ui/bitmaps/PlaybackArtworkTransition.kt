package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ARTWORK_LOCAL_UPDATE_MS = 50
private const val ARTWORK_SETTLE_MS = 650
private const val ARTWORK_MAX_COMMANDS = 16
private const val ARTWORK_FRAME_HEADER = 4
private const val ARTWORK_COMMAND_STRIDE = 4
private const val ARTWORK_TARGET_SIDE = 1440

// Fade each card according to its distance from the selected center position.
private const val ARTWORK_ALPHA_ZERO_DISTANCE = 0.65f
private const val ARTWORK_ALPHA_FULL_DISTANCE = 0.40f

// Default perspective parameters. The selected card remains centered while adjacent cards use a
// denser horizontal step, distance-based scaling and small three-axis rotations. The absolute-axis
// flags select whether each rotation follows signed direction or only distance from center.
private const val PERSPECTIVE_DENSE_FACTOR = 0.75f
private const val PERSPECTIVE_MIN_SCALE = 0.50f
private const val PERSPECTIVE_MAX_ROTATION_X_DEGREES = 5.0f
private const val PERSPECTIVE_MAX_ROTATION_Y_DEGREES = -10.0f
private const val PERSPECTIVE_MAX_ROTATION_Z_DEGREES = 5.5f
private const val PERSPECTIVE_ABS_ROTATION_X = true
private const val PERSPECTIVE_ABS_ROTATION_Y = false
private const val PERSPECTIVE_ABS_ROTATION_Z = false

// Optional retained carousel style.
private const val CAROUSEL_SIDE_DISTANCE = 0.78f
private const val CAROUSEL_ITEM_STRIDE_FACTOR = 0.90f
private const val CAROUSEL_MIN_SIDE_SCALE = 0.78f
private const val CAROUSEL_MAX_Z_ROTATION_DEGREES = 4.5f
private const val CAROUSEL_MAX_Y_ROTATION_DEGREES = 7.0f
private const val CAROUSEL_CAMERA_DISTANCE_FACTOR = 1.15f
private const val ARTWORK_SWIPE_COMMIT_RATIO = 0.30f
private const val ARTWORK_SWIPE_FLING_FRACTION_PER_SECOND = 1.15f

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
    val low: BitmapRequest?,
    val high: BitmapRequest?
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

    internal var direction by mutableStateOf(PlayerArtworkDirection.Next)
        private set
    internal var ratio by mutableFloatStateOf(0f)
        private set
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
    }

    internal fun gestureTargetKey(direction: PlayerArtworkDirection): String? = when (direction) {
        PlayerArtworkDirection.Previous -> gesturePreviousKey
        PlayerArtworkDirection.Next -> gestureNextKey
    }.takeIf { it.isNotBlank() }

    /** Starts a user-controlled artwork drag. */
    fun beginGesture(direction: PlayerArtworkDirection, expectedKey: String?): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return false
        if (key == currentKey) return false
        transitionJob?.cancel()
        isSettling = false
        isGestureActive = true
        pendingCommit = false
        beginTarget(direction, key, autoSettle = false)
        setRatioFromUi(0f)
        return true
    }

    fun updateGesture(progress: Float) {
        if (!isGestureActive || closed) return
        setRatioFromUi(progress.coerceIn(0f, 1f))
    }

    /**
     * velocityFractionPerSecond is horizontal velocity divided by artwork width.
     * Returns whether the gesture committed to a song change.
     */
    fun endGesture(
        progress: Float,
        velocityFractionPerSecond: Float,
        onCommit: () -> Unit
    ): Boolean {
        if (!isGestureActive || closed) return false
        val forwardVelocity = velocityFractionPerSecond * direction.sign
        val commit = progress >= ARTWORK_SWIPE_COMMIT_RATIO ||
            forwardVelocity <= -ARTWORK_SWIPE_FLING_FRACTION_PER_SECOND
        isGestureActive = false
        pendingCommit = commit
        if (commit) onCommit()
        settleTo(if (commit) 1f else 0f, velocityFractionPerSecond)
        return commit
    }

    fun cancelGesture() {
        if (!isGestureActive) return
        isGestureActive = false
        pendingCommit = false
        settleTo(0f, 0f)
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
            beginTarget(resolvedDirection, key, autoSettle = true)
        } else {
            direction = resolvedDirection
            resetToBoundSong(key)
        }
    }

    internal fun visual(token: Int): Bitmap? = visuals[token]?.bitmap?.takeUnless { it.isRecycled }

    fun backgroundLayers(): List<PlaybackArtworkBackgroundLayer> = buildList {
        drawCommands.forEach { command ->
            val visual = visuals[command.token] ?: return@forEach
            if (!visual.bitmap.isRecycled && command.alpha > 0f) {
                add(
                    PlaybackArtworkBackgroundLayer(
                        token = command.token,
                        key = visual.key,
                        alpha = command.alpha.coerceIn(0f, 1f)
                    )
                )
            }
        }
        if (isEmpty() && currentToken != 0) {
            visuals[currentToken]?.let { add(PlaybackArtworkBackgroundLayer(currentToken, it.key, 1f)) }
        }
    }

    internal fun foregroundCurrentToken(): Int = currentToken
    internal fun foregroundTargetToken(): Int = targetToken
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

    fun pendingGestureTarget(requestedDirection: PlayerArtworkDirection): String? =
        targetKey.takeIf { pendingCommit && direction == requestedDirection && it.isNotBlank() }

    private fun beginTarget(
        newDirection: PlayerArtworkDirection,
        key: String,
        autoSettle: Boolean
    ) {
        if (closed || key.isBlank() || key == currentKey) return
        if (targetKey == key) {
            direction = newDirection
            targetAutoSettle = targetAutoSettle || autoSettle
            requestArtwork(key)
            if (targetToken != 0 && targetAutoSettle && !isGestureActive && !isSettling) settleTo(1f, 0f)
            return
        }

        resolveInterruptedTargetForNewRequest()
        direction = newDirection
        targetKey = key
        targetToken = 0
        lastNativeFrameNs = SystemClock.elapsedRealtimeNanos()
        targetQuality = 0
        targetAutoSettle = autoSettle
        pendingCommit = false
        requestArtwork(key)
        if (targetToken == 0) installPlaceholder(key, primary = false)
        if (targetAutoSettle && !isGestureActive) settleTo(1f, 0f)
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
        pendingCommit = false
        publishFrame(0L)
        releaseUnusedVisuals()
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
        pendingCommit = false
        ratio = 0f
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
        BitmapProvider.warmPlaybackArt(key)

        val cached = ArtworkDisplayResolver.acquirePlayback(
            key = key,
            lowResSize = AlbumArtTiers.HI_RES_SIDE,
            hiResSize = ARTWORK_TARGET_SIDE,
            surface = ArtworkSurface.Playback
        )
        if (cached?.handle?.isValid == true) {
            acceptHandle(key, cached.handle, cached.quality)
        } else {
            cached?.handle?.release()
        }

        val lowRequest = BitmapProvider.loadThumbnail(
            key = key,
            targetWidth = AlbumArtTiers.HI_RES_SIDE,
            targetHeight = AlbumArtTiers.HI_RES_SIDE,
            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
            surface = ArtworkSurface.Playback
        ) { bitmap ->
            val handle = BitmapProvider.acquireLoaded(
                key = key,
                bitmap = bitmap,
                targetWidth = AlbumArtTiers.HI_RES_SIDE,
                targetHeight = AlbumArtTiers.HI_RES_SIDE,
                surface = ArtworkSurface.Playback
            )
            if (handle != null) acceptHandle(key, handle, ArtworkDisplayResolver.QUALITY_LOW)
        }

        val highRequest = BitmapProvider.load(
            key = key,
            targetWidth = ARTWORK_TARGET_SIDE,
            targetHeight = ARTWORK_TARGET_SIDE,
            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
            surface = ArtworkSurface.Playback
        ) { bitmap ->
            val handle = BitmapProvider.acquireLoaded(
                key = key,
                bitmap = bitmap,
                targetWidth = ARTWORK_TARGET_SIDE,
                targetHeight = ARTWORK_TARGET_SIDE,
                surface = ArtworkSurface.Playback
            )
            if (handle != null) {
                acceptHandle(key, handle, ArtworkDisplayResolver.QUALITY_HIGH)
            } else if (shouldShowDefaultAlbumArtwork(key, ARTWORK_TARGET_SIDE, ARTWORK_TARGET_SIDE)) {
                acceptTerminalNoArtwork(key)
            }
        }
        loads[key] = ArtworkLoad(lowRequest, highRequest)
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
            else -> handle.release()
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

    private fun settleTo(end: Float, velocityFractionPerSecond: Float) {
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
        val baseDuration = (ARTWORK_SETTLE_MS * distance).roundToInt()
        val velocityDuration = if (abs(velocityFractionPerSecond) > 0.01f) {
            ((distance / abs(velocityFractionPerSecond)) * 1000f * 0.9f).roundToInt()
        } else {
            baseDuration
        }
        val durationMs = minOf(baseDuration, velocityDuration).coerceIn(120, ARTWORK_SETTLE_MS)

        transitionJob = scope.launch {
            val startNs = withFrameNanos { it }
            var previousNs = startNs
            while (true) {
                val now = withFrameNanos { it }
                val linear = ((now - startNs).toDouble() /
                    (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(linear)
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
            if (boundSongKey.isNotBlank() && boundSongKey != targetKey && !awaitingPlayerConfirmation) {
                // The player has already confirmed a different song. Never let a late visual
                // settle commit its stale identity over the current playback source of truth.
                NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
                targetToken.takeIf { it != 0 }?.let { visuals.remove(it)?.release() }
                clearLoad(targetKey)
                targetKey = ""
                targetToken = 0
                targetQuality = 0
                targetAutoSettle = false
                pendingCommit = false
                ratio = 0f
                publishFrame(0L)
                releaseUnusedVisuals()
                return
            }
            generation += 1
            NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
            currentKey = targetKey
            currentToken = targetToken
            currentQuality = targetQuality
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
            pendingCommit = false
            ratio = 0f
            publishFrame(0L)
            releaseUnusedVisuals()
        } else if (end <= 0f) {
            NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
            targetToken.takeIf { it != 0 }?.let { visuals.remove(it)?.release() }
            clearLoad(targetKey)
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
            pendingCommit = false
            ratio = 0f
            publishFrame(0L)
            releaseUnusedVisuals()
        }
    }

    private fun setRatioFromUi(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
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
        loads.remove(key)?.let { load ->
            load.low?.let { BitmapProvider.cancel(it, keepDecoding = true) }
            load.high?.let { BitmapProvider.cancel(it, keepDecoding = true) }
        }
    }

    private fun replaceVisual(token: Int, visual: ArtworkVisual) {
        val previous = visuals.put(token, visual)
        if (previous?.bitmap !== visual.bitmap) previous?.release()
    }

    private fun releaseUnusedVisuals() {
        val retained = drawCommands.mapTo(mutableSetOf()) { it.token }
        if (currentToken != 0) retained += currentToken
        if (targetToken != 0) retained += targetToken
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
        loads.values.forEach { load ->
            load.low?.let { BitmapProvider.cancel(it, keepDecoding = true) }
            load.high?.let { BitmapProvider.cancel(it, keepDecoding = true) }
        }
        loads.clear()
        visuals.values.forEach { it.release() }
        visuals.clear()
        drawCommands = emptyList()
        NativePlayerArtworkBridge.destroy(nativeHandle)
    }
}

@Composable
fun rememberPlaybackArtworkTransitionState(
    currentKey: String?,
    queueCurrentIndex: Int,
    queueSize: Int
): PlaybackArtworkTransitionState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val state = remember(context) { PlaybackArtworkTransitionState(scope, context) }
    val key = currentKey.orEmpty()

    LaunchedEffect(key, queueCurrentIndex, queueSize) {
        state.bindSong(key, queueCurrentIndex, queueSize)
    }
    DisposableEffect(Unit) {
        onDispose { state.close() }
    }
    return state
}

/**
 * Horizontal track gesture used by the immersive clear-art area.
 * The first horizontal direction locks the adjacent song for the full gesture; dragging back toward
 * the origin reverses progress without accidentally selecting the opposite neighbour.
 */
fun Modifier.playbackArtworkSwipeGesture(
    state: PlaybackArtworkTransitionState,
    previousKey: String?,
    nextKey: String?,
    enabled: Boolean = true,
    onGestureActiveChange: (Boolean) -> Unit = {},
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier = composed {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val latestPreviousKey by rememberUpdatedState(previousKey)
    val latestNextKey by rememberUpdatedState(nextKey)
    val latestOnGestureActiveChange by rememberUpdatedState(onGestureActiveChange)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    if (!enabled) return@composed this

    this
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        // Native transition frames recompose this modifier while the finger is down. Keep the
        // pointer coroutine stable and read adjacent keys/callbacks through updated state so a
        // frame update cannot cancel the gesture before its up event commits the track change.
        .pointerInput(state) {
            var dragX = 0f
            var direction: PlayerArtworkDirection? = null
            var gestureStarted = false
            var velocityTracker = VelocityTracker()

            fun progressForDirection(): Float = when (direction) {
                PlayerArtworkDirection.Next -> (-dragX / widthPx).coerceIn(0f, 1f)
                PlayerArtworkDirection.Previous -> (dragX / widthPx).coerceIn(0f, 1f)
                null -> 0f
            }

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
                        state.updateGesture(progressForDirection())
                        change.consume()
                    }
                },
                onDragEnd = {
                    if (gestureStarted) {
                        val velocityX = velocityTracker.calculateVelocity().x
                        state.endGesture(
                            progress = progressForDirection(),
                            velocityFractionPerSecond = velocityX / widthPx
                        ) {
                            when (direction) {
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
 * `logicalDistance` is the signed item distance from the selected center: 0 for the selected item
 * and -1/+1 for adjacent slots. The transform uses a dense horizontal step, distance-based scale and
 * small X/Y/Z rotations. Each axis can use absolute distance or signed direction. Artwork, title,
 * artist and item-local controls receive the same transform.
 */
private fun perspectiveDepthTransform(
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float
): ForegroundItemTransform {
    val p = progress.coerceIn(0f, 1f)
    val signedDistance = when (role) {
        PlayerArtworkItemRole.Current -> -direction.sign.toFloat() * p
        PlayerArtworkItemRole.Target -> direction.sign.toFloat() * (1f - p)
    }.coerceIn(-1f, 1f)
    val absoluteDistance = abs(signedDistance)
    val extent = itemExtentPx.coerceAtLeast(1f)

    fun rotationInput(useAbsoluteDistance: Boolean): Float =
        if (useAbsoluteDistance) absoluteDistance else signedDistance

    val scale = 1f - (1f - PERSPECTIVE_MIN_SCALE) * absoluteDistance
    return ForegroundItemTransform(
        translationX = signedDistance * extent * PERSPECTIVE_DENSE_FACTOR,
        scaleX = scale,
        scaleY = scale,
        rotationZ = rotationInput(PERSPECTIVE_ABS_ROTATION_Z) *
            PERSPECTIVE_MAX_ROTATION_Z_DEGREES,
        rotationX = rotationInput(PERSPECTIVE_ABS_ROTATION_X) *
            PERSPECTIVE_MAX_ROTATION_X_DEGREES,
        rotationY = rotationInput(PERSPECTIVE_ABS_ROTATION_Y) *
            PERSPECTIVE_MAX_ROTATION_Y_DEGREES,
        alpha = foregroundItemAlpha(absoluteDistance),
        // AAItemView only forces 10000px on Android <= 27. On modern Android it leaves the View
        // camera distance untouched, so the default style must not inject a made-up distance.
        cameraDistance = null,
        pivotFractionX = 0.5f,
        pivotFractionY = 0.5f
    )
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
    itemExtentPx: Float
): ForegroundItemTransform = when (style) {
    PlayerArtworkAnimationStyle.PerspectiveDepth -> perspectiveDepthTransform(
        role = role,
        direction = direction,
        progress = progress,
        itemExtentPx = itemExtentPx
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
    val current = state.visual(state.foregroundCurrentToken())
    val target = state.visual(state.foregroundTargetToken())
    val progress = state.ratio.coerceIn(0f, 1f)

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val itemExtentPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val currentTransform = playerArtworkForegroundTransform(
            style = animationStyle,
            role = PlayerArtworkItemRole.Current,
            direction = state.direction,
            progress = progress,
            itemExtentPx = itemExtentPx
        )
        val targetTransform = playerArtworkForegroundTransform(
            style = animationStyle,
            role = PlayerArtworkItemRole.Target,
            direction = state.direction,
            progress = progress,
            itemExtentPx = itemExtentPx
        )

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

        // Draw the item closer to the center last for every transition style. This avoids visible
        // identity swaps at gesture start.
        if (progress < 0.5f) {
            ArtworkItem(target, targetTransform)
            ArtworkItem(current, currentTransform)
        } else {
            ArtworkItem(current, currentTransform)
            ArtworkItem(target, targetTransform)
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
