package com.rawsmusic.core.ui.widget.bitmaps

import androidx.compose.runtime.Composable
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.core.ui.scene.bottomNavigationRoot
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.data.prefs.HdrScene

private fun NavScene.hdrScene(): HdrScene = when (bottomNavigationRoot()) {
    NavScene.HOME -> HdrScene.HOME
    NavScene.SETTINGS, NavScene.AUDIO_EFFECTS -> HdrScene.SETTINGS
    else -> HdrScene.LIBRARY
}

/** Reads endpoints only: never subscribes the root to gesture/animation progress. */
@Composable
fun MainSceneHdrWindow(navigation: NavigationState, player: PlayerSceneController, fullCover: Boolean) {
    fun map(scene: PlayerSceneController.Scene): HdrScene = when (scene) {
        PlayerSceneController.Scene.MAIN -> navigation.currentScene.hdrScene()
        PlayerSceneController.Scene.LYRIC -> HdrScene.LYRICS
        else -> HdrScene.PLAYER
    }
    val current: HdrScene
    val transition: HdrScene?
    if (fullCover) {
        current = HdrScene.PLAYER
        transition = null
    } else if (player.composeIsTransitioning) {
        current = map(player.composeFromScene)
        transition = map(player.composeToScene)
    } else if (player.currentScene != PlayerSceneController.Scene.MAIN) {
        current = map(player.currentScene)
        transition = null
    } else if (navigation.isTransitioning) {
        current = navigation.transitionFromScene.hdrScene()
        transition = navigation.transitionToScene.hdrScene()
    } else {
        current = navigation.currentScene.hdrScene()
        transition = navigation.backPreviewScene?.hdrScene()
    }
    SceneHdrWindow(current, transition)
}
