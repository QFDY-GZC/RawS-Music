package com.rawsmusic.module.player

/** A longer overlap must still leave real incoming audio for the decoder handoff. */
internal object AutoTransitionWindowBudget {
    fun fitIncoming(plan: AutoTransitionStartPlan, durationMs: Long, sourceStartMs: Long): AutoTransitionStartPlan? {
        val normalized = if (plan.handoverMs < AutoTransitionPolicy.MIN_HANDOVER_MS)
            plan.copy(handoverMs = AutoTransitionPolicy.MIN_HANDOVER_MS,
                pivotMs = AutoTransitionPolicy.pivotMs(AutoTransitionPolicy.MIN_HANDOVER_MS)) else plan
        if (durationMs <= 0L) return normalized
        val available = durationMs - sourceStartMs - 1500L - plan.preRollMs
        val main = minOf(normalized.handoverMs.toLong(), available)
        if (main < AutoTransitionPolicy.MIN_HANDOVER_MS) return null
        if (main == normalized.handoverMs.toLong()) return normalized
        return normalized.copy(handoverMs = main.toInt(), pivotMs = AutoTransitionPolicy.pivotMs(main.toInt()))
    }
}
