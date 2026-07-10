package com.rawsmusic.module.data.repository

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Project-style warm-start library snapshot.
 *
 * Room is still the source of truth. This file is only a compact, last-known-good UI snapshot used
 * to make the song list appear before Room finishes opening/converting entities during a cold
 * process start. The repository overwrites it after every real Room refresh.
 */
internal object StartupSongSnapshotStore {
    private const val TAG = "StartupSongSnapshot"
    private const val VERSION = 1
    private const val FILE_NAME = "startup_song_snapshot_v1.json"
    private const val MAX_BYTES = 4 * 1024 * 1024L

    fun load(context: Context, expectedSortOrder: SortOrder): List<AudioFile>? {
        val file = snapshotFile(context)
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_BYTES) return null

        val t0 = System.currentTimeMillis()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) return null
            val sortOrderName = root.optString("sortOrder", "")
            if (sortOrderName.isNotBlank() && sortOrderName != expectedSortOrder.name) {
                AppLogger.d(TAG, "load skipped: sortOrder=$sortOrderName expected=${expectedSortOrder.name}")
                return null
            }

            val array = root.optJSONArray("songs") ?: return null
            val songs = ArrayList<AudioFile>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                songs += AudioFile(
                    id = o.optLong("id", 0L),
                    path = o.optString("path", ""),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    albumId = o.optLong("albumId", -1L),
                    duration = o.optLong("duration", 0L),
                    sampleRate = o.optInt("sampleRate", 0),
                    bitRate = o.optInt("bitRate", 0),
                    bitsPerSample = o.optInt("bitsPerSample", 0),
                    format = o.optString("format", ""),
                    fileSize = o.optLong("fileSize", 0L),
                    trackNumber = o.optInt("trackNumber", 0),
                    year = o.optInt("year", 0),
                    dateAdded = o.optLong("dateAdded", 0L),
                    dateModified = o.optLong("dateModified", 0L),
                    albumArtPath = o.optString("albumArtPath", ""),
                    genre = o.optString("genre", ""),
                    composer = o.optString("composer", ""),
                    discNumber = o.optInt("discNumber", 0),
                    channelCount = o.optInt("channelCount", 0),
                    bpm = o.optInt("bpm", 0),
                    albumArtist = o.optString("albumArtist", ""),
                    encodingFormat = o.optString("encodingFormat", ""),
                    isFavorite = o.optBoolean("isFavorite", false),
                    trackGain = o.optDouble("trackGain", 0.0).toFloat(),
                    trackPeak = o.optDouble("trackPeak", 1.0).toFloat(),
                    albumGain = o.optDouble("albumGain", 0.0).toFloat(),
                    albumPeak = o.optDouble("albumPeak", 1.0).toFloat(),
                    cueOffsetMs = o.optLong("cueOffsetMs", 0L),
                    cueEndMs = o.optLong("cueEndMs", 0L),
                    cueTrackIndex = o.optInt("cueTrackIndex", 0)
                )
            }
            if (songs.isEmpty()) null else songs.also {
                AppLogger.d(TAG, "load: songs=${it.size} time=${System.currentTimeMillis() - t0}ms bytes=${file.length()}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "load failed", e)
            null
        }
    }

    fun save(context: Context, songs: List<AudioFile>, sortOrder: SortOrder) {
        if (songs.isEmpty()) return
        val t0 = System.currentTimeMillis()
        try {
            val root = JSONObject()
                .put("version", VERSION)
                .put("sortOrder", sortOrder.name)
                .put("savedAt", System.currentTimeMillis())
            val array = JSONArray()
            songs.forEach { song -> array.put(song.toJson()) }
            root.put("songs", array)

            val file = snapshotFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile ?: context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            AppLogger.d(TAG, "save: songs=${songs.size} time=${System.currentTimeMillis() - t0}ms bytes=${file.length()}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "save failed", e)
        }
    }

    fun clear(context: Context) {
        try {
            snapshotFile(context).delete()
        } catch (_: Exception) {
        }
    }

    private fun snapshotFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private fun AudioFile.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("path", path)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("albumId", albumId)
        .put("duration", duration)
        .put("sampleRate", sampleRate)
        .put("bitRate", bitRate)
        .put("bitsPerSample", bitsPerSample)
        .put("format", format)
        .put("fileSize", fileSize)
        .put("trackNumber", trackNumber)
        .put("year", year)
        .put("dateAdded", dateAdded)
        .put("dateModified", dateModified)
        .put("albumArtPath", albumArtPath)
        .put("genre", genre)
        .put("composer", composer)
        .put("discNumber", discNumber)
        .put("channelCount", channelCount)
        .put("bpm", bpm)
        .put("albumArtist", albumArtist)
        .put("encodingFormat", encodingFormat)
        .put("isFavorite", isFavorite)
        .put("trackGain", trackGain.toDouble())
        .put("trackPeak", trackPeak.toDouble())
        .put("albumGain", albumGain.toDouble())
        .put("albumPeak", albumPeak.toDouble())
        .put("cueOffsetMs", cueOffsetMs)
        .put("cueEndMs", cueEndMs)
        .put("cueTrackIndex", cueTrackIndex)
}
