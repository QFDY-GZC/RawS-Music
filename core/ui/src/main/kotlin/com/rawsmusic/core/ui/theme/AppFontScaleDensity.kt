package com.rawsmusic.core.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * Applies the app's font-size multiplier without replacing Android's platform font converter.
 *
 * `Density(density, fontScale)` is linear on Android. Wrapping the context-provided density keeps
 * its API 34+ non-linear SP conversion intact and applies the app preference after that conversion.
 */
internal fun Density.withAppFontScale(scale: Float): Density {
    val safeScale = scale.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
    return if (safeScale == 1f) this else AppFontScaledDensity(this, safeScale)
}

private data class AppFontScaledDensity(
    private val platformDensity: Density,
    private val appScale: Float,
) : Density {
    override val density: Float
        get() = platformDensity.density

    override val fontScale: Float
        get() = platformDensity.fontScale * appScale

    override fun TextUnit.toDp(): Dp = with(platformDensity) {
        this@toDp.toDp() * appScale
    }

    override fun Dp.toSp(): TextUnit = with(platformDensity) {
        (this@toSp / appScale).toSp()
    }
}
