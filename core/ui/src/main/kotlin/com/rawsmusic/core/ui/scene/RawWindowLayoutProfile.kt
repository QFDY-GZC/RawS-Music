package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class RawWindowWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

enum class RawWindowHeightClass {
    Compact,
    Medium,
    Expanded,
}

@Immutable
data class RawWindowLayoutProfile(
    val windowWidth: Dp = 360.dp,
    val windowHeight: Dp = 800.dp,
    val widthClass: RawWindowWidthClass = RawWindowWidthClass.Compact,
    val heightClass: RawWindowHeightClass = RawWindowHeightClass.Medium,
    val contentMaxWidth: Dp = 360.dp,
    val homeCardContentMaxWidth: Dp = 360.dp,
    val playerContentMaxWidth: Dp = 360.dp,
    val settingsContentMaxWidth: Dp = 360.dp,
    val settingsRootContentMaxWidth: Dp = 360.dp,
    val dialogMaxWidth: Dp = 360.dp,
    val sideRailMaxWidth: Dp = 320.dp,
    val libraryRailWidth: Dp = 0.dp,
    val libraryRailGap: Dp = 0.dp,
    val libraryContentMaxWidth: Dp = 360.dp,
) {
    val bottomChromeLayoutClass: BottomChromeLayoutClass
        get() = if (widthClass == RawWindowWidthClass.Compact) {
            BottomChromeLayoutClass.Compact
        } else {
            BottomChromeLayoutClass.Regular
        }

    /**
     * Wide player layout is a window decision, not an orientation decision.
     *
     * Expanded+ windows get a two-pane player when vertical room is usable. A short, wide
     * window (including small landscape phones / split-screen strips) also stays horizontal so the portrait
     * stack cannot overflow a sub-480dp height.
     */
    val useWidePlayerLayout: Boolean
        get() =
            (widthClass.ordinal >= RawWindowWidthClass.Expanded.ordinal &&
                heightClass != RawWindowHeightClass.Compact) ||
                (heightClass == RawWindowHeightClass.Compact &&
                    windowWidth > windowHeight)

    /**
     * Multi-region layout becomes available at a current window width of 640dp.
     * Keep this as a capability flag rather than immediately switching every page to two panes.
     */
    val supportsMultiRegion: Boolean
        get() = windowWidth >= 640.dp && heightClass != RawWindowHeightClass.Compact

    /** A real supporting pane needs more room than the 640dp capability threshold. */
    val supportsSupportingPane: Boolean
        get() = windowWidth >= 840.dp && heightClass != RawWindowHeightClass.Compact

    /** Persistent library/category rail is reserved for genuinely large windows. */
    val supportsPersistentLibraryRail: Boolean
        get() = windowWidth >= 1200.dp && heightClass != RawWindowHeightClass.Compact

    val homeCardColumns: Int
        get() = when (widthClass) {
            RawWindowWidthClass.Compact -> 2
            // Keep category cards spacious on small tablets. Add columns only as the current
            // window grows, rather than changing the holder structure at the 600dp breakpoint.
            RawWindowWidthClass.Medium -> 2
            RawWindowWidthClass.Expanded -> 3
            RawWindowWidthClass.Large,
            RawWindowWidthClass.ExtraLarge -> 4
        }
}

/**
 * Resolve RawSMusic's adaptive geometry from the current app window.
 *
 * This intentionally depends only on Compose density-independent window dimensions. It does not
 * call API-level-specific platform window managers, so the same policy is valid from minSdk 24
 * through the current compile/target SDK. The breakpoints mirror Android's current window-size
 * guidance while keeping Raw's rendering/navigation ownership intact.
 */
