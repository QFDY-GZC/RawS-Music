package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Animated artwork replacement timing and alpha policy.
 *
 * The page/holder motion is the only animation owner once a holder already contains real artwork.
 * Provider quality upgrades and same-song pixel refreshes are installed atomically so the moving
 * holder never runs a second local dissolve on top of translation/scale/rotation.
 */
internal fun ReferenceInterruptedPreviousAlpha(
    previousStartAlpha: Float,
    replacementProgress: Float,
): Float = (
    previousStartAlpha.coerceIn(0f, 1f) - replacementProgress.coerceIn(0f, 1f)
).coerceAtLeast(0f)

internal fun ReferenceArtworkAlphaByte(alpha: Float): Int =
    (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

/**
 * Only a fallback/default-art holder may locally dissolve into the first real provider artwork.
 * Real -> real replacement is always atomic, regardless of low/high tier or equal decoded size.
 */
internal fun shouldAnimateArtworkReplacement(
    previousWasFallback: Boolean,
    nextIsFallback: Boolean,
): Boolean = previousWasFallback && !nextIsFallback

internal fun isFallbackArtworkSourceKey(sourceKey: String): Boolean =
    sourceKey.startsWith("default-art:")

internal const val Reference_AA_REPLACEMENT_MS = 200L

/**
 * Accept a provider callback only while the physical holder still owns the exact identity and
 * generation that issued it. This stays independent from transition tokens and central visual
 * state so a recycled holder can never accept a stale bitmap.
 */
internal fun shouldAcceptArtworkProviderResult(
    boundKey: String,
    currentGeneration: Long,
    callbackKey: String,
    callbackGeneration: Long,
): Boolean =
    boundKey.isNotBlank() &&
        boundKey == callbackKey &&
        currentGeneration == callbackGeneration
