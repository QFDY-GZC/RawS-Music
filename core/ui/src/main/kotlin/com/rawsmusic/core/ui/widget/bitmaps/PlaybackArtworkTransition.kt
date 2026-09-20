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
import androidx.compose.runtime.SideEffect
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
import com.rawsmusic.module.data.prefs.PlayerHiResBadgeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ARTWORK_LOCAL_UPDATE_MS = 50
// ArtworkMotionConfiguration passes x0.artworkAnimationTimeMs=550ms and artworkSettleAnimationMs=650ms into ArtworkPagerMotion.
private const val ARTWORK_PROGRAMMATIC_POSITION_MS = 550
private const val ARTWORK_GESTURE_SETTLE_MS = 650
private const val AUTO_CROSSFADE_ARTWORK_FADE_OUT_MS = 1_000
private const val AUTO_CROSSFADE_ARTWORK_FADE_IN_MS = 1_200
private const val ARTWORK_MAX_COMMANDS = 16
private const val ARTWORK_FRAME_HEADER = 4
private const val ARTWORK_COMMAND_STRIDE = 4
private const val MANUAL_UNKNOWN_BINDING_LEASE_MS = 2_000L
private const val MANUAL_EXACT_BINDING_LEASE_MS = 8_000L

// artwork pager transform normal-holder alpha. The narrower 0.65..0.40 envelope belongs to z(), which h()
// invokes only for synthetic edge/overscroll holders (-1000/-2000), not the ordinary prev/current/next
// ArtworkItemNodes. Normal cards use visible=(1-|distance|), then fade from visible=0.05 to 1.0.
private const val Reference_NORMAL_ALPHA_VISIBLE_FLOOR = 0.05f
private const val Reference_NORMAL_ALPHA_INV_RANGE = 1.0526316f // 1 / 0.95

// Perspective player values applied by the artwork item transform rather than a flat page translation.
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
// Reference ArtworkPagerMotion: p=0.2 and release projects signed page fraction by xVelocity * 1e-4.
private const val ARTWORK_SWIPE_COMMIT_RATIO = 0.20f
// artwork release policy: beyond 60% Reference commits transport immediately; between the projected 20% page
// threshold and 60% it finishes the retained-holder settle first and only then changes the track.
private const val ARTWORK_IMMEDIATE_TRANSPORT_COMMIT_RATIO = 0.60f
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
 * Exact port of the androidx.core.content.res interpolator used by Reference ArtworkPagerMotion/motionClock.
 *
 * motionClock does not use Android/Compose's generic PathInterpolator evaluation. It asks the cubic for Y
 * at a given X with a tolerance of 1 / (durationSeconds * 500). The implementation first runs up
 * to eight Newton iterations and then falls back to bisection. Keeping that tolerance matters at
 * artwork-card scale: a mathematically exact CubicBezierEasing follows a subtly different position
 * sequence than Reference's millisecond motionClock owner.
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

/** ArtworkPagerMotion-style page release: signed fractional position plus a small velocity projection. */
internal fun shouldCommitPlaybackArtworkSwipe(
    progress: Float,
    direction: PlayerArtworkDirection,
    velocityPxPerSecond: Float,
): Boolean {
    val signedPosition = -direction.sign.toFloat() * progress.coerceIn(0f, 1f)
    val projectedPosition = signedPosition + velocityPxPerSecond * ARTWORK_SWIPE_VELOCITY_PROJECTION
    // Keep the derived 0.20 boundary stable across Float rounding (0.12 + 0.08 may become
    // 0.19999999 on ART/JVM). This epsilon is far below a perceptible page-distance delta.
    return abs(projectedPosition) + 1.0e-6f >= ARTWORK_SWIPE_COMMIT_RATIO
}

/**
 * A follow-up flick is judged by the NEW pointer's own travel, not the old settle's live ratio.
 * Otherwise a page already at 0.9 would make even a tiny accidental second touch auto-commit.
 */
internal fun shouldCommitPlaybackArtworkFollowupFlick(
    gestureTravelProgress: Float,
    direction: PlayerArtworkDirection,
    velocityPxPerSecond: Float,
): Boolean = shouldCommitPlaybackArtworkSwipe(
    progress = gestureTravelProgress,
    direction = direction,
    velocityPxPerSecond = velocityPxPerSecond,
)

/** Whether Reference's artwork release policy publishes the new track before the visual settle completes. */
internal fun shouldCommitPlaybackTransportImmediately(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) > ARTWORK_IMMEDIATE_TRANSPORT_COMMIT_RATIO

/**
 * A visually prepared target becomes the logical centre for a follow-up gesture only after the
 * transport has actually moved to that target. In the ArtworkReleasePolicy 20%-60% release band the retained
 * holder is settling toward B while the transport is deliberately still A.
 */
internal fun playbackTargetActsAsCommittedGestureCenter(
    pendingCommit: Boolean,
    deferredTransportPending: Boolean,
    boundSongMatchesTarget: Boolean,
): Boolean = boundSongMatchesTarget || (pendingCommit && !deferredTransportPending)

/**
 * A settled target is stale only when no committed direct-manipulation handoff still owns it.
 *
 * Artwork gesture transport is intentionally decoupled from the pointer/settle UI turn. Therefore
 * [boundSongKey] may legitimately remain on A while the already-accepted A -> B holder finishes its
 * visual settle. Treating that lag as stale drops B and makes the later authoritative bind start a
 * second programmatic A -> B animation. Reference keeps ArtworkPagerMotion/ArtworkPager visual ownership independent
 * from the player transport callback, so a committed gesture target must survive that confirmation
 * gap.
 */
internal fun shouldDiscardSettledArtworkTargetAsStale(
    gestureCommitPending: Boolean,
    deferredGestureTransportPending: Boolean,
    manualProgrammaticPending: Boolean,
    boundSongKey: String,
    targetKey: String,
): Boolean =
    !gestureCommitPending &&
        !deferredGestureTransportPending &&
        !manualProgrammaticPending &&
        boundSongKey.isNotBlank() &&
        targetKey.isNotBlank() &&
        boundSongKey != targetKey

/**
 * Absolute page coordinate for a new same-direction drag that grabs an already-committed visual
 * settle. The previous page remains physically in flight until this coordinate reaches 1.0; any
 * excess then belongs to the following page. This is the ArtworkPager-style continuous-list coordinate Raw
 * needs for A -> B (settling) -> C without briefly publishing B as an idle/layout state.
 */
internal fun continuedArtworkAbsolutePosition(
    settledStartRatio: Float,
    signedDragPosition: Float,
    direction: PlayerArtworkDirection,
): Float = settledStartRatio.coerceIn(0f, 1f) -
    direction.sign.toFloat() * signedDragPosition

/** Local B -> C fraction after the committed A -> B boundary has been crossed. */
internal fun continuedArtworkLocalProgress(absolutePosition: Float): Float =
    (absolutePosition - 1f).coerceIn(0f, 1f)

/**
 * Reference's artwork pager binds the next player identity to the physical previous/next holder that is
 * already attached beside the centre item.  The queue/neighbour StateFlow snapshot may be blank for
 * one UI turn during a page promotion; that must not erase the holder's identity or force a rebind.
 */
