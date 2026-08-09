package com.rawsmusic.module.player.usb

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger

/** Android-only discovery of the shared output scheduler profile. */
internal object UsbAndroidAudioSchedulerProfile {
    private const val TAG = "UsbAudioEngine"

    fun configure(context: Context, applyNative: (sampleRate: Int, framesPerBuffer: Int) -> Unit) {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it in 44_100..192_000 }
            ?: 48_000
        val propertyFrames = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it >= 64 }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val minBufferFrames = if (minBufferBytes > 0) minBufferBytes / 4 else 0
        val framesPerBuffer = (propertyFrames ?: minBufferFrames.takeIf { it >= 64 } ?: 192)
            .coerceIn(64, 4096)
        runCatching {
            applyNative(sampleRate, framesPerBuffer)
        }.onSuccess {
            AppLogger.i(
                TAG,
                "Android audio scheduler profile configured: sr=$sampleRate frames=$framesPerBuffer " +
                    "propertyFrames=${propertyFrames ?: 0} minBufferBytes=$minBufferBytes",
            )
        }.onFailure {
            AppLogger.w(TAG, "Unable to configure Android audio scheduler profile", it)
        }
    }
}
