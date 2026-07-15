package com.rawsmusic.helper

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService

/**
 * 统一歌词发布出口。
 *
 * 每次切歌先发布当前歌曲的空歌词身份，立即淘汰上一首的词幕状态；
 * 完整歌词加载完成后再用同一歌曲身份覆盖。
 */
class LyricsPublisher(
    private val getCurrentPositionMs: () -> Long = { 0L },
    private val isPlaying: () -> Boolean = { false },
    private val pushServiceLyrics: () -> Unit = {}
) {
    private var lastSong: AudioFile? = null
    private var lastLyrics: LyricData = LyricData()

    /**
     * 切歌第一时间调用。即使歌词仍在读取，也必须先把所有外部出口切到
     * 当前歌曲，避免无歌词歌曲继续显示/加载上一首的歌词。
     */
    fun beginSong(song: AudioFile) {
        lastSong = song
        lastLyrics = LyricData()

        PlayerService.updateLyrics(null)
        pushServiceLyrics()

        // LyriconProviderManager 的签名包含 stable song id 和歌词数量。先发送
        // lyrics=0 可立即废弃上一首；稍后完整歌词到达时会产生不同签名并覆盖。
        LyriconProviderManager.setSong(song, null)
        LyriconProviderManager.setPosition(0L)
        LyriconProviderManager.setPlaybackState(isPlaying())
    }

    fun publish(
        song: AudioFile?,
        lyrics: LyricData,
        setComposeLyrics: ((LyricData) -> Unit)? = null
    ) {
        lastSong = song
        lastLyrics = lyrics

        setComposeLyrics?.invoke(lyrics)

        // 1. PlayerService 是状态栏 / MediaSession 词幕的数据源
        PlayerService.updateLyrics(lyrics.takeUnless { it.isEmpty })

        // 2. 刷新通知 / MediaSession metadata
        pushServiceLyrics()

        // 3. 有歌词发送完整数据；确认无歌词时也发送当前歌曲的空歌词身份，
        //    不能让 Lyricon 继续保留上一首。
        if (song != null) {
            LyriconProviderManager.setSong(song, lyrics.takeUnless { it.isEmpty })
        } else {
            Log.d("LyricsPublisher", "skip Lyricon: song=null")
        }

        // 4. 同步状态
        val lyricPos = getCurrentPositionMs().coerceAtLeast(0L)
        LyriconProviderManager.setPosition(lyricPos)
        LyriconProviderManager.setPlaybackState(isPlaying())
    }

    fun publishEmpty() {
        lastLyrics = LyricData()
        PlayerService.updateLyrics(null)
        pushServiceLyrics()
        LyriconProviderManager.setPosition(0L)
    }

    fun resendToLyricon() {
        val song = lastSong
        val lyrics = lastLyrics

        if (song != null) {
            LyriconProviderManager.setSong(song, lyrics.takeUnless { it.isEmpty })
        } else {
            Log.d("LyricsPublisher", "skip resend: song=null")
        }

        LyriconProviderManager.setPlaybackState(isPlaying())
        LyriconProviderManager.setPosition(
            getCurrentPositionMs().coerceAtLeast(0L)
        )
    }

    /**
     * 不再向 Lyricon 推 metadata-only song。
     * 状态栏端收到 lyrics=0 后可能固定为标题模式。
     */
    fun pushSongMetadata(song: AudioFile?) {
        lastSong = song
        Log.d("LyricsPublisher", "skip pushSongMetadata: ${song?.title}")
    }
}
