package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import com.rawsmusic.core.ui.widget.powerlist.ListZoomParams

/**
 * Compose 状态管理
 * 对应原版的状态管理系统
 *
 * 管理应用的各种状态
 */
@Stable
class ComposeState {
    // ==================== 列表状态 ====================

    /** 列表状态 */
    val listState = ComposeListState()

    // ==================== 双引擎状态 ====================

    /** 双引擎状态 */
    val dualEngineState = DualEngineState()

    // ==================== 场景状态 ====================

    /** 当前场景 */
    var currentScene by mutableStateOf(SceneType.HOME)

    /** 目标场景 */
    var targetScene by mutableStateOf(SceneType.HOME)

    /** 是否正在过渡 */
    var isTransitioning by mutableStateOf(false)

    /** 过渡进度 (0..1) */
    var transitionProgress by mutableStateOf(0f)

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

    // ==================== 音量状态 ====================

    /** 音量 (0..1) */
    var volume by mutableStateOf(1f)

    /** 是否静音 */
    var isMuted by mutableStateOf(false)

    // ==================== 播放模式 ====================

    /** 播放模式 */
    var playMode by mutableStateOf(PlayMode.SEQUENCE)

    /** 是否随机播放 */
    val isShuffle: Boolean
        get() = playMode == PlayMode.SHUFFLE

    /** 是否单曲循环 */
    val isRepeatOne: Boolean
        get() = playMode == PlayMode.REPEAT_ONE

    /** 是否列表循环 */
    val isRepeatAll: Boolean
        get() = playMode == PlayMode.REPEAT_ALL

    // ==================== 方法 ====================

    /**
     * 切换场景
     *
     * @param scene 目标场景
     */
    fun switchScene(scene: SceneType) {
        if (scene == currentScene || isTransitioning) return

        targetScene = scene
        isTransitioning = true
        transitionProgress = 0f

        // 模拟动画完成
        currentScene = scene
        isTransitioning = false
        transitionProgress = 1f
    }

    /**
     * 切换播放状态
     */
    fun togglePlay() {
        isPlaying = !isPlaying
    }

    /**
     * 切换静音
     */
    fun toggleMute() {
        isMuted = !isMuted
    }

    /**
     * 切换播放模式
     */
    fun togglePlayMode() {
        playMode = when (playMode) {
            PlayMode.SEQUENCE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENCE
        }
    }

    /**
     * 设置播放位置
     *
     * @param position 位置 (ms)
     */
    fun seekTo(position: Int) {
        currentPosition = position.coerceIn(0, totalDuration)
    }

    /**
     * 重置状态
     */
    fun reset() {
        listState.resetGestureState()
        dualEngineState.reset()
        currentScene = SceneType.HOME
        targetScene = SceneType.HOME
        isTransitioning = false
        transitionProgress = 0f
        isPlaying = false
        currentPosition = 0
        totalDuration = 0
        volume = 1f
        isMuted = false
        playMode = PlayMode.SEQUENCE
    }
}

/**
 * 场景类型
 */
enum class SceneType(val label: String) {
    HOME("首页"),
    SONGS("歌曲"),
    ALBUMS("专辑"),
    ARTISTS("艺术家"),
    PLAYLISTS("播放列表"),
    FOLDERS("文件夹"),
    PLAYER("播放器"),
    LYRIC("歌词"),
    QUEUE("队列"),
    ALBUM_DETAIL("专辑详情"),
    EFFECTS("音效")
}

/**
 * 播放模式
 */
enum class PlayMode {
    /** 顺序播放 */
    SEQUENCE,
    /** 列表循环 */
    REPEAT_ALL,
    /** 单曲循环 */
    REPEAT_ONE,
    /** 随机播放 */
    SHUFFLE
}

/**
 * 记住 ComposeState
 */
@Composable
fun rememberComposeState(): ComposeState {
    return remember { ComposeState() }
}
