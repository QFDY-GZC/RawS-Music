package com.rawsmusic.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.rawsmusic.compose.player.PlayerScene
import com.rawsmusic.compose.player.PlayerSceneState
import com.rawsmusic.compose.player.rememberPlayerSceneState
import kotlinx.coroutines.CoroutineScope

/**
 * 应用状态管理
 * 替代原版 MainActivity 的状态管理
 *
 * 管理播放器场景、主容器导航、播放状态等
 */
@Stable
class AppState(
    private val scope: CoroutineScope
) {
    // ==================== 播放器场景状态 ====================

    /** 播放器场景状态 */
    val playerSceneState = PlayerSceneState(scope)

    // ==================== 播放状态 ====================

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 当前播放位置 */
    var currentPosition by mutableStateOf(0)

    /** 总时长 */
    var totalDuration by mutableStateOf(0)

    /** 播放进度 (0..1) */
    val playbackProgress: Float
        get() = if (totalDuration > 0) {
            currentPosition.toFloat() / totalDuration.toFloat()
        } else {
            0f
        }

    // ==================== 歌曲信息 ====================

    /** 当前歌曲标题 */
    var currentTitle by mutableStateOf("")

    /** 当前歌曲艺术家 */
    var currentArtist by mutableStateOf("")

    /** 当前歌曲封面路径 */
    var currentCoverPath by mutableStateOf<String?>(null)

    // ==================== 方法 ====================

    /**
     * 打开播放器
     */
    fun openPlayer() {
        playerSceneState.transitionToScene(PlayerScene.PLAYER)
    }

    /**
     * 关闭播放器
     */
    fun closePlayer() {
        playerSceneState.transitionToScene(PlayerScene.MAIN)
    }

    /**
     * 打开歌词
     */
    fun openLyric() {
        playerSceneState.transitionToScene(PlayerScene.LYRIC)
    }

    /**
     * 打开队列
     */
    fun openQueue() {
        playerSceneState.transitionToScene(PlayerScene.QUEUE)
    }

    /**
     * 打开音效
     */
    fun openEffects() {
        playerSceneState.transitionToScene(PlayerScene.EFFECTS)
    }

    /**
     * 切换播放状态
     */
    fun togglePlay() {
        isPlaying = !isPlaying
    }

    /**
     * 更新播放信息
     */
    fun updatePlayback(title: String, artist: String, duration: Int) {
        currentTitle = title
        currentArtist = artist
        totalDuration = duration
        currentPosition = 0
    }

    /**
     * 重置状态
     */
    fun reset() {
        playerSceneState.reset()
        isPlaying = false
        currentPosition = 0
        totalDuration = 0
        currentTitle = ""
        currentArtist = ""
        currentCoverPath = null
    }
}

/**
 * 记住 AppState
 */
@Composable
fun rememberAppState(): AppState {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppState(scope) }
}
