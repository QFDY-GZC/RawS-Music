package com.rawsmusic.ui.folderfilter

/** Keeps folder-dialog SAF grants aligned with the currently selected physical roots. */
internal object FolderDialogUriSelectionPolicy {

    fun reconcile(
        currentUris: Set<String>,
        previousDialogMap: Map<String, String>,
        currentDialogMap: Map<String, String>,
        selectedPaths: Collection<String>
    ): Set<String> {
        val selected = selectedPaths.map(::normalize).filter { it.isNotBlank() }
        val previouslyManaged = previousDialogMap.values.filter { it.isNotBlank() }.toSet()
        val independentlyManaged = currentUris - previouslyManaged
        val activeDialogUris = currentDialogMap
            .filterKeys { mappedPath ->
                val mapped = normalize(mappedPath)
                selected.any { selectedPath ->
                    mapped == selectedPath || mapped.startsWith(selectedPath + "/")
                }
            }
            .values
            .filter { it.isNotBlank() }
            .toSet()
        return independentlyManaged + activeDialogUris
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim().trimEnd('/')
}
