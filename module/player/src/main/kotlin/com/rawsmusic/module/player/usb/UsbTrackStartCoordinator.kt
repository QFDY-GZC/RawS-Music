package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode

/**
 * USB track cutover sequencing. Device discovery and native session ownership
 * stay in UsbExclusiveManager; this class only owns the order of reuse or
 * fade -> stop -> prepare -> prebuffer -> start.
 */
internal class UsbTrackStartCoordinator(
    private val currentConfig: () -> UsbAudioConfig?,
    private val fadeOut: (Int) -> Unit,
    private val stopStreaming: (String) -> Unit,
    private val prepareForPlayback: (Int, Int, Int) -> Boolean,
    private val startStreaming: () -> Boolean,
    private val setStreamingState: (Boolean) -> Unit,
) {
    private val tag = "UsbExclusiveManager"

    fun prepareAndStart(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        firstPcmChunks: List<ByteArray>,
    ): Boolean {
        AppLogger.i(tag, "prepareAndStartForTrack: sr=$sampleRate bits=$bits ch=$channels chunks=${firstPcmChunks.size}")

        val config = UsbTrackStartPolicy.selectConfig(
            sampleRate = sampleRate,
            bits = bits,
            channels = channels,
            requestedTargetBits = AppPreferences.Player.usbTargetBitDepth,
            pcmMode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode),
            strictBitPerfect = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
                AppPreferences.Player.usbBitPerfectMode,
                AppPreferences.Player.usbVolumeMode,
            ) && bits <= 32,
            pcmDsdActive = AppPreferences.Player.dsdConversionEnabled,
        )
        if (config == null) {
            AppLogger.e(tag, "No USB config for ${sampleRate}/${bits}/${channels}")
            return false
        }

        val oldConfig = currentConfig()
        val sameFormat = UsbAudioEngine.currentHandle != 0L && oldConfig == config
        if (sameFormat) {
            AppLogger.i(tag, "Same format, keeping USB streaming running, just write new data")
            val handle = UsbAudioEngine.currentHandle
            if (handle == 0L) {
                AppLogger.e(tag, "handle=0 unexpectedly")
                return false
            }
            for (chunk in firstPcmChunks) {
                UsbAudioEngine.safeNativeWriteHandle(handle, chunk, 0, chunk.size)
            }
            return true
        }

        AppLogger.i(tag, "Format changed: old=$oldConfig new=$config, need stop/reinit")
        fadeOut(com.rawsmusic.module.data.prefs.TransitionPreferences.transportDurationOrZero())
        stopStreaming("format_change")
        if (!prepareForPlayback(sampleRate, bits, channels)) {
            AppLogger.e(tag, "prepareForPlayback failed")
            return false
        }

        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L) {
            AppLogger.e(tag, "handle=0 after prepare")
            return false
        }
        for (chunk in firstPcmChunks) {
            UsbAudioEngine.safeNativeWriteHandle(handle, chunk, 0, chunk.size)
        }
        val started = startStreaming()
        if (started) setStreamingState(true)
        return started
    }
}
