package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ARTWORK_LOCAL_UPDATE_MS = 50
// C0661 passes x0.aaPosAnimTime=550ms and aaPosSettleAnimTime=650ms into C0858.
private const val ARTWORK_PROGRAMMATIC_POSITION_MS = 550
private const val ARTWORK_GESTURE_SETTLE_MS = 650
private const val AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS = 1_000
private const val AUTO_CROSSFADE_ARTWORK_FADE_IN_MS = 1_200
private const val ARTWORK_MAX_COMMANDS = 16
private const val ARTWORK_FRAME_HEADER = 4
private const val ARTWORK_COMMAND_STRIDE = 4
private const val ARTWORK_TARGET_SIDE = 1440
private const val MANUAL_UNKNOWN_BINDING_LEASE_MS = 2_000L
private const val MANUAL_EXACT_BINDING_LEASE_MS = 8_000L

// C0889.w() normal-holder alpha. The narrower 0.65..0.40 envelope belongs to z(), which h()
// invokes only for synthetic edge/overscroll holders (-1000/-2000), not the ordinary prev/current/next
// AAItemViews. Normal cards use visible=(1-|distance|), then fade from visible=0.05 to 1.0.
private const val Reference_NORMAL_ALPHA_VISIBLE_FLOOR = 0.05f
private const val Reference_NORMAL_ALPHA_INV_RANGE = 1.0526316f // 1 / 0.95

// Perspective player values applied by the AA item transform rather than a flat page translation.
private const val PERSPECTIVE_DENSE_FACTOR = 0.75f
private const val PERSPECTIVE_MAX_SCALE_PARAMETER = 0.50f
private const val PERSPECTIVE_MAX_ROTATION_X_DEGREES = 5.0f
private const val PERSPECTIVE_MAX_ROTATION_Y_DEGREES = -10.0f
private const val PERSPECTIVE_MAX_ROTATION_Z_DEGREES = 5.5f

// Optional retained carousel style.
private const val CAROUSEL_SIDE_DISTANCE = 0.78f
private const val CAROUSEL_ITEM_STRIDE_FACTOR = 0.90f
private const val CAROUSEL_MIN_SIDE_SCALE = 0.78f
private const val CAROUSEL_MAX_Z_ROTATION_DEGREES = 4.5f
private const val CAROUSEL_MAX_Y_ROTATION_DEGREES = 7.0f
private const val CAROUSEL_CAMERA_DISTANCE_FACTOR = 1.15f
// Reference C0858: p=0.2 and release projects signed page fraction by xVelocity * 1e-4.
private const val ARTWORK_SWIPE_COMMIT_RATIO = 0.20f
private const val ARTWORK_SWIPE_VELOCITY_PROJECTION = 0.0001f
private const val ARTWORK_COMMIT_VELOCITY_RATIO = 0.80f
private const val ARTWORK_ROLLBACK_VELOCITY_RATIO = 0.20f
private const val ARTWORK_MAX_NORMALIZED_SPEED = 8f
private const val ARTWORK_MIN_ROLLBACK_SPEED = 3.5f
private const val Reference_AA_PROGRAMMATIC_P1_X = 0.50f
private const val Reference_AA_PROGRAMMATIC_P1_Y = 0.10f
private const val Reference_AA_PROGRAMMATIC_P2_X = 0.15f
private const val Reference_AA_PROGRAMMATIC_P2_Y = 0.99f

/**
 * Exact port of the androidx.core.content.res interpolator used by Reference C0858/n1.
 *
 * n1 does not use Android/Compose's generic PathInterpolator evaluation. It asks the cubic for Y
 * at a given X with a tolerance of 1 / (durationSeconds * 500). The implementation first runs up
 * to eight Newton iterations and then falls back to bisection. Keeping that tolerance matters at
 * AA-card scale: a mathematically exact CubicBezierEasing follows a subtly different position
 * sequence than Reference's millisecond n1 owner.
 */
internal fun ReferenceProgrammaticArtworkEasing(
    input: Float,
    durationMs: Int = ARTWORK_PROGRAMMATIC_POSITION_MS,
): Float {
    val x = input.coerceIn(0f, 1f)
    if (x <= 0f || x >= 1f) return x

    val durationSeconds = durationMs.coerceAtLeast(1) / 1000f
    val tolerance = 1f / (durationSeconds * 500f)

    fun coefficients(p1: Float, p2: Float): FloatArray {
        val c = 3f * p1
        val b = 3f * (p2 - p1) - c
        val a = 1f - c - b
        return floatArrayOf(a, b, c)
    }
    val cx = coefficients(Reference_AA_PROGRAMMATIC_P1_X, Reference_AA_PROGRAMMATIC_P2_X)
    val cy = coefficients(Reference_AA_PROGRAMMATIC_P1_Y, Reference_AA_PROGRAMMATIC_P2_Y)

    fun sample(coefficients: FloatArray, t: Float): Float =
        ((coefficients[0] * t + coefficients[1]) * t + coefficients[2]) * t

    fun sampleDerivative(coefficients: FloatArray, t: Float): Float =
        (3f * coefficients[0] * t + 2f * coefficients[1]) * t + coefficients[2]

    var t = x
    repeat(8) {
        val error = sample(cx, t) - x
        if (abs(error) < tolerance) {
            return sample(cy, t).coerceIn(0f, 1f)
        }
        val derivative = sampleDerivative(cx, t)
        if (abs(derivative) < 1.0e-6f) return@repeat
        t -= error / derivative
    }

    var low = 0f
    var high = 1f
    t = x
    while (low < high) {
        val sampleX = sample(cx, t)
        if (abs(sampleX - x) < tolerance) break
        if (sampleX > x) high = t else low = t
        val next = (low + high) * 0.5f
        if (next == t) break
        t = next
    }
    return sample(cy, t).coerceIn(0f, 1f)
}

private enum class ArtworkSettleCurve {
    Programmatic,
    Gesture,
}

/** Direction follows the player carousel: left drag is Next, right drag is Previous. */
enum class PlayerArtworkDirection(val sign: Int) {
    Previous(-1),
    Next(1)
}

/** C0858-style page release: signed fractional position plus a small velocity projection. */
internal fun shouldCommitPlaybackArtworkSwipe(
    progress: Float,
    direction: PlayerArtworkDirection,
    velocityPxPerSecond: Float,
): Boolean {
    val signedPosition = -direction.sign.toFloat() * progress.coerceIn(0f, 1f)
    val projectedPosition = signedPosition + velocityPxPerSecond * ARTWORK_SWIPE_VELOCITY_PROJECTION
    // Keep the recovered 0.20 boundary stable across Float rounding (0.12 + 0.08 may become
    // 0.19999999 on ART/JVM). This epsilon is far below a perceptible page-distance delta.
    return abs(projectedPosition) + 1.0e-6f >= ARTWORK_SWIPE_COMMIT_RATIO
}

/** Manual transport hints are never allowed to masquerade as renderer-owned natural advance. */
internal fun isNaturalPlaybackArtworkAdvance(
    queueAdvanced: Boolean,
    manualNavigationBinding: Boolean,
    hasManualDirectionHint: Boolean,
): Boolean = queueAdvanced && !manualNavigationBinding && !hasManualDirectionHint

/** Long auto artwork dissolve belongs only to a genuine natural advance while Auto Crossfade is on. */
internal fun shouldUseAutomaticCrossfadeArtwork(
    automaticCrossfadeEnabled: Boolean,
    isNaturalQueueAdvance: Boolean,
): Boolean = automaticCrossfadeEnabled && isNaturalQueueAdvance

/**
 * Reference C0858 -> n1 keeps aaPosAnimTime as the time base for a programmatic page move.
 * The physical distance changes, but an idle programmatic move still uses the full 550 ms owner;
 * n1 derives per-frame deltas from that time base instead of shortening the tween in proportion
 * to the remaining normalized fraction.
 */
internal fun programmaticArtworkDurationMs(
    distance: Float,
    fullDistanceDurationMs: Int = ARTWORK_PROGRAMMATIC_POSITION_MS,
): Int = if (distance <= 0.0005f) 1 else fullDistanceDurationMs.coerceAtLeast(1)

/**
 * Reference C0858.K() keeps [aaPosAnimTime] as the base clock even when the live fractional
 * position is retargeted across more than one physical page. Its effective scroller speed is
 * approximately `distance / aaPosAnimTime + 0.2 * retainedVelocity`, so a farther target does not
 * stretch 550 ms into 880/1430 ms; retained same-direction velocity can only shorten the clock.
 *
 * Page units are used here instead of pixels; the physical page span cancels from the ratio. The
 * small 10 ms distance carry mirrors K()'s `f5 += 0.01 * abs(velocity)` term.
 */
internal fun programmaticRetargetDurationMs(
    startFraction: Float,
    destinationPages: Float,
    retainedVelocityPagesPerSecond: Float = 0f,
    pageDurationMs: Int = ARTWORK_PROGRAMMATIC_POSITION_MS,
): Int {
    val distancePages = (destinationPages - startFraction.coerceIn(0f, 1f)).coerceAtLeast(0f)
    if (distancePages <= 0.0005f) return 1
    val baseSeconds = pageDurationMs.coerceAtLeast(1) / 1000f
    val retained = retainedVelocityPagesPerSecond.coerceAtLeast(0f)
    val adjustedDistance = distancePages + if (retained > 0f) retained * 0.01f else 0f
    val speed = (adjustedDistance / baseSeconds) + retained * 0.2f
    return ((adjustedDistance / speed.coerceAtLeast(0.0001f)) * 1000f)
        .roundToInt()
        .coerceIn(1, pageDurationMs.coerceAtLeast(1))
}

/**
 * C0889.mo2767() is the physical page step: resolved AA side * aaDenseFactor.
 * Direct manipulation is normalized by this step, not by the full artwork/container width.
 */
