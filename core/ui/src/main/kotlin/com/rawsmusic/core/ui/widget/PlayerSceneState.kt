package com.rawsmusic.core.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放器场景枚举。
 *
 * MAIN: 主界面（播放器容器隐藏）
 * PLAYER: 播放器页面
 * LYRIC: 歌词页面
 * QUEUE: 播放队列
 * ALBUM_DETAIL: 当前歌曲专辑详情
 * FULL_COVER: 全屏封面
 */
enum class PlayerScene {
    MAIN,
    PLAYER,
    LYRIC,
    QUEUE,
    ALBUM_DETAIL,
    FULL_COVER
}

/**
 * 播放器场景状态管理。
 *
 * 管理当前场景和场景切换逻辑。
 */
class PlayerSceneState {
    var currentScene by mutableStateOf(PlayerScene.MAIN)
        private set

    var previousScene by mutableStateOf(PlayerScene.MAIN)
        private set

    fun transitionTo(scene: PlayerScene) {
        if (scene == currentScene) return
        previousScene = currentScene
        currentScene = scene
    }

    fun switchToSilent(scene: PlayerScene) {
        currentScene = scene
    }

    fun openPlayer() = transitionTo(PlayerScene.PLAYER)
    fun openLyric() = transitionTo(PlayerScene.LYRIC)
    fun openQueue() = transitionTo(PlayerScene.QUEUE)
    fun openAlbumDetail() = transitionTo(PlayerScene.ALBUM_DETAIL)
    fun openFullCover() = transitionTo(PlayerScene.FULL_COVER)

    fun backToMain() = transitionTo(PlayerScene.MAIN)
    fun backToPlayer() = transitionTo(PlayerScene.PLAYER)

    fun isPlayerVisible(): Boolean = currentScene != PlayerScene.MAIN
}
