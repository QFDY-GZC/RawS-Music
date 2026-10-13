package com.rawsmusic.core.ui.widget.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.ui.scene.LocalAppBackdrop
import com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PLAYER_MORE_TARGET_ARTWORK_RADIUS_DP = 8f
private const val PLAYER_MORE_DIM_ENTER_MS = 300
private const val PLAYER_MORE_DIM_EXIT_MS = 250
private const val PLAYER_MORE_EXIT_MS = 260
private const val PLAYER_MORE_MAX_HEIGHT_DP = 600
private const val PLAYER_MORE_OUTSIDE_MARGIN_DP = 12

internal fun playerPopupTargetHeightPx(viewportHeightPx: Float, preferredHeightPx: Float?): Float {
    val availableHeight = (viewportHeightPx * 0.74f).coerceAtLeast(1f)
    return preferredHeightPx?.coerceAtLeast(1f)?.coerceAtMost(availableHeight) ?: availableHeight
}

private data class ArtworkTransitionSnapshot(
    val bitmap: Bitmap?,
    val source: Rect?,
    val target: Rect?,
)

private typealias PlayerPopupPortalContent = @Composable () -> Unit

private val LocalPlayerPopupPortal = staticCompositionLocalOf<
    ((PlayerPopupPortalContent?) -> Unit)?
> { null }

@Composable
internal fun PlayerPopupPortal(
    active: Boolean,
    content: PlayerPopupPortalContent,
) {
    val portal = LocalPlayerPopupPortal.current
    val latestContent = rememberUpdatedState(content)
    if (!active) return
    if (portal == null) {
        content()
        return
    }
    DisposableEffect(portal) {
        portal { latestContent.value() }
        onDispose { portal(null) }
    }
}

/**
 * Shared-element target thumbnail for the More sheet.
 *
 * The transition source published by PlayerMainPage is the *visible* Fit rect, not its square
 * envelope. Report the same visible aspect rect here so source/target interpolation never changes
 * bitmap aspect ratio. The previous implementation reported the whole 68/76dp square and then
 * rendered the actor with Crop, which made non-square artwork visibly jump/crop on the first frame.
 */
@Composable
internal fun PlayerMoreArtworkThumbnail(
    size: Dp,
    cornerRadiusDp: Float,
    artwork: Bitmap?,
    coverPath: String?,
    artworkAlpha: Float,
    onArtworkBoundsChanged: (Rect) -> Unit,
) {
    val activeArtwork = artwork?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (activeArtwork != null) {
            val aspect = activeArtwork.width.toFloat() / activeArtwork.height.toFloat()
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val visibleModifier = if (aspect >= 1f) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .align(Alignment.Center)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(aspect)
                        .align(Alignment.Center)
                }
                Box(
                    modifier = visibleModifier
                        .clip(RoundedCornerShape(cornerRadiusDp.dp))
                        .onGloballyPositioned { coordinates ->
                            onArtworkBoundsChanged(coordinates.boundsInRoot())
                        }
                        .graphicsLayer { alpha = artworkAlpha.coerceIn(0f, 1f) },
                ) {
                    Image(
                        bitmap = activeArtwork.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadiusDp.dp))
                    .onGloballyPositioned { coordinates ->
                        onArtworkBoundsChanged(coordinates.boundsInRoot())
                    }
                    .graphicsLayer { alpha = artworkAlpha.coerceIn(0f, 1f) },
            ) {
                if (!coverPath.isNullOrBlank()) {
                    BitmapImage(
                        key = coverPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        targetWidth = 220,
                        targetHeight = 220,
                        surface = ArtworkSurface.Playback,
                        contentScale = ContentScale.Fit,
                        fadeInMillis = 0,
                        holdPreviousOnKeyChange = true,
                        fadeOnBitmapChange = false,
                    )
                }
            }
        }
    }
}

/**
 * Shared player-domain popup copied from the landscape/fullscreen More geometry.
 *
 * The panel itself keeps the two independent spring axes and glass backdrop from
 * LandscapePlayerActivity. Shared artwork uses only clamped geometry progress, so it still moves
 * from the player artwork to the header but never inherits spring overshoot/inertia.
 */
