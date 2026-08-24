package com.rawsmusic.core.ui.systemui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stable inset handling for gesture navigation.
 *
 * Layout geometry must not depend on whether the OEM currently draws the gesture handle.
 * `navigationBarsIgnoringVisibility` keeps the same safe area when the small system handle is
 * hidden, preventing the bottom chrome and player content from shifting downward. Visual surfaces
 * can still draw edge-to-edge; only interactive chrome opts into the full or reduced stable inset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rawStableNavigationBottomPadding(): Dp =
    WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

@Composable
fun rawReducedNavigationBottomPadding(
    reduceBy: Dp = 12.dp,
    minimum: Dp = 0.dp
): Dp {
    val bottom = rawStableNavigationBottomPadding()
    return (bottom - reduceBy).coerceAtLeast(minimum)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.rawStableNavigationBarsPadding(): Modifier =
    this.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

@Composable
fun Modifier.rawNavigationBarsPadding(
    reduceBy: Dp = 12.dp,
    minimum: Dp = 0.dp
): Modifier {
    val bottom = rawReducedNavigationBottomPadding(reduceBy = reduceBy, minimum = minimum)
    return this.padding(bottom = bottom)
}
