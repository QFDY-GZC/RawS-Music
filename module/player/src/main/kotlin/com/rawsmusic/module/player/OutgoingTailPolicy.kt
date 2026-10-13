package com.rawsmusic.module.player

internal data class OutgoingTailPlan(val fadeStartMs: Long, val cutoffMs: Long)

internal object OutgoingTailPolicy {
    fun select(levels: List<Float>, startMs: Long, windowMs: Long, earliestMs: Long): OutgoingTailPlan? {
        if (windowMs <= 0 || levels.any { !it.isFinite() }) return null
        val section = (1000L / windowMs).toInt().coerceAtLeast(1)
        val history = section * 4
        val future = (3000L / windowMs).toInt().coerceAtLeast(1)
        if (levels.size < history + future) return null
        for (i in history until levels.size - future + 1) {
            val cutoff = startMs + i * windowMs + 200L
            if (cutoff < earliestMs + 1500L) continue
            val slices = (0..3).map { s ->
                levels.subList(i - history + s * section, i - history + (s + 1) * section).average()
            }
            val ahead = levels.subList(i, i + future)
            val low = ahead.take(section * 2).average()
            val base = slices.take(2).average()
            val recovery = ahead.sorted()[(ahead.size - 1) * 9 / 10]
            val abrupt = base - low >= 5.0 && low <= -22.0 && recovery < base - 3.0
            val gentle = slices.first() - low >= 2.5 && low <= -23.0 &&
                slices.zipWithNext().all { (a, b) -> b <= a + .35 } &&
                slices.zipWithNext().count { (a, b) -> a - b >= .3 } >= 2 && recovery <= low + 1.5
            if (abrupt || gentle) {
                val fadeStart = (cutoff - if (abrupt) 3000L else 4000L).coerceAtLeast(earliestMs)
                if (cutoff - fadeStart >= 1500L) return OutgoingTailPlan(fadeStart, cutoff)
            }
        }
        return null
    }
}
