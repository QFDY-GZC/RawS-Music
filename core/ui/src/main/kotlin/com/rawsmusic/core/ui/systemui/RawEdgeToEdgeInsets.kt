package com.rawsmusic.core.ui.systemui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom inset handling for gesture navigation.
 *
 * The visual surface is allowed to draw behind the system gesture handle. Only floating or
 * interactive chrome is lifted by a reduced bottom inset so that we do not create a solid
 * navigation-bar strip.
 */
@Composable
fun rawReducedNavigationBottomPadding(
    reduceBy: Dp = 12.dp,
    minimum: Dp = 0.dp
): Dp {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return (bottom - reduceBy).coerceAtLeast(minimum)
}

@Composable
fun Modifier.rawNavigationBarsPadding(
    reduceBy: Dp = 12.dp,
    minimum: Dp = 0.dp
): Modifier {
    val bottom = rawReducedNavigationBottomPadding(reduceBy = reduceBy, minimum = minimum)
    return this.padding(bottom = bottom)
}
