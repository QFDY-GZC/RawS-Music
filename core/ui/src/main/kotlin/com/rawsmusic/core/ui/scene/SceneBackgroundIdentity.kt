package com.rawsmusic.core.ui.scene

import com.rawsmusic.core.common.model.AudioFile

/** Background follows transport identity, not the retained library's deferred queue snapshot. */
internal fun resolveSceneBackgroundSong(
    retainedSong: AudioFile?,
    liveSong: AudioFile?,
): AudioFile? = liveSong ?: retainedSong
