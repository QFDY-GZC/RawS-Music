package com.rawsmusic.module.player

import java.util.concurrent.atomic.AtomicLong

/** Generates positive queue-row ids for new entries and legacy JSON migrations. */
internal object QueueEntryIdGenerator {
    private val nextId = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L) * 1000L)

    fun normalize(size: Int, existing: List<Long>): List<Long> {
        if (size <= 0) return emptyList()
        val used = HashSet<Long>(size)
        return List(size) { index ->
            val candidate = existing.getOrNull(index)
            if (candidate != null && candidate > 0L && used.add(candidate)) {
                candidate
            } else {
                var generated = nextId.incrementAndGet()
                while (!used.add(generated)) generated = nextId.incrementAndGet()
                generated
            }
        }
    }
}
