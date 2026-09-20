package com.rawsmusic.module.player

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import org.json.JSONArray
import org.json.JSONObject

internal data class RestoredPlayerState(
    /** Null when the durable queue exists but there is no selected now-playing item. */
    val song: AudioFile?,
    val queue: List<AudioFile>,
    val queueIndex: Int,
    val queueEntryIds: List<Long> = emptyList(),
    val priorityQueue: List<AudioFile> = emptyList(),
    val positionMs: Long,
    val source: String,
    val repositorySongCount: Int
)

/** Owns the persisted representation of playback state and its cold-start hydration. */
internal class PlayerStatePersistence(context: Context) {
    private data class SongIdentity(
        val path: String,
        val cueOffsetMs: Long,
        val cueTrackIndex: Int,
    )

    private val queueDatabase = PlayerQueueDatabase(context)
    private val persistenceLock = Any()

    fun saveSongSnapshot(song: AudioFile) {
        AppPreferences.Player.lastSongId = song.id
        AppPreferences.Player.lastSongPath = song.path
        AppPreferences.Player.lastSongTitle = song.title
        AppPreferences.Player.lastSongArtist = song.artist
        AppPreferences.Player.lastSongAlbum = song.album
        AppPreferences.Player.lastSongAlbumArtPath = song.albumArtPath
        AppPreferences.Player.lastSongDuration = song.duration
        AppPreferences.Player.lastSongAlbumId = song.albumId
    }

    fun saveRuntime(playStateOrdinal: Int, keepUsbExclusive: Boolean) {
        AppPreferences.Player.lastPlayStateOrdinal = playStateOrdinal
        AppPreferences.Player.lastUsbExclusiveActive =
            AppPreferences.Player.lastUsbExclusiveActive || keepUsbExclusive
    }

