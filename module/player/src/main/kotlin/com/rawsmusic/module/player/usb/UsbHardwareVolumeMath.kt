package com.rawsmusic.module.player.usb

import kotlin.math.floor
import kotlin.math.roundToInt

/** Device-independent mapping between the UI preference and UAC raw volume. */
object UsbHardwareVolumeMath {
    const val MIN_DB = -60
    const val MAX_DB = 0
    const val MAX_STEP = MAX_DB - MIN_DB
    private const val SAFE_DB = -30

    fun clampStep(step: Int): Int = step.coerceIn(0, MAX_STEP)

    fun stepToDb(step: Int): Int = MIN_DB + clampStep(step)

    fun stepToUi(step: Int): Float = clampStep(step).toFloat() / MAX_STEP.toFloat()

    fun uiToStep(volume: Float): Int =
        (volume.coerceIn(0f, 1f) * MAX_STEP).roundToInt().coerceIn(0, MAX_STEP)

    fun quantizeRaw(raw: Int, minRaw: Int, maxRaw: Int, resRaw: Int): Int {
        if (minRaw >= maxRaw) return raw
        val resolution = resRaw.coerceAtLeast(1)
        val clamped = raw.coerceIn(minRaw, maxRaw)
        val steps = ((clamped - minRaw).toDouble() / resolution.toDouble()).roundToInt()
        return (minRaw + steps * resolution).coerceIn(minRaw, maxRaw)
    }

    fun conservativeSafeRaw(minRaw: Int, maxRaw: Int, resRaw: Int): Int? {
        if (minRaw >= maxRaw) return null
        val nominalSafeRaw = SAFE_DB * 256
        if (minRaw > nominalSafeRaw) return null
        val resolution = resRaw.coerceAtLeast(1)
        val safeCeiling = minOf(nominalSafeRaw, maxRaw)
        val steps = floor((safeCeiling - minRaw).toDouble() / resolution.toDouble()).toInt()
        return (minRaw + steps * resolution)
            .coerceIn(minRaw, maxRaw)
            .takeIf { it <= nominalSafeRaw }
    }

    fun rawToUi(raw: Int, minRaw: Int, maxRaw: Int): Float {
        if (minRaw >= maxRaw) return 0f
        return ((raw.coerceIn(minRaw, maxRaw) - minRaw).toFloat() /
            (maxRaw - minRaw).toFloat()).coerceIn(0f, 1f)
    }

    fun uiToRaw(uiVolume: Float, minRaw: Int, maxRaw: Int, resRaw: Int): Int {
        if (minRaw >= maxRaw) return minRaw
        val target = minRaw + ((maxRaw - minRaw) * uiVolume.coerceIn(0f, 1f)).roundToInt()
        return quantizeRaw(target, minRaw, maxRaw, resRaw)
    }
}
