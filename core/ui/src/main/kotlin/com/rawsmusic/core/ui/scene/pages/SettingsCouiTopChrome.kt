package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/*
 * ColorOS Settings top chrome, restored from jadx/设置:
 *  - com.coui.appcompat.toolbar.AppBarBlurHelper / AppBarBlurParamsExtKt / GradientBackgroundDrawable
 *    gradient blur (30 -> 0) under a page-colour ramp; the AppBar itself is transparent.
 *  - AppBarBlurOverlayView / AppBarBlurOverlayBehavior
 *    overlay spans AppBar top .. AppBar bottom + extra, alpha = min(1, scrollPx / 30dp).
 *  - COUIDividerAppBarLayout: divider fallback when the material blur is unavailable.
 *  - SettingsCollapsingToolbarLayout / COUICollapsingTextHelper: large title transform.
 *  - SettingsCollapsableAppBarLayout / COUIAppBarLayout / COUIViewOffsetHelper
 *    spring-followed offset and midpoint snap with the "attach" spring.
 */
internal object CouiTopChromeSpec {
    /** toolbar_min_height / coui_appbar_title_toolbar_height. */
    val ToolbarHeight = 52.dp
    /** SettingsCOUICollapsingToolbarLayoutStyle height. */
    val CollapsingHeight = 116.dp
    val ExpandedTitleMarginStart = 24.dp
    val ExpandedTitleMarginEnd = 16.dp
    val ExpandedTitleMarginBottom = 18.dp
    val ExpandedTitleSize = 40.sp
    /** SettingsCollapsingToolbarLayout: helper.scale = lerp(1.0, 0.7, f). */
    const val CollapsedTitleScale = 0.7f
    /** TextAppearance.COUI.Toolbar.SecondTitle. */
    val SecondTitleSize = 18.sp

    /** AppBarBlurOverlayBehavior.ALPHA_SCROLL_THRESHOLD_DP. */
    val OverlayAlphaScrollThreshold = 30.dp
    /** AppBarBlurOverlayView.EXTRA_HEIGHT_DP_SCROLL_DRIVEN. */
    val OverlayExtraHeightScrollDriven = 24.dp
    /** homepage_appbar_blur_extra_height, applied only while collapsed. */
    val HomepageOverlayExtraHeight = 14.dp

    /** AppBarBlurParams: startBlurRadius = 30, endBlurRadius = 0 (RenderEffect pixels). */
    const val GradientStartBlurRadiusPx = 30f

    /** COUIDividerAppBarLayout / toolbar_divider_height. */
    val DividerHeight = 0.33.dp
    val DividerSideMargin = 24.dp

    /** COUICollapsingToolbarLayout centre-title fade thresholds. */
    const val SmallTitleShowFraction = 0.17f
    const val SmallTitleHideFraction = 0.15f
    const val SmallTitleBlurPx = 15f

    /** COUIEaseInterpolator. */
    val EaseInterpolator = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    /** COUICollapsableAppBarLayoutParamsExt: attach 0.25, collapse 0.2, expand 0.1, alpha 0.3. */
    val AttachStiffness = couiSpringStiffness(0.25f)
    val CollapseFollowStiffness = couiSpringStiffness(0.2f)
    val ExpandFollowStiffness = couiSpringStiffness(0.1f)
    val TitleAlphaStiffness = couiSpringStiffness(0.3f)
}

