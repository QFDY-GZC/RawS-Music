package com.rawsmusic.core.ui.widget.powerlist

object PowerListSceneItem {
    const val SCENE_SMALL = 0
    const val SCENE_NORMAL = 1
    const val SCENE_ZOOMED = 2
    const val SCENE_GRID = 3
}

fun sceneIdForZoomIndex(index: ListZoomIndex): Int = when (index) {
    ListZoomIndex.SMALL -> PowerListSceneItem.SCENE_SMALL
    ListZoomIndex.NORMAL -> PowerListSceneItem.SCENE_NORMAL
    ListZoomIndex.ZOOMED -> PowerListSceneItem.SCENE_ZOOMED
}

fun sceneIdForZoomParams(params: ListZoomParams): Int = when {
    params.coverSizeDp <= 32f -> PowerListSceneItem.SCENE_SMALL
    params.coverSizeDp >= 120f -> PowerListSceneItem.SCENE_ZOOMED
    else -> PowerListSceneItem.SCENE_NORMAL
}
