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
    private val setEnabled: (Boolean) -> Unit,
    private val hasPermission: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val setSpectrum: (FloatArray) -> Unit,
) {
    private fun emptySpectrum() = FloatArray(NativeStereoSpectrumAnalyzer.OUTPUT_SIZE)
    private var boundController: PlayerController? = null

    fun bind(controller: PlayerController) {
        val previous = boundController
        if (previous === controller) {
            // Transport actions call ensureRuntimeController() even when the operational owner did
            // not change. Never drop the PCM consumer on that idempotent rebind: doing so used to
            // stop visualizer input after every next/previous command until PLAYER was re-entered.
            updateRuntime("same_controller_rebind")
            return
        }
        previous?.ffmpegPlayerRef?.setWaveformConsumerActive(false)
        previous?.onPcmWaveformFrame = null
        boundController = controller
        controller.onPcmWaveformFrame = waveform@{
                buffer, read, channels, sampleRate, validBitsPerSample, sampleEncoding ->
            // Realtime spectrum consumers are not limited to the optional visualizer overlay.
            // Music Spine uses the same internal PCM analysis even when that overlay is disabled.
            if (!isActivityForeground() || !isUiRequested()) {
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
        updateRuntime("controller_bind")
    }

    fun applyEnabled(enabled: Boolean, reason: String) {
        setEnabled(enabled)
        AppPreferences.UI.isAudioVisualizerEnabled = enabled
        // Compose owns whether any realtime-spectrum consumer is still visible. Do not tear down
        // the shared PCM analysis merely because the optional visualizer overlay was disabled.
        updateRuntime(reason)
    }

    fun syncPreference(reason: String) {
        val enabled = AppPreferences.UI.isAudioVisualizerEnabled && hasPermission()
        setEnabled(enabled)
        if (!enabled && AppPreferences.UI.isAudioVisualizerEnabled) {
            AppPreferences.UI.isAudioVisualizerEnabled = false
        }
        updateRuntime(reason)
    }

    fun updateRuntime(reason: String) {
        val active = isActivityForeground() && isUiRequested()
        boundController?.ffmpegPlayerRef?.setWaveformConsumerActive(active)
        pipeline.setPlaying(isPlaying())
        pipeline.setActive(active)
        if (!active) {
            setSpectrum(emptySpectrum())
        }
        AppLogger.d("AudioVisualizer", "runtime active=$active reason=$reason")
    }

    fun stopAndReset() {
        boundController?.ffmpegPlayerRef?.setWaveformConsumerActive(false)
        pipeline.setActive(false)
        setSpectrum(emptySpectrum())
    }
}