/** COUISpringForce: stiffness = (2π / response)², damping ratio = 1 - bounce (bounce is 0 here). */
private fun couiSpringStiffness(response: Float): Float {
    val omega = (2.0 * PI / response).toFloat()
    return omega * omega
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/**
 * Collapsing AppBar offset: [targetOffset] follows the finger immediately, [offset] follows it with
 * a critically damped spring exactly like COUIViewOffsetHelper does for every nested-scroll event.
 */
@Stable
internal class CouiCollapsingHeaderState(private val scope: CoroutineScope) {
    var rangePx by mutableFloatStateOf(0f)
        private set
    /**
     * Logical AppBar offset. The scrolling container follows it 1:1 (the finger), while the
     * header views chase it through [offset] (COUICoordinatorScrollingViewAnimator +
     * COUIViewOffsetHelper).
     */
    var targetOffset by mutableFloatStateOf(0f)
        private set
    val offset = Animatable(0f)

    val fraction: Float
        get() = if (rangePx <= 0f) 0f else (-offset.value / rangePx).coerceIn(0f, 1f)

    val isCollapsed: Boolean
        get() = rangePx > 0f && offset.value <= -rangePx + 0.5f

    fun updateRange(range: Float) {
        if (range == rangePx) return
        rangePx = range
        val clamped = targetOffset.coerceIn(-range, 0f)
        if (clamped != targetOffset) {
            targetOffset = clamped
            scope.launch { offset.snapTo(clamped) }
        }
    }

    /**
     * Moves the header by [delta] (negative collapses) and returns the consumed amount.
     * SettingsCollapsableAppBarLayout.ScrollBehavior.scroll: COUIAppBarLayout.BaseBehavior's
     * onNestedPreScroll / onNestedScroll pass type 0 for touch and fling alike, so the response is
     * 0.2 when collapsing (dy > 0) and 0.1 otherwise; followHeader (type 1) is never reached here.
     */
    fun dispatch(delta: Float): Float {
        interruptSnap()
        val next = (targetOffset + delta).coerceIn(-rangePx, 0f)
        val consumed = next - targetOffset
        if (consumed == 0f) return 0f
        targetOffset = next
        follow(
            next,
            if (consumed < 0f) CouiTopChromeSpec.CollapseFollowStiffness else CouiTopChromeSpec.ExpandFollowStiffness
        )
        return consumed
    }

    /** COUIAppBarLayout.snapToChildIfNeeded: midpoint rule, animated with the attach spring. */
    fun snapToEdge() {
        if (targetOffset <= -rangePx || targetOffset >= 0f) return
        val from = targetOffset
        // calculateSnapOffset on int offsets: offset < (collapsed + expanded) / 2 → collapsed.
        val range = rangePx.roundToInt()
        val to = if (targetOffset.roundToInt() < -range / 2) -rangePx else 0f
        targetOffset = to
        isSnapping = true
        val attach = spring<Float>(dampingRatio = 1f, stiffness = CouiTopChromeSpec.AttachStiffness)
        snapJob?.cancel()
        snapJob = scope.launch {
            // animateOffsetWithDuration override: header and container spring with the same
            // attach parameters, each from where it currently is.
            launch { offset.animateTo(to, attach) }
            contentSnap.snapTo(from)
            contentSnap.animateTo(to, attach)
            isSnapping = false
        }
    }

    /** True while the release snap animates the container with its own spring. */
    var isSnapping by mutableStateOf(false)
        private set
    private val contentSnap = Animatable(0f)
    private var snapJob: kotlinx.coroutines.Job? = null

    /** Where the scrolling container sits. */
    val contentOffset: Float
        get() = if (isSnapping) contentSnap.value else targetOffset

    /** A touch during the snap continues from the container's current position. */
    private fun interruptSnap() {
        if (!isSnapping) return
        snapJob?.cancel()
        targetOffset = contentSnap.value
        isSnapping = false
    }

    private fun follow(target: Float, stiffness: Float) {
        scope.launch {
            offset.animateTo(target, spring(dampingRatio = 1f, stiffness = stiffness))
        }
    }
}

internal fun couiCollapsingHeaderConnection(state: CouiCollapsingHeaderState): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f) return Offset.Zero
            return Offset(0f, state.dispatch(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f) return Offset.Zero
            return Offset(0f, state.dispatch(available.y))
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            state.snapToEdge()
            return Velocity.Zero
        }
    }

/**
 * Places the scrolling content at the header offset (COUIHeaderScrollingViewBehavior) and extends
 * its height by the scroll range so the bottom edge stays glued to the window while collapsed.
 * The offset is read only in the placement phase.
 */
