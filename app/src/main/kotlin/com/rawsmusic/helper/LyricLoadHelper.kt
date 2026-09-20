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
 * Stable identity of the audio segment whose lyrics are being rendered.
 *
 * Do not include mutable database/enrichment fields such as id or duration. The controller may
 * replace the current [AudioFile] with an enriched copy while the asynchronous lyric read is in
 * flight. CUE coordinates are part of the identity because multiple tracks can share one physical
 * path.
 */
internal fun AudioFile.lyricRequestKey(): String = buildString {
    append(path)
    append('|')
    append(cueOffsetMs)
    append('|')
    append(cueEndMs)
    append('|')
    append(cueTrackIndex)
}

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
            // Parsing and animation-flag decoration both walk the full lyric structure. Keep that
            // immutable preparation off Main so a freshly written Lyrico sidecar cannot contend
            // with an immediate PLAYER <-> LYRIC shared transition.
            val styledLyricData = LyricReader.readLyrics(song).withAnimationFlags()
            launch(Dispatchers.Main) {
                if (loadGeneration.get() != generation) return@launch
                val current = getCurrentSong()
                if (current == null || current.lyricRequestKey() != requestKey) return@launch

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

}
