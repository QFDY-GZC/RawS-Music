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

    var displayTranslation by mutableStateOf(AppPreferences.Lyricon.displayTranslation)
        private set

    var displayRoma by mutableStateOf(AppPreferences.Lyricon.displayRoma)
        private set

    /** 当前行歌词文本，供胶囊/通知使用 */
    var currentLyricText by mutableStateOf("")
        private set

    private var currentLyricData by mutableStateOf(LyricData())
    private var lyricsNeedSeekTo = false
    private var lastSongKey: String? = null
    private var lastSong: AudioFile? = null

    private fun AudioFile.lyricKey(): String = "${path}|${id}|${duration}"

    private val publisher = LyricsPublisher(
        getCurrentPositionMs = { getController()?.position?.value ?: 0L },
        getLyricOffsetMs = { getController()?.lyricManualOffsetMs?.toLong() ?: 0L },
        isPlaying = { getController()?.playState?.value == PlayState.PLAYING },
        pushServiceLyrics = { serviceBridge.pushLyricsUpdate() }
    )

    private val loader = LyricLoadHelper(
        context = context,
        scope = lifecycleScope,
        setLyricEnabled = onLyricEnabledChanged,
        getCurrentSong = { getController()?.currentSong?.value },
        setComposeLyricData = { setLyrics(it) },
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
            currentLyricData = LyricData()
            lyricSong = null
            clearExternalLyrics()
            LyriconProviderManager.setPosition(0L)
        }

        // 不先发 metadata-only，等歌词加载完一次性发完整 Song
        // 避免 Lyricon fork 缓存空歌词状态
        loader.load(song)
    }

    /**
     * 歌词加载完成后由 LyricLoadHelper 回调。
     */
    fun setLyrics(data: LyricData) {
        val song = getController()?.currentSong?.value ?: lastSong
        currentLyricData = data

        lyricSong = if (song != null && !data.isEmpty) {
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
        if (song != null) {
            publisher.publish(song, data)
        }

        onApplyLyricColors()

        // 立即用当前播放位置推一次当前行，不等下一个 position tick
        val pos = getController()?.position?.value ?: 0L
        onPositionChanged(pos)
    }

    /**
     * 播放位置变化时调用。
     */
    fun onPositionChanged(positionMs: Long) {
        val lyricOffset = getController()?.lyricManualOffsetMs?.toLong() ?: 0L
        val lyricPos = (positionMs - lyricOffset).coerceAtLeast(0L)
        lyricPositionMs = lyricPos
        updateExternalCurrentLine(lyricPos)
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

        if (lineText == currentLyricText) return

        currentLyricText = lineText
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
