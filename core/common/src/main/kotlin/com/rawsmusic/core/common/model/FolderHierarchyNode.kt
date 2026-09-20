package com.rawsmusic.core.common.model

/**
 * Canonical folder-hierarchy record used by the Reference-style Folders Hierarchy provider.
 *
 * [id]/[parentId] are derived from normalized paths, so the hierarchy identity is stable across
 * rescans even when Room row ids are rebuilt. Counts/duration are subtree aggregates; direct files
 * remain owned by the song provider and are resolved by exact parent path in the UI.
 */
data class FolderHierarchyNode(
    val id: Long,
    val parentId: Long,
    val path: String,
    val name: String,
    val parentLabel: String,
    val directSongCount: Int,
    val hierarchicalSongCount: Int,
    val hierarchicalDurationMs: Long,
    val childFolderCount: Int,
    val coverKey: String,
    val depth: Int,
)

/** Stable, process-independent 64-bit identity for a normalized folder path. Negative IDs namespace folder rows away from MediaStore song IDs. */
fun stableFolderHierarchyId(path: String): Long {
    var hash = -3750763034362895579L // FNV-1a 64 offset basis as signed Long
    path.forEach { ch ->
        hash = hash xor ch.code.toLong()
        hash *= 1099511628211L
    }
    return (hash and Long.MAX_VALUE) or Long.MIN_VALUE
}
