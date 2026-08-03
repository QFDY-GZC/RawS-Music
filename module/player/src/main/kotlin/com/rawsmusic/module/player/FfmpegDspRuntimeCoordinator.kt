package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.dsp.FfmpegDspCoordinator

/** Keeps DSP lifecycle and realtime-stem track boundaries out of the audio I/O class. */
internal class FfmpegDspRuntimeCoordinator(
    private val tag: String,
    private val processor: FfmpegDspCoordinator,
    private val pcmOutputConversion: PcmOutputConversionController,
    private val doublePrecisionEnabled: () -> Boolean,
    private val markAudioTrackFlush: () -> Unit,
    private val flushNativePcm: (String) -> Unit,
    private val disableHardwarePositionTracking: () -> Unit,
) {
    fun init(sampleRate: Int, channels: Int) {
        processor.internalDoublePrecisionProcessing = doublePrecisionEnabled()
        processor.init(sampleRate, channels)
        pcmOutputConversion.configure(sampleRate, channels)
    }

    fun release() {
        processor.release()
    }

    fun process(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = processor.process(buffer, read, channels, sampleRate, bitsPerSample)

    fun processAfterRealtime(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = processor.processAfterRealtime(buffer, read, channels, sampleRate, bitsPerSample)

    fun resetRealtimeSeparationAtTrackBoundary(reason: String): Boolean {
        if (!RealtimePlaybackPcmProcessorRegistry.isActive()) return false
        RealtimePlaybackPcmProcessorRegistry.reset(reason)
        markAudioTrackFlush()
        flushNativePcm(reason)
        disableHardwarePositionTracking()
        AppLogger.i(tag, "AI realtime track boundary reset: reason=$reason")
        return true
    }
}
