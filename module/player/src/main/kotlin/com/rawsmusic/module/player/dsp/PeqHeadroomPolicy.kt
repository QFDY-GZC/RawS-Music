package com.rawsmusic.module.player.dsp

const val PEQ_MIN_PREAMP_DB = -96f
const val PEQ_MAX_PREAMP_DB = 12f

internal const val PEQ_AUTO_HEADROOM_MARGIN_DB = 1.0f
internal const val PEQ_AUTO_HEADROOM_TRIGGER_DB = 0.05f

fun sanitizePeqPreamp(value: Float): Float = if (value.isFinite()) {
    value.coerceIn(PEQ_MIN_PREAMP_DB, PEQ_MAX_PREAMP_DB)
} else {
    0f
}

internal data class PeqHeadroomDecision(
    val requestedPreampDb: Float,
    val filterPeakDb: Float,
    val requestedOutputPeakDb: Float,
    val reductionDb: Float,
    val effectivePreampDb: Float,
)

/**
 * Adds safety headroom only when the requested preamp + filter response would still cross the
 * configured peak threshold. This preserves AutoEq's supplied negative preamp instead of applying
 * the filter peak twice.
 */
internal fun resolvePeqHeadroom(
    requestedPreampDb: Float,
    filterPeakDb: Float,
    marginDb: Float = PEQ_AUTO_HEADROOM_MARGIN_DB,
    triggerDb: Float = PEQ_AUTO_HEADROOM_TRIGGER_DB,
): PeqHeadroomDecision {
    val requested = sanitizePeqPreamp(requestedPreampDb)
    val peak = if (filterPeakDb.isFinite()) filterPeakDb.coerceAtLeast(0f) else 0f
    val requestedOutputPeak = requested + peak
    val reduction = if (requestedOutputPeak > triggerDb) {
        requestedOutputPeak + marginDb.coerceAtLeast(0f)
    } else {
        0f
    }
    val effective = sanitizePeqPreamp(requested - reduction)
    return PeqHeadroomDecision(
        requestedPreampDb = requested,
        filterPeakDb = peak,
        requestedOutputPeakDb = requestedOutputPeak,
        reductionDb = (requested - effective).coerceAtLeast(0f),
        effectivePreampDb = effective,
    )
}
