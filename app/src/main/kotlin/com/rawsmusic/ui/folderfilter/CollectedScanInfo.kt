package com.rawsmusic.ui.folderfilter

import com.rawsmusic.ui.treeview.InMemoryTree
import java.util.TreeSet

class CollectedScanInfo(
    val initialFolder: FolderInfo?,
    private val selectedPaths: TreeSet<String>
) {
    val sequentiallyAddedNodes = ArrayList<FolderInfo>()
    var isInvalid: Boolean = false
    var debugFolderCount: Int = 0

    fun addNode(folderInfo: FolderInfo) {
        sequentiallyAddedNodes.add(folderInfo)
        debugFolderCount++
    }

    fun commitToTree(tree: InMemoryTree<FolderInfo>) {
        for (fi in sequentiallyAddedNodes) {
            try {
                val nodeInfo = tree.getNodeInfo(fi)
                if (!nodeInfo.hasChildren) {
                    fi.checked = selectedPaths.contains(fi.fullPath)
                    fi.partiallyChecked = false
                } else {
                    val isSelected = selectedPaths.contains(fi.fullPath)
                    fi.checked = isSelected
                    if (!isSelected) {
                        checkChildrenSelected(tree, fi)
                    }
                }
            } catch (_: Exception) {
                fi.checked = selectedPaths.contains(fi.fullPath)
            }
        }
    }

    private fun checkChildrenSelected(tree: InMemoryTree<FolderInfo>, fi: FolderInfo) {
        try {
            val node = tree.getTreeNode(fi)
            var anyChildChecked = false
            for (child in node.children) {
                val childFi = child.data ?: continue
                if (childFi is EmptyFolderPlaceholder) continue
                if (selectedPaths.contains(childFi.fullPath)) {
                    anyChildChecked = true
                    break
                }
            }
            fi.partiallyChecked = anyChildChecked
        } catch (_: Exception) {}
    }
}
