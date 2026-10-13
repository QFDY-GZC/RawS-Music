package com.rawsmusic.module.player

internal object IncomingEntryPolicy {
    fun select(levels: List<Float>, windowMs: Long, firstLyricMs: Long? = null,
               introLeadMs: Long = 4000L, sampleStartMs: Long = 0L): Long {
        if (windowMs <= 0 || levels.size < 6 || levels.any { !it.isFinite() }) return 0L
        val sustain = ((600L + windowMs - 1) / windowMs).toInt().coerceAtLeast(1)
        if (levels.size < sustain) return 0L
        if (firstLyricMs != null && firstLyricMs >= 0L) {
            if (firstLyricMs <= 2000L) return 0L
            val vocalIndex = (firstLyricMs - sampleStartMs) / windowMs
            if (firstLyricMs < sampleStartMs || vocalIndex !in 0L..(levels.size - sustain).toLong()) return 0L
            val vocal = vocalIndex.toInt()
            if (vocal + sustain <= levels.size &&
                levels.subList(vocal, vocal + sustain).all { it > -50f }) {
                return (firstLyricMs - if (firstLyricMs <= 6000L) 200L else
                    introLeadMs.coerceIn(200L, 30000L)).coerceAtLeast(0L)
            }
            return 0L
        }
        val onsetDb = if (firstLyricMs != null) -30f else -45f
        val onset = (0..(levels.size - sustain).coerceAtLeast(0)).firstOrNull { index ->
            levels.subList(index, index + sustain).all { it > onsetDb }
        } ?: return 0L
        val onsetMs = onset * windowMs
        if (onsetMs < 500L) return 0L
        val before = levels.take(onset)
        val silent = before.isNotEmpty() && before.all { it <= -60f }
        val lyricSafe = firstLyricMs != null && firstLyricMs >= onsetMs + 2000L
        val weakIntro = onsetMs >= 8000L && lyricSafe && before.all { it <= -38f } &&
            levels.subList(onset, onset + sustain).average() - before.average() >= 18.0
        if (!silent && !weakIntro) return 0L
        return (onsetMs - 200L).coerceIn(0L, 15_000L)
    }
}
