package com.rawsmusic.core.ui.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** Shared predictive-back motion for non-settings dialogs and popup cards. */
@Composable
fun rememberPredictiveDialogProgress(
    enabled: Boolean,
    onDismissRequest: () -> Unit
): State<Float> {
    val progress = remember { Animatable(0f) }
    val progressState = remember(progress) {
        object : State<Float> {
            override val value: Float
                get() = progress.value
        }
    }
    val scope = rememberCoroutineScope()
    val latestDismiss = rememberUpdatedState(onDismissRequest)

    LaunchedEffect(enabled) {
        if (enabled) progress.snapTo(0f)
    }

    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = enabled,
        onBackCancelled = {
            scope.launch {
                progress.animateTo(0f, tween(140))
            }
        },
        onBackCompleted = {
            scope.launch {
                // A non-gesture back can complete without ever publishing predictive progress.
                // Leave that at zero so the owner performs its normal timed exit. A real edge
                // gesture has already moved the visual; only that path commits directly to 1.
                if (progress.value > 0.001f) progress.snapTo(1f)
                latestDismiss.value()
            }
        }
    )

    LaunchedEffect(navigationEventState) {
        snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
            if (
                transitionState is NavigationEventTransitionState.InProgress &&
                transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
            ) {
                progress.snapTo(transitionState.latestEvent.progress.coerceIn(0f, 1f))
            }
        }
    }

    return progressState
}

fun Modifier.predictiveDialogMotion(
    progress: State<Float>,
    translationY: Dp = 64.dp,
    transformOrigin: TransformOrigin = TransformOrigin.Center
): Modifier = graphicsLayer {
    val amount = progress.value.coerceIn(0f, 1f)
    val scale = 1f - amount * 0.10f
    scaleX = scale
    scaleY = scale
    alpha = 1f - amount * 0.22f
    compositingStrategy = CompositingStrategy.ModulateAlpha
    this.translationY = amount * translationY.toPx()
    this.transformOrigin = transformOrigin
}

/**
 * Predictive-back motion tuned for bottom sheets.
 *
 * A bottom sheet should keep its lower edge visually anchored while the system back gesture is
 * still in progress. Moving it down while applying only a very small scale change reads as the
 * reverse of the 200 ms bottom-up entrance instead of the generic dialog shrink motion.
 */
fun Modifier.predictiveBottomSheetMotion(
    progress: State<Float>,
    translationY: Dp = 176.dp,
): Modifier = graphicsLayer {
    val amount = progress.value.coerceIn(0f, 1f)
    val scale = 1f - amount * 0.025f
    scaleX = scale
    scaleY = scale
    alpha = 1f - amount * 0.14f
    compositingStrategy = CompositingStrategy.ModulateAlpha
    this.translationY = amount * translationY.toPx()
    transformOrigin = TransformOrigin(0.5f, 1f)
}

/** Predictive-back companion for the dim layer behind a bottom sheet. */
fun Modifier.predictiveBottomSheetScrim(progress: State<Float>): Modifier = graphicsLayer {
    val amount = progress.value.coerceIn(0f, 1f)
    alpha = 1f - amount * 0.92f
    compositingStrategy = CompositingStrategy.ModulateAlpha
}
