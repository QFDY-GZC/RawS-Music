package com.rawsmusic.module.player

import android.media.AudioFormat
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Snapshots AudioTrack format (sample rate / channel config / encoding) so that
 * resume / route-repair paths can detect a decoder format change and trigger a
 * proactive AudioTrack rebuild.
 */
internal class AudioTrackFormatMonitor(private val tag: String) {
    @Volatile private var sampleRate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = 0

    fun snapshot(sampleRate: Int, channelConfig: Int, encoding: Int) {
        this.sampleRate = sampleRate
        this.channels = channelConfig
        this.encoding = encoding
        AppLogger.d(tag, "Track format snapshot: sr=$sampleRate, ch=$channelConfig, enc=$encoding")
    }

    fun hasChanged(wavSampleRate: Int, wavChannels: Int, probedEncoding: Int): Boolean {
        if (sampleRate == 0) return false  // never snapshotted → not a change
        val currentCh = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        return sampleRate != wavSampleRate ||
               channels != currentCh ||
               encoding != probedEncoding
    }

    fun snapshotDescription(): String = "$sampleRate/$channels/$encoding"

    fun currentDescription(wavSampleRate: Int, wavChannels: Int, probedEncoding: Int): String {
        val currentCh = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        return "$wavSampleRate/$currentCh/$probedEncoding"
    }
}
