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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val onLyricsPrepared: (AudioFile, LyricData, Song?) -> Unit = { _, _, _ -> },
    private val onLyricsInvalidated: (AudioFile) -> Unit = { },
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
    private var displayedLyricSongKey: String? = null
    private var lastSong: AudioFile? = null
    private var publisherSongKey: String? = null
    private var lyricBuildGeneration: Int = 0
    private var externalIdentityGeneration: Int = 0
    private var externalIdentityJob: Job? = null
    private val externalIdentityMutex = Mutex()

    private val publisher = LyricsPublisher(
        getCurrentPositionMs = { getController()?.position?.value ?: 0L },
        isPlaying = { getController()?.playState?.value == PlayState.PLAYING },
        pushServiceLyrics = { serviceBridge.pushLyricsUpdate() }
    )

    init {
        lifecycleScope.launch {
            LyriconProviderManager.displayTranslationState.collect { enabled ->
                displayTranslation = enabled
            }
        }
        lifecycleScope.launch {
            LyriconProviderManager.displayRomaState.collect { enabled ->
                displayRoma = enabled
            }
        }
    }

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
    fun markSongPending(song: AudioFile) {
        val key = song.lyricRequestKey()
        if (key == lastSongKey) return
        lastSongKey = key
        lastSong = song

        // Keep the immutable lyric tree mounted and frozen until the replacement is complete.
        // Disposing it here and composing it again after IO made every track change rebuild the
        // lyric page twice. displayedLyricSongKey is cleared immediately, so no old line can be
        // published under the new media identity while the retained pixels await replacement.
        lyricBuildGeneration++
        displayedLyricSongKey = null
        currentLyricText = ""
        currentLyricTranslation = ""
        onCapsuleTextNeedRefresh()
        // Clear the service/MediaSession lyric payload at the same time as the in-app state. The
        // actual file read remains delayed/coalesced, but notification-bar consumers must never
        // see the previous song's lyric during that interval.
        scheduleExternalSongIdentity(song, startLyricsLoad = false)
    }

    fun loadLyricsForSong(song: AudioFile) {
        val key = song.lyricRequestKey()
        lastSongKey = key
        lastSong = song

        // Keep the previous immutable lyric tree attached while the replacement is read/built.
        // Ownership already moved through lastSongKey, so position ticks cannot advance or
        // republish that old tree. This mirrors the artwork provider's retained-wrapper model:
        // replace the visual generation only after the new one is completely prepared.
        scheduleExternalSongIdentity(song, startLyricsLoad = true)
    }

    private fun scheduleExternalSongIdentity(song: AudioFile, startLyricsLoad: Boolean) {
        val requestKey = song.lyricRequestKey()
        val generation = ++externalIdentityGeneration
        externalIdentityJob?.cancel()
        externalIdentityJob = lifecycleScope.launch(Dispatchers.IO) {
            externalIdentityMutex.withLock {
                if (generation != externalIdentityGeneration || lastSongKey != requestKey) return@withLock
                clearExternalLyrics()
                if (generation != externalIdentityGeneration || lastSongKey != requestKey) return@withLock
                beginPublisherSong(song)
            }
            if (startLyricsLoad && generation == externalIdentityGeneration && lastSongKey == requestKey) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == externalIdentityGeneration && lastSongKey == requestKey) {
                        loader.load(song)
                    }
                }
            }
        }
    }

    private fun beginPublisherSong(song: AudioFile) {
        val key = song.lyricRequestKey()
        if (publisherSongKey == key) return
        publisherSongKey = key
        publisher.beginSong(song)
    }

    /**
     * 歌词加载完成后由 LyricLoadHelper 回调。
     */
    fun setLyrics(requestSong: AudioFile, data: LyricData) {
        val currentSong = getController()?.currentSong?.value
        val expectedKey = lastSongKey
        val requestKey = requestSong.lyricRequestKey()
        val currentKey = currentSong?.lyricRequestKey()

        // LyricLoadHelper 自身已有 generation gate；这里再做一次发布边界校验，
        // 防止取消/切歌交界处的旧读取结果污染当前词幕。
        if (expectedKey == null || requestKey != expectedKey || currentKey != expectedKey) {
            android.util.Log.d(
                "LyricsCoordinator",
                "drop stale lyrics: request=$requestKey expected=$expectedKey current=$currentKey"
            )
            return
        }

        val song = currentSong
        val generation = ++lyricBuildGeneration
        if (data.isEmpty) {
            publishPreparedLyrics(
                requestSong = requestSong,
                data = data,
                preparedSong = null,
                generation = generation,
            )
            return
        }

        // Converting LyricData into Lyricon's immutable row/word tree is pure but allocation-heavy.
        // After a lyric write this used to happen on Main exactly when the user could swipe back to
        // the lyric page. Build the replacement tree on Default and perform one small state swap on
        // Main, keeping the previously displayed tree alive until the new one is fully prepared.
        val title = song.title.ifBlank { song.displayName }
        val artist = song.artist
        val duration = song.duration
        lifecycleScope.launch(Dispatchers.Default) {
            val prepared = data.toLyriconSong(
                name = title,
                artist = artist,
                durationMs = duration,
            )
            withContext(Dispatchers.Main.immediate) {
                publishPreparedLyrics(
                    requestSong = requestSong,
                    data = data,
                    preparedSong = prepared,
                    generation = generation,
                )
            }
        }
    }

    private fun publishPreparedLyrics(
        requestSong: AudioFile,
        data: LyricData,
        preparedSong: Song?,
        generation: Int,
    ) {
        if (generation != lyricBuildGeneration) return
        val expectedKey = lastSongKey ?: return
        val requestKey = requestSong.lyricRequestKey()
        val currentSong = getController()?.currentSong?.value ?: return
        if (requestKey != expectedKey || currentSong.lyricRequestKey() != expectedKey) return

        currentLyricData = data
        lyricSong = preparedSong
        displayedLyricSongKey = requestKey
        // MainActivity still has a legacy LyricData/Song mirror used by the lyric scene entry and
        // timing tools. Publish the exact same prepared generation to that mirror atomically; it
        // must never remain empty/old after the coordinator has accepted the new song.
        onLyricsPrepared(currentSong, data, preparedSong)

        displayTranslation = AppPreferences.Lyricon.displayTranslation
        displayRoma = AppPreferences.Lyricon.displayRoma
        publisher.publish(currentSong, data)
        onApplyLyricColors()

        val pos = getController()?.position?.value ?: 0L
        onPositionChanged(pos)
    }

    /**
     * 播放位置变化时调用。
     */
    fun onPositionChanged(positionMs: Long, updateUiPosition: Boolean = true) {
        val lyricPos = playbackToLyricPosition(positionMs)
        val waitingForCurrentLyrics = lyricSong != null && displayedLyricSongKey != lastSongKey
        if (updateUiPosition && !waitingForCurrentLyrics) {
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
        val newState = !displayTranslation
        LyriconProviderManager.setDisplayTranslation(newState)
        displayTranslation = newState
    }

    fun toggleRoma() {
        val newState = !displayRoma
        LyriconProviderManager.setDisplayRoma(newState)
        displayRoma = newState
    }

    fun resendToLyricon() {
        publisher.resendToLyricon()
    }

    private fun updateExternalCurrentLine(lyricPos: Long) {
        // A track change can be acknowledged before its lyric tree is rebuilt. Keep the old tree
        // visually frozen and never republish one of its lines under the new media identity.
        if (displayedLyricSongKey != lastSongKey) return
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
