package com.rawsmusic.core.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** Shared predictive-back motion for non-settings dialogs and popup cards. */
@Composable
fun rememberPredictiveDialogProgress(
    enabled: Boolean,
    onDismissRequest: () -> Unit
): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    var gestureActive by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)

    LaunchedEffect(enabled) {
        gestureActive = false
        progress = 0f
    }

    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = enabled,
        onBackCancelled = {
            gestureActive = false
            progress = 0f
        },
        onBackCompleted = {
            gestureActive = false
            progress = 1f
            latestDismiss()
        }
    )

    LaunchedEffect(navigationEventState) {
        snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
            if (
                transitionState is NavigationEventTransitionState.InProgress &&
                transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
            ) {
                gestureActive = true
                progress = transitionState.latestEvent.progress.coerceIn(0f, 1f)
            }
        }
    }

    val visualProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (gestureActive || progress == 0f) snap() else tween(140),
        label = "predictive-dialog-progress"
    )
    return visualProgress
}

fun Modifier.predictiveDialogMotion(
    progress: Float,
    translationY: Dp = 64.dp,
    transformOrigin: TransformOrigin = TransformOrigin.Center
): Modifier = graphicsLayer {
    val amount = progress.coerceIn(0f, 1f)
    val scale = 1f - amount * 0.10f
    scaleX = scale
    scaleY = scale
    alpha = 1f - amount * 0.22f
    this.translationY = amount * translationY.toPx()
    this.transformOrigin = transformOrigin
}
