package com.rawsmusic.ui.treeview

import java.io.Serializable
import java.util.LinkedList

class TreeNode<T>(
    val data: T?,
    val parent: TreeNode<T>?,
    val level: Int,
    var visible: Boolean = true
) : Serializable {

    val children: LinkedList<TreeNode<T>> = LinkedList()
    private var childIdListCache: MutableList<Any>? = null

    fun childIdList(): List<Any> {
        if (childIdListCache == null) {
            childIdListCache = mutableListOf()
            for (child in children) {
                child.data?.let { childIdListCache!!.add(it) }
            }
        }
        return childIdListCache!!
    }

    fun invalidateCache() {
        childIdListCache = null
    }
}
