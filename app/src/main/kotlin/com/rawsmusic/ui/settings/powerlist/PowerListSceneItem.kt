package com.rawsmusic.ui.settings.powerlist

/**
 * Poweramp's com.maxmpz.widget.list.C equivalent.
 *
 * Item views that implement this interface own their internal scene transition.
 * The outer PowerList only supplies scene ids, item bounds and transition progress,
 * exactly like Poweramp's B.m1209() -> C.G0()/R0()/mo2931() path.
 */
interface PowerListSceneItem {
    fun G0(sceneId: Int, width: Int, height: Int)
    fun H(completeToTarget: Boolean)
    fun L(): Int
    fun R0(sceneId: Int, width: Int, height: Int): Boolean
    fun mo2931(progress: Float)

    /**
     * Poweramp's B.m1209() expects the item's current scene to match the
     * primary slot before G0(otherScene) starts the internal scene transition.
     */
    fun syncPrimaryScene(sceneId: Int) = Unit

    companion object {
        const val SCENE_SMALL = 0
        const val SCENE_NORMAL = 1
        const val SCENE_ZOOMED = 2
        const val SCENE_GRID = 3
    }
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
