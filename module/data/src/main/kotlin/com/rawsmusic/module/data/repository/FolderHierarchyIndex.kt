package com.rawsmusic.module.data.repository

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FolderHierarchyNode
import com.rawsmusic.core.common.model.stableFolderHierarchyId
import com.rawsmusic.core.common.utils.CjkSortUtils

/**
 * Canonical `folders_hier`-style index built once per library publication.
 *
 * The UI never walks the filesystem during navigation. It selects direct children by [parentId]
 * and direct files by the exact folder path, while subtree song count/duration are already folded
 * into each node just like Reference's hierarchy provider projection.
 */
internal fun buildFolderHierarchyIndex(songs: List<AudioFile>): List<FolderHierarchyNode> {
    if (songs.isEmpty()) return emptyList()

    data class MutableNode(
        val path: String,
        var parentPath: String?,
        val depth: Int,
        var directSongs: Int = 0,
        var hierarchicalSongs: Int = 0,
        var hierarchicalDurationMs: Long = 0L,
        var coverKey: String = "",
        val children: LinkedHashSet<String> = LinkedHashSet(),
    )

    fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')

    fun storageRoot(path: String): String? {
        val normalized = normalize(path)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated") {
            return "/storage/emulated/${parts[2]}"
        }
        if (parts.size >= 2 && parts[0] == "storage") {
            return "/storage/${parts[1]}"
        }
        if (parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw") {
            return "/mnt/media_rw/${parts[2]}"
        }
        return null
    }

    fun topHierarchyRoot(directory: String): String {
        val normalized = normalize(directory)
        val base = storageRoot(normalized) ?: return normalized.substringBeforeLast('/', normalized)
        val relative = normalized.removePrefix(base).trimStart('/')
        val first = relative.substringBefore('/', relative)
        return if (first.isBlank()) base else "$base/$first"
    }

    fun ancestors(directory: String): List<String> {
        val leaf = normalize(directory)
        val top = topHierarchyRoot(leaf)
        val result = ArrayList<String>()
        var cursor = leaf
        while (cursor.isNotBlank()) {
            result += cursor
            if (cursor == top) break
            val parent = cursor.substringBeforeLast('/', "")
            if (parent.isBlank() || parent == cursor) break
            cursor = parent
        }
        result.reverse()
        return result
    }

    val nodes = LinkedHashMap<String, MutableNode>()
    songs.forEach { song ->
        val directory = normalize(song.path.substringBeforeLast('/', ""))
        if (directory.isBlank()) return@forEach
        val chain = ancestors(directory)
        chain.forEachIndexed { index, path ->
            val parentPath = chain.getOrNull(index - 1)
            val node = nodes.getOrPut(path) {
                MutableNode(path = path, parentPath = parentPath, depth = index)
            }
            if (node.parentPath == null && parentPath != null) node.parentPath = parentPath
            node.hierarchicalSongs += 1
            node.hierarchicalDurationMs += song.duration.coerceAtLeast(0L)
            if (node.coverKey.isBlank() && song.coverKey.isNotBlank()) node.coverKey = song.coverKey
            if (parentPath != null) {
                nodes.getOrPut(parentPath) {
                    MutableNode(
                        path = parentPath,
                        parentPath = chain.getOrNull(index - 2),
                        depth = (index - 1).coerceAtLeast(0),
                    )
                }.children += path
            }
        }
        nodes[directory]?.directSongs = (nodes[directory]?.directSongs ?: 0) + 1
    }

    return nodes.values
        .map { node ->
            FolderHierarchyNode(
                id = stableFolderHierarchyId(node.path),
                parentId = node.parentPath?.let(::stableFolderHierarchyId) ?: 0L,
                path = node.path,
                name = node.path.substringAfterLast('/').ifBlank { node.path },
                parentLabel = node.parentPath?.substringAfterLast('/').orEmpty(),
                directSongCount = node.directSongs,
                hierarchicalSongCount = node.hierarchicalSongs,
                hierarchicalDurationMs = node.hierarchicalDurationMs,
                childFolderCount = node.children.size,
                coverKey = node.coverKey,
                depth = node.depth,
            )
        }
        .sortedWith(
            compareBy<FolderHierarchyNode> { it.parentId }
                .thenBy { CjkSortUtils.sortKey(it.name) }
                .thenBy { it.path }
        )
}
