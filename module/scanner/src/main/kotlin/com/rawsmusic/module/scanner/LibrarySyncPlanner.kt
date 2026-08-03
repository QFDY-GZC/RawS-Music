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


    /**
     * Quick scan must make new files visible immediately, but it must not downgrade
     * already-enriched records with placeholder technical fields. Existing rows are
     * only touched when the underlying file changed; advanced metadata is preserved
     * until the lazy enrich pass replaces it with real values.
     */
    fun calculateQuickVisibleUpserts(oldSongs: List<AudioFile>, quickSongs: List<AudioFile>): List<AudioFile> {
        if (quickSongs.isEmpty()) return emptyList()
        val oldByKey = oldSongs.associateBy { it.stableLibraryKey() }
        val upserts = ArrayList<AudioFile>()

        for (quick in quickSongs.distinctBy { it.stableLibraryKey() }) {
            val old = oldByKey[quick.stableLibraryKey()]
            if (old == null) {
                upserts += quick
                continue
            }

            val fileChanged = old.fileSize != quick.fileSize || old.dateModified != quick.dateModified
            if (fileChanged) {
                upserts += mergeQuickWithExisting(old, quick)
            }
        }
        return upserts
    }

    private fun mergeQuickWithExisting(old: AudioFile, quick: AudioFile): AudioFile {
        return quick.copy(
            id = old.id,
            title = quick.title.ifBlank { old.title },
            artist = quick.artist.ifBlank { old.artist },
            album = quick.album.ifBlank { old.album },
            albumArtist = old.albumArtist.ifBlank { quick.albumArtist },
            sampleRate = old.sampleRate.takeIf { it > 0 } ?: quick.sampleRate,
            bitRate = old.bitRate.takeIf { it > 0 } ?: quick.bitRate,
            bitsPerSample = old.bitsPerSample.takeIf { it > 0 } ?: quick.bitsPerSample,
            channelCount = old.channelCount.takeIf { it > 0 } ?: quick.channelCount,
            format = old.format.ifBlank { quick.format },
            encodingFormat = old.encodingFormat.ifBlank { quick.encodingFormat },
            genre = old.genre.ifBlank { quick.genre },
            composer = old.composer.ifBlank { quick.composer },
            discNumber = old.discNumber.takeIf { it > 0 } ?: quick.discNumber,
            bpm = old.bpm.takeIf { it > 0 } ?: quick.bpm,
            albumArtPath = old.albumArtPath.ifBlank { quick.albumArtPath },
            trackGain = old.trackGain.takeIf { it != 0f } ?: quick.trackGain,
            trackPeak = old.trackPeak.takeIf { it != 1.0f } ?: quick.trackPeak,
            albumGain = old.albumGain.takeIf { it != 0f } ?: quick.albumGain,
            albumPeak = old.albumPeak.takeIf { it != 1.0f } ?: quick.albumPeak,
            isFavorite = old.isFavorite
        )
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
