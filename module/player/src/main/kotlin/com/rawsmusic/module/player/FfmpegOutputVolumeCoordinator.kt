package com.rawsmusic.module.player

import android.media.AudioTrack
import android.os.Build
import com.rawsmusic.core.common.utils.AppLogger

/** Applies software volume to the active Android/native output path. */
internal class FfmpegOutputVolumeCoordinator(
    private val tag: String,
    private val setStoredVolume: (Float) -> Unit,
    private val setNativeVolume: (Float, String) -> Unit,
    private val audioTrack: () -> AudioTrack?,
    private val usbExclusiveMode: () -> Boolean,
) {
    fun setVolume(requested: Float) {
        val volume = requested.coerceIn(0f, 1f)
        setStoredVolume(volume)
        setNativeVolume(volume, "setVolume")
        if (usbExclusiveMode()) {
            AppLogger.d(
                tag,
                "USB exclusive: store ffmpeg volume=$volume only; native volume is controlled by UsbVolumePlan",
            )
        }
        runCatching {
            audioTrack()?.let { track ->
                if (Build.VERSION.SDK_INT >= 28) {
                    track.setVolume(volume)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(volume, volume)
                }
            }
        }
    }
}
