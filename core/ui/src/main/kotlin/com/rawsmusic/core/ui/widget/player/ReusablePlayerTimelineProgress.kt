package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rawsmusic.core.common.model.AudioFile

/** Shared player timeline renderer used by portrait and landscape player surfaces. */
@Composable
fun ReusablePlayerTimelineProgress(
    styleValue: Int,
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    climaxEnabled: Boolean,
    waveformDebugPanel: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (ImmersiveProgressStyle.from(styleValue)) {
        ImmersiveProgressStyle.Classic -> ClassicTimelineProgress(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            modifier = modifier,
            trackColor = waveformPlayedColor,
            fillColor = waveformRemainingColor,
            timeColor = waveformRemainingColor.copy(alpha = 0.72f),
        )

        ImmersiveProgressStyle.Waveform -> WindowWaveformTimelineProgress(
            currentSong = currentSong,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            waveformRemainingColor = waveformRemainingColor,
            waveformPlayedColor = waveformPlayedColor,
            waveformClimaxColor = waveformClimaxColor,
            climaxEnabled = climaxEnabled,
            showDebugPanel = waveformDebugPanel,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            modifier = modifier,
        )

        ImmersiveProgressStyle.Seconds -> SecondSpectrumTimelineProgress(
            currentSong = currentSong,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            waveformRemainingColor = waveformRemainingColor,
            waveformPlayedColor = waveformPlayedColor,
            waveformClimaxColor = waveformClimaxColor,
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            modifier = modifier,
        )
    }
}
