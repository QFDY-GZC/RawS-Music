package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** Retires playback-owned resources after transient state has been reset. */
internal class PlaybackResourceStopCoordinator(
    private val cancelPlaybackWorker: () -> Unit,
    private val retireDecoder: () -> Unit,
    private val closeNextDecoder: () -> Unit,
    private val closeDecoderPath: () -> Unit,
    private val clearUsbPostStartRestoreGate: () -> Unit,
    private val invalidatePlaybackSession: () -> Unit,
    private val releaseAudioTrack: () -> Unit,
    private val closeNativeAudioEngine: () -> Unit,
    private val isUsbExclusive: () -> Boolean,
    private val releaseDsp: () -> Unit,
    private val setStopped: () -> Unit,
    private val tag: String,
) {
    fun stop(reason: String) {
        cancelPlaybackWorker()

        // Request the old decoder to retire before invalidating the session. If the decoder
        // thread is already gone but kept an EOF handle for seek, the retire helper closes it.
        retireDecoder()
        closeNextDecoder()
        closeDecoderPath()
        clearUsbPostStartRestoreGate()
        invalidatePlaybackSession()

        releaseAudioTrack()
        closeNativeAudioEngine()

        if (isUsbExclusive()) {
            // USB engine lifecycle is owned by PlayerController, not by stop(). Calling
            // UsbAudioEngine.stop() here would tear down the native engine during normal
            // pause/seek/settings changes and cause the next write to fail.
            AppLogger.i(
                tag,
                "stop(): USB exclusive active, decoder stopped; USB engine lifecycle owned by PlayerController",
            )
        }

        releaseDsp()
        setStopped()
        AppLogger.d(tag, "playback resources stopped reason=$reason")
    }
}
