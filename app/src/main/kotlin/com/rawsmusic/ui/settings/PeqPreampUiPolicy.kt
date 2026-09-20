package com.rawsmusic.ui.settings

import com.rawsmusic.module.player.dsp.PEQ_MAX_PREAMP_DB
import com.rawsmusic.module.player.dsp.PEQ_MIN_PREAMP_DB
import kotlin.math.floor
import kotlin.math.roundToInt

private const val DEFAULT_UI_MIN_PREAMP_DB = -12f
private const val PREAMP_BUCKET_DB = 6f

/** Keep the normal UI compact, but expand downward when an imported preset needs more attenuation. */
internal fun peqPreampSliderRange(currentPreampDb: Float): ClosedFloatingPointRange<Float> {
    val safe = currentPreampDb.takeIf { it.isFinite() } ?: 0f
    val lower = if (safe >= DEFAULT_UI_MIN_PREAMP_DB) {
        DEFAULT_UI_MIN_PREAMP_DB
    } else {
        (floor(safe / PREAMP_BUCKET_DB) * PREAMP_BUCKET_DB)
            .coerceIn(PEQ_MIN_PREAMP_DB, DEFAULT_UI_MIN_PREAMP_DB)
    }
    return lower..PEQ_MAX_PREAMP_DB
}

internal fun peqPreampHalfDbSteps(range: ClosedFloatingPointRange<Float>): Int {
    val intervals = ((range.endInclusive - range.start) * 2f).roundToInt().coerceAtLeast(1)
    return (intervals - 1).coerceAtLeast(0)
}
