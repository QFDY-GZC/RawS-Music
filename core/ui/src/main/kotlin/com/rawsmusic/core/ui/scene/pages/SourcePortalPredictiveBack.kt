package com.rawsmusic.core.ui.scene.pages

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Visibility gate for the Activity scene-level predictive back callback.
 *
 * The source portal has an internal hierarchy (tab -> source tab, lyrics -> player -> browse). While
 * one of those internal destinations is visible, its Compose NavigationBackHandler owns the gesture
 * and the Activity callback must stand down. At the browse/source root this runtime is inactive so
 * the existing SOURCE_IMPORT -> HOME scene transition remains the owner.
 */
object SourcePortalBackRuntime {
    private const val SCENE_BACK_COMMIT_GUARD_MS = 420L
    private val lock = Any()
    private val activeTokens = LinkedHashSet<Any>()

    @Volatile
    private var suppressSceneBackUntilUptimeMs: Long = 0L

    var activeCount by mutableIntStateOf(0)
        private set

    internal fun attach(token: Any) {
        synchronized(lock) {
            activeTokens += token
            activeCount = activeTokens.size
        }
    }

    internal fun detach(token: Any) {
        synchronized(lock) {
            activeTokens -= token
            activeCount = activeTokens.size
        }
    }

    /**
     * A completed internal predictive-back gesture may be delivered more than once while the
     * destination recomposes and the Activity callback is re-enabled. Keep a short time barrier so
     * every duplicate delivery is consumed instead of only the first one.
     */
    internal fun markInternalBackCommitted() {
        suppressSceneBackUntilUptimeMs = SystemClock.uptimeMillis() + SCENE_BACK_COMMIT_GUARD_MS
    }

    fun shouldSuppressSceneBack(): Boolean =
        SystemClock.uptimeMillis() < suppressSceneBackUntilUptimeMs

    fun consumeSuppressedSceneBack(): Boolean = shouldSuppressSceneBack()
}

private const val PREDICTIVE_BACK_COMPLETE_MS = 220
private const val PREDICTIVE_BACK_CANCEL_MS = 180
private val PredictiveBackSettleEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Drives one predictive-back preview.
 *
 * Gesture progress is followed synchronously. On commit we keep the old and destination pages alive,
 * animate from the released progress to 1, and only then mutate navigation. This avoids the previous
 * one-frame jump from the user's release point straight to the final page. Cancellation similarly
 * eases back to zero instead of snapping.
 */
@Composable
internal fun rememberSourcePortalPredictiveBackProgress(
    enabled: Boolean,
    destinationKey: Any?,
    onBackCompleted: (wasPredictiveGesture: Boolean) -> Unit,
): Float {
    val token = remember { Any() }
    val latestBack by rememberUpdatedState(onBackCompleted)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var gestureActive by remember { mutableStateOf(false) }
    var commitSettling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var completionConsumed by remember(destinationKey) { mutableStateOf(false) }

    DisposableEffect(enabled, token) {
        if (enabled) SourcePortalBackRuntime.attach(token)
        onDispose {
            settleJob?.cancel()
            SourcePortalBackRuntime.detach(token)
        }
    }

    LaunchedEffect(enabled, destinationKey) {
        settleJob?.cancel()
        settleJob = null
        gestureActive = false
        commitSettling = false
        progress.snapTo(0f)
    }

    fun settleDuration(baseDurationMs: Int, distance: Float): Int =
        (baseDurationMs * distance.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(72, baseDurationMs)

    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = enabled,
        onBackCancelled = {
            gestureActive = false
            commitSettling = false
            settleJob?.cancel()
            val distance = progress.value.coerceIn(0f, 1f)
            settleJob = scope.launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = settleDuration(PREDICTIVE_BACK_CANCEL_MS, distance),
                        easing = PredictiveBackSettleEasing,
                    ),
                )
            }
        },
        onBackCompleted = {
            val duplicateCommit = completionConsumed || SourcePortalBackRuntime.shouldSuppressSceneBack()
            val wasPredictiveGesture = gestureActive || progress.value > 0.001f
            gestureActive = false

            if (duplicateCommit) {
                // A repeated completion while the first commit is settling must not cancel that
                // in-flight animation. Once it reaches 1, the original callback will pop exactly one
                // destination.
                if (!commitSettling) {
                    settleJob?.cancel()
                    settleJob = scope.launch {
                        progress.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = settleDuration(PREDICTIVE_BACK_CANCEL_MS, progress.value),
                                easing = PredictiveBackSettleEasing,
                            ),
                        )
                    }
                }
                return@NavigationBackHandler
            }

            settleJob?.cancel()
            completionConsumed = true
            SourcePortalBackRuntime.markInternalBackCommitted()
            if (!wasPredictiveGesture) {
                commitSettling = false
                latestBack(false)
                settleJob = scope.launch { progress.snapTo(0f) }
                return@NavigationBackHandler
            }

            commitSettling = true
            val remaining = (1f - progress.value).coerceIn(0f, 1f)
            settleJob = scope.launch {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = settleDuration(PREDICTIVE_BACK_COMPLETE_MS, remaining),
                        easing = PredictiveBackSettleEasing,
                    ),
                )
                // Commit only after the visual transition reaches its final frame. The destination-key
                // effect resets progress once the navigation stack changes.
                latestBack(true)
            }
        },
    )

    LaunchedEffect(navigationEventState) {
        snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
            if (
                transitionState is NavigationEventTransitionState.InProgress &&
                transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK &&
                !commitSettling
            ) {
                settleJob?.cancel()
                settleJob = null
                gestureActive = true
                progress.snapTo(transitionState.latestEvent.progress.coerceIn(0f, 1f))
            }
        }
    }

    return progress.value.coerceIn(0f, 1f)
}

internal fun Modifier.sourcePortalPredictiveBackMotion(progress: Float): Modifier = graphicsLayer {
    val amount = progress.coerceIn(0f, 1f)
    translationX = 0f
    val scale = 1f - amount * 0.25f
    scaleX = scale
    scaleY = scale
    alpha = 1f - amount
    transformOrigin = TransformOrigin.Center
    clip = false
}
