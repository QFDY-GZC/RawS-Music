package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Builds and creates Android AudioTrack instances for the regular Android output path.
 *
 * FfmpegAudioPlayer still owns decoder state, playback loops, and route decisions. This class
 * only owns the repeated AudioTrack format/buffer/fallback mechanics so those rules stay in one
 * place across initial playback, inline rebuild, and legacy file playback.
 */
internal class AndroidAudioTrackFactory(private val context: Context) {
    companion object {
        private const val TAG = "AndroidAudioTrackFactory"
        private const val DEFAULT_PCM_BUFFER_SIZE = 8192

        fun encodingForWavData(wavBitsPerSample: Int): Int {
            return when {
                wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT in 26..28 ->
                    AudioFormat.ENCODING_PCM_16BIT
                wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT >= 26 ->
                    AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
        }
    }

    data class Spec(
        val sampleRate: Int,
        val channelConfig: Int,
        val encoding: Int,
        val bufferSizeInBytes: Int,
        val audioAttributes: AudioAttributes,
        val useSco: Boolean,
        val scoActive: Boolean,
        val useScoAttributes: Boolean
    )

    fun buildSpec(
        wavSampleRate: Int,
        wavChannels: Int,
        probedEncoding: Int,
        useSco: Boolean,
        scoActive: Boolean,
        usbExclusiveMode: Boolean = false,
        wavBitsPerSample: Int = 16,
        applyScoDownsample: Boolean = false,
        scoDownsampleEnabled: Boolean = false
    ): Spec {
        val useScoAttributes = useSco && scoActive
        val channelConfig = when {
            useScoAttributes -> AudioFormat.CHANNEL_OUT_MONO
            wavChannels == 1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        var sampleRate = when {
            usbExclusiveMode -> wavSampleRate.coerceAtLeast(44100)
            useScoAttributes -> wavSampleRate.coerceAtLeast(8000)
            else -> wavSampleRate.coerceAtLeast(44100)
        }
        if (useScoAttributes && applyScoDownsample && scoDownsampleEnabled) {
            sampleRate = 16000
        }

        val encoding = when {
            usbExclusiveMode -> encodingForWavData(wavBitsPerSample)
            useScoAttributes -> AudioFormat.ENCODING_PCM_16BIT
            else -> probedEncoding
        }

        val audioAttributes = if (useScoAttributes) {
            AudioOutputManager.buildScoAudioAttributes()
        } else {
            AudioOutputManager.buildMediaAudioAttributes(context)
        }

        return Spec(
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            encoding = encoding,
            bufferSizeInBytes = minBufferSize(sampleRate, channelConfig, encoding),
            audioAttributes = audioAttributes,
            useSco = useSco,
            scoActive = scoActive,
            useScoAttributes = useScoAttributes
        )
    }

    fun minBufferSize(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        fallbackBytes: Int = DEFAULT_PCM_BUFFER_SIZE
    ): Int {
        return try {
            AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                .coerceAtLeast(fallbackBytes)
        } catch (e: Exception) {
            AppLogger.w(TAG, "AudioTrack minBufferSize failed: sr=$sampleRate ch=$channelConfig enc=$encoding", e)
            fallbackBytes
        }
    }

    fun createWithFallback(spec: Spec, safeMode: Boolean): AudioTrack? {
        return createWithFallback(
            sampleRate = spec.sampleRate,
            channelConfig = spec.channelConfig,
            encoding = spec.encoding,
            bufferSize = spec.bufferSizeInBytes,
            audioAttributes = spec.audioAttributes,
            safeMode = safeMode
        )
    }

    fun createWithFallback(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
        audioAttributes: AudioAttributes,
        safeMode: Boolean
    ): AudioTrack? {
        val safeSampleRate = if (safeMode) 44100 else sampleRate
        val safeEncoding = if (safeMode) AudioFormat.ENCODING_PCM_16BIT else encoding

        // The streaming decoder has already been opened for this exact rate/encoding.
        // Do not silently create a different-rate AudioTrack here: that produces UI
        // progress with wrong/no audio, especially for 24-bit/192k. Format fallback
        // must happen before opening FFmpeg, in AudioOutputManager.probeRateAndEncoding().
        val fallbackRates = listOf(safeSampleRate)
        val fallbackEncodings = listOf(safeEncoding)

        for (rate in fallbackRates) {
            for (enc in fallbackEncodings) {
                var track: AudioTrack? = null
                try {
                    val minBuf = AudioTrack.getMinBufferSize(rate, channelConfig, enc)
                    val buf = if (minBuf <= 0) bufferSize else maxOf(minBuf, bufferSize)
                    val format = AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(enc)
                        .setChannelMask(channelConfig)
                        .build()
                    track = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(buf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT >= 26) {
                                val mode = AudioOutputManager.getCurrentOutputMode(context)
                                val performanceMode = when (mode) {
                                    AudioOutputMode.AAUDIO -> AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                                    AudioOutputMode.DIRECT -> AudioTrack.PERFORMANCE_MODE_NONE
                                    AudioOutputMode.OPENSL_ES -> AudioTrack.PERFORMANCE_MODE_POWER_SAVING
                                }
                                setPerformanceMode(performanceMode)
                            }
                        }
                        .build()
                    track.play()
                    track.pause()
                    AppLogger.i(
                        TAG,
                        "AudioTrack created: rate=$rate encoding=$enc " +
                            "(requested: rate=$sampleRate enc=$encoding), " +
                            "usage=${audioAttributes.usage}, contentType=${audioAttributes.contentType}"
                    )
                    return track
                } catch (e: Exception) {
                    track?.let { releaseQuietly(it) }
                    AppLogger.w(TAG, "AudioTrack fallback failed: rate=$rate enc=$enc", e)
                }
            }
        }
        AppLogger.e(TAG, "AudioTrack creation failed for all fallback combinations")
        return null
    }

    private fun releaseQuietly(track: AudioTrack) {
        try { track.release() } catch (_: Exception) {}
    }
}
