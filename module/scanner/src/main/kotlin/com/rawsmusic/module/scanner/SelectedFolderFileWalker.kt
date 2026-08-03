package com.rawsmusic.module.scanner

import java.io.File

/**
 * Enumerates every readable descendant of user-selected folders.
 *
 * The walker is intentionally independent from MediaStore so a folder selection always means
 * the complete subtree, including files that the system media database has not indexed yet.
 */
internal object SelectedFolderFileWalker {

    fun collect(
        rootPaths: List<String>,
        excludedPaths: Set<String> = emptySet(),
        acceptFile: (File) -> Boolean
    ): List<File> {
        val excluded = excludedPaths.mapTo(HashSet()) { normalize(it) }
        val visitedDirectories = HashSet<String>()
        val result = ArrayList<File>()

        rootPaths
            .asSequence()
            .map(::File)
            .filter { it.exists() && it.isDirectory }
            .forEach { root ->
                walk(root, excluded, visitedDirectories, result, acceptFile)
            }

        return result
    }

    private fun walk(
        directory: File,
        excludedPaths: Set<String>,
        visitedDirectories: MutableSet<String>,
        result: MutableList<File>,
        acceptFile: (File) -> Boolean
    ) {
        if (!visitedDirectories.add(normalize(directory.path))) return

        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            if (child.name.startsWith(".") || child.name.startsWith("_")) return@forEach
            when {
                child.isDirectory -> walk(
                    child,
                    excludedPaths,
                    visitedDirectories,
                    result,
                    acceptFile
                )
                child.isFile && normalize(child.path) !in excludedPaths && acceptFile(child) -> {
                    result += child
                }
            }
        }
    }

    internal fun normalize(path: String): String =
        runCatching { File(path).canonicalPath }
            .getOrElse { File(path).absolutePath }
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase()
}
