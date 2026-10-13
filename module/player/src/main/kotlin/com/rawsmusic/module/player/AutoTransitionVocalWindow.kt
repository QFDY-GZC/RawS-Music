package com.rawsmusic.module.player

internal object AutoTransitionVocalWindow {
    const val MAIN_HANDOVER_MS = 15000L

    fun select(starts: List<Long>, vocalEnd: Long): Long? {
        val tail = starts.distinct().sorted().takeLast(3)
        for (count in tail.size downTo 1) {
            val start = tail[tail.size - count]
            val limit = when (count) { 3 -> 20000L; 2 -> 14000L; else -> 8000L }
            if (vocalEnd - start in 1L..limit) return start
        }
        return null
    }

    fun introLeadMs(recipe: AutoTransitionPolicy.Recipe?, duration: Long): Long {
        val start = recipe?.gentleStartPositionMs ?: return 4000L
        val end = recipe.handoverPositionMs ?: return 4000L
        val lead = (end - start).coerceAtLeast(0)
        val main = (duration - end).coerceIn(0L, MAIN_HANDOVER_MS)
        return (lead + 1500L).coerceAtMost((lead + main - 500L).coerceAtLeast(200L))
    }
}