internal fun Modifier.couiHeaderScrollingView(state: CouiCollapsingHeaderState): Modifier =
    layout { measurable, constraints ->
        val extra = state.rangePx.roundToInt()
        if (extra <= 0 || constraints.maxHeight == Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val height = constraints.maxHeight + extra
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // The list follows the finger (or its own snap spring); only the header lags.
            placeable.place(0, state.contentOffset.roundToInt())
        }
    }

/**
 * AppBarBlurOverlayView: GradientBackgroundDrawable over a gradient background blur.
 *
 * GradientBackgroundDrawable runs its LinearGradient from the bottom edge (position 0) to the top
 * edge (position 1). Light: positions [0, 0.4, 1] / alphas [0, 0.7, 1]; dark: [0, 1] / [0, 0.4].
 * Converted to top-to-bottom stops below.
 */
@Composable
fun CouiAppBarBlurOverlay(
    backdrop: Backdrop,
    blurSupported: Boolean,
    baseColor: Color,
    isDark: Boolean,
    alphaProvider: () -> Float,
    modifier: Modifier = Modifier,
    blurRadiusPx: Float = CouiTopChromeSpec.GradientStartBlurRadiusPx,
    exportedBackdrop: LayerBackdrop? = null,
) {
    val gradient = remember(baseColor, isDark) {
        if (isDark) {
            Brush.verticalGradient(
                0f to baseColor.copy(alpha = settingsOverlayTintAlpha(baseColor.alpha, 0.4f)),
                1f to baseColor.copy(alpha = 0f),
            )
        } else {
            Brush.verticalGradient(
                0f to baseColor.copy(alpha = settingsOverlayTintAlpha(baseColor.alpha, 1f)),
                0.6f to baseColor.copy(alpha = settingsOverlayTintAlpha(baseColor.alpha, 0.7f)),
                1f to baseColor.copy(alpha = 0f),
            )
        }
    }
    // AppBarBlurOverlayBehavior applies View alpha to the whole material, not just its tint.
    // The sampled page already contains its fill; a second opaque fill here masks missing
    // samples and creates a flat band instead of the reference background material.
    Box(modifier = modifier
        .then(exportedBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
        .graphicsLayer { alpha = settingsBlurSampleAlpha(alphaProvider()) }) {
        if (blurSupported) {
                // Composite in visible View coordinates on every supported API. The mask is
                // outside the padded Gaussian input, so it cannot inherit that input's bounds.
                // An opaque sampled scene makes SrcOver the reference complementary RGB mix.
                Box(
                    modifier = Modifier.matchParentSize().drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {},
                        highlight = null,
                        shadow = null,
                    ),
                )
                Box(
                    modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = couiGradientBlurMask(),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            padding = blurRadiusPx * 3f
                            blur(blurRadiusPx)
                        },
                        highlight = null,
                        shadow = null,
                    )
                )
        }
        Box(modifier = Modifier.matchParentSize().background(gradient))
    }
}

/**
 * Blur-off appearance: opaque AppBar plus the COUIDividerAppBarLayout divider,
 * alpha = f and side margin = 24dp * (1 - f), with f = min(1, dy / dividerScrollRange).
 */
