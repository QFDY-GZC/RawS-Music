package com.rawsmusic.ui.treeview

class NodeNotFoundException(id: Any) :
    RuntimeException("The tree does not contain the node specified: $id")
