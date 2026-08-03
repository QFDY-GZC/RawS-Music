package com.rawsmusic

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer
import com.rawsmusic.module.player.dsp.RealtimeSpectrumPipeline

/** Coordinates the Activity-side lifecycle of the realtime PCM visualizer. */
internal class MainActivityVisualizerCoordinator(
    private val pipeline: RealtimeSpectrumPipeline,
    private val isActivityForeground: () -> Boolean,
    private val isUiRequested: () -> Boolean,
    private val setUiRequested: (Boolean) -> Unit,
    private val isEnabled: () -> Boolean,
    private val setEnabled: (Boolean) -> Unit,
    private val hasPermission: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val setSpectrum: (FloatArray) -> Unit,
) {
    private fun emptySpectrum() = FloatArray(NativeStereoSpectrumAnalyzer.OUTPUT_SIZE)

    fun bind(controller: PlayerController) {
        controller.onPcmWaveformFrame = waveform@{
                buffer, read, channels, sampleRate, validBitsPerSample, sampleEncoding ->
            if (!isEnabled() || !isActivityForeground() || !isUiRequested()) {
                return@waveform
            }
            pipeline.submit(
                buffer = buffer,
                read = read,
                channels = channels,
                sampleRate = sampleRate,
                sampleEncoding = sampleEncoding,
                validBitsPerSample = validBitsPerSample,
            )
        }
    }

    fun applyEnabled(enabled: Boolean, reason: String) {
        setEnabled(enabled)
        AppPreferences.UI.isAudioVisualizerEnabled = enabled
        if (!enabled) {
            setUiRequested(false)
            setSpectrum(emptySpectrum())
        }
        updateRuntime(reason)
    }

    fun syncPreference(reason: String) {
        val enabled = AppPreferences.UI.isAudioVisualizerEnabled && hasPermission()
        setEnabled(enabled)
        if (!enabled && AppPreferences.UI.isAudioVisualizerEnabled) {
            AppPreferences.UI.isAudioVisualizerEnabled = false
        }
        if (!enabled) setUiRequested(false)
        updateRuntime(reason)
    }

    fun updateRuntime(reason: String) {
        val active = isEnabled() && isActivityForeground() && isUiRequested()
        pipeline.setPlaying(isPlaying())
        pipeline.setActive(active)
        if (!active) {
            setSpectrum(emptySpectrum())
        }
        AppLogger.d("AudioVisualizer", "runtime active=$active reason=$reason")
    }

    fun stopAndReset() {
        pipeline.setActive(false)
        setSpectrum(emptySpectrum())
    }
}