@Composable
internal fun CouiOpaqueAppBarBackground(
    background: Color,
    dividerColor: Color,
    dividerFractionProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val sideMarginPx = with(LocalDensity.current) { CouiTopChromeSpec.DividerSideMargin.toPx() }
    Box(modifier = modifier.background(background)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(CouiTopChromeSpec.DividerHeight)
                .graphicsLayer {
                    val f = dividerFractionProvider().coerceIn(0f, 1f)
                    alpha = f
                    val width = size.width
                    scaleX = if (width > 0f) {
                        ((width - 2f * sideMarginPx * (1f - f)) / width).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
                .background(dividerColor)
        )
    }
}

/** COUIToolbar: 52dp, navigation button and an 18dp medium second title. */
@Composable
internal fun CouiSettingsToolbar(
    title: String,
    titleColor: Color,
    statusBarTop: Dp,
    onBack: (() -> Unit)?,
    titleVisibility: State<Float>?,
    onTitleStartChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var titleHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val toolbarHeightPx = with(density) { CouiTopChromeSpec.ToolbarHeight.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarTop)
            .height(CouiTopChromeSpec.ToolbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = titleColor
                )
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(CouiTopChromeSpec.ExpandedTitleMarginStart))
        }
        Text(
            text = title,
            color = titleColor,
            fontSize = CouiTopChromeSpec.SecondTitleSize,
            fontWeight = FontWeight.Medium,
            fontFamily = appFontFamily(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .onPlaced { onTitleStartChanged(it.positionInParent().x) }
                .padding(end = 16.dp)
                .onSizeChanged { titleHeight = it.height }
                .then(
                    if (titleVisibility == null) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            // Centre-title fade: alpha = v, translationY = (toolbarH - titleBottom) * (1 - v),
                            // blur = 15px * (1 - v).
                            val v = titleVisibility.value.coerceIn(0f, 1f)
                            alpha = v
                            translationY = (toolbarHeightPx - titleHeight) / 2f * (1f - v)
                            val radius = CouiTopChromeSpec.SmallTitleBlurPx * (1f - v)
                            renderEffect = if (radius > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                BlurEffect(radius, radius, TileMode.Decal)
                            } else {
                                null
                            }
                        }
                    }
                )
        )
    }
}

/**
 * Small-title visibility for the collapsing page: shown past f > 0.17, hidden at f <= 0.15,
 * animated with the response-0.3 critically damped spring.
 */
@Composable
internal fun rememberCouiSmallTitleVisibility(state: CouiCollapsingHeaderState): State<Float> {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.fraction }.collect { f ->
            if (f > CouiTopChromeSpec.SmallTitleShowFraction) {
                shown = true
            } else if (f <= CouiTopChromeSpec.SmallTitleHideFraction) {
                shown = false
            }
        }
    }
    return animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = CouiTopChromeSpec.TitleAlphaStiffness),
        label = "coui-small-title-visibility",
    )
}

/**
 * SettingsCollapsingToolbarLayout large title, drawn in window coordinates:
 * position eased with COUIEaseInterpolator between the expanded (start|bottom) and collapsed
 * (toolbar title) anchors, scale lerp(1, 0.7, f) around the text's top-left corner,
 * alpha lerp(255, 0, f).
 */
@Composable
internal fun CouiCollapsingLargeTitle(
    title: String,
    color: Color,
    statusBarTop: Dp,
    state: CouiCollapsingHeaderState,
    collapsedStartXProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var textHeight by remember { mutableIntStateOf(0) }
    val statusTopPx = with(density) { statusBarTop.toPx() }
    val collapsingHeightPx = with(density) { CouiTopChromeSpec.CollapsingHeight.toPx() }
    val toolbarHeightPx = with(density) { CouiTopChromeSpec.ToolbarHeight.toPx() }
    val marginStartPx = with(density) { CouiTopChromeSpec.ExpandedTitleMarginStart.toPx() }
    val marginBottomPx = with(density) { CouiTopChromeSpec.ExpandedTitleMarginBottom.toPx() }
    Text(
        text = title,
        color = color,
        fontSize = CouiTopChromeSpec.ExpandedTitleSize,
        fontWeight = FontWeight.Medium,
        fontFamily = appFontFamily(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = CouiTopChromeSpec.ExpandedTitleMarginStart + CouiTopChromeSpec.ExpandedTitleMarginEnd)
            .onSizeChanged { textHeight = it.height }
            .graphicsLayer {
                val offsetPx = state.offset.value
                val f = state.fraction
                val eased = CouiTopChromeSpec.EaseInterpolator.transform(f)
                val scale = lerpFloat(1f, CouiTopChromeSpec.CollapsedTitleScale, f)
                val expandedX = marginStartPx
                val expandedY = statusTopPx + collapsingHeightPx - marginBottomPx - textHeight + offsetPx
                val collapsedX = collapsedStartXProvider()
                val collapsedY = statusTopPx + (toolbarHeightPx - textHeight * CouiTopChromeSpec.CollapsedTitleScale) / 2f
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerpFloat(expandedX, collapsedX, eased)
                translationY = lerpFloat(expandedY, collapsedY, eased)
                scaleX = scale
                scaleY = scale
                alpha = 1f - f
            }
    )
}