internal fun resolveRawWindowLayoutProfile(
    width: Dp,
    height: Dp,
): RawWindowLayoutProfile {
    // LocalWindowInfo can briefly report an empty container during attachment. Do not publish a
    // 1dp adaptive profile for that transient frame; keep the compact phone fallback until the
    // first real window measurement arrives.
    val safeWidth = if (width > 1.dp) width else 360.dp
    val safeHeight = if (height > 1.dp) height else 800.dp
    val widthClass = when {
        safeWidth < 600.dp -> RawWindowWidthClass.Compact
        safeWidth < 840.dp -> RawWindowWidthClass.Medium
        safeWidth < 1200.dp -> RawWindowWidthClass.Expanded
        safeWidth < 1600.dp -> RawWindowWidthClass.Large
        else -> RawWindowWidthClass.ExtraLarge
    }
    val heightClass = when {
        safeHeight < 480.dp -> RawWindowHeightClass.Compact
        safeHeight < 900.dp -> RawWindowHeightClass.Medium
        else -> RawWindowHeightClass.Expanded
    }

    val contentMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium -> 760.dp
        RawWindowWidthClass.Expanded -> 1080.dp
        RawWindowWidthClass.Large -> 1200.dp
        RawWindowWidthClass.ExtraLarge -> 1280.dp
    }.coerceAtMost(safeWidth)

    val homeCardContentMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium -> 760.dp
        RawWindowWidthClass.Expanded -> 960.dp
        RawWindowWidthClass.Large -> 1120.dp
        RawWindowWidthClass.ExtraLarge -> 1200.dp
    }.coerceAtMost(safeWidth)

    val playerContentMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium -> 760.dp
        RawWindowWidthClass.Expanded -> 1080.dp
        RawWindowWidthClass.Large -> 1200.dp
        RawWindowWidthClass.ExtraLarge -> 1200.dp
    }.coerceAtMost(safeWidth)

    val settingsContentMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium -> 680.dp
        RawWindowWidthClass.Expanded,
        RawWindowWidthClass.Large,
        RawWindowWidthClass.ExtraLarge -> 760.dp
    }.coerceAtMost(safeWidth)

    val settingsRootContentMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium -> 680.dp
        RawWindowWidthClass.Expanded -> 960.dp
        RawWindowWidthClass.Large,
        RawWindowWidthClass.ExtraLarge -> 1040.dp
    }.coerceAtMost(safeWidth)

    // Bound large-screen dialogs to 560dp so overlays remain readable on wide windows.
    val dialogMaxWidth = if (widthClass == RawWindowWidthClass.Compact) {
        safeWidth
    } else {
        560.dp.coerceAtMost(safeWidth)
    }

    // Cap the moving navigation rail at 320dp instead of scaling it with tablet/desktop width.
    val sideRailMaxWidth = when (widthClass) {
        RawWindowWidthClass.Compact -> safeWidth
        RawWindowWidthClass.Medium,
        RawWindowWidthClass.Expanded,
        RawWindowWidthClass.Large,
        RawWindowWidthClass.ExtraLarge -> 320.dp
    }.coerceAtMost(safeWidth)

    val supportsLibraryRail = safeWidth >= 1200.dp && heightClass != RawWindowHeightClass.Compact
    val libraryRailWidth = if (supportsLibraryRail) 208.dp else 0.dp
    val libraryRailGap = if (supportsLibraryRail) 16.dp else 0.dp
    val libraryContentMaxWidth =
        (contentMaxWidth - libraryRailWidth - libraryRailGap).coerceAtLeast(1.dp)

    return RawWindowLayoutProfile(
        windowWidth = safeWidth,
        windowHeight = safeHeight,
        widthClass = widthClass,
        heightClass = heightClass,
        contentMaxWidth = contentMaxWidth,
        homeCardContentMaxWidth = homeCardContentMaxWidth,
        playerContentMaxWidth = playerContentMaxWidth,
        settingsContentMaxWidth = settingsContentMaxWidth,
        settingsRootContentMaxWidth = settingsRootContentMaxWidth,
        dialogMaxWidth = dialogMaxWidth,
        sideRailMaxWidth = sideRailMaxWidth,
        libraryRailWidth = libraryRailWidth,
        libraryRailGap = libraryRailGap,
        libraryContentMaxWidth = libraryContentMaxWidth,
    )
}

val LocalRawWindowLayoutProfile = staticCompositionLocalOf { RawWindowLayoutProfile() }

/** Measure the Activity window, not a scaled scene or an artwork transition surface. */
@Composable
fun rememberRawWindowLayoutProfile(): RawWindowLayoutProfile {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    return remember(windowInfo.containerSize, density.density) {
        resolveRawWindowLayoutProfile(
            width = with(density) { windowInfo.containerSize.width.toDp() },
            height = with(density) { windowInfo.containerSize.height.toDp() },
        )
    }
}