    fun savePosition(positionMs: Long) {
        AppPreferences.Player.lastPosition = if (AppPreferences.Player.trackProgressMemoryEnabled) {
            positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
    }

    fun saveQueue(songs: List<AudioFile>, currentIndex: Int, entryIds: List<Long> = emptyList()) {
        val priority = decodeSongList(AppPreferences.Player.priorityQueueSongsJson)
        saveSnapshot(songs, currentIndex, entryIds, priority)
    }

    fun savePriorityQueue(songs: List<AudioFile>) {
        synchronized(persistenceLock) {
            AppPreferences.Player.priorityQueueSongsJson = encodeSongList(songs)
            val existing = loadDatabaseSnapshot()
            if (existing != null) {
                queueDatabase.saveSnapshot(
                    entries = existing.entries,
                    currentEntryId = existing.currentEntryId,
                    prioritySongsJson = songs.map { encodeSong(it).toString() },
                    shuffleTraversalOrder = existing.shuffleTraversalOrder,
                    shuffleTraversalCursor = existing.shuffleTraversalCursor,
                )
            }
        }
    }

    /** Commits queue rows, cursor and priority entries together, then mirrors legacy preferences. */
    fun saveSnapshot(
        songs: List<AudioFile>,
        currentIndex: Int,
        entryIds: List<Long> = emptyList(),
        prioritySongs: List<AudioFile> = emptyList(),
    ) {
        val normalizedIds = ensureEntryIds(songs, entryIds)
        val safeIndex = currentIndex.takeIf { it in songs.indices }
        val shuffleTraversalOrder = AppPreferences.Player.shuffleTraversalOrder
            .split(',')
            .mapNotNull(String::toIntOrNull)
        synchronized(persistenceLock) {
            queueDatabase.saveSnapshot(
                entries = songs.mapIndexed { index, song ->
                    PlayerQueueDatabase.Entry(
                        entryId = normalizedIds[index],
                        sort = index,
                        songJson = encodeSong(song).toString(),
                    )
                },
                currentEntryId = safeIndex?.let(normalizedIds::get),
                prioritySongsJson = prioritySongs.map { encodeSong(it).toString() },
                shuffleTraversalOrder = shuffleTraversalOrder,
                shuffleTraversalCursor = AppPreferences.Player.shuffleTraversalCursor,
            )
            // Keep the old MMKV representation for downgrade/rollback compatibility. The DB is
            // authoritative for this build; these keys are only a migration fallback.
            AppPreferences.Player.currentQueueIndex = safeIndex ?: -1
            AppPreferences.Player.playQueueSongsJson = encodeSongList(songs)
            AppPreferences.Player.priorityQueueSongsJson = encodeSongList(prioritySongs)
        }
    }

    fun encodeSongList(songs: List<AudioFile>): String = encodeSongs(songs)

    fun decodeSongList(json: String): List<AudioFile> = decodeSongs(
        json = json,
        songsByIdentity = emptyMap(),
        songsById = emptyMap(),
        songsByPath = emptyMap()
    )

    fun restore(): RestoredPlayerState? {
        val lastPath = AppPreferences.Player.lastSongPath

        val repositorySongs = MusicRepository.songs.value
        val songsByIdentity = if (repositorySongs.isNotEmpty()) {
            repositorySongs.associateBy(::identityOf)
        } else {
            emptyMap()
        }
        val songsById = if (repositorySongs.isNotEmpty()) repositorySongs.associateBy { it.id } else emptyMap()
        val songsByPath = if (repositorySongs.isNotEmpty()) repositorySongs.associateBy { it.path } else emptyMap()
        val lastId = AppPreferences.Player.lastSongId
        val legacyQueue = decodeSongs(
            json = AppPreferences.Player.playQueueSongsJson,
            songsByIdentity = songsByIdentity,
            songsById = songsById,
            songsByPath = songsByPath,
        )
        val databaseSnapshot = loadDatabaseSnapshot()
        // Keep the decoded song and its row id together.  Filtering a stale/missing media row
        // must not leave entryIds shifted relative to the restored queue.
        val databaseEntries = databaseSnapshot?.entries
            ?.mapNotNull { entry ->
                decodeSongFromJson(entry.songJson, songsByIdentity, songsById, songsByPath)
                    ?.let { song -> entry.entryId to song }
            }
            .orEmpty()
        val databaseQueue = databaseEntries.map { it.second }
        val hasDatabaseQueueState = databaseSnapshot != null
        val restoredQueue = if (hasDatabaseQueueState) databaseQueue else legacyQueue
        val restoredEntryIds = if (hasDatabaseQueueState) {
            databaseEntries.map { it.first }
        } else {
            ensureEntryIds(restoredQueue, emptyList())
        }
        val restoredPriorityQueue = databaseSnapshot?.prioritySongsJson
            ?.mapNotNull { decodeSongFromJson(it, emptyMap(), emptyMap(), emptyMap()) }
            ?.ifEmpty { decodeSongList(AppPreferences.Player.priorityQueueSongsJson) }
            ?: decodeSongList(AppPreferences.Player.priorityQueueSongsJson)
        if (databaseSnapshot != null) {
            // Shuffle order is a queue cursor, not a media-list sort. Restore it together with
            // the queue rows so the first post-cold-start next/previous request continues the same
            // traversal instead of creating a new random cycle.
            AppPreferences.Player.shuffleTraversalOrder =
                databaseSnapshot.shuffleTraversalOrder.joinToString(",")
            AppPreferences.Player.shuffleTraversalCursor = databaseSnapshot.shuffleTraversalCursor
        }
        val savedQueueIndex = AppPreferences.Player.currentQueueIndex
        val databaseCurrentIndex = databaseSnapshot?.currentEntryId?.let { currentId ->
            restoredEntryIds.indexOfFirst { it == currentId }.takeIf { it >= 0 }
        }
        val effectiveSavedQueueIndex = databaseCurrentIndex ?: savedQueueIndex
        val indexedQueueSong = restoredQueue.getOrNull(effectiveSavedQueueIndex)
            ?.takeIf { lastPath.isBlank() || it.path == lastPath }
        val repositorySong = indexedQueueSong ?:
            (if (lastId != -1L) songsById[lastId] else null) ?:
            lastPath.takeIf { it.isNotBlank() }?.let(songsByPath::get)
        val song = repositorySong ?: lastPath.takeIf { it.isNotBlank() }?.let {
            AudioFile(
                id = lastId,
                path = it,
                title = AppPreferences.Player.lastSongTitle,
                artist = AppPreferences.Player.lastSongArtist,
                album = AppPreferences.Player.lastSongAlbum,
                albumId = AppPreferences.Player.lastSongAlbumId,
                duration = AppPreferences.Player.lastSongDuration,
                albumArtPath = AppPreferences.Player.lastSongAlbumArtPath
            )
        }
        // Reference restores the queue entity independently from now-playing.  In particular, a
        // stopped/never-started session can have queue rows while no last-song path is present.
        // Keep that state visible instead of making the first Play action recreate the queue.
        val hasPersistedQueueState = databaseSnapshot != null || legacyQueue.isNotEmpty() ||
            restoredPriorityQueue.isNotEmpty()
        if (song == null && !hasPersistedQueueState) return null

        val decodedQueue = restoredQueue.ifEmpty { song?.let(::listOf).orEmpty() }
        val hydratedEntryIds = ensureEntryIds(decodedQueue, restoredEntryIds)
        val exactSongIndex = song?.let { target ->
            decodedQueue.indexOfFirst { identityOf(it) == identityOf(target) }
        } ?: -1
        val restoredIndex = when {
            exactSongIndex >= 0 -> exactSongIndex
            effectiveSavedQueueIndex in decodedQueue.indices -> effectiveSavedQueueIndex
            // Reference keeps a current track object even when the decoder is idle.  A durable
            // queue without a persisted now-playing path therefore still needs a deterministic
            // selected row for the player page, artwork pager and first next/previous command.
            // This is a logical selection only; restore never starts audio.
            decodedQueue.isNotEmpty() -> 0
            else -> -1
        }
        val hydratedQueue = if (song != null && exactSongIndex < 0 && restoredIndex in decodedQueue.indices) {
            decodedQueue.toMutableList().apply {
                this[restoredIndex] = song
            }
        } else {
            decodedQueue
        }
        val positionMs = if (AppPreferences.Player.trackProgressMemoryEnabled) {
            AppPreferences.Player.lastPosition.coerceAtLeast(0L)
        } else {
            0L
        }
        return RestoredPlayerState(
            song = song,
            queue = hydratedQueue,
            queueIndex = restoredIndex,
            queueEntryIds = hydratedEntryIds,
            priorityQueue = restoredPriorityQueue,
            positionMs = positionMs,
            source = when {
                databaseSnapshot != null && song == null -> "queue_database_only"
                databaseQueue.isNotEmpty() && repositorySong != null -> "queue_database_repository_state"
                databaseQueue.isNotEmpty() -> "queue_database_snapshot"
                repositorySong != null -> "repository_state"
                song != null -> "preference_snapshot"
                else -> "queue_legacy_only"
            },
            repositorySongCount = repositorySongs.size
        )
    }

    private fun loadDatabaseSnapshot(): PlayerQueueDatabase.Snapshot? =
        synchronized(persistenceLock) {
            runCatching { queueDatabase.loadSnapshot() }.getOrNull()
        }

    private fun encodeSongs(songs: List<AudioFile>): String {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(encodeSong(song))
        }
        return array.toString()
    }

