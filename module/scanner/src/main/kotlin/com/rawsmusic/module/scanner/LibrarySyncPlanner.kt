package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile

object LibrarySyncPlanner {

    data class Delta(
        val upserts: List<AudioFile>,
        val deletes: List<AudioFile>,
        val unchanged: List<AudioFile>
    ) {
        val hasChanges: Boolean get() = upserts.isNotEmpty() || deletes.isNotEmpty()
    }

    fun calculateDelta(oldSongs: List<AudioFile>, newSongs: List<AudioFile>): Delta {
        val oldByKey = oldSongs.associateBy { it.stableLibraryKey() }
        val newByKey = newSongs.associateBy { it.stableLibraryKey() }

        val upserts = mutableListOf<AudioFile>()
        val unchanged = mutableListOf<AudioFile>()
        val deletes = mutableListOf<AudioFile>()

        for ((key, newSong) in newByKey) {
            val oldSong = oldByKey[key]
            if (oldSong == null) {
                upserts += newSong
            } else if (oldSong.metadataFingerprint() != newSong.metadataFingerprint()) {
                upserts += preserveStableIdIfNeeded(oldSong, newSong)
            } else {
                unchanged += oldSong
            }
        }

        for ((key, oldSong) in oldByKey) {
            if (!newByKey.containsKey(key)) deletes += oldSong
        }

        return Delta(upserts = upserts, deletes = deletes, unchanged = unchanged)
    }

    private fun preserveStableIdIfNeeded(oldSong: AudioFile, newSong: AudioFile): AudioFile {
        return if (oldSong.id != newSong.id) newSong.copy(id = oldSong.id) else newSong
    }

    private fun AudioFile.stableLibraryKey(): String {
        return if (cueTrackIndex > 0 || cueOffsetMs > 0L) {
            "cue|$path|$cueTrackIndex|$cueOffsetMs"
        } else "file|$path"
    }

    private fun AudioFile.metadataFingerprint(): String = buildString {
        append(path); append('|'); append(fileSize); append('|'); append(dateModified); append('|')
        append(title); append('|'); append(artist); append('|'); append(album); append('|')
        append(albumArtist); append('|'); append(duration); append('|'); append(sampleRate); append('|')
        append(bitRate); append('|'); append(bitsPerSample); append('|'); append(channelCount); append('|')
        append(format); append('|'); append(encodingFormat); append('|'); append(trackNumber); append('|')
        append(discNumber); append('|'); append(year); append('|'); append(genre); append('|')
        append(composer); append('|'); append(bpm); append('|'); append(trackGain); append('|')
        append(trackPeak); append('|'); append(albumGain); append('|'); append(albumPeak); append('|')
        append(cueOffsetMs); append('|'); append(cueEndMs); append('|'); append(cueTrackIndex)
    }
}
