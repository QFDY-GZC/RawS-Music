package com.rawsmusic.core.ui.widget.bottombar

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.BottomChromeEnvironment
import com.rawsmusic.core.ui.scene.BottomChromeLayoutClass
import com.rawsmusic.core.ui.scene.BottomChromeScrollState
import com.rawsmusic.core.ui.scene.BottomChromeStableMode
import com.rawsmusic.core.ui.scene.BottomNavigationEntryIcon
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.bottomNavigationLabel
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.widget.utils.InteractiveHighlight
import com.rawsmusic.core.ui.widget.flow.darkAlbumGradient
import com.rawsmusic.module.data.prefs.BottomBarMaterial
import com.rawsmusic.module.data.prefs.BottomBarStyle
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.MiniPlayerSecondaryAction
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private const val PLAYER_SHEET_ANCHOR = 0.5f

/**
 * One geometry owner for the iOS 26 glass bottom navigation bottom-chrome contract.
 *
 * Expanded: the MiniPlayer is a bottom accessory above the tab bar.
 * Minimized (compact only): the accessory moves inline with the tab bar; the selected tab becomes
 * a leading compact control and a configured Search tab becomes the trailing compact control.
 * NORMAL/FLOATING owns presentation behavior; surface material is independent and never changes
 * geometry or lifecycle.
 */
