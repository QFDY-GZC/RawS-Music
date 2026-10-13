package com.rawsmusic.module.data.repository

/** Protects delayed library work from resurrecting successfully deleted rows. */
internal class LibraryDeletionFence {
    private val paths = HashSet<String>()
    private val keys = HashSet<String>()

    @Synchronized fun deleted(path: String, key: String, wholePath: Boolean) {
        if (wholePath) paths += path else keys += key
    }

    @Synchronized fun isVisible(path: String, key: String): Boolean =
        path !in paths && key !in keys

    @Synchronized fun needsPhysicalValidation(path: String): Boolean = path in paths

    @Synchronized fun canWrite(path: String, key: String, physicalFilePresent: Boolean): Boolean =
        key !in keys && (path !in paths || physicalFilePresent)

    // A file explicitly restored to disk can be scanned again. Do not clear a
    // deleted CUE row just because its shared source file still exists.
    @Synchronized fun written(path: String) { paths -= path }
}
