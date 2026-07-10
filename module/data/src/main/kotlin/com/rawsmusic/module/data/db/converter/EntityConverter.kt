package com.rawsmusic.module.data.db.converter

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.db.entity.FolderEntity
import com.rawsmusic.module.data.db.entity.FolderFileEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntryEntity
import java.io.File

/**
 * AudioFile ↔ FolderFileEntity 双向转换
 */
object EntityConverter {

    fun audioFileToFolderFile(audioFile: AudioFile, folderId: Long): FolderFileEntity {
        val file = File(audioFile.path)
        return FolderFileEntity(
            id = if (audioFile.id > 0) audioFile.id else 0,
            name = file.name,
            titleTag = audioFile.title,
            artistTag = audioFile.artist,
            albumTag = audioFile.album,
            albumArtistTag = audioFile.albumArtist,
            genreTag = audioFile.genre,
            composerTag = audioFile.composer,
            duration = audioFile.duration,
            fileType = mapFormatToFileType(audioFile.format),
            fileCreatedAt = audioFile.dateAdded,
            fileModifiedAt = audioFile.dateModified,
            tagStatus = if (audioFile.sampleRate > 0) 1 else 0,
            trackNumber = audioFile.trackNumber,
            year = audioFile.year,
            rating = if (audioFile.isFavorite) 1 else 0,
            folderId = folderId,
            sampleRate = audioFile.sampleRate,
            bitRate = audioFile.bitRate,
            bitsPerSample = audioFile.bitsPerSample,
            channels = audioFile.channelCount,
            encodingFormat = audioFile.encodingFormat.ifBlank { audioFile.format },
            fileSize = audioFile.fileSize,
            albumArtPath = audioFile.albumArtPath,
            hasLyrics = 0,
            bpm = audioFile.bpm,
            discNumber = audioFile.discNumber,
            cueOffsetMs = audioFile.cueOffsetMs,
            cueEndMs = audioFile.cueEndMs,
            cueTrackIndex = audioFile.cueTrackIndex,
            trackGain = audioFile.trackGain,
            trackPeak = audioFile.trackPeak,
            albumGain = audioFile.albumGain,
            albumPeak = audioFile.albumPeak,
            playedTimes = 0,
            lastPos = 0,
            playedAt = 0,
            playedFullyAt = 0,
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            filePath = audioFile.path,
            meta = ""
        )
    }

    fun folderFileToAudioFile(entity: FolderFileEntity): AudioFile {
        return AudioFile(
            id = entity.id,
            path = entity.filePath.ifBlank { "" },
            title = entity.titleTag,
            artist = entity.artistTag,
            album = entity.albumTag,
            albumId = 0,
            duration = entity.duration,
            sampleRate = entity.sampleRate,
            bitRate = entity.bitRate,
            bitsPerSample = entity.bitsPerSample,
            format = entity.encodingFormat.ifBlank { "" },
            fileSize = entity.fileSize,
            trackNumber = entity.trackNumber,
            year = entity.year,
            dateAdded = entity.fileCreatedAt.takeIf { it > 0L } ?: entity.createdAt,
            dateModified = entity.fileModifiedAt.takeIf { it > 0L } ?: entity.updatedAt,
            albumArtPath = entity.albumArtPath,
            genre = entity.genreTag,
            composer = entity.composerTag,
            discNumber = entity.discNumber,
            channelCount = entity.channels,
            bpm = entity.bpm,
            albumArtist = entity.albumArtistTag,
            encodingFormat = entity.encodingFormat,
            isFavorite = entity.rating > 0,
            trackGain = entity.trackGain,
            trackPeak = entity.trackPeak,
            albumGain = entity.albumGain,
            albumPeak = entity.albumPeak,
            cueOffsetMs = entity.cueOffsetMs,
            cueEndMs = entity.cueEndMs,
            cueTrackIndex = entity.cueTrackIndex
        )
    }

    fun pathToFolderEntity(path: String, isCue: Boolean = false): FolderEntity {
        val file = File(path)
        return FolderEntity(
            path = path,
            shortName = file.name,
            parentName = file.parentFile?.name ?: "",
            isCue = if (isCue) 1 else 0
        )
    }

    private fun mapFormatToFileType(format: String): Int {
        return when (format.uppercase()) {
            "MP3" -> 1
            "FLAC" -> 2
            "AAC" -> 3
            "OGG" -> 4
            "WMA" -> 5
            "WAV" -> 6
            "APE" -> 7
            "OPUS" -> 8
            "M4A", "MP4" -> 9
            "ALAC" -> 10
            "DSF" -> 11
            "DFF" -> 12
            "AIFF", "AIF" -> 13
            else -> 0
        }
    }
}
