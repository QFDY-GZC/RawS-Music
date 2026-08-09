package com.rawsmusic.core.ui.widget.bottombar

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.BottomNavigationEntryIcon
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.bottomNavigationLabel
import com.rawsmusic.core.ui.systemui.rawReducedNavigationBottomPadding
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.widget.flow.darkAlbumGradient
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import kotlin.math.exp

private const val NORMAL_BOTTOM_BAR_ANCHOR = 0.5f

/**
 * The resource-backed, stacked bottom chrome used by the Normal style.
 *
 * The stacked bottom-navigation surface is one full-width root. The mini-player is a
 * 64dp touch panel in that root and the 56dp navigation layer is a sibling below it;
 * neither layer is wrapped in a floating card. During an upward sheet drag only the
 * navigation layer moves, using the `1 - exp(-20 * slideOffset)` curve. The player
 * scene receives the same normalized offset and takes over the remaining expansion.
 */
@Composable
fun NormalBottomChrome(
    title: String,
    artist: String,
    lyricText: String,
    lyricTranslation: String,
    isPlaying: Boolean,
    progress: Float,
    coverPath: String?,
    currentSong: AudioFile?,
    previousSong: AudioFile?,
    nextSong: AudioFile?,
    queueCurrentIndex: Int,
    queueSize: Int,
    accentColor: Color,
    backdrop: Backdrop?,
    tabScenes: List<NavScene>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onExpandDragStart: () -> Unit = {},
    onExpandDragProgress: (Float) -> Unit = {},
    onExpandDragEnd: (Boolean, Float) -> Unit = { _, _ -> },
    drivePlayerScene: Boolean = false,
    playerSceneProgressState: State<Float>? = null,
    onCoverBoundsChanged: (android.graphics.RectF?) -> Unit = {},
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val flingVelocityBoundsPx = remember(context) {
        ViewConfiguration.get(context).let { config ->
            config.scaledMinimumFlingVelocity.toFloat() to
                config.scaledMaximumFlingVelocity.toFloat()
        }
    }
    val minimumFlingVelocityPx = flingVelocityBoundsPx.first
    val maximumFlingVelocityPx = flingVelocityBoundsPx.second
    val scope = rememberCoroutineScope()
    val expansion = remember { Animatable(0f) }
    var dragActive by remember { mutableStateOf(false) }
    var dragExpansion by remember { mutableFloatStateOf(0f) }
    val latestOpenPlayer by rememberUpdatedState(onOpenPlayer)
    val latestExpandDragStart by rememberUpdatedState(onExpandDragStart)
    val latestExpandDragProgress by rememberUpdatedState(onExpandDragProgress)
    val latestExpandDragEnd by rememberUpdatedState(onExpandDragEnd)
    val miniPlayerHeight = 66.dp
    val navigationHeight = 56.dp
    // AppMainLayout lifts NORMAL chrome by the same reduced gesture-navigation inset. Include
    // that inset in both the bottom-sheet peek height and the stacked navigation exit distance.
    val reducedNavigationBottomPadding = rawReducedNavigationBottomPadding(reduceBy = 12.dp)
    val miniPlayerHeightPx = with(density) { miniPlayerHeight.toPx() }
    val navigationHeightPx = with(density) { navigationHeight.toPx() }
    val reducedNavigationBottomPaddingPx = with(density) { reducedNavigationBottomPadding.toPx() }
    val expandedDistancePx = (
        windowInfo.containerSize.height.toFloat() -
            miniPlayerHeightPx -
            navigationHeightPx -
            reducedNavigationBottomPaddingPx
    ).coerceAtLeast(1f)
    fun currentExpansion(): Float = if (drivePlayerScene && playerSceneProgressState != null) {
        playerSceneProgressState.value.coerceIn(0f, 1f)
    } else {
        expansion.value
    }

    fun settle(target: Float) {
        scope.launch {
            expansion.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = 600,
                    easing = androidx.compose.animation.core.Easing { value ->
                        val shifted = value - 1f
                        shifted * shifted * shifted * shifted * shifted + 1f
                    },
                ),
            )
        }
    }

    val backgroundColors = remember(accentColor) {
        darkAlbumGradient(accentColor)
    }
    // Dark artwork uses a white foreground so controls remain legible,
    // for the mini-player foreground. Keep navigation foreground in the same high-contrast family.
    val dividerColor = Color.White.copy(alpha = 0.12f)
    val selectedColor = Color.White
    val unselectedColor = Color.White.copy(alpha = 0.66f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Do not treat the stacked mini-player + navigation as one fading visual
            // surface. Keep the parent transparent: the mini-player owns its own background and
            // fades as one source bar, while navigation remains a separate translating sibling.
            .pointerInput(expandedDistancePx, drivePlayerScene) {
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        dragActive = true
                        dragExpansion = currentExpansion()
                        velocityTracker.resetTracking()
                        if (!drivePlayerScene) scope.launch { expansion.stop() }
                        if (drivePlayerScene) latestExpandDragStart()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val next = (dragExpansion - dragAmount / expandedDistancePx)
                            .coerceIn(0f, 1f)
                        dragExpansion = next
                        if (drivePlayerScene) {
                            latestExpandDragProgress(next)
                        } else {
                            scope.launch { expansion.snapTo(next) }
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val rawVelocityY = velocityTracker.calculateVelocity().y
                        val velocityY = when {
                            abs(rawVelocityY) < minimumFlingVelocityPx -> 0f
                            rawVelocityY > maximumFlingVelocityPx -> maximumFlingVelocityPx
                            rawVelocityY < -maximumFlingVelocityPx -> -maximumFlingVelocityPx
                            else -> rawVelocityY
                        }
                        // BottomSheetBehavior chooses the (clamped) velocity direction first and
                        // only uses the nearest anchor when ViewDragHelper zeroes the velocity.
                        val shouldOpen = when {
                            velocityY < 0f -> true
                            velocityY > 0f -> false
                            else -> dragExpansion >= NORMAL_BOTTOM_BAR_ANCHOR
                        }
                        if (drivePlayerScene) {
                            // ViewDragHelper's settle duration is based on parent width even for a
                            // vertical sheet. Normalize by that same dimension before handing the
                            // velocity to PlayerSceneController.
                            val parentWidthPx = windowInfo.containerSize.width.toFloat().coerceAtLeast(1f)
                            latestExpandDragEnd(shouldOpen, velocityY / parentWidthPx)
                        } else {
                            settle(if (shouldOpen) 1f else 0f)
                        }
                        dragActive = false
                    },
                    onDragCancel = {
                        val shouldOpen = dragExpansion >= NORMAL_BOTTOM_BAR_ANCHOR
                        if (drivePlayerScene) {
                            latestExpandDragEnd(shouldOpen, 0f)
                        } else {
                            settle(if (shouldOpen) 1f else 0f)
                        }
                        dragActive = false
                    },
                )
            },
    ) {
        // Fade the MiniPlayer binding/content root, not the stacked bottom-chrome substrate.
        // Keep the bar background mounted and opaque while only the actual MiniPlayer
        // content participates in exp(-300p). Otherwise PLAYER -> MAIN ends with the player-sheet
        // backdrop fading away at the same time as this whole bar (including its background) fades
        // back in, which reads as a one-frame brightness dip/flash. The navigation sibling already
        // behaves this way: its background remains present while only its geometry translates.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(miniPlayerHeight)
                .background(Brush.horizontalGradient(backgroundColors)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(miniPlayerHeight)
                    .graphicsLayer {
                        // MiniPlayer content alpha follows exp(-300 * slideOffset).
                        // Keep the content node mounted for interrupted reverse drags, but do not
                        // include the bottom-chrome background in this alpha handoff.
                        alpha = exp(-300f * currentExpansion())
                    },
            ) {
                ComposeMiniPlayer(
                title = title,
                artist = artist,
                lyricText = lyricText,
                lyricTranslation = lyricTranslation,
                isPlaying = isPlaying,
                progress = progress,
                coverPath = coverPath,
                currentSong = currentSong,
                previousSong = previousSong,
                nextSong = nextSong,
                queueCurrentIndex = queueCurrentIndex,
                queueSize = queueSize,
                backdrop = backdrop,
                animateArtwork = false,
                drawBackground = false,
                drawOuterProgress = false,
                containerHeight = miniPlayerHeight,
                containerShape = RectangleShape,
                clipContent = false,
                contentPaddingHorizontal = 16.dp,
                contentPaddingVertical = 6.dp,
                primaryContentColor = Color.White,
                secondaryContentColor = Color.White,
                onClick = {
                    latestOpenPlayer()
                },
                onPlayPause = onPlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onCoverBoundsChanged = onCoverBoundsChanged,
                onCoverTargetChanged = onCoverTargetChanged,
                modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(navigationHeight)
                .background(Brush.horizontalGradient(backgroundColors))
                .graphicsLayer {
                    // This is a translation, not a fade or a scale. It mirrors
                    // PlayerActivity.StackedBottomNavigationHolder.c(float).
                    translationY = (navigationHeightPx + reducedNavigationBottomPaddingPx) *
                        (1f - exp(-20f * currentExpansion()))
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(dividerColor),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(navigationHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabScenes.forEachIndexed { index, scene ->
                    val selected = index == selectedTabIndex
                    val tint = if (selected) selectedColor else unselectedColor
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(navigationHeight)
                            .clickableWithoutRipple { onTabSelected(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        BottomNavigationEntryIcon(
                            scene = scene,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = scene.bottomNavigationLabel(),
                            color = tint,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableWithoutRipple(onClick: () -> Unit): Modifier =
    then(
        Modifier.pointerInput(onClick) {
            detectTapGestures(
                onTap = { onClick() },
            )
        },
    )
