package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp

internal enum class SourcePortalTabNavigationDirection {
    Forward,
    Back,
}

/**
 * One immutable page transition request.
 *
 * Navigation owns the current page. The transition host only renders this from/to snapshot and
 * therefore cannot drift away from the bottom-navigation state.
 */
internal data class SourcePortalTabTransition(
    val id: Int,
    val from: SourcePortalTab,
    val to: SourcePortalTab,
    val direction: SourcePortalTabNavigationDirection,
)

private const val TAB_TRANSITION_MS = 320
private const val SCALE_MIN = 0.75f
private const val SCALE_MAX = 1.25f
private val Decelerate2 = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Stateless two-layer tab transition matching RawSMusic's generic scene motion.
 *
 * [currentTab] is always the navigation stack's final destination. Unlike the old host, this
 * composable never keeps another remembered currentTab, so the selected bottom tab and the page
 * content cannot diverge.
 */
@Composable
internal fun SourcePortalTabTransitionHost(
    currentTab: SourcePortalTab,
    transition: SourcePortalTabTransition?,
    predictiveBackProgress: Float,
    predictiveBackTarget: SourcePortalTab?,
    onTransitionFinished: (transitionId: Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (SourcePortalTab) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val predictive = predictiveBackProgress.coerceIn(0f, 1f)
        var frozenPair by remember { mutableStateOf<Pair<SourcePortalTab, SourcePortalTab>?>(null) }
        val candidatePair = predictiveBackTarget
            ?.takeIf { it != currentTab }
            ?.let { currentTab to it }
        val activePair = if (predictive > 0f) frozenPair ?: candidatePair else null
        SideEffect {
            when {
                predictive <= 0f -> frozenPair = null
                frozenPair == null && candidatePair != null -> frozenPair = candidatePair
            }
        }
        if (activePair != null) {
            val (predictiveFrom, predictiveTarget) = activePair
            // Generic back transform: destination 1.25 -> 1 while current 1 -> 0.75.
            SourcePortalTabLayer(
                scale = lerp(SCALE_MAX, 1f, predictive),
                alpha = predictive,
            ) { content(predictiveTarget) }
            SourcePortalTabLayer(
                scale = lerp(1f, SCALE_MIN, predictive),
                alpha = 1f - predictive,
            ) { content(predictiveFrom) }
        } else if (transition != null && transition.to == currentTab) {
            key(transition.id) {
                SourcePortalRunningTabTransition(
                    transition = transition,
                    onFinished = onTransitionFinished,
                    content = content,
                )
            }
        } else {
            content(currentTab)
        }
    }
}

@Composable
private fun SourcePortalRunningTabTransition(
    transition: SourcePortalTabTransition,
    onFinished: (transitionId: Int) -> Unit,
    content: @Composable (SourcePortalTab) -> Unit,
) {
    // A fresh Animatable is created for every immutable transition id, preventing the previous
    // animation coroutine from leaving a stale current page behind after rapid tab changes.
    val progress = remember(transition.id) { Animatable(0f) }
    LaunchedEffect(transition.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(TAB_TRANSITION_MS, easing = Decelerate2),
        )
        onFinished(transition.id)
    }

    val amount = progress.value.coerceIn(0f, 1f)
    when (transition.direction) {
        SourcePortalTabNavigationDirection.Forward -> {
            // Destination is below; outgoing page remains above while fading away.
            SourcePortalTabLayer(
                scale = lerp(SCALE_MIN, 1f, amount),
                alpha = amount,
            ) { content(transition.to) }
            SourcePortalTabLayer(
                scale = lerp(1f, SCALE_MAX, amount),
                alpha = 1f - amount,
            ) { content(transition.from) }
        }

        SourcePortalTabNavigationDirection.Back -> {
            SourcePortalTabLayer(
                scale = lerp(SCALE_MAX, 1f, amount),
                alpha = amount,
            ) { content(transition.to) }
            SourcePortalTabLayer(
                scale = lerp(1f, SCALE_MIN, amount),
                alpha = 1f - amount,
            ) { content(transition.from) }
        }
    }
}

@Composable
private fun SourcePortalTabLayer(
    scale: Float,
    alpha: Float,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin.Center
                clip = false
            },
    ) {
        content()
    }
}
