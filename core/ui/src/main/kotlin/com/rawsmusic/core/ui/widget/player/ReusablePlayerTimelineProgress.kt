package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences

/** Shared player timeline renderer used by portrait and landscape player surfaces. */
@Composable
fun ReusablePlayerTimelineProgress(
    styleValue: Int,
    currentSong: AudioFile?,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    spectrum: FloatArray = FloatArray(0),
    spectrumState: State<FloatArray>? = null,
    climaxEnabled: Boolean,
    waveformRemainingColor: Color,
    waveformPlayedColor: Color,
    waveformClimaxColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
) {
    val waveformBarCount by PlayerProgressPreferences.waveformBarCount.collectAsState()
    when (ImmersiveProgressStyle.from(styleValue)) {
        ImmersiveProgressStyle.Classic -> ClassicTimelineProgress(
            currentSong = currentSong,
            isPlaying = isPlaying,
            coverPath = coverPath,
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
            waveformBarCount = waveformBarCount,
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

        ImmersiveProgressStyle.MusicSpine -> MusicSpineTimelineProgress(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            isPlaying = isPlaying,
            spectrum = spectrum,
            spectrumState = spectrumState,
            playedColor = waveformPlayedColor,
            remainingColor = waveformRemainingColor,
            timeColor = waveformRemainingColor.copy(alpha = 0.72f),
            onSeekStart = onSeekStart,
            onSeekStop = onSeekStop,
            modifier = modifier,
        )
    }
}
