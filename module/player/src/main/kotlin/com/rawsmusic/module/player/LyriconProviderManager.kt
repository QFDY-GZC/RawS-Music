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
    private const val POSITION_SYNC_INTERVAL_MS = 50

    private var provider: LyriconProvider? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isInitialized = false

    // 缓存最近一次 setSong 的参数，用于 resendLastSong
    private var lastSong: com.rawsmusic.core.common.model.AudioFile? = null
    private var lastLyricData: com.rawsmusic.core.common.model.LyricData? = null
    private var lastPositionMs: Long = 0L
    private var lastPlaying: Boolean = false
    private var lastSentPlaying: Boolean? = null
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
                    lastSentPlaying = null
                    lastSentSignature = null
                    provider?.player?.setPositionUpdateInterval(POSITION_SYNC_INTERVAL_MS)
                    // 不依赖 Activity 回调恢复歌词。manager 自己持有最后一次 Song/LyricData，
                    // 新建 provider 或 Binder 重连后立即补发；随后 UI 回调若再次 resend 会被签名去重。
                    resendLastSong()
                    onConnectionStatusChanged?.invoke(connectionStatus)
                    onProviderConnected?.invoke()
                }
                onReconnected { _ ->
                    Log.d(TAG, "Reconnected to Lyricon")
                    connectionStatus = ConnectionStatus.CONNECTED
                    lastSentPlaying = null
                    lastSentSignature = null
                    provider?.player?.setPositionUpdateInterval(POSITION_SYNC_INTERVAL_MS)
                    // 不依赖 Activity 回调恢复歌词。manager 自己持有最后一次 Song/LyricData，
                    // 新建 provider 或 Binder 重连后立即补发；随后 UI 回调若再次 resend 会被签名去重。
                    resendLastSong()
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
            // 设置页运行时关闭再开启 Lyricon 时，destroy() 会停止旧位置任务。
            // 重新 init 后直接接回 Service 持有的 PlayerController，避免只恢复歌词却不再走位置时钟。
            PlayerController.getInstanceOrNull()?.let(::startPositionSync)
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
            lastSentPlaying = null
            connectionStatus = ConnectionStatus.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy provider", e)
        }
    }

    /**
     * 切歌时立即切换本地缓存身份，并通过 sendText(null) 清掉上一首。
     * 不发送 lyrics=emptyList() 的结构化 Song：Lyricon 中央端会异步解析 setSong，
     * 空 Song 与随后完整 Song 连发存在完成顺序颠倒的竞态。
     */
    fun beginSong(song: com.rawsmusic.core.common.model.AudioFile) {
        sendSongOrClear(song, null)
    }

    /**
     * 重发最近一次缓存的歌曲+歌词。
     * 用于 provider 重连后恢复状态。
     */
    fun resendLastSong() {
        lastSentSignature = null
        lastSentPlaying = null
        val song = lastSong ?: return
        sendSongOrClear(song, lastLyricData)
    }

    fun setSong(
        song: com.rawsmusic.core.common.model.AudioFile?,
        lyricData: com.rawsmusic.core.common.model.LyricData?
    ) {
        if (song == null) {
            lastSong = null
            lastLyricData = null
            lastPositionMs = 0L
            lastSentSignature = null
            provider?.player?.sendText(null)
            Log.d(TAG, "setSong: song=null, cleared remote lyric")
            return
        }
        sendSongOrClear(song, lyricData?.takeUnless { it.isEmpty })
    }

    private fun sendSongOrClear(
        song: com.rawsmusic.core.common.model.AudioFile,
        lyricData: com.rawsmusic.core.common.model.LyricData?
    ) {
        val finalLyricData = lyricData?.takeUnless { it.isEmpty }

        val oldKey = lastSong?.let { "${it.path}|${it.id}|${it.duration}" }
        val newKey = "${song.path}|${song.id}|${song.duration}"
        val isNewSong = oldKey != newKey
        if (isNewSong) lastPositionMs = 0L

        lastSong = song
        lastLyricData = finalLyricData

        val player = provider?.player ?: return
        val stableId = song.lyriconStableId()

        if (finalLyricData == null) {
            val clearSignature = "$stableId|clear"
            if (clearSignature != lastSentSignature) {
                // sendText() 在 Lyricon 中央端是同步事件；它不会和 setSong() 的后台 JSON
                // 解析形成“空结构化 Song 最后覆盖完整 Song”的同类竞态。
                val accepted = player.sendText(null)
                if (accepted) {
                    lastSentSignature = clearSignature
                    Log.d(TAG, "clearSong: ${song.title}, id=$stableId")
                } else {
                    // CachedRemotePlayer 即使断线也会缓存本次 clear。不要把本地签名标成已发送，
                    // 这样重连后的显式 resend 仍有机会补发。
                    lastSentSignature = null
                    Log.w(TAG, "clearSong deferred: remote player not active, song=${song.title}")
                }
            }
            syncPresentationAndPlayback(player)
            return
        }

        val colorCompatibility = AppPreferences.Lyricon.originalTextColorCompatibility
        // 内容 hash + 兼容模式进入签名，在线歌词刷新/翻译更新/模式切换都不会被旧的
        // “行数 + 首尾时间”粗签名误判为重复。
        val signature = "$stableId|${finalLyricData.hashCode()}|plainColor=$colorCompatibility"
        if (signature == lastSentSignature) {
            Log.d(TAG, "setSong skipped: duplicate signature, song=${song.title}")
            syncPresentationAndPlayback(player)
            return
        }

        var lyriconSong = finalLyricData.toLyriconSong(
            id = stableId,
            name = song.title.ifBlank { song.displayName },
            artist = song.artist,
            durationMs = song.duration
        )

        if (colorCompatibility) {
            // Lyricon 的逐字主行使用 background/highlight 两套 paint，normal(primary)
            // 只用于 plain/scroll-only 路径。仅剥离“主行 words”即可让原文重新走 normal
            // 颜色；RawSMusic 自身 LyricData 完全不改，内部卡拉 OK 效果不受影响。
            lyriconSong = lyriconSong.copy(
                lyrics = lyriconSong.lyrics?.map { line ->
                    if (line.words.isNullOrEmpty()) line else line.copy(words = null)
                }
            )
        }

        Log.d(
            TAG,
            "setSong: ${song.title}, id=$stableId, songDuration=${song.duration}, " +
                "lyrics=${lyriconSong.lyrics?.size ?: 0}, " +
                "wordLines=${lyriconSong.lyrics?.count { !it.words.isNullOrEmpty() } ?: 0}, " +
                "colorCompat=$colorCompatibility, lastPosition=$lastPositionMs, " +
                "isNewSong=$isNewSong, lastPlaying=$lastPlaying"
        )

        val accepted = player.setSong(lyriconSong)
        if (accepted) {
            lastSentSignature = signature
        } else {
            // bridge 会缓存 Song 并在重连时 autoSync；这里仍保留“未确认发送”状态，
            // 避免连接瞬断后被 duplicate signature 永久挡住。
            lastSentSignature = null
            Log.w(TAG, "setSong deferred: remote player not active, song=${song.title}")
        }
        syncPresentationAndPlayback(player)
    }

    private fun syncPresentationAndPlayback(player: io.github.proify.lyricon.provider.RemotePlayer) {
        player.setDisplayTranslation(AppPreferences.Lyricon.displayTranslation)
        player.setDisplayRoma(AppPreferences.Lyricon.displayRoma)
        player.setPosition(lastPositionMs)
        player.setPlaybackState(lastPlaying)
        lastSentPlaying = lastPlaying
    }

    fun setPlaybackState(isPlaying: Boolean) {
        lastPlaying = isPlaying
        if (lastSentPlaying == isPlaying) return
        val player = provider?.player ?: return
        lastSentPlaying = isPlaying
        Log.d(TAG, "setPlaybackState: $isPlaying")
        player.setPlaybackState(isPlaying)
    }

    fun setPosition(positionMs: Long) {
        lastPositionMs = positionMs.coerceAtLeast(0L)
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

    fun setOriginalTextColorCompatibility(enabled: Boolean) {
        if (AppPreferences.Lyricon.originalTextColorCompatibility == enabled) return
        AppPreferences.Lyricon.originalTextColorCompatibility = enabled
        // 传输形态发生变化（逐字 ↔ 行级），必须强制刷新当前 Song。
        lastSentSignature = null
        resendLastSong()
    }

    fun startPositionSync(playerController: PlayerController) {
        stopPositionSync()
        // Lyricon 从共享内存读取位置；不设置间隔时部分实现只读取初始值。
        provider?.player?.setPositionUpdateInterval(POSITION_SYNC_INTERVAL_MS)
        positionJob = scope.launch {
            while (isActive) {
                try {
                    val pos = playerController.position.value
                    val state = playerController.playState.value

                    // 不只在播放时同步；暂停、拖动、seek 后也让外部端拿到当前位置
                    setPosition(pos.coerceAtLeast(0L))
                    setPlaybackState(state == PlayState.PLAYING)
                } catch (_: Exception) {}
                delay(POSITION_SYNC_INTERVAL_MS.toLong())
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
