package com.rawsmusic.core.ui.widget.bitmaps

/** Separate insertion-order wrapper lanes, matching the provider's low/high ownership. */
internal class ProviderLaneEvictionIndex<T> {
    private val low = LinkedHashMap<String, T>()
    private val high = LinkedHashMap<String, T>()
    fun put(key: String, value: T, highResolution: Boolean) {
        (if (highResolution) high else low)[key] = value
    }
    fun remove(key: String, highResolution: Boolean) {
        (if (highResolution) high else low).remove(key)
    }
    fun clear() { low.clear(); high.clear() }
    fun removeOldest(highResolution: Boolean, evictable: (T) -> Boolean): T? {
        val iterator = (if (highResolution) high else low).entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (evictable(entry.value)) {
                val value = entry.value
                iterator.remove()
                return value
            }
        }
        return null
    }
}
