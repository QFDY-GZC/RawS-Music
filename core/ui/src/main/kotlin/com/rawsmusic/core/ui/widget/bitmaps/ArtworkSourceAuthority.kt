package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Per-version authority state for embedded artwork versus folder fallback.
 *
 * Folder fallback is a two-phase operation: a request first receives a permit after every embedded
 * lane missed, then commits the decoded folder source only if no concurrent request confirmed
 * embedded artwork in the meantime.
 */
internal class ArtworkSourceAuthority {
    enum class EmbeddedState {
        Unknown,
        Present,
        Absent
    }

    data class FolderFallbackPermit internal constructor(
        val providerKey: String,
        internal val generation: Long
    )

    private data class Entry(
        var state: EmbeddedState = EmbeddedState.Unknown,
        var generation: Long = 0L
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun stateFor(providerKey: String): EmbeddedState = synchronized(lock) {
        entries[providerKey]?.state ?: EmbeddedState.Unknown
    }

    fun markEmbeddedPresent(providerKey: String) = synchronized(lock) {
        if (providerKey.isBlank()) return@synchronized
        val entry = entries.getOrPut(providerKey) { Entry() }
        if (entry.state != EmbeddedState.Present) {
            entry.generation++
            entry.state = EmbeddedState.Present
        }
    }

    fun markEmbeddedAbsent(providerKey: String) = synchronized(lock) {
        if (providerKey.isBlank()) return@synchronized
        val entry = entries.getOrPut(providerKey) { Entry() }
        if (entry.state == EmbeddedState.Unknown) {
            entry.generation++
            entry.state = EmbeddedState.Absent
        }
    }

    fun beginFolderFallback(providerKey: String): FolderFallbackPermit? = synchronized(lock) {
        if (providerKey.isBlank()) return@synchronized null
        val entry = entries.getOrPut(providerKey) { Entry() }
        if (entry.state == EmbeddedState.Present) return@synchronized null
        if (entry.state == EmbeddedState.Unknown) {
            entry.generation++
            entry.state = EmbeddedState.Absent
        }
        FolderFallbackPermit(providerKey, entry.generation)
    }

    fun canCommitFolderFallback(permit: FolderFallbackPermit): Boolean = synchronized(lock) {
        val entry = entries[permit.providerKey] ?: return@synchronized false
        entry.state == EmbeddedState.Absent && entry.generation == permit.generation
    }

    fun mayUseFolderFallback(providerKey: String): Boolean = synchronized(lock) {
        entries[providerKey]?.state == EmbeddedState.Absent
    }

    fun reset(providerKey: String): Boolean = synchronized(lock) {
        entries.remove(providerKey) != null
    }

    fun resetAll(keys: Collection<String>): Int = synchronized(lock) {
        var removed = 0
        keys.forEach { if (entries.remove(it) != null) removed++ }
        removed
    }

    fun clear() = synchronized(lock) {
        entries.clear()
    }
}