/**
 * Settings page scaffold with the COUI top chrome.
 *
 * [background] fills the page and may be transparent (custom settings background); the blur
 * overlay colour ramp then uses [rampColor] (transparent disables the ramp and keeps only the blur).
 */
@Composable
fun CouiSettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    background: Color,
    titleColor: Color,
    modifier: Modifier = Modifier,
    rampColor: Color = background,
    contentMaxWidth: Dp = Dp.Infinity,
    collapsingLargeTitle: Boolean = false,
    backgroundBackdrop: Backdrop? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusBarTop = rawStableStatusBarTopPadding()
    val toolbarBottom = statusBarTop + CouiTopChromeSpec.ToolbarHeight
    val headerRange = if (collapsingLargeTitle) {
        CouiTopChromeSpec.CollapsingHeight - CouiTopChromeSpec.ToolbarHeight
    } else {
        0.dp
    }
    val headerRangePx = with(density) { headerRange.toPx() }
    val overlayThresholdPx = with(density) { CouiTopChromeSpec.OverlayAlphaScrollThreshold.toPx() }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val headerState = remember { CouiCollapsingHeaderState(scope) }
    SideEffect { headerState.updateRange(headerRangePx) }
    val headerConnection = remember(headerState) { couiCollapsingHeaderConnection(headerState) }
    val overscroll = rememberCouiSpringOverscrollState()
    val topBlurState = rememberHazeState()
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isDarkPage = (if (rampColor.alpha > 0f) rampColor else titleColor.invertedForLuminance()).luminance() < 0.5f
    var smallTitleStartX by remember { mutableFloatStateOf(0f) }
    val smallTitleVisibility = if (collapsingLargeTitle) rememberCouiSmallTitleVisibility(headerState) else null
    val headerCollapsed by remember(headerState) { derivedStateOf { headerState.isCollapsed } }

    Box(
        modifier
            .fillMaxSize()
            .background(background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Record the full viewport and its solid fill, not only the narrower scrolling
                // column. Homepage and child pages now share the exact same sampling boundary.
                .then(if (blurSupported) Modifier.settingsTopBlurSource(topBlurState, backgroundBackdrop) else Modifier)
                .background(background)
                // Outer: the rubber band sees pre-scroll first (push-back while overscrolled) and
                // post-scroll last (the AppBar expands before the list overscrolls).
                .nestedScroll(overscroll.connection)
                .then(if (collapsingLargeTitle) Modifier.nestedScroll(headerConnection) else Modifier)
                .couiHeaderScrollingView(headerState)
        ) {
            WithoutPlatformOverscroll {
            Column(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .couiOverscrollOffset(overscroll)
                    .verticalScroll(scrollState, flingBehavior = rememberCouiFlingBehavior(overscroll))
                    .rawStableNavigationBarsPadding()
                    .padding(horizontal = 12.dp),
                content = {
                    Spacer(Modifier.height(toolbarBottom + headerRange + 8.dp))
                    content()
                    Spacer(Modifier.height(180.dp))
                }
            )
            }
        }

        // The overlay spans the AppBar (which follows the header offset) plus its extra height.
        val appBarHeight = toolbarBottom + headerRange
        val overlayExtra = when {
            !collapsingLargeTitle -> CouiTopChromeSpec.OverlayExtraHeightScrollDriven
            headerCollapsed -> CouiTopChromeSpec.HomepageOverlayExtraHeight
            else -> 0.dp
        }
        val appBarOffsetModifier = Modifier.offset {
            IntOffset(0, headerState.offset.value.roundToInt())
        }
        if (blurSupported) {
            // The blur stays at the window top. Only its visible height follows the collapsing
            // header; moving the effect itself puts the status bar in the weak/clear tail.
            val visibleHeight = with(density) {
                settingsBlurViewportHeight((appBarHeight + overlayExtra).toPx(), headerState.offset.value).toDp()
            }
            ProgressiveTopBlur(
                hazeState = topBlurState,
                radius = 30.dp,
                tintColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background,
                tintAlpha = if (isDarkPage) 0.20f else 0.24f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleHeight)
                    .graphicsLayer {
                        alpha = couiOverlayAlpha(
                            couiScrollDistance(headerState.offset.value, scrollState.value),
                            overlayThresholdPx,
                        )
                    },
            )
        } else if (!blurSupported) {
            // Divider range: AppBar height for a fixed bar, total scroll range for a collapsing one.
            val dividerRangePx = with(density) {
                (if (collapsingLargeTitle) headerRange else toolbarBottom).toPx()
            }
            CouiOpaqueAppBarBackground(
                background = rampColor,
                dividerColor = titleColor.copy(alpha = if (isDarkPage) 0.16f else 0.12f),
                dividerFractionProvider = {
                    if (dividerRangePx <= 0f) {
                        0f
                    } else {
                        (scrollState.value + abs(headerState.targetOffset)) / dividerRangePx
                    }
                },
                modifier = appBarOffsetModifier
                    .fillMaxWidth()
                    .height(appBarHeight)
            )
        }

        if (collapsingLargeTitle) {
            CouiCollapsingLargeTitle(
                title = title,
                color = titleColor,
                statusBarTop = statusBarTop,
                state = headerState,
                collapsedStartXProvider = { smallTitleStartX },
            )
        }

        CouiSettingsToolbar(
            title = title,
            titleColor = titleColor,
            statusBarTop = statusBarTop,
            onBack = onBack,
            titleVisibility = smallTitleVisibility,
            onTitleStartChanged = { smallTitleStartX = it },
        )
    }
}

