package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-level registry of discovered artwork image sources.
 *
 * Embedded, folder and direct-image records live in independent slots. A late folder result can no
 * longer overwrite an embedded record merely because both share the same provider key. Folder
 * records are visible only after embedded absence is confirmed, and new folder records require a
 * generation-bound permit from [ArtworkSourceAuthority].
 */
internal object ArtworkSourceIndex {
    data class SourceRecord(
        val sourcePath: String,
        val kind: ArtworkSourceSelectionPolicy.IndexedSourceKind,
        val updatedAtMs: Long
    )

    private val authority = ArtworkSourceAuthority()
    private val mutationLock = Any()
    private val sourcesByProviderKey =
        ConcurrentHashMap<String, ConcurrentHashMap<ArtworkSourceSelectionPolicy.IndexedSourceKind, SourceRecord>>()

    fun sourceFor(
        providerKey: String,
        acceptedKinds: Set<ArtworkSourceSelectionPolicy.IndexedSourceKind> =
            ArtworkSourceSelectionPolicy.allIndexedKinds
    ): SourceRecord? {
        if (providerKey.isBlank()) return null
        val records = sourcesByProviderKey[providerKey] ?: return null

        for (kind in ArtworkSourceSelectionPolicy.indexedLookupOrder) {
            if (kind !in acceptedKinds) continue
            if (
                kind == ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover &&
                !authority.mayUseFolderFallback(providerKey)
            ) {
                continue
            }

            val record = records[kind] ?: continue
            val file = File(record.sourcePath)
            if (file.exists() && file.canRead() && file.length() > 1024L) {
                return record
            }

            records.remove(kind, record)
            if (kind == ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded) {
                synchronized(mutationLock) {
                    authority.reset(providerKey)
                }
            }
        }

        if (records.isEmpty()) sourcesByProviderKey.remove(providerKey, records)
        return null
    }

    fun sourcePathFor(
        providerKey: String,
        acceptedKinds: Set<ArtworkSourceSelectionPolicy.IndexedSourceKind> =
            ArtworkSourceSelectionPolicy.allIndexedKinds
    ): String? = sourceFor(providerKey, acceptedKinds)?.sourcePath

    fun rememberSource(
        providerKey: String,
        sourcePath: String,
        kind: ArtworkSourceSelectionPolicy.IndexedSourceKind
    ) {
        val record = validatedRecord(sourcePath, kind) ?: return
        if (providerKey.isBlank()) return

        synchronized(mutationLock) {
            val records = sourcesByProviderKey.getOrPut(providerKey) { ConcurrentHashMap() }
            when (kind) {
                ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded -> {
                    authority.markEmbeddedPresent(providerKey)
                    records.remove(ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover)
                    records[kind] = record
                }

                ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover -> {
                    // Legacy/pre-indexed folder records may be remembered before probing, but they
                    // remain invisible until authority confirms embedded absence. Runtime folder
                    // decodes use commitFolderSource() instead.
                    records[kind] = record
                }

                ArtworkSourceSelectionPolicy.IndexedSourceKind.DirectImage -> {
                    records[kind] = record
                }
            }
        }
    }

    fun markEmbeddedPresent(providerKey: String) {
        if (providerKey.isBlank()) return
        synchronized(mutationLock) {
            authority.markEmbeddedPresent(providerKey)
            sourcesByProviderKey[providerKey]
                ?.remove(ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover)
        }
    }

    fun markEmbeddedAbsent(providerKey: String) {
        if (providerKey.isBlank()) return
        synchronized(mutationLock) {
            authority.markEmbeddedAbsent(providerKey)
        }
    }

    fun beginFolderFallback(providerKey: String): ArtworkSourceAuthority.FolderFallbackPermit? {
        if (providerKey.isBlank()) return null
        return synchronized(mutationLock) {
            authority.beginFolderFallback(providerKey)
        }
    }

    fun commitFolderSource(
        permit: ArtworkSourceAuthority.FolderFallbackPermit,
        sourcePath: String
    ): Boolean {
        val record = validatedRecord(
            sourcePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover
        ) ?: return false

        return synchronized(mutationLock) {
            if (!authority.canCommitFolderFallback(permit)) return@synchronized false
            val records = sourcesByProviderKey.getOrPut(permit.providerKey) { ConcurrentHashMap() }
            records[ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover] = record
            true
        }
    }

    fun mayUseFolderFallback(providerKey: String): Boolean {
        if (providerKey.isBlank()) return false
        return authority.mayUseFolderFallback(providerKey)
    }

    fun embeddedStateFor(providerKey: String): ArtworkSourceAuthority.EmbeddedState =
        authority.stateFor(providerKey)

    fun remove(providerKey: String): Boolean {
        if (providerKey.isBlank()) return false
        synchronized(mutationLock) {
            val authorityRemoved = authority.reset(providerKey)
            val sourceRemoved = sourcesByProviderKey.remove(providerKey) != null
            return authorityRemoved || sourceRemoved
        }
    }

    fun removeAll(keys: Collection<String>): Int {
        synchronized(mutationLock) {
            var removed = 0
            keys.forEach { key ->
                val sourceRemoved = sourcesByProviderKey.remove(key) != null
                val authorityRemoved = authority.reset(key)
                if (sourceRemoved || authorityRemoved) removed++
            }
            return removed
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            sourcesByProviderKey.clear()
            authority.clear()
        }
    }

    private fun validatedRecord(
        sourcePath: String,
        kind: ArtworkSourceSelectionPolicy.IndexedSourceKind
    ): SourceRecord? {
        if (sourcePath.isBlank()) return null
        val file = File(sourcePath)
        if (!file.exists() || !file.canRead() || file.length() <= 1024L) return null
        return SourceRecord(
            sourcePath = file.absolutePath,
            kind = kind,
            updatedAtMs = System.currentTimeMillis()
        )
    }
}
