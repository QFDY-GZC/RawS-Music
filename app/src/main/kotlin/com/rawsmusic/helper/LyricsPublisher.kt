package com.rawsmusic.helper

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService

/**
 * 统一歌词发布出口。
 *
 * 每次切歌先通过 Lyricon 的纯文本通道同步清空旧词幕；
 * 完整歌词加载完成后只发送一次结构化 Song，避免空 Song 与完整 Song 异步解析乱序。
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

        PlayerService.updateLyrics(null, song)
        pushServiceLyrics()

        // Lyricon 中央端会异步解析 setSong。切歌时若连续发送“空 Song → 完整 Song”，
        // 两次解析可能乱序，最终反而被空 Song 覆盖。这里改走同步的 sendText(null) 清场，
        // 完整歌词加载完成后再发送唯一一次结构化 Song。
        LyriconProviderManager.beginSong(song)
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
        PlayerService.updateLyrics(lyrics.takeUnless { it.isEmpty }, song)

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
        PlayerService.updateLyrics(null, lastSong)
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
