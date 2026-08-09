package com.rawsmusic.module.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Compact, last-known-good library snapshot used while Room is opening on a cold start.
 * Room remains the source of truth.
 */
internal object StartupSongSnapshotStore {
    private const val TAG = "StartupSongSnapshot"
    private const val VERSION = 1
    private const val FILE_NAME = "startup_song_snapshot_v1.json"
    private const val MAX_BYTES = 4 * 1024 * 1024L
    private const val CANCELLATION_CHECK_INTERVAL = 64

    fun load(context: Context, expectedSortOrder: SortOrder): List<AudioFile>? {
        val file = snapshotFile(context)
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_BYTES) return null

        val t0 = System.currentTimeMillis()
        return try {
            var version = -1
            var sortOrderName = ""
            var songs: ArrayList<AudioFile>? = null
            JsonReader(
                InputStreamReader(BufferedInputStream(file.inputStream()), Charsets.UTF_8)
            ).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt()
                        "sortOrder" -> sortOrderName = reader.nextString()
                        "songs" -> {
                            val loaded = ArrayList<AudioFile>()
                            reader.beginArray()
                            while (reader.hasNext()) loaded += reader.readSong()
                            reader.endArray()
                            songs = loaded
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            if (version != VERSION) return null
            if (sortOrderName.isNotBlank() && sortOrderName != expectedSortOrder.name) {
                AppLogger.d(TAG, "load skipped: sortOrder=$sortOrderName expected=${expectedSortOrder.name}")
                return null
            }
            songs?.takeIf { it.isNotEmpty() }?.also {
                AppLogger.d(
                    TAG,
                    "load: songs=${it.size} time=${System.currentTimeMillis() - t0}ms bytes=${file.length()}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "load failed", e)
            null
        }
    }

    suspend fun save(context: Context, songs: List<AudioFile>, sortOrder: SortOrder) {
        if (songs.isEmpty()) return
        val t0 = System.currentTimeMillis()
        val atomicFile = AtomicFile(snapshotFile(context))
        var output: java.io.FileOutputStream? = null
        try {
            atomicFile.baseFile.parentFile?.mkdirs()
            output = atomicFile.startWrite()
            val bufferedOutput = BufferedOutputStream(output)
            val writer = JsonWriter(OutputStreamWriter(bufferedOutput, Charsets.UTF_8))
            writer.beginObject()
            writer.name("version").value(VERSION.toLong())
            writer.name("sortOrder").value(sortOrder.name)
            writer.name("savedAt").value(System.currentTimeMillis())
            writer.name("songs").beginArray()
            songs.forEachIndexed { index, song ->
                if (index % CANCELLATION_CHECK_INTERVAL == 0) {
                    currentCoroutineContext().ensureActive()
                    writer.flush()
                    if (output.channel.position() > MAX_BYTES) throw SnapshotTooLargeException()
                }
                writer.writeSong(song)
            }
            writer.endArray()
            writer.endObject()
            writer.flush()
            if (output.channel.position() > MAX_BYTES) throw SnapshotTooLargeException()
            atomicFile.finishWrite(output)
            output = null
            AppLogger.d(
                TAG,
                "save: songs=${songs.size} time=${System.currentTimeMillis() - t0}ms bytes=${atomicFile.baseFile.length()}"
            )
        } catch (e: SnapshotTooLargeException) {
            output?.let(atomicFile::failWrite)
            output = null
            atomicFile.delete()
            AppLogger.w(TAG, "save skipped: songs=${songs.size} snapshot exceeds ${MAX_BYTES / 1024 / 1024}MB")
        } catch (e: CancellationException) {
            output?.let(atomicFile::failWrite)
            output = null
            throw e
        } catch (e: Exception) {
            output?.let(atomicFile::failWrite)
            output = null
            AppLogger.e(TAG, "save failed", e)
        } finally {
            output?.let(atomicFile::failWrite)
        }
    }

    fun clear(context: Context) {
        try {
            AtomicFile(snapshotFile(context)).delete()
        } catch (_: Exception) {
        }
    }

    private fun snapshotFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun JsonWriter.writeSong(song: AudioFile) {
        beginObject()
        name("id").value(song.id)
        name("path").value(song.path)
        name("title").value(song.title)
        name("artist").value(song.artist)
        name("album").value(song.album)
        name("albumId").value(song.albumId)
        name("duration").value(song.duration)
        name("sampleRate").value(song.sampleRate.toLong())
        name("bitRate").value(song.bitRate.toLong())
        name("bitsPerSample").value(song.bitsPerSample.toLong())
        name("format").value(song.format)
        name("fileSize").value(song.fileSize)
        name("trackNumber").value(song.trackNumber.toLong())
        name("year").value(song.year.toLong())
        name("dateAdded").value(song.dateAdded)
        name("dateModified").value(song.dateModified)
        name("albumArtPath").value(song.albumArtPath)
        name("genre").value(song.genre)
        name("composer").value(song.composer)
        name("discNumber").value(song.discNumber.toLong())
        name("channelCount").value(song.channelCount.toLong())
        name("bpm").value(song.bpm.toLong())
        name("albumArtist").value(song.albumArtist)
        name("encodingFormat").value(song.encodingFormat)
        name("isFavorite").value(song.isFavorite)
        name("trackGain").value(song.trackGain.toDouble())
        name("trackPeak").value(song.trackPeak.toDouble())
        name("albumGain").value(song.albumGain.toDouble())
        name("albumPeak").value(song.albumPeak.toDouble())
        name("cueOffsetMs").value(song.cueOffsetMs)
        name("cueEndMs").value(song.cueEndMs)
        name("cueTrackIndex").value(song.cueTrackIndex.toLong())
        endObject()
    }

    private fun JsonReader.readSong(): AudioFile {
        var id = 0L
        var path = ""
        var title = ""
        var artist = ""
        var album = ""
        var albumId = -1L
        var duration = 0L
        var sampleRate = 0
        var bitRate = 0
        var bitsPerSample = 0
        var format = ""
        var fileSize = 0L
        var trackNumber = 0
        var year = 0
        var dateAdded = 0L
        var dateModified = 0L
        var albumArtPath = ""
        var genre = ""
        var composer = ""
        var discNumber = 0
        var channelCount = 0
        var bpm = 0
        var albumArtist = ""
        var encodingFormat = ""
        var isFavorite = false
        var trackGain = 0f
        var trackPeak = 1f
        var albumGain = 0f
        var albumPeak = 1f
        var cueOffsetMs = 0L
        var cueEndMs = 0L
        var cueTrackIndex = 0

        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "id" -> id = nextLong()
                "path" -> path = nextStringOrEmpty()
                "title" -> title = nextStringOrEmpty()
                "artist" -> artist = nextStringOrEmpty()
                "album" -> album = nextStringOrEmpty()
                "albumId" -> albumId = nextLong()
                "duration" -> duration = nextLong()
                "sampleRate" -> sampleRate = nextInt()
                "bitRate" -> bitRate = nextInt()
                "bitsPerSample" -> bitsPerSample = nextInt()
                "format" -> format = nextStringOrEmpty()
                "fileSize" -> fileSize = nextLong()
                "trackNumber" -> trackNumber = nextInt()
                "year" -> year = nextInt()
                "dateAdded" -> dateAdded = nextLong()
                "dateModified" -> dateModified = nextLong()
                "albumArtPath" -> albumArtPath = nextStringOrEmpty()
                "genre" -> genre = nextStringOrEmpty()
                "composer" -> composer = nextStringOrEmpty()
                "discNumber" -> discNumber = nextInt()
                "channelCount" -> channelCount = nextInt()
                "bpm" -> bpm = nextInt()
                "albumArtist" -> albumArtist = nextStringOrEmpty()
                "encodingFormat" -> encodingFormat = nextStringOrEmpty()
                "isFavorite" -> isFavorite = nextBoolean()
                "trackGain" -> trackGain = nextDouble().toFloat()
                "trackPeak" -> trackPeak = nextDouble().toFloat()
                "albumGain" -> albumGain = nextDouble().toFloat()
                "albumPeak" -> albumPeak = nextDouble().toFloat()
                "cueOffsetMs" -> cueOffsetMs = nextLong()
                "cueEndMs" -> cueEndMs = nextLong()
                "cueTrackIndex" -> cueTrackIndex = nextInt()
                else -> skipValue()
            }
        }
        endObject()
        return AudioFile(
            id = id,
            path = path,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            duration = duration,
            sampleRate = sampleRate,
            bitRate = bitRate,
            bitsPerSample = bitsPerSample,
            format = format,
            fileSize = fileSize,
            trackNumber = trackNumber,
            year = year,
            dateAdded = dateAdded,
            dateModified = dateModified,
            albumArtPath = albumArtPath,
            genre = genre,
            composer = composer,
            discNumber = discNumber,
            channelCount = channelCount,
            bpm = bpm,
            albumArtist = albumArtist,
            encodingFormat = encodingFormat,
            isFavorite = isFavorite,
            trackGain = trackGain,
            trackPeak = trackPeak,
            albumGain = albumGain,
            albumPeak = albumPeak,
            cueOffsetMs = cueOffsetMs,
            cueEndMs = cueEndMs,
            cueTrackIndex = cueTrackIndex
        )
    }

    private fun JsonReader.nextStringOrEmpty(): String {
        if (peek() != JsonToken.NULL) return nextString()
        nextNull()
        return ""
    }

    private class SnapshotTooLargeException : Exception()
}
