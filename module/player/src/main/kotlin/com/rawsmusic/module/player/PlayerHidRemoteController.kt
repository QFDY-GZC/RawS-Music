package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine

/**
 * Keeps USB/HID media-key handling out of the playback state machine.
 * The callbacks deliberately point back to PlayerController's public transport and volume
 * entry points so HID commands cannot bypass the normal serialization path.
 */
internal class PlayerHidRemoteController(
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val nextSong: () -> com.rawsmusic.core.common.model.AudioFile?,
    private val previousSong: () -> com.rawsmusic.core.common.model.AudioFile?,
    private val playNext: (com.rawsmusic.core.common.model.AudioFile) -> Unit,
    private val stop: () -> Unit,
    private val isUsbExclusiveActive: () -> Boolean,
    private val canControlUsbVolume: () -> Boolean,
    private val stepUsbVolume: (Float) -> Unit,
    private val setUsbVolumeLinear: (Float) -> Unit,
    private val setVolume: (Float) -> Unit,
) {
    fun start(): Boolean {
        if (!UsbAudioEngine.initHidSafely()) {
            AppLogger.w(TAG, "USB HID remote key listener disabled: native HID bridge unavailable")
            return false
        }

        UsbAudioEngine.setHidKeyEventListener(object : UsbAudioEngine.HidKeyEventListener {
            override fun onHidKeyEvent(keyCode: Int, pressed: Boolean) {
                if (!pressed) return

                AppLogger.i(TAG, "HID key event: keyCode=0x${keyCode.toString(16)}, pressed=$pressed")
                when (keyCode) {
                    0xCD -> {
                        AppLogger.i(TAG, "HID: Play/Pause")
                        if (isPlaying()) pause() else resume()
                    }

                    0xB5 -> {
                        AppLogger.i(TAG, "HID: Next Track")
                        nextSong()?.let(playNext)
                    }

                    0xB6 -> {
                        AppLogger.i(TAG, "HID: Previous Track")
                        previousSong()?.let(playNext)
                    }

                    0xB7 -> {
                        AppLogger.i(TAG, "HID: Stop")
                        stop()
                    }

                    0xE9 -> {
                        AppLogger.i(TAG, "HID: Volume Up")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            stepUsbVolume(+0.04f)
                        } else {
                            setVolume((AppPreferences.Player.volume + 0.05f).coerceAtMost(1.0f))
                        }
                    }

                    0xEA -> {
                        AppLogger.i(TAG, "HID: Volume Down")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            stepUsbVolume(-0.04f)
                        } else {
                            setVolume((AppPreferences.Player.volume - 0.05f).coerceAtLeast(0.0f))
                        }
                    }

                    0xE2 -> {
                        AppLogger.i(TAG, "HID: Mute")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            setUsbVolumeLinear(0f)
                        } else if (AppPreferences.Player.volume > 0f) {
                            setVolume(0f)
                        } else {
                            setVolume(0.5f)
                        }
                    }

                    else -> AppLogger.d(TAG, "HID: Unknown key 0x${keyCode.toString(16)}")
                }
            }
        })
        return true
    }

    private companion object {
        const val TAG = "PlayerHidRemote"
    }
}