@Composable
internal fun PlayerAnchoredGlassPopup(
    show: Boolean,
    anchorBounds: Rect?,
    sourceArtworkBounds: Rect?,
    artwork: Bitmap?,
    onDismiss: () -> Unit,
    onReopen: () -> Unit = {},
    onMountedChange: (Boolean) -> Unit = {},
    sourceArtworkRadiusDp: Float = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP,
    targetArtworkRadiusDp: Float = PLAYER_MORE_TARGET_ARTWORK_RADIUS_DP,
    preferredHeight: Dp? = null,
    targetBottomFraction: Float = 0.87f,
    fallbackTint: Color = Color.Unspecified,
    layerZ: Float = 30f,
    content: @Composable (artworkAlpha: Float, onArtworkBoundsChanged: (Rect) -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val backdrop = LocalAppBackdrop.current
    val viewportWidthPx = with(density) { windowInfo.containerDpSize.width.toPx() }.coerceAtLeast(1f)
    val viewportHeightPx = with(density) { windowInfo.containerDpSize.height.toPx() }.coerceAtLeast(1f)
    val fallbackSizePx = with(density) { 44.dp.toPx() }
    val fallbackInsetPx = with(density) { 12.dp.toPx() }
    val anchor = anchorBounds?.takeIf { it.isUsable } ?: Rect(
        left = viewportWidthPx - fallbackInsetPx - fallbackSizePx,
        top = fallbackInsetPx,
        right = viewportWidthPx - fallbackInsetPx,
        bottom = fallbackInsetPx + fallbackSizePx,
    )

    var mounted by remember { mutableStateOf(show) }
    var targetArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveExitStart by remember { mutableStateOf<Float?>(null) }
    val progressX = remember { Animatable(if (show) 1f else 0f) }
    val progressY = remember { Animatable(if (show) 1f else 0f) }
    val popupScope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestReopen by rememberUpdatedState(onReopen)
    val latestMountedChange by rememberUpdatedState(onMountedChange)
    val runtimeToken = remember { Any() }

    LaunchedEffect(show, anchor, viewportWidthPx, viewportHeightPx) {
        if (show) {
            val reversingExit = mounted
            mounted = true
            latestMountedChange(true)
            predictiveBackActive = false
            predictiveBackProgress = 0f
            predictiveExitStart = null
            if (!reversingExit) targetArtworkBounds = null
            if (progressX.value <= 0.001f) progressX.snapTo(0f)
            if (progressY.value <= 0.001f) progressY.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch {
                    progressX.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 750,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                        ),
                    )
                }
                launch {
                    progressY.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 750,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                        ),
                    )
                }
            }
        } else if (mounted) {
            predictiveExitStart?.let { start ->
                val retained = start.coerceIn(0f, 1f)
                progressX.snapTo(retained)
                progressY.snapTo(retained)
                predictiveBackActive = false
                predictiveBackProgress = 0f
                predictiveExitStart = null
            }
            kotlinx.coroutines.coroutineScope {
                launch {
                    progressX.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 750,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                        ),
                    )
                }
                launch {
                    progressY.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 750,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                        ),
                    )
                }
            }
            latestMountedChange(false)
            withFrameNanos { }
            mounted = false
            targetArtworkBounds = null
        }
    }

    DisposableEffect(mounted, runtimeToken) {
        if (mounted) MiuixOverlayBackRuntime.attach(runtimeToken)
        onDispose { MiuixOverlayBackRuntime.detach(runtimeToken) }
    }

    if (!mounted) return

    FullCoverPredictiveBackHandler(
        enabled = show,
        onProgress = { value ->
            predictiveBackActive = true
            predictiveBackProgress = value.coerceIn(0f, 1f)
        },
        onCancelled = {
            val released = (1f - predictiveBackProgress).coerceIn(0f, 1f)
            popupScope.launch {
                progressX.snapTo(released)
                progressY.snapTo(released)
                predictiveBackActive = false
                predictiveBackProgress = 0f
                kotlinx.coroutines.coroutineScope {
                    launch {
                        progressX.animateTo(
                            1f,
                            tween(750, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
                        )
                    }
                    launch {
                        progressY.animateTo(
                            1f,
                            tween(750, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
                        )
                    }
                }
            }
        },
        onCompleted = { wasPredictive ->
            if (wasPredictive) {
                predictiveExitStart = (1f - predictiveBackProgress).coerceIn(0f, 1f)
            }
            latestDismiss()
        },
    )

    val wideAnchoredLayout = windowInfo.containerDpSize.width >= 700.dp &&
        windowInfo.containerDpSize.height >= 480.dp
    val halfWidthPx = viewportWidthPx * 0.5f
    val useLeftSide = anchor.center.x < halfWidthPx
    val sideStartPx = if (wideAnchoredLayout) {
        if (useLeftSide) 0f else halfWidthPx
    } else {
        0f
    }
    val sideWidthPx = if (wideAnchoredLayout) halfWidthPx else viewportWidthPx
    val sideEndPx = sideStartPx + sideWidthPx
    val horizontalInsetPx = with(density) { 6.dp.toPx() }
    val targetLeftPx = sideStartPx + horizontalInsetPx
    val targetRightPx = sideEndPx - horizontalInsetPx
    val targetBottomPx = viewportHeightPx * targetBottomFraction.coerceIn(0.5f, 0.95f)
    val targetWidthPx = (targetRightPx - targetLeftPx).coerceAtLeast(1f)
    val targetHeightPx = playerPopupTargetHeightPx(
        viewportHeightPx,
        preferredHeight?.let { with(density) { it.toPx() } },
    )
    val targetTopPx = targetBottomPx - targetHeightPx
    val targetWidthDp = with(density) { targetWidthPx.toDp() }
    val targetHeightDp = with(density) { targetHeightPx.toDp() }
    val sideWidthDp = with(density) { sideWidthPx.toDp() }

    val predictiveProgress = if (predictiveBackActive) {
        (1f - predictiveBackProgress).coerceIn(0f, 1f)
    } else {
        null
    }
    val rawProgressX = predictiveProgress ?: progressX.value
    val rawProgressY = predictiveProgress ?: progressY.value
    val geometryProgressX = rawProgressX.coerceIn(0f, 1f)
    val geometryProgressY = rawProgressY.coerceIn(0f, 1f)
    val startScaleX = (anchor.width / targetWidthPx).coerceIn(0.01f, 1f)
    val startScaleY = (anchor.height / targetHeightPx).coerceIn(0.01f, 1f)
    val morphScaleX = lerpPlayerPopup(startScaleX, 1f, geometryProgressX)
    val morphScaleY = lerpPlayerPopup(startScaleY, 1f, geometryProgressY)
    val startTranslationX = if (useLeftSide) {
        anchor.left - targetLeftPx
    } else {
        anchor.right - targetRightPx
    }
    val morphTranslationX = lerpPlayerPopup(startTranslationX, 0f, geometryProgressX)
    val morphTranslationY = lerpPlayerPopup(
        anchor.top - targetTopPx,
        0f,
        geometryProgressY,
    )
    val elasticOvershootX = (rawProgressX - 1f).coerceIn(0f, 0.12f)
    val elasticOvershootY = (rawProgressY - 1f).coerceIn(0f, 0.12f)
    val elasticScaleX = 1f + elasticOvershootX * 0.42f
    val elasticScaleY = 1f + elasticOvershootY * 0.34f
    val contentProgress = minOf(geometryProgressX, geometryProgressY)
    val contentAlpha = ((contentProgress - 0.24f) / 0.34f).coerceIn(0f, 1f)
    val scrimAlpha = (contentProgress * 0.14f).coerceIn(0f, 0.14f)
    val shellAlpha = if (show) {
        ((contentProgress - 0.035f) / 0.12f).coerceIn(0f, 1f)
    } else {
        ((contentProgress - 0.018f) / 0.08f).coerceIn(0f, 1f)
    }
    val panelShape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val activeArtwork = artwork?.takeUnless { it.isRecycled }
    val sourceArtwork = sourceArtworkBounds?.takeIf { it.isUsable }
    val targetArtwork = targetArtworkBounds?.takeIf { it.isUsable }
    val floatingArtworkActive = activeArtwork != null && sourceArtwork != null
    val headerArtworkAlpha = if (floatingArtworkActive) 0f else 1f
    var portalContent by remember { mutableStateOf<PlayerPopupPortalContent?>(null) }
    val portalSetter = remember {
        { next: PlayerPopupPortalContent? -> portalContent = next }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(layerZ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(show) {
                    detectTapGestures(onTap = { if (show) latestDismiss() })
                },
        )

        val resolvedTint = fallbackTint.takeUnless { it == Color.Unspecified }
            ?: MiuixTheme.colorScheme.surfaceContainerHigh
        val tintedSurface = Modifier.background(
            brush = Brush.verticalGradient(
                listOf(
                    resolvedTint.copy(alpha = 0.16f),
                    resolvedTint.copy(alpha = 0.10f),
                ),
            ),
            shape = panelShape,
        )
        val glassModifier = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdrop != null
        ) {
            tintedSurface.drawBackdrop(
                backdrop = backdrop,
                shape = { panelShape },
                effects = {
                    blur(52.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(alpha = 0.10f)
                },
                shadow = {
                    Shadow(
                        radius = 14.dp,
                        color = Color.Black.copy(alpha = 0.24f),
                    )
                },
                onDrawSurface = {
                    drawRect(resolvedTint.copy(alpha = 0.12f))
                },
            )
        } else {
            tintedSurface
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(sideStartPx.roundToInt(), 0) }
                .width(sideWidthDp)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (targetLeftPx - sideStartPx).roundToInt(),
                            targetTopPx.roundToInt(),
                        )
                    }
                    .width(targetWidthDp)
                    .height(targetHeightDp)
                    .graphicsLayer {
                        translationX = morphTranslationX
                        translationY = morphTranslationY
                        scaleX = morphScaleX * elasticScaleX
                        scaleY = morphScaleY * elasticScaleY
                        alpha = shellAlpha
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            pivotFractionX = if (useLeftSide) 0f else 1f,
                            pivotFractionY = 0f,
                        )
                    }
                    .clip(panelShape)
                    .then(glassModifier)
                    // The panel owns blank/header taps; only the separate outside scrim dismisses.
                    // Child buttons and scrolling still get first refusal of their own input.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    CompositionLocalProvider(LocalPlayerPopupPortal provides portalSetter) {
                        content(headerArtworkAlpha) { bounds ->
                            if (bounds.isUsable) targetArtworkBounds = bounds
                        }
                    }
                    if (!show || contentProgress < 0.985f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                        )
                    }
                }
            }
        }

        if (activeArtwork != null && sourceArtwork != null && floatingArtworkActive) {
            Image(
                bitmap = activeArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        val bounds = interpolatePlayerSharedArtworkBounds(
                            source = sourceArtwork,
                            target = targetArtwork,
                            progress = contentProgress,
                        )
                        IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
                    }
                    .layout { measurable, _ ->
                        val bounds = interpolatePlayerSharedArtworkBounds(
                            source = sourceArtwork,
                            target = targetArtwork,
                            progress = contentProgress,
                        )
                        val width = bounds.width.roundToInt().coerceAtLeast(1)
                        val height = bounds.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val radiusDp = sourceArtworkRadiusDp +
                            (targetArtworkRadiusDp - sourceArtworkRadiusDp) * contentProgress
                        shape = RoundedCornerShape(radiusDp.dp)
                        clip = true
                    }
                    .zIndex(layerZ + 1f),
            )
        }

        portalContent?.invoke()

        // The source button stays tappable throughout either direction of the morph. Reversing
        // uses the current animated fraction instead of waiting for the previous exit to unmount.
        Box(
            modifier = Modifier
                .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                .width(with(density) { anchor.width.toDp() })
                .height(with(density) { anchor.height.toDp() })
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { if (show) latestDismiss() else latestReopen() },
        )
    }
}

