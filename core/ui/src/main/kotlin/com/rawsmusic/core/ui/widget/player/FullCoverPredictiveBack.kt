package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Predictive-back bridge for Compose-owned full-cover hosts such as the landscape player.
 *
 * It owns only gesture delivery. Scene geometry and settle animation stay in the caller so both
 * hosts can keep their existing 360/320 ms transition model.
 */
@Composable
fun FullCoverPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancelled: () -> Unit,
    onCompleted: (wasPredictiveGesture: Boolean) -> Unit,
) {
    val latestProgress by rememberUpdatedState(onProgress)
    val latestCancelled by rememberUpdatedState(onCancelled)
    val latestCompleted by rememberUpdatedState(onCompleted)
    var gestureActive by remember { mutableStateOf(false) }
    var completionConsumed by remember(enabled) { mutableStateOf(false) }
    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = enabled,
        onBackCancelled = {
            gestureActive = false
            completionConsumed = false
            latestCancelled()
        },
        onBackCompleted = {
            if (completionConsumed) return@NavigationBackHandler
            completionConsumed = true
            val wasPredictive = gestureActive
            gestureActive = false
            latestCompleted(wasPredictive)
        },
    )

    LaunchedEffect(navigationEventState, enabled) {
        snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
            if (
                enabled &&
                transitionState is NavigationEventTransitionState.InProgress &&
                transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
            ) {
                gestureActive = true
                completionConsumed = false
                latestProgress(transitionState.latestEvent.progress.coerceIn(0f, 1f))
            }
        }
    }
}