    private fun encodeSong(song: AudioFile): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("path", song.path)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("albumId", song.albumId)
        put("duration", song.duration)
        put("sampleRate", song.sampleRate)
        put("bitRate", song.bitRate)
        put("bitsPerSample", song.bitsPerSample)
        put("format", song.format)
        put("fileSize", song.fileSize)
        put("trackNumber", song.trackNumber)
        put("year", song.year)
        put("dateAdded", song.dateAdded)
        put("dateModified", song.dateModified)
        put("albumArtPath", song.albumArtPath)
        put("genre", song.genre)
        put("composer", song.composer)
        put("discNumber", song.discNumber)
        put("channelCount", song.channelCount)
        put("bpm", song.bpm)
        put("albumArtist", song.albumArtist)
        put("encodingFormat", song.encodingFormat)
        put("isFavorite", song.isFavorite)
        put("trackGain", song.trackGain)
        put("trackPeak", song.trackPeak)
        put("albumGain", song.albumGain)
        put("albumPeak", song.albumPeak)
        put("cueOffsetMs", song.cueOffsetMs)
        put("cueEndMs", song.cueEndMs)
        put("cueTrackIndex", song.cueTrackIndex)
    }

    private fun decodeSongs(
        json: String,
        songsByIdentity: Map<SongIdentity, AudioFile>,
        songsById: Map<Long, AudioFile>,
        songsByPath: Map<String, AudioFile>
    ): List<AudioFile> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = try {
                        decodeSongFromJson(
                            obj = array.getJSONObject(index),
                            songsByIdentity = songsByIdentity,
                            songsById = songsById,
                            songsByPath = songsByPath,
                        )
                    } catch (_: Exception) { null }
                    if (item != null) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun identityOf(song: AudioFile): SongIdentity =
        SongIdentity(song.path, song.cueOffsetMs, song.cueTrackIndex)

    private fun decodeSongFromJson(
        json: String,
        songsByIdentity: Map<SongIdentity, AudioFile>,
        songsById: Map<Long, AudioFile>,
        songsByPath: Map<String, AudioFile>,
    ): AudioFile? = runCatching { decodeSongFromJson(JSONObject(json), songsByIdentity, songsById, songsByPath) }.getOrNull()

    private fun decodeSongFromJson(
        obj: JSONObject,
        songsByIdentity: Map<SongIdentity, AudioFile>,
        songsById: Map<Long, AudioFile>,
        songsByPath: Map<String, AudioFile>,
    ): AudioFile? {
        val path = obj.optString("path", "")
        if (path.isBlank()) return null
        val id = obj.optLong("id", -1L)
        val cueOffsetMs = obj.optLong("cueOffsetMs", 0L)
        val cueTrackIndex = obj.optInt("cueTrackIndex", 0)
        val exact = songsByIdentity[SongIdentity(path, cueOffsetMs, cueTrackIndex)]
        val legacy = if (cueOffsetMs == 0L && cueTrackIndex == 0) {
            (if (id != -1L) songsById[id] else null) ?: songsByPath[path]
        } else null
        return exact ?: legacy ?: AudioFile(
            id = id,
            path = path,
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            album = obj.optString("album", ""),
            albumId = obj.optLong("albumId", -1L),
            duration = obj.optLong("duration", 0L),
            sampleRate = obj.optInt("sampleRate", 0),
            bitRate = obj.optInt("bitRate", 0),
            bitsPerSample = obj.optInt("bitsPerSample", 0),
            format = obj.optString("format", ""),
            fileSize = obj.optLong("fileSize", 0L),
            trackNumber = obj.optInt("trackNumber", 0),
            year = obj.optInt("year", 0),
            dateAdded = obj.optLong("dateAdded", 0L),
            dateModified = obj.optLong("dateModified", 0L),
            albumArtPath = obj.optString("albumArtPath", ""),
            genre = obj.optString("genre", ""),
            composer = obj.optString("composer", ""),
            discNumber = obj.optInt("discNumber", 0),
            channelCount = obj.optInt("channelCount", 0),
            bpm = obj.optInt("bpm", 0),
            albumArtist = obj.optString("albumArtist", ""),
            encodingFormat = obj.optString("encodingFormat", ""),
            isFavorite = obj.optBoolean("isFavorite", false),
            trackGain = obj.optDouble("trackGain", 0.0).toFloat(),
            trackPeak = obj.optDouble("trackPeak", 1.0).toFloat(),
            albumGain = obj.optDouble("albumGain", 0.0).toFloat(),
            albumPeak = obj.optDouble("albumPeak", 1.0).toFloat(),
            cueOffsetMs = cueOffsetMs,
            cueEndMs = obj.optLong("cueEndMs", 0L),
            cueTrackIndex = cueTrackIndex,
        )
    }

    private fun ensureEntryIds(songs: List<AudioFile>, entryIds: List<Long>): List<Long> {
        return QueueEntryIdGenerator.normalize(songs.size, entryIds)
    }
}
