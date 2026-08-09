package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/**
 * Smooths the player callback clock without adding a lyric offset.
 *
 * The player position is the only source of truth. Between callbacks we only
 * interpolate from the latest callback to the current frame; a new callback
 * always replaces the anchor immediately, which also makes seeks exact.
 */
@Composable
internal fun rememberLyricTimelinePosition(
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
): Long {
    val latestPosition by rememberUpdatedState(positionMs.coerceAtLeast(0L))
    val latestPlaying by rememberUpdatedState(isPlaying)
    val latestDuration by rememberUpdatedState(durationMs)
    val timelinePosition by produceState(
        initialValue = latestPosition,
        key1 = isPlaying,
        key2 = durationMs,
    ) {
        var lastSourcePosition = latestPosition
        var anchorPosition = lastSourcePosition
        var anchorFrameNs = 0L
        value = clampLyricPosition(lastSourcePosition, latestDuration)

        while (isActive) {
            val frameNs = withFrameNanos { it }
            val sourcePosition = latestPosition
            val sourceDuration = latestDuration

            if (sourcePosition != lastSourcePosition) {
                lastSourcePosition = sourcePosition
                anchorPosition = sourcePosition
                anchorFrameNs = frameNs
                value = clampLyricPosition(sourcePosition, sourceDuration)
                continue
            }

            if (!latestPlaying) {
                anchorPosition = sourcePosition
                anchorFrameNs = frameNs
                value = clampLyricPosition(sourcePosition, sourceDuration)
                continue
            }

            if (anchorFrameNs == 0L) {
                anchorPosition = sourcePosition
                anchorFrameNs = frameNs
            }
            val elapsedMs = ((frameNs - anchorFrameNs) / 1_000_000L).coerceAtLeast(0L)
            value = clampLyricPosition(anchorPosition + elapsedMs, sourceDuration)
        }
    }
    return timelinePosition
}

private fun clampLyricPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
