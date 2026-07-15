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
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val lock = Any()
    private val storeFile = context.filesDir.resolve("rawsmusic_playlists.json")
    private val _playlists = MutableStateFlow(loadPlaylists())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    fun favoriteSongKeys(): Set<String> =
        _playlists.value.firstOrNull { it.isFavorites }?.songs?.mapTo(mutableSetOf()) { it.key }
            ?: emptySet()

    fun isFavorite(song: AudioFile): Boolean = song.playlistIdentityKey() in favoriteSongKeys()

    suspend fun toggleFavorite(song: AudioFile): Boolean {
        val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return false
        return if (favorite.songs.any { it.key == song.playlistIdentityKey() }) {
            removeSongFromPlaylist(favorite.id, song.playlistIdentityKey())
            false
        } else {
            addSongToPlaylist(favorite.id, song)
            true
        }
    }

    suspend fun createPlaylist(name: String): UserPlaylist? = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return@withContext null
        synchronized(lock) {
            if (_playlists.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return@synchronized null
            }
            val now = System.currentTimeMillis()
            val playlist = UserPlaylist("playlist-${UUID.randomUUID()}", cleanName, emptyList(), now, now)
            publishLocked(_playlists.value + playlist)
            playlist
        }
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        if (id == FAVORITES_PLAYLIST_ID) return@withContext
        synchronized(lock) { publishLocked(_playlists.value.filterNot { it.id == id }) }
    }

    suspend fun renamePlaylist(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withContext false
        synchronized(lock) {
            if (_playlists.value.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) {
                return@synchronized false
            }
            var renamed = false
            val updated = _playlists.value.map { playlist ->
                if (playlist.id == id && !playlist.isFavorites) {
                    renamed = true
                    playlist.copy(name = cleanName, updatedAt = System.currentTimeMillis())
                } else {
                    playlist
                }
            }
            if (renamed) publishLocked(updated)
            renamed
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, song: AudioFile) =
        addSongsToPlaylist(playlistId, listOf(song))

    suspend fun addSongsToPlaylist(playlistId: String, songs: Collection<AudioFile>) =
        withContext(Dispatchers.IO) {
            val incoming = songs.map { it.toUserPlaylistSong() }
            synchronized(lock) {
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) return@map playlist
                    val keys = playlist.songs.mapTo(mutableSetOf()) { it.key }
                    val additions = incoming.filter { keys.add(it.key) }
                    if (additions.isEmpty()) playlist else playlist.copy(
                        songs = playlist.songs + additions,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                publishLocked(updated)
            }
        }

    suspend fun removeSongFromPlaylist(playlistId: String, songKey: String) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val updated = _playlists.value.map { playlist ->
                    if (playlist.id != playlistId) playlist else playlist.copy(
                        songs = playlist.songs.filterNot { it.key == songKey },
                        updatedAt = System.currentTimeMillis()
                    )
                }
                publishLocked(updated)
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
                    ?: byFileName[requested.substringAfterLast('/').lowercase()]?.singleOrNull()
            }.distinctBy { it.playlistIdentityKey() }

            val baseName = displayName(uri).substringBeforeLast('.').trim().ifBlank { "Imported playlist" }
            val playlistName = uniquePlaylistName(baseName)
            val now = System.currentTimeMillis()
            val imported = UserPlaylist(
                id = "playlist-${UUID.randomUUID()}",
                name = playlistName,
                songs = matched.map { it.toUserPlaylistSong() },
                createdAt = now,
                updatedAt = now
            )
            synchronized(lock) { publishLocked(_playlists.value + imported) }
            PlaylistImportResult(imported, matched.size, (requestedPaths.size - matched.size).coerceAtLeast(0))
        }

    fun exportJson(): JSONObject = JSONObject().put(
        "playlists",
        JSONArray().also { array -> _playlists.value.forEach { array.put(playlistToJson(it)) } }
    )

    suspend fun restoreJson(json: JSONObject) = withContext(Dispatchers.IO) {
        val array = json.optJSONArray("playlists") ?: return@withContext
        val restored = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
            }
        }
        synchronized(lock) { publishLocked(ensureFavorites(restored)) }
    }

    private fun publishLocked(playlists: List<UserPlaylist>) {
        val safe = ensureFavorites(playlists)
        saveAtomically(safe)
        _playlists.value = safe
    }

    private fun saveAtomically(playlists: List<UserPlaylist>) {
        val root = JSONObject().put(
            "playlists",
            JSONArray().also { array -> playlists.forEach { array.put(playlistToJson(it)) } }
        )
        val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        val backup = File(storeFile.parentFile, "${storeFile.name}.bak")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (storeFile.exists()) {
            backup.delete()
            if (!storeFile.renameTo(backup)) throw IllegalStateException("Unable to back up playlists")
        }
        if (!temp.renameTo(storeFile)) {
            backup.renameTo(storeFile)
            throw IllegalStateException("Unable to commit playlists")
        }
        backup.delete()
    }

    private fun loadPlaylists(): List<UserPlaylist> {
        val source = when {
            storeFile.exists() -> storeFile
            File(storeFile.parentFile, "${storeFile.name}.bak").exists() ->
                File(storeFile.parentFile, "${storeFile.name}.bak")
            else -> return listOf(createFavoritesPlaylist())
        }
        return runCatching {
            val array = JSONObject(source.readText(Charsets.UTF_8)).optJSONArray("playlists") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
                }
            }.let(::ensureFavorites)
        }.getOrElse { listOf(createFavoritesPlaylist()) }
    }

    private fun uniquePlaylistName(base: String): String {
        val existing = _playlists.value.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in existing) return base
        var suffix = 2
        while ("$base ($suffix)".lowercase() in existing) suffix++
        return "$base ($suffix)"
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
                    artist = item.optString("artist"), album = item.optString("album"), albumId = item.optLong("albumId", -1),
                    duration = item.optLong("duration"), path = item.optString("path"), fileSize = item.optLong("fileSize"),
                    format = item.optString("format"), sampleRate = item.optInt("sampleRate"), bitRate = item.optInt("bitRate"),
                    bitsPerSample = item.optInt("bitsPerSample"), albumArtPath = item.optString("albumArtPath"),
                    addedAt = item.optLong("addedAt")
                ))
            }
        }
        return UserPlaylist(
            id = json.optString("id").ifBlank { "playlist-${UUID.randomUUID()}" },
            name = json.optString("name"), songs = songs,
            createdAt = json.optLong("createdAt"), updatedAt = json.optLong("updatedAt")
        )
    }
}
