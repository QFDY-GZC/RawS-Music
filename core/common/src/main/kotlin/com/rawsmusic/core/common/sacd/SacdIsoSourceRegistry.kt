package com.rawsmusic.core.common.sacd

/** Only live/recent SACD sources need an FD-aware decoder address. No audio data is retained. */
object SacdIsoSourceRegistry {
    private val sources = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }
    @Synchronized fun register(path: String, area: Int = 0) {
        if (path.isNotBlank() && area in 0..1) sources[path] = area
    }
    @Synchronized fun area(path: String): Int? = sources[path]
}
