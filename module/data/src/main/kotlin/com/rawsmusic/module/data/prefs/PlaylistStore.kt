package com.rawsmusic.module.data.prefs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistImportResult(
    val playlist: UserPlaylist?,
    val importedCount: Int,
    val missingCount: Int
)

class PlaylistStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: PlaylistStore? = null

        fun getInstance(context: Context): PlaylistStore =
            instance ?: synchronized(this) {
                instance ?: PlaylistStore(context.applicationContext).also { instance = it }
            }
    }

    private val storeFile = context.filesDir.resolve("rawsmusic_playlists.json")
    private val backupFile = File(storeFile.parentFile, "${storeFile.name}.bak")
    private val legacyPlaylists = loadLegacyPlaylists()
    private val dao = MusicDatabase.getInstance(context).userPlaylistDao()
    private val databaseDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "RawS-PlaylistsRoom").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
    private val databaseScope = CoroutineScope(SupervisorJob() + databaseDispatcher)
    private val mutationMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()

    private val _playlists = MutableStateFlow(
        legacyPlaylists ?: listOf(createFavoritesPlaylist())
    )
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    init {
        databaseScope.launch {
            runCatching {
                val legacy = legacyPlaylists
                when {
                    legacy != null -> {
                        replaceRoom(ensureFavorites(legacy))
                        deleteLegacyFiles()
                    }
                    dao.countPlaylists() == 0 -> replaceRoom(listOf(createFavoritesPlaylist()))
                }
                _playlists.value = loadRoomSnapshot()
            }
            ready.complete(Unit)
        }
    }

    fun favoriteSongKeys(): Set<String> =
        _playlists.value.firstOrNull { it.isFavorites }?.songs?.mapTo(mutableSetOf()) { it.key }
            ?: emptySet()

    fun isFavorite(song: AudioFile): Boolean = song.playlistIdentityKey() in favoriteSongKeys()

    suspend fun toggleFavorite(song: AudioFile): Boolean {
        ready.await()
        val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return false
        return if (favorite.songs.any { it.key == song.playlistIdentityKey() }) {
            removeSongFromPlaylist(favorite.id, song.playlistIdentityKey())
            false
        } else {
            addSongToPlaylist(favorite.id, song)
            true
        }
    }

    suspend fun createPlaylist(name: String): UserPlaylist? = withDatabase {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return@withDatabase null
        mutationMutex.withLock {
            if (_playlists.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return@withLock null
            }
            val now = System.currentTimeMillis()
            val playlist = UserPlaylist("playlist-${UUID.randomUUID()}", cleanName, emptyList(), now, now)
            dao.upsertPlaylist(playlist.toEntity(_playlists.value.size))
            _playlists.value = _playlists.value + playlist
            playlist
        }
    }

    suspend fun deletePlaylist(id: String) = withDatabase {
        if (id == FAVORITES_PLAYLIST_ID) return@withDatabase
        mutationMutex.withLock {
            dao.deletePlaylist(id)
            _playlists.value = _playlists.value.filterNot { it.id == id }
        }
    }

    suspend fun renamePlaylist(id: String, newName: String): Boolean = withDatabase {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withDatabase false
        mutationMutex.withLock {
            if (_playlists.value.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) {
                return@withLock false
            }
            val current = _playlists.value.firstOrNull { it.id == id && !it.isFavorites }
                ?: return@withLock false
            val now = System.currentTimeMillis()
            dao.renamePlaylist(id, cleanName, now)
            _playlists.value = _playlists.value.map { playlist ->
                if (playlist.id == current.id) playlist.copy(name = cleanName, updatedAt = now)
                else playlist
            }
            true
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, song: AudioFile) =
        addSongsToPlaylist(playlistId, listOf(song))

    suspend fun addSongsToPlaylist(playlistId: String, songs: Collection<AudioFile>) = withDatabase {
        val incoming = songs.map { it.toUserPlaylistSong() }
        mutationMutex.withLock {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withLock
            val keys = playlist.songs.mapTo(mutableSetOf()) { it.key }
            val additions = incoming.filter { keys.add(it.key) }
            if (additions.isEmpty()) return@withLock

            val now = System.currentTimeMillis()
            dao.insertSongs(additions.mapIndexed { index, song ->
                song.toEntity(playlistId, playlist.songs.size + index)
            })
            dao.touchPlaylist(playlistId, now)
            _playlists.value = _playlists.value.map { current ->
                if (current.id == playlistId) {
                    current.copy(songs = current.songs + additions, updatedAt = now)
                } else {
                    current
                }
            }
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songKey: String) = withDatabase {
        mutationMutex.withLock {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withLock
            if (playlist.songs.none { it.key == songKey }) return@withLock
            val now = System.currentTimeMillis()
            dao.deleteSong(playlistId, songKey)
            dao.touchPlaylist(playlistId, now)
            _playlists.value = _playlists.value.map { current ->
                if (current.id == playlistId) {
                    current.copy(
                        songs = current.songs.filterNot { it.key == songKey },
                        updatedAt = now
                    )
                } else {
                    current
                }
            }
        }
    }

    /** Resolves stored identities against the live library so edited tags and artwork stay current. */
    fun resolveSongs(playlist: UserPlaylist?, librarySongs: List<AudioFile>): List<AudioFile> {
        playlist ?: return emptyList()
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val byPath = buildMap<String, AudioFile> {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }
        return playlist.songs.mapNotNull { stored ->
            byIdentity[stored.key]
                ?: stored.path.pathCandidates().firstNotNullOfOrNull(byPath::get)
                ?: stored.toAudioFile().takeIf { File(it.path).exists() }
        }
    }

    suspend fun importTextPlaylist(uri: Uri, librarySongs: List<AudioFile>): PlaylistImportResult =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext PlaylistImportResult(null, 0, 0)
            val requestedPaths = decodeText(bytes)
                .lineSequence()
                .mapNotNull(::parsePlaylistLine)
                .distinctBy { it.normalizedPath() }
                .toList()

            val exact = buildMap<String, AudioFile> {
                librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
            }
            val byFileName = librarySongs.groupBy { it.path.substringAfterLast('/').lowercase() }
            val matched = requestedPaths.mapNotNull { requested ->
                requested.pathCandidates().firstNotNullOfOrNull(exact::get)
                    ?: requested.pathCandidates().firstNotNullOfOrNull { candidate ->
                        librarySongs.firstOrNull { song ->
                            song.path.normalizedPath().endsWith("/${candidate.trimStart('/')}")
                        }
                    }
                    ?: byFileName[requested.substringAfterLast('/').lowercase()]?.singleOrNull()
            }.distinctBy { it.playlistIdentityKey() }

            withDatabase {
                mutationMutex.withLock {
                    val baseName = displayName(uri).substringBeforeLast('.').trim()
                        .ifBlank { "Imported playlist" }
                    val now = System.currentTimeMillis()
                    val existing = _playlists.value.firstOrNull {
                        !it.isFavorites && it.name.equals(baseName, ignoreCase = true)
                    }
                    val existingKeys = existing?.songs?.mapTo(mutableSetOf()) { it.key } ?: mutableSetOf()
                    val importedSongs = existing.orEmptySongs() +
                        matched.map { it.toUserPlaylistSong() }.filter { existingKeys.add(it.key) }
                    val imported = UserPlaylist(
                        id = existing?.id ?: "playlist-${UUID.randomUUID()}",
                        name = existing?.name ?: baseName,
                        songs = importedSongs,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                    dao.upsertPlaylist(imported.toEntity(existing?.let(_playlists.value::indexOf) ?: _playlists.value.size))
                    if (existing != null) dao.deleteSongsForPlaylist(existing.id)
                    dao.insertSongs(imported.songs.mapIndexed { index, song ->
                        song.toEntity(imported.id, index)
                    })
                    _playlists.value = if (existing == null) {
                        _playlists.value + imported
                    } else {
                        _playlists.value.map { if (it.id == existing.id) imported else it }
                    }
                    PlaylistImportResult(
                        imported,
                        matched.size,
                        (requestedPaths.size - matched.size).coerceAtLeast(0)
                    )
                }
            }
        }

    private fun UserPlaylist?.orEmptySongs(): List<UserPlaylistSong> = this?.songs.orEmpty()

    fun exportJson(): JSONObject = JSONObject().put(
        "playlists",
        JSONArray().also { array -> _playlists.value.forEach { array.put(playlistToJson(it)) } }
    )

    suspend fun restoreJson(json: JSONObject) = withDatabase {
        val array = json.optJSONArray("playlists") ?: return@withDatabase
        val restored = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
            }
        }.let(::ensureFavorites)
        mutationMutex.withLock {
            replaceRoom(restored)
            deleteLegacyFiles()
            _playlists.value = restored
        }
    }

    private suspend fun <T> withDatabase(block: suspend () -> T): T =
        withContext(databaseDispatcher) {
            ready.await()
            block()
        }

    private suspend fun replaceRoom(playlists: List<UserPlaylist>) {
        dao.replaceAll(
            playlists = playlists.mapIndexed { index, playlist -> playlist.toEntity(index) },
            songs = playlists.flatMap { playlist ->
                playlist.songs.mapIndexed { index, song -> song.toEntity(playlist.id, index) }
            }
        )
    }

    private suspend fun loadRoomSnapshot(): List<UserPlaylist> {
        val songsByPlaylist = dao.getSongs()
            .groupBy(UserPlaylistSongEntity::playlistId)
            .mapValues { (_, songs) -> songs.map(UserPlaylistSongEntity::toModel) }
        return ensureFavorites(
            dao.getPlaylists().map { playlist ->
                UserPlaylist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    songs = songsByPlaylist[playlist.playlistId].orEmpty(),
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt
                )
            }
        )
    }

    private fun loadLegacyPlaylists(): List<UserPlaylist>? {
        val source = when {
            storeFile.exists() -> storeFile
            backupFile.exists() -> backupFile
            else -> return null
        }
        return runCatching {
            val array = JSONObject(source.readText(Charsets.UTF_8))
                .optJSONArray("playlists") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
                }
            }.let(::ensureFavorites)
        }.getOrNull()
    }

    private fun deleteLegacyFiles() {
        storeFile.delete()
        backupFile.delete()
        File(storeFile.parentFile, "${storeFile.name}.tmp").delete()
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
    }.getOrNull().orEmpty()

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16LE"))
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16BE"))
        else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun parsePlaylistLine(raw: String): String? {
        val line = raw.trim().trim('"')
        if (line.isBlank() || line.startsWith('#')) return null
        val candidate = line.substringAfterLast('\t').substringAfterLast('|').trim().trim('"')
        return runCatching {
            if (candidate.startsWith("file://", true)) {
                URLDecoder.decode(Uri.parse(candidate).path.orEmpty(), Charsets.UTF_8.name())
            } else {
                candidate
            }
        }.getOrDefault(candidate).replace('\\', '/')
    }

    private fun String.normalizedPath(): String = trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun String.pathCandidates(): Set<String> {
        val normalized = normalizedPath().removePrefix("file://").trimStart('/')
        if (normalized.isBlank()) return emptySet()
        val result = linkedSetOf(normalized, "/$normalized")
        val relative = when {
            normalized.startsWith("primary/") -> normalized.removePrefix("primary/")
            normalized.startsWith("storage/emulated/0/") -> normalized.removePrefix("storage/emulated/0/")
            normalized.startsWith("sdcard/") -> normalized.removePrefix("sdcard/")
            else -> null
        }
        if (relative != null) {
            result += relative
            result += "primary/$relative"
            result += "/storage/emulated/0/$relative"
            result += "/sdcard/$relative"
        }
        return result
    }

    private fun createFavoritesPlaylist(): UserPlaylist {
        val now = System.currentTimeMillis()
        return UserPlaylist(FAVORITES_PLAYLIST_ID, "我喜欢的音乐", emptyList(), now, now)
    }

    private fun ensureFavorites(playlists: List<UserPlaylist>): List<UserPlaylist> =
        if (playlists.any { it.isFavorites }) playlists else listOf(createFavoritesPlaylist()) + playlists

    private fun playlistToJson(playlist: UserPlaylist): JSONObject = JSONObject()
        .put("id", playlist.id)
        .put("name", playlist.name)
        .put("createdAt", playlist.createdAt)
        .put("updatedAt", playlist.updatedAt)
        .put("songs", JSONArray().also { songs ->
            playlist.songs.forEach { song ->
                songs.put(JSONObject()
                    .put("key", song.key).put("id", song.id).put("title", song.title)
                    .put("artist", song.artist).put("album", song.album).put("albumId", song.albumId)
                    .put("duration", song.duration).put("path", song.path).put("fileSize", song.fileSize)
                    .put("format", song.format).put("sampleRate", song.sampleRate).put("bitRate", song.bitRate)
                    .put("bitsPerSample", song.bitsPerSample).put("albumArtPath", song.albumArtPath)
                    .put("addedAt", song.addedAt))
            }
        })

    private fun jsonToPlaylist(json: JSONObject): UserPlaylist {
        val array = json.optJSONArray("songs") ?: JSONArray()
        val songs = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(UserPlaylistSong(
                    key = item.optString("key"), id = item.optLong("id"), title = item.optString("title"),
                    artist = item.optString("artist"), album = item.optString("album"),
                    albumId = item.optLong("albumId", -1), duration = item.optLong("duration"),
                    path = item.optString("path"), fileSize = item.optLong("fileSize"),
                    format = item.optString("format"), sampleRate = item.optInt("sampleRate"),
                    bitRate = item.optInt("bitRate"), bitsPerSample = item.optInt("bitsPerSample"),
                    albumArtPath = item.optString("albumArtPath"), addedAt = item.optLong("addedAt")
                ))
            }
        }
        return UserPlaylist(
            id = json.optString("id").ifBlank { "playlist-${UUID.randomUUID()}" },
            name = json.optString("name"),
            songs = songs,
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt")
        )
    }
}

private fun UserPlaylist.toEntity(sortOrder: Int) = UserPlaylistEntity(
    playlistId = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder
)

private fun UserPlaylistSong.toEntity(playlistId: String, sortOrder: Int) = UserPlaylistSongEntity(
    playlistId = playlistId,
    songKey = key,
    mediaId = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    path = path,
    fileSize = fileSize,
    format = format,
    sampleRate = sampleRate,
    bitRate = bitRate,
    bitsPerSample = bitsPerSample,
    albumArtPath = albumArtPath,
    addedAt = addedAt,
    sortOrder = sortOrder
)

private fun UserPlaylistSongEntity.toModel() = UserPlaylistSong(
    key = songKey,
    id = mediaId,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    path = path,
    fileSize = fileSize,
    format = format,
    sampleRate = sampleRate,
    bitRate = bitRate,
    bitsPerSample = bitsPerSample,
    albumArtPath = albumArtPath,
    addedAt = addedAt
)
