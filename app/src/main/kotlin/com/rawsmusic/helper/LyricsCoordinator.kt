package com.rawsmusic.helper

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.LyricGetterBridge
import com.rawsmusic.module.player.lyrics.TickerBridge
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope

/**
 * 歌词状态协调器。
 *
 * 管理 Compose 歌词 UI 状态 + 外部歌词桥（Lyricon / Ticker / Bluetooth），
 * 替代 MainActivity 里散落的 currentLyricData / composeLyricSong / composeDisplayXxx 等字段。
 */
class LyricsCoordinator(
    private val context: Context,
    private val lifecycleScope: CoroutineScope,
    private val getController: () -> PlayerController?,
    private val onLyricEnabledChanged: (Boolean) -> Unit,
    private val onApplyLyricColors: () -> Unit,
    private val onCapsuleTextNeedRefresh: () -> Unit,
    private val serviceBridge: PlayerServiceBridgeHelper
) {
    /** 已转换的 Lyricon Song，供 ComposeLyricView 使用 */
    var lyricSong by mutableStateOf<Song?>(null)
        private set

    /** 歌词滚动位置 */
    var lyricPositionMs by mutableLongStateOf(0L)
        private set

    /** 当前歌曲是否包含真实逐字时间轴。仅逐字歌词需要 60 fps 羽化/高亮刷新。 */
    val hasWordTimedLyrics: Boolean
        get() = lyricSong?.lyrics.orEmpty().any { !it.words.isNullOrEmpty() }

    var displayTranslation by mutableStateOf(AppPreferences.Lyricon.displayTranslation)
        private set

    var displayRoma by mutableStateOf(AppPreferences.Lyricon.displayRoma)
        private set

    /** 当前行歌词文本，供胶囊/通知使用 */
    var currentLyricText by mutableStateOf("")
        private set

    var currentLyricTranslation by mutableStateOf("")
        private set

    private var currentLyricData by mutableStateOf(LyricData())
    private var lyricsNeedSeekTo = false
    private var lastSongKey: String? = null
    private var lastSong: AudioFile? = null

    private fun AudioFile.lyricKey(): String = "${path}|${id}|${duration}"

    private val publisher = LyricsPublisher(
        getCurrentPositionMs = { getController()?.position?.value ?: 0L },
        isPlaying = { getController()?.playState?.value == PlayState.PLAYING },
        pushServiceLyrics = { serviceBridge.pushLyricsUpdate() }
    )

    private val loader = LyricLoadHelper(
        context = context,
        scope = lifecycleScope,
        setLyricEnabled = onLyricEnabledChanged,
        getCurrentSong = { getController()?.currentSong?.value },
        setComposeLyricData = { requestSong, data -> setLyrics(requestSong, data) },
        setMiniLyricData = { },
        clearCurrentLyricText = { currentLyricText = "" },
        updateLyricAnchor = { },
        applyLyricColors = onApplyLyricColors,
        lyricsPublisher = publisher
    )

    /**
     * 切歌时调用：先推 metadata，再异步加载歌词。
     */
    fun loadLyricsForSong(song: AudioFile) {
        val key = song.lyricKey()
        val isNewSong = key != lastSongKey
        lastSongKey = key
        lastSong = song

        if (isNewSong) {
            lyricPositionMs = 0L
            currentLyricText = ""
            currentLyricTranslation = ""
            currentLyricData = LyricData()
            lyricSong = null
            clearExternalLyrics()
            onLyricEnabledChanged(false)
            publisher.beginSong(song)
        }

        loader.load(song)
    }

    /**
     * 歌词加载完成后由 LyricLoadHelper 回调。
     */
    fun setLyrics(requestSong: AudioFile, data: LyricData) {
        val currentSong = getController()?.currentSong?.value
        val expectedKey = lastSongKey
        val requestKey = requestSong.lyricKey()
        val currentKey = currentSong?.lyricKey()

        // LyricLoadHelper 自身已有 generation gate；这里再做一次发布边界校验，
        // 防止取消/切歌交界处的旧读取结果污染当前词幕。
        if (expectedKey == null || requestKey != expectedKey || currentKey != expectedKey) {
            android.util.Log.d(
                "LyricsCoordinator",
                "drop stale lyrics: request=$requestKey expected=$expectedKey current=$currentKey"
            )
            return
        }

        val song = currentSong ?: return
        currentLyricData = data

        lyricSong = if (!data.isEmpty) {
            data.toLyriconSong(
                name = song.title.ifBlank { song.displayName },
                artist = song.artist,
                durationMs = song.duration
            )
        } else {
            null
        }

        displayTranslation = AppPreferences.Lyricon.displayTranslation
        displayRoma = AppPreferences.Lyricon.displayRoma

        // 发布到所有出口（PlayerService + Lyricon）
        publisher.publish(song, data)

        onApplyLyricColors()

        // 立即用当前播放位置推一次当前行，不等下一个 position tick
        val pos = getController()?.position?.value ?: 0L
        onPositionChanged(pos)
    }

    /**
     * 播放位置变化时调用。
     */
    fun onPositionChanged(positionMs: Long, updateUiPosition: Boolean = true) {
        val lyricPos = playbackToLyricPosition(positionMs)
        if (updateUiPosition) {
            lyricPositionMs = lyricPos
        }
        updateExternalCurrentLine(lyricPos)
    }

    /** Lyrics use the exact playback timeline; no app-side delay or advance is applied. */
    fun playbackToLyricPosition(positionMs: Long): Long {
        return positionMs.coerceAtLeast(0L)
    }

    /** A lyric timestamp maps directly back to the same playback timestamp. */
    fun lyricToPlaybackPosition(positionMs: Long): Long {
        val controller = getController()
        val durationMs = controller?.duration?.value ?: 0L
        val playbackPosition = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) playbackPosition.coerceAtMost(durationMs) else playbackPosition
    }

    fun markNeedSeekTo() {
        lyricsNeedSeekTo = true
    }

    fun toggleTranslation() {
        val prefs = AppPreferences.Lyricon
        val newState = !prefs.displayTranslation
        prefs.displayTranslation = newState
        prefs.displayRoma = !newState
        displayTranslation = newState
        displayRoma = !newState
    }

    fun resendToLyricon() {
        publisher.resendToLyricon()
    }

    private fun updateExternalCurrentLine(lyricPos: Long) {
        if (currentLyricData.isEmpty) {
            if (currentLyricText.isNotEmpty()) {
                currentLyricText = ""
                currentLyricTranslation = ""
                TickerBridge.clearLyric(context)
                LyricGetterBridge.clearLyric(context)
                BluetoothLyricBridge.clearLyric()
                onCapsuleTextNeedRefresh()
            }
            return
        }

        val lineIdx = currentLyricData.findCurrentLine(lyricPos)
        if (lineIdx < 0) return

        val line = currentLyricData.getLine(lineIdx) ?: return
        val lineText = line.text
        val translation = line.translation.orEmpty()

        if (lineText == currentLyricText && translation == currentLyricTranslation) return

        currentLyricText = lineText
        currentLyricTranslation = translation
        onCapsuleTextNeedRefresh()

        android.util.Log.d("StatusLyric", "line: pos=$lyricPos, idx=$lineIdx, text=$lineText")

        if (lineText.isNotBlank()) {
            TickerBridge.updateLyric(context, lineText, translation)
            LyricGetterBridge.updateLyric(context, lineText, translation)
            BluetoothLyricBridge.updateLyric(lineText, translation)
        }
    }

    private fun clearExternalLyrics() {
        TickerBridge.clearLyric(context)
        LyricGetterBridge.clearLyric(context)
        BluetoothLyricBridge.clearLyric()
    }
}
