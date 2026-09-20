package com.rawsmusic.module.player

import kotlin.math.pow
import kotlin.math.log10

/**
 * Perceptual USB-exclusive software-volume curve used while Android still owns the normal local
 * STREAM_MUSIC UI.
 *
 * The platform may expose only ~15-16 hardware-key steps. Mapping those steps directly to linear
 * PCM amplitude makes the first non-zero step far too loud. Instead keep the UI position unchanged
 * and map it onto a configurable logarithmic gain range. 0 remains hard mute and 100% remains unity.
 */
internal object UsbSoftwareVolumeCurve {
    const val DEFAULT_RANGE_DB = 60
    const val MIN_RANGE_DB = 40
    const val MAX_RANGE_DB = 80

    fun gainForLocalStream(uiLinear: Float, rangeDb: Int): Float {
        val volume = uiLinear.coerceIn(0f, 1f)
        if (volume <= 0.0001f) return 0f
        if (volume >= 0.9999f) return 1f

        val boundedRange = rangeDb.coerceIn(MIN_RANGE_DB, MAX_RANGE_DB).toFloat()
        val gainDb = -boundedRange * (1f - volume)
        return 10.0.pow(gainDb.toDouble() / 20.0)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    /**
     * Inverse of [gainForLocalStream] for volume-route handoff.
     *
     * Hardware Feature Unit volume is reported in dB while the software route is presented as a
     * 0..1 local STREAM_MUSIC position. Converting the current hardware attenuation onto the same
     * software curve lets hardware -> software ownership change without a loudness discontinuity.
     */
    fun localStreamForGain(linearGain: Float, rangeDb: Int): Float {
        val gain = linearGain.coerceIn(0f, 1f)
        if (gain <= 0f) return 0f
        if (gain >= 0.9999f) return 1f

        val boundedRange = rangeDb.coerceIn(MIN_RANGE_DB, MAX_RANGE_DB).toFloat()
        val gainDb = (20.0 * log10(gain.toDouble())).toFloat()
        return (1f + gainDb / boundedRange).coerceIn(0f, 1f)
    }

    fun localStreamForDb(gainDb: Float, rangeDb: Int): Float {
        if (!gainDb.isFinite()) return 0f
        if (gainDb >= -0.001f) return 1f
        val boundedRange = rangeDb.coerceIn(MIN_RANGE_DB, MAX_RANGE_DB).toFloat()
        return (1f + gainDb / boundedRange).coerceIn(0f, 1f)
    }
}
