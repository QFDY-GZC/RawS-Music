package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Owns in-place Android AudioTrack recreation when the output route changes.
 *
 * Decoder/session ownership stays in [FfmpegAudioPlayer]; this class only
 * rebuilds the platform track and reports the replacement back to the host.
 */
internal class AndroidAudioTrackRebuildCoordinator(
    private val context: Context,
    private val tag: String,
    private val audioTrackFactory: AndroidAudioTrackFactory,
    private val audioTrackLifecycle: AudioTrackLifecycleController,
    private val routeController: AndroidAudioRouteController,
    private val readSampleRate: () -> Int,
    private val readChannels: () -> Int,
    private val readEncoding: () -> Int,
    private val stateName: () -> String,
    private val shouldResumePlayback: () -> Boolean,
    private val createWithFallback: (
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
        attributes: android.media.AudioAttributes,
    ) -> AudioTrack?,
    private val onTrackCreated: (AudioTrack, sampleRate: Int, channelConfig: Int, encoding: Int) -> Unit,
    private val disableHardwarePositionTracking: () -> Unit,
) {
    fun recreate(
        forceSco: Boolean = false,
        forcedDevice: AudioDeviceInfo? = null,
    ): AudioTrack? {
        val useSco = AudioOutputManager.shouldUseScoMode(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val scoActive = forceSco || (audioManager?.isBluetoothScoOn == true)
        val spec = audioTrackFactory.buildSpec(
            wavSampleRate = readSampleRate(),
            wavChannels = readChannels(),
            probedEncoding = readEncoding(),
            useSco = useSco,
            scoActive = scoActive,
            applyScoDownsample = false,
        )
        val shouldResume = shouldResumePlayback()
        AppLogger.w(
            tag,
            "=== recreateAudioTrackInline: sr=${spec.sampleRate}, ch=${spec.channelConfig}, " +
                "enc=${spec.encoding}, sco=$useSco, scoActive=$scoActive, " +
                "useScoAttrs=${spec.useScoAttributes}, " +
                "forcedDevice=${forcedDevice?.shortRouteName() ?: "none"} " +
                "shouldResume=$shouldResume state=${stateName()} ===",
        )

        audioTrackLifecycle.detachAndRelease(
            reason = "recreate_audio_track_inline",
            stop = true,
            flush = true,
        )

        return try {
            val attributes = spec.audioAttributes
            AppLogger.i(
                tag,
                "recreateAudioTrackInline: audioAttributes usage=${attributes.usage}, " +
                    "contentType=${attributes.contentType}",
            )
            val newTrack = createWithFallback(
                spec.sampleRate,
                spec.channelConfig,
                spec.encoding,
                spec.bufferSizeInBytes,
                attributes,
            )
            if (newTrack == null) {
                AppLogger.e(tag, "recreateAudioTrackInline: createAudioTrackWithFallback returned null")
                return null
            }

            routeController.applyPreferredDeviceToAudioTrack(
                reason = "recreateAudioTrackInline",
                useScoAttributes = spec.useScoAttributes,
                allowDirectPreferredDevice = !useSco,
                forcedDevice = forcedDevice,
                trackOverride = newTrack,
            )
            onTrackCreated(newTrack, spec.sampleRate, spec.channelConfig, spec.encoding)
            setTrackPlaybackState(newTrack, shouldResume)
            disableHardwarePositionTracking()
            AppLogger.w(
                tag,
                "recreateAudioTrackInline SUCCESS, sessionId=${newTrack.audioSessionId} " +
                    "resumed=$shouldResume",
            )
            newTrack
        } catch (error: Throwable) {
            AppLogger.e(tag, "recreateAudioTrackInline EXCEPTION", error)
            null
        }
    }

    fun rebuildForSco() {
        if (!shouldResumePlayback()) {
            AppLogger.w(tag, "rebuildAudioTrackForSco: not playing, skip")
            return
        }
        AppLogger.i(tag, "rebuildAudioTrackForSco: rebuilding AudioTrack for SCO routing")
        val newTrack = recreate(forceSco = true)
        if (newTrack != null) {
            AppLogger.i(tag, "rebuildAudioTrackForSco: success, new sessionId=${newTrack.audioSessionId}")
        } else {
            AppLogger.e(tag, "rebuildAudioTrackForSco: failed to create new AudioTrack")
        }
    }

    fun rebuildAfterScoDisconnected() {
        val state = stateName()
        if (state != "PLAYING" && state != "PAUSED") {
            AppLogger.w(tag, "rebuildAudioTrackForScoDisconnected: state=$state, skip")
            return
        }
        AppLogger.i(tag, "rebuildAudioTrackForScoDisconnected: rebuilding AudioTrack with MEDIA attributes")
        val newTrack = recreate(forceSco = false)
        if (newTrack != null) {
            AppLogger.i(
                tag,
                "rebuildAudioTrackForScoDisconnected: success, new sessionId=${newTrack.audioSessionId}",
            )
        } else {
            AppLogger.e(tag, "rebuildAudioTrackForScoDisconnected: failed to create new AudioTrack")
        }
    }

    private fun setTrackPlaybackState(track: AudioTrack, shouldResume: Boolean) {
        if (shouldResume) {
            track.play()
        } else {
            runCatching { track.pause() }
        }
    }
}