internal fun physicalAdjacentPlaybackBindingDirection(
    previousSlotKey: String?,
    nextSlotKey: String?,
    boundKey: String,
    rewriteKind: PlaybackArtworkKeyContinuity.RewriteKind?,
): PlayerArtworkDirection? {
    if (rewriteKind != null || boundKey.isBlank()) return null
    return when (boundKey) {
        previousSlotKey?.takeIf { it.isNotBlank() } -> PlayerArtworkDirection.Previous
        nextSlotKey?.takeIf { it.isNotBlank() } -> PlayerArtworkDirection.Next
        else -> null
    }
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
 * Reference ArtworkPagerMotion -> motionClock keeps artworkAnimationTimeMs as the time base for a programmatic page move.
 * The physical distance changes, but an idle programmatic move still uses the full 550 ms owner;
 * motionClock derives per-frame deltas from that time base instead of shortening the tween in proportion
 * to the remaining normalized fraction.
 */
internal fun programmaticArtworkDurationMs(
    distance: Float,
    fullDistanceDurationMs: Int = ARTWORK_PROGRAMMATIC_POSITION_MS,
): Int = if (distance <= 0.0005f) 1 else fullDistanceDurationMs.coerceAtLeast(1)

/**
 * Reference ArtworkPagerMotion.K() keeps [artworkAnimationTimeMs] as the base clock even when the live fractional
 * position is retargeted across more than one physical page. Its effective scroller speed is
 * approximately `distance / artworkAnimationTimeMs + 0.2 * retainedVelocity`, so a farther target does not
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
 * ArtworkPager.page-step helper is the physical page step: resolved artwork side * artworkDenseFactor.
 * Direct manipulation is normalized by this step, not by the full artwork/container width.
 */
internal fun ReferenceArtworkPageStepPx(itemExtentPx: Float): Float =
    itemExtentPx.coerceAtLeast(1f) * PERSPECTIVE_DENSE_FACTOR

/** Resolve ArtworkPager's direct-manipulation page step from the actual physical artwork holder extent. */
internal fun resolveArtworkGesturePageStepPx(
    holderExtentPx: Float,
    fallbackGestureExtentPx: Float,
): Float {
    val extent = holderExtentPx.takeIf { it.isFinite() && it > 1f }
        ?: fallbackGestureExtentPx.coerceAtLeast(1f)
    return ReferenceArtworkPageStepPx(extent)
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
    val ownedBitmap: Boolean,
    val syntheticPlaceholder: Boolean = false,
    // A logical rebind may retain old pixels until the new provider result arrives.
    val rasterKey: String = key,
) {
    fun release() {
        // Compose/RenderThread can retain a display-list reference to this Bitmap for a frame after
        // the Kotlin holder is detached/replaced. Never recycle player-transition bitmaps here:
        // dropping the provider handle/strong reference is sufficient and avoids draw-after-recycle
        // crashes during rapid navigation.
        handle?.release()
    }
}

private class ArtworkLoad {
    var request: BitmapRequest? = null

    fun cancel() {
        request?.let { BitmapProvider.cancel(it) }
        request = null
    }
}

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
    fun onPresentationChanged(topologyChanged: Boolean, pixelsChangedToken: Int)
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
    // Live programmatic velocity in normalized artwork pages/second. ArtworkPagerMotion.K() carries a fraction of the
    // active velocity into a retarget instead of restarting the next button press from rest.
    private var programmaticVelocityPagesPerSecond = 0f
    internal var manualProgrammaticTransition by mutableStateOf(false)
        private set
    // PLAYER <-> LYRIC motion is a separate scene owner from ArtworkPager track paging. A Lyrico cover
    // rewrite may finish decoding while that shared-artwork scene is under the finger. Keep only
    // that same-current-holder replacement pending until the scene settles; normal track-target
    // ArtworkImageNode wrapper updates remain eager and independent from page motion.
    private var externalSceneArtworkMotionActive = false
    // Reference ArtworkImageNode owns the foreground provider request while its physical pager is attached.
    // There can be two physical pager views for one state during a shared player/scene handoff. Keep
    // this as a lease count instead of a Boolean: detaching the temporary view must not make the
    // still-visible view start a second state-side request or clear the accepted wrapper.
    private var artworkImageProviderOwnerCount = 0
    private val artworkImageProviderOwnerActive: Boolean
        get() = artworkImageProviderOwnerCount > 0
    // ArtworkPager only owns a position animator while the physical foreground pager is actually visible.
    // Raw keeps the player composition mounted behind other scenes; attachment alone therefore
    // cannot be used as visibility evidence.
    private var foregroundPresentationActive = true
    private var deferCurrentArtworkRewriteForSceneMotion = false
    private var pendingCurrentArtworkRewriteVisual: ArtworkVisual? = null
    private var closed = false
    private var directionHint: PlayerArtworkDirection? = null
    private var directionHintDeadlineMs = 0L
    private var expectedHintKey: String? = null
    // Manual commands are multi-flight: rapid Next/Previous taps can have several committed player
    // identities in transit at once. A single "latest key" marker is therefore incorrect because
    // the first binding consumes/overwrites it and a later manual binding can be misclassified as
    // natural Auto Crossfade. Keep exact pending identities independently until each one binds.
    private val pendingManualNavigationKeys = LinkedHashMap<String, Long>()
    // Reference ArtworkPagerMotion retargets from the live fractional list position. Keep later same-direction
    // programmatic requests as exact page destinations instead of force-committing the active page
    // before the next command can move. The frame owner can then cross holder boundaries without an
    // idle/zero-velocity seam.
    private data class ProgrammaticRetarget(
        val direction: PlayerArtworkDirection,
        val key: String,
        val gestureOwned: Boolean = false,
    )
    private val queuedProgrammaticTargets = ArrayDeque<ProgrammaticRetarget>()
    // A follow-up flick can retarget the same ArtworkPagerMotion-style scroller to C/D/E without waiting for each
    // intermediate 650 ms gesture settle. This is presentation-local state only; transport keeps its
    // own non-StateFlow projected cursor and authoritative queue publication remains asynchronous.
    private var gestureProgrammaticRetargetActive = false
    // Fallback only for manual sources that cannot resolve a song key before dispatch. Each action
    // owns one credit; when the exact key becomes known markManualNavigationTarget() transfers one
    // credit into the exact-key map instead of leaving a broad manual lease behind.
    private var pendingUnknownManualBindings = 0
    private var unknownManualBindingDeadlineMs = 0L
    private var lastQueueIndex = -1
    private var queueSize = 0
    // Shared physical artwork item extent. Artwork publishes this once on layout; title holders reuse it
    // so image and metadata move on the same ArtworkPager track instead of two different viewport widths.
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

    private fun notifyPresentationObservers(
        topologyChanged: Boolean,
        pixelsChangedToken: Int = 0,
    ) {
        if (topologyChanged) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.TOPOLOGY_NOTIFY)
        } else if (pixelsChangedToken != 0) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PIXEL_NOTIFY)
        }
        var index = 0
        while (index < presentationObservers.size) {
            presentationObservers[index].onPresentationChanged(topologyChanged, pixelsChangedToken)
            index += 1
        }
    }

    private fun notifyPresentationPixelsChanged(token: Int) {
        if (token != 0) notifyPresentationObservers(topologyChanged = false, pixelsChangedToken = token)
    }

    /**
     * ArtworkImageNode has already installed its wrapper before it calls back into this state. For the
     * View-backed Reference pager, assigning the state/background token must not re-run foreground
     * holder topology or transforms. Non-View animation styles still consume these notifications.
     */
    private fun publishStateArtworkTopologyIfNeeded() {
        if (referenceShouldRepublishForegroundForArtworkCallback(artworkImageProviderOwnerActive)) {
            publishFrame(0L)
        }
    }

    private fun publishStateArtworkPixelsIfNeeded(token: Int) {
        if (referenceShouldRepublishForegroundForArtworkCallback(artworkImageProviderOwnerActive)) {
            notifyPresentationPixelsChanged(token)
        }
    }

    private fun updateArtworkPerfState(reason: String) {
        PlaybackArtworkPerfTrace.setState(
            "reason=$reason dir=$direction ratio=${"%.3f".format(ratio)} gesture=$isGestureActive " +
                "settling=$isSettling pending=$pendingCommit manual=$manualProgrammaticTransition " +
                "cur=${PlaybackArtworkPerfTrace.keyTag(currentKey)}/$currentToken " +
                "target=${PlaybackArtworkPerfTrace.keyTag(targetKey)}/$targetToken " +
                "queueTargets=${queuedProgrammaticTargets.size} prev=${gesturePreviousSlot?.token ?: 0} " +
                "next=${gestureNextSlot?.token ?: 0}"
        )
    }

    private fun beginArtworkPerf(kind: String, detail: String) {
        PlaybackArtworkPerfTrace.begin(kind, detail)
        updateArtworkPerfState(kind)
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
    // reference player keeps the current ArtworkItemNode draggable even when there is no adjacent item.
    // The gesture then behaves as resisted edge/overscroll and q1 settles the same holder back to
    // centre; transport is queried only on release so the UI can report "List finished" without
    // inventing a target artwork identity.
    private var edgeGestureWithoutTarget = false
    private var pendingEdgeGestureReleaseDirection: PlayerArtworkDirection? = null
    // A new same-direction pointer may grab A -> B after B was already logically committed but
    // before the visual settle reached centre. Keep that old pair alive and treat the new drag as
    // one continuous page coordinate. B becomes the physical current holder only at the exact 1.0
    // boundary; no intermediate settled Compose/player-artwork layout is ever published.
    private var continuedGestureTargetKey = ""
    private var continuedGestureDirection: PlayerArtworkDirection? = null
    private var continuedGestureStartRatio = 0f
    private var continuedGestureCrossedBoundary = false
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
    val isMotionActive: Boolean
        get() = isGestureActive || isSettling
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
        beginArtworkPerf(
            kind = "manual_${direction.name.lowercase()}",
            detail = "key=${PlaybackArtworkPerfTrace.keyTag(key)} qIndex=$expectedQueueIndex ratio=${"%.3f".format(ratio)}",
        )
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.MANUAL_REQUEST)
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
            PlaybackArtworkPerfTrace.mark(
                "manual_queue",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} queued=${queuedProgrammaticTargets.size}",
            )
            restartProgrammaticChainFromCurrentFraction()
            return true
        }
        manualProgrammaticTransition = true
        if (!isSettling) programmaticVelocityPagesPerSecond = 0f
        if (key == targetKey) {
            targetAutoSettle = true
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
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = false
    }

    /**
     * Reverse an in-flight button/programmatic page back to its still-authoritative current holder.
     *
     * reference player ArtworkPagerMotion keeps pager fractional position fractional position live when the opposite command
     * wins before the first page reaches centre. Its q1 release owner then returns that same holder
     * population with `1 - (1 - t)^3`; there is no intermediate centre commit. Raw previously let
     * resolveInterruptedTargetForNewRequest() force-commit the prepared target, which preserved FPS
     * but introduced a visible velocity/position discontinuity.
     */
    fun reverseManualProgrammaticMotionToCurrent(expectedCurrentKey: String?): Boolean {
        val key = expectedCurrentKey?.takeIf { it.isNotBlank() }
        val canReverse =
            key != null &&
                key == currentKey &&
                targetKey.isNotBlank() &&
                isSettling &&
                manualProgrammaticTransition &&
                targetSettleCurve == ArtworkSettleCurve.Programmatic

        // Drop only the speculative transport hint after snapshotting the visual ownership above.
        // cancelManualNavigationExpectation() intentionally clears manualProgrammaticTransition,
        // so checking that flag after this call (the old implementation) made reversal unreachable.
        cancelManualNavigationExpectation()
        if (!canReverse) return false

        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
        pendingCommit = false
        pendingGestureCommit = null
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
        PlaybackArtworkPerfTrace.mark(
            "manual_reverse",
            "ratio=${"%.3f".format(ratio)} cur=${PlaybackArtworkPerfTrace.keyTag(currentKey)} target=${PlaybackArtworkPerfTrace.keyTag(targetKey)}",
        )
        settleTo(0f, 0f)
        return true
    }

    fun cancelManualProgrammaticMotion(expectedKey: String?) {
        val key = expectedKey?.takeIf { it.isNotBlank() }
        val canCancel =
            key != null &&
                key == targetKey &&
                manualProgrammaticTransition
        cancelManualNavigationExpectation()
        if (!canCancel) return
        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
        pendingCommit = false
        pendingGestureCommit = null
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
        settleTo(0f, 0f)
    }

    /**
     * The player can report intermediate queue positions after several rapid commands. Keep the
     * newest exact key/index binding and ignore older identities while that request is pending.
     */
    fun expectConfirmedNavigation(direction: PlayerArtworkDirection) {
        // A newer transport command must not remain blocked by an exact binding expected by the
        // preceding swipe. The next player emission is authoritative, but it is still manual.
        armUnknownManualBinding()
        directionHint = direction
        directionHintDeadlineMs = SystemClock.uptimeMillis() + 4_000L
        expectedHintKey = null
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
    }

    internal fun setGestureTargetKeys(previousKey: String?, nextKey: String?) {
        // Freeze neighbour identities only while a finger owns the signed drag coordinate. Once
        // the pointer releases, outgoing/current/target tokens are retained independently, so the
        // newly-confirmed previous/next keys may be parked while settle continues. Deferring them
        // until settle completion makes a rapid follow-up click/swipe miss the third hot holder.
        if (
            isGestureActive ||
            (manualProgrammaticTransition && (isSettling || targetKey.isNotBlank() || queuedProgrammaticTargets.isNotEmpty()))
        ) {
            // While ArtworkPagerMotion-style programmatic motion owns the attached artwork holders, repository/Compose
            // neighbour publication is advisory only.  Defer it until the physical page owner
            // settles instead of rebinding the third holder mid-flight.
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
        if (gesturePreviousKey == newPreviousKey && gestureNextKey == newNextKey) return

        // ArtworkImageNode is bound to its logical item before the provider callback arrives.  Mirror
        // that directly: a physical neighbour slot may have token=0 while its wrapper is loading.
        // A blank publication is treated as "no new identity" rather than "destroy the holder";
        // interaction availability still follows gesturePrevious/NextKey below, while bindSong() can
        // match the retained physical holder across the transient promotion gap.
        syncGestureSlotIdentity(PlayerArtworkDirection.Previous, newPreviousKey)
        syncGestureSlotIdentity(PlayerArtworkDirection.Next, newNextKey)
        gesturePreviousKey = newPreviousKey
        gestureNextKey = newNextKey
        releaseUnusedVisuals()
        notifyPresentationObservers(topologyChanged = true)
    }

    private fun syncGestureSlotIdentity(
        direction: PlayerArtworkDirection,
        publishedKey: String,
    ) {
        if (publishedKey.isBlank()) return
        if (publishedKey == currentKey || publishedKey == targetKey) return

        val existing = gestureSlot(direction)
        if (existing?.key != publishedKey) {
            existing?.token
                ?.takeIf { it != 0 && it != currentToken && it != targetToken }
                ?.let { token -> visuals.remove(token)?.release() }
            setGestureSlot(
                direction,
                GestureArtworkSlot(
                    key = publishedKey,
                    token = 0,
                    quality = 0,
                ),
            )
        }
        primeGestureSlot(direction, publishedKey)
    }

    private fun applyDeferredGestureTargetKeys() {
        if (!hasDeferredGestureKeys || isGestureActive) return
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
        // Keep navigation topology here; the three persistent artwork holders own their provider
        // requests when those identities are bound. Do not create a second state-side warmup owner.
        setGestureTargetKeys(previousKey, nextKey)
    }

    /** Prewarms both adjacent artwork holders so landscape and portrait share the same hot artwork lane. */
    fun prefetchNavigationNeighbours(previousKey: String?, nextKey: String?) {
        prefetchGestureTargets(previousKey, nextKey)
    }

    private fun acquirePlaybackPagerCachedHandle(key: String): ArtworkHandle? {
        val handle = BitmapProvider.acquirePreferredPlayback(
            key = key,
            surface = ArtworkSurface.Playback,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) ?: BitmapProvider.acquireAny(
            key = key,
            surface = ArtworkSurface.Playback,
            minimumSide = 1,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) ?: return null
        if (!handle.isValid) {
            handle.release()
            return null
        }
        val bitmap = handle.bitmap
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val preferredSide = BitmapProvider.preferredPlaybackTargetSide()
        if (maxSide > preferredSide) {
            // Keep the three moving holders on the active playback tier. A source/original viewer
            // wrapper larger than the selected 1024/1536 policy is normalized asynchronously.
            handle.release()
            PlaybackArtworkPerfTrace.mark(
                "pager_cache_oversize_deferred",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} bitmap=${bitmap.width}x${bitmap.height} preferred=$preferredSide",
            )
            return null
        }
        return handle
    }

    private fun primeGestureSlot(direction: PlayerArtworkDirection, rawKey: String?) {
        val key = rawKey?.takeIf { it.isNotBlank() && it != currentKey && it != targetKey } ?: return
        val existing = gestureSlot(direction)
        if (existing?.key == key) {
            // Identity is already attached. ArtworkImageNode owns the only foreground provider flight;
            // state-side pixels, if needed for static-artwork/background, arrive through its callback bridge.
            if (!artworkImageProviderOwnerActive) requestArtwork(key)
            return
        }
        existing?.token?.takeIf { it != 0 && it != currentToken && it != targetToken }?.let { token ->
            visuals.remove(token)?.release()
        }

        // Exact ArtworkImageNode holder bind callback ordering: publish/bind the physical identity before asking the
        // provider for pixels. token=0 is a valid bound-but-not-yet-returned wrapper state.
        setGestureSlot(direction, GestureArtworkSlot(key, 0, 0))
        notifyPresentationObservers(topologyChanged = true)
        if (artworkImageProviderOwnerActive) return

        val cachedHandle = acquirePlaybackPagerCachedHandle(key)
        if (cachedHandle?.isValid == true) {
            val token = nextToken()
            val visual = ArtworkVisual(
                key = key,
                bitmap = cachedHandle.bitmap,
                quality = ArtworkDisplayResolver.qualityForPlaybackBitmap(cachedHandle.bitmap),
                handle = cachedHandle,
                ownedBitmap = false,
            )
            visuals[token] = visual
            setGestureSlot(direction, GestureArtworkSlot(key, token, visual.quality))
            notifyPresentationObservers(topologyChanged = true)
        } else {
            cachedHandle?.release()
        }
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
    fun confirmManualNavigationBinding(
        key: String,
        queueIndex: Int,
        navigationDirection: PlayerArtworkDirection? = null,
    ) {
        if (key.isBlank()) return
        markManualNavigationTarget(key)
        if (!gestureProgrammaticRetargetActive || key == currentKey || key == targetKey) return
        if (queuedProgrammaticTargets.any { it.key == key }) return

        val resolvedDirection = navigationDirection ?: direction
        manualProgrammaticTransition = true
        targetAutoSettle = true
        targetSettleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS
        targetSettleCurve = ArtworkSettleCurve.Programmatic
        queuedProgrammaticTargets.addLast(
            ProgrammaticRetarget(
                direction = resolvedDirection,
                key = key,
                gestureOwned = true,
            )
        )
        PlaybackArtworkPerfTrace.mark(
            "gesture_chain_confirm",
            "key=${PlaybackArtworkPerfTrace.keyTag(key)} queued=${queuedProgrammaticTargets.size} qIndex=$queueIndex",
        )
        // Only the first future page has a free physical third holder. Later identities remain logical
        // until the preceding boundary rotates that holder, exactly like the existing button chain.
        if (queuedProgrammaticTargets.size == 1) {
            primeGestureSlot(resolvedDirection, key)
        }
        restartProgrammaticChainFromCurrentFraction()
    }

    /** True only while a manual button/queue programmatic page move owns the backdrop. */
    fun shouldFreezeBackdropMotionForManualProgrammaticTransition(): Boolean =
        manualProgrammaticTransition && targetKey.isNotBlank()

    /** One-shot marker consumed by ComposePlayerContainer when an edge gesture releases. */
    fun consumeEdgeGestureRelease(direction: PlayerArtworkDirection): Boolean {
        if (pendingEdgeGestureReleaseDirection != direction) return false
        pendingEdgeGestureReleaseDirection = null
        return true
    }

    private fun beginEdgeGesture(direction: PlayerArtworkDirection): Boolean {
        if (closed || currentKey.isBlank() || targetKey.isNotBlank()) return false
        beginArtworkPerf(
            kind = "gesture_edge_${direction.name.lowercase()}",
            detail = "cur=${PlaybackArtworkPerfTrace.keyTag(currentKey)} queueSize=$queueSize",
        )
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.GESTURE_BEGIN)
        transitionJob?.cancel()
        manualProgrammaticTransition = false
        isSettling = false
        isGestureActive = true
        edgeGestureWithoutTarget = true
        pendingEdgeGestureReleaseDirection = null
        this.direction = direction
        pendingCommit = false
        pendingGestureCommit = null
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
        gestureBaseRatio = 0f
        gestureOriginSignedPosition = 0f
        setRatioFromUi(0f)
        notifyPresentationObservers(topologyChanged = false)
        return true
    }

    /** Starts a user-controlled artwork drag. */
    fun beginGesture(direction: PlayerArtworkDirection, expectedKey: String?): Boolean {
        val key = expectedKey?.takeIf { it.isNotBlank() }
        if (key == null) return beginEdgeGesture(direction)
        if (key == currentKey && queueSize <= 1 && targetKey.isBlank()) {
            return beginEdgeGesture(direction)
        }
        edgeGestureWithoutTarget = false
        pendingEdgeGestureReleaseDirection = null
        val canReverseCommittedSettle =
            pendingCommit &&
                targetHolderReadyForMotion() &&
                key == currentKey &&
                this.direction != direction
        if (key == currentKey && !canReverseCommittedSettle) return false
        val canContinueCommittedSettle =
            (pendingCommit || targetActsAsCommittedGestureCenter()) &&
                targetHolderReadyForMotion() &&
                this.direction == direction &&
                key != targetKey
        if (artworkImageProviderOwnerActive && !canReverseCommittedSettle && !canContinueCommittedSettle) {
            // Bind the adjacent physical identity immediately. Reference's ArtworkPagerMotion/ArtworkPager motion does
            // not query ArtworkImageNode wrapper quality; ArtworkImageNode.image provider callback continues its provider request
            // independently while the already-attached holder follows the finger.
            primeGestureSlot(direction, key)
        }
        val resumesPreparedTarget = key == targetKey && targetKey.isNotBlank()
        val resumedSignedPosition = if (resumesPreparedTarget) {
            -this.direction.sign.toFloat() * ratio
        } else {
            0f
        }
        beginArtworkPerf(
            kind = "gesture_${direction.name.lowercase()}",
            detail = "key=${PlaybackArtworkPerfTrace.keyTag(key)} ratio=${"%.3f".format(ratio)} pending=$pendingCommit settling=$isSettling",
        )
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.GESTURE_BEGIN)
        transitionJob?.cancel()
        manualProgrammaticTransition = false
        isSettling = false
        isGestureActive = true
        // A pointer grab is authoritative over an in-flight natural dissolve. When it takes over the
        // same prepared target, beginTarget() would otherwise preserve the existing automatic flags
        // and targetAutoSettle, leaving the auto-only lane visible instead of a draggable ArtworkPager page.
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture

        if (canReverseCommittedSettle) {
            // B is already the player/transport identity but A -> B has not visually finished. A
            // Previous drag must be able to grab that same pair immediately. Swap only their logical
            // roles and invert the fraction: Next(A current, B target, r) is pixel-identical to
            // Previous(B current, A target, 1-r). The two retained Holder Views do not move/rebind.
            PlaybackArtworkPerfTrace.mark("gesture_reverse_grab", "ratio=${"%.3f".format(ratio)}")
            rebaseCommittedSettleForReverseGesture(direction)
            gestureBaseRatio = ratio
            gestureOriginSignedPosition = -direction.sign.toFloat() * ratio
            return true
        }

        if (canContinueCommittedSettle) {
            // The transport already committed B while A -> B was visually settling. Reference keeps
            // that physical pair and lets the next pointer continue through B into C; it does not
            // publish B as a transient idle layout first. Keep pendingCommit=true until the exact
            // holder boundary is crossed, and prewarm C in the parked third-slot lane.
            PlaybackArtworkPerfTrace.mark(
                "gesture_continue_settle",
                "next=${PlaybackArtworkPerfTrace.keyTag(key)} ratio=${"%.3f".format(ratio)}",
            )
            continuedGestureTargetKey = key
            continuedGestureDirection = direction
            continuedGestureStartRatio = ratio.coerceIn(0f, 1f)
            continuedGestureCrossedBoundary = false
            gestureBaseRatio = 0f
            gestureOriginSignedPosition = 0f
            // Once rapid flick retargeting has queued C/D/E, the single physical third holder belongs
            // to the first queued page. Do not overwrite C with a later logical D/E merely because a
            // new pointer was accepted before the scroller crossed the preceding boundary.
            if (queuedProgrammaticTargets.isEmpty()) {
                primeGestureSlot(direction, key)
                // The exact slot may already have been warm, in which case primeGestureSlot() is a
                // no-op. The continuation preference itself changed, so ask the retained pager once.
                notifyPresentationObservers(topologyChanged = true)
            }
            return true
        }

        clearContinuedGesture()
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
     * matching artwork pager coordinate. Crossing zero changes the adjacent slot while the current item is fully
     * centred, so a B -> C drag can reverse through B and continue naturally toward A.
     */
    fun updateContinuousGesture(
        signedDragPosition: Float,
        previousKey: String? = gesturePreviousKey,
        nextKey: String? = gestureNextKey,
    ) {
        if (!isGestureActive || closed) return

        if (edgeGestureWithoutTarget) {
            // Same edge resistance shape in both directions. `signedDragPosition` is in page units;
            // at one full-page finger travel the physical current holder moves only ~0.286 page,
            // then asymptotically resists further displacement instead of exposing a fake neighbour.
            val alongEdge = when (direction) {
                PlayerArtworkDirection.Previous -> signedDragPosition.coerceAtLeast(0f)
                PlayerArtworkDirection.Next -> (-signedDragPosition).coerceAtLeast(0f)
            }.coerceIn(0f, 1f)
            val resisted = alongEdge / (1f + 2.5f * alongEdge)
            setRatioFromUi(resisted.coerceIn(0f, 0.30f))
            return
        }

        val continuedDirection = continuedGestureDirection
        if (continuedDirection != null && continuedGestureTargetKey.isNotBlank()) {
            val absolutePosition = continuedArtworkAbsolutePosition(
                settledStartRatio = continuedGestureStartRatio,
                signedDragPosition = signedDragPosition,
                direction = continuedDirection,
            )
            if (!continuedGestureCrossedBoundary) {
                if (absolutePosition < 1f) {
                    // B was already the logical transport destination, so reversal here only takes
                    // temporary visual distance back toward A. Release/cancel below will still settle
                    // the committed A -> B pair to B rather than resurrecting A as the track owner.
                    setRatioFromUi(absolutePosition.coerceIn(0f, 1f))
                    return
                }
                if (!crossCommittedGestureBoundaryToContinuation(continuedGestureTargetKey)) {
                    // Never cross the holder boundary with a fabricated blank/placeholder. If C is
                    // not hot yet, pin B exactly at centre; provider prewarm continues and the next
                    // motion event may cross once the exact third-holder wrapper is ready.
                    setRatioFromUi(1f)
                    return
                }
                continuedGestureCrossedBoundary = true
            }
            setRatioFromUi(continuedArtworkLocalProgress(absolutePosition))
            return
        }

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

    /**
     * Crosses the already-committed A -> B boundary into a new direct B -> C gesture without ever
     * exposing a settled B layout/state between the two physical artwork holder positions.
     *
     * The boundary follows the already-bound third holder identity even when its provider wrapper is
     * still loading. Reference ArtworkPager advances list position independently from ArtworkImageNode.image provider callback; pinning
     * at B until C reaches HIGH quality creates the exact second-stage animation Reference avoids.
     */
    private fun crossCommittedGestureBoundaryToContinuation(key: String): Boolean {
        val continuationDirection = continuedGestureDirection ?: return false
        if (
            key.isBlank() ||
            continuationDirection != direction ||
            !targetHolderReadyForMotion()
        ) return false

        var prepared = takeGestureSlot(continuationDirection, key)
        if (prepared == null && artworkImageProviderOwnerActive) {
            // The physical third ArtworkImageNode may be bound before it owns pixels. That bound identity
            // is a valid Reference ArtworkPager holder; do not pin the gesture at B waiting for HIGH artwork.
            primeGestureSlot(continuationDirection, key)
            prepared = takeGestureSlot(continuationDirection, key)
        }
        prepared = prepared ?: createCachedArtworkSlot(key) ?: run {
            requestArtwork(key)
            return false
        }

        val outgoingKey = currentKey
        val outgoingToken = currentToken
        val outgoingQuality = currentQuality
        val promotedKey = targetKey
        val promotedToken = targetToken
        val promotedQuality = targetQuality

        generation += 1
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)

        currentKey = promotedKey
        currentToken = promotedToken
        currentQuality = promotedQuality
        clearGestureSlotForPromotedIdentity(promotedKey, promotedToken)
        rotateOutgoingIntoOppositeHolder(outgoingKey, outgoingToken, outgoingQuality)

        direction = continuationDirection
        targetKey = key
        targetToken = prepared.token
        targetQuality = prepared.quality
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = false
        pendingCommit = false
        suppressedAutomaticOutgoingToken = 0

        // Publish the new A/B/C role topology once at the physical centre boundary. Pixel tokens are
        // retained, so ArtworkPagerView only changes holder roles/drawing order; no bitmap decode or
        // shader replacement is admitted here.
        writeUiRatioState(0f)
        NativePlayerArtworkBridge.setArtwork(
            nativeHandle,
            targetToken,
            primary = false,
            requestGeneration = generation,
            durationMs = 0,
        )
        NativePlayerArtworkBridge.setRatio(nativeHandle, 0f)
        publishFrame(0L)
        requestArtwork(key)
        releaseUnusedVisuals()
        return true
    }

    private fun rebaseCommittedSettleForReverseGesture(newDirection: PlayerArtworkDirection) {
        if (!targetHolderReadyForMotion() || newDirection == direction) return
        val oldProgress = ratio.coerceIn(0f, 1f)
        val oldCurrentKey = currentKey
        val oldCurrentToken = currentToken
        val oldCurrentQuality = currentQuality
        val oldTargetKey = targetKey
        val oldTargetToken = targetToken
        val oldTargetQuality = targetQuality

        generation += 1
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)

        currentKey = oldTargetKey
        currentToken = oldTargetToken
        currentQuality = oldTargetQuality
        targetKey = oldCurrentKey
        targetToken = oldCurrentToken
        targetQuality = oldCurrentQuality
        clearGestureSlotForPromotedIdentity(currentKey, currentToken)
        clearGestureSlotForPromotedIdentity(targetKey, targetToken)
        direction = newDirection
        pendingCommit = false
        targetAutoSettle = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        manualProgrammaticTransition = false
        suppressedAutomaticOutgoingToken = 0

        val rebasedProgress = (1f - oldProgress).coerceIn(0f, 1f)
        writeUiRatioState(rebasedProgress)
        NativePlayerArtworkBridge.setArtwork(
            nativeHandle,
            targetToken,
            primary = false,
            requestGeneration = generation,
            durationMs = 0,
        )
        NativePlayerArtworkBridge.setRatio(nativeHandle, rebasedProgress)
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    private fun clearContinuedGesture() {
        continuedGestureTargetKey = ""
        continuedGestureDirection = null
        continuedGestureStartRatio = 0f
        continuedGestureCrossedBoundary = false
    }

    /** Returns whether the gesture committed to a song change. */
    fun endGesture(
        progress: Float,
        velocityPxPerSecond: Float,
        artworkWidthPx: Float,
        physicalEndpointReached: Boolean = false,
        gestureTravelProgress: Float = progress,
        onCommit: () -> Unit
    ): Boolean {
        if (!isGestureActive || closed) return false
        if (edgeGestureWithoutTarget) {
            val releaseDirection = direction
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.GESTURE_END)
            PlaybackArtworkPerfTrace.mark(
                "gesture_edge_end",
                "ratio=${"%.3f".format(ratio)} velocity=${velocityPxPerSecond.toInt()}",
            )
            isGestureActive = false
            edgeGestureWithoutTarget = false
            pendingCommit = false
            pendingGestureCommit = null
            pendingEdgeGestureReleaseDirection = releaseDirection
            targetAutoSettle = false
            targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
            targetSettleCurve = ArtworkSettleCurve.Gesture
            // q1 equivalent: settleTo(0) uses 1-(1-t)^3 and keeps the live fractional holder
            // position. Query transport now only to obtain the boundary/no-song result/feedback.
            settleTo(0f, 0f)
            onCommit()
            return false
        }
        if (continuedGestureDirection != null && !continuedGestureCrossedBoundary) {
            val continuationDirection = continuedGestureDirection ?: return false
            val continuationKey = continuedGestureTargetKey
            val safeWidth = artworkWidthPx.coerceAtLeast(1f)
            val travelProgress = gestureTravelProgress.coerceIn(0f, 1f)
            val rapidCommit = continuationKey.isNotBlank() && shouldCommitPlaybackArtworkFollowupFlick(
                gestureTravelProgress = travelProgress,
                direction = continuationDirection,
                velocityPxPerSecond = velocityPxPerSecond,
            )
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.GESTURE_END)
            PlaybackArtworkPerfTrace.mark(
                "gesture_followup_end",
                "commit=$rapidCommit travel=${"%.3f".format(travelProgress)} ratio=${"%.3f".format(ratio)} " +
                    "velocity=${velocityPxPerSecond.toInt()} queued=${queuedProgrammaticTargets.size}",
            )
            isGestureActive = false

            if (!rapidCommit) {
                // A short second touch still leaves already-committed B authoritative. Finish the old
                // settle without dispatching another track.
                pendingCommit = true
                pendingGestureCommit = null
                clearContinuedGesture()
                settleTo(1f, 0f)
                return false
            }

            // Reference ArtworkPagerMotion.K() retargets its live fractional scroller to a farther page and carries
            // velocity; it does not require the new flick to physically drag through the remaining
            // distance to the intermediate centre first. Convert this follow-up flick into one more
            // exact page destination on Raw's already-existing ArtworkPagerMotion-style programmatic chain.
            val deferredPreviousCommit = pendingGestureCommit
            val deferredPreviousKey = targetKey
            pendingCommit = true
            pendingGestureCommit = null
            gestureProgrammaticRetargetActive = true
            manualProgrammaticTransition = true
            targetAutoSettle = true
            targetSettleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS
            targetSettleCurve = ArtworkSettleCurve.Programmatic
            programmaticVelocityPagesPerSecond = maxOf(
                programmaticVelocityPagesPerSecond,
                abs(velocityPxPerSecond / safeWidth),
            )
            var queuedNewTarget = false
            if (
                continuationKey != currentKey &&
                continuationKey != targetKey &&
                queuedProgrammaticTargets.none { it.key == continuationKey }
            ) {
                queuedProgrammaticTargets.addLast(
                    ProgrammaticRetarget(continuationDirection, continuationKey, gestureOwned = true)
                )
                queuedNewTarget = true
                if (queuedProgrammaticTargets.size == 1) {
                    primeGestureSlot(continuationDirection, continuationKey)
                }
            }
            clearContinuedGesture()
            // The second flick introduces C and must start the farther-page scroller immediately. On
            // third/fourth flicks the immediate physical C may already be queued; the transport's
            // actual projected D/E identity is fed back by confirmManualNavigationBinding(), which
            // performs the single required restart. Avoid cancelling/restarting twice in one UI turn.
            if (queuedNewTarget) restartProgrammaticChainFromCurrentFraction()

            // If the first A -> B release was in ArtworkReleasePolicy's deferred 20%-60% band, its transport callback
            // is still parked even though B is the accepted visual navigation centre. Submit B first,
            // then this new C command. The gesture transport coordinator has a private projected cursor,
            // so these back-to-back calls resolve A->B->C without any public queue/Compose publication.
            if (deferredPreviousCommit != null && deferredPreviousKey.isNotBlank()) {
                committedGestureKey = deferredPreviousKey
                committedGestureDirection = continuationDirection
                deferredPreviousCommit.invoke()
            }
            committedGestureKey = continuationKey
            committedGestureDirection = continuationDirection
            onCommit()
            return true
        }
        if (continuedGestureCrossedBoundary) {
            // We are now a normal B -> C drag. The exact boundary helper already promoted B without
            // an idle frame, so release policy below can decide C exactly like any ordinary gesture.
            clearContinuedGesture()
        }
        val safeWidth = artworkWidthPx.coerceAtLeast(1f)
        val velocityFractionPerSecond = velocityPxPerSecond / safeWidth
        val towardTargetSpeed = -velocityFractionPerSecond * direction.sign
        val effectiveProgress = ratio.coerceIn(0f, 1f)
        val commit = shouldCommitPlaybackArtworkSwipe(
            progress = effectiveProgress,
            direction = direction,
            velocityPxPerSecond = velocityPxPerSecond,
        )
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.GESTURE_END)
        PlaybackArtworkPerfTrace.mark(
            "gesture_end",
            "commit=$commit ratio=${"%.3f".format(effectiveProgress)} velocity=${velocityPxPerSecond.toInt()} width=${artworkWidthPx.toInt()}",
        )
        isGestureActive = false
        pendingCommit = commit
        pendingGestureCommit = null
        if (commit) {
            val commitTransportImmediately =
                physicalEndpointReached || shouldCommitPlaybackTransportImmediately(effectiveProgress)
            if (!commitTransportImmediately) {
                // ArtworkReleasePolicy stores the original page index, runs the retained artwork holders to centre, and
                // invokes Y0 only from the touch handler's completion callback.  Deferring the app
                // transport here prevents queue binding, lyrics, background and artwork decode from
                // competing with the 20%-60% release animation on Main/RenderThread.
                pendingGestureCommit = onCommit
            } else {
                // Publish the one-shot committed target only when the transport callback is about
                // to run. A deferred 20%-60% settle is not yet a new logical song centre.
                committedGestureKey = targetKey
                committedGestureDirection = direction
            }
            if (physicalEndpointReached) {
                // ViewDragHelper does not start another Scroller when the child is already at the
                // destination coordinate. Hold the exact endpoint until transport confirms, then
                // bindSong() rotates the three physical holders with no second animation.
                transitionJob?.cancel()
                isSettling = false
                setRatioFromUi(1f)
                publishFrame(0L)
                PlaybackArtworkPerfTrace.mark("gesture_endpoint_hold", "key=${PlaybackArtworkPerfTrace.keyTag(targetKey)}")
            } else {
                settleTo(1f, towardTargetSpeed)
            }
            if (commitTransportImmediately) onCommit()
        } else {
            settleTo(0f, towardTargetSpeed)
        }
        return commit
    }

    fun cancelGesture() {
        if (!isGestureActive) return
        if (edgeGestureWithoutTarget) {
            edgeGestureWithoutTarget = false
            pendingEdgeGestureReleaseDirection = null
            isGestureActive = false
            pendingCommit = false
            pendingGestureCommit = null
            targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
            targetSettleCurve = ArtworkSettleCurve.Gesture
            settleTo(0f, 0f)
            return
        }
        if (continuedGestureDirection != null && !continuedGestureCrossedBoundary) {
            isGestureActive = false
            pendingCommit = true
            pendingGestureCommit = null
            clearContinuedGesture()
            settleTo(1f, 0f)
            return
        }
        clearContinuedGesture()
        isGestureActive = false
        pendingCommit = false
        pendingGestureCommit = null
        settleTo(0f, 0f)
    }

    internal fun bindSong(key: String, queueIndex: Int, newQueueSize: Int) {
        queueSize = newQueueSize.coerceAtLeast(0)
        if (closed || key.isBlank()) return
        val artworkPerfWasActiveAtBind = PlaybackArtworkPerfTrace.isActive()
        if (artworkPerfWasActiveAtBind) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.BIND_SONG)
            PlaybackArtworkPerfTrace.mark(
                "bind_song",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} index=$queueIndex size=$queueSize cur=${PlaybackArtworkPerfTrace.keyTag(currentKey)} target=${PlaybackArtworkPerfTrace.keyTag(targetKey)}",
            )
        }

        val oldIndex = lastQueueIndex
        lastQueueIndex = queueIndex
        boundSongKey = key
        // While the retained artwork pager is attached, its physical holder is the provider owner.
        // Keep the state-side warm path only as the off-screen/no-holder fallback.
        if (!artworkImageProviderOwnerActive) BitmapProvider.warmPlaybackArt(key)
        val manualNavigationBinding = consumeManualNavigationBinding(key)

        if (!foregroundPresentationActive) {
            syncBoundSongWithoutForegroundMotion(key)
            return
        }

        if (queuedProgrammaticTargets.any { it.key == key }) {
            PlaybackArtworkPerfTrace.mark(
                "bind_queued_future",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} queued=${queuedProgrammaticTargets.size}",
            )
            // The newest player identity may arrive before the visual scroller crosses the previous
            // physical page. ArtworkPagerMotion does not snap that page to centre first; the existing fractional
            // scroller owns the crossing and will install this exact key at the boundary.
            return
        }

        if (currentToken == 0) {
            currentKey = key
            if (artworkImageProviderOwnerActive) {
                notifyPresentationObservers(topologyChanged = true)
            } else {
                requestArtwork(key)
                if (currentToken == 0) installPlaceholder(key, primary = true)
            }
            return
        }
        if (key == currentKey) return
        if (key == targetKey) {
            PlaybackArtworkPerfTrace.mark("bind_matches_target", "token=$targetToken ratio=${"%.3f".format(ratio)}")
            targetAutoSettle = true
            if (pendingCommit && ratio >= 0.9995f) {
                // Direct manipulation already placed the physical artwork holder at its final position.
                // Transport confirmation only rotates roles; never launch a second settle.
                finishSettle(1f)
            } else {
                requestTargetAutoSettle()
            }
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val hinted = directionHint?.takeIf {
            nowMs <= directionHintDeadlineMs &&
                (expectedHintKey == null || expectedHintKey == key)
        }
        val rewriteKind = PlaybackArtworkKeyContinuity.rewriteKind(
            previousKey = currentKey,
            committedKey = key,
        )
        val physicalAdjacentDirection = physicalAdjacentPlaybackBindingDirection(
            previousSlotKey = gesturePreviousSlot?.key,
            nextSlotKey = gestureNextSlot?.key,
            boundKey = key,
            rewriteKind = rewriteKind,
        )
        val resolvedDirection = hinted
            ?: physicalAdjacentDirection
            ?: inferDirection(oldIndex, queueIndex, queueSize)
        directionHint = null
        directionHintDeadlineMs = 0L
        expectedHintKey = null

        // Natural end-of-track changes arrive without prepare(). Create the exact adjacent slot and
        // run the same settle animation; only a same-position identity refresh (cover edit, metadata
        // refresh or cold restore) is rebound immediately.
        val queueIndexAdvanced = oldIndex >= 0 && queueIndex >= 0 && oldIndex != queueIndex && queueSize > 1
        // currentSong and queue.currentIndex are published independently. Manual identity/direction
        // is itself enough proof of navigation when the index sample is one frame stale; otherwise
        // the new key is misclassified as a same-position metadata refresh and snaps with no ArtworkPager
        // motion. Natural advances are resolved by callers from the exact currentSong identity in
        // the same queue snapshot.
        val queueAdvanced = queueIndexAdvanced ||
            (queueSize > 1 && (
                manualNavigationBinding ||
                    hinted != null ||
                    physicalAdjacentDirection != null
            ))
        if (!queueIndexAdvanced && physicalAdjacentDirection != null) {
            PlaybackArtworkPerfTrace.mark(
                "bind_physical_adjacent_owner",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} dir=$physicalAdjacentDirection index=$queueIndex",
            )
        }
        val isNaturalQueueAdvance = isNaturalPlaybackArtworkAdvance(
            queueAdvanced = queueAdvanced,
            manualNavigationBinding = manualNavigationBinding,
            hasManualDirectionHint = hinted != null || physicalAdjacentDirection != null,
        )
        val isManualProgrammaticAdvance = queueAdvanced && !isNaturalQueueAdvance
        if (queueAdvanced) {
            if (!artworkPerfWasActiveAtBind) {
                beginArtworkPerf(
                    kind = if (isNaturalQueueAdvance) "natural_${resolvedDirection.name.lowercase()}" else "program_bind_${resolvedDirection.name.lowercase()}",
                    detail = "key=${PlaybackArtworkPerfTrace.keyTag(key)} oldIndex=$oldIndex newIndex=$queueIndex",
                )
                PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.BIND_SONG)
            }
            PlaybackArtworkPerfTrace.mark(
                "queue_advance",
                "manual=$isManualProgrammaticAdvance natural=$isNaturalQueueAdvance dir=$resolvedDirection $oldIndex->$queueIndex",
            )
        }
        if (
            isManualProgrammaticAdvance &&
            manualProgrammaticTransition &&
            isSettling &&
            targetAutoSettle &&
            targetSettleCurve == ArtworkSettleCurve.Programmatic &&
            targetKey.isNotBlank() &&
            resolvedDirection == direction &&
            key != targetKey
        ) {
            // Rapid button presses may publish C while A -> B is still physically moving. Do not
            // route C through beginTarget(), whose generic interruption path force-commits B and
            // restarts from ratio 0. Extend the same ArtworkPagerMotion-style absolute scroller instead.
            if (queuedProgrammaticTargets.none { it.key == key }) {
                queuedProgrammaticTargets.addLast(ProgrammaticRetarget(resolvedDirection, key))
            }
            PlaybackArtworkPerfTrace.mark(
                "bind_extend_chain",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} queued=${queuedProgrammaticTargets.size} ratio=${"%.3f".format(ratio)}",
            )
            // The first future page is the one physical third ArtworkImageNode. Later queue entries stay
            // logical until a boundary frees that holder; Reference never overwrites C with D while
            // the A->B page is still using C as its retained neighbour.
            if (queuedProgrammaticTargets.size == 1) {
                primeGestureSlot(resolvedDirection, key)
            }
            restartProgrammaticChainFromCurrentFraction()
            return
        }
        manualProgrammaticTransition = isManualProgrammaticAdvance
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
     * the same fake colors under different song identities. Reference's static-artwork artwork state carries the
     * real artwork id, never a generic stand-in identity; keep the same ownership rule here.
     */
    internal fun backgroundArtwork(token: Int): Bitmap? = visuals[token]
        ?.takeUnless { it.syntheticPlaceholder || it.bitmap.isRecycled }
        ?.bitmap

    fun backgroundLayers(): List<PlaybackArtworkBackgroundLayer> = buildList {
        val current = visuals[currentToken]?.takeUnless { it.bitmap.isRecycled }
        val target = visuals[targetToken]?.takeUnless { it.bitmap.isRecycled }

        // Keep both prepared backgrounds composed for the full transition. Reference keeps its artwork
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
        // Background ownership follows the prepared artwork *identity*, not the moment a bitmap
        // wrapper happens to arrive. The physical ArtworkPager can start moving as soon as the
        // adjacent holder/key is bound, while the background previously stayed current-only until
        // targetToken became non-zero. That made the cover animate first and the backdrop join the
        // transition late (or appear to snap at commit). Publish the target role immediately with
        // token=0 when necessary; RawFlow/STATIC can resolve a cached/prefetched endpoint by key and
        // later upgrade to the exact holder bitmap without changing the transition clock.
        val targetLayerKey = target?.key ?: targetKey.takeIf { it.isNotBlank() && it != currentKey }
        targetLayerKey?.let { key ->
            add(
                PlaybackArtworkBackgroundLayer(
                    token = targetToken,
                    key = key,
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
    /**
     * While a pointer directly owns A <-> B, keep the opposite neighbour physical so that same
     * pointer can reverse through A. After B has been committed and is merely settling, the more
     * valuable third holder is C: it makes a rapid same-direction click/swipe cross the next boundary
     * without a bitmap/shader bind on that boundary frame.
     */
    internal fun preferForwardParkedHolderDuringTarget(): Boolean =
        targetKey.isNotBlank() &&
            (continuedGestureDirection != null || (!isGestureActive && (pendingCommit || targetAutoSettle)))
    internal fun foregroundItemExtentPx(): Float = foregroundItemExtentPx
    internal fun updateForegroundItemExtentPx(value: Float) {
        if (value.isFinite() && value > 1f) foregroundItemExtentPx = value
    }
    internal fun acquireArtworkImageProviderOwner() {
        if (closed) return
        artworkImageProviderOwnerCount += 1
    }

    internal fun releaseArtworkImageProviderOwner() {
        artworkImageProviderOwnerCount = (artworkImageProviderOwnerCount - 1).coerceAtLeast(0)
    }

    internal fun setForegroundPresentationActive(active: Boolean) {
        if (foregroundPresentationActive == active) return
        foregroundPresentationActive = active
        if (!active && (
                isGestureActive ||
                    isSettling ||
                    targetKey.isNotBlank() ||
                    ratio > 0.0005f ||
                    presentationRatio > 0.0005f
                )
        ) {
            // Reference has no off-screen ArtworkPager animator. Once the physical player page leaves the
            // visible hierarchy, collapse any visual-only motion onto the authoritative player key.
            // Include a non-zero ratio even when targetKey is already empty: a transport/provider
            // acknowledgement may have promoted the target just before the final animator callback.
            // Such a ratio is not a valid stable state and must never survive a PLAYER -> LYRIC ->
            // PLAYER round trip.
            boundSongKey.takeIf { it.isNotBlank() }?.let(::syncBoundSongWithoutForegroundMotion)
        }
    }

    /**
     * ArtworkImageNode provider callback bridge. The physical holder already owns the provider flight;
     * acquire a second ref-counted wrapper only for background/native consumers. No decode/request
     * is started here.
     */
    internal fun acceptArtworkImage(key: String, bitmap: Bitmap, quality: Int) {
        if (closed || key.isBlank() || bitmap.isRecycled) return
        // ArtworkPagerView already owns/published this provider wrapper. Re-publishing a 384/512px
        // placeholder through acquireLoaded(..., 1024/1536) assigns low pixels to a high-tier exact
        // cache key and can make the real high-resolution request look satisfied. Acquire the best
        // already-resident playback wrapper instead; this adds only a ref and never changes tier
        // identity. If the holder callback raced a high/full arrival, transition consumers upgrade
        // immediately to that sharper wrapper.
        val handle = BitmapProvider.acquirePreferredPlayback(
            key = key,
            surface = ArtworkSurface.Playback,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) ?: BitmapProvider.acquireLoaded(
            // The holder callback is already the provider's successful delivery. If cache
            // eviction races that callback, do not drop the only valid pixels and leave the
            // retained current/next holder blank. Store the delivered bitmap in its actual bucket;
            // a later preferred-tier request can still promote it without treating a small image
            // as a high-resolution result.
            key = key,
            bitmap = bitmap,
            targetWidth = bitmap.width,
            targetHeight = bitmap.height,
            surface = ArtworkSurface.Playback,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) ?: return
        val residentQuality = ArtworkDisplayResolver.qualityForPlaybackBitmap(handle.bitmap)
        acceptHandle(key, handle, maxOf(quality, residentQuality))
    }

    /**
     * Physical ArtworkImageNode terminal fallback notification. The default/skin bitmap is not a
     * provider result, so never route those pixels through acquireLoaded(realSongKey). Build the
     * transition-owned fallback through the existing terminal no-art lane instead.
     */
    internal fun acceptArtworkImageTerminalNoArt(key: String) {
        if (closed || key.isBlank()) return
        acceptTerminalNoArtwork(key, AlbumArtTiers.HI_RES_SIDE)
    }

    private fun isArtworkImageOwnedKey(key: String): Boolean =
        artworkImageProviderOwnerActive && key.isNotBlank() && (
            key == currentKey ||
                key == targetKey ||
                key == gesturePreviousSlot?.key ||
                key == gestureNextSlot?.key
            )
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

    internal fun foregroundArtworkBitmapForKey(key: String): Bitmap? =
        sequenceOf(currentToken, targetToken)
            .mapNotNull { visuals[it] }
            .firstOrNull { it.rasterKey == key && !it.syntheticPlaceholder && !it.bitmap.isRecycled }
            ?.bitmap

    fun foregroundCurrentKey(): String = currentKey
    internal fun foregroundTargetKey(): String = targetKey
    internal fun hasPendingNavigation(): Boolean =
        targetKey.isNotBlank() || isSettling || isGestureActive || pendingCommit

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
        notifyPresentationPixelsChanged(currentToken)
    }

    private fun clearPendingCurrentArtworkRewriteVisual() {
        pendingCurrentArtworkRewriteVisual?.release()
        pendingCurrentArtworkRewriteVisual = null
        deferCurrentArtworkRewriteForSceneMotion = false
    }

    private fun targetActsAsCommittedGestureCenter(): Boolean =
        targetKey.isNotBlank() && playbackTargetActsAsCommittedGestureCenter(
            pendingCommit = pendingCommit,
            deferredTransportPending = pendingGestureCommit != null,
            boundSongMatchesTarget = boundSongKey == targetKey,
        )

    /**
     * Visual/navigation centre for a new pointer while ArtworkPager holders are still settling. An accepted
     * 20%-60% gesture may deliberately defer transport, but Reference still lets the next flick retarget
     * the list from that accepted B toward C. This is presentation-local only: no queue/currentSong
     * StateFlow is published until transport accepts the command.
     */
    internal fun gestureLogicalCenterKey(): String =
        targetKey.takeIf { pendingCommit || targetActsAsCommittedGestureCenter() } ?: currentKey

    fun pendingGestureTarget(requestedDirection: PlayerArtworkDirection): String? {
        if (committedGestureDirection != requestedDirection || committedGestureKey.isBlank()) return null
        val key = committedGestureKey
        committedGestureKey = ""
        committedGestureDirection = null
        return key
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
        // Bind the logical identity to its retained physical holder before the provider request.
        // This notification is required when a cold target intentionally keeps token=0.
        notifyPresentationObservers(topologyChanged = true)
        requestArtwork(key)
        if (targetToken == 0 && !autoSettle && !artworkImageProviderOwnerActive) {
            // Non-artwork fallback lanes still need a drawable neighbour. PerspectiveDepth binds the
            // physical ArtworkImageNode identity first and lets that holder own its provider wrapper.
            installPlaceholder(key, primary = false)
        }
        requestTargetAutoSettle()
    }

    /**
     * Starts ArtworkPagerMotion-style movement from the bound physical holder identity.
     *
     * Reference keeps three ArtworkItemNodes attached and ArtworkItemNode.holder bind/reuse callbacks immediately calls image provider callback on each
     * bound holder. ArtworkPagerMotion never waits for LOW/HIGH wrapper quality before moving ArtworkPager. Keep the same
     * separation here: topology/identity admits motion; ArtworkImageNode provider callbacks upgrade pixels
     * locally without starting or restarting the outer page clock.
     */
    private fun requestTargetAutoSettle() {
        if (closed || !targetAutoSettle || isGestureActive || !targetHolderReadyForMotion()) return
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

    /**
     * Reference ArtworkPagerMotion/ArtworkPager moves a bound ArtworkItemNode regardless of ArtworkImageNode wrapper quality. The
     * physical three-View pager is therefore motion-ready as soon as the target identity is bound;
     * provider pixels may arrive/upgrade independently. Fallback renderers still require a token.
     */
    private fun targetHolderReadyForMotion(): Boolean = referenceBoundArtworkHolderReadyForMotion(
        providerViewOwnerActive = artworkImageProviderOwnerActive,
        targetKeyBound = targetKey.isNotBlank(),
        targetToken = targetToken,
    )

    private fun restartProgrammaticChainFromCurrentFraction() {
        if (
            closed ||
            !targetHolderReadyForMotion() ||
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
        // ArtworkPagerMotion.K() retargets from the live fractional position but keeps artworkAnimationTimeMs as the base
        // clock; retained velocity shortens that clock rather than making multi-page requests take
        // one additional 550 ms block per page.
        val durationMs = programmaticRetargetDurationMs(
            startFraction = startFraction,
            destinationPages = destinationPages,
            retainedVelocityPagesPerSecond = retainedVelocity,
        )
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PROGRAMMATIC_CHAIN_RESTART)
        PlaybackArtworkPerfTrace.mark(
            "chain_restart",
            "start=${"%.3f".format(startFraction)} pages=${"%.2f".format(destinationPages)} queued=$queuedCountAtStart velocity=${"%.2f".format(retainedVelocity)} duration=${durationMs}ms",
        )
        updateArtworkPerfState("chain_restart")

        transitionJob = scope.launch {
            // The target holder is already attached. Do not burn the first Choreographer callback
            // merely to capture an epoch: that creates one completely stationary vsync before the
            // first ArtworkPager property update. Reference's persistent-holder path starts advancing on the
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
                    if (!targetHolderReadyForMotion()) {
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
        if (targetKey.isBlank() || !targetHolderReadyForMotion()) return
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.PROGRAMMATIC_BOUNDARY)
        PlaybackArtworkPerfTrace.mark(
            "chain_boundary",
            "from=${PlaybackArtworkPerfTrace.keyTag(currentKey)} to=${PlaybackArtworkPerfTrace.keyTag(targetKey)} token=$targetToken",
        )
        val outgoingKey = currentKey
        val outgoingToken = currentToken
        val outgoingQuality = currentQuality
        generation += 1
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        currentKey = targetKey
        currentToken = targetToken
        currentQuality = targetQuality
        clearGestureSlotForPromotedIdentity(currentKey, currentToken)
        rotateOutgoingIntoOppositeHolder(outgoingKey, outgoingToken, outgoingQuality)
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        writeUiRatioState(0f)
        suppressedAutomaticOutgoingToken = 0
        // The next target is installed immediately in the same UI turn. Let that installation
        // publish the final current+target topology once; publishing here as well rebuilds the
        // retained ArtworkPager holder topology twice at every rapid-click boundary.
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
        pendingCommit = next.gestureOwned
        if (next.gestureOwned) gestureProgrammaticRetargetActive = true

        var parkedTarget = takeGestureSlot(next.direction, next.key)
        if (parkedTarget != null && visuals[parkedTarget.token]?.syntheticPlaceholder == true) {
            visuals.remove(parkedTarget.token)?.release()
            parkedTarget = null
        }
        targetToken = parkedTarget?.also { targetQuality = it.quality }?.token ?: 0
        if (targetToken != 0) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.TARGET_INSTALL_HOT)
            PlaybackArtworkPerfTrace.mark(
                "target_hot",
                "key=${PlaybackArtworkPerfTrace.keyTag(next.key)} token=$targetToken q=$targetQuality",
            )
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
            if (targetToken == 0) {
                PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.TARGET_INSTALL_COLD)
                PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.COLD_TARGET_WAIT)
                PlaybackArtworkPerfTrace.mark(
                    "target_cold",
                    "key=${PlaybackArtworkPerfTrace.keyTag(next.key)} queued=${queuedProgrammaticTargets.size}",
                )
                // Boundary commit intentionally did not publish. A true cold third-holder miss
                // still needs exactly one topology update for the newly-current artwork.
                publishFrame(0L)
            }
        }
        if (targetToken == 0 && artworkImageProviderOwnerActive) {
            // Keep the target identity physical even before the wrapper callback. ArtworkImageNode.image provider callback
            // is now the only provider owner for this holder.
            notifyPresentationObservers(topologyChanged = true)
        } else {
            requestArtwork(next.key)
        }
        queuedProgrammaticTargets.firstOrNull()?.let { future ->
            primeGestureSlot(future.direction, future.key)
        }
        releaseUnusedVisuals()
    }

    private fun createCachedArtworkSlot(key: String): GestureArtworkSlot? {
        val cachedHandle = acquirePlaybackPagerCachedHandle(key) ?: return null
        if (!cachedHandle.isValid) {
            cachedHandle.release()
            return null
        }
        val token = nextToken()
        val quality = ArtworkDisplayResolver.qualityForPlaybackBitmap(cachedHandle.bitmap)
        visuals[token] = ArtworkVisual(
            key = key,
            bitmap = cachedHandle.bitmap,
            quality = quality,
            handle = cachedHandle,
            ownedBitmap = false
        )
        return GestureArtworkSlot(key = key, token = token, quality = quality)
    }

    private fun installCachedTarget(key: String) {
        val cached = createCachedArtworkSlot(key) ?: return
        targetToken = cached.token
        targetQuality = cached.quality
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
        clearContinuedGesture()
        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
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
        if (manualProgrammaticTransition && targetSettleCurve == ArtworkSettleCurve.Programmatic) {
        }
        NativePlayerArtworkBridge.setRatio(nativeHandle, 1f)
        generation += 1
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        currentKey = targetKey
        currentToken = targetToken
        currentQuality = targetQuality
        clearGestureSlotForPromotedIdentity(currentKey, currentToken)
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

    private fun takeGestureSlot(
        direction: PlayerArtworkDirection,
        key: String,
    ): GestureArtworkSlot? {
        val slot = gestureSlot(direction)?.takeIf { it.key == key } ?: return null
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

    private fun clearGestureSlotForPromotedIdentity(key: String, token: Int) {
        if (token != 0) clearGestureSlotByToken(token)
        if (key.isNotBlank()) {
            if (gesturePreviousSlot?.key == key) gesturePreviousSlot = null
            if (gestureNextSlot?.key == key) gestureNextSlot = null
        }
    }

    /**
     * ArtworkPager keeps physical previous/current/next holders and rotates their roles after a commit.
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
        clearContinuedGesture()
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
        // The editor proved the artwork bytes unchanged; this same raster is valid for the
        // new metadata version too, including a subsequent shared-cover return gesture.
        visuals[currentToken] = currentVisual.copy(rasterKey = key)
        // Token, bitmap, provider handle, generation and native lane stay unchanged. Only the
        // logical song binding advances to the post-metadata file version.
        notifyPresentationObservers(topologyChanged = false)
        return true
    }

    /**
     * Preserve the current artwork holder while intentionally reloading changed artwork bytes.
     *
     * Lyrico/embedded-cover writers replace the file, so the versioned playback key legitimately
     * changes. Rebuilding the entire current holder at that point caused a blank/texture burst that
     * could overlap the user's immediate PLAYER <-> LYRIC gesture. Keep the existing token and old
     * pixels visible, rebind the logical key, and request the new wrapper into the same holder. The
     * View-backed ArtworkImageNode analogue performs the eventual 200 ms image-local shader fade.
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

    private fun syncBoundSongWithoutForegroundMotion(key: String) {
        if (closed || key.isBlank()) return
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        isSettling = false
        isGestureActive = false
        clearContinuedGesture()
        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
        pendingCommit = false
        pendingGestureCommit = null
        manualProgrammaticTransition = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        suppressedAutomaticOutgoingToken = 0

        val candidate = sequenceOf(gesturePreviousSlot, gestureNextSlot)
            .filterNotNull()
            .firstOrNull { it.key == key }
        val oldCurrentToken = currentToken
        val oldTargetToken = targetToken
        clearLoad(currentKey)
        clearLoad(targetKey)
        currentKey = key
        val exactToken = candidate?.token
            ?: visuals.entries.firstOrNull { it.value.key == key }?.key
        val retainedToken = exactToken ?: oldCurrentToken.takeIf { token ->
            token != 0 && visuals[token]?.bitmap?.let { bitmap -> !bitmap.isRecycled } == true
        }
        currentToken = retainedToken ?: 0
        if (exactToken != null) {
            currentQuality = visuals[exactToken]?.quality ?: 0
        } else if (retainedToken != null) {
            // Keep the last pixels on the physical current holder while the new identity is loaded.
            // The pixels are only a visual continuity frame; reset the admission quality so the
            // first valid wrapper for the new song cannot be rejected as "lower quality" than the
            // old song's bitmap.
            visuals[retainedToken]?.let { visual ->
                visuals[retainedToken] = visual.copy(key = key)
            }
            currentQuality = 0
        } else {
            currentQuality = 0
        }
        targetKey = ""
        targetToken = 0
        targetQuality = 0
        targetAutoSettle = false
        targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
        targetSettleCurve = ArtworkSettleCurve.Gesture
        writeUiRatioState(0f)
        generation += 1
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        if (!artworkImageProviderOwnerActive) requestArtwork(key)
        publishFrame(0L)
        if (oldCurrentToken != 0 && oldCurrentToken != currentToken) visuals.remove(oldCurrentToken)?.release()
        if (oldTargetToken != 0 && oldTargetToken != currentToken) visuals.remove(oldTargetToken)?.release()
        releaseUnusedVisuals()
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.mark(
                "bind_offscreen_direct",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} token=$currentToken",
            )
            PlaybackArtworkPerfTrace.discard()
        }
    }

    private fun resetToBoundSong(key: String) {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.mark("reset_bound_song", "key=${PlaybackArtworkPerfTrace.keyTag(key)}")
            updateArtworkPerfState("reset_bound_song")
            PlaybackArtworkPerfTrace.discard()
        }
        val oldCurrentToken = currentToken
        val oldCurrentKey = currentKey
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        clearPendingCurrentArtworkRewriteVisual()
        isSettling = false
        isGestureActive = false
        clearContinuedGesture()
        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
        pendingCommit = false
        automaticFadeOnly = false
        automaticCrossfadeArtworkFade = false
        discardTarget()

        clearLoad(oldCurrentKey)
        generation += 1
        currentKey = key
        val retainedCurrent = visuals[oldCurrentToken]?.takeUnless {
            it.bitmap.isRecycled || it.syntheticPlaceholder
        }
        if (retainedCurrent != null) {
            // reference player does not blank the current ArtworkImageNode when a new track is committed.
            // Retain the old pixels under the new logical key and replace them in-place when the
            // provider callback arrives. This is especially important during cold-start restore,
            // where the player page can become visible before the preferred wrapper is delivered.
            currentToken = oldCurrentToken
            currentQuality = 0
            visuals[oldCurrentToken] = retainedCurrent.copy(key = key)
        } else {
            currentToken = 0
            currentQuality = 0
        }
        NativePlayerArtworkBridge.commit(nativeHandle, generation, 0)
        requestArtwork(key)
        if (currentToken == 0 && !artworkImageProviderOwnerActive) installPlaceholder(key, primary = true)
        if (oldCurrentToken != 0 && oldCurrentToken != currentToken) {
            visuals.remove(oldCurrentToken)?.release()
        }
        publishFrame(0L)
        releaseUnusedVisuals()
    }

    private fun residentPlaybackQualityForKey(key: String): Int {
        if (key.isBlank()) return 0
        var quality = 0
        if (currentKey == key && currentToken != 0 && visuals.containsKey(currentToken)) {
            quality = maxOf(quality, currentQuality)
        }
        if (targetKey == key && targetToken != 0 && visuals.containsKey(targetToken)) {
            quality = maxOf(quality, targetQuality)
        }
        gesturePreviousSlot?.takeIf { it.key == key && visuals.containsKey(it.token) }?.let {
            quality = maxOf(quality, it.quality)
        }
        gestureNextSlot?.takeIf { it.key == key && visuals.containsKey(it.token) }?.let {
            quality = maxOf(quality, it.quality)
        }
        return quality
    }

    private fun residentPlaybackCoverageForKey(key: String): Int {
        if (key.isBlank()) return 0
        var side = 0
        fun include(token: Int) {
            if (token == 0) return
            val visual = visuals[token]?.takeIf { it.rasterKey == key && !it.syntheticPlaceholder } ?: return
            val bitmap = visual.bitmap
            if (!bitmap.isRecycled) side = maxOf(side, bitmap.width, bitmap.height)
        }
        if (currentKey == key) include(currentToken)
        if (targetKey == key) include(targetToken)
        gesturePreviousSlot?.takeIf { it.key == key }?.let { include(it.token) }
        gestureNextSlot?.takeIf { it.key == key }?.let { include(it.token) }
        return side
    }

    private fun requestArtwork(key: String) {
        if (key.isBlank() || loads.containsKey(key) || closed) return
        if (artworkImageProviderOwnerActive) {
            // Exactly one provider owner while the three ArtworkImageNodes are attached. The state may
            // retain a second ref-counted wrapper after the holder callback, but never starts a
            // parallel decode/request for navigation or parked/future identities.
            if (PlaybackArtworkPerfTrace.isActive()) {
                PlaybackArtworkPerfTrace.mark(
                    "art_request_holder_owned",
                    "key=${PlaybackArtworkPerfTrace.keyTag(key)}",
                )
            }
            return
        }
        val preferredSide = BitmapProvider.preferredPlaybackTargetSide()
        val residentQuality = residentPlaybackQualityForKey(key)
        val residentCoverage = residentPlaybackCoverageForKey(key)
        // Cache residency does not mean this state owns the image. Consume the wrapper before
        // skipping a provider flight, including when the detail page warmed it before entry.
        val cached = BitmapProvider.acquirePreferredPlayback(key)
        if (cached != null) {
            acceptHandle(key, cached, ArtworkDisplayResolver.qualityForPlaybackBitmap(cached.bitmap))
            return
        }
        if (residentCoverage >= preferredSide) {
            if (PlaybackArtworkPerfTrace.isActive()) {
                PlaybackArtworkPerfTrace.mark(
                    "art_request_skip_resident",
                    "key=${PlaybackArtworkPerfTrace.keyTag(key)} q=$residentQuality side=$residentCoverage preferred=$preferredSide",
                )
            }
            return
        }
        PlaybackArtworkPerfTrace.artworkRequest(key)

        // Reference baseline implementation ArtworkImageNode.image provider callback issues one provider request for a bound physical
        // holder. The provider owns cache/source selection and returns the wrapper; ArtworkImageNode then
        // swaps the shader in-place. Raw's explicit 512 -> 1024 two-request ladder created two
        // request/callback/lease lifetimes for every parked neighbour while ArtworkPager was moving.
        // Keep the already-resident fast path in installCachedTarget()/primeGestureSlot(), then use
        // exactly one provider request for a missing/upgrading holder.
        val load = ArtworkLoad()
        loads[key] = load
        var providerRequest: BitmapRequest? = null
        providerRequest = BitmapProvider.load(
            key = key,
            targetWidth = preferredSide,
            targetHeight = preferredSide,
            priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
            surface = ArtworkSurface.Playback,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) { bitmap ->
            if (loads[key] === load) loads.remove(key)
            if (bitmap != null && !bitmap.isRecycled) {
                val quality = ArtworkDisplayResolver.qualityForPlaybackBitmap(bitmap)
                PlaybackArtworkPerfTrace.artworkArrived(
                    key = key,
                    quality = quality,
                    lane = "provider_single",
                )
                val handle = BitmapProvider.acquireLoaded(
                    key = key,
                    bitmap = bitmap,
                    targetWidth = preferredSide,
                    targetHeight = preferredSide,
                    surface = ArtworkSurface.Playback,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                )
                if (handle != null) acceptHandle(key, handle, quality)
            } else if (providerRequest?.terminalNoArt == true) {
                PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.ARTWORK_TERMINAL)
                PlaybackArtworkPerfTrace.mark(
                    "art_terminal",
                    "key=${PlaybackArtworkPerfTrace.keyTag(key)} lane=provider_single",
                )
                acceptTerminalNoArtwork(key)
            }
        }
        load.request = providerRequest
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
        if (PlaybackArtworkPerfTrace.isActive()) {
            val role = when {
                key == currentKey -> "current"
                key == targetKey -> "target"
                key == gesturePreviousSlot?.key -> "previous"
                key == gestureNextSlot?.key -> "next"
                queuedProgrammaticTargets.any { it.key == key } -> "queued"
                else -> "other"
            }
            PlaybackArtworkPerfTrace.mark(
                "accept_handle",
                "key=${PlaybackArtworkPerfTrace.keyTag(key)} q=$quality role=$role bitmap=${handle.bitmap.width}x${handle.bitmap.height}",
            )
        }

        when (key) {
            currentKey -> {
                val pendingQuality = pendingCurrentArtworkRewriteVisual?.quality ?: 0
                if (currentToken != 0 && visuals[currentToken]?.rasterKey == key &&
                    quality <= maxOf(currentQuality, pendingQuality)) {
                    handle.release()
                    return
                }
                if (currentToken == 0) {
                    clearPendingCurrentArtworkRewriteVisual()
                    currentToken = nextToken()
                    currentQuality = quality
                    visuals[currentToken] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                    NativePlayerArtworkBridge.setArtwork(nativeHandle, currentToken, true, generation, 0)
                    publishStateArtworkTopologyIfNeeded()
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
                    if (appliedNow) publishStateArtworkPixelsIfNeeded(currentToken)
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
                    if (!artworkImageProviderOwnerActive) pumpNativeEntryFade()
                } else {
                    val appliedNow = replaceVisual(
                        targetToken,
                        ArtworkVisual(key, handle.bitmap, quality, handle, false),
                    )
                    targetQuality = quality
                    if (appliedNow) publishStateArtworkPixelsIfNeeded(targetToken)
                }
                if (targetAutoSettle) requestTargetAutoSettle()
                else if (pendingCommit && !isGestureActive && !isSettling) settleTo(1f, 0f)
            }
            boundSongKey -> {
                val queuedDirection = queuedProgrammaticTargets.firstOrNull { it.key == key }?.direction
                if (queuedDirection != null) {
                    // The player may confirm C before the absolute A -> B -> C scroller reaches B.
                    // C is therefore boundSongKey but it is still a *future physical holder*, not a
                    // reason to interrupt/force-commit B. Park the wrapper and let the scroller cross.
                    acceptParkedArtworkHandle(key, handle, quality, queuedDirection)
                    return
                }
                // External track change reached us before prepare/bind could establish a target.
                direction = directionHint ?: PlayerArtworkDirection.Next
                beginTarget(direction, key, autoSettle = true, settleDurationMs = ARTWORK_PROGRAMMATIC_POSITION_MS, settleCurve = ArtworkSettleCurve.Programmatic)
                acceptHandle(key, handle, quality)
            }
            else -> {
                val parked = parkedGestureSlotForKey(key)
                if (parked != null) {
                    acceptParkedArtworkHandle(key, handle, quality, parked.first)
                } else {
                    val prefetchDirection = when {
                        key == continuedGestureTargetKey -> continuedGestureDirection
                        else -> queuedProgrammaticTargets.firstOrNull { it.key == key }?.direction
                            ?: when (key) {
                                gesturePreviousSlot?.key -> PlayerArtworkDirection.Previous
                                gestureNextSlot?.key -> PlayerArtworkDirection.Next
                                else -> null
                            }
                    }
                    if (prefetchDirection == null) {
                        handle.release()
                    } else {
                        acceptParkedArtworkHandle(key, handle, quality, prefetchDirection)
                    }
                }
            }
        }
    }

    private fun acceptParkedArtworkHandle(
        key: String,
        handle: ArtworkHandle,
        quality: Int,
        parkedDirection: PlayerArtworkDirection,
    ) {
        val parked = parkedGestureSlotForKey(key)
        if (parked != null) {
            if (parked.second.token != 0 && quality <= parked.second.quality) {
                handle.release()
                return
            }
            if (parked.second.token == 0) {
                val token = nextToken()
                visuals[token] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
                setGestureSlot(parked.first, GestureArtworkSlot(key, token, quality))
                publishStateArtworkTopologyIfNeeded()
            } else {
                val appliedNow = replaceVisual(
                    parked.second.token,
                    ArtworkVisual(key, handle.bitmap, quality, handle, false),
                )
                setGestureSlot(parked.first, parked.second.copy(quality = quality))
                if (appliedNow) publishStateArtworkPixelsIfNeeded(parked.second.token)
            }
            return
        }
        val token = nextToken()
        visuals[token] = ArtworkVisual(key, handle.bitmap, quality, handle, false)
        setGestureSlot(parkedDirection, GestureArtworkSlot(key, token, quality))
        publishStateArtworkTopologyIfNeeded()
        releaseUnusedVisuals()
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
            val prefetchDirection = when {
                key == continuedGestureTargetKey -> continuedGestureDirection
                else -> queuedProgrammaticTargets.firstOrNull { it.key == key }?.direction
                    ?: when (key) {
                        gesturePreviousSlot?.key -> PlayerArtworkDirection.Previous
                        gestureNextSlot?.key -> PlayerArtworkDirection.Next
                        else -> null
                    }
            } ?: return
            val parked = parkedGestureSlotForKey(key)
            if (parked == null || parked.second.token == 0) {
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
                    notifyPresentationPixelsChanged(currentToken)
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
        val topologyChanged = existingToken == 0
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
        if (installedNow) {
            if (topologyChanged) publishFrame(0L)
            else notifyPresentationPixelsChanged(existingToken)
        }
        if (!primary && targetAutoSettle) {
            requestTargetAutoSettle()
        } else if (!primary && pendingCommit && !isGestureActive && !isSettling) {
            settleTo(1f, 0f)
        }
    }

    private fun settleTo(end: Float, towardTargetSpeed: Float) {
        transitionJob?.cancel()
        if (closed) return
        if (!PlaybackArtworkPerfTrace.isActive()) {
            beginArtworkPerf(
                kind = "settle",
                detail = "target=${PlaybackArtworkPerfTrace.keyTag(targetKey)} end=${"%.2f".format(end)}",
            )
        }
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
            // ArtworkPagerMotion.B -> K -> motionClock keeps artworkAnimationTimeMs as the programmatic time base.
            // Do not scale 550 ms down with the remaining normalized ratio: Reference changes the
            // physical delta while the motionClock owner still runs on its artworkAnimationTimeMs clock.
            programmaticArtworkDurationMs(distance, targetSettleDurationMs)
        } else if (target >= 1f) {
            targetSettleDurationMs.coerceAtLeast(1)
        } else {
            ARTWORK_GESTURE_SETTLE_MS
        }
        PlaybackArtworkPerfTrace.mark(
            "settle_start",
            "start=${"%.3f".format(start)} end=${"%.3f".format(target)} duration=${durationMs}ms curve=$targetSettleCurve velocity=${"%.2f".format(towardTargetSpeed)}",
        )
        updateArtworkPerfState("settle_start")

        transitionJob = scope.launch {
            // The target holder is already attached. Do not burn the first Choreographer callback
            // merely to capture an epoch: that creates one completely stationary vsync before the
            // first ArtworkPager property update. Reference's persistent-holder path starts advancing on the
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
                        // ArtworkPagerMotion release uses scaleGestureOwner: f = 1 - (1 - t)^3, including velocity-driven settle.
                        val remaining = 1f - linear
                        1f - remaining * remaining * remaining
                    }
                }
                val value = start + (target - start) * eased
                // Reference ArtworkPagerMotion/ArtworkPager updates the already-attached View properties directly.
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
        // A provider/transport acknowledgement can complete an endpoint on the same main turn
        // as the Choreographer settle callback. The first path owns the target promotion; a late
        // callback must not run another native commit or rotate the same holder a second time.
        //
        // The duplicate callback still owns one important presentation responsibility: collapse
        // the UI motion lane back to the neutral page. Leaving ratio==1 after target promotion
        // makes ArtworkPagerView treat the already-committed current holder as a parked page. The
        // provider/background still own valid pixels, but the physical holder is translated/faded
        // out, which presents as an empty/half-transparent artwork frame until another scene forces
        // a state reset.
        if (end >= 1f && (targetKey.isBlank() || targetKey == currentKey)) {
            isSettling = false
            clearContinuedGesture()
            programmaticVelocityPagesPerSecond = 0f
            pendingCommit = false
            pendingGestureCommit = null
            targetAutoSettle = false
            targetSettleDurationMs = ARTWORK_GESTURE_SETTLE_MS
            targetSettleCurve = ArtworkSettleCurve.Gesture
            automaticFadeOnly = false
            automaticCrossfadeArtworkFade = false
            manualProgrammaticTransition = false
            queuedProgrammaticTargets.clear()
            gestureProgrammaticRetargetActive = false
            suppressedAutomaticOutgoingToken = 0

            if (targetKey == currentKey) {
                val duplicateTargetToken = targetToken
                targetKey = ""
                targetToken = 0
                targetQuality = 0
                if (duplicateTargetToken != 0 && duplicateTargetToken != currentToken) {
                    visuals.remove(duplicateTargetToken)?.release()
                }
            }

            writeUiRatioState(0f)
            publishFrame(0L)
            releaseUnusedVisuals()
            applyDeferredGestureTargetKeys()
            updateArtworkPerfState("settle_duplicate_neutral")
            PlaybackArtworkPerfTrace.stop("settle_duplicate_neutral")
            return
        }
        PlaybackArtworkPerfTrace.mark(
            "settle_finish",
            "end=${"%.3f".format(end)} cur=${PlaybackArtworkPerfTrace.keyTag(currentKey)} target=${PlaybackArtworkPerfTrace.keyTag(targetKey)} token=$targetToken",
        )
        isSettling = false
        clearContinuedGesture()
        programmaticVelocityPagesPerSecond = 0f
        if (end >= 1f && targetHolderReadyForMotion() && targetKey.isNotBlank()) {
            if (
                shouldDiscardSettledArtworkTargetAsStale(
                    gestureCommitPending = pendingCommit,
                    deferredGestureTransportPending = pendingGestureCommit != null,
                    manualProgrammaticPending = manualProgrammaticTransition,
                    boundSongKey = boundSongKey,
                    targetKey = targetKey,
                )
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
                queuedProgrammaticTargets.clear()
                gestureProgrammaticRetargetActive = false
                pendingCommit = false
                pendingGestureCommit = null
                writeUiRatioState(0f)
                suppressedAutomaticOutgoingToken = 0
                publishFrame(0L)
                releaseUnusedVisuals()
                applyDeferredGestureTargetKeys()
                updateArtworkPerfState("settle_stale_target")
                PlaybackArtworkPerfTrace.discard()
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
            clearGestureSlotForPromotedIdentity(committedKey, promotedToken)
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
            gestureProgrammaticRetargetActive = false
            if (gestureCommit != null) {
                committedGestureKey = committedKey
                committedGestureDirection = direction
                gestureCommit.invoke()
            }
            applyDeferredGestureTargetKeys()
        } else if (end <= 0f) {
            queuedProgrammaticTargets.clear()
            gestureProgrammaticRetargetActive = false
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
        updateArtworkPerfState(if (end >= 1f) "settle_commit" else "settle_rollback")
        if (end >= 1f) {
            PlaybackArtworkPerfTrace.stop("settle_commit")
        } else {
            PlaybackArtworkPerfTrace.discard()
        }
    }

    private fun writeUiRatioState(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        presentationRatio = clamped
        ratio = clamped
        PlaybackArtworkPerfTrace.ratioFrame(clamped)
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
        // ArtworkPager writes View/RenderNode geometry directly. Make the visible artwork move first;
        // only after those physical holders have the new position do we publish the Compose-side
        // ratio used by title/background layers. Previously mutableFloatStateOf was written first,
        // scheduling Snapshot/layer work before the holder update and adding avoidable input latency.
        presentationRatio = clamped
        notifyPresentationObservers(topologyChanged = false)
        ratio = clamped
        PlaybackArtworkPerfTrace.ratioFrame(clamped)
    }

    private fun setRatioFromUi(value: Float) {
        // Gesture/programmatic motion is a UI property lane, just like Reference's ArtworkPager View
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
        // Native frame ratio used to overwrite the Kotlin UI ratio here. The player artwork UI no
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
        loads.remove(key)?.cancel()
    }

    /**
     * Replace pixels inside the existing physical holder immediately.
     *
     * Reference keeps ArtworkPager page motion and ArtworkImageNode wrapper replacement as separate owners. Its
     * ArtworkImageNode can accept a newer wrapper while the outer ArtworkItemNode keeps moving, with any fade
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
        if (PlaybackArtworkPerfTrace.isActive()) PlaybackArtworkPerfTrace.discard()
        closed = true
        clearContinuedGesture()
        presentationObservers.clear()
        transitionJob?.cancel()
        nativePumpJob?.cancel()
        loads.values.forEach { it.cancel() }
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
        edgeGestureWithoutTarget = false
        pendingEdgeGestureReleaseDirection = null
        queuedProgrammaticTargets.clear()
        gestureProgrammaticRetargetActive = false
        pendingUnknownManualBindings = 0
        unknownManualBindingDeadlineMs = 0L
        nativeRetainedTokenCount = 0
        artworkImageProviderOwnerCount = 0
        NativePlayerArtworkBridge.destroy(nativeHandle)
    }
}

@Composable
fun rememberPlaybackArtworkTransitionState(
    currentKey: String?,
    queueCurrentIndex: Int,
    queueSize: Int,
    automaticCrossfadeEnabled: Boolean = false,
    passiveBindingEnabled: Boolean = true,
): PlaybackArtworkTransitionState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val state = remember(context) { PlaybackArtworkTransitionState(scope, context) }
    val key = currentKey.orEmpty()

    LaunchedEffect(passiveBindingEnabled, key, queueCurrentIndex, queueSize, automaticCrossfadeEnabled) {
        state.setAutomaticCrossfadeVisualEnabled(automaticCrossfadeEnabled)
        if (passiveBindingEnabled) {
            state.bindSong(key, queueCurrentIndex, queueSize)
        }
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

            fun pageStepPx(): Float {
                // ArtworkPager.page-step helper: normalize the pointer by the physical artwork holder side, not by the
                // outer gesture/container width. Using the outer width lets the artwork reach the
                // centre visually while ratio is still < 1, which causes a second settle on UP.
                return resolveArtworkGesturePageStepPx(
                    holderExtentPx = state.foregroundItemExtentPx(),
                    fallbackGestureExtentPx = widthPx,
                )
            }

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
                        val step = pageStepPx()
                        state.endGesture(
                            progress = state.ratio,
                            velocityPxPerSecond = velocityX,
                            artworkWidthPx = step,
                            physicalEndpointReached = abs(dragX) >= step - 0.5f,
                            gestureTravelProgress = (abs(dragX) / step).coerceIn(0f, 1f),
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
    /** Null means keep the platform/Compose default camera distance, matching modern ArtworkItemNode. */
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
        // logical holder-distance domain. Feeding the compressed 0.78 side distance into ArtworkPager's
        // alpha law leaves a parked neighbour at ~0.179 alpha; with both neighbours retained that
        // leaks previous + current + next simultaneously. A parked logical +/-1 holder is alpha 0,
        // while the actively moving current/target pair still follows the normal ArtworkPager reveal.
        alpha = inwardCarouselHolderAlpha(rawDistance),
        cameraDistance = extent * CAROUSEL_CAMERA_DISTANCE_FACTOR,
        pivotFractionX = pivotX,
        pivotFractionY = 1f
    )
}

/** Exact artwork pager transform outer-holder scale, shared by Compose math and the persistent View pager. */
internal fun ReferenceNormalHolderScale(signedDistance: Float): Float =
    1f - ((1f - PERSPECTIVE_MAX_SCALE_PARAMETER) * 0.5f) *
        abs(signedDistance.coerceIn(-1f, 1f))

/** Exact artwork pager transform outer-holder X translation, using the resolved artwork side and dense factor. */
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
    // Keep translation and scale on the exact same ArtworkPager geometry helpers used by the real View
    // pager. This prevents the fallback/title Compose lane from drifting from the top artwork's
    // physical holder during a switch or reversal.
    val scale = ReferenceNormalHolderScale(signedDistance)
    return ForegroundItemTransform(
        translationX = ReferenceNormalHolderTranslationXPx(signedDistance, extent),
        scaleX = scale,
        scaleY = scale,
        // ArtworkPager gates the rotation channels through reference vendor helper. Its static
        // initializer resolves B=true only on API <=27 and x=true on API >=28. Therefore modern
        // Reference uses X/Y perspective but deliberately suppresses Z rotation; pre-28 does the
        // inverse. Applying all three channels on Android 16 was the remaining "twisted" look.
        rotationZ = ReferenceArtworkRotationZ(signedDistance),
        rotationX = ReferenceArtworkRotationX(signedDistance),
        rotationY = ReferenceArtworkRotationY(signedDistance),
        alpha = perspectiveSlotAlpha(absoluteDistance),
        // Reference does not call View.setCameraDistance() for ArtworkPager ArtworkItemNode transforms. Both
        // Android View's default and Compose/RenderNode's default resolve to the same internal
        // camera distance (8). Supplying 1280*density directly to Compose was a unit error that
        // flattened the perspective and made the derived rotations look visually wrong.
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

/** Exact normal-holder alpha from artwork pager transform. z()'s 0.65..0.40 edge-holder envelope is not used here. */
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
    contentScale: ContentScale = ContentScale.Fit,
    cornerRadius: Dp = 28.dp,
    contentInset: Dp = 0.dp,
    contentScaleFactor: Float = 1f,
    contentElevation: Dp = 0.dp,
    hiResBadgeSettings: PlayerHiResBadgeSettings = PlayerHiResBadgeSettings(),
    hiResArtworkKeys: Set<String> = emptySet(),
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.ARTWORK_PAGER_COMPOSE_COMMIT)
        }
    }
    if (animationStyle == PlayerArtworkAnimationStyle.PerspectiveDepth) {
        // ArtworkPager is a View pager, not a Compose scene graph. Keep the exact three physical artwork
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
                        hiResBadgeSettings = hiResBadgeSettings,
                        hiResArtworkKeys = hiResArtworkKeys,
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
                    hiResBadgeSettings = hiResBadgeSettings,
                    hiResArtworkKeys = hiResArtworkKeys,
                )
            },
            onRelease = { view -> view.release() },
            modifier = modifier,
        )
        return
    }

    // Token/key ownership is snapshot state, so composition only changes when a physical holder is
    // added, removed or promoted. Ratio itself is read inside graphicsLayer below: a 60/90/120 Hz
    // swipe therefore updates RenderNode properties without rebuilding the Image/clip subtree.
    val currentToken = state.foregroundCurrentToken()
    val targetToken = state.foregroundTargetToken()
    val customBadgeBitmap = remember(hiResBadgeSettings.customPath) {
        decodePlayerHiResBadgeBitmap(hiResBadgeSettings.customPath)
    }
    DisposableEffect(customBadgeBitmap) {
        onDispose { customBadgeBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
    }

    BoxWithConstraints(modifier = modifier) {
        val itemExtentPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        state.updateForegroundItemExtentPx(itemExtentPx)
        val density = LocalDensity.current.density

        data class Holder(
            val key: String,
            val token: Int,
            val bitmap: Bitmap,
            val role: PlayerArtworkItemRole,
            val parkedDirection: PlayerArtworkDirection? = null,
        )

        fun holderForToken(
            key: String,
            token: Int,
            role: PlayerArtworkItemRole,
            parkedDirection: PlayerArtworkDirection? = null,
        ): Holder? = token
            .takeIf { it != 0 }
            ?.let(state::visual)
            ?.takeUnless(Bitmap::isRecycled)
            ?.let { Holder(key, token, it, role, parkedDirection) }

        val currentHolder = holderForToken(
            state.foregroundCurrentKey(),
            currentToken,
            PlayerArtworkItemRole.Current,
        )
        val targetHolder = holderForToken(
            state.foregroundTargetKey(),
            targetToken,
            PlayerArtworkItemRole.Target,
        )
        val previousParkedHolder = holderForToken(
            state.foregroundPreviousParkedKey(),
            state.foregroundPreviousParkedToken(),
            PlayerArtworkItemRole.Target,
            PlayerArtworkDirection.Previous,
        )
        val nextParkedHolder = holderForToken(
            state.foregroundNextParkedKey(),
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
        // Parked neighbours stay alpha-zero at their ArtworkPager side position until one is promoted.
        val parkedHolders = listOfNotNull(previousParkedHolder, nextParkedHolder)
            .filterNot { parked -> activeHolders.any { it.key == parked.key || it.token == parked.token } }
        val holders = (parkedHolders + activeHolders)
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
                    // three physical ArtworkItemNode holders stay attached; only their properties change.
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
                    if (hiResBadgeSettings.enabled && holder.key in hiResArtworkKeys) {
                        PlayerHiResBadgeOverlay(
                            corner = hiResBadgeSettings.corner,
                            customBitmap = customBadgeBitmap,
                            contentInset = contentInset,
                            contentScaleFactor = contentScaleFactor,
                        )
                    }
                }
            }
        }
    }
}


/**
 * Generic metadata lane that follows the exact same persistent artwork holder topology as artwork.
 * Reference keeps title/line2/meta inside ArtworkItemNode; landscape RawSMusic previously rendered a
 * single global current-song Text which changed immediately while the artwork holder was moving.
 */
@Composable
fun PlaybackArtworkMetadataTransition(
    state: PlaybackArtworkTransitionState,
    animationStyle: PlayerArtworkAnimationStyle = PlayerArtworkAnimationStyle.PerspectiveDepth,
    modifier: Modifier = Modifier,
    content: @Composable (artworkKey: String, isInteractiveCurrent: Boolean) -> Unit,
) {
    SideEffect {
        if (PlaybackArtworkPerfTrace.isActive()) {
            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.ARTWORK_METADATA_COMPOSE_COMMIT)
        }
    }
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
        (listOf(previous, next).filterNot { parked ->
            active.any { it.key.isNotBlank() &&
                (it.key == parked.key || (it.token != 0 && it.token == parked.token)) }
        } + active)
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
