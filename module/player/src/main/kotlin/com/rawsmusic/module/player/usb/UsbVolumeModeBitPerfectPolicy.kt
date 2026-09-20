package com.rawsmusic.module.player.usb

import com.rawsmusic.module.data.prefs.UsbBitPerfectMode

/**
 * Resolves the interaction between the explicit USB user-volume mode and PCM bit-perfect intent.
 *
 * RawSMusic exposes software volume as an explicit user choice. Software user volume necessarily
 * mutates PCM samples, so that choice must disable *effective* PCM bit-perfect for the session
 * rather than leaving the UI adjustable while native silently pins gain to unity.
 *
 * Hardware Feature Unit and fixed-digital modes keep PCM bit-perfect eligible because user volume
 * is either outside the PCM data plane or intentionally fixed at unity.
 */
internal object UsbVolumeModeBitPerfectPolicy {
    const val SOFTWARE_VOLUME_MODE = 0

    fun softwareVolumeSelected(usbVolumeMode: Int): Boolean =
        usbVolumeMode == SOFTWARE_VOLUME_MODE

    fun effectivePcmBitPerfect(candidate: Boolean, usbVolumeMode: Int): Boolean =
        candidate && !softwareVolumeSelected(usbVolumeMode)

    fun shouldPreArmStrict(mode: UsbBitPerfectMode, usbVolumeMode: Int): Boolean =
        mode == UsbBitPerfectMode.STRICT && !softwareVolumeSelected(usbVolumeMode)

    fun reason(candidate: Boolean, usbVolumeMode: Int, upstreamReason: String): String =
        if (candidate && softwareVolumeSelected(usbVolumeMode)) {
            "software_volume_selected_disables_effective_bitperfect"
        } else {
            upstreamReason
        }
}
