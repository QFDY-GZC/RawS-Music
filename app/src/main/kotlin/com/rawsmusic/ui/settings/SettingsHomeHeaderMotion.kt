package com.rawsmusic.ui.settings

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.PI

/**
 * Scroll-driven values from the ColorOS Settings home app bar (jadx/设置):
 * SettingsCollapsingToolbarLayout (116dp, pinned 52dp toolbar), homepage_toolbar search bar (52dp),
 * AppBarBlurOverlayBehavior and HomepageImpl.
 */
internal object SettingsHomeHeaderMotion {
    /** Total AppBar scroll range: the whole 116dp collapsing toolbar scrolls away (snap|scroll). */
    const val COLLAPSE_RANGE_DP = 116f
    /** COUICollapsingToolbarLayout title range: height - minimumHeight = 116 - 52. */
    const val TITLE_RANGE_DP = 64f
    const val TOOLBAR_HEIGHT_DP = 52f
    const val SEARCH_BAR_HEIGHT_DP = 52f
    const val TITLE_MARGIN_BOTTOM_DP = 18f
    /**
     * COUICollapsedToolbarStyle's title margins (supportTitleMarginTop 3dp / supportTitleMarginBottom
     * 1dp) shift the collapsed anchor's centre down by (3 - 1) / 2.
     */
    const val TITLE_MARGIN_CENTER_OFFSET_DP = 1f
    /** AppBarBlurOverlayBehavior.ALPHA_SCROLL_THRESHOLD_DP. */
    const val BLUR_RAMP_DP = 30f
    /** homepage_appbar_blur_extra_height, only while the AppBar is collapsed. */
    const val BLUR_COLLAPSED_EXTRA_DP = 14f
    /** AppBarBlurParams startBlurRadius (RenderEffect pixels). */
    const val BLUR_RADIUS_PX = 30f

    /** COUIEaseInterpolator, used for the title position. */
    val TitleEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    /** SettingsCollapsableAppBarLayout "attach" spring: response 0.25, bounce 0. */
    val SnapStiffness: Float = couiStiffness(0.25f)
    /** followListResponseUp: nested scroll (touch or fling) collapsing the AppBar. */
    val CollapseFollowStiffness: Float = couiStiffness(0.2f)
    /** followListResponseDown: nested scroll (touch or fling) expanding the AppBar. */
    val ExpandFollowStiffness: Float = couiStiffness(0.1f)

    /** COUISpringForce: stiffness = (2π / response)², damping ratio = 1 − bounce (0 here). */
    private fun couiStiffness(response: Float): Float {
        val omega = (2.0 * PI / response).toFloat()
        return omega * omega
    }

    data class Frame(
        /** |offset| / totalScrollRange. */
        val collapseFraction: Float,
        /** Title fraction f = |offset| / 64dp. */
        val titleFraction: Float,
        val titleAlpha: Float,
        val titleScale: Float,
        /** COUIEaseInterpolator(f), drives the position lerp. */
        val titlePositionFraction: Float,
        /** Offset-only variant; the chrome resolves the real distance (see [blurAlpha]). */
        val blurAlpha: Float,
        /**
         * SettingsCollapsableAppBarLayout.isCollapsed() delegates to
         * COUICollapsingToolbarLayout.isCollapsed() = `COUICollapsingTextHelper.getExpansionFraction()
         * == 1f`, i.e. the *title* has finished collapsing (|offset| >= 64dp) — not the full 116dp
         * range. HomepageImpl keys `homepage_appbar_blur_extra_height` off this.
         */
        val collapsed: Boolean,
    )

    fun at(scrollDp: Float): Frame {
        val collapse = (scrollDp / COLLAPSE_RANGE_DP).coerceIn(0f, 1f)
        val title = (scrollDp / TITLE_RANGE_DP).coerceIn(0f, 1f)
        return Frame(
            collapseFraction = collapse,
            titleFraction = title,
            // SettingsCollapsingToolbarLayout: alpha lerp(255, 0, f), scale lerp(1.0, 0.7, f).
            titleAlpha = 1f - title,
            titleScale = 1f - 0.3f * title,
            titlePositionFraction = TitleEasing.transform(title),
            blurAlpha = (scrollDp / BLUR_RAMP_DP).coerceIn(0f, 1f),
            collapsed = title >= 1f,
        )
    }

    /**
     * AppBarBlurOverlayBehavior.computeOverlayAlpha: `alpha = min(1, distance / 30dp)`, 0 when the
     * distance is 0. [distanceDp] is `resolveScrollDistancePx()`: the |AppBar offset| while it is
     * non-zero, otherwise the content scroll plus the COUI rubber band
     * (`mAccumulatedContentScrollPx + COUIRecyclerView.getScrollY()`), so a list that cannot move the
     * bar still fades the overlay in while it is overscrolled.
     */
    fun blurAlpha(distanceDp: Float): Float = (distanceDp / BLUR_RAMP_DP).coerceIn(0f, 1f)

    /**
     * Nested-scroll delta the AppBar takes out of the *pre*-scroll phase (Compose signs:
     * `available.y < 0` drags the list up = collapse, `> 0` drags it down = expand).
     *
     * COUIAppBarLayout.BaseBehavior.onNestedPreScroll only ever consumes a collapse here: for that
     * direction its range is `[-getTotalScrollRange(), -getTotalScrollRange() +
     * getDownNestedPreScrollRange()]`, and this AppBar's children are `scroll|snap` (17) and
     * `snap|exitUntilCollapsed|scroll` (19) — neither carries ENTER_ALWAYS, so
     * `getDownNestedPreScrollRange() == 0` and the range is the full `[-116dp, 0]`. The bar therefore
     * collapses *before* the list moves at all.
     */
    fun preScrollConsumption(availableY: Float, offsetPx: Float, rangePx: Float): Float =
        if (availableY >= 0f) 0f else clampToRange(availableY, offsetPx, rangePx)

    /**
     * Nested-scroll delta the AppBar takes out of the *post*-scroll leftover.
     *
     * Expanding is never part of the pre-scroll: for an expansion the same call computes
     * `min = -getUpNestedPreScrollRange() = -116dp` and `max = -116dp + 0`, an empty range, so
     * `onNestedPreScroll` bails out. The expansion only arrives through `onNestedScroll`'s
     * `dyUnconsumed` — i.e. what the RecyclerView could not consume because it is already at its top.
     * That is why the large title stays hidden while the list is scrolled and only fades back in once
     * the list is home.
     */
    fun postScrollConsumption(availableY: Float, offsetPx: Float, rangePx: Float): Float =
        if (availableY <= 0f) 0f else clampToRange(availableY, offsetPx, rangePx)

    /** Clamps `offsetPx + delta` into the AppBar's scroll range and reports the accepted part. */
    private fun clampToRange(delta: Float, offsetPx: Float, rangePx: Float): Float =
        (offsetPx + delta).coerceIn(-rangePx, 0f) - offsetPx
}
