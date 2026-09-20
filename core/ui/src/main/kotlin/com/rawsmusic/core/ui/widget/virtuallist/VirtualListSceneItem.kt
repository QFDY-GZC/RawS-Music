package com.rawsmusic.core.ui.widget.virtuallist

object VirtualListSceneItem {
    const val SCENE_SMALL = 0
    const val SCENE_NORMAL = 1
    const val SCENE_ZOOMED = 2
    const val SCENE_GRID = 3
}

fun sceneIdForZoomIndex(index: ListZoomIndex): Int = when (index) {
    ListZoomIndex.SMALL -> VirtualListSceneItem.SCENE_SMALL
    ListZoomIndex.NORMAL -> VirtualListSceneItem.SCENE_NORMAL
    ListZoomIndex.ZOOMED -> VirtualListSceneItem.SCENE_ZOOMED
}

fun sceneIdForZoomParams(params: ListZoomParams): Int = when {
    params.coverSizeDp <= 32f -> VirtualListSceneItem.SCENE_SMALL
    params.coverSizeDp >= 120f -> VirtualListSceneItem.SCENE_ZOOMED
    else -> VirtualListSceneItem.SCENE_NORMAL
}
