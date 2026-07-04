package com.rawsmusic.module.player

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.service.addConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object LyriconProviderManager {

    private const val TAG = "LyriconProvider"

    private var provider: LyriconProvider? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isInitialized = false

    // 缓存最近一次 setSong 的参数，用于 resendLastSong
    private var lastSong: com.rawsmusic.core.common.model.AudioFile? = null
    private var lastLyricData: com.rawsmusic.core.common.model.LyricData? = null
    private var lastPositionMs: Long = 0L
    private var lastPlaying: Boolean = false
    private var lastSentSignature: String? = null

    private fun com.rawsmusic.core.common.model.AudioFile.lyriconStableId(): String {
        return path.ifBlank { id.toString() } + "|" + duration + "|" + fileSize + "|" + dateModified
    }

    var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set

    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null

    var onProviderConnected: (() -> Unit)? = null

    fun init(context: Context, appIconResId: Int = 0) {
        if (!AppPreferences.Lyricon.enabled) {
            Log.d(TAG, "Lyricon provider disabled")
            return
        }

        if (isInitialized && provider != null) {
            Log.d(TAG, "Provider already initialized")
            return
        }

        try {
            val logo = if (appIconResId != 0) {
                ProviderLogo.fromDrawable(context, appIconResId)
            } else null

            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = context.packageName,
                playerPackageName = context.packageName,
                logo = logo
            )

            provider?.service?.addConnectionListener {
                onConnected { _ ->
                    Log.d(TAG, "Connected to Lyricon")
                    connectionStatus = ConnectionStatus.CONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                    onProviderConnected?.invoke()
                }
                onReconnected { _ ->
                    Log.d(TAG, "Reconnected to Lyricon")
                    connectionStatus = ConnectionStatus.CONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                    onProviderConnected?.invoke()
                }
                onDisconnected { _ ->
                    Log.d(TAG, "Disconnected from Lyricon")
                    connectionStatus = ConnectionStatus.DISCONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
                onConnectTimeout { _ ->
                    Log.d(TAG, "Connection timeout")
                    connectionStatus = ConnectionStatus.DISCONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
            }

            provider?.register()
            connectionStatus = ConnectionStatus.CONNECTING
            onConnectionStatusChanged?.invoke(connectionStatus)
            isInitialized = true
            Log.d(TAG, "Provider registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init provider", e)
        }
    }

    fun destroy() {
        try {
            stopPositionSync()
            provider?.destroy()
            provider = null
            isInitialized = false
            connectionStatus = ConnectionStatus.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy provider", e)
        }
    }

    /**
     * 重发最近一次缓存的歌曲+歌词。
     * 用于 provider 重连后恢复状态。
     */
    fun resendLastSong() {
        lastSentSignature = null  // 强制重发
        setSong(lastSong, lastLyricData)
    }

    fun setSong(
        song: com.rawsmusic.core.common.model.AudioFile?,
        lyricData: com.rawsmusic.core.common.model.LyricData?
    ) {
        if (song == null) {
            lastSong = null
            lastLyricData = null
            lastPositionMs = 0L
            Log.d(TAG, "setSong ignored: song=null")
            return
        }

        val finalLyricData = lyricData?.takeUnless { it.isEmpty }

        val oldKey = lastSong?.let { "${it.path}|${it.id}|${it.duration}" }
        val newKey = "${song.path}|${song.id}|${song.duration}"
        val isNewSong = oldKey != newKey

        if (isNewSong) {
            lastPositionMs = 0L
        }

        lastSong = song
        lastLyricData = finalLyricData

        val player = provider?.player ?: return
        val stableId = song.lyriconStableId()

        // 签名去重：和 Halcyon 一致，避免重复发送相同数据
        val signature = "${stableId}|${finalLyricData?.lines?.size ?: 0}|${finalLyricData?.lines?.firstOrNull()?.timeStamp}|${finalLyricData?.lines?.lastOrNull()?.timeStamp}"
        if (signature == lastSentSignature) {
            Log.d(TAG, "setSong skipped: duplicate signature, song=${song.title}")
            player.setPosition(lastPositionMs)
            player.setPlaybackState(lastPlaying)
            return
        }

        // 构造 Song 对象（空歌词时 lyrics=emptyList，有歌词时完整转换）
        val lyriconSong = if (finalLyricData != null) {
            finalLyricData.toLyriconSong(
                id = stableId,
                name = song.title.ifBlank { song.displayName },
                artist = song.artist,
                durationMs = song.duration
            )
        } else {
            io.github.proify.lyricon.lyric.model.Song(
                id = stableId,
                name = song.title.ifBlank { song.displayName },
                artist = song.artist,
                duration = song.duration,
                lyrics = emptyList()
            )
        }

        Log.d(TAG, "setSong: ${song.title}, id=$stableId, songDuration=${song.duration}, " +
            "lyrics=${lyriconSong.lyrics?.size ?: 0}, " +
            "first=${lyriconSong.lyrics?.firstOrNull()?.let { "${it.begin}-${it.end}/${it.duration}" }}, " +
            "last=${lyriconSong.lyrics?.lastOrNull()?.let { "${it.begin}-${it.end}/${it.duration}" }}, " +
            "lastPosition=$lastPositionMs, isNewSong=$isNewSong, lastPlaying=$lastPlaying")

        player.setSong(lyriconSong)
        lastSentSignature = signature
        player.setDisplayTranslation(AppPreferences.Lyricon.displayTranslation)
        player.setDisplayRoma(AppPreferences.Lyricon.displayRoma)
        player.setPosition(lastPositionMs)
        player.setPlaybackState(lastPlaying)
    }

    fun setPlaybackState(isPlaying: Boolean) {
        lastPlaying = isPlaying
        Log.d(TAG, "setPlaybackState: $isPlaying")
        provider?.player?.setPlaybackState(isPlaying)
    }

    fun setPosition(positionMs: Long) {
        lastPositionMs = positionMs.coerceAtLeast(0L)
        Log.d(TAG, "setPosition: $lastPositionMs")
        provider?.player?.setPosition(lastPositionMs)
    }

    fun seekTo(positionMs: Long) {
        provider?.player?.seekTo(positionMs)
    }

    fun setDisplayTranslation(display: Boolean) {
        AppPreferences.Lyricon.displayTranslation = display
        provider?.player?.setDisplayTranslation(display)
    }

    fun setDisplayRoma(display: Boolean) {
        AppPreferences.Lyricon.displayRoma = display
        provider?.player?.setDisplayRoma(display)
    }

    fun startPositionSync(playerController: PlayerController) {
        stopPositionSync()
        positionJob = scope.launch {
            while (isActive) {
                try {
                    val pos = playerController.position.value
                    val lyricOffset = playerController.lyricManualOffsetMs.toLong()
                    val state = playerController.playState.value
                    val lyricPos = (pos - lyricOffset).coerceAtLeast(0L)

                    // 不只在播放时同步；暂停、拖动、seek 后也让外部端拿到当前位置
                    setPosition(lyricPos)
                    setPlaybackState(state == PlayState.PLAYING)
                } catch (_: Exception) {}
                delay(200)
            }
        }
    }

    fun stopPositionSync() {
        positionJob?.cancel()
        positionJob = null
    }

    fun isConnected(): Boolean = connectionStatus == ConnectionStatus.CONNECTED

    fun isEnabled(): Boolean = AppPreferences.Lyricon.enabled

    fun setEnabled(enabled: Boolean) {
        AppPreferences.Lyricon.enabled = enabled
    }
}
