package com.rawsmusic.module.scanner

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.BitrateNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PersistentMetadataCache private constructor(
    private val cacheFile: File,
    private val records: ConcurrentHashMap<String, CachedMetadata>
) {
    @Volatile private var dirty: Boolean = false

    fun get(raw: AudioFile): AudioFile? {
        val record = records[cacheKey(raw)] ?: return null
        return record.applyTo(raw)
    }

    fun put(song: AudioFile) {
        if (song.path.isBlank() || song.fileSize <= 0L || song.dateModified <= 0L) return
        val key = cacheKey(song)
        val record = CachedMetadata.from(song)
        if (records[key] != record) {
            records[key] = record
            dirty = true
        }
    }

    fun remove(song: AudioFile) { if (records.remove(cacheKey(song)) != null) dirty = true }
    fun contains(song: AudioFile): Boolean = records.containsKey(cacheKey(song))
    fun size(): Int = records.size

    suspend fun save() {
        if (!dirty) return
        withContext(Dispatchers.IO) {
            if (!dirty) return@withContext
            val t0 = System.currentTimeMillis()
            val snapshot = records.values.toList()
            val array = JSONArray()
            snapshot.forEach { array.put(it.toJson()) }
            val root = JSONObject().put("version", CACHE_VERSION).put("items", array)
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tempFile.writeText(root.toString(), Charsets.UTF_8)
            if (cacheFile.exists()) cacheFile.delete()
            if (tempFile.renameTo(cacheFile)) {
                dirty = false
                Log.d(TAG, "save: items=${snapshot.size} time=${System.currentTimeMillis() - t0}ms bytes=${cacheFile.length()}")
            }
        }
    }

    private fun cacheKey(song: AudioFile): String = buildKey(song.path, song.fileSize, song.dateModified)

    private data class CachedMetadata(
        val path: String, val fileSize: Long, val dateModified: Long,
        val title: String, val artist: String, val album: String,
        val duration: Long, val sampleRate: Int, val bitRate: Int, val bitsPerSample: Int,
        val format: String, val genre: String, val composer: String,
        val discNumber: Int, val channelCount: Int, val bpm: Int, val albumArtist: String,
        val encodingFormat: String, val year: Int,
        val trackGain: Float, val trackPeak: Float, val albumGain: Float, val albumPeak: Float
    ) {
        fun applyTo(raw: AudioFile): AudioFile = raw.copy(
            title = title.ifBlank { raw.title },
            artist = artist.ifBlank { raw.artist },
            album = album.ifBlank { raw.album },
            duration = if (duration > 0L) duration else raw.duration,
            sampleRate = if (sampleRate > 0) sampleRate else raw.sampleRate,
            bitRate = BitrateNormalizer.toBps(
                rawBitrate = if (bitRate > 0) bitRate else raw.bitRate,
                durationMs = if (duration > 0L) duration else raw.duration,
                fileSizeBytes = raw.fileSize,
                codecName = encodingFormat.ifBlank { format },
                formatName = raw.format,
                filePath = raw.path
            ),
            bitsPerSample = if (bitsPerSample > 0) bitsPerSample else raw.bitsPerSample,
            format = format.ifBlank { raw.format },
            genre = genre.ifBlank { raw.genre },
            composer = composer.ifBlank { raw.composer },
            discNumber = if (discNumber > 0) discNumber else raw.discNumber,
            channelCount = if (channelCount > 0) channelCount else raw.channelCount,
            bpm = if (bpm > 0) bpm else raw.bpm,
            albumArtist = albumArtist.ifBlank { raw.albumArtist },
            encodingFormat = encodingFormat.ifBlank { raw.encodingFormat },
            year = if (year > 0) year else raw.year,
            trackGain = if (trackGain != 0f) trackGain else raw.trackGain,
            trackPeak = if (trackPeak != 1.0f) trackPeak else raw.trackPeak,
            albumGain = if (albumGain != 0f) albumGain else raw.albumGain,
            albumPeak = if (albumPeak != 1.0f) albumPeak else raw.albumPeak
        )

        fun toJson(): JSONObject = JSONObject()
            .put("path", path).put("fileSize", fileSize).put("dateModified", dateModified)
            .put("title", title).put("artist", artist).put("album", album)
            .put("duration", duration).put("sampleRate", sampleRate).put("bitRate", bitRate)
            .put("bitsPerSample", bitsPerSample).put("format", format)
            .put("genre", genre).put("composer", composer).put("discNumber", discNumber)
            .put("channelCount", channelCount).put("bpm", bpm).put("albumArtist", albumArtist)
            .put("encodingFormat", encodingFormat).put("year", year)
            .put("trackGain", trackGain.toDouble()).put("trackPeak", trackPeak.toDouble())
            .put("albumGain", albumGain.toDouble()).put("albumPeak", albumPeak.toDouble())

        companion object {
            fun from(song: AudioFile) = CachedMetadata(
                path = song.path, fileSize = song.fileSize, dateModified = song.dateModified,
                title = song.title, artist = song.artist, album = song.album,
                duration = song.duration, sampleRate = song.sampleRate,
                bitRate = BitrateNormalizer.toBps(
                    rawBitrate = song.bitRate,
                    durationMs = song.duration,
                    fileSizeBytes = song.fileSize,
                    codecName = song.encodingFormat,
                    formatName = song.format,
                    filePath = song.path
                ),
                bitsPerSample = song.bitsPerSample, format = song.format,
                genre = song.genre, composer = song.composer, discNumber = song.discNumber,
                channelCount = song.channelCount, bpm = song.bpm, albumArtist = song.albumArtist,
                encodingFormat = song.encodingFormat, year = song.year,
                trackGain = song.trackGain, trackPeak = song.trackPeak,
                albumGain = song.albumGain, albumPeak = song.albumPeak
            )

            fun fromJson(json: JSONObject): CachedMetadata? {
                val path = json.optString("path")
                val fileSize = json.optLong("fileSize", 0L)
                val dateModified = json.optLong("dateModified", 0L)
                if (path.isBlank() || fileSize <= 0L || dateModified <= 0L) return null
                return CachedMetadata(
                    path = path, fileSize = fileSize, dateModified = dateModified,
                    title = json.optString("title"), artist = json.optString("artist"),
                    album = json.optString("album"), duration = json.optLong("duration", 0L),
                    sampleRate = json.optInt("sampleRate", 0), bitRate = json.optInt("bitRate", 0),
                    bitsPerSample = json.optInt("bitsPerSample", 0), format = json.optString("format"),
                    genre = json.optString("genre"), composer = json.optString("composer"),
                    discNumber = json.optInt("discNumber", 0), channelCount = json.optInt("channelCount", 0),
                    bpm = json.optInt("bpm", 0), albumArtist = json.optString("albumArtist"),
                    encodingFormat = json.optString("encodingFormat"), year = json.optInt("year", 0),
                    trackGain = json.optDouble("trackGain", 0.0).toFloat(),
                    trackPeak = json.optDouble("trackPeak", 1.0).toFloat(),
                    albumGain = json.optDouble("albumGain", 0.0).toFloat(),
                    albumPeak = json.optDouble("albumPeak", 1.0).toFloat()
                )
            }
        }
    }

    companion object {
        private const val CACHE_VERSION = 3
        private const val CACHE_FILE_NAME = "audio_metadata_cache.json"

        suspend fun load(context: Context): PersistentMetadataCache = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            val map = ConcurrentHashMap<String, CachedMetadata>()
            if (file.exists()) {
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    val version = root.optInt("version", 0)
                    if (version == CACHE_VERSION) {
                        val items = root.optJSONArray("items") ?: JSONArray()
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val record = CachedMetadata.fromJson(item) ?: continue
                            map[buildKey(record.path, record.fileSize, record.dateModified)] = record
                        }
                    } else {
                        Log.w(TAG, "ignore old metadata cache: version=$version current=$CACHE_VERSION")
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to load: ${it.message}", it)
                }
            }
            Log.d(TAG, "load: items=${map.size} exists=${file.exists()} bytes=${if (file.exists()) file.length() else 0}")
            PersistentMetadataCache(file, map)
        }

        private const val TAG = "PersistentMetadataCache"

        private fun buildKey(path: String, fileSize: Long, dateModified: Long): String =
            "$path|$fileSize|$dateModified"
    }
}
