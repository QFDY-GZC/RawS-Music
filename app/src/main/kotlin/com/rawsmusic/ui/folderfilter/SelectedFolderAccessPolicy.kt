package com.rawsmusic.ui.folderfilter

/**
 * Requests a tree grant only for folders that are actually inaccessible.
 *
 * Reference follows the same capability-first split: Android 10+ may use SAF for an
 * unavailable folder, but a readable filesystem folder is not forced through a system
 * picker merely because of the OS version. Android 9 and below keep the classic storage
 * permission path.
 */
internal object SelectedFolderAccessPolicy {
    fun pathsRequiringSaf(
        sdkInt: Int,
        hasAllFilesAccess: Boolean,
        selectedPaths: Collection<String>,
        uriByGrantedPath: Map<String, String>,
        directlyReadablePaths: Collection<String>
    ): List<String> {
        if (sdkInt < 29 || hasAllFilesAccess) return emptyList()
        val grantedRoots = uriByGrantedPath
            .filterValues { it.isNotBlank() }
            .keys
            .map(::normalize)
        val readableRoots = directlyReadablePaths.map(::normalize)

        return selectedPaths
            .map(::normalize)
            .filter { selected ->
                !isCoveredBy(selected, grantedRoots) &&
                    !isCoveredBy(selected, readableRoots)
            }
    }

    private fun isCoveredBy(selected: String, roots: Collection<String>): Boolean =
        roots.any { root -> selected == root || selected.startsWith("$root/") }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim().trimEnd('/')
}
