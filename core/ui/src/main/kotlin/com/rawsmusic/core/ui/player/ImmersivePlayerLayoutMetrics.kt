package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

internal data class ImmersivePlayerLayoutMetrics(
    val titleTop: Dp,
    val lyricPreviewHeight: Dp,
    val lyricPreviewRows: Int,
    val progressBottomPadding: Dp
)

/**
 * Resolves the vertical player layout from measured children instead of fixed preview heights.
 * This keeps the mini lyric viewport between the title block and the actual progress/transport
 * bounds on short screens, large-font devices, and every immersive progress style.
 */
internal fun resolveImmersivePlayerLayoutMetrics(
    viewportHeight: Dp,
    clearArtworkBottom: Dp,
    titleInfoHeight: Dp,
    progressPanelHeight: Dp,
    transportControlsHeight: Dp,
    fontScale: Float,
    hasSecondaryText: Boolean
): ImmersivePlayerLayoutMetrics {
    val safeViewport = viewportHeight.coerceAtLeast(1.dp)
    val titleTop = (clearArtworkBottom + 6.dp)
        .coerceAtLeast(116.dp)
        .coerceAtMost((safeViewport - 180.dp).coerceAtLeast(116.dp))

    val progressBottomPadding = transportControlsHeight.coerceAtLeast(56.dp) + 8.dp
    val progressTop = safeViewport - progressBottomPadding - progressPanelHeight.coerceAtLeast(64.dp)
    val lyricTop = titleTop + titleInfoHeight.coerceAtLeast(52.dp)
    val available = (progressTop - lyricTop - 10.dp).coerceAtLeast(0.dp)
    val lyricPreviewHeight = available.coerceAtMost(292.dp)

    val estimatedPrimaryRowHeight =
        (if (hasSecondaryText) 44f else 31f) * fontScale.coerceIn(0.85f, 1.45f)
    val lyricPreviewRows = if (lyricPreviewHeight <= 0.dp) {
        1
    } else {
        floor(lyricPreviewHeight.value / estimatedPrimaryRowHeight)
            .toInt()
            .coerceIn(1, if (hasSecondaryText) 3 else 5)
    }

    return ImmersivePlayerLayoutMetrics(
        titleTop = titleTop,
        lyricPreviewHeight = lyricPreviewHeight,
        lyricPreviewRows = lyricPreviewRows,
        progressBottomPadding = progressBottomPadding
    )
}

internal fun fallbackImmersiveProgressPanelHeight(style: ImmersiveProgressStyle): Dp = when (style) {
    ImmersiveProgressStyle.Classic -> 88.dp
    ImmersiveProgressStyle.Waveform -> 112.dp
    ImmersiveProgressStyle.Seconds -> 104.dp
}
