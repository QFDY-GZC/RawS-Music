package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine
import java.io.File

/**
 * Owns output-route callbacks and AudioTrack rebuild policy for FfmpegAudioPlayer.
 * Decoder/session state is supplied through callbacks so the coordinator cannot accidentally
 * become a second playback owner.
 */
internal class FfmpegAudioOutputLifecycleCoordinator(
    private val context: Context,
    private val tag: String,
    private val routeController: AndroidAudioRouteController,
    private val isReleased: () -> Boolean,
    private val isUsbExclusiveMode: () -> Boolean,
    private val state: () -> FfmpegAudioPlayer.State,
    private val isPlaying: () -> Boolean,
    private val positionMs: () -> Long,
    private val currentPath: () -> String?,
    private val sourcePath: () -> String?,
    private val tempWavFile: () -> File?,
    private val resampledPath: () -> String?,
    private val wavSampleRate: () -> Int,
    private val wavChannels: () -> Int,
    private val wavBitsPerSample: () -> Int,
    private val wavDataSize: () -> Long,
    private val generation: () -> Int,
    private val audioTrackProvider: () -> AudioTrack?,
    private val rebuildTempWav: (RebuildRequest, (String) -> Unit) -> Unit,
    private val rebuildStreaming: (RebuildRequest, (String) -> Unit) -> Unit,
    private val attemptUsbRecovery: (Int, Int, Int, String, Int) -> Boolean,
    private val rebuildForSco: () -> Unit,
    private val rebuildAfterScoDisconnected: () -> Unit,
    private val spatialRebuildPending: () -> Boolean,
    private val setSpatialRebuildPending: (Boolean) -> Unit,
) {
    data class RebuildRequest(
        val positionMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataSize: Long,
        val seekPath: String,
        val originalSourcePath: String,
        val generation: Int,
    )

    fun registerAudioDeviceCallback() = routeController.registerAudioDeviceCallback()

    fun unregisterAudioDeviceCallback() = routeController.unregisterAudioDeviceCallback()

    fun rebuildAudioTrack() {
        val rebuildStart = System.nanoTime()
        fun lap(name: String) {
            val elapsedMs = (System.nanoTime() - rebuildStart) / 1_000_000.0
            AppLogger.w(tag, "rebuildAudioTrack lap[$name] = ${"%.1f".format(elapsedMs)}ms")
        }

        AppLogger.w(
            tag,
            "=== rebuildAudioTrack called: state=${state()}, isPlaying=${isPlaying()}, " +
                "pos=${positionMs()}ms, audioTrack=${audioTrackProvider() != null} ==="
        )
        val path = currentPath() ?: return
        val position = positionMs()
        lap("init")
        val request = RebuildRequest(
            positionMs = position,
            sampleRate = wavSampleRate(),
            channels = wavChannels(),
            bitsPerSample = wavBitsPerSample(),
            dataSize = wavDataSize(),
            seekPath = if (position > 0 && isUsbExclusiveMode()) resampledPath() ?: path else path,
            originalSourcePath = sourcePath() ?: path,
            generation = generation(),
        )

        if (tempWavFile() != null) {
            rebuildTempWav(request, ::lap)
            return
        }

        AppLogger.i(tag, "rebuildAudioTrack: streaming mode, restarting decoder from ${position}ms")
        rebuildStreaming(request, ::lap)
    }

    fun rebuildAfterSourceFileMutation() {
        AppLogger.i(tag, "Metadata source replaced; rebuilding decoder and native output at ${positionMs()} ms")
        rebuildAudioTrack()
    }

    fun onAndroidSpatialAudioPreferenceChanged(): Boolean {
        if (isUsbExclusiveMode()) {
            AppLogger.i(tag, "Android spatial audio change ignored for USB exclusive output")
            return false
        }
        val outputMode = AudioOutputManager.getCurrentOutputMode(context)
        if (!AndroidSpatialAudio.backendSupportsExplicitBehavior(outputMode)) {
            AppLogger.i(tag, "Android spatial audio stored but backend has no explicit behavior: mode=$outputMode")
            return false
        }
        return when (state()) {
            FfmpegAudioPlayer.State.PLAYING -> {
                setSpatialRebuildPending(false)
                AppLogger.i(tag, "Android spatial audio changed during playback; rebuilding output")
                rebuildAudioTrack()
                true
            }

            FfmpegAudioPlayer.State.PAUSED -> {
                setSpatialRebuildPending(true)
                AppLogger.i(tag, "Android spatial audio changed while paused; rebuild deferred until resume")
                true
            }

            else -> {
                setSpatialRebuildPending(false)
                AppLogger.i(tag, "Android spatial audio changed while idle; next stream will use new policy")
                false
            }
        }
    }

    fun ensureTrackValidAfterBackground(): Boolean {
        AppLogger.i(tag, "ensureTrackValidAfterBackground: state=${state()}, usbExclusive=${isUsbExclusiveMode()}")
        if (isUsbExclusiveMode()) {
            if (!UsbAudioEngine.isRunning()) {
                AppLogger.w(tag, "USB engine not running after background, attempting recovery")
                val path = sourcePath() ?: currentPath() ?: return false
                return attemptUsbRecovery(wavSampleRate(), wavBitsPerSample(), wavChannels(), path, 0)
            }
            return false
        }

        val track = audioTrackProvider()
        when {
            track == null -> {
                AppLogger.w(tag, "AudioTrack is null after background, rebuilding")
                rebuildAudioTrack()
                return true
            }

            track.playState == AudioTrack.PLAYSTATE_STOPPED -> {
                AppLogger.w(tag, "AudioTrack STOPPED after background, rebuilding")
                rebuildAudioTrack()
                return true
            }

            track.state == AudioTrack.STATE_UNINITIALIZED -> {
                AppLogger.w(tag, "AudioTrack UNINITIALIZED after background, rebuilding")
                rebuildAudioTrack()
                return true
            }
        }
        return false
    }

    fun rebuildAudioTrackForSco() = rebuildForSco()

    fun rebuildAudioTrackForScoDisconnected() = rebuildAfterScoDisconnected()

    fun isSpatialAudioRebuildPending(): Boolean = spatialRebuildPending()
}
