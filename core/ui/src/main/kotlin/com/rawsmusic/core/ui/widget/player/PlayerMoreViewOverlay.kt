package com.rawsmusic.core.ui.widget.player

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime

private const val PLAYER_MORE_ENTER_MS = 300
private const val PLAYER_MORE_EXIT_MS = 250
private const val PLAYER_MORE_MAX_HEIGHT_DP = 600
private const val PLAYER_MORE_OUTSIDE_MARGIN_DP = 12
private const val PLAYER_MORE_SOURCE_ARTWORK_RADIUS_DP = 28f
private const val PLAYER_MORE_TARGET_ARTWORK_RADIUS_DP = 8f

private data class ArtworkTransitionSnapshot(
    val bitmap: Bitmap?,
    val source: Rect?,
    val target: Rect?
)

/**
 * A player-only, same-root replacement for Miuix's DialogLayout host.
 *
 * Miuix's visual values are intentionally retained, but the card is composed in the player's
 * existing root. That makes the artwork a real view-layer transition instead of two independent
 * windows that happen to contain the same bitmap.
 */
@Composable
internal fun PlayerMoreViewOverlay(
    show: Boolean,
    sourceArtworkBounds: Rect?,
    artwork: Bitmap?,
    onDismiss: () -> Unit,
    onMountedChange: (Boolean) -> Unit = {},
    sourceArtworkRadiusDp: Float = PLAYER_MORE_SOURCE_ARTWORK_RADIUS_DP,
    targetArtworkRadiusDp: Float = PLAYER_MORE_TARGET_ARTWORK_RADIUS_DP,
    content: @Composable (artworkAlpha: Float, onArtworkBoundsChanged: (Rect) -> Unit) -> Unit
) {
    var mounted by remember { mutableStateOf(show) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var sourceArtworkSnapshot by remember { mutableStateOf<Rect?>(null) }
    var targetArtworkSnapshot by remember { mutableStateOf<Rect?>(null) }
    var targetLayoutReady by remember { mutableStateOf(false) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var transitionArtwork by remember { mutableStateOf<Bitmap?>(null) }
    var closing by remember { mutableStateOf(false) }
    var dismissIssued by remember { mutableStateOf(false) }
    var exitStartProgress by remember { mutableFloatStateOf(1f) }
    var closeProgressOverride by remember { mutableStateOf<Float?>(null) }
    val progress = remember { Animatable(0f) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestMountedChange by rememberUpdatedState(onMountedChange)
    val runtimeToken = remember { Any() }
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scheme = MiuixTheme.colorScheme
    val backgroundColor = DialogDefaults.backgroundColor()
    val windowHeightPx = with(density) {
        windowInfo.containerDpSize.height.toPx().coerceAtLeast(rootSize.height.toFloat())
    }

    LaunchedEffect(show) {
        if (show) {
            mounted = true
            closing = false
            dismissIssued = false
            exitStartProgress = 1f
            closeProgressOverride = null
            predictiveBackActive = false
            predictiveBackProgress = 0f
            sourceArtworkSnapshot = sourceArtworkBounds
            targetArtworkSnapshot = null
            targetLayoutReady = false
            transitionArtwork = artwork?.takeUnless { it.isRecycled }
            progress.snapTo(0f)
        } else if (mounted) {
            closing = true
            // Predictive-back renders 1 - gestureProgress, while the settled Animatable normally
            // remains at 1. Continue from the exact frame released by the finger instead of
            // snapping back to the fully-open scene and replaying a second exit animation.
            val startProgress = exitStartProgress.coerceIn(0f, 1f)
            progress.snapTo(startProgress)
            // Keep the released frame visible until Animatable has published its first value.
            // Without this one-frame handoff, predictive progress is cleared first and the card
            // briefly renders at progress=1, exposing the complete popup before it starts leaving.
            closeProgressOverride = null
            predictiveBackActive = false
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = PLAYER_MORE_EXIT_MS,
                    easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
                )
            )
            // Hand the source slot back to the player before removing this actor. The two layers
            // are identical at progress=0, so the handoff is visually continuous instead of
            // leaving one frame where neither layer owns the artwork.
            latestMountedChange(false)
            withFrameNanos { }
            mounted = false
            sourceArtworkSnapshot = null
            targetArtworkSnapshot = null
            targetLayoutReady = false
            transitionArtwork = null
            closing = false
        }
    }

    // Submit the entrance scene only after dialog_frame has a stable layout. Keep the
    // same ordering so the artwork actor never interpolates toward a pre-layout rectangle.
    LaunchedEffect(show, targetLayoutReady) {
        if (show && mounted && !closing && targetLayoutReady) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PLAYER_MORE_ENTER_MS,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
            )
        }
    }

    LaunchedEffect(show, sourceArtworkBounds) {
        if (show && mounted && sourceArtworkSnapshot == null) {
            sourceArtworkSnapshot = sourceArtworkBounds
        }
    }

    // Keep the parent in the modal state until the reverse scene has actually completed. This
    // prevents the player root from reclaiming the artwork layer halfway through the transition.
    LaunchedEffect(mounted) {
        latestMountedChange(mounted)
    }

    DisposableEffect(mounted, runtimeToken) {
        if (mounted) MiuixOverlayBackRuntime.attach(runtimeToken)
        onDispose { MiuixOverlayBackRuntime.detach(runtimeToken) }
    }

    val requestDismiss: () -> Unit = {
        if (show && !dismissIssued && !closing) {
            dismissIssued = true
            closing = true
            exitStartProgress = if (predictiveBackActive) {
                (1f - predictiveBackProgress).coerceIn(0f, 1f)
            } else {
                progress.value.coerceIn(0f, 1f)
            }
            closeProgressOverride = exitStartProgress
            latestDismiss()
        }
    }

    FullCoverPredictiveBackHandler(
        enabled = mounted && show,
        onProgress = { value ->
            predictiveBackActive = true
            predictiveBackProgress = value.coerceIn(0f, 1f)
        },
        onCancelled = {
            predictiveBackActive = false
            predictiveBackProgress = 0f
        },
        onCompleted = {
            requestDismiss()
            predictiveBackActive = false
        }
    )

    // `show` makes the overlay render immediately on the first opening frame, before the mounted
    // effect has reported to the parent. `closing` keeps the final source frame alive while the
    // parent restores its artwork layer one frame before this actor is removed.
    if (!mounted && !show && !closing) return

    val displayedProgress = closeProgressOverride ?: if (predictiveBackActive) {
        (1f - predictiveBackProgress).coerceIn(0f, 1f)
    } else {
        progress.value.coerceIn(0f, 1f)
    }
    val transitionSnapshot = ArtworkTransitionSnapshot(
        bitmap = transitionArtwork?.takeUnless { it.isRecycled },
        source = sourceArtworkSnapshot?.takeIf { it.isUsable },
        target = targetArtworkSnapshot?.takeIf { it.isUsable }
    )
    val activeArtwork = transitionSnapshot.bitmap
        ?: artwork?.takeUnless { it.isRecycled }
    val source = transitionSnapshot.source ?: sourceArtworkBounds?.takeIf { it.isUsable }
    val target = transitionSnapshot.target
    val artworkActorReady = activeArtwork != null && source != null
    val floatingBounds = if (target != null) {
        interpolateArtworkBounds(source, target, displayedProgress)
    } else {
        source
    }
    // Do not cross-fade two album-art requests. The source actor owns the bitmap until
    // it reaches the target slot, then the target actor takes over in the same frame. Keeping this
    // as a binary hand-off avoids the transparent -> opaque flash on the reverse scene.
    val sceneClosing = closing || !show
    val targetReached = displayedProgress >= 0.999f
    val floatingArtworkAlpha = if (artworkActorReady && (sceneClosing || !targetReached)) 1f else 0f
    val headerArtworkAlpha = if (!artworkActorReady || (!sceneClosing && targetReached)) 1f else 0f
    val cardCornerRadius = with(density) { 32.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .zIndex(30f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    scheme.windowDimming.copy(
                        alpha = scheme.windowDimming.alpha * displayedProgress
                    )
                )
                .pointerInput(Unit) {
                    detectTapGestures { requestDismiss() }
                }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    horizontal = PLAYER_MORE_OUTSIDE_MARGIN_DP.dp,
                    vertical = PLAYER_MORE_OUTSIDE_MARGIN_DP.dp
                )
                .widthIn(max = DialogDefaults.MaxWidth)
                .heightIn(max = PLAYER_MORE_MAX_HEIGHT_DP.dp)
                .graphicsLayer {
                    // Compact-screen Miuix DialogContentLayout uses the full window height as
                    // its entrance travel distance and keeps alpha opaque.
                    translationY = if (targetLayoutReady) {
                        (1f - displayedProgress) * windowHeightPx
                    } else {
                        0f
                    }
                    // Measure the resting layout before making the card visible, just like
                    // the dialog frame does in its layout-change callback.
                    alpha = if (targetLayoutReady) 1f else 0f
                }
                .squircleSurface(
                    color = backgroundColor,
                    cornerRadius = 32.dp
                )
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .pointerInput(Unit) {
                    // Consume taps on the card itself; child controls still receive their own
                    // pointer events, while a blank card area never dismisses the sheet.
                    detectTapGestures { }
                }
        ) {
            content(headerArtworkAlpha) { bounds ->
                if (show && !targetLayoutReady && bounds.isUsable) {
                    targetArtworkSnapshot = bounds
                    targetLayoutReady = true
                }
            }
        }

        if (activeArtwork != null && floatingBounds != null && floatingArtworkAlpha > 0.001f) {
            val bounds = floatingBounds
            val width = with(density) { bounds.width.toDp().coerceAtLeast(1.dp) }
            val height = with(density) { bounds.height.toDp().coerceAtLeast(1.dp) }
            val radius = sourceArtworkRadiusDp.dp +
                (targetArtworkRadiusDp.dp - sourceArtworkRadiusDp.dp) * displayedProgress
            Image(
                bitmap = activeArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt()
                        )
                    }
                    .size(width, height)
                    .clip(RoundedCornerShape(radius))
                    .graphicsLayer {
                        alpha = floatingArtworkAlpha
                    }
                    .zIndex(31f)
            )
        }
    }
}

private fun interpolateArtworkBounds(
    source: Rect?,
    target: Rect?,
    progress: Float
): Rect? {
    val start = source ?: return target
    val end = target ?: start
    val t = progress.coerceIn(0f, 1f)
    return Rect(
        left = start.left + (end.left - start.left) * t,
        top = start.top + (end.top - start.top) * t,
        right = start.right + (end.right - start.right) * t,
        bottom = start.bottom + (end.bottom - start.bottom) * t
    )
}

private val Rect.isUsable: Boolean
    get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width > 1f && height > 1f
