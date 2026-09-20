package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SceneTransitionFrameState
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.referenceStaticForeground
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowChromeBaseColor
import com.rawsmusic.core.ui.widget.utils.InteractiveHighlight
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.rememberVirtualListExactScrollPx
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsSurface
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Persistent owner for the HOME header actions.
 *
 * The reference media UI owns its own button surface and long-press interaction.
 * Keep the same ownership boundary here: the page only publishes state/actions, while the actual
 * glass buttons are rendered after sceneBackdrop so each button can safely sample the complete
 * background + HOME scene without recording itself back into the sampled layer.
 */
@Stable
internal class HomeFloatingHeaderController {
    private var current by mutableStateOf<HomeFloatingHeaderRegistration?>(null)
    private var retained by mutableStateOf<HomeFloatingHeaderRegistration?>(null)

    fun bind(next: HomeFloatingHeaderRegistration) {
        current = next
        retained = next
    }

    fun release(registration: HomeFloatingHeaderRegistration) {
        if (current === registration) current = null
    }

    fun registration(): HomeFloatingHeaderRegistration? = current ?: retained
}

internal class HomeFloatingHeaderRegistration(
    val revealProgress: State<Float>,
    val sourceCoverKey: State<String?>,
    val showSettingsShortcut: State<Boolean>,
    val onMenuClick: State<() -> Unit>,
    val onFlowBackgroundClick: State<() -> Unit>,
    val onSettingsClick: State<() -> Unit>,
)

internal val LocalHomeFloatingHeaderController =
    staticCompositionLocalOf<HomeFloatingHeaderController?> { null }

