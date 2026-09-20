package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Geometry contract shared by the page, MiniPlayer accessory, navigation and PLAYER sheet.
 *
 * This mirrors the role of UIKit's tab-bar/accessory environment: content never guesses the
 * current bottom-bar height and visual styles never own layout.  The stable endpoint stays latched
 * while an interactive transition is in flight so moving surfaces cannot feed geometry back into
 * their own source coordinates.
 */
enum class BottomChromeLayoutClass {
    Compact,
    Regular,
}

enum class BottomChromeStableMode {
    Expanded,
    Minimized,
}

@Immutable
data class BottomChromeInsets(
    val contentBottom: Dp = 0.dp,
    val accessoryHeight: Dp = 0.dp,
    val navigationHeight: Dp = 0.dp,
    val interSurfaceGap: Dp = 0.dp,
    val systemBottom: Dp = 0.dp,
)


@Immutable
data class BottomChromeGeometry(
    val chromeHeight: Dp = 0.dp,
    val contentBottomInset: Dp = 0.dp,
    val navigationBottomInset: Dp = 0.dp,
    val miniPlayerBottomInset: Dp = 0.dp,
    val navigationMinimizeTravel: Dp = 0.dp,
    val miniPlayerMinimizeTravel: Dp = 0.dp,
)

@Immutable
data class BottomChromeEnvironment(
    val active: Boolean = false,
    val layoutClass: BottomChromeLayoutClass = BottomChromeLayoutClass.Compact,
    val hasMiniPlayer: Boolean = false,
    val hasNavigation: Boolean = false,
    val stableMode: BottomChromeStableMode = BottomChromeStableMode.Expanded,
    val transitionProgress: Float = 0f,
    val compactLeading: Dp = 16.dp,
    val compactTrailing: Dp = 20.dp,
    val compactVertical: Dp = 9.dp,
    val regularHorizontal: Dp = 8.dp,
    val regularVertical: Dp = 12.dp,
    val accessoryHeight: Dp = 62.dp,
    val compactAccessoryHeight: Dp = 54.dp,
    val navigationHeight: Dp = 64.dp,
    val interSurfaceGap: Dp = 8.dp,
    val contentBreathingRoom: Dp = 8.dp,
    val regularAccessoryMaxWidth: Dp = 690.dp,
    val systemBottom: Dp = 0.dp,
) {
    val geometry: BottomChromeGeometry
        get() {
            if (!active) return BottomChromeGeometry()
            val navigationStack = if (hasNavigation) navigationHeight else 0.dp
            val gap = if (hasMiniPlayer && hasNavigation) interSurfaceGap else 0.dp
            val accessory = if (hasMiniPlayer) accessoryHeight else 0.dp
            // Keep the host container large enough for the complete Expanded -> Minimized morph.
            // Page safe-area accommodation is a separate contract and follows only stable endpoints.
            val chromeHeight = navigationStack + gap + accessory + systemBottom
            val expandedContentBottom = chromeHeight + contentBreathingRoom
            val minimizedInlineHeight = if (
                layoutClass == BottomChromeLayoutClass.Compact && hasNavigation
            ) {
                // Compact navigation buttons share the center accessory's physical height.
                // This keeps all three inline surfaces on one horizontal envelope after the
                // MiniPlayer compact-height style is adjusted.
                compactAccessoryHeight + systemBottom
            } else {
                chromeHeight
            }
            val endpointProgress = transitionProgress.coerceIn(0f, 1f)
            val contentBottomInset = expandedContentBottom +
                ((minimizedInlineHeight + contentBreathingRoom) - expandedContentBottom) * endpointProgress
            return BottomChromeGeometry(
                chromeHeight = chromeHeight,
                contentBottomInset = contentBottomInset,
                navigationBottomInset = systemBottom,
                miniPlayerBottomInset = navigationStack + gap + systemBottom,
                // One normalized transition owns both surfaces; these are visual mappings only.
                navigationMinimizeTravel = 18.dp,
                miniPlayerMinimizeTravel = if (hasNavigation) navigationHeight + gap else 0.dp,
            )
        }

    val insets: BottomChromeInsets
        get() {
            if (!active) return BottomChromeInsets()
            return BottomChromeInsets(
                contentBottom = geometry.contentBottomInset,
                accessoryHeight = if (hasMiniPlayer) accessoryHeight else 0.dp,
                navigationHeight = if (hasNavigation) navigationHeight else 0.dp,
                interSurfaceGap = if (hasMiniPlayer && hasNavigation) interSurfaceGap else 0.dp,
                systemBottom = systemBottom,
            )
        }
}

val LocalBottomChromeEnvironment = staticCompositionLocalOf { BottomChromeEnvironment() }
val LocalBottomChromeInsets = staticCompositionLocalOf { BottomChromeInsets() }
