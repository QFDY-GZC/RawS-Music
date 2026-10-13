package com.rawsmusic.core.ui.scene.pages

/** Weight of the blurred sample; the sharp sample receives the complementary weight. */
internal fun settingsBlurWeight(normalizedY: Float): Float {
    val t = normalizedY.coerceIn(0f, 1f)
    return 1f - t * t * t
}

internal fun settingsOverlayTintAlpha(baseAlpha: Float, stopAlpha: Float): Float =
    baseAlpha.coerceIn(0f, 1f) * stopAlpha.coerceIn(0f, 1f)

/** Visible band below the top edge after the collapsing AppBar moves upward. */
fun settingsBlurViewportHeight(fullHeight: Float, headerOffset: Float): Float =
    (fullHeight + (if (headerOffset.isFinite()) headerOffset else 0f).coerceAtMost(0f)).coerceAtLeast(0f)

/** Reference overlay View alpha; blur radius itself does not depend on scroll distance. */
internal fun settingsBlurSampleAlpha(scrollAlpha: Float): Float = scrollAlpha.coerceIn(0f, 1f)

enum class SettingsSearchSample { PAGE, APP_BAR }

/** Select the scene rendered behind the search material, including the AppBar overlay. */
fun settingsSearchSample(overlayAlpha: Float): SettingsSearchSample =
    if (overlayAlpha > 0f) SettingsSearchSample.APP_BAR else SettingsSearchSample.PAGE
