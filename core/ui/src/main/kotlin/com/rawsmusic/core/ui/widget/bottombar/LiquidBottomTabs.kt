package com.rawsmusic.core.ui.widget.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.rawsmusic.core.ui.widget.utils.InteractiveHighlight
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Bottom navigation with distinct Halcyon-style blur and liquid glass paths.
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val isLightTheme = scheme.background.luminance() > 0.5f
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val containerColor = when {
        performanceMode && isLightTheme -> Color.White.copy(alpha = 0.56f)
        performanceMode -> Color(0xFF111114).copy(alpha = 0.58f)
        isLightTheme -> Color.White.copy(alpha = 0.34f)
        else -> Color(0xFF111114).copy(alpha = 0.38f)
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    onTabSelected(targetIndex)
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f
                    )
                }
            )
        }

        val indicatorPosition = Modifier
            .padding(horizontal = 4f.dp)
            .graphicsLayer {
                translationX = if (isLtr) {
                    dampedDragAnimation.value * tabWidth + panelOffset
                } else {
                    size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
            }

        // Base glass, selected droplet, content, and gesture input are separate layers.
        // The demo's selected lens is otherwise flattened by a second full-bar backdrop draw.
        Box(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        if (performanceMode) {
                            blur(34f.dp.toPx())
                        } else {
                            blur(7f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = if (performanceMode) {
                                if (isLightTheme) 0.22f else 0.14f
                            } else {
                                if (isLightTheme) 0.18f else 0.10f
                            }
                        )
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(
                                alpha = if (performanceMode) {
                                    if (isLightTheme) 0.12f else 0.30f
                                } else {
                                    if (isLightTheme) 0.12f else 0.26f
                                }
                            )
                        )
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 2f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
        )

        Box(
            indicatorPosition
                .then(
                    if (performanceMode) {
                        Modifier
                            .background(
                                if (isLightTheme) Color(0xFF0A7BEF).copy(alpha = 0.12f)
                                else Color(0xFF55AFFF).copy(alpha = 0.15f),
                                Capsule()
                            )
                            .graphicsLayer {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                            }
                    } else {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                blur(5f.dp.toPx())
                                lens(11f.dp.toPx(), 15f.dp.toPx(), depthEffect = true)
                            },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (isLightTheme) 0.24f else 0.16f
                                )
                            },
                            shadow = {
                                Shadow.Default.copy(
                                    color = Color.Black.copy(
                                        alpha = if (isLightTheme) 0.025f else 0.08f
                                    )
                                )
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 9f
                                scaleX /= 1f - (velocity * 0.82f).fastCoerceIn(-0.28f, 0.28f)
                                scaleY *= 1f - (velocity * 0.30f).fastCoerceIn(-0.22f, 0.22f)
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (isLightTheme) Color.White.copy(alpha = 0.18f)
                                    else Color.White.copy(alpha = 0.10f)
                                )
                            }
                        )
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.035f, dampedDragAnimation.pressProgress)
            },
            LocalLiquidBottomTabIsDragging provides {
                dampedDragAnimation.isDragAnimating
            }
        ) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .height(64f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            indicatorPosition
            .then(interactiveHighlight.gestureModifier)
            .then(dampedDragAnimation.modifier)
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
