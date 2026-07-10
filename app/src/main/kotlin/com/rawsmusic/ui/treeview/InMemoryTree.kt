package com.rawsmusic.ui.treeview

import java.io.Serializable

class InMemoryTree<T> : Serializable {

    private val nodeMap = HashMap<Any, TreeNode<T>>()
    val root = TreeNode<T>(null, null, -1, true)

    private var visibleNodesCache: MutableList<Any>? = null
    private var visibleNodesListCache: List<Any>? = null

    companion object {
        fun <T> setVisibilityRecursive(node: TreeNode<T>, visible: Boolean, recursive: Boolean) {
            for (child in node.children) {
                child.visible = visible
                if (recursive) {
                    setVisibilityRecursive(child, visible, true)
                }
            }
            node.invalidateCache()
        }
    }

    fun getTreeNode(id: Any?): TreeNode<T> {
        return if (id == null) root else findNode(id)
    }

    fun findNode(id: Any): TreeNode<T> {
        return nodeMap[id] ?: throw NodeNotFoundException(id)
    }

    fun addNode(nodeId: T, parentId: T?, level: Int): TreeNode<T> {
        val parent = if (parentId == null) root else nodeMap[parentId]
            ?: throw NodeNotFoundException(parentId!!)
        val node = TreeNode(nodeId, parent, level, parent.visible)
        parent.children.add(node)
        nodeMap[nodeId!!] = node
        parent.invalidateCache()
        return node
    }

    fun getNodeInfo(id: Any): TreeNodeInfo {
        val node = findNode(id)
        val hasChildren = node.children.isNotEmpty()
        val isExpanded = hasChildren && node.children[0].visible
        return TreeNodeInfo(
            id = id,
            level = node.level,
            hasChildren = hasChildren,
            isVisible = node.visible,
            isExpanded = isExpanded
        )
    }

    fun getVisibleNodes(): List<Any> {
        if (visibleNodesCache != null) {
            return visibleNodesListCache!!
        }
        visibleNodesCache = mutableListOf()
        var currentId: Any? = null
        while (true) {
            val node = try {
                getTreeNode(currentId)
            } catch (_: NodeNotFoundException) {
                break
            }
            if (!node.visible) {
                break
            }
            if (currentId != null) {
                visibleNodesCache!!.add(currentId)
            }
            if (node.children.isNotEmpty()) {
                var foundVisibleChild = false
                for (child in node.children) {
                    if (child.visible && child.data != null && nodeMap.containsKey(child.data)) {
                        currentId = child.data
                        foundVisibleChild = true
                        break
                    }
                }
                if (foundVisibleChild) continue
            }
            val next = try {
                getNextSibling(currentId)
            } catch (_: NodeNotFoundException) {
                null
            }
            if (next != null) {
                currentId = next
                continue
            }
            var found = false
            var searchId = currentId
            while (searchId != null) {
                val parentNode = try {
                    findNode(searchId)
                } catch (_: NodeNotFoundException) {
                    break
                }
                val parentParentData = parentNode.parent?.data
                if (parentParentData != null) {
                    val nextParentSibling = try {
                        getNextSibling(parentParentData)
                    } catch (_: NodeNotFoundException) {
                        null
                    }
                    if (nextParentSibling != null) {
                        currentId = nextParentSibling
                        found = true
                        break
                    }
                }
                searchId = parentParentData
            }
            if (!found) break
        }
        visibleNodesListCache = visibleNodesCache!!.toList()
        return visibleNodesListCache!!
    }

    private fun getNextSibling(id: Any?): Any? {
        if (id == null) return null
        val node = getTreeNode(id)
        val parent = node.parent ?: return null
        val siblings = parent.children
        val idx = siblings.indexOfFirst { it.data == id }
        if (idx < 0 || idx + 1 >= siblings.size) return null
        return siblings[idx + 1].data
    }

    fun notifyDataChanged() {
        visibleNodesCache = null
        visibleNodesListCache = null
    }

    fun removeNodeRecursively(node: TreeNode<T>): Boolean {
        var changedVisible = false
        for (child in node.children) {
            if (removeNodeRecursively(child)) changedVisible = true
        }
        synchronized(node) {
            node.children.clear()
            node.invalidateCache()
        }
        val data = node.data
        if (data != null) {
            nodeMap.remove(data)
            if (node.visible) changedVisible = true
        }
        return changedVisible
    }
}