@Composable
internal fun PublishHomeFloatingHeader(
    sourceCoverKey: String?,
    virtualListState: ComposeVirtualListState?,
    scrollState: ScrollState?,
    showSettingsShortcut: Boolean,
    onMenuClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val controller = LocalHomeFloatingHeaderController.current ?: return
    val density = LocalDensity.current
    val revealDistancePx = with(density) { 56.dp.toPx() }
    val virtualScroll = virtualListState?.viewportScrollOwner?.let { owner ->
        rememberVirtualListExactScrollPx(owner)
    }
    val progress = remember(virtualScroll, scrollState, revealDistancePx) {
        derivedStateOf {
            val scrollPx = virtualScroll?.value ?: scrollState?.value?.toFloat() ?: 0f
            if (revealDistancePx > 0f) {
                (scrollPx / revealDistancePx).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val coverKey = rememberUpdatedState(sourceCoverKey)
    val settingsVisible = rememberUpdatedState(showSettingsShortcut)
    val menuClick = rememberUpdatedState(onMenuClick)
    val flowClick = rememberUpdatedState(onFlowBackgroundClick)
    val settingsClick = rememberUpdatedState(onSettingsClick)
    val registration = remember(progress) {
        HomeFloatingHeaderRegistration(
            revealProgress = progress,
            sourceCoverKey = coverKey,
            showSettingsShortcut = settingsVisible,
            onMenuClick = menuClick,
            onFlowBackgroundClick = flowClick,
            onSettingsClick = settingsClick,
        )
    }

    DisposableEffect(controller, registration) {
        controller.bind(registration)
        onDispose { controller.release(registration) }
    }
}

@Composable
internal fun BoxScope.HomeFloatingHeaderHost(
    controller: HomeFloatingHeaderController,
    currentScene: NavScene,
    transitionFrame: SceneTransitionFrameState,
    backdrop: Backdrop?,
    options: HomeHeaderOptionsState,
) {
    val activeHomeTransition = transitionFrame.active &&
        transitionFrame.fromScene != transitionFrame.toScene &&
        (transitionFrame.fromScene == NavScene.HOME || transitionFrame.toScene == NavScene.HOME)
    // NavigationState publishes the route one composition before SceneTransitionHost publishes the
    // matching transition frame. retained-view implementation keeps the source retained scene layout child attached through
    // that handoff; dropping the retained HOME registration here made the buttons disappear before
    // PivotTransition could ever animate them. Keep the last HOME visual registration and explicitly
    // cover the one-frame route/frame skew on both forward and reverse navigation.
    val routeHandoffFromHome = !transitionFrame.active &&
        currentScene != NavScene.HOME &&
        transitionFrame.fromScene == NavScene.HOME &&
        transitionFrame.toScene == NavScene.HOME
    val routeHandoffToHome = !transitionFrame.active &&
        currentScene == NavScene.HOME &&
        transitionFrame.fromScene == transitionFrame.toScene &&
        transitionFrame.fromScene != NavScene.HOME
    if (
        currentScene != NavScene.HOME &&
        !activeHomeTransition &&
        !routeHandoffFromHome
    ) return
    val registration = controller.registration() ?: return
    val mainButtonsSurface by
        PersonalizationPreferences.libraryBottomButtonsSurface.collectAsState()
    val sceneRole = when {
        routeHandoffFromHome -> LibraryTopChromeSceneRole.EXIT
        routeHandoffToHome -> LibraryTopChromeSceneRole.ENTER
        !activeHomeTransition -> LibraryTopChromeSceneRole.IDLE
        transitionFrame.fromScene == NavScene.HOME -> LibraryTopChromeSceneRole.EXIT
        transitionFrame.toScene == NavScene.HOME -> LibraryTopChromeSceneRole.ENTER
        else -> LibraryTopChromeSceneRole.IDLE
    }
    val sceneProgressProvider = remember(
        transitionFrame,
        routeHandoffFromHome,
        routeHandoffToHome,
    ) {
        {
            if (routeHandoffFromHome || routeHandoffToHome) {
                0f
            } else {
                transitionFrame.progress.coerceIn(0f, 1f)
            }
        }
    }
    val sceneAlphaProvider = remember(sceneProgressProvider, sceneRole) {
        { sceneRole.visibilityAt(sceneProgressProvider()) }
    }
    val sceneScaleProvider = remember(
        transitionFrame,
        sceneProgressProvider,
        sceneRole,
        routeHandoffToHome,
    ) {
        {
            val p = sceneProgressProvider()
            val isBack = transitionFrame.isBack || routeHandoffToHome
            when (sceneRole) {
                LibraryTopChromeSceneRole.IDLE -> 1f
                LibraryTopChromeSceneRole.EXIT -> if (isBack) {
                    1f - 0.5f * p
                } else {
                    1f + 0.5f * p
                }
                LibraryTopChromeSceneRole.ENTER -> if (isBack) {
                    1.5f - 0.5f * p
                } else {
                    0.5f + 0.5f * p
                }
            }
        }
    }
    val windowInfo = LocalWindowInfo.current
    val viewportCenter = Offset(
        x = windowInfo.containerSize.width * 0.5f,
        y = windowInfo.containerSize.height * 0.5f,
    )
    var menuCenterInRoot by remember { mutableStateOf(Offset.Unspecified) }
    var actionsCenterInRoot by remember { mutableStateOf(Offset.Unspecified) }
    val revealProgress = homeHeaderEaseOut(registration.revealProgress.value)
    val featherColor = rememberRawFlowChromeBaseColor(
        sourceCoverKey = registration.sourceCoverKey.value,
        mode = LocalRawFlowMode.current,
        fallback = MiuixTheme.colorScheme.background,
    )
    val topInset = rawStableStatusBarTopPadding()
    val featherSpec = when (options.topFeatherStrength) {
        HomeTopFeatherStrength.Soft -> HomeTopFeatherSpec(
            extraHeight = 50.dp,
            topAlpha = 0.76f,
            holdAlpha = 0.58f,
        )
        HomeTopFeatherStrength.Standard -> HomeTopFeatherSpec(
            extraHeight = 55.dp,
            topAlpha = 0.92f,
            holdAlpha = 0.76f,
        )
        HomeTopFeatherStrength.Strong -> HomeTopFeatherSpec(
            extraHeight = 60.dp,
            topAlpha = 1f,
            holdAlpha = 0.94f,
        )
    }
    val menuInteraction = rememberHomeFloatingInteractionState(
        longPressSpringEnabled = options.floatingLongPressSpringEnabled,
    )
    val flowInteraction = rememberHomeFloatingInteractionState(
        longPressSpringEnabled = options.floatingLongPressSpringEnabled,
    )
    val settingsInteraction = rememberHomeFloatingInteractionState(
        longPressSpringEnabled = options.floatingLongPressSpringEnabled,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (options.topFeatherEnabled) {
            // Deliberately keep this compact. Strong mode fully merges the very top into the
            // current RawFlow/theme background, then falls away quickly below the status area.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topInset + featherSpec.extraHeight)
                    .graphicsLayer { alpha = revealProgress * sceneAlphaProvider() }
                    .background(
                        Brush.verticalGradient(
                            0f to featherColor.copy(alpha = featherSpec.topAlpha),
                            0.30f to featherColor.copy(alpha = featherSpec.holdAlpha),
                            0.56f to featherColor.copy(alpha = featherSpec.holdAlpha * 0.72f),
                            0.78f to featherColor.copy(alpha = featherSpec.holdAlpha * 0.28f),
                            1f to Color.Transparent,
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .rawStableStatusBarsPadding()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                .height(48.dp),
        ) {
            HomeFloatingGlassGroup(
                revealProgress = revealProgress,
                backdrop = backdrop,
                surface = mainButtonsSurface,
                displacementEnabled = options.floatingPressDisplacementEnabled,
                primaryInteraction = menuInteraction,
                shape = CircleShape,
                width = 48.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .onGloballyPositioned { coordinates ->
                        menuCenterInRoot = coordinates.boundsInRoot().center
                    }
                    .graphicsLayer {
                        val scale = sceneScaleProvider().coerceIn(0.5f, 1.5f)
                        alpha = sceneAlphaProvider()
                        if (menuCenterInRoot.x.isFinite() && menuCenterInRoot.y.isFinite()) {
                            translationX = (menuCenterInRoot.x - viewportCenter.x) * (scale - 1f)
                            translationY = (menuCenterInRoot.y - viewportCenter.y) * (scale - 1f)
                        }
                        scaleX = scale
                        scaleY = scale
                        clip = false
                    },
            ) {
                HomeFloatingActionSlot(
                    interaction = menuInteraction,
                    onClick = registration.onMenuClick.value,
                    enabled = !activeHomeTransition,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_home_hamburger),
                        contentDescription = stringResource(R.string.home_header_menu_action),
                        tint = referenceStaticForeground(MiuixTheme.colorScheme.onSurface),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            val showSettings = registration.showSettingsShortcut.value
            HomeFloatingGlassGroup(
                revealProgress = revealProgress,
                backdrop = backdrop,
                surface = mainButtonsSurface,
                displacementEnabled = options.floatingPressDisplacementEnabled,
                primaryInteraction = flowInteraction,
                secondaryInteraction = settingsInteraction.takeIf { showSettings },
                shape = if (showSettings) RoundedCornerShape(24.dp) else CircleShape,
                width = if (showSettings) 96.dp else 48.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .onGloballyPositioned { coordinates ->
                        actionsCenterInRoot = coordinates.boundsInRoot().center
                    }
                    .graphicsLayer {
                        val scale = sceneScaleProvider().coerceIn(0.5f, 1.5f)
                        alpha = sceneAlphaProvider()
                        if (actionsCenterInRoot.x.isFinite() && actionsCenterInRoot.y.isFinite()) {
                            translationX = (actionsCenterInRoot.x - viewportCenter.x) * (scale - 1f)
                            translationY = (actionsCenterInRoot.y - viewportCenter.y) * (scale - 1f)
                        }
                        scaleX = scale
                        scaleY = scale
                        clip = false
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeFloatingActionSlot(
                        interaction = flowInteraction,
                        onClick = registration.onFlowBackgroundClick.value,
                        enabled = !activeHomeTransition,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_palette),
                            contentDescription = stringResource(R.string.flow_background_action),
                            tint = referenceStaticForeground(MiuixTheme.colorScheme.onSurface),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    if (showSettings) {
                        HomeFloatingActionSlot(
                            interaction = settingsInteraction,
                            onClick = registration.onSettingsClick.value,
                            enabled = !activeHomeTransition,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Settings,
                                contentDescription = stringResource(R.string.bottom_nav_settings),
                                tint = referenceStaticForeground(MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberHomeFloatingInteractionState(
    longPressSpringEnabled: Boolean,
): HomeFloatingInteractionState {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val viewConfiguration = LocalViewConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    var longPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, longPressSpringEnabled, viewConfiguration.longPressTimeoutMillis) {
        if (!pressed || !longPressSpringEnabled) {
            longPressed = false
            return@LaunchedEffect
        }
        delay(viewConfiguration.longPressTimeoutMillis)
        longPressed = true
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val longPressProgress by animateFloatAsState(
        targetValue = if (longPressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 300f,
            visibilityThreshold = 0.001f,
        ),
        label = "home_header_symbol_long_press",
    )
    return HomeFloatingInteractionState(
        interactionSource = interactionSource,
        highlight = highlight,
        longPressProgress = longPressProgress,
    )
}

private data class HomeFloatingInteractionState(
    val interactionSource: MutableInteractionSource,
    val highlight: InteractiveHighlight,
    val longPressProgress: Float,
)

private data class HomeTopFeatherSpec(
    val extraHeight: androidx.compose.ui.unit.Dp,
    val topAlpha: Float,
    val holdAlpha: Float,
)

@Composable
private fun HomeFloatingGlassGroup(
    revealProgress: Float,
    backdrop: Backdrop?,
    surface: LibraryBottomButtonsSurface,
    displacementEnabled: Boolean,
    primaryInteraction: HomeFloatingInteractionState,
    secondaryInteraction: HomeFloatingInteractionState? = null,
    shape: Shape,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width, 48.dp)
            .graphicsLayer {
                clip = false
                val primaryTouch = primaryInteraction.highlight.pressProgress
                val secondaryTouch = secondaryInteraction?.highlight?.pressProgress ?: 0f
                val activeInteraction = if (secondaryTouch > primaryTouch) {
                    secondaryInteraction ?: primaryInteraction
                } else {
                    primaryInteraction
                }
                val touchProgress = maxOf(primaryTouch, secondaryTouch).coerceIn(0f, 1f)
                val longProgress = maxOf(
                    primaryInteraction.longPressProgress,
                    secondaryInteraction?.longPressProgress ?: 0f,
                ).coerceIn(0f, 1f)
                val height = size.height.coerceAtLeast(1f)
                val widthPx = size.width.coerceAtLeast(1f)
                val minDimension = size.minDimension.coerceAtLeast(1f)
                val maxDimension = size.maxDimension.coerceAtLeast(1f)
                val grow = 1f + 4.dp.toPx() / height * longProgress
                scaleX = grow
                scaleY = grow
                translationX = 0f
                translationY = 0f

                if (displacementEnabled && touchProgress > 0f) {
                    val offset = activeInteraction.highlight.offset
                    val maxDragScale = 4.dp.toPx() / height
                    val offsetAngle = atan2(offset.y, offset.x)
                    translationX = minDimension * tanh(0.05f * offset.x / minDimension)
                    translationY = minDimension * tanh(0.05f * offset.y / minDimension)
                    scaleX += maxDragScale * abs(cos(offsetAngle) * offset.x / maxDimension) *
                        (widthPx / height).fastCoerceAtMost(1f) * touchProgress
                    scaleY += maxDragScale * abs(sin(offsetAngle) * offset.y / maxDimension) *
                        (height / widthPx).fastCoerceAtMost(1f) * touchProgress
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        HomeFloatingGlassMaterial(
            revealProgress = revealProgress,
            interactionProgress = maxOf(
                primaryInteraction.highlight.pressProgress,
                secondaryInteraction?.highlight?.pressProgress ?: 0f,
                primaryInteraction.longPressProgress,
                secondaryInteraction?.longPressProgress ?: 0f,
            ).coerceIn(0f, 1f),
            backdrop = backdrop,
            surface = surface,
            shape = shape,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}

@Composable
private fun HomeFloatingActionSlot(
    interaction: HomeFloatingInteractionState,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(interaction.highlight.gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun HomeFloatingGlassMaterial(
    revealProgress: Float,
    interactionProgress: Float,
    backdrop: Backdrop?,
    surface: LibraryBottomButtonsSurface,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    // The HOME top endpoint deliberately has revealProgress == 0 while the
    // action icons themselves remain visible. Material selection must remain
    // visible there as well; otherwise SOLID/ACRYLIC/LIQUID_GLASS all look
    // identical until the user scrolls the page.
    val alpha = 1f
    val surfaceColor = MiuixTheme.colorScheme.surface
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()

    if (surface == LibraryBottomButtonsSurface.SOLID) {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha }
                .background(surfaceColor.copy(alpha = 0.92f), shape)
        )
        return
    }

    if (backdrop != null && homeHeaderBackdropRuntimeSupported) {
        when (surface) {
            LibraryBottomButtonsSurface.SOLID -> Unit
            LibraryBottomButtonsSurface.FROSTED -> Box(
                modifier = modifier
                    .graphicsLayer { this.alpha = alpha }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy(0.7f * glassSettings.vibrancyStrength)
                            blur((glassSettings.blurRadiusDp * 1.45f).dp.toPx())
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = ((0.20f + 0.18f * interactionProgress) *
                                    glassSettings.highlightStrength).coerceIn(0f, 0.7f)
                            )
                        },
                        shadow = null,
                        onDrawSurface = {
                            drawRect(
                                surfaceColor.copy(alpha = 0.52f + 0.08f * interactionProgress)
                            )
                        },
                    )
            )
            LibraryBottomButtonsSurface.LIQUID_GLASS -> Box(
                modifier = modifier
                    .graphicsLayer { this.alpha = alpha }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            val minDimension = size.minDimension
                            val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f).coerceAtMost(8f)
                            vibrancy(glassSettings.vibrancyStrength)
                            blur(liquidBlurDp.dp.toPx())
                            lens(
                                refractionHeight =
                                    glassSettings.refractionHeightFraction * minDimension * 0.35f,
                                refractionAmount =
                                    glassSettings.refractionAmountFraction * minDimension * 1.35f,
                                depthEffect = true,
                                chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = ((0.34f + 0.22f * interactionProgress) *
                                    glassSettings.highlightStrength).coerceIn(0f, 1f)
                            )
                        },
                        shadow = null,
                        onDrawSurface = {
                            drawRect(
                                surfaceColor.copy(alpha = 0.08f + 0.04f * interactionProgress)
                            )
                        },
                    )
            )
        }
    } else {
        val fallbackAlpha = when (surface) {
            LibraryBottomButtonsSurface.SOLID -> 0.92f
            LibraryBottomButtonsSurface.FROSTED -> 0.62f
            LibraryBottomButtonsSurface.LIQUID_GLASS -> 0.34f
        }
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha }
                .background(surfaceColor.copy(alpha = fallbackAlpha), shape)
        )
    }
}

private val homeHeaderBackdropRuntimeSupported: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        false
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            android.graphics.RuntimeShader("half4 main(float2 c) { return half4(1.0); }")
            true
        } catch (_: Throwable) {
            false
        }
    } else {
        true
    }
}

private fun homeHeaderEaseOut(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return 1f - (1f - p) * (1f - p)
}
