package com.rawsmusic.helper

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.scanner.LyricReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 歌词读取器。
 *
 * 只负责从文件读取歌词，读取完成后回调 setComposeLyricData。
 * 所有发布动作（Lyricon / PlayerService / TickerBridge）统一由 LyricsCoordinator 处理。
 */
class LyricLoadHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val setLyricEnabled: (Boolean) -> Unit,
    private val getCurrentSong: () -> AudioFile?,
    private val setComposeLyricData: (AudioFile, LyricData) -> Unit,
    private val setMiniLyricData: (LyricData) -> Unit,
    private val clearCurrentLyricText: () -> Unit,
    private val updateLyricAnchor: () -> Unit,
    private val applyLyricColors: () -> Unit,
    private val lyricsPublisher: LyricsPublisher
) {
    private val loadGeneration = AtomicInteger(0)

    fun load(songPath: String) {
        load(AudioFile(path = songPath))
    }

    fun load(song: AudioFile) {
        if (song.path.isBlank()) {
            val generation = loadGeneration.incrementAndGet()
            clearLyricsIfLatest(generation, song)
            return
        }

        val generation = loadGeneration.incrementAndGet()
        val requestKey = song.lyricRequestKey()
        clearCurrentLyricText()

        scope.launch(Dispatchers.IO) {
            val lyricData = LyricReader.readLyrics(song)
            launch(Dispatchers.Main) {
                if (loadGeneration.get() != generation) return@launch
                val current = getCurrentSong()
                if (current == null || current.lyricRequestKey() != requestKey) return@launch

                val styledLyricData = lyricData.withAnimationFlags()

                setComposeLyricData(song, styledLyricData)
                setMiniLyricData(styledLyricData)
                setLyricEnabled(!styledLyricData.isEmpty)

                if (!styledLyricData.isEmpty) {
                    applyLyricColors()
                } else {
                    clearCurrentLyricText()
                }

                updateLyricAnchor()
            }
        }
    }

    private fun clearLyricsIfLatest(generation: Int, requestSong: AudioFile) {
        if (loadGeneration.get() != generation) return
        val emptyData = LyricData()
        setComposeLyricData(requestSong, emptyData)
        setMiniLyricData(emptyData)
        clearCurrentLyricText()
        setLyricEnabled(false)
        updateLyricAnchor()
    }

    private fun AudioFile.lyricRequestKey(): String {
        return buildString {
            append(path)
            append('|')
            append(cueOffsetMs)
            append('|')
            append(cueEndMs)
            append('|')
            append(cueTrackIndex)
            append('|')
            append(duration)
        }
    }
}
