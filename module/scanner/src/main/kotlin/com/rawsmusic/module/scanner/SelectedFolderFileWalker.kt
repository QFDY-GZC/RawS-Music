package com.rawsmusic.module.scanner

import java.io.File

/**
 * Enumerates every readable descendant of user-selected folders.
 *
 * The walker is intentionally independent from MediaStore so a folder selection always means
 * the complete subtree, including files that the system media database has not indexed yet.
 */
internal object SelectedFolderFileWalker {

    internal data class Report(
        val files: List<File>,
        val skippedDirectories: Set<String>,
        val directorySnapshots: Map<String, Long>,
        val failedDirectories: Set<String>
    )

    fun collect(
        rootPaths: List<String>,
        excludedPaths: Set<String> = emptySet(),
        acceptFile: (File) -> Boolean
    ): List<File> = collectReport(rootPaths, excludedPaths, acceptFile).files

    fun collectReport(
        rootPaths: List<String>,
        excludedPaths: Set<String> = emptySet(),
        acceptFile: (File) -> Boolean,
        shouldSkipDirectory: (File, Long) -> Boolean = { _, _ -> false },
        shouldScanFile: (File) -> Boolean = { true }
    ): Report {
        val excluded = excludedPaths.mapTo(HashSet()) { normalize(it) }
        val visitedDirectories = HashSet<String>()
        val result = ArrayList<File>()
        val skippedDirectories = LinkedHashSet<String>()
        val directorySnapshots = LinkedHashMap<String, Long>()
        val failedDirectories = LinkedHashSet<String>()

        rootPaths
            .asSequence()
            .map(::File)
            .forEach { root ->
                if (!root.exists() || !root.isDirectory) {
                    failedDirectories += normalize(root.path)
                    return@forEach
                }
                walk(
                    root,
                    excluded,
                    visitedDirectories,
                    result,
                    acceptFile,
                    shouldSkipDirectory,
                    shouldScanFile,
                    skippedDirectories,
                    directorySnapshots,
                    failedDirectories
                )
            }

        return Report(result, skippedDirectories, directorySnapshots, failedDirectories)
    }

    private fun walk(
        directory: File,
        excludedPaths: Set<String>,
        visitedDirectories: MutableSet<String>,
        result: MutableList<File>,
        acceptFile: (File) -> Boolean,
        shouldSkipDirectory: (File, Long) -> Boolean,
        shouldScanFile: (File) -> Boolean,
        skippedDirectories: MutableSet<String>,
        directorySnapshots: MutableMap<String, Long>,
        failedDirectories: MutableSet<String>
    ) {
        val normalizedDirectory = normalize(directory.path)
        if (!visitedDirectories.add(normalizedDirectory)) return

        val modifiedAt = directory.lastModified()
        val skipUnchangedFiles = shouldSkipDirectory(directory, modifiedAt)
        if (skipUnchangedFiles) skippedDirectories += normalizedDirectory
        if (modifiedAt > 0L) directorySnapshots[normalizedDirectory] = modifiedAt

        val children = runCatching { directory.listFiles() }.getOrNull()
        if (children == null) {
            failedDirectories += normalizedDirectory
            return
        }
        children.forEach { child ->
            if (child.name.startsWith(".") || child.name.startsWith("_")) return@forEach
            when {
                child.isDirectory -> walk(
                    child,
                    excludedPaths,
                    visitedDirectories,
                    result,
                    acceptFile,
                    shouldSkipDirectory,
                    shouldScanFile,
                    skippedDirectories,
                    directorySnapshots,
                    failedDirectories
                )
                child.isFile && normalize(child.path) !in excludedPaths && acceptFile(child) &&
                    (!skipUnchangedFiles || shouldScanFile(child)) -> {
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
