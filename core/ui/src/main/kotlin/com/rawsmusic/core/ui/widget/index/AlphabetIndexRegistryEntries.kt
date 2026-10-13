package com.rawsmusic.core.ui.widget.index

/** Repeated identical publication must not invalidate the parent scene's snapshot. */
internal fun <T> upsertAlphabetIndexEntry(entries: List<T>, value: T, sameOwner: (T, T) -> Boolean): List<T> {
    val index = entries.indexOfFirst { sameOwner(it, value) }
    if (index < 0) return entries + value
    if (entries[index] == value) return entries
    return entries.toMutableList().also { it[index] = value }
}
