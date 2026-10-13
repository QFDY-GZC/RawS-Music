package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile

internal fun shouldInvalidateServiceLyrics(previous: AudioFile?, incoming: AudioFile): Boolean =
    previous == null || previous.path != incoming.path ||
        previous.cueTrackIndex != incoming.cueTrackIndex ||
        previous.cueOffsetMs != incoming.cueOffsetMs ||
        previous.cueEndMs != incoming.cueEndMs ||
        previous.fileSize != incoming.fileSize ||
        previous.dateModified != incoming.dateModified
