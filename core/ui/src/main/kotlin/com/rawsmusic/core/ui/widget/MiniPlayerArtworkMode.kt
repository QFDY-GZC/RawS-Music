package com.rawsmusic.core.ui.widget

import kotlin.math.max
import kotlin.math.min

enum class MiniPlayerArtworkMode {
    Normal,
    Vinyl,
    Original;

    fun toggle(): MiniPlayerArtworkMode = when (this) {
        Normal -> Vinyl
        Vinyl -> Original
        Original -> Normal
    }

    companion object {
        fun fromPrefs(value: String?): MiniPlayerArtworkMode {
            // Keep the historical v14 reversed values byte-compatible. Only the new Original
            // mode gets a new stored token.
            return when (value) {
                "vinyl" -> Normal
                "normal" -> Vinyl
                "original" -> Original
                else -> Vinyl
            }
        }

        fun toPrefs(mode: MiniPlayerArtworkMode): String = when (mode) {
            Normal -> "vinyl"
            Vinyl -> "normal"
            Original -> "original"
        }
    }
}

data class MiniPlayerArtworkSizeBounds(
    val minDp: Float,
    val maxDp: Float,
) {
    fun constrain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(minDp, maxDp) else minDp
}

private const val MINI_PLAYER_ARTWORK_MAX_DP = 64f
private const val MINI_PLAYER_ARTWORK_ABSOLUTE_MIN_DP = 18f
private const val MINI_PLAYER_ARTWORK_DYNAMIC_MIN_FRACTION = 0.45f
private const val MINI_PLAYER_ARTWORK_VERTICAL_BREATHING_ROOM_DP = 2f
private const val MINI_PLAYER_VINYL_HEIGHT_FACTOR = 52f / 44f
private const val MINI_PLAYER_VINYL_SLEEVE_RADIUS_FACTOR = 10f / 44f
private const val MINI_PLAYER_ORIGINAL_VISUAL_SCALE = 0.92f

fun miniPlayerArtworkSizeBounds(
    expandedHeightDp: Float,
    compactHeightDp: Float,
    mode: MiniPlayerArtworkMode,
): MiniPlayerArtworkSizeBounds {
    val expanded = if (expandedHeightDp.isFinite()) expandedHeightDp.coerceIn(36f, 104f) else 62f
    val compact = if (compactHeightDp.isFinite()) compactHeightDp.coerceIn(34f, 88f) else 54f
    val tightestHeight = min(expanded, compact)
    val availableEnvelope = max(
        1f,
        tightestHeight - MINI_PLAYER_ARTWORK_VERTICAL_BREATHING_ROOM_DP,
    )
    val envelopeFactor = when (mode) {
        MiniPlayerArtworkMode.Normal,
        MiniPlayerArtworkMode.Original -> 1f
        MiniPlayerArtworkMode.Vinyl -> MINI_PLAYER_VINYL_HEIGHT_FACTOR
    }
    val maxDp = min(MINI_PLAYER_ARTWORK_MAX_DP, availableEnvelope / envelopeFactor)
        .coerceAtLeast(MINI_PLAYER_ARTWORK_ABSOLUTE_MIN_DP)
    val minDp = max(
        MINI_PLAYER_ARTWORK_ABSOLUTE_MIN_DP,
        maxDp * MINI_PLAYER_ARTWORK_DYNAMIC_MIN_FRACTION,
    ).coerceAtMost(maxDp)
    return MiniPlayerArtworkSizeBounds(minDp = minDp, maxDp = maxDp)
}

fun effectiveMiniPlayerArtworkSizeDp(
    requestedArtworkSizeDp: Float,
    expandedHeightDp: Float,
    compactHeightDp: Float,
    mode: MiniPlayerArtworkMode,
): Float = miniPlayerArtworkSizeBounds(
    expandedHeightDp = expandedHeightDp,
    compactHeightDp = compactHeightDp,
    mode = mode,
).constrain(requestedArtworkSizeDp)

fun originalMiniPlayerArtworkVisualSizeDp(artworkSizeDp: Float): Float =
    (if (artworkSizeDp.isFinite()) artworkSizeDp.coerceAtLeast(0f) else 0f) *
        MINI_PLAYER_ORIGINAL_VISUAL_SCALE

fun miniPlayerArtworkSourceRadiusDp(
    mode: MiniPlayerArtworkMode,
    effectiveArtworkSizeDp: Float,
    originalArtworkCornerRadiusDp: Float,
): Float = when (mode) {
    MiniPlayerArtworkMode.Normal -> effectiveArtworkSizeDp.coerceAtLeast(0f) / 2f
    MiniPlayerArtworkMode.Vinyl ->
        effectiveArtworkSizeDp.coerceAtLeast(0f) * MINI_PLAYER_VINYL_SLEEVE_RADIUS_FACTOR
    MiniPlayerArtworkMode.Original -> min(
        if (originalArtworkCornerRadiusDp.isFinite()) originalArtworkCornerRadiusDp.coerceAtLeast(0f) else 0f,
        originalMiniPlayerArtworkVisualSizeDp(effectiveArtworkSizeDp) / 2f,
    )
}
