package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp

internal enum class SourcePortalRoute {
    Browse,
    Player,
    Lyrics,
}

internal data class SourcePortalRouteTransition(
    val id: Int,
    val from: SourcePortalRoute,
    val to: SourcePortalRoute,
    val direction: SourcePortalTabNavigationDirection,
)

private const val ROUTE_TRANSITION_MS = 320
private const val ROUTE_SCALE_MIN = 0.75f
private const val ROUTE_SCALE_MAX = 1.25f
private val RouteDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Two-layer route host for Browse / Player / Lyrics.
 *
 * The destination is kept alive below the outgoing page for the entire predictive-back gesture.
 * The pair is frozen until progress returns to zero, so changing the navigation state at commit
 * cannot expose the next back destination for one frame.
 */
@Composable
internal fun SourcePortalRouteTransitionHost(
    currentRoute: SourcePortalRoute,
    transition: SourcePortalRouteTransition?,
    predictiveBackProgress: Float,
    predictiveBackTarget: SourcePortalRoute?,
    onTransitionFinished: (transitionId: Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (SourcePortalRoute) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val predictive = predictiveBackProgress.coerceIn(0f, 1f)
        var frozenPair by remember { mutableStateOf<Pair<SourcePortalRoute, SourcePortalRoute>?>(null) }
        val candidatePair = predictiveBackTarget
            ?.takeIf { it != currentRoute }
            ?.let { currentRoute to it }
        val activePair = if (predictive > 0f) frozenPair ?: candidatePair else null

        SideEffect {
            when {
                predictive <= 0f -> frozenPair = null
                frozenPair == null && candidatePair != null -> frozenPair = candidatePair
            }
        }

        if (activePair != null) {
            val (from, to) = activePair
            SourcePortalRouteLayer(
                scale = lerp(ROUTE_SCALE_MAX, 1f, predictive),
                alpha = predictive,
            ) { content(to) }
            SourcePortalRouteLayer(
                scale = lerp(1f, ROUTE_SCALE_MIN, predictive),
                alpha = 1f - predictive,
            ) { content(from) }
        } else if (transition != null && transition.to == currentRoute) {
            key(transition.id) {
                SourcePortalRunningRouteTransition(
                    transition = transition,
                    onFinished = onTransitionFinished,
                    content = content,
                )
            }
        } else {
            content(currentRoute)
        }
    }
}

@Composable
private fun SourcePortalRunningRouteTransition(
    transition: SourcePortalRouteTransition,
    onFinished: (transitionId: Int) -> Unit,
    content: @Composable (SourcePortalRoute) -> Unit,
) {
    val progress = remember(transition.id) { Animatable(0f) }
    LaunchedEffect(transition.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(ROUTE_TRANSITION_MS, easing = RouteDecelerate),
        )
        onFinished(transition.id)
    }

    val amount = progress.value.coerceIn(0f, 1f)
    when (transition.direction) {
        SourcePortalTabNavigationDirection.Forward -> {
            SourcePortalRouteLayer(
                scale = lerp(ROUTE_SCALE_MIN, 1f, amount),
                alpha = amount,
            ) { content(transition.to) }
            SourcePortalRouteLayer(
                scale = lerp(1f, ROUTE_SCALE_MAX, amount),
                alpha = 1f - amount,
            ) { content(transition.from) }
        }

        SourcePortalTabNavigationDirection.Back -> {
            SourcePortalRouteLayer(
                scale = lerp(ROUTE_SCALE_MAX, 1f, amount),
                alpha = amount,
            ) { content(transition.to) }
            SourcePortalRouteLayer(
                scale = lerp(1f, ROUTE_SCALE_MIN, amount),
                alpha = 1f - amount,
            ) { content(transition.from) }
        }
    }
}

@Composable
private fun SourcePortalRouteLayer(
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

/** Applies the same route transform to the Browse-only mini-player and bottom navigation. */
@Composable
internal fun SourcePortalBrowseChromeTransitionHost(
    currentRoute: SourcePortalRoute,
    transition: SourcePortalRouteTransition?,
    predictiveBackProgress: Float,
    predictiveBackTarget: SourcePortalRoute?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val predictive = predictiveBackProgress.coerceIn(0f, 1f)
    var frozenPair by remember { mutableStateOf<Pair<SourcePortalRoute, SourcePortalRoute>?>(null) }
    val candidatePair = predictiveBackTarget
        ?.takeIf { it != currentRoute }
        ?.let { currentRoute to it }
    val activePair = if (predictive > 0f) frozenPair ?: candidatePair else null

    SideEffect {
        when {
            predictive <= 0f -> frozenPair = null
            frozenPair == null && candidatePair != null -> frozenPair = candidatePair
        }
    }

    val normalProgress = remember(transition?.id) {
        Animatable(if (transition == null) 1f else 0f)
    }
    LaunchedEffect(transition?.id) {
        if (transition != null) {
            normalProgress.snapTo(0f)
            normalProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(ROUTE_TRANSITION_MS, easing = RouteDecelerate),
            )
        }
    }
    val normalAmount = normalProgress.value.coerceIn(0f, 1f)

    val (scale, alpha) = when {
        activePair != null && activePair.second == SourcePortalRoute.Browse ->
            lerp(ROUTE_SCALE_MAX, 1f, predictive) to predictive
        activePair != null && activePair.first == SourcePortalRoute.Browse ->
            lerp(1f, ROUTE_SCALE_MIN, predictive) to (1f - predictive)
        transition != null && transition.to == SourcePortalRoute.Browse ->
            lerp(ROUTE_SCALE_MAX, 1f, normalAmount) to normalAmount
        transition != null && transition.from == SourcePortalRoute.Browse ->
            lerp(1f, ROUTE_SCALE_MAX, normalAmount) to (1f - normalAmount)
        currentRoute == SourcePortalRoute.Browse -> 1f to 1f
        else -> 1f to 0f
    }

    if (alpha > 0.001f) {
        Box(
            modifier = modifier.graphicsLayer {
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
}
