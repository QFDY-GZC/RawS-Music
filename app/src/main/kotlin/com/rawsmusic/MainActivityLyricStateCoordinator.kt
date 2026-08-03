package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song

/** Converts loaded lyrics into the Compose/Lyricon state exposed by MainActivity. */
internal class MainActivityLyricStateCoordinator(
    private val currentSong: () -> AudioFile?,
    private val currentPositionMs: () -> Long,
    private val setLyricData: (LyricData) -> Unit,
    private val setLyricSong: (Song?) -> Unit,
    private val setDisplayTranslation: (Boolean) -> Unit,
    private val setDisplayRoma: (Boolean) -> Unit,
    private val setPositionMs: (Long) -> Unit,
) {
    fun update(data: LyricData) {
        setLyricData(data)
        val song = currentSong()
        setLyricSong(
            if (!data.isEmpty && song != null) {
                data.toLyriconSong(
                    name = song.title,
                    artist = song.artist,
                    durationMs = song.duration,
                )
            } else {
                null
            },
        )
        setDisplayTranslation(AppPreferences.Lyricon.displayTranslation)
        setDisplayRoma(AppPreferences.Lyricon.displayRoma)
        setPositionMs(currentPositionMs())
    }
}