@Composable
fun GlassBottomChrome(
    environment: BottomChromeEnvironment,
    scrollState: BottomChromeScrollState,
    bottomBarStyle: BottomBarStyle,
    bottomBarMaterial: BottomBarMaterial,
    backdrop: Backdrop,
    title: String,
    artist: String,
    lyricText: String,
    lyricTranslation: String,
    lyricSong: Song?,
    isPlaying: Boolean,
    progress: Float,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    coverPath: String?,
    currentSong: AudioFile?,
    previousSong: AudioFile?,
    nextSong: AudioFile?,
    queueCurrentIndex: Int,
    queueSize: Int,
    accentColor: Color,
    tabScenes: List<NavScene>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onSearchSelected: () -> Unit,
    isSearchSelected: Boolean,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenQueue: () -> Unit,
    onExpandDragStart: () -> Unit,
    onExpandDragProgress: (Float) -> Unit,
    onExpandDragEnd: (Boolean, Float) -> Unit,
    playerSceneProgressState: State<Float>?,
    artworkVisible: Boolean = true,
    onCoverBoundsChanged: (android.graphics.RectF?) -> Unit,
    onCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    onMiniPlayerBoundsChanged: (Rect?) -> Unit,
    onNavigationBoundsChanged: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!environment.active) return

    val density = LocalDensity.current
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground && (
        MiuixTheme.colorScheme.background.red +
            MiuixTheme.colorScheme.background.green +
            MiuixTheme.colorScheme.background.blue > 1.5f
        )
    val foregroundAccentColor = if (staticForeground) Color.White else accentColor
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val progressEffects by PersonalizationPreferences.miniPlayerProgressEffects.collectAsState()
    val miniPlayerStyle by PersonalizationPreferences.miniPlayerStyle.collectAsState()
    val miniPlayerControls by PersonalizationPreferences.miniPlayerControls.collectAsState()
    val navigationStyle by PersonalizationPreferences.bottomNavigationStyle.collectAsState()
    val flingBounds = remember(context) {
        ViewConfiguration.get(context).let { config ->
            config.scaledMinimumFlingVelocity.toFloat() to config.scaledMaximumFlingVelocity.toFloat()
        }
    }
    val latestExpandStart by rememberUpdatedState(onExpandDragStart)
    val latestExpandProgress by rememberUpdatedState(onExpandDragProgress)
    val latestExpandEnd by rememberUpdatedState(onExpandDragEnd)
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val geometry = environment.geometry
    val compact = environment.layoutClass == BottomChromeLayoutClass.Compact
    // UIKit only minimizes this tab-bar form factor on iPhone/compact layouts.
    val minimizeProgress = if (compact) {
        scrollState.renderVisibilityProgress.coerceIn(0f, 1f)
    } else {
        0f
    }
    // PlayerSceneController updates this clock at display refresh rate. Keep the float out of
    // composition: reference player feeds its transition ratio into retained View/RenderNode properties,
    // it does not rebind the complete bottom-chrome tree for every animation frame.
    val playerProgressProvider = remember(playerSceneProgressState) {
        { playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f }
    }
    // Hit-test/topology ownership only needs the transition edge. derivedStateOf therefore
    // invalidates composition once when PLAYER leaves/returns to the MAIN endpoint, while visual
    // alpha/translation continues to sample the live float from graphicsLayer.
    val playerSheetVisible by remember(playerSceneProgressState) {
        derivedStateOf { playerProgressProvider() > 0.001f }
    }
    val morphProgress = smoothStep(minimizeProgress)
    val selectedIndex = selectedTabIndex.coerceIn(0, tabScenes.lastIndex.coerceAtLeast(0))
    val selectedScene = tabScenes.getOrNull(selectedIndex) ?: tabScenes.firstOrNull()
    // iOS 26 uses a dedicated UISearchTab. Keep Search as the trailing compact control even when
    // Raw's configurable expanded tab list does not contain SEARCH; if Search itself is selected,
    // do not duplicate it as a leading compact control.
    val searchSelected = isSearchSelected || selectedScene == NavScene.SEARCH
    val leadingCompactTabVisible = selectedScene != null && !searchSelected
    val trailingSearchVisible = environment.hasNavigation

    fun settleExpandedAfterSelection() {
        if (compact && minimizeProgress > 0.001f) {
            scope.launch { scrollState.settleTo(BottomChromeStableMode.Expanded) }
        }
    }

    fun selectTabAndExpand(index: Int) {
        onTabSelected(index)
        settleExpandedAfterSelection()
    }

    fun selectSearchAndExpand() {
        onSearchSelected()
        settleExpandedAfterSelection()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(geometry.chromeHeight),
    ) {
        val rootWidth = maxWidth
        // The inline selected/search surfaces and the center MiniPlayer are one visual row.
        // Use the same physical height so MiniPlayer style changes cannot leave stepped edges.
        val compactTabSize = environment.compactAccessoryHeight
        // glass bottom navigation repeatedly uses an 8pt bottom-chrome rhythm. More importantly, this is a
        // geometric clearance, not padding painted inside either glass surface.
        val inlineGap = 8.dp
        val expandedLeadingMargin = miniPlayerStyle.leadingMarginDp.dp
        val expandedTrailingMargin = miniPlayerStyle.trailingMarginDp.dp
        val navigationLeadingMargin = navigationStyle.leadingMarginDp.dp
        val navigationTrailingMargin = navigationStyle.trailingMarginDp.dp
        val expandedHorizontalPadding = expandedLeadingMargin + expandedTrailingMargin
        val expandedMiniWidth = (rootWidth - expandedHorizontalPadding)
            .coerceAtLeast(1.dp)
            .let { if (compact) it else it.coerceAtMost(environment.regularAccessoryMaxWidth) }

        val inlineLeadingReserve = navigationLeadingMargin +
            (if (leadingCompactTabVisible) compactTabSize + inlineGap else 0.dp)
        val inlineTrailingReserve = navigationTrailingMargin +
            (if (trailingSearchVisible) compactTabSize + inlineGap else 0.dp)
        // Do not impose the old 148..248dp Raw clamp here. UIKit gives the accessory the actual
        // remaining environment width and lets its contents collapse/hide controls instead. That
        // also makes the 8pt tab/accessory clearance invariant across compact screen widths.
        val inlineMiniWidth = (rootWidth - inlineLeadingReserve - inlineTrailingReserve)
            .coerceAtLeast(1.dp)
        val miniWidth = if (compact) {
            lerpDp(expandedMiniWidth, inlineMiniWidth, morphProgress)
        } else {
            expandedMiniWidth
        }
        val miniHeight = if (compact) {
            lerpDp(environment.accessoryHeight, environment.compactAccessoryHeight, morphProgress)
        } else {
            environment.accessoryHeight
        }
        val miniBottomInset = if (compact) {
            lerpDp(geometry.miniPlayerBottomInset, geometry.navigationBottomInset, morphProgress)
        } else {
            geometry.miniPlayerBottomInset
        }
        // 16/20 compact optical margins put the expanded center 2pt left. In the inline endpoint,
        // center the MiniPlayer in the *remaining* region so the visible left/right gap is exactly
        // 8pt even though those outer margins are asymmetric.
        val expandedMiniCenterShift = (expandedLeadingMargin - expandedTrailingMargin) / 2f
        val inlineMiniCenterShift = if (compact) {
            (inlineLeadingReserve - inlineTrailingReserve) / 2f
        } else {
            0.dp
        }
        val miniOpticalShiftX = if (compact) {
            lerpDp(expandedMiniCenterShift, inlineMiniCenterShift, morphProgress)
        } else {
            0.dp
        }
        val miniLeft = (rootWidth - miniWidth) / 2f + miniOpticalShiftX
        val miniRight = miniLeft + miniWidth
        val leadingClearance = if (leadingCompactTabVisible) {
            miniLeft - (navigationLeadingMargin + compactTabSize)
        } else {
            inlineGap
        }
        val trailingClearance = if (trailingSearchVisible) {
            (rootWidth - navigationTrailingMargin - compactTabSize) - miniRight
        } else {
            inlineGap
        }
        val minimumClearance = minOf(leadingClearance, trailingClearance)
        // The compact buttons were previously visible while the still-wide accessory passed below
        // them. Do not expose them until there is already physical air between the glass surfaces;
        // then ramp to full opacity over the remaining 4pt until the exact 8pt endpoint.
        val compactControlClearanceProgress = ((minimumClearance.value - 4f) / 4f)
            .coerceIn(0f, 1f)
        val compactControlAlpha = morphProgress * smoothStep(compactControlClearanceProgress)
        val expandedCorner = miniPlayerStyle.cornerRadiusDp.dp
            .coerceAtMost(environment.accessoryHeight / 2f)
        val compactCorner = (miniPlayerStyle.cornerRadiusDp + 5f).dp
            .coerceAtMost(environment.compactAccessoryHeight / 2f)
        val miniCorner = if (compact) lerpDp(expandedCorner, compactCorner, morphProgress) else expandedCorner
        val miniShape = miniPlayerSurfaceShape(miniCorner, miniPlayerStyle.cornerCurve)
        val navigationCorner = navigationStyle.cornerRadiusDp.dp
            .coerceAtMost(environment.navigationHeight / 2f)
        val navigationShape = miniPlayerSurfaceShape(navigationCorner, navigationStyle.cornerCurve)
        val compactNavigationCorner = navigationStyle.cornerRadiusDp.dp
            .coerceAtMost(compactTabSize / 2f)
        val compactNavigationShape = miniPlayerSurfaceShape(
            compactNavigationCorner,
            navigationStyle.cornerCurve,
        )
        val mediaControlVisibility = ((0.72f - morphProgress) / 0.56f).coerceIn(0f, 1f)

        val expandedHorizontalModifier = Modifier
            .padding(start = navigationLeadingMargin, end = navigationTrailingMargin)
            .let { base ->
                if (compact) base.fillMaxWidth()
                else base.widthIn(max = environment.regularAccessoryMaxWidth).fillMaxWidth()
            }

        if (environment.hasNavigation && tabScenes.isNotEmpty()) {
            GlassNavigationSurface(
                bottomBarStyle = bottomBarStyle,
                bottomBarMaterial = bottomBarMaterial,
                shape = navigationShape,
                backdrop = backdrop,
                accentColor = accentColor,
                tabScenes = tabScenes,
                selectedTabIndex = selectedIndex,
                onTabSelected = ::selectTabAndExpand,
                minimizeProgress = morphProgress,
                playerProgressProvider = playerProgressProvider,
                playerSheetVisible = playerSheetVisible,
                surfaceAlpha = 1f - morphProgress,
                isLight = isLight,
                performanceMode = performanceMode,
                modifier = expandedHorizontalModifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = geometry.navigationBottomInset)
                    .offset {
                        IntOffset(
                            0,
                            with(density) {
                                (geometry.navigationMinimizeTravel * morphProgress).toPx().roundToInt()
                            },
                        )
                    }
                    .onGloballyPositioned { coordinates ->
                        // Keep the full navigation bounds as the content-obstruction endpoint. The
                        // compact pills are transient controls and must not feed a smaller inset back
                        // into the page while the same scroll gesture is still running.
                        onNavigationBoundsChanged(coordinates.boundsInRoot())
                    },
                height = environment.navigationHeight,
            )

            if (compact && morphProgress > 0.001f) {
                val compactAlpha = compactControlAlpha
                if (leadingCompactTabVisible) GlassCompactTabButton(
                    scene = selectedScene ?: tabScenes.first(),
                    selected = true,
                    alpha = compactAlpha,
                    playerProgressProvider = playerProgressProvider,
                    bottomBarStyle = bottomBarStyle,
                    bottomBarMaterial = bottomBarMaterial,
                    shape = compactNavigationShape,
                    backdrop = backdrop,
                    accentColor = accentColor,
                    isLight = isLight,
                    performanceMode = performanceMode,
                    onClick = { selectTabAndExpand(selectedIndex) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = navigationLeadingMargin,
                            bottom = geometry.navigationBottomInset,
                        )
                        .size(compactTabSize),
                )
                if (trailingSearchVisible) {
                    GlassCompactTabButton(
                        scene = NavScene.SEARCH,
                        selected = searchSelected,
                        alpha = compactAlpha,
                        playerProgressProvider = playerProgressProvider,
                        bottomBarStyle = bottomBarStyle,
                        bottomBarMaterial = bottomBarMaterial,
                        shape = compactNavigationShape,
                        backdrop = backdrop,
                        accentColor = accentColor,
                        isLight = isLight,
                        performanceMode = performanceMode,
                        onClick = ::selectSearchAndExpand,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = navigationTrailingMargin,
                                bottom = geometry.navigationBottomInset,
                            )
                            .size(compactTabSize),
                    )
                }
            }
        }

        if (environment.hasMiniPlayer) {
            val miniGestureModifier = Modifier.pointerInput(windowInfo.containerSize.width, miniWidth) {
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { position ->
                        dragProgress = playerSceneProgressState?.value?.coerceIn(0f, 1f) ?: 0f
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(android.os.SystemClock.uptimeMillis(), position)
                        latestExpandStart()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        // The measured accessory top is the exact sheet travel owned by the host.
                        val travel = (miniBounds?.top ?: size.height.toFloat()).coerceAtLeast(1f)
                        dragProgress = (dragProgress - dragAmount / travel).coerceIn(0f, 1f)
                        latestExpandProgress(dragProgress)
                        change.consume()
                    },
                    onDragEnd = {
                        val rawVelocity = velocityTracker.calculateVelocity().y
                        val velocity = when {
                            abs(rawVelocity) < flingBounds.first -> 0f
                            rawVelocity > flingBounds.second -> flingBounds.second
                            rawVelocity < -flingBounds.second -> -flingBounds.second
                            else -> rawVelocity
                        }
                        val shouldOpen = when {
                            velocity < 0f -> true
                            velocity > 0f -> false
                            else -> dragProgress >= PLAYER_SHEET_ANCHOR
                        }
                        latestExpandEnd(
                            shouldOpen,
                            velocity / windowInfo.containerSize.width.toFloat().coerceAtLeast(1f),
                        )
                    },
                    onDragCancel = {
                        latestExpandEnd(dragProgress >= PLAYER_SHEET_ANCHOR, 0f)
                    },
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = miniOpticalShiftX)
                    .padding(bottom = miniBottomInset)
                    .width(miniWidth)
                    .height(miniHeight)
                    .graphicsLayer {
                        // PLAYER and bottom chrome share the controller's single transition clock.
                        val playerProgress = playerProgressProvider()
                        translationY = with(density) { 18.dp.toPx() } * playerProgress
                        alpha = (1f - playerProgress * 1.35f).coerceIn(0f, 1f)
                        val scale = 1f - 0.018f * playerProgress
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(miniGestureModifier)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        miniBounds = bounds
                        onMiniPlayerBoundsChanged(bounds)
                    }
                    .then(
                        Modifier.glassSurfaceModifier(
                            material = bottomBarMaterial,
                            backdrop = backdrop,
                            shape = miniShape,
                            accentColor = accentColor,
                            isLight = isLight,
                            performanceMode = performanceMode,
                        )
                    ),
            ) {
                ComposeMiniPlayer(
                    title = title,
                    artist = artist,
                    lyricText = lyricText,
                    lyricTranslation = lyricTranslation,
                    lyricSong = lyricSong,
                    isPlaying = isPlaying,
                    progress = progress,
                    playbackPositionMs = playbackPositionMs,
                    playbackDurationMs = playbackDurationMs,
                    coverPath = coverPath,
                    currentSong = currentSong,
                    previousSong = previousSong,
                    nextSong = nextSong,
                    queueCurrentIndex = queueCurrentIndex,
                    queueSize = queueSize,
                    artworkVisible = artworkVisible,
                    animateArtwork = false,
                    backdrop = backdrop,
                    drawBackground = false,
                    drawOuterProgress = false,
                    drawInlineProgress = true,
                    inlineProgressColor = foregroundAccentColor,
                    inlineProgressBubbles = !performanceMode && progressEffects.bubbleCount > 0,
                    inlineProgressDirection = progressEffects.direction,
                    inlineProgressBubbleCount = if (performanceMode) 0 else progressEffects.bubbleCount,
                    inlineProgressBubbleMotion = progressEffects.bubbleMotion,
                    inlineProgressBubbleSpeed = progressEffects.bubbleSpeed,
                    inlineProgressHighEnergyHighlight = !performanceMode && progressEffects.highEnergyHighlight,
                    inlineProgressBubbleOrigin = progressEffects.bubbleOrigin,
                    containerHeight = miniHeight,
                    containerShape = miniShape,
                    clipContent = true,
                    contentPaddingStart = if (compact) {
                        lerpDp(environment.compactLeading, 8.dp, morphProgress)
                    } else {
                        environment.regularHorizontal
                    },
                    contentPaddingEnd = if (compact) {
                        lerpDp(environment.compactTrailing, 10.dp, morphProgress)
                    } else {
                        environment.regularHorizontal
                    },
                    contentPaddingVertical = if (compact) {
                        lerpDp(environment.compactVertical, 5.dp, morphProgress)
                    } else {
                        environment.regularVertical
                    },
                    playPauseVisualSize = 32.dp,
                    playPauseTouchSize = 44.dp,
                    showPreviousControl = miniPlayerControls.showPrevious,
                    previousIconRes = R.drawable.ic_rewind_fill,
                    playPausePosition = miniPlayerControls.playPausePosition,
                    showSkipNextControl = miniPlayerControls.secondaryAction != MiniPlayerSecondaryAction.HIDDEN,
                    skipNextIconRes = if (miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.QUEUE) {
                        R.drawable.ic_queue_music
                    } else {
                        R.drawable.ic_speed_fill
                    },
                    skipNextImageVector = if (miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.QUEUE) {
                        MiuixIcons.Regular.ListView
                    } else {
                        null
                    },
                    secondaryControlVisualSize = 28.dp,
                    secondaryControlTouchSize = 44.dp,
                    secondaryControlVisibility = mediaControlVisibility,
                    artworkSize = miniPlayerStyle.artworkSizeDp.dp,
                    artworkConstraintHeight = minOf(
                        miniPlayerStyle.expandedHeightDp,
                        miniPlayerStyle.compactHeightDp,
                    ).dp,
                    originalArtworkCornerRadius = miniPlayerStyle.originalArtworkCornerRadiusDp.dp,
                    artworkTextGap = miniPlayerStyle.artworkTextGapDp.dp,
                    controlGap = miniPlayerStyle.controlGapDp.dp,
                    onPlayPause = onPlayPause,
                    onSkipPrevious = onSkipPrevious,
                    // Horizontal pager transport is always the real queue next action.  The visible
                    // secondary button may be remapped to Queue without changing swipe semantics.
                    onSkipNext = onSkipNext,
                    onSecondaryAction = if (miniPlayerControls.secondaryAction == MiniPlayerSecondaryAction.QUEUE) {
                        onOpenQueue
                    } else {
                        onSkipNext
                    },
                    onClick = onOpenPlayer,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    onCoverTargetChanged = onCoverTargetChanged,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GlassNavigationSurface(
    bottomBarStyle: BottomBarStyle,
    bottomBarMaterial: BottomBarMaterial,
    shape: Shape,
    backdrop: Backdrop,
    accentColor: Color,
    tabScenes: List<NavScene>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    minimizeProgress: Float,
    playerProgressProvider: () -> Float,
    playerSheetVisible: Boolean,
    surfaceAlpha: Float,
    isLight: Boolean,
    performanceMode: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val staticForeground = usesReferenceStaticForeground()
    val selectedColor = if (staticForeground) Color.White else accentColor
    val unselectedColor = if (staticForeground) Color.White.copy(alpha = 0.66f)
        else if (isLight) Color.Black.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.66f)
    val demoContentColor = if (staticForeground) Color.White else if (isLight) Color.Black else Color.White
    val demoContainerColor = if (isLight) {
        Color(0xFFFAFAFA).copy(alpha = 0.12f)
    } else {
        Color(0xFF121212).copy(alpha = 0.10f)
    }
    val tabsBackdrop = rememberLayerBackdrop()
    val selectorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val animationScope = rememberCoroutineScope()
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val offsetAnimation = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier.height(height),
        contentAlignment = Alignment.CenterStart,
    ) {
        val tabsCount = tabScenes.size.coerceAtLeast(1)
        val density = LocalDensity.current
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val maxWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val trackInsetPx = with(density) { 4.dp.toPx() }
        val itemWidthPx = ((maxWidthPx - trackInsetPx * 2f).coerceAtLeast(1f)) / tabsCount
        val selectedIndex = selectedTabIndex.coerceIn(0, tabsCount - 1)
        var currentIndex by remember(tabsCount) { mutableIntStateOf(selectedIndex) }
        val lifecycleOwner = LocalLifecycleOwner.current
        var resumeSelectionSyncGeneration by remember { mutableIntStateOf(0) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    resumeSelectionSyncGeneration += 1
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val selectorMotion = remember(animationScope, tabsCount, itemWidthPx, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    val changed = currentIndex != targetIndex
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    if (changed) onTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.5f),
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    val direction = if (isLtr) 1f else -1f
                    updateValue(
                        (targetValue + dragAmount.x / itemWidthPx.coerceAtLeast(1f) * direction)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        LaunchedEffect(
            selectedIndex,
            selectorMotion.isUserDragging,
            selectorMotion,
            resumeSelectionSyncGeneration,
        ) {
            if (!selectorMotion.isUserDragging && currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                selectorMotion.animateToValue(selectedIndex.toFloat())
            }
        }

        val panelOffset by remember(density, maxWidthPx) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / maxWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(kotlin.math.abs(fraction))
                }
            }
        }
        val interactiveHighlight = remember(animationScope, itemWidthPx, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            trackInsetPx + (selectorMotion.value + 0.5f) * itemWidthPx + panelOffset
                        } else {
                            size.width - trackInsetPx - (selectorMotion.value + 0.5f) * itemWidthPx + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }
        val visualSelectedIndex = selectorMotion.value.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
        val selectedX = if (isLtr) {
            trackInsetPx + selectorMotion.value * itemWidthPx + panelOffset
        } else {
            maxWidthPx - trackInsetPx - (selectorMotion.value + 1f) * itemWidthPx + panelOffset
        }
        // The source demo uses a 64dp container with a 56dp selector/recording lane. Preserve the
        // same 8dp relationship when users customize the navigation height instead of shrinking the
        // selector a second time during the bottom-chrome morph.
        val selectorHeight = (height - 8.dp).coerceAtLeast(24.dp)
        val surfaceVisualAlpha = surfaceAlpha.coerceIn(0f, 1f)
        val morphScale = 1f - 0.035f * minimizeProgress
        // Material and geometry are independent owners. LIQUID_GLASS must use the real
        // refraction/recording-lane renderer regardless of whether the bottom chrome geometry is
        // NORMAL or FLOATING; keeping this tied to BottomBarStyle made the Appearance material
        // selector look ineffective whenever the current geometry happened to be NORMAL.
        val floatingDemoStyle = bottomBarMaterial == BottomBarMaterial.LIQUID_GLASS
        val commonSurfaceAlpha = surfaceVisualAlpha * (1f - playerProgressProvider()).coerceIn(0f, 1f)

        // Source demo layer 1: the visible 64dp glass row. Its content stays neutral; the selected
        // accent is produced by the tinted recording + selector above it, not by recoloring this row.
        Row(
            modifier = Modifier
                .graphicsLayer {
                    translationX = panelOffset
                    alpha = commonSurfaceAlpha
                    scaleX = morphScale
                    scaleY = morphScale
                }
                .then(
                    if (floatingDemoStyle) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                val minDimension = size.minDimension
                                val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f)
                                    .coerceAtMost(if (performanceMode) 2f else 8f)
                                vibrancy(glassSettings.vibrancyStrength)
                                blur(liquidBlurDp.dp.toPx())
                                lens(
                                    refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f,
                                    refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.35f,
                                    depthEffect = true,
                                    chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                                )
                            },
                            layerBlock = {
                                val progress = selectorMotion.pressProgress
                                val pressScale = lerp(
                                    1f,
                                    1f + with(density) { 16.dp.toPx() } / size.width.coerceAtLeast(1f),
                                    progress,
                                )
                                scaleX = pressScale
                                scaleY = pressScale
                            },
                            onDrawSurface = { drawRect(demoContainerColor) },
                        )
                    } else {
                        Modifier.glassSurfaceModifier(
                            material = bottomBarMaterial,
                            backdrop = backdrop,
                            shape = shape,
                            accentColor = accentColor,
                            isLight = isLight,
                            performanceMode = performanceMode,
                        )
                    }
                )
                .then(if (performanceMode) Modifier else interactiveHighlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabScenes.forEachIndexed { index, scene ->
                val visualSelected = if (selectorMotion.isUserDragging) {
                    index == visualSelectedIndex
                } else {
                    index == selectedIndex
                }
                val itemTint = if (floatingDemoStyle) {
                    demoContentColor
                } else if (visualSelected) {
                    selectedColor
                } else {
                    unselectedColor
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = !selectorMotion.isUserDragging &&
                                surfaceAlpha > 0.12f && !playerSheetVisible,
                        ) {
                            if (currentIndex != index) {
                                currentIndex = index
                                selectorMotion.animateToValue(index.toFloat())
                                onTabSelected(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BottomNavigationEntryIcon(
                            scene = scene,
                            tint = itemTint,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = scene.bottomNavigationLabel(),
                            color = itemTint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.graphicsLayer {
                                alpha = (1f - minimizeProgress).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
        }

        // Source demo layer 2: record a second copy of the actual tab content, tinting the complete
        // row with the accent color. The moving selector samples this layer so the selected glyph
        // and label become accented *inside* the refracted glass instead of being painted above it.
        if (floatingDemoStyle) {
            Row(
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            val progress = selectorMotion.pressProgress
                            val minDimension = size.minDimension
                            val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f)
                                .coerceAtMost(if (performanceMode) 2f else 8f)
                            vibrancy(glassSettings.vibrancyStrength)
                            blur(liquidBlurDp.dp.toPx())
                            lens(
                                refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f * progress,
                                refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.35f * progress,
                                depthEffect = true,
                                chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = (selectorMotion.pressProgress * glassSettings.highlightStrength).coerceIn(0f, 1f),
                            )
                        },
                        onDrawSurface = { drawRect(demoContainerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(selectorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(selectedColor)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabScenes.forEach { scene ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.graphicsLayer {
                                val contentScale = lerp(1f, 1.2f, selectorMotion.pressProgress)
                                scaleX = contentScale
                                scaleY = contentScale
                            },
                        ) {
                            BottomNavigationEntryIcon(
                                scene = scene,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = scene.bottomNavigationLabel(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.graphicsLayer {
                                    alpha = (1f - minimizeProgress).coerceIn(0f, 1f)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Source demo layer 3: selector is topmost and is itself the drag owner. Keeping the visual
        // and pointer owner on the same node avoids the old transparent-overlay race where a route
        // preview could update selectedTabIndex before ACTION_UP and suppress the actual navigation.
        Box(
            modifier = Modifier
                .offset { IntOffset(selectedX.roundToInt(), 0) }
                .width(with(density) { itemWidthPx.toDp() })
                .height(selectorHeight)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    alpha = commonSurfaceAlpha
                    scaleX = morphScale
                    scaleY = morphScale
                }
                .then(
                    if (surfaceAlpha > 0.12f && !playerSheetVisible) {
                        if (performanceMode) selectorMotion.modifier
                        else interactiveHighlight.gestureModifier.then(selectorMotion.modifier)
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (floatingDemoStyle) {
                        Modifier.drawBackdrop(
                            backdrop = selectorBackdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = selectorMotion.pressProgress
                                val minDimension = size.minDimension
                                val selectorHeightScale = glassSettings.refractionHeightFraction / 0.75f
                                val selectorAmountScale = glassSettings.refractionAmountFraction / 0.375f
                                lens(
                                    refractionHeight = minDimension * (10f / 56f) * selectorHeightScale * progress,
                                    refractionAmount = minDimension * (14f / 56f) * selectorAmountScale * progress,
                                    depthEffect = true,
                                    chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = (selectorMotion.pressProgress * glassSettings.highlightStrength).coerceIn(0f, 1f),
                                )
                            },
                            shadow = {
                                Shadow(
                                    alpha = (selectorMotion.pressProgress * glassSettings.shadowStrength).coerceIn(0f, 1f),
                                )
                            },
                            innerShadow = {
                                val progress = selectorMotion.pressProgress
                                InnerShadow(
                                    radius = 8.dp * progress,
                                    alpha = (progress * glassSettings.shadowStrength).coerceIn(0f, 1f),
                                )
                            },
                            layerBlock = {
                                scaleX = selectorMotion.scaleX
                                scaleY = selectorMotion.scaleY
                                val velocity = selectorMotion.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = selectorMotion.pressProgress
                                drawRect(
                                    if (isLight) Color.Black.copy(alpha = 0.10f)
                                    else Color.White.copy(alpha = 0.10f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                    } else {
                        Modifier
                            .graphicsLayer {
                                scaleX = selectorMotion.scaleX
                                scaleY = selectorMotion.scaleY
                                val velocity = selectorMotion.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                            .background(selectedColor.copy(alpha = 0.14f), Capsule())
                    },
                ),
        )
    }
}

@Composable
private fun GlassCompactTabButton(
    scene: NavScene,
    selected: Boolean,
    alpha: Float,
    playerProgressProvider: () -> Float,
    bottomBarStyle: BottomBarStyle,
    bottomBarMaterial: BottomBarMaterial,
    shape: Shape,
    backdrop: Backdrop,
    accentColor: Color,
    isLight: Boolean,
    performanceMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(scene) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "am-compact-tab-press-${scene.tag}",
    )
    val staticForeground = usesReferenceStaticForeground()
    val tint = if (staticForeground) {
        Color.White.copy(alpha = if (selected) 1f else 0.72f)
    } else if (selected) {
        accentColor
    } else if (isLight) {
        Color.Black.copy(alpha = 0.68f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha.coerceIn(0f, 1f) *
                    (1f - playerProgressProvider()).coerceIn(0f, 1f)
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(
                Modifier.glassSurfaceModifier(
                    material = bottomBarMaterial,
                    backdrop = backdrop,
                    shape = shape,
                    accentColor = accentColor,
                    isLight = isLight,
                    performanceMode = performanceMode,
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BottomNavigationEntryIcon(
            scene = scene,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun Modifier.glassSurfaceModifier(
    material: BottomBarMaterial,
    backdrop: Backdrop,
    shape: Shape,
    accentColor: Color,
    isLight: Boolean,
    performanceMode: Boolean,
): Modifier {
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    if (material == BottomBarMaterial.SOLID) {
        return background(
            brush = Brush.horizontalGradient(darkAlbumGradient(accentColor)),
            shape = shape,
        )
    }
    val colorScheme = MiuixTheme.colorScheme
    // Material selection is authoritative. Performance mode may reduce the blur radius, but it
    // must never silently replace ACRYLIC/LIQUID_GLASS with a flat translucent fill; otherwise the
    // Appearance selector reports the new value while every visible bottom surface still looks
    // SOLID. Keep background sampling/refraction alive and only trim the expensive blur pass.
    val effectiveBlurRadiusDp = if (performanceMode) {
        glassSettings.blurRadiusDp.coerceAtMost(12f)
    } else {
        glassSettings.blurRadiusDp
    }
    if (material == BottomBarMaterial.ACRYLIC) {
        return drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy(0.7f * glassSettings.vibrancyStrength)
                blur((effectiveBlurRadiusDp * 1.55f).dp.toPx())
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = (0.18f * glassSettings.highlightStrength).coerceIn(0f, 0.55f)
                )
            },
            shadow = {
                Shadow(
                    radius = 8.dp,
                    color = colorScheme.onBackground.copy(
                        alpha = ((if (isLight) 0.12f else 0.20f) * glassSettings.shadowStrength)
                            .coerceIn(0f, 0.45f)
                    ),
                )
            },
            innerShadow = {
                InnerShadow(
                    radius = 5.dp,
                    alpha = (0.10f * glassSettings.shadowStrength).coerceIn(0f, 0.35f),
                )
            },
            onDrawSurface = {
                drawRect(
                    colorScheme.surfaceContainer.copy(alpha = if (isLight) 0.78f else 0.64f)
                )
                drawRect(accentColor.copy(alpha = if (isLight) 0.035f else 0.055f))
            },
        )
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            val minDimension = size.minDimension
            // Kyant's 48dp LiquidButton uses roughly 2dp blur with a much stronger lens.
            // Raw previously used the full 8dp default blur plus a 30-46% opaque wash, which
            // smoothed away the high-frequency pixels the lens needs to make displacement visible.
            val liquidBlurDp = (effectiveBlurRadiusDp * 0.25f)
                .coerceAtMost(if (performanceMode) 2f else 8f)
            vibrancy(glassSettings.vibrancyStrength)
            blur(liquidBlurDp.dp.toPx())
            lens(
                refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f,
                refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.35f,
                depthEffect = true,
                chromaticAberration = glassSettings.chromaticAberration > 0.001f,
            )
        },
        highlight = {
            Highlight.Default.copy(alpha = (0.34f * glassSettings.highlightStrength).coerceIn(0f, 1f))
        },
        shadow = {
            Shadow(
                radius = 6.dp,
                color = Color.Black.copy(alpha = (0.08f * glassSettings.shadowStrength).coerceIn(0f, 1f)),
            )
        },
        innerShadow = {
            InnerShadow(radius = 6.dp, alpha = (0.22f * glassSettings.shadowStrength).coerceIn(0f, 1f))
        },
        onDrawSurface = {
            drawRect(colorScheme.surfaceContainer.copy(alpha = if (isLight) 0.14f else 0.10f))
            drawRect(accentColor.copy(alpha = if (isLight) 0.018f else 0.025f))
        },
    )
}

private fun lerpDp(start: Dp, end: Dp, progress: Float): Dp {
    val p = progress.coerceIn(0f, 1f)
    return start + (end - start) * p
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
