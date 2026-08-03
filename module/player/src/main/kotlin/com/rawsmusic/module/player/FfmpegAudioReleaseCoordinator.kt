package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes the terminal resource-release order of FfmpegAudioPlayer. */
internal class FfmpegAudioReleaseCoordinator(
    private val tag: String,
    private val released: AtomicBoolean,
    private val clearFade: (String) -> Unit,
    private val closeDecoderPath: (String) -> Unit,
    private val clearUsbPostStartVolumeRestore: (String) -> Unit,
    private val unregisterAudioDeviceCallback: () -> Unit,
    private val cancelPlaybackWorker: () -> Unit,
    private val resetPlaybackState: () -> Unit,
    private val closeNextDecoder: () -> Unit,
    private val nextDecoderExecutor: ExecutorService,
    private val resetDecoderState: () -> Unit,
    private val invalidatePlaybackSession: () -> Unit,
    private val shutdownPlaybackWorker: () -> Unit,
    private val releaseAudioTrack: () -> Unit,
    private val releaseNativeAudioEngine: () -> Unit,
    private val clearUsbExclusiveMode: () -> Unit,
    private val releaseDspEngine: () -> Unit,
    private val closePcmConversion: () -> Unit,
    private val clearPlaybackFiles: () -> Unit,
    private val setIdle: () -> Unit,
) {
    fun release() {
        AppLogger.w(tag, "=== release() called")
        if (released.getAndSet(true)) return

        clearFade("release")
        closeDecoderPath("release")
        clearUsbPostStartVolumeRestore("release")
        unregisterAudioDeviceCallback()

        cancelPlaybackWorker()
        resetPlaybackState()
        closeNextDecoder()
        nextDecoderExecutor.shutdownNow()
        resetDecoderState()
        invalidatePlaybackSession()

        shutdownPlaybackWorker()
        releaseAudioTrack()
        releaseNativeAudioEngine()
        clearUsbExclusiveMode()
        releaseDspEngine()
        closePcmConversion()
        clearPlaybackFiles()
        setIdle()
    }
}