/** Dark text implies a light page and vice versa. */
private fun Color.invertedForLuminance(): Color =
    if (luminance() < 0.5f) Color.White else Color.Black

/**
 * `OplusRenderEffect.createGradientBlurEffect(30, 0, isVertical = true, speed = 3, 0, 0, 0)` →
 * `RenderEffect.nativeCreateGradientBlurEffect(30, 30, 30, 0, 3, …)`, rendered in libhwui by
 *
 *     ratio = ratios.x + (ratios.y - ratios.x) * (coord.y / size);   // 1 at the top → 0 at the bottom
 *     ratio = pow(1.0 - ratio, speed);
 *     return mix(blurShader.eval(coord), originShader.eval(coord), ratio);
 *
 * i.e. a 30px blur mixed with the sharp content by t³ (t = y / height). As a DstIn mask over the
 * blurred layer that is a blur weight of 1 − t³.
 */
fun couiGradientBlurMask(): Brush = GradientBlurMask

private val GradientBlurMask: Brush = Brush.verticalGradient(
    *Array(33) { i ->
        val t = i / 32f
        t to Color.White.copy(alpha = settingsBlurWeight(t))
    }
)

/** AppBarBlurOverlayBehavior.computeOverlayAlpha. */
internal fun couiOverlayAlpha(scrollDistancePx: Float, thresholdPx: Float): Float =
    if (scrollDistancePx <= 0f || thresholdPx <= 0f) 0f else min(1f, scrollDistancePx / thresholdPx)

/** resolveScrollDistancePx: the AppBar offset wins while it is non-zero, then the content scroll. */
internal fun couiScrollDistance(headerOffsetPx: Float, contentScrollPx: Int): Float {
    val appBar = abs(headerOffsetPx)
    return if (appBar > 0.5f) appBar else contentScrollPx.toFloat()
}
