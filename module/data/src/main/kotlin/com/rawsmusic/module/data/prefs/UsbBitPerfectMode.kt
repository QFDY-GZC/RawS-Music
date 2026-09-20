package com.rawsmusic.module.data.prefs

/**
 * USB exclusive PCM bit-perfect policy.
 *
 * OFF: always allow the normal processed/resampled USB PCM path.
 * WHEN_POSSIBLE: use strict bit-perfect only when the current source geometry
 * can be represented exactly by the connected USB device; otherwise keep
 * exclusive playback alive and fall back to the processed path for that track.
 * STRICT: never resample/down-convert PCM; fail the track when no exact USB
 * profile exists.
 */
enum class UsbBitPerfectMode(val id: Int) {
    OFF(0),
    WHEN_POSSIBLE(1),
    STRICT(2);

    val requestsBitPerfect: Boolean get() = this != OFF
    val requiresBitPerfect: Boolean get() = this == STRICT

    companion object {
        fun fromId(id: Int): UsbBitPerfectMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}
