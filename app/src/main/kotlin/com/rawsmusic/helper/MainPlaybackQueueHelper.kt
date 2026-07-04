package com.rawsmusic.helper

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.player.PlayerController

/**
 * MainActivity 播放队列拆分：只负责"从当前页面点击歌曲时应该传哪一组队列"。
 * 这样最近添加/搜索/列表页的队列规则不再散落在 Activity 内。
 */
class MainPlaybackQueueHelper(
    private val playerControllerProvider: () -> PlayerController?
) {
    fun playSongFromScene(song: AudioFile, scene: NavScene) {
        val queue = when (scene) {
            NavScene.SONGS -> MusicRepository.songs.value
            NavScene.RECENTLY_ADDED -> recentSongs()
            else -> listOf(song)
        }.ifEmpty { listOf(song) }

        val index = queue.indexOfFirst { it.id == song.id && it.path == song.path }
            .takeIf { it >= 0 } ?: queue.indexOf(song).coerceAtLeast(0)
        playQueue(queue, index)
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int) {
        if (songs.isEmpty()) return
        playerControllerProvider()?.setPlayQueue(songs, startIndex.coerceIn(0, songs.lastIndex))
    }

    private fun recentSongs(): List<AudioFile> {
        val sevenDaysAgo = System.currentTimeMillis() - RECENT_WINDOW_MS
        return MusicRepository.songs.value.filter {
            it.dateAdded > sevenDaysAgo || it.dateModified > sevenDaysAgo
        }
    }

    private companion object {
        private const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
