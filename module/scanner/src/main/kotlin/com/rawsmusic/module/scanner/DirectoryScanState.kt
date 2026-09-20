package com.rawsmusic.module.scanner

import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Persistent directory-level fingerprints used by the cold-start scanner.
 *
 * A directory timestamp is only a structural fast path.  It is never used when the
 * provider/filesystem cannot supply a positive timestamp, and callers still need to
 * prove that an existing database row is available before reusing a skipped subtree.
 */
internal object DirectoryScanState {
    private const val SEPARATOR = '\t'

    fun fileSystemKey(path: String): String = "fs:" + SelectedFolderFileWalker.normalize(path)

    fun safKey(uri: String): String = "saf:" + uri.trim()

    fun index(): Index = Index(load())

    class Index internal constructor(private val snapshots: Map<String, Long>) {
        fun canSkip(key: String, modifiedAt: Long, hasBaseline: Boolean): Boolean {
            if (modifiedAt <= 0L || !hasBaseline) return false
            return snapshots[key] == modifiedAt
        }
    }

    fun commit(snapshots: Map<String, Long>) {
        if (snapshots.isEmpty()) return
        val merged = load().toMutableMap()
        snapshots.forEach { (key, modifiedAt) ->
            if (modifiedAt > 0L) merged[key] = modifiedAt
        }

        // Keep this bounded in case a removable drive is repeatedly reconfigured.
        val retained = merged.entries
            .sortedBy { it.key }
            .takeLast(MAX_ENTRIES)
            .joinToString("\n") { "${it.key}$SEPARATOR${it.value}" }
        AppPreferences.Scanner.coldDirectoryFingerprints = retained
    }

    private fun load(): Map<String, Long> {
        val raw = AppPreferences.Scanner.coldDirectoryFingerprints
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(SEPARATOR)
            if (separator <= 0 || separator >= line.lastIndex) return@mapNotNull null
            val key = line.substring(0, separator)
            val modifiedAt = line.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            key to modifiedAt
        }.toMap()
    }

    private const val MAX_ENTRIES = 4096
}
