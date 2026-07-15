package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioFormat
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.isLikelyDsdSource

/**
 * Resolves the decoder target format for normal Android playback.
 *
 * This keeps AudioTrack/AAudio/DIRECT capability policy out of the main
 * playback session code. It intentionally does not handle USB exclusive mode.
 */
internal class AndroidPlaybackTargetResolver(
    private val context: Context,
    private val tag: String
) {
    data class Target(
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val encoding: Int,
        val sourceRate: Int,
        val userTargetRate: Int,
        val preferredRate: Int,
        val cappedTargetRate: Int,
        val rawEncoding: Int,
        val useSco: Boolean,
        val outputMode: AudioOutputMode,
        val sharedMixerMode: Boolean,
        val sourceIsDsd: Boolean
    )

    fun resolve(sourcePath: String): Target {
        val useSco = AudioOutputManager.shouldUseScoMode(context)
        val userTargetRate = AudioOutputManager.getTargetSampleRate()
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val outputMode = AudioOutputManager.getCurrentOutputMode(context)
        val sharedMixerMode = outputMode != AudioOutputMode.DIRECT
        val rawSourceRate = FFmpegBridge.probeSampleRate(sourcePath)
        val sourceBits = FFmpegBridge.probeBitsPerSample(sourcePath)
        val sourceIsDsd = isLikelyDsdSource(sourcePath, sourceBits, rawSourceRate)
        val sourceRate = rawSourceRate.takeIf { it > 0 } ?: 44_100
        val minRate = AudioOutputManager.getMinSampleRateForMode(outputMode)
        val preferredRate = (if (userTargetRate > 0) userTargetRate else sourceRate)
            .coerceAtLeast(minRate)
        // v6d: 不再把 AAudio/OpenSL 共享输出统一压到 48kHz。
        // AudioTrack 普通模式固定为系统混音高兼容 lane，限制到 48kHz/24bit/stereo。
        val maxRate = AudioOutputManager.getMaxSampleRateForMode(outputMode)
        val cappedTargetRate = if (preferredRate > maxRate) maxRate else preferredRate
        val (probedRate, rawEncoding) = AudioOutputManager.probeRateAndEncoding(
            cappedTargetRate,
            channelConfig,
            context
        )
        val aaudioPacked24 = outputMode == AudioOutputMode.AAUDIO &&
            AudioOutputManager.pcm24PackedEncodingOrNull()?.let { rawEncoding == it } == true
        val adjustedEncoding = when {
            aaudioPacked24 && android.os.Build.VERSION.SDK_INT >= 26 -> AudioFormat.ENCODING_PCM_FLOAT
            aaudioPacked24 -> AudioFormat.ENCODING_PCM_16BIT
            outputMode == AudioOutputMode.OPENSL_ES -> AudioFormat.ENCODING_PCM_16BIT
            outputMode == AudioOutputMode.AUDIO_TRACK -> when (rawEncoding) {
                AudioFormat.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                else -> if (android.os.Build.VERSION.SDK_INT >= 26) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            }
            else -> rawEncoding
        }
        if (aaudioPacked24) {
            AppLogger.w(
                tag,
                "AAudio packed-24 output avoided: rawEncoding=$rawEncoding " +
                    "adjustedEncoding=$adjustedEncoding rate=$probedRate"
            )
        }

        var targetRate = probedRate
        var targetEncoding = adjustedEncoding
        var targetBits = AudioOutputManager.encodingToFFmpegBits(adjustedEncoding)
        val targetChannels = if (useSco) 1 else 2

        if (useSco) {
            // SCO path only supports narrowband/wideband PCM. Keep decoder output
            // in the exact format the AudioTrack path will request.
            targetEncoding = AudioFormat.ENCODING_PCM_16BIT
            targetBits = 16
            if (AppPreferences.Player.bluetoothScoDownsample) {
                targetRate = 16_000
            }
            AppLogger.i(tag, "SCO mode: forcing FFmpeg output to ${targetRate}Hz/16bit/mono")
        }

        AppLogger.i(
            tag,
            "Device capability: sourceRate=$sourceRate userTargetRate=$userTargetRate " +
                "preferredRate=$preferredRate effectiveRate=$cappedTargetRate " +
                "verifiedRate=$targetRate rawEncoding=$rawEncoding encoding=$targetEncoding " +
                "-> bits=$targetBits, sco=$useSco, mode=$outputMode, shared=$sharedMixerMode, sourceDsd=$sourceIsDsd"
        )

        return Target(
            sampleRate = targetRate,
            bitsPerSample = targetBits,
            channels = targetChannels,
            encoding = targetEncoding,
            sourceRate = sourceRate,
            userTargetRate = userTargetRate,
            preferredRate = preferredRate,
            cappedTargetRate = cappedTargetRate,
            rawEncoding = rawEncoding,
            useSco = useSco,
            outputMode = outputMode,
            sharedMixerMode = sharedMixerMode,
            sourceIsDsd = sourceIsDsd
        )
    }
}
