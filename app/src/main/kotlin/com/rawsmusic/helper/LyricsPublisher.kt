package com.rawsmusic.helper

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerService

/**
 * 统一歌词发布出口。
 *
 * publish() 只在有完整歌词时才通知 Lyricon，不发送 lyrics=0。
 * pushSongMetadata() 不再向 Lyricon 推 metadata-only song。
 */
class LyricsPublisher(
    private val getCurrentPositionMs: () -> Long = { 0L },
    private val getLyricOffsetMs: () -> Long = { 0L },
    private val isPlaying: () -> Boolean = { false },
    private val pushServiceLyrics: () -> Unit = {}
) {
    private var lastSong: AudioFile? = null
    private var lastLyrics: LyricData = LyricData()

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

        // 3. Lyricon 只在有完整歌词时发送
        if (song != null && !lyrics.isEmpty) {
            LyriconProviderManager.setSong(song, lyrics)
        } else {
            Log.d("LyricsPublisher", "skip Lyricon: song=${song?.title}, empty=${lyrics.isEmpty}")
        }

        // 4. 同步状态
        val lyricPos = (getCurrentPositionMs() - getLyricOffsetMs()).coerceAtLeast(0L)
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

        if (song != null && !lyrics.isEmpty) {
            LyriconProviderManager.setSong(song, lyrics)
        } else {
            Log.d("LyricsPublisher", "skip resend: song=${song?.title}, empty=${lyrics.isEmpty}")
        }

        LyriconProviderManager.setPlaybackState(isPlaying())
        LyriconProviderManager.setPosition(
            (getCurrentPositionMs() - getLyricOffsetMs()).coerceAtLeast(0L)
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