internal fun ReferenceArtworkPageStepPx(itemExtentPx: Float): Float =
    itemExtentPx.coerceAtLeast(1f) * PERSPECTIVE_DENSE_FACTOR

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
    val ownedBitmap: Boolean,
    val syntheticPlaceholder: Boolean = false,
) {
    fun release() {
        // Compose/RenderThread can retain a display-list reference to this Bitmap for a frame after
        // the Kotlin holder is detached/replaced. Never recycle player-transition bitmaps here:
        // dropping the provider handle/strong reference is sufficient and avoids draw-after-recycle
        // crashes during rapid navigation.
        handle?.release()
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

enum class PlaybackArtworkBackgroundRole {
    Current,
    Target,
}

data class PlaybackArtworkBackgroundLayer(
    val token: Int,
    val key: String,
    val role: PlaybackArtworkBackgroundRole,
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
internal fun interface PlaybackArtworkPresentationObserver {
    fun onPresentationChanged(topologyChanged: Boolean)
}

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
    private var currentKey by mutableStateOf("")
    private var currentToken by mutableIntStateOf(0)
    private var currentQuality = 0
    private var targetKey by mutableStateOf("")
    private var targetToken by mutableIntStateOf(0)
    private var targetQuality = 0
    private var targetAutoSettle = false
    private var targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
    private var targetSettleCurve = ArtworkSettleCurve.Gesture
    private var pendingCommit = false
    private var transitionJob: Job? = null
    private var nativePumpJob: Job? = null
    // Live programmatic velocity in normalized AA pages/second. C0858.K() carries a fraction of the
    // active velocity into a retarget instead of restarting the next button press from rest.
    private var programmaticVelocityPagesPerSecond = 0f
    internal var manualProgrammaticTransition by mutableStateOf(false)
        private set
    // PLAYER <-> LYRIC motion is a separate scene owner from C0889 track paging. A Lyrico cover
    // rewrite may finish decoding while that shared-artwork scene is under the finger. Keep only
    // that same-current-holder replacement pending until the scene settles; normal track-target
    // AAImageView wrapper updates remain eager and independent from page motion.
    private var externalSceneArtworkMotionActive = false
    private var deferCurrentArtworkRewriteForSceneMotion = false
    private var pendingCurrentArtworkRewriteVisual: ArtworkVisual? = null
    private var closed = false
    private var directionHint: PlayerArtworkDirection? = null
    private var directionHintDeadlineMs = 0L
    private var expectedHintKey: String? = null
    private var expectedBoundKey = ""
    private var expectedBoundIndex = -1
    private var awaitingPlayerConfirmation = false
    private var expectedBoundDeadlineMs = 0L
    // Manual commands are multi-flight: rapid Next/Previous taps can have several committed player
    // identities in transit at once. A single "latest key" marker is therefore incorrect because
    // the first binding consumes/overwrites it and a later manual binding can be misclassified as
    // natural Auto Crossfade. Keep exact pending identities independently until each one binds.
    private val pendingManualNavigationKeys = LinkedHashMap<String, Long>()
    // Reference C0858 retargets from the live fractional list position. Keep later same-direction
    // programmatic requests as exact page destinations instead of force-committing the active page
    // before the next command can move. The frame owner can then cross holder boundaries without an
    // idle/zero-velocity seam.
    private data class ProgrammaticRetarget(
        val direction: PlayerArtworkDirection,
        val key: String,
    )
    private val queuedProgrammaticTargets = ArrayDeque<ProgrammaticRetarget>()
    // Fallback only for manual sources that cannot resolve a song key before dispatch. Each action
    // owns one credit; when the exact key becomes known markManualNavigationTarget() transfers one
    // credit into the exact-key map instead of leaving a broad manual lease behind.
    private var pendingUnknownManualBindings = 0
    private var unknownManualBindingDeadlineMs = 0L
    private var lastQueueIndex = -1
    private var queueSize = 0
    // Shared physical AA item extent. Artwork publishes this once on layout; title holders reuse it
    // so image and metadata move on the same C0889 track instead of two different viewport widths.
    private var foregroundItemExtentPx = 1f
    private var gesturePreviousKey = ""
    private var gestureNextKey = ""
    private var gesturePreviousSlot by mutableStateOf<GestureArtworkSlot?>(null)
    private var gestureNextSlot by mutableStateOf<GestureArtworkSlot?>(null)
    private var deferredGesturePreviousKey: String? = null
    private var deferredGestureNextKey: String? = null
    private var hasDeferredGestureKeys = false
    private var committedGestureKey = ""
    private var committedGestureDirection: PlayerArtworkDirection? = null
    // Once the outgoing lane has completed its auto fade it may never become visible again, even
    // if song binding/commit state flips for one Compose frame. This token latch is independent
    // from automaticFadeOnly so a transient flag reset cannot resurrect the old cover.
    private var suppressedAutomaticOutgoingToken by mutableIntStateOf(0)
    // View-backed artwork pager observers. Ratio/topology changes notify the attached Android View
    // directly, avoiding a Compose recomposition/AndroidView update on every motion frame.
    private val presentationObservers = ArrayList<PlaybackArtworkPresentationObserver>(1)

    internal fun addPresentationObserver(observer: PlaybackArtworkPresentationObserver) {
        if (!presentationObservers.contains(observer)) presentationObservers.add(observer)
    }

    internal fun removePresentationObserver(observer: PlaybackArtworkPresentationObserver) {
        presentationObservers.remove(observer)
    }

    /**
     * True while C0889-style foreground geometry owns visible motion. Bitmap-wrapper quality
     * upgrades must not start a second pixel fade/upload clock on the same moving holder.
     *
     * Reference normally has adjacent AAImageView wrappers attached before page movement. Raw's
     * provider can deliver low/high/full upgrades later, so the View pager uses this gate to keep
     * the already-presented pixels stable until the page/gesture owner is idle.
     */
    internal fun isForegroundArtworkMotionActive(): Boolean =
        isGestureActive || isSettling || pendingCommit || ratio > 0.001f

    private fun notifyPresentationObservers(topologyChanged: Boolean) {
        var index = 0
        while (index < presentationObservers.size) {
            presentationObservers[index].onPresentationChanged(topologyChanged)
            index += 1
        }
    }

    internal var direction by mutableStateOf(PlayerArtworkDirection.Next)
        private set
    /**
     * Compose-facing ratio. Title/background/fallback layers observe this in graphicsLayer/draw.
     * The Android View artwork pager has a separate plain [presentationRatio] so its RenderNode
     * properties can be written before Snapshot invalidation work on every pointer/vsync tick.
     */
    internal var ratio by mutableFloatStateOf(0f)
        private set
    private var presentationRatio: Float = 0f
    internal fun foregroundPresentationRatio(): Float = presentationRatio

    private var gestureBaseRatio = 0f
    private var gestureOriginSignedPosition = 0f
    private var pendingGestureCommit: (() -> Unit)? = null
    // Renderer bookkeeping is not UI snapshot state. Updating it every vsync used to invalidate
    // Compose even though foreground geometry only needs ratio in graphicsLayer.
    internal var parity: Boolean = false
        private set
    private val nativeRetainedTokens = IntArray(ARTWORK_MAX_COMMANDS)
    private var nativeRetainedTokenCount = 0
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
        val key = expectedKey?.takeIf { it.isNotBlank() }
        armManualNavigation(direction, key, expectedQueueIndex)
        if (key == null || key == currentKey) return
        if (key == targetKey) {
            targetAutoSettle = true
            requestArtwork(key)
            requestTargetAutoSettle()
            return
        }
        beginTarget(direction, key, autoSettle = true, fadeOnly = false, autoCrossfadeFade = false, settleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS, settleCurve = ArtworkSettleCurve.Programmatic)
    }

    /**
     * Marks the next binding as manual without moving artwork yet. Call this before transport
     * dispatch so a synchronous player callback can never be classified as natural Auto Crossfade.
     */
    fun armManualNavigation(
        direction: PlayerArtworkDirection,
        expectedKey: String? = null,
        expectedQueueIndex: Int = -1,
    ) {
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 4_000L
        expectedHintKey = expectedKey?.takeIf { it.isNotBlank() }
        if (expectedHintKey != null) {
            markManualNavigationTarget(expectedHintKey)
            expectPlayerBinding(expectedHintKey!!, expectedQueueIndex)
        } else {
            armUnknownManualBinding()
        }
    }

    private fun pruneManualNavigationBindings(nowMs: Long = SystemClock.uptimeMillis()) {
        val expired = pendingManualNavigationKeys.filterValues { it < nowMs }.keys.toList()
        expired.forEach(pendingManualNavigationKeys::remove)
        if (unknownManualBindingDeadlineMs > 0L && nowMs > unknownManualBindingDeadlineMs) {
            pendingUnknownManualBindings = 0
            unknownManualBindingDeadlineMs = 0L
        }
    }

    private fun armUnknownManualBinding(nowMs: Long = SystemClock.uptimeMillis()) {
        pruneManualNavigationBindings(nowMs)
        pendingUnknownManualBindings = (pendingUnknownManualBindings + 1).coerceAtMost(ARTWORK_MAX_COMMANDS)
        unknownManualBindingDeadlineMs = nowMs + MANUAL_UNKNOWN_BINDING_LEASE_MS
    }

    /** Marks a requested/manual identity even when the command came from notification/headset UI. */
    fun markManualNavigationTarget(expectedKey: String?) {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return
        val now = SystemClock.uptimeMillis()
        pruneManualNavigationBindings(now)
        // A command may have been armed before its exact selected song was known. Convert one
        // anonymous manual credit into this exact identity so it cannot accidentally classify a
        // later unrelated natural advance as manual.
        if (pendingUnknownManualBindings > 0) {
            pendingUnknownManualBindings -= 1
            if (pendingUnknownManualBindings == 0) unknownManualBindingDeadlineMs = 0L
        }
        pendingManualNavigationKeys[key] = now + MANUAL_EXACT_BINDING_LEASE_MS
        while (pendingManualNavigationKeys.size > ARTWORK_MAX_COMMANDS) {
            pendingManualNavigationKeys.entries.firstOrNull()?.key?.let(pendingManualNavigationKeys::remove)
        }
    }

    /** Starts/reconciles the visual lane only after the manual transport command was dispatched. */
    fun startManualNavigation(
        direction: PlayerArtworkDirection,
        expectedKey: String?,
        expectedQueueIndex: Int = -1,
    ): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return false
        armManualNavigation(direction, key, expectedQueueIndex)
        if (key == currentKey) return false
        if (
            manualProgrammaticTransition &&
            isSettling &&
            targetAutoSettle &&
            targetSettleCurve == ArtworkSettleCurve.Programmatic &&
            targetKey.isNotBlank() &&
            direction == this.direction &&
            key != targetKey
        ) {
            if (queuedProgrammaticTargets.none { it.key == key }) {
                queuedProgrammaticTargets.addLast(ProgrammaticRetarget(direction, key))
            }
            restartProgrammaticChainFromCurrentFraction()
            return true
        }
        manualProgrammaticTransition = true
        if (!isSettling) programmaticVelocityPagesPerSecond = 0f
        if (key == targetKey) {
            targetAutoSettle = true
            requestArtwork(key)
            requestTargetAutoSettle()
            return true
        }
        beginTarget(direction, key, autoSettle = true, fadeOnly = false, autoCrossfadeFade = false, settleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS, settleCurve = ArtworkSettleCurve.Programmatic)
        return targetKey == key
    }

    private fun consumeManualNavigationBinding(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        pruneManualNavigationBindings(now)
        if (pendingManualNavigationKeys.remove(key) != null) return true
        if (pendingUnknownManualBindings > 0 && now <= unknownManualBindingDeadlineMs) {
            pendingUnknownManualBindings -= 1
            if (pendingUnknownManualBindings == 0) unknownManualBindingDeadlineMs = 0L
            return true
        }
        return false
    }

    /** Clears a speculative manual target when transport stayed on the current song (for example previous->restart). */
    fun cancelManualNavigationExpectation() {
        // Cancel only the speculative identity owned by this command. Do not wipe unrelated pending
        // manual keys from a rapid navigation burst.
        expectedHintKey?.let(pendingManualNavigationKeys::remove)
        if (expectedHintKey == null && pendingUnknownManualBindings > 0) {
            pendingUnknownManualBindings -= 1
            if (pendingUnknownManualBindings == 0) unknownManualBindingDeadlineMs = 0L
        }
        directionHint = null
        directionHintDeadlineMs = 0L
        expectedHintKey = null
        clearExpectedPlayerBinding()
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = false
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
        // preceding swipe. The next player emission is authoritative, but it is still manual.
        clearExpectedPlayerBinding()
        armUnknownManualBinding()
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 4_000L
        expectedHintKey = null
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
    }

    internal fun setGestureTargetKeys(previousKey: String?, nextKey: String?) {
        if (isGestureActive || isSettling || pendingCommit) {
            deferredGesturePreviousKey = previousKey
            deferredGestureNextKey = nextKey
            hasDeferredGestureKeys = true
            return
        }
        applyGestureTargetKeys(previousKey, nextKey)
    }

    private fun applyGestureTargetKeys(previousKey: String?, nextKey: String?) {
        val newPreviousKey = previousKey.orEmpty()
        val newNextKey = nextKey.orEmpty()
        if (gesturePreviousKey != newPreviousKey) {
            gesturePreviousSlot = retainMatchingGestureSlot(gesturePreviousSlot, newPreviousKey)
        }
        if (gestureNextKey != newNextKey) {
            gestureNextSlot = retainMatchingGestureSlot(gestureNextSlot, newNextKey)
        }
        gesturePreviousKey = newPreviousKey
        gestureNextKey = newNextKey
        // Keep exact previous/current/next holders alive while idle. Ordinary recomposition must
        // not destroy prefetched neighbours; only an identity change may release a holder.
        releaseUnusedVisuals()
        notifyPresentationObservers(topologyChanged = true)
    }

    private fun applyDeferredGestureTargetKeys() {
        if (!hasDeferredGestureKeys || isGestureActive || isSettling || pendingCommit) return
        val previousKey = deferredGesturePreviousKey
        val nextKey = deferredGestureNextKey
        deferredGesturePreviousKey = null
        deferredGestureNextKey = null
        hasDeferredGestureKeys = false
        applyGestureTargetKeys(previousKey, nextKey)
    }

    internal fun prefetchGestureTargets(previousKey: String?, nextKey: String?) {
        primeGestureSlot(PlayerArtworkDirection.Previous, previousKey)
        primeGestureSlot(PlayerArtworkDirection.Next, nextKey)
        releaseUnusedVisuals()
    }

    /** App-level player surfaces publish the exact adjacent identities without exposing slot internals. */
    fun updateNavigationNeighbourKeys(previousKey: String?, nextKey: String?) {
        setGestureTargetKeys(previousKey, nextKey)
    }

    /** Prewarms both adjacent artwork holders so landscape and portrait share the same hot AA lane. */
    fun prefetchNavigationNeighbours(previousKey: String?, nextKey: String?) {
        prefetchGestureTargets(previousKey, nextKey)
    }

    private fun primeGestureSlot(direction: PlayerArtworkDirection, rawKey: String?) {
        val key = rawKey?.takeIf { it.isNotBlank() && it != currentKey && it != targetKey } ?: return
        val existing = gestureSlot(direction)
        if (existing?.key == key && visuals.containsKey(existing.token)) {
            requestArtwork(key)
            return
        }
        existing?.token?.takeIf { it != 0 && it != currentToken && it != targetToken }?.let { token ->
            visuals.remove(token)?.release()
        }

        val cachedHandle = BitmapProvider.acquireAny(
            key = key,
            surface = ArtworkSurface.Playback,
            minimumSide = 1,
        ) ?: CoilArtworkRuntime.peekBitmap(
            context = context,
            key = key,
            width = ARTWORK_TARGET_SIDE,
            height = ARTWORK_TARGET_SIDE,
            surface = ArtworkSurface.Playback,
        )?.takeUnless(Bitmap::isRecycled)?.let { cached ->
            BitmapProvider.acquireLoaded(
                key = key,
                bitmap = cached,
                targetWidth = ARTWORK_TARGET_SIDE,
                targetHeight = ARTWORK_TARGET_SIDE,
                surface = ArtworkSurface.Playback,
            )
        }
        if (cachedHandle?.isValid == true) {
            val token = nextToken()
            val visual = ArtworkVisual(
                key = key,
                bitmap = cachedHandle.bitmap,
                quality = ArtworkDisplayResolver.QUALITY_ANY,
                handle = cachedHandle,
                ownedBitmap = false,
            )
            visuals[token] = visual
            setGestureSlot(direction, GestureArtworkSlot(key, token, visual.quality))
            notifyPresentationObservers(topologyChanged = true)
        } else {
            cachedHandle?.release()
        }
        // Warm provider low/high tiers before the user requests the page. Reference's three AAItemView
        // holders are persistent, but an identity without a wrapper is not painted as a fake
        // "no album art" image. Keep the physical neighbour absent until a real/terminal wrapper is
        // known instead of manufacturing a synthetic visual that flashes before the real cover.
        BitmapProvider.warmPlaybackArt(key)
        requestArtwork(key)
    }

    internal fun gestureTargetKey(direction: PlayerArtworkDirection): String? = when (direction) {
        PlayerArtworkDirection.Previous -> gesturePreviousKey
        PlayerArtworkDirection.Next -> gestureNextKey
    }.takeIf { it.isNotBlank() }

    /**
     * Cross-module transport facade used by app-level player surfaces. The underlying gesture-slot
     * bookkeeping stays internal to core/ui; callers only receive the exact manual target identity.
     */
    fun manualNavigationTargetKey(direction: PlayerArtworkDirection): String? =
        pendingGestureTarget(direction) ?: gestureTargetKey(direction)

    /**
     * Confirms the exact song/index selected by a manual transport command without starting a second
     * visual settle. This is used after a gesture whose existing transition already owns the motion.
     */
    fun confirmManualNavigationBinding(key: String, queueIndex: Int) {
        expectPlayerBinding(key, queueIndex)
    }

    /**
     * Reconciles the exact identity returned by a button/queue transport command without starting
     * artwork motion. Reference starts programmatic AA movement from the subsequent committed
     * current-track/index update (C0661.s0 -> B0 -> PowerList.c0), not from the button callback.
     */
    fun confirmManualTransportDispatch(
        direction: PlayerArtworkDirection,
        key: String,
        queueIndex: Int,
    ) {
        if (closed || key.isBlank() || key == currentKey || key == targetKey || key == boundSongKey) return
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 4_000L
        expectedHintKey = key
        markManualNavigationTarget(key)
        expectPlayerBinding(key, queueIndex)
    }

    /** True only while a manual button/queue programmatic page move owns the backdrop. */
    fun shouldFreezeBackdropMotionForManualProgrammaticTransition(): Boolean =
        manualProgrammaticTransition && targetKey.isNotBlank()

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
        manualProgrammaticTransition = false
        isSettling = false
        isGestureActive = true
        pendingCommit = false
        // A pointer grab is authoritative over an in-flight natural dissolve. When it takes over the
        // same prepared target, beginTarget() would otherwise preserve the existing automatic flags
        // and targetAutoSettle, leaving the auto-only lane visible instead of a draggable C0889 page.
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
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
     * Keep three item holders alive for the complete pointer sequence. Compose only
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
        manualProgrammaticTransition = false
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
        writeUiRatioState(0f)

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
        onCommit: () -> Unit
    ): Boolean {
        if (!isGestureActive || closed) return false
        val safeWidth = artworkWidthPx.coerceAtLeast(1f)
        val velocityFractionPerSecond = velocityPxPerSecond / safeWidth
        val towardTargetSpeed = -velocityFractionPerSecond * direction.sign
        val effectiveProgress = ratio.coerceIn(0f, 1f)
        val commit = shouldCommitPlaybackArtworkSwipe(
            progress = effectiveProgress,
            direction = direction,
            velocityPxPerSecond = velocityPxPerSecond,
        )
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
     * Compatibility entry point for callers that still package visual preparation and dispatch.
     * Transport is dispatched first; the visual lane starts only after that dispatch.
     */
    fun animateNavigation(
        direction: PlayerArtworkDirection,
        expectedKey: String?,
        expectedQueueIndex: Int = -1,
        onCommit: () -> Unit,
    ): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() } ?: return false
        if (closed || key == currentKey || isGestureActive) return false
        armManualNavigation(direction, key, expectedQueueIndex)
        onCommit()
        // The committed player binding owns programmatic motion. Do not speculate a 0->1 visual
        // transition from this callback; that is precisely what made button switching differ from
        // C0661.s0 -> PowerList.c0.
        return true
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
                // Late/intermediate confirmation from an earlier command. A key that is already in
                // the multi-page programmatic destination queue is still a genuine manual binding,
                // so consume its lease before ignoring the visual rebind; otherwise that stale lease
                // could misclassify a later natural advance to the same song.
                if (queuedProgrammaticTargets.any { it.key == key }) {
                    consumeManualNavigationBinding(key)
                    boundSongKey = key
                }
                return
            }
        }

        val oldIndex = lastQueueIndex
        lastQueueIndex = queueIndex
        boundSongKey = key
        val manualNavigationBinding = consumeManualNavigationBinding(key)
        if (queuedProgrammaticTargets.any { it.key == key }) {
            // The newest player identity may arrive before the visual scroller crosses the previous
            // physical page. C0858 does not snap that page to centre first; the existing fractional
            // scroller owns the crossing and will install this exact key at the boundary.
            return
        }

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
            requestTargetAutoSettle()
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
        val queueIndexAdvanced = oldIndex >= 0 && queueIndex >= 0 && oldIndex != queueIndex && queueSize > 1
        // currentSong and queue.currentIndex are published independently. Manual identity/direction
        // is itself enough proof of navigation when the index sample is one frame stale; otherwise
        // the new key is misclassified as a same-position metadata refresh and snaps with no C0889
        // motion. Natural advances are resolved by callers from the exact currentSong identity in
        // the same queue snapshot.
        val queueAdvanced = queueIndexAdvanced ||
            (queueSize > 1 && (manualNavigationBinding || hinted != null))
        val isNaturalQueueAdvance = isNaturalPlaybackArtworkAdvance(
            queueAdvanced = queueAdvanced,
            manualNavigationBinding = manualNavigationBinding,
            hasManualDirectionHint = hinted != null,
        )
        manualProgrammaticTransition = queueAdvanced && !isNaturalQueueAdvance
        if (queueAdvanced) {
            beginTarget(
                resolvedDirection,
                key,
                autoSettle = true,
                fadeOnly = isNaturalQueueAdvance,
                autoCrossfadeFade = shouldUseAutomaticCrossfadeArtwork(
                    automaticCrossfadeEnabled = automaticCrossfadeVisualEnabled,
                    isNaturalQueueAdvance = isNaturalQueueAdvance,
                ),
                settleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS,
                settleCurve = ArtworkSettleCurve.Programmatic,
            )
        } else {
            direction = resolvedDirection
            val rewriteKind = PlaybackArtworkKeyContinuity.rewriteKind(
                previousKey = currentKey,
                committedKey = key,
            )
            val retainedCurrentHolder = when (rewriteKind) {
                PlaybackArtworkKeyContinuity.RewriteKind.MetadataOnly ->
                    retainCurrentVisualForMetadataRewrite(key)
                PlaybackArtworkKeyContinuity.RewriteKind.ArtworkChanged ->
                    rebindCurrentHolderForArtworkRewrite(key)
                null -> false
            }
            if (!retainedCurrentHolder) {
                resetToBoundSong(key)
            }
        }
    }

    internal fun visual(token: Int): Bitmap? = visuals[token]?.bitmap?.takeUnless { it.isRecycled }

    internal fun visualQuality(token: Int): Int = visuals[token]?.quality ?: 0

    /**
     * Artwork that is safe to use as an album-derived background source.
     *
     * Synthetic transition placeholders intentionally reuse a generic bitmap while the exact
     * neighbour is still loading. Feeding that bitmap into RawFlow palette extraction would cache
     * the same fake colors under different song identities. Reference's Milk AA state carries the
     * real AA id, never a generic stand-in identity; keep the same ownership rule here.
     */
    internal fun backgroundArtwork(token: Int): Bitmap? = visuals[token]
        ?.takeUnless { it.syntheticPlaceholder || it.bitmap.isRecycled }
        ?.bitmap

    fun backgroundLayers(): List<PlaybackArtworkBackgroundLayer> = buildList {
        val current = visuals[currentToken]?.takeUnless { it.bitmap.isRecycled }
        val target = visuals[targetToken]?.takeUnless { it.bitmap.isRecycled }

        // Keep both prepared backgrounds composed for the full transition. Reference keeps its AA
        // holders alive and only changes properties; adding the incoming RawFlow subtree on the
        // first non-zero motion frame caused a visible hitch exactly when the artwork started moving.
        current?.let {
            add(
                PlaybackArtworkBackgroundLayer(
                    token = currentToken,
                    key = it.key,
                    role = PlaybackArtworkBackgroundRole.Current,
                )
            )
        }
        target?.let {
            add(
                PlaybackArtworkBackgroundLayer(
                    token = targetToken,
                    key = it.key,
                    role = PlaybackArtworkBackgroundRole.Target,
                )
            )
        }
    }

    /** Read from a graphicsLayer lambda so ratio changes stay in the layer phase, not composition. */
    fun backgroundLayerAlpha(role: PlaybackArtworkBackgroundRole): Float = when (role) {
        PlaybackArtworkBackgroundRole.Current -> 1f
        PlaybackArtworkBackgroundRole.Target -> {
            val progress = ratio.coerceIn(0f, 1f)
            if (automaticCrossfadeArtworkFade) smoothArtworkFadeProgress(progress) else progress
        }
    }

    internal fun foregroundCurrentToken(): Int = currentToken
    internal fun foregroundTargetToken(): Int = targetToken
    internal fun foregroundPreviousParkedToken(): Int = gesturePreviousSlot?.token ?: 0
    internal fun foregroundNextParkedToken(): Int = gestureNextSlot?.token ?: 0
    internal fun foregroundPreviousParkedKey(): String = gesturePreviousSlot?.key.orEmpty()
    internal fun foregroundNextParkedKey(): String = gestureNextSlot?.key.orEmpty()
    internal fun foregroundItemExtentPx(): Float = foregroundItemExtentPx
    internal fun updateForegroundItemExtentPx(value: Float) {
        if (value.isFinite() && value > 1f) foregroundItemExtentPx = value
    }
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

    fun foregroundCurrentKey(): String = currentKey
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

    internal fun setExternalSceneArtworkMotionActive(active: Boolean) {
        if (closed || externalSceneArtworkMotionActive == active) return
        externalSceneArtworkMotionActive = active
        if (!active) applyPendingCurrentArtworkRewriteVisual()
    }

    private fun applyPendingCurrentArtworkRewriteVisual() {
        val pending = pendingCurrentArtworkRewriteVisual ?: return
        pendingCurrentArtworkRewriteVisual = null
        if (closed || currentToken == 0 || pending.key != currentKey) {
            pending.release()
            deferCurrentArtworkRewriteForSceneMotion = false
            return
        }
        replaceVisual(currentToken, pending)
        deferCurrentArtworkRewriteForSceneMotion = false
        publishFrame(0L)
    }

    private fun clearPendingCurrentArtworkRewriteVisual() {
        pendingCurrentArtworkRewriteVisual?.release()
        pendingCurrentArtworkRewriteVisual = null
        deferCurrentArtworkRewriteForSceneMotion = false
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
        settleDurationMs: Int = ARTWORK_GESTURE_SETTLE_MS,
        settleCurve: ArtworkSettleCurve = ArtworkSettleCurve.Gesture,
    ) {
        if (closed || key.isBlank() || key == currentKey) return
        // A track navigation supersedes any pending same-song cover rewrite. Do not retain that
        // decoded wrapper after its physical centre holder becomes outgoing.
        clearPendingCurrentArtworkRewriteVisual()
        if (targetKey == key) {
            direction = newDirection
            automaticFadeOnly = automaticFadeOnly || fadeOnly
            automaticCrossfadeArtworkFade = automaticCrossfadeArtworkFade || autoCrossfadeFade
            targetAutoSettle = targetAutoSettle || autoSettle
            if (autoSettle) {
                targetSettleDurationMs = settleDurationMs
                targetSettleCurve = settleCurve
            }
            requestArtwork(key)
            requestTargetAutoSettle()
            return
        }

        resolveInterruptedTargetForNewRequest()
        direction = newDirection
        automaticFadeOnly = fadeOnly
        automaticCrossfadeArtworkFade = autoCrossfadeFade
        suppressedAutomaticOutgoingToken = 0
        targetKey = key
        var parkedTarget = takeGestureSlot(newDirection, key)
        if (autoSettle && parkedTarget != null && visuals[parkedTarget.token]?.syntheticPlaceholder == true) {
            visuals.remove(parkedTarget.token)?.release()
            parkedTarget = null
        }
        targetToken = parkedTarget?.also { slot ->
            targetQuality = slot.quality
        }?.token ?: 0
        if (targetToken == 0) targetQuality = 0
        targetAutoSettle = autoSettle
        targetSettleDurationMs = settleDurationMs
        targetSettleCurve = settleCurve
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
            // Direct manipulation needs a drawable neighbour under the finger. Programmatic C0858
            // movement is different: never start it with a synthetic "no-art" bitmap. The normal
            // previous/current/next prewarm should already have a wrapper; on a cold miss we keep
            // the outgoing holder stable for the brief provider admission and start the exact-key
            // move as soon as the real or terminal wrapper arrives.
            installPlaceholder(key, primary = false)
        }
        requestTargetAutoSettle()
    }

    /**
     * Starts C0858-style movement once the exact target holder has a drawable wrapper.
     *
     * Reference keeps three AAItemView holders attached and normally has the adjacent wrapper request
     * already in flight before c0(index) moves it. Raw cannot recreate that guarantee with a fake
     * no-art bitmap: doing so visibly flashes placeholder -> real cover. We therefore prewarm the
     * neighbours continuously and, only on a true cold miss, admit the programmatic move when the
     * exact real/terminal wrapper reaches the persistent target holder. Once admitted, wrapper
     * upgrades remain local and never restart the outer C0889 motion clock.
     */
    private fun requestTargetAutoSettle() {
        if (closed || !targetAutoSettle || isGestureActive || targetToken == 0) return
        if (isSettling) return
        if (
            targetSettleCurve == ArtworkSettleCurve.Programmatic &&
            manualProgrammaticTransition &&
            queuedProgrammaticTargets.isNotEmpty()
        ) {
            restartProgrammaticChainFromCurrentFraction()
        } else {
            settleTo(1f, 0f)
        }
    }

    private fun restartProgrammaticChainFromCurrentFraction() {
        if (
            closed ||
            targetToken == 0 ||
            targetKey.isBlank() ||
            targetSettleCurve != ArtworkSettleCurve.Programmatic ||
            isGestureActive
        ) return

        val retainedVelocity = programmaticVelocityPagesPerSecond.coerceAtLeast(0f)
        transitionJob?.cancel()
        isSettling = true
        val startFraction = ratio.coerceIn(0f, 1f)
        val queuedCountAtStart = queuedProgrammaticTargets.size
        val destinationPages = 1f + queuedCountAtStart.toFloat()
        val distancePages = (destinationPages - startFraction).coerceAtLeast(0.001f)
        // C0858.K() retargets from the live fractional position but keeps aaPosAnimTime as the base
        // clock; retained velocity shortens that clock rather than making multi-page requests take
        // one additional 550 ms block per page.
        val durationMs = programmaticRetargetDurationMs(
            startFraction = startFraction,
            destinationPages = destinationPages,
            retainedVelocityPagesPerSecond = retainedVelocity,
        )

        transitionJob = scope.launch {
            // The target holder is already attached. Do not burn the first Choreographer callback
            // merely to capture an epoch: that creates one completely stationary vsync before the
            // first C0889 property update. Reference's persistent-holder path starts advancing on the
            // first frame callback after the command is accepted. Choreographer frameTimeNanos and
            // System.nanoTime() share the monotonic nano-time domain.
            val startNs = System.nanoTime()
            var previousFrameNs = startNs
            var previousAbsolutePosition = startFraction
            var committedBoundaries = 0
            while (true) {
                val now = withFrameNanos { it }
                val linear = ((now - startNs).toDouble() /
                    (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
                val eased = ReferenceProgrammaticArtworkEasing(linear, durationMs)
                val absolutePosition = startFraction + distancePages * eased
                val deltaSeconds = (now - previousFrameNs).coerceAtLeast(1L) / 1_000_000_000f
                programmaticVelocityPagesPerSecond =
                    ((absolutePosition - previousAbsolutePosition) / deltaSeconds).coerceAtLeast(0f)
                previousFrameNs = now
                previousAbsolutePosition = absolutePosition

                while (
                    committedBoundaries < queuedCountAtStart &&
                    absolutePosition >= (committedBoundaries + 1).toFloat()
                ) {
                    commitProgrammaticBoundaryWithoutStopping()
                    val next = queuedProgrammaticTargets.pollFirst() ?: break
                    installProgrammaticChainTarget(next)
                    committedBoundaries += 1
                    if (targetToken == 0) {
                        // A rapid multi-next can outrun the third persistent holder. Do not cross the
                        // next page with a fabricated no-art bitmap. Park exactly at this committed
                        // boundary; acceptHandle() will call requestTargetAutoSettle() and resume the
                        // remaining exact-key chain when the wrapper arrives.
                        updateUiRatio(0f)
                        isSettling = false
                        return@launch
                    }
                }

                val localFraction = (absolutePosition - committedBoundaries.toFloat()).coerceIn(0f, 1f)
                updateUiRatio(localFraction)
                if (linear >= 1f) break
            }
            setRatioFromUi(1f)
            finishSettle(1f)
        }
    }

    private fun commitProgrammaticBoundaryWithoutStopping() {
        if (targetToken == 0 || targetKey.isBlank()) return
        val outgoingKey = currentKey
        val outgoingToken = currentToken
        val outgoingQuality = currentQuality
        generation += 1
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        currentKey = targetKey
        currentToken = targetToken
        currentQuality = targetQuality
        clearGestureSlotByToken(currentToken)
        rotateOutgoingIntoOppositeHolder(outgoingKey, outgoingToken, outgoingQuality)
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        writeUiRatioState(0f)
        suppressedAutomaticOutgoingToken = 0
        publishFrame(0L)
    }

    private fun installProgrammaticChainTarget(next: ProgrammaticRetarget) {
        direction = next.direction
        targetKey = next.key
        targetQuality = 0
        targetAutoSettle = true
        targetSettleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS
        targetSettleCurve = ArtworkSettleCurve.Programmatic
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = true
        pendingCommit = false

        var parkedTarget = takeGestureSlot(next.direction, next.key)
        if (parkedTarget != null && visuals[parkedTarget.token]?.syntheticPlaceholder == true) {
            visuals.remove(parkedTarget.token)?.release()
            parkedTarget = null
        }
        targetToken = parkedTarget?.also { targetQuality = it.quality }?.token ?: 0
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
            installCachedTarget(next.key)
        }
        BitmapProvider.warmPlaybackArt(next.key)
        requestArtwork(next.key)
        releaseUnusedVisuals()
    }

    private fun installCachedTarget(key: String) {
        val cachedHandle = BitmapProvider.acquireAny(
            key = key,
            surface = ArtworkSurface.Playback,
            minimumSide = 1,
        ) ?: CoilArtworkRuntime.peekBitmap(
            context = context,
            key = key,
            width = ARTWORK_TARGET_SIDE,
            height = ARTWORK_TARGET_SIDE,
            surface = ArtworkSurface.Playback
        )?.takeUnless(Bitmap::isRecycled)?.let { cached ->
            BitmapProvider.acquireLoaded(
                key = key,
                bitmap = cached,
                targetWidth = ARTWORK_TARGET_SIDE,
                targetHeight = ARTWORK_TARGET_SIDE,
                surface = ArtworkSurface.Playback,
            )
        } ?: return
        if (!cachedHandle.isValid) {
            cachedHandle.release()
            return
        }
        targetToken = nextToken()
        targetQuality = ArtworkDisplayResolver.QUALITY_ANY
        visuals[targetToken] = ArtworkVisual(
            key = key,
            bitmap = cachedHandle.bitmap,
            quality = ArtworkDisplayResolver.QUALITY_ANY,
            handle = cachedHandle,
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
        queuedProgrammaticTargets.clear()
        if (targetKey.isBlank()) {
            writeUiRatioState(0f)
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
        val outgoingKey = currentKey
        val outgoingToken = currentToken
        val outgoingQuality = currentQuality
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        generation += 1
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        currentKey = targetKey
        currentToken = targetToken
        currentQuality = targetQuality
        clearGestureSlotByToken(currentToken)
        rotateOutgoingIntoOppositeHolder(outgoingKey, outgoingToken, outgoingQuality)
        writeUiRatioState(0f)
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        targetAutoSettle = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = false
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

    /**
     * C0889 keeps physical previous/current/next holders and rotates their roles after a commit.
     * Reusing the outgoing centre as the opposite neighbour avoids destroying and recreating the
     * same bitmap/layer at every track boundary.
     */
    private fun rotateOutgoingIntoOppositeHolder(
        outgoingKey: String,
        outgoingToken: Int,
        outgoingQuality: Int,
    ) {
        if (outgoingKey.isBlank() || outgoingToken == 0 || outgoingToken == currentToken) return
        val opposite = when (direction) {
            PlayerArtworkDirection.Next -> PlayerArtworkDirection.Previous
            PlayerArtworkDirection.Previous -> PlayerArtworkDirection.Next
        }
        // The holder on that side referred to a track two positions away before the commit. Drop
        // only that stale role; releaseUnusedVisuals() will reclaim its token after the new role is set.
        setGestureSlot(opposite, null)
        parkGestureSlot(opposite, outgoingKey, outgoingToken, outgoingQuality)
    }

    private fun discardTarget() {
        transitionJob?.cancel()
        isSettling = false
        programmaticVelocityPagesPerSecond = 0f
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
        manualProgrammaticTransition = false
        pendingCommit = false
        writeUiRatioState(0f)
        suppressedAutomaticOutgoingToken = 0
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    /**
     * Rebinds a metadata-only file-version key without touching the displayed artwork token.
     *
     * A tag rewrite changes fileSize/dateModified, which is intentionally part of the normal
     * artwork cache identity. When the metadata editor has explicitly proved that artwork bytes did
     * not change, keep the current decoded/provider wrapper and native RenderNode token intact.
     * This avoids a bitmap decode/texture/palette burst immediately before PLAYER <-> LYRIC motion.
     * The ArtworkVisual keeps its previous visual key on purpose so background/palette owners also
     * retain the same visual identity until a genuine artwork mutation or track change occurs.
     */
    private fun retainCurrentVisualForMetadataRewrite(key: String): Boolean {
        if (
            closed ||
            key.isBlank() ||
            currentToken == 0 ||
            isGestureActive ||
            isSettling ||
            targetKey.isNotBlank()
        ) return false
        val currentVisual = visuals[currentToken] ?: return false
        if (currentVisual.syntheticPlaceholder || currentVisual.bitmap.isRecycled) return false

        val previousKey = currentKey
        if (previousKey.isBlank() || previousKey == key) return previousKey == key
        clearLoad(previousKey)
        clearLoad(key)
        currentKey = key
        boundSongKey = key
        // Token, bitmap, provider handle, generation and native lane stay unchanged. Only the
        // logical song binding advances to the post-metadata file version.
        notifyPresentationObservers(topologyChanged = false)
        return true
    }

    /**
     * Preserve the current AA holder while intentionally reloading changed artwork bytes.
     *
     * Lyrico/embedded-cover writers replace the file, so the versioned playback key legitimately
     * changes. Rebuilding the entire current holder at that point caused a blank/texture burst that
     * could overlap the user's immediate PLAYER <-> LYRIC gesture. Keep the existing token and old
     * pixels visible, rebind the logical key, and request the new wrapper into the same holder. The
     * View-backed AAImageView analogue performs the eventual 200 ms image-local shader fade.
     */
    private fun rebindCurrentHolderForArtworkRewrite(key: String): Boolean {
        if (
            closed ||
            key.isBlank() ||
            currentToken == 0 ||
            isGestureActive ||
            isSettling ||
            targetKey.isNotBlank()
        ) return false
        val currentVisual = visuals[currentToken] ?: return false
        if (currentVisual.syntheticPlaceholder || currentVisual.bitmap.isRecycled) return false

        val previousKey = currentKey
        if (previousKey.isBlank() || previousKey == key) return previousKey == key
        clearLoad(previousKey)
        clearLoad(key)
        currentKey = key
        boundSongKey = key
        // The existing bitmap remains displayed until the new key resolves. Reset only the quality
        // admission threshold so even a valid low wrapper may replace the previous-version pixels.
        currentQuality = 0
        deferCurrentArtworkRewriteForSceneMotion = true
        requestArtwork(key)
        notifyPresentationObservers(topologyChanged = false)
        return true
    }

    private fun resetToBoundSong(key: String) {
        val oldCurrentToken = currentToken
        val oldCurrentKey = currentKey
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        clearPendingCurrentArtworkRewriteVisual()
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
        // Keep a provider low/high request in flight alongside the exact full player decode. This
        // lets the persistent neighbour bind a usable wrapper before a manual switch in the common
        // case and mirrors AAImageView.J() being issued while the holder is still parked off-centre.
        BitmapProvider.warmPlaybackArt(key)
        var lowProviderRequest: BitmapRequest? = null
        lowProviderRequest = BitmapProvider.loadThumbnail(
            key = key,
            targetWidth = AlbumArtTiers.LOW_RES_NORMAL_CAP,
            targetHeight = AlbumArtTiers.LOW_RES_NORMAL_CAP,
            priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
            surface = ArtworkSurface.Playback,
        ) { lowBitmap ->
            if (lowBitmap != null && !lowBitmap.isRecycled) {
                val lowHandle = BitmapProvider.acquireLoaded(
                    key = key,
                    bitmap = lowBitmap,
                    targetWidth = AlbumArtTiers.LOW_RES_NORMAL_CAP,
                    targetHeight = AlbumArtTiers.LOW_RES_NORMAL_CAP,
                    surface = ArtworkSurface.Playback,
                )
                if (lowHandle != null) {
                    acceptHandle(key, lowHandle, ArtworkDisplayResolver.QUALITY_ANY)
                }
            } else if (lowProviderRequest?.terminalNoArt == true) {
                // Provider no-art is authoritative. Coil/full-player misses are not: the provider
                // may still be extracting embedded/folder art on its source lane. Treating the
                // first independent miss as terminal is what produced a visible no-art frame
                // immediately before the real wrapper arrived.
                acceptTerminalNoArtwork(key)
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = CoilArtworkRuntime.executeBitmap(
                context = context,
                key = key,
                width = ARTWORK_TARGET_SIDE,
                height = ARTWORK_TARGET_SIDE,
                surface = ArtworkSurface.Playback
            )
            if (bitmap != null && !bitmap.isRecycled) {
                val providerHandle = BitmapProvider.acquireLoaded(
                    key = key,
                    bitmap = bitmap,
                    targetWidth = ARTWORK_TARGET_SIDE,
                    targetHeight = ARTWORK_TARGET_SIDE,
                    surface = ArtworkSurface.Playback,
                )
                val stableHandle = providerHandle ?: run {
                    // Extremely defensive fallback: keep a transition-private immutable copy if the
                    // provider cannot attach the decoded bitmap. This copy is never manually recycled.
                    val stableCopy = runCatching {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    stableCopy?.let {
                        ArtworkHandle(
                            sourceKey = key,
                            tier = ArtworkTier.Full,
                            surface = ArtworkSurface.Playback,
                            bitmap = it,
                            onRelease = {}
                        )
                    }
                }
                if (stableHandle != null) {
                    acceptHandle(
                        key = key,
                        handle = stableHandle,
                        quality = ArtworkDisplayResolver.QUALITY_HIGH
                    )
                }
            }
            // A miss in this independent full-player path is not proof of no artwork. The provider
            // request above owns terminal/no-art classification because it can resolve embedded,
            // folder and indexed artwork sources.
        }
        loads[key] = ArtworkLoad(job)
        job.invokeOnCompletion {
            mainHandler.post {
                if (loads[key]?.job === job) loads.remove(key)
            }
        }
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
                val pendingQuality = pendingCurrentArtworkRewriteVisual?.quality ?: 0
                if (currentToken != 0 && quality <= maxOf(currentQuality, pendingQuality)) {
                    handle.release()
                    return
                }
                if (currentToken == 0) {
                    clearPendingCurrentArtworkRewriteVisual()
                    currentToken = nextToken()
                    currentQuality = quality
                    visuals[currentToken] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                    NativePlayerArtworkBridge.setArtwork(nativeHandle, currentToken, true, generation, 0)
                    publishFrame(0L)
                } else {
                    val incoming = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                    currentQuality = quality
                    if (deferCurrentArtworkRewriteForSceneMotion && externalSceneArtworkMotionActive) {
                        // Preserve the frozen shared-artwork pixels for the complete PLAYER<->LYRIC
                        // gesture. Only the latest/highest wrapper is retained; no bitmap/texture
                        // owner is swapped on a moving scene frame.
                        pendingCurrentArtworkRewriteVisual?.release()
                        pendingCurrentArtworkRewriteVisual = incoming
                        return
                    }
                    val appliedNow = replaceVisual(currentToken, incoming)
                    deferCurrentArtworkRewriteForSceneMotion = false
                    if (appliedNow) publishFrame(0L)
                }
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
                    val appliedNow = replaceVisual(
                        targetToken,
                        ArtworkVisual(key, handle.bitmap, quality, handle, false),
                    )
                    targetQuality = quality
                    if (appliedNow) publishFrame(0L)
                }
                if (targetAutoSettle) requestTargetAutoSettle()
                else if (pendingCommit && !isGestureActive && !isSettling) settleTo(1f, 0f)
            }
            boundSongKey -> {
                // External track change reached us before prepare/bind could establish a target.
                direction = directionHint ?: PlayerArtworkDirection.Next
                beginTarget(direction, key, autoSettle = true, settleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS, settleCurve = ArtworkSettleCurve.Programmatic)
                acceptHandle(key, handle, quality)
            }
            else -> {
                val parked = parkedGestureSlotForKey(key)
                if (parked != null) {
                    if (quality <= parked.second.quality) {
                        handle.release()
                    } else {
                        val appliedNow = replaceVisual(
                            parked.second.token,
                            ArtworkVisual(key, handle.bitmap, quality, handle, false),
                        )
                        setGestureSlot(
                            parked.first,
                            parked.second.copy(quality = quality),
                        )
                        if (appliedNow) publishFrame(0L)
                    }
                } else {
                    val prefetchDirection = when (key) {
                        gesturePreviousKey -> PlayerArtworkDirection.Previous
                        gestureNextKey -> PlayerArtworkDirection.Next
                        else -> null
                    }
                    if (prefetchDirection == null) {
                        handle.release()
                    } else {
                        val token = nextToken()
                        visuals[token] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                        setGestureSlot(prefetchDirection, GestureArtworkSlot(key, token, quality))
                        notifyPresentationObservers(topologyChanged = true)
                        releaseUnusedVisuals()
                    }
                }
            }
        }
    }

    private fun installPlaceholder(
        key: String,
        primary: Boolean,
        side: Int = 512,
        synthetic: Boolean = true,
    ) {
        if (closed || key.isBlank()) return
        val placeholderSide = side.coerceIn(256, 768)
        val bitmap = if (DefaultAlbumArtworkPolicy.enabled) {
            decodeDefaultAlbumArtwork(context.resources, placeholderSide)
                ?: createPlaceholderBitmap(placeholderSide)
        } else {
            createPlaceholderBitmap(placeholderSide)
        }
        val token = nextToken()
        visuals[token] = ArtworkVisual(
            key = key,
            bitmap = bitmap,
            quality = ArtworkDisplayResolver.QUALITY_ANY,
            handle = null,
            ownedBitmap = true,
            syntheticPlaceholder = synthetic,
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
        if (!primary && !synthetic && targetAutoSettle) requestTargetAutoSettle()
    }

    private fun acceptTerminalNoArtwork(key: String, side: Int = 768) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { acceptTerminalNoArtwork(key, side) }
            return
        }
        if (closed) return
        loads.remove(key)
        if (key != currentKey && key != targetKey) {
            val prefetchDirection = when (key) {
                gesturePreviousKey -> PlayerArtworkDirection.Previous
                gestureNextKey -> PlayerArtworkDirection.Next
                else -> null
            } ?: return
            if (parkedGestureSlotForKey(key) == null) {
                val terminalSide = side.coerceIn(256, 768)
                val terminalBitmap = if (DefaultAlbumArtworkPolicy.enabled) {
                    decodeDefaultAlbumArtwork(context.resources, terminalSide)
                        ?: createPlaceholderBitmap(terminalSide)
                } else {
                    createPlaceholderBitmap(terminalSide)
                }
                val token = nextToken()
                visuals[token] = ArtworkVisual(
                    key = key,
                    bitmap = terminalBitmap,
                    quality = ArtworkDisplayResolver.QUALITY_ANY,
                    handle = null,
                    ownedBitmap = true,
                    syntheticPlaceholder = false,
                )
                setGestureSlot(
                    prefetchDirection,
                    GestureArtworkSlot(key, token, ArtworkDisplayResolver.QUALITY_ANY),
                )
                notifyPresentationObservers(topologyChanged = true)
                releaseUnusedVisuals()
            }
            return
        }
        if (DefaultAlbumArtworkPolicy.enabled) {
            installDefaultArtwork(key, primary = key == currentKey, side = side)
        } else if (key == currentKey) {
            if (currentToken == 0) {
                installPlaceholder(key, primary = true, side = side, synthetic = false)
            } else {
                // A proven artwork rewrite can also remove embedded art. Do not leave the old
                // cover stuck in the preserved physical holder if the replacement resolves to
                // terminal no-art. Apply the stable fallback through the same scene-motion gate.
                val placeholderSide = side.coerceIn(256, 768)
                val terminalBitmap = createPlaceholderBitmap(placeholderSide)
                val terminalVisual = ArtworkVisual(
                    key = key,
                    bitmap = terminalBitmap,
                    quality = ArtworkDisplayResolver.QUALITY_ANY,
                    handle = null,
                    ownedBitmap = true,
                    syntheticPlaceholder = false,
                )
                currentQuality = ArtworkDisplayResolver.QUALITY_ANY
                if (deferCurrentArtworkRewriteForSceneMotion && externalSceneArtworkMotionActive) {
                    pendingCurrentArtworkRewriteVisual?.release()
                    pendingCurrentArtworkRewriteVisual = terminalVisual
                } else {
                    replaceVisual(currentToken, terminalVisual)
                    deferCurrentArtworkRewriteForSceneMotion = false
                    publishFrame(0L)
                }
            }
        } else if (key == targetKey && targetToken == 0) {
            installPlaceholder(key, primary = false, side = side, synthetic = false)
            if (targetAutoSettle) requestTargetAutoSettle()
            else if (pendingCommit && !isGestureActive && !isSettling) settleTo(1f, 0f)
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
        var installedNow = true
        if (existingToken != 0) {
            if (primary && deferCurrentArtworkRewriteForSceneMotion && externalSceneArtworkMotionActive) {
                pendingCurrentArtworkRewriteVisual?.release()
                pendingCurrentArtworkRewriteVisual = visual
                currentQuality = ArtworkDisplayResolver.QUALITY_ANY
                installedNow = false
            } else {
                installedNow = replaceVisual(existingToken, visual)
                if (primary) deferCurrentArtworkRewriteForSceneMotion = false
            }
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
        if (installedNow) publishFrame(0L)
        if (!primary && targetAutoSettle) {
            requestTargetAutoSettle()
        } else if (!primary && pendingCommit && !isGestureActive && !isSettling) {
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
                    1000f / targetSettleDurationMs.coerceAtLeast(1),
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
        } else if (target >= 1f && targetSettleCurve == ArtworkSettleCurve.Programmatic) {
            // C0858.B -> K -> n1 keeps aaPosAnimTime as the programmatic time base.
            // Do not scale 550 ms down with the remaining normalized ratio: Reference changes the
            // physical delta while the n1 owner still runs on its aaPosAnimTime clock.
            programmaticArtworkDurationMs(distance, targetSettleDurationMs)
        } else if (target >= 1f) {
            targetSettleDurationMs.coerceAtLeast(1)
        } else {
            ARTWORK_GESTURE_SETTLE_MS
        }

        transitionJob = scope.launch {
            // The target holder is already attached. Do not burn the first Choreographer callback
            // merely to capture an epoch: that creates one completely stationary vsync before the
            // first C0889 property update. Reference's persistent-holder path starts advancing on the
            // first frame callback after the command is accepted. Choreographer frameTimeNanos and
            // System.nanoTime() share the monotonic nano-time domain.
            val startNs = System.nanoTime()
            var previousFrameNs = startNs
            var previousValue = start
            while (true) {
                val now = withFrameNanos { it }
                val linear = ((now - startNs).toDouble() /
                    (durationMs * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
                val eased = when {
                    automaticCrossfadeArtworkFade -> {
                        // Auto Crossfade artwork owns its easing per lane below. Keep the shared ratio
                        // as a linear 0..1 clock so 1000ms out / 1200ms in are physically accurate.
                        linear
                    }
                    target >= 1f && targetSettleCurve == ArtworkSettleCurve.Programmatic ->
                        ReferenceProgrammaticArtworkEasing(linear, durationMs)
                    else -> {
                        // C0858 release uses q1: f = 1 - (1 - t)^3, including velocity-driven settle.
                        val remaining = 1f - linear
                        1f - remaining * remaining * remaining
                    }
                }
                val value = start + (target - start) * eased
                // Reference C0858/C0889 updates the already-attached View properties directly.
                // Do the same here: ratio is observed only from graphicsLayer/draw phase. The old
                // path crossed JNI twice per vsync (setRatio + advance) even though no foreground
                // consumer uses the native lane frame anymore.
                updateUiRatio(value)
                if (targetSettleCurve == ArtworkSettleCurve.Programmatic && !automaticCrossfadeArtworkFade) {
                    val deltaSeconds = (now - previousFrameNs).coerceAtLeast(1L) / 1_000_000_000f
                    programmaticVelocityPagesPerSecond =
                        (abs(value - previousValue) / deltaSeconds).coerceAtLeast(0f)
                    previousFrameNs = now
                    previousValue = value
                }
                if (linear >= 1f) break
            }
            setRatioFromUi(target)
            finishSettle(target)
        }
    }

    private fun finishSettle(end: Float) {
        isSettling = false
        programmaticVelocityPagesPerSecond = 0f
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
                        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
                targetSettleCurve = ArtworkSettleCurve.Gesture
                automaticFadeOnly = false
                automaticCrossfadeArtworkFade = false
                manualProgrammaticTransition = false
                pendingCommit = false
                pendingGestureCommit = null
                writeUiRatioState(0f)
                suppressedAutomaticOutgoingToken = 0
                publishFrame(0L)
                releaseUnusedVisuals()
                applyDeferredGestureTargetKeys()
                return
            }
            val committedKey = targetKey
            val promotedToken = targetToken
            val outgoingKey = currentKey
            val outgoingToken = currentToken
            val outgoingQuality = currentQuality
            generation += 1
            NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
            currentKey = targetKey
            currentToken = targetToken
            currentQuality = targetQuality
            clearGestureSlotByToken(promotedToken)
            rotateOutgoingIntoOppositeHolder(outgoingKey, outgoingToken, outgoingQuality)
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
                targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
            targetSettleCurve = ArtworkSettleCurve.Gesture
            automaticFadeOnly = false
            automaticCrossfadeArtworkFade = false
            manualProgrammaticTransition = false
            pendingCommit = false
            writeUiRatioState(0f)
            val gestureCommit = pendingGestureCommit
            pendingGestureCommit = null
            publishFrame(0L)
            releaseUnusedVisuals()
            queuedProgrammaticTargets.clear()
            if (gestureCommit != null) {
                committedGestureKey = committedKey
                committedGestureDirection = direction
                gestureCommit.invoke()
            }
            applyDeferredGestureTargetKeys()
        } else if (end <= 0f) {
            queuedProgrammaticTargets.clear()
            NativePlayerArtworkBridge.commit(nativeHandle, 0, 0)
            val rolledBackKey = targetKey
            val rolledBackToken = targetToken
            val rolledBackQuality = targetQuality
            clearLoad(targetKey)
            targetKey = ""
            targetToken = 0
            targetQuality = 0
            targetAutoSettle = false
                targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
            targetSettleCurve = ArtworkSettleCurve.Gesture
            // Return the exact physical neighbour to its parked role instead of destroying its
            // Image/RenderNode on every cancelled swipe.
            parkGestureSlot(direction, rolledBackKey, rolledBackToken, rolledBackQuality)
            automaticFadeOnly = false
            automaticCrossfadeArtworkFade = false
            manualProgrammaticTransition = false
            pendingCommit = false
            pendingGestureCommit = null
            writeUiRatioState(0f)
            suppressedAutomaticOutgoingToken = 0
            publishFrame(0L)
            releaseUnusedVisuals()
            applyDeferredGestureTargetKeys()
        }
    }

    private fun writeUiRatioState(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        presentationRatio = clamped
        ratio = clamped
    }

    private fun updateUiRatio(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (
            automaticCrossfadeArtworkFade &&
            currentToken != 0 &&
            clamped * AUTO_CROSSFADE_ARTWORK_FADE_IN_MS >= AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS
        ) {
            suppressedAutomaticOutgoingToken = currentToken
        }
        // C0889 writes View/RenderNode geometry directly. Make the visible artwork move first;
        // only after those physical holders have the new position do we publish the Compose-side
        // ratio used by title/background layers. Previously mutableFloatStateOf was written first,
        // scheduling Snapshot/layer work before the holder update and adding avoidable input latency.
        presentationRatio = clamped
        notifyPresentationObservers(topologyChanged = false)
        ratio = clamped
    }

    private fun setRatioFromUi(value: Float) {
        // Gesture/programmatic motion is a UI property lane, just like Reference's C0889 View
        // transforms. Keep it out of the JNI/native bookkeeping path on every pointer/vsync.
        updateUiRatio(value)
    }

    private fun pumpNativeEntryFade() {
        // The old native two-lane renderer is no longer a foreground/background draw source; its
        // per-entry fade pump only produced eight extra frame callbacks. Keep topology bookkeeping
        // synchronous and let the persistent Compose holders own presentation.
        nativePumpJob?.cancel()
        nativePumpJob = null
        publishFrame(0L)
    }

    private fun publishFrame(deltaNs: Long) {
        if (closed) return
        notifyPresentationObservers(topologyChanged = true)
        if (nativeHandle == 0L) {
            publishFallbackFrame()
            return
        }
        NativePlayerArtworkBridge.advance(nativeHandle, deltaNs, nativeFrame)
        // Native frame ratio used to overwrite the Kotlin UI ratio here. The player AA UI no
        // longer draws native lanes, so this would create a second clock/owner for the same motion.
        parity = nativeFrame[2] >= 0.5f
        val count = nativeFrame[3].roundToInt().coerceIn(0, ARTWORK_MAX_COMMANDS)
        nativeRetainedTokenCount = count
        repeat(count) { index ->
            val base = ARTWORK_FRAME_HEADER + index * ARTWORK_COMMAND_STRIDE
            nativeRetainedTokens[index] = nativeFrame[base].roundToInt()
        }
    }

    private fun publishFallbackFrame() {
        var count = 0
        if (currentToken != 0) nativeRetainedTokens[count++] = currentToken
        if (targetToken != 0 && count < nativeRetainedTokens.size) {
            nativeRetainedTokens[count++] = targetToken
        }
        nativeRetainedTokenCount = count
        parity = false
    }

    private fun clearLoad(key: String) {
        if (key.isBlank()) return
        loads.remove(key)?.job?.cancel()
    }

    /**
     * Replace pixels inside the existing physical holder immediately.
     *
     * Reference keeps C0889 page motion and AAImageView wrapper replacement as separate owners. Its
     * AAImageView can accept a newer wrapper while the outer AAItemView keeps moving, with any fade
     * local to the image view. RawSMusic previously deferred the replacement until settle finished,
     * which visibly turned track switching into placeholder motion followed by a late cover pop.
     * The View-backed holder now owns that image-local shader fade, so state promotion stays eager.
     */
    private fun replaceVisual(token: Int, visual: ArtworkVisual): Boolean {
        if (token == 0) {
            visual.release()
            return false
        }
        val previous = visuals.put(token, visual)
        if (previous?.bitmap !== visual.bitmap) previous?.release()
        return true
    }

    private fun releaseUnusedVisuals() {
        val retained = mutableSetOf<Int>()
        repeat(nativeRetainedTokenCount) { index ->
            nativeRetainedTokens[index].takeIf { it != 0 }?.let(retained::add)
        }
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
        presentationObservers.clear()
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        loads.values.forEach { it.job.cancel() }
        loads.clear()
        pendingCurrentArtworkRewriteVisual?.release()
        pendingCurrentArtworkRewriteVisual = null
        visuals.values.forEach { it.release() }
        visuals.clear()
        gesturePreviousSlot = null
        gestureNextSlot = null
        deferredGesturePreviousKey = null
        deferredGestureNextKey = null
        hasDeferredGestureKeys = false
        pendingManualNavigationKeys.clear()
        queuedProgrammaticTargets.clear()
        pendingUnknownManualBindings = 0
        unknownManualBindingDeadlineMs = 0L
        nativeRetainedTokenCount = 0
        NativePlayerArtworkBridge.destroy(nativeHandle)
    }
}

@Composable
fun rememberPlaybackArtworkTransitionState(
    currentKey: String?,
    queueCurrentIndex: Int,
    queueSize: Int,
    automaticCrossfadeEnabled: Boolean = false,
    requestedManualKey: String? = null,
): PlaybackArtworkTransitionState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val state = remember(context) { PlaybackArtworkTransitionState(scope, context) }
    val key = currentKey.orEmpty()

    LaunchedEffect(key, queueCurrentIndex, queueSize, automaticCrossfadeEnabled) {
        state.setAutomaticCrossfadeVisualEnabled(automaticCrossfadeEnabled)
        state.bindSong(key, queueCurrentIndex, queueSize)
    }
    LaunchedEffect(requestedManualKey) {
        state.markManualNavigationTarget(requestedManualKey)
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

            fun pageStepPx(): Float = ReferenceArtworkPageStepPx(widthPx)

            fun signedPosition(): Float = (dragX / pageStepPx()).coerceIn(-1f, 1f)

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
                            artworkWidthPx = pageStepPx(),
                        ) {
                            when (commitDirection) {
                                PlayerArtworkDirection.Previous -> latestOnPrevious()
                                PlayerArtworkDirection.Next -> latestOnNext()
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
internal fun plainArtworkAlphaTransform(alpha: Float) = ForegroundItemTransform(
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
    val absoluteVisualDistance = abs(visualDistance)
    val centreCrossDistance = CAROUSEL_SIDE_DISTANCE * 0.5f
    val sideAmount = smoothStep(
        (absoluteVisualDistance / centreCrossDistance.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
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
        // CAROUSEL_SIDE_DISTANCE is only a visual X/depth compression. Opacity must remain in the
        // logical holder-distance domain. Feeding the compressed 0.78 side distance into C0889's
        // alpha law leaves a parked neighbour at ~0.179 alpha; with both neighbours retained that
        // leaks previous + current + next simultaneously. A parked logical +/-1 holder is alpha 0,
        // while the actively moving current/target pair still follows the normal C0889 reveal.
        alpha = inwardCarouselHolderAlpha(rawDistance),
        cameraDistance = extent * CAROUSEL_CAMERA_DISTANCE_FACTOR,
        pivotFractionX = pivotX,
        pivotFractionY = 1f
    )
}

/** Exact C0889.w() outer-holder scale, shared by Compose math and the persistent View pager. */
internal fun ReferenceNormalHolderScale(signedDistance: Float): Float =
    1f - ((1f - PERSPECTIVE_MAX_SCALE_PARAMETER) * 0.5f) *
        abs(signedDistance.coerceIn(-1f, 1f))

/** Exact C0889.w() outer-holder X translation, using the resolved AA side and dense factor. */
internal fun ReferenceNormalHolderTranslationXPx(
    signedDistance: Float,
    itemExtentPx: Float,
): Float = signedDistance.coerceIn(-1f, 1f) * ReferenceArtworkPageStepPx(itemExtentPx)

/**
 * Inward-carousel opacity is deliberately keyed to logical page distance, not compressed visual X.
 * This keeps both parked neighbours fully hidden at +/-1 and limits a switch to the active pair.
 */
internal fun inwardCarouselHolderAlpha(logicalDistance: Float): Float =
    ReferenceNormalHolderAlpha(abs(logicalDistance.coerceIn(-1f, 1f)))

/**
 * Perspective transform for the standard player.
 *
 * The preference name is retained for compatibility. Artwork and text use the same signed slot
 * distance so a reversal remains one continuous perspective track rather than two flat pages.
 */
internal fun ReferenceArtworkRotationX(
    signedDistance: Float,
    apiLevel: Int = Build.VERSION.SDK_INT,
): Float = if (apiLevel >= 28) {
    abs(signedDistance) * PERSPECTIVE_MAX_ROTATION_X_DEGREES
} else {
    0f
}

internal fun ReferenceArtworkRotationY(
    signedDistance: Float,
    apiLevel: Int = Build.VERSION.SDK_INT,
): Float = if (apiLevel >= 28) {
    signedDistance * PERSPECTIVE_MAX_ROTATION_Y_DEGREES
} else {
    0f
}

internal fun ReferenceArtworkRotationZ(
    signedDistance: Float,
    apiLevel: Int = Build.VERSION.SDK_INT,
): Float = if (apiLevel < 28) {
    signedDistance * PERSPECTIVE_MAX_ROTATION_Z_DEGREES
} else {
    0f
}

private fun perspectiveDepthTransform(
    role: PlayerArtworkItemRole,
    direction: PlayerArtworkDirection,
    progress: Float,
    itemExtentPx: Float,
    @Suppress("UNUSED_PARAMETER") density: Float,
): ForegroundItemTransform {
    val p = progress.coerceIn(0f, 1f)
    val signedDistance = when (role) {
        PlayerArtworkItemRole.Current -> -direction.sign.toFloat() * p
        PlayerArtworkItemRole.Target -> direction.sign.toFloat() * (1f - p)
    }.coerceIn(-1f, 1f)
    val extent = itemExtentPx.coerceAtLeast(1f)
    val absoluteDistance = abs(signedDistance)
    // Keep translation and scale on the exact same C0889 geometry helpers used by the real View
    // pager. This prevents the fallback/title Compose lane from drifting from the top artwork's
    // physical holder during a switch or reversal.
    val scale = ReferenceNormalHolderScale(signedDistance)
    return ForegroundItemTransform(
        translationX = ReferenceNormalHolderTranslationXPx(signedDistance, extent),
        scaleX = scale,
        scaleY = scale,
        // C0889 gates the rotation channels through com.maxmpz.utils.vendor.\u0412. Its static
        // initializer resolves B=true only on API <=27 and x=true on API >=28. Therefore modern
        // Reference uses X/Y perspective but deliberately suppresses Z rotation; pre-28 does the
        // inverse. Applying all three channels on Android 16 was the remaining "twisted" look.
        rotationZ = ReferenceArtworkRotationZ(signedDistance),
        rotationX = ReferenceArtworkRotationX(signedDistance),
        rotationY = ReferenceArtworkRotationY(signedDistance),
        alpha = perspectiveSlotAlpha(absoluteDistance),
        // Reference does not call View.setCameraDistance() for C0889 AAItemView transforms. Both
        // Android View's default and Compose/RenderNode's default resolve to the same internal
        // camera distance (8). Supplying 1280*density directly to Compose was a unit error that
        // flattened the perspective and made the recovered rotations look visually wrong.
        cameraDistance = null,
        pivotFractionX = 0.5f,
        pivotFractionY = 0.5f
    )
}

private fun perspectiveSlotAlpha(distanceFromCenter: Float): Float =
    ReferenceNormalHolderAlpha(distanceFromCenter)

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

/** Exact normal-holder alpha from C0889.w(). z()'s 0.65..0.40 edge-holder envelope is not used here. */
internal fun ReferenceNormalHolderAlpha(distanceFromCenter: Float): Float {
    val visible = 1f - abs(distanceFromCenter)
    if (visible < Reference_NORMAL_ALPHA_VISIBLE_FLOOR) return 0f
    return ((visible - Reference_NORMAL_ALPHA_VISIBLE_FLOOR) *
        Reference_NORMAL_ALPHA_INV_RANGE).coerceIn(0f, 1f)
}

internal fun foregroundItemAlpha(distanceFromCenter: Float): Float =
    ReferenceNormalHolderAlpha(distanceFromCenter)

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

internal fun automaticCrossfadeArtworkOutAlpha(progress: Float): Float {
    val elapsedMs = progress.coerceIn(0f, 1f) * AUTO_CROSSFADE_ARTWORK_FADE_IN_MS
    val outProgress = (elapsedMs / AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS).coerceIn(0f, 1f)
    return 1f - smoothArtworkFadeProgress(outProgress)
}

internal fun automaticCrossfadeArtworkInAlpha(progress: Float): Float =
    smoothArtworkFadeProgress(progress)

@Composable
fun PlaybackArtworkTransition(
    state: PlaybackArtworkTransitionState,
    animationStyle: PlayerArtworkAnimationStyle = PlayerArtworkAnimationStyle.PerspectiveDepth,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Dp = 28.dp,
    contentInset: Dp = 0.dp,
    contentScaleFactor: Float = 1f,
    contentElevation: Dp = 0.dp,
) {
    if (animationStyle == PlayerArtworkAnimationStyle.PerspectiveDepth) {
        // C0889 is a View pager, not a Compose scene graph. Keep the exact three physical artwork
        // holders in Android View space so every ratio tick becomes property writes on existing
        // RenderNodes (translation/scale/rotation/alpha), matching Reference's hot path.
        val density = LocalDensity.current
        val insetPx = with(density) { contentInset.roundToPx() }
        val radiusPx = with(density) { cornerRadius.toPx() }
        val elevationPx = with(density) { contentElevation.toPx() }
        AndroidView(
            factory = { context ->
                ArtworkPagerView(context).apply {
                    bind(
                        newState = state,
                        insetPx = insetPx,
                        localScale = contentScaleFactor,
                        radiusPx = radiusPx,
                        elevationPx = elevationPx,
                    )
                }
            },
            update = { view ->
                view.bind(
                    newState = state,
                    insetPx = insetPx,
                    localScale = contentScaleFactor,
                    radiusPx = radiusPx,
                    elevationPx = elevationPx,
                )
            },
            modifier = modifier,
        )
        return
    }

    // Token/key ownership is snapshot state, so composition only changes when a physical holder is
    // added, removed or promoted. Ratio itself is read inside graphicsLayer below: a 60/90/120 Hz
    // swipe therefore updates RenderNode properties without rebuilding the Image/clip subtree.
    val currentToken = state.foregroundCurrentToken()
    val targetToken = state.foregroundTargetToken()

    BoxWithConstraints(modifier = modifier) {
        val itemExtentPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        state.updateForegroundItemExtentPx(itemExtentPx)
        val density = LocalDensity.current.density

        data class Holder(
            val token: Int,
            val bitmap: Bitmap,
            val role: PlayerArtworkItemRole,
            val parkedDirection: PlayerArtworkDirection? = null,
        )

        fun holderForToken(
            token: Int,
            role: PlayerArtworkItemRole,
            parkedDirection: PlayerArtworkDirection? = null,
        ): Holder? = token
            .takeIf { it != 0 }
            ?.let(state::visual)
            ?.takeUnless(Bitmap::isRecycled)
            ?.let { Holder(token, it, role, parkedDirection) }

        val currentHolder = holderForToken(currentToken, PlayerArtworkItemRole.Current)
        val targetHolder = holderForToken(targetToken, PlayerArtworkItemRole.Target)
        val previousParkedHolder = holderForToken(
            state.foregroundPreviousParkedToken(),
            PlayerArtworkItemRole.Target,
            PlayerArtworkDirection.Previous,
        )
        val nextParkedHolder = holderForToken(
            state.foregroundNextParkedToken(),
            PlayerArtworkItemRole.Target,
            PlayerArtworkDirection.Next,
        )

        if (currentHolder == null && targetHolder == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.Black.copy(alpha = 0.10f))
            )
        }

        // Stable physical holder order. key(token) lets the exact incoming Image/graphicsLayer group
        // survive Target -> Current promotion instead of moving the bitmap into the other Compose
        // call-site on the settle boundary. That call-site transfer was the small end-of-switch pop.
        val activeHolders = if (state.direction == PlayerArtworkDirection.Next) {
            listOfNotNull(currentHolder, targetHolder)
        } else {
            listOfNotNull(targetHolder, currentHolder)
        }
        // Previous/current/next now exist as actual Compose holders, not just cached bitmap tokens.
        // Parked neighbours stay alpha-zero at their C0889 side position until one is promoted.
        val holders = (listOfNotNull(previousParkedHolder, nextParkedHolder) + activeHolders)
            .distinctBy { it.token }
        holders.forEach { holder ->
            key(holder.token) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = state.ratio.coerceIn(0f, 1f)
                            val transform = when {
                                holder.parkedDirection != null -> playerArtworkForegroundTransform(
                                    style = animationStyle,
                                    role = PlayerArtworkItemRole.Target,
                                    direction = holder.parkedDirection,
                                    progress = 0f,
                                    itemExtentPx = itemExtentPx,
                                    density = density,
                                )

                                holder.role == PlayerArtworkItemRole.Current &&
                                    state.isAutomaticOutgoingSuppressed(holder.token) ->
                                    plainArtworkAlphaTransform(0f)

                                state.automaticFadeOnly -> {
                                    val alpha = when (holder.role) {
                                        PlayerArtworkItemRole.Current -> {
                                            if (state.automaticCrossfadeArtworkFade) {
                                                automaticCrossfadeArtworkOutAlpha(progress)
                                            } else {
                                                1f - progress
                                            }
                                        }
                                        PlayerArtworkItemRole.Target -> {
                                            if (state.automaticCrossfadeArtworkFade) {
                                                automaticCrossfadeArtworkInAlpha(progress)
                                            } else {
                                                progress
                                            }
                                        }
                                    }
                                    plainArtworkAlphaTransform(alpha)
                                }

                                else -> playerArtworkForegroundTransform(
                                    style = animationStyle,
                                    role = holder.role,
                                    direction = state.direction,
                                    progress = progress,
                                    itemExtentPx = itemExtentPx,
                                    density = density,
                                )
                            }

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
                    // Never remove a holder merely because its current alpha is zero. Reference's
                    // three v0/AAItemView holders stay attached; only their properties change.
                    Image(
                        bitmap = holder.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = contentScale,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentInset)
                            .graphicsLayer {
                                scaleX = contentScaleFactor
                                scaleY = contentScaleFactor
                            }
                            .clip(RoundedCornerShape(cornerRadius))
                    )
                }
            }
        }
    }
}


/**
 * Generic metadata lane that follows the exact same persistent AA holder topology as artwork.
 * Reference keeps title/line2/meta inside AAItemView; landscape RawSMusic previously rendered a
 * single global current-song Text which changed immediately while the artwork holder was moving.
 */
@Composable
fun PlaybackArtworkMetadataTransition(
    state: PlaybackArtworkTransitionState,
    animationStyle: PlayerArtworkAnimationStyle = PlayerArtworkAnimationStyle.PerspectiveDepth,
    modifier: Modifier = Modifier,
    content: @Composable (artworkKey: String, isInteractiveCurrent: Boolean) -> Unit,
) {
    val currentKey = state.foregroundCurrentKey()
    val targetKey = state.foregroundTargetKey()
    val currentToken = state.foregroundCurrentToken()
    val targetToken = state.foregroundTargetToken()
    val previousToken = state.foregroundPreviousParkedToken()
    val nextToken = state.foregroundNextParkedToken()
    val previousKey = state.foregroundPreviousParkedKey()
    val nextKey = state.foregroundNextParkedKey()

    data class MetadataHolder(
        val token: Int,
        val key: String,
        val role: PlayerArtworkItemRole,
        val parkedDirection: PlayerArtworkDirection? = null,
    )

    val current = MetadataHolder(currentToken, currentKey, PlayerArtworkItemRole.Current)
    val target = MetadataHolder(targetToken, targetKey, PlayerArtworkItemRole.Target)
    val previous = MetadataHolder(
        previousToken, previousKey, PlayerArtworkItemRole.Target, PlayerArtworkDirection.Previous
    )
    val next = MetadataHolder(
        nextToken, nextKey, PlayerArtworkItemRole.Target, PlayerArtworkDirection.Next
    )
    val active = if (state.direction == PlayerArtworkDirection.Next) {
        listOf(current, target)
    } else {
        listOf(target, current)
    }
    val holders = if (state.automaticFadeOnly) {
        listOf(current)
    } else {
        (listOf(previous, next) + active)
            .filter { it.key.isNotBlank() && (it.token != 0 || it === current) }
            .distinctBy { if (it.token != 0) "token:${it.token}" else "key:${it.key}" }
    }

    BoxWithConstraints(modifier = modifier) {
        val fallbackExtent = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val itemExtent = state.foregroundItemExtentPx().takeIf { it > 1f } ?: fallbackExtent
        val density = LocalDensity.current.density
        holders.forEach { holder ->
            val identity: Any = holder.token.takeIf { it != 0 } ?: holder.key
            key(identity) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val transform = if (holder.parkedDirection != null) {
                                playerArtworkForegroundTransform(
                                    style = animationStyle,
                                    role = PlayerArtworkItemRole.Target,
                                    direction = holder.parkedDirection,
                                    progress = 0f,
                                    itemExtentPx = itemExtent,
                                    density = density,
                                )
                            } else {
                                playerArtworkForegroundTransform(
                                    style = animationStyle,
                                    role = holder.role,
                                    direction = state.direction,
                                    progress = if (state.automaticFadeOnly) 0f else state.ratio,
                                    itemExtentPx = itemExtent,
                                    density = density,
                                )
                            }
                            translationX = transform.translationX
                            scaleX = transform.scaleX
                            scaleY = transform.scaleY
                            rotationZ = transform.rotationZ
                            rotationX = transform.rotationX
                            rotationY = transform.rotationY
                            alpha = transform.alpha
                            transform.cameraDistance?.let { cameraDistance = it }
                            transformOrigin = TransformOrigin(
                                transform.pivotFractionX, transform.pivotFractionY
                            )
                        }
                ) {
                    content(
                        holder.key,
                        holder.role == PlayerArtworkItemRole.Current &&
                            holder.parkedDirection == null &&
                            targetKey.isBlank(),
                    )
                }
            }
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