private fun lerpPlayerPopup(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)

private fun interpolatePlayerSharedArtworkBounds(
    source: Rect,
    target: Rect?,
    progress: Float,
): Rect {
    val end = target ?: source
    val t = progress.coerceIn(0f, 1f)
    return Rect(
        left = source.left + (end.left - source.left) * t,
        top = source.top + (end.top - source.top) * t,
        right = source.right + (end.right - source.right) * t,
        bottom = source.bottom + (end.bottom - source.bottom) * t,
    )
}

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
    sourceArtworkRadiusDp: Float = STANDARD_PLAYER_ARTWORK_CORNER_RADIUS_DP,
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
    var entranceComplete by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val dimProgress = remember { Animatable(0f) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestMountedChange by rememberUpdatedState(onMountedChange)
    val runtimeToken = remember { Any() }
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scheme = MiuixTheme.colorScheme
    val backgroundColor = DialogDefaults.backgroundColor()
    val isLargeScreen = windowInfo.containerDpSize.height >= 480.dp &&
        windowInfo.containerDpSize.width >= 840.dp
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
            entranceComplete = false
            transitionArtwork = artwork?.takeUnless { it.isRecycled }
            progress.snapTo(0f)
            dimProgress.snapTo(0f)
        } else if (mounted) {
            closing = true
            entranceComplete = false
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
            launch {
                dimProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = PLAYER_MORE_DIM_EXIT_MS,
                        easing = DecelerateEasing(1.5f),
                    ),
                )
            }
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = PLAYER_MORE_EXIT_MS,
                    easing = DecelerateEasing(1.5f),
                ),
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
            val largeAtStart = isLargeScreen
            progress.snapTo(0f)
            dimProgress.snapTo(0f)
            launch {
                dimProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = PLAYER_MORE_DIM_ENTER_MS,
                        easing = DecelerateEasing(1.5f),
                    ),
                )
            }
            progress.animateTo(
                targetValue = 1f,
                animationSpec = if (largeAtStart) {
                    folmeSpring(damping = 0.9f, response = 0.3f)
                } else {
                    spring(
                        dampingRatio = 0.88f,
                        stiffness = 450f,
                        visibilityThreshold = 0.0001f,
                    )
                },
            )
            if (show && mounted && !closing) entranceComplete = true
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
            entranceComplete = false
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

    val displayedProgressProvider: () -> Float = {
        closeProgressOverride ?: if (predictiveBackActive) {
            (1f - predictiveBackProgress).coerceIn(0f, 1f)
        } else {
            progress.value.coerceIn(0f, 1f)
        }
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
    // The shared-art actor is a retained presentation owner. Only its ownership handoff changes
    // composition; the hundreds of intermediate progress values stay in layout/layer phases.
    // Keep one physical shared-art actor mounted for the complete More-sheet lifetime.
    // Once it reaches the target rect it simply stays there at progress=1. Do not hand the image
    // to a second header owner after entrance; that owner swap was visually perceptible even when
    // both layers referenced the same bitmap.
    val floatingArtworkActive = artworkActorReady && (mounted || show || closing)
    val headerArtworkAlpha = if (floatingArtworkActive) 0f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .zIndex(30f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val predictiveDim = if (predictiveBackActive) {
                        (1f - predictiveBackProgress).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    drawRect(
                        scheme.windowDimming.copy(
                            alpha = scheme.windowDimming.alpha *
                                dimProgress.value.coerceIn(0f, 1f) * predictiveDim
                        )
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { requestDismiss() }
                }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .rawStableNavigationBarsPadding()
                .padding(
                    horizontal = PLAYER_MORE_OUTSIDE_MARGIN_DP.dp,
                    vertical = PLAYER_MORE_OUTSIDE_MARGIN_DP.dp
                )
                .widthIn(max = DialogDefaults.MaxWidth)
                .heightIn(max = PLAYER_MORE_MAX_HEIGHT_DP.dp)
                .graphicsLayer {
                    val p = displayedProgressProvider().coerceIn(0f, 1f)
                    if (isLargeScreen) {
                        val scale = 0.8f + 0.2f * p
                        scaleX = scale
                        scaleY = scale
                        translationY = 0f
                        alpha = if (targetLayoutReady) p else 0f
                    } else {
                        scaleX = 1f
                        scaleY = 1f
                        translationY = if (targetLayoutReady) {
                            (1f - p) * windowHeightPx
                        } else {
                            0f
                        }
                        // Measure the resting layout before making the card visible, just like
                        // Miuix DialogContentLayout's layout-change callback.
                        alpha = if (targetLayoutReady) 1f else 0f
                    }
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

        if (activeArtwork != null && source != null && floatingArtworkActive) {
            val stableArtworkBounds = target ?: source
            Image(
                bitmap = activeArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        val bounds = stableArtworkBounds
                        IntOffset(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt()
                        )
                    }
                    .layout { measurable, _ ->
                        val bounds = stableArtworkBounds
                        val width = bounds.width.roundToInt().coerceAtLeast(1)
                        val height = bounds.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        val radiusDp = if (target != null) {
                            targetArtworkRadiusDp
                        } else {
                            sourceArtworkRadiusDp
                        }
                        shape = RoundedCornerShape(radiusDp.dp)
                        clip = true
                        alpha = 1f
                    }
                    .zIndex(31f)
            )
        }
    }
}

private val Rect.isUsable: Boolean
    get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width > 1f && height > 1f
