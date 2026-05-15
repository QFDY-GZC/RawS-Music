package com.rawsmusic.module.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

object MediaStoreScanner {

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.BITRATE,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DATA
    )

    fun scan(context: Context, customPaths: List<String> = emptyList(), quickScan: Boolean = false): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()
        val audioFiles = mutableListOf<AudioFile>()

        val contentResolver = context.contentResolver
        val uri = getAudioUri()
        val selection = buildSelection(customPaths)
        val selectionArgs = buildSelectionArgs(customPaths)

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, PROJECTION, selection, selectionArgs,
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER)

            cursor?.let {
                val total = it.count
                emit(ScanProgress.Started(total))

                while (it.moveToNext()) {
                    val rawFile = parseCursor(it, quickScan)
                    if (rawFile != null && rawFile.duration > 0) {
                        audioFiles.add(rawFile)
                    }
                    emit(ScanProgress.Progress(audioFiles.size, total))
                }
            }
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Unknown error"))
        } finally {
            cursor?.close()
        }

        val elapsed = System.currentTimeMillis() - startTime
        emit(ScanProgress.Completed(audioFiles, audioFiles.size, elapsed))
    }.flowOn(Dispatchers.IO)

    private fun getAudioUri(): Uri {
        return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun buildSelection(customPaths: List<String>): String? {
        if (customPaths.isEmpty()) return null
        val pathConditions = customPaths.mapIndexed { index, _ ->
            "${MediaStore.Audio.Media.DATA} LIKE ?"
        }.joinToString(" OR ")
        return "($pathConditions) AND ${MediaStore.Audio.Media.IS_MUSIC} != 0"
    }

    private fun buildSelectionArgs(customPaths: List<String>): Array<String>? {
        if (customPaths.isEmpty()) return null
        return customPaths.map { "$it%" }.toTypedArray()
    }

    private fun parseCursor(cursor: Cursor, quickScan: Boolean = false): AudioFile? {
        return try {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                ?: ""
            val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                ?: ""
            val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                ?: ""
            val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
            var duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
            val bitRate = cursor.getColumnSafely(MediaStore.Audio.Media.BITRATE)
            val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE))
                ?: ""
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
            val track = cursor.getColumnSafely(MediaStore.Audio.Media.TRACK)
            val year = cursor.getColumnSafely(MediaStore.Audio.Media.YEAR)
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
            val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED))
            val data = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                ?: ""

            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId
            )

            if (quickScan) {
                var actualBitRate = bitRate
                if (actualBitRate <= 0 && duration > 0 && size > 0) {
                    actualBitRate = (size * 8.0 / (duration / 1000.0)).toInt()
                }
                val fileExt = data.substringAfterLast(".", "").uppercase()
                val displayFormat = when (fileExt) {
                    "M4A", "MP4" -> "M4A"
                    "MP3" -> "MP3"
                    "FLAC" -> "FLAC"
                    "WAV" -> "WAV"
                    "OGG" -> "OGG"
                    "AAC" -> "AAC"
                    "WMA" -> "WMA"
                    "APE" -> "APE"
                    "OPUS" -> "OPUS"
                    "DSF" -> "DSF"
                    "DFF" -> "DFF"
                    "AIFF", "AIF" -> "AIFF"
                    else -> fileExt.ifBlank { mimeType.substringAfter("/").uppercase() }
                }

                // QuickScan 也需要读取标签（流派、作曲、年份等 MediaStore 不提供的字段）
                val tagData = try { FfmpegMetadataReader.readTags(data) } catch (_: Exception) { FfmpegMetadataReader.ExtendedTags() }

                return AudioFile(
                    id = id,
                    path = data,
                    title = title.ifBlank { tagData.title },
                    artist = if (artist.isBlank() || artist == "<unknown>") tagData.artist else artist,
                    album = if (album.isBlank() || album == "<unknown>") tagData.album else album,
                    albumId = albumId,
                    duration = duration.coerceAtLeast(0),
                    sampleRate = 0,
                    bitRate = actualBitRate,
                    bitsPerSample = 0,
                    format = displayFormat,
                    fileSize = size,
                    trackNumber = track,
                    year = if (year > 0) year else tagData.year,
                    dateAdded = dateAdded * 1000,
                    dateModified = dateModified * 1000,
                    albumArtPath = albumArtUri.toString(),
                    genre = tagData.genre,
                    composer = tagData.composer,
                    discNumber = tagData.discNumber,
                    channelCount = 0,
                    bpm = tagData.bpm,
                    albumArtist = tagData.albumArtist,
                    encodingFormat = displayFormat,
                    trackGain = tagData.trackGain,
                    trackPeak = tagData.trackPeak,
                    albumGain = tagData.albumGain,
                    albumPeak = tagData.albumPeak
                )
            }

            val fullInfo = FfmpegMetadataReader.readFullInfo(data)
            val tagData = fullInfo.tags
            val streamInfo = fullInfo.stream

            var sampleRate = streamInfo.sampleRate
            var actualBitRate = if (streamInfo.bitRate > 0) streamInfo.bitRate else bitRate
            var bitsPerSample = streamInfo.bitsPerSample
            var channelCount = streamInfo.channels
            var encodingFormat = FfmpegMetadataReader.mapCodecToFormat(streamInfo.codecName, data)

            if (duration <= 0 && streamInfo.durationMs > 0) {
                duration = streamInfo.durationMs
            }

            if (actualBitRate <= 0 && duration > 0 && size > 0) {
                actualBitRate = (size * 8.0 / (duration / 1000.0)).toInt()
            }

            AudioFile(
                id = id,
                path = data,
                title = title.ifBlank { tagData.title },
                artist = if (artist.isBlank() || artist == "<unknown>") tagData.artist else artist,
                album = if (album.isBlank() || album == "<unknown>") tagData.album else album,
                albumId = albumId,
                duration = duration.coerceAtLeast(0),
                sampleRate = sampleRate,
                bitRate = actualBitRate,
                bitsPerSample = bitsPerSample,
                format = encodingFormat.ifBlank { data.substringAfterLast(".", "").uppercase() },
                fileSize = size,
                trackNumber = track,
                year = if (year > 0) year else tagData.year,
                dateAdded = dateAdded * 1000,
                dateModified = dateModified * 1000,
                albumArtPath = albumArtUri.toString(),
                genre = tagData.genre,
                composer = tagData.composer,
                discNumber = tagData.discNumber,
                channelCount = channelCount,
                bpm = tagData.bpm,
                albumArtist = tagData.albumArtist,
                encodingFormat = encodingFormat,
                trackGain = tagData.trackGain,
                trackPeak = tagData.trackPeak,
                albumGain = tagData.albumGain,
                albumPeak = tagData.albumPeak
            )
        } catch (e: Exception) {
            null
        }
    }

    fun enrichSong(song: AudioFile): AudioFile {
        return try {
            val fullInfo = FfmpegMetadataReader.readFullInfo(song.path)
            val tagData = fullInfo.tags
            val streamInfo = fullInfo.stream
            android.util.Log.d("EnrichSong", "path=${song.path}, FFmpeg: sr=${streamInfo.sampleRate}, br=${streamInfo.bitRate}, " +
                    "bps=${streamInfo.bitsPerSample}, ch=${streamInfo.channels}, codec=${streamInfo.codecName}")
            song.copy(
                sampleRate = if (streamInfo.sampleRate > 0) streamInfo.sampleRate else song.sampleRate,
                bitRate = if (streamInfo.bitRate > 0) streamInfo.bitRate else song.bitRate,
                bitsPerSample = if (streamInfo.bitsPerSample > 0) streamInfo.bitsPerSample else song.bitsPerSample,
                channelCount = if (streamInfo.channels > 0) streamInfo.channels else song.channelCount,
                encodingFormat = if (streamInfo.codecName.isNotBlank()) FfmpegMetadataReader.mapCodecToFormat(streamInfo.codecName, song.path) else song.encodingFormat,
                title = tagData.title.ifBlank { song.title },
                artist = tagData.artist.ifBlank { song.artist },
                album = tagData.album.ifBlank { song.album },
                genre = tagData.genre.ifBlank { song.genre },
                composer = tagData.composer.ifBlank { song.composer },
                year = if (tagData.year > 0) tagData.year else song.year,
                discNumber = if (tagData.discNumber > 1) tagData.discNumber else song.discNumber,
                bpm = if (tagData.bpm > 0) tagData.bpm else song.bpm,
                albumArtist = tagData.albumArtist.ifBlank { song.albumArtist },
                trackGain = if (tagData.trackGain != 0f) tagData.trackGain else song.trackGain,
                trackPeak = if (tagData.trackPeak != 1.0f) tagData.trackPeak else song.trackPeak,
                albumGain = if (tagData.albumGain != 0f) tagData.albumGain else song.albumGain,
                albumPeak = if (tagData.albumPeak != 1.0f) tagData.albumPeak else song.albumPeak
            )
        } catch (e: Exception) {
            android.util.Log.w("EnrichSong", "Failed for ${song.path}: ${e.message}")
            song
        }
    }

    private fun Cursor.getColumnSafely(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getInt(index) else 0
    }
}

sealed class ScanProgress {
    data class Started(val totalEstimated: Int) : ScanProgress()
    data class Progress(val scanned: Int, val total: Int) : ScanProgress()
    data class Completed(val songs: List<AudioFile>, val found: Int, val timeMs: Long) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}
