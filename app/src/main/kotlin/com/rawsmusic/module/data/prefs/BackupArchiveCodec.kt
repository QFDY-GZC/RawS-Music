package com.rawsmusic.module.data.prefs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal object BackupArchiveCodec {
    const val CURRENT_VERSION = 3
    private const val FORMAT = "RawSMusicBackup"

    /** Adapter for the existing Room Store JSON contracts; never forwards unvalidated input. */
    internal fun legacyPayload(backup: ValidatedBackup, selection: BackupSelection): JsonObject = JsonObject().apply {
        addProperty("version", 2)
        addProperty("timestamp", backup.timestamp)
        val admitted = backup.preferences.filter { BackupPreferenceRegistry.classify(it.address)?.section in selection.sections }
        if (admitted.isNotEmpty()) add("settings", JsonObject().apply {
            add("mmkv", JsonArray().apply { admitted.filter { it.address.store == "mmkv" }.forEach { add(encodeEntry(it)) } })
            add("sharedPreferences", JsonObject().apply {
                admitted.filter { it.address.store != "mmkv" }.groupBy { it.address.store }.forEach { (store, entries) ->
                    add(store, JsonArray().apply { entries.forEach { add(encodeEntry(it)) } })
                }
            })
        })
        if (BackupSection.PLAYLISTS in selection.sections) backup.playlists?.let { add("playlists", encodeLegacyPlaylists(it)) }
        if (BackupSection.PLAYBACK in selection.sections) backup.playback?.let { add("playback", encodePlayback(it)) }
        require(admitted.isNotEmpty() || has("playlists") || has("playback")) { "No selected recoverable content" }
    }

    fun decode(bytes: ByteArray): ValidatedBackup {
        require(bytes.isNotEmpty() && bytes.size <= BackupBoundedReader.MAX_BYTES) { "Invalid backup size" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) { throw IllegalArgumentException("Invalid UTF-8 backup", failure) }
        val root = objectValue(BackupStrictJson.parse(text))
        val version = if (root.has("version")) integer(root["version"]) else 1L
        require(version in 1L..CURRENT_VERSION.toLong()) { "Unsupported backup version: $version" }
        val timestamp = if (root.has("timestamp")) nonnegative(root["timestamp"]) else 0L
        val appVersion = if (root.has("appVersion")) string(root["appVersion"]) else ""
        return if (version == 3L) decodeV3(root, timestamp, appVersion)
        else decodeLegacy(root, version.toInt(), timestamp, appVersion)
    }

    fun encode(backup: ValidatedBackup, selection: BackupSelection): ByteArray {
        val sections = JsonObject()
        listOf(BackupSection.PREFERENCES, BackupSection.DEVICE_REFERENCES).forEach { section ->
            if (section in selection.sections) {
                val entries = backup.preferences.filter { BackupPreferenceRegistry.classify(it.address)?.section == section }
                if (entries.isNotEmpty()) sections.add(section.name, JsonArray().apply {
                    entries.sortedWith(compareBy({ it.address.store }, { it.address.key })).forEach { add(encodeEntry(it)) }
                })
            }
        }
        if (BackupSection.PLAYLISTS in selection.sections) backup.playlists?.let { playlists ->
            sections.add(BackupSection.PLAYLISTS.name, encodePlaylists(playlists))
        }
        if (BackupSection.PLAYBACK in selection.sections) backup.playback?.let { playback ->
            sections.add(BackupSection.PLAYBACK.name, encodePlayback(playback))
        }
        require(sections.size() > 0) { "No selected backup content" }
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", CURRENT_VERSION)
            addProperty("timestamp", backup.timestamp)
            addProperty("appVersion", backup.appVersion)
            add("sections", sections)
            add("counts", counts(sections))
            addProperty("contentSha256", digest(sections))
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupBoundedReader.MAX_BYTES) { "Backup exceeds size limit" }
        decode(bytes) // Also validate business-model content supplied by the exporter.
        return bytes
    }

    private fun decodeLegacy(root: JsonObject, version: Int, timestamp: Long, appVersion: String): ValidatedBackup {
        val entries = mutableListOf<PreferenceEntry>()
        val skipped = mutableListOf<String>()
        val seen = HashSet<PreferenceAddress>()
        if (root.has("settings")) {
            require(version >= 2) { "Version 1 cannot contain preferences" }
            val settings = objectValue(root["settings"])
            array(settings["mmkv"]).forEach { readEntry(it, "mmkv", seen, entries, skipped) }
            objectValue(settings["sharedPreferences"]).entrySet().forEach { (store, values) ->
                array(values).forEach { readEntry(it, store, seen, entries, skipped) }
            }
        }
        val playlists = root.get("playlists")?.let(::parsePlaylists)
        val playback = root.get("playback")?.let(::parsePlayback)
        val sections = entries.map { requireNotNull(BackupPreferenceRegistry.classify(it.address)).section }.toMutableSet()
        if (playlists != null) sections += BackupSection.PLAYLISTS
        if (playback != null) sections += BackupSection.PLAYBACK
        require(sections.isNotEmpty() || skipped.isNotEmpty()) { "No recoverable backup structure" }
        return ValidatedBackup(version, timestamp, appVersion, sections, entries, playlists, playback, skipped)
    }

    private fun decodeV3(root: JsonObject, timestamp: Long, appVersion: String): ValidatedBackup {
        require(string(root["format"]) == FORMAT) { "Invalid backup format" }
        require(root.has("timestamp") && root.has("appVersion")) { "Missing backup metadata" }
        val sections = objectValue(root["sections"])
        require(sections.size() > 0) { "No backup sections" }
        val declaredCounts = objectValue(root["counts"])
        require(declaredCounts == counts(sections)) { "Backup content counts mismatch" }
        require(string(root["contentSha256"]) == digest(sections)) { "Backup checksum mismatch" }
        val entries = mutableListOf<PreferenceEntry>()
        val skipped = mutableListOf<String>()
        val seen = HashSet<PreferenceAddress>()
        var playlists: List<UserPlaylist>? = null
        var playback: BackupPlayback? = null
        val selected = mutableSetOf<BackupSection>()
        sections.entrySet().forEach { (name, payload) ->
            val section = try { BackupSection.valueOf(name) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("Unknown backup section: $name", failure) }
            selected += section
            when (section) {
                BackupSection.PLAYLISTS -> playlists = parsePlaylists(payload)
                BackupSection.PLAYBACK -> playback = parsePlayback(payload)
                else -> array(payload).forEach { item ->
                    val entry = objectValue(item)
                    val before = entries.size
                    readEntry(item, string(entry["store"]), seen, entries, skipped)
                    if (entries.size > before) require(BackupPreferenceRegistry.classify(entries.last().address)?.section == section) { "Preference in wrong section" }
                }
            }
        }
        require(entries.isNotEmpty() || playlists != null || playback != null || skipped.isNotEmpty()) { "Empty preferences are not a restore operation" }
        return ValidatedBackup(3, timestamp, appVersion, selected, entries, playlists, playback, skipped)
    }

    private fun readEntry(
        raw: JsonElement, store: String, seen: MutableSet<PreferenceAddress>,
        entries: MutableList<PreferenceEntry>, skipped: MutableList<String>,
    ) {
        val item = objectValue(raw)
        val key = string(item["key"])
        require(key.isNotBlank() && store.isNotBlank()) { "Empty preference address" }
        val address = PreferenceAddress(store, key)
        require(seen.add(address)) { "Duplicate preference address: $address" }
        val type = string(item["type"])
        require(item.has("value")) { "Missing preference value" }
        val rule = BackupPreferenceRegistry.classify(address)
        if (rule == null) { skipped += "$store/$key: unknown or excluded"; return }
        require(type == rule.type.name) { "Wrong preference type: $address" }
        val value = decodeValue(rule.type, item["value"])
        val entry = BackupPreferenceRegistry.normalizeLegacy(PreferenceEntry(address, value))
        BackupPreferenceRegistry.validate(entry)
        entries += entry
    }

    private fun decodeValue(type: BackupPreferenceSchema.ValueType, raw: JsonElement): PreferenceValue = when (type) {
        BackupPreferenceSchema.ValueType.BOOLEAN -> PreferenceValue.BooleanValue(boolean(raw))
        BackupPreferenceSchema.ValueType.INT -> PreferenceValue.IntValue(int(raw))
        BackupPreferenceSchema.ValueType.LONG -> PreferenceValue.LongValue(integer(raw))
        BackupPreferenceSchema.ValueType.FLOAT -> {
            val value = BackupStrictJson.number(raw).toFloat()
            require(value.isFinite()) { "Float overflow" }
            PreferenceValue.FloatValue(value)
        }
        BackupPreferenceSchema.ValueType.DOUBLE -> PreferenceValue.DoubleValue(BackupStrictJson.number(raw))
        BackupPreferenceSchema.ValueType.STRING -> PreferenceValue.StringValue(string(raw))
        BackupPreferenceSchema.ValueType.STRING_SET -> {
            val values = array(raw).map(::string)
            require(values.distinct().size == values.size) { "Duplicate string-set values" }
            PreferenceValue.StringSetValue(values.toSet())
        }
    }

    private fun encodeEntry(rawEntry: PreferenceEntry): JsonObject {
        val entry = BackupPreferenceRegistry.normalizeLegacy(rawEntry)
        BackupPreferenceRegistry.validate(entry)
        return JsonObject().apply {
            addProperty("store", entry.address.store)
            addProperty("key", entry.address.key)
            addProperty("type", entry.value.type.name)
            add("value", when (val value = entry.value) {
                is PreferenceValue.BooleanValue -> JsonPrimitive(value.value)
                is PreferenceValue.IntValue -> JsonPrimitive(value.value)
                is PreferenceValue.LongValue -> JsonPrimitive(value.value)
                is PreferenceValue.FloatValue -> JsonPrimitive(value.value)
                is PreferenceValue.DoubleValue -> JsonPrimitive(value.value)
                is PreferenceValue.StringValue -> JsonPrimitive(value.value)
                is PreferenceValue.StringSetValue -> JsonArray().apply { value.value.sorted().forEach { add(it) } }
            })
        }
    }

    private fun parsePlaylists(raw: JsonElement): List<UserPlaylist> {
        val root = objectValue(raw)
        if (root.has("encoding")) {
            require(string(root["encoding"]) == "local-paths-v1") { "Unsupported playlist encoding" }
            val playlists = array(root["playlists"]).mapIndexed { index, item ->
                val group = objectValue(item)
                require(group.keySet() == setOf("favorites", "paths")) { "Invalid local playlist group" }
                val favorite = group["favorites"].let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
                val paths = array(group["paths"]).map { string(it).also { path -> require(path.isNotBlank()) { "Empty playlist path" } } }
                require(paths.distinct().size == paths.size) { "Duplicate playlist path" }
                UserPlaylist(if (favorite) com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID else "restored-$index",
                    if (favorite) "我喜欢的音乐" else "导入歌单 ${index + 1}", paths.map { path ->
                        UserPlaylistSong("path|${path.replace('\\', '/').lowercase()}", 0, "", "", "", 0, 0, path, 0, "", 0, 0, 0, "", 0)
                    }, 0, 0)
            }
            require(playlists.map { it.id }.distinct().size == playlists.size) { "Duplicate favorites group" }
            return playlists
        }
        val playlists = array(root["playlists"]).map { item ->
            val playlist = objectValue(item)
            val id = string(playlist["id"])
            require(id.isNotBlank()) { "Empty playlist ID" }
            val songs = array(playlist["songs"]).map { songRaw ->
                val song = objectValue(songRaw)
                val key = string(song["key"])
                require(key.isNotBlank()) { "Empty playlist song identity" }
                UserPlaylistSong(key, integer(song["id"]), string(song["title"]), string(song["artist"]),
                    string(song["album"]), integer(song["albumId"]), nonnegative(song["duration"]),
                    string(song["path"]), nonnegative(song["fileSize"]), string(song["format"]),
                    nonnegativeInt(song["sampleRate"]), nonnegativeInt(song["bitRate"]), nonnegativeInt(song["bitsPerSample"]),
                    string(song["albumArtPath"]), nonnegative(song["addedAt"]))
            }
            require(songs.map { it.key }.distinct().size == songs.size) { "Duplicate song in playlist" }
            UserPlaylist(id, string(playlist["name"]), songs, nonnegative(playlist["createdAt"]), nonnegative(playlist["updatedAt"]))
        }
        require(playlists.map { it.id }.distinct().size == playlists.size) { "Duplicate playlist ID" }
        return playlists
    }

    private fun parsePlayback(raw: JsonElement): BackupPlayback {
        val playback = objectValue(raw)
        val stats = array(playback["stats"]).map { item ->
            val stat = objectValue(item)
            SongPlaybackStats(integer(stat["songId"]), string(stat["title"]), string(stat["artist"]),
                string(stat["album"]), nonnegativeInt(stat["playCount"]), nonnegative(stat["listenedMs"]), nonnegative(stat["lastPlayedAt"]))
        }
        require(stats.map { it.songId }.distinct().size == stats.size) { "Duplicate playback statistic" }
        val history = array(playback["history"]).map { item ->
            val entry = objectValue(item)
            PlaybackHistoryEntry(integer(entry["songId"]), string(entry["title"]), string(entry["artist"]),
                string(entry["album"]), nonnegative(entry["playedAt"]))
        }
        require(history.size <= 200) { "Playback history exceeds retained limit" }
        val daily = objectValue(playback["daily"]).entrySet().associate { (date, value) ->
            try { java.time.LocalDate.parse(date) }
            catch (failure: Exception) { throw IllegalArgumentException("Invalid playback date", failure) }
            date to nonnegative(value)
        }
        val ids = (stats.map { it.songId } + history.map { it.songId }).toSet()
        val paths = playback.get("paths")?.let { objectValue(it).entrySet().associate { (key, value) ->
            val id = key.toLongOrNull() ?: throw IllegalArgumentException("Invalid playback path identity")
            require(key == id.toString() && id in ids) { "Unknown playback path identity" }
            id to string(value).also { path -> require(path.isNotBlank()) { "Empty playback path" } }
        } }.orEmpty()
        return BackupPlayback(stats, history, daily, paths)
    }

    private fun encodePlaylists(playlists: List<UserPlaylist>) = JsonObject().apply {
        addProperty("encoding", "local-paths-v1")
        add("playlists", JsonArray().apply { playlists.forEach { playlist -> add(JsonObject().apply {
            addProperty("favorites", playlist.isFavorites)
            add("paths", JsonArray().apply { playlist.songs.map { it.path }.filter { it.isNotBlank() }.distinct().forEach { add(it) } })
        }) } })
    }

    private fun encodeLegacyPlaylists(playlists: List<UserPlaylist>) = JsonObject().apply {
        add("playlists", JsonArray().apply { playlists.forEach { playlist ->
            add(JsonObject().apply {
                addProperty("id", playlist.id); addProperty("name", playlist.name)
                addProperty("createdAt", playlist.createdAt); addProperty("updatedAt", playlist.updatedAt)
                add("songs", JsonArray().apply { playlist.songs.forEach { song -> add(JsonObject().apply {
                    addProperty("key", song.key); addProperty("id", song.id); addProperty("title", song.title)
                    addProperty("artist", song.artist); addProperty("album", song.album); addProperty("albumId", song.albumId)
                    addProperty("duration", song.duration); addProperty("path", song.path); addProperty("fileSize", song.fileSize)
                    addProperty("format", song.format); addProperty("sampleRate", song.sampleRate); addProperty("bitRate", song.bitRate)
                    addProperty("bitsPerSample", song.bitsPerSample); addProperty("albumArtPath", song.albumArtPath); addProperty("addedAt", song.addedAt)
                }) } })
            })
        } })
    }

    private fun encodePlayback(playback: BackupPlayback) = JsonObject().apply {
        add("stats", JsonArray().apply { playback.stats.forEach { stat -> add(JsonObject().apply {
            addProperty("songId", stat.songId); addProperty("title", stat.title); addProperty("artist", stat.artist)
            addProperty("album", stat.album); addProperty("playCount", stat.playCount)
            addProperty("listenedMs", stat.listenedMs); addProperty("lastPlayedAt", stat.lastPlayedAt)
        }) } })
        add("history", JsonArray().apply { playback.history.forEach { entry -> add(JsonObject().apply {
            addProperty("songId", entry.songId); addProperty("title", entry.title); addProperty("artist", entry.artist)
            addProperty("album", entry.album); addProperty("playedAt", entry.playedAt)
        }) } })
        add("daily", JsonObject().apply { playback.daily.toSortedMap().forEach { (date, value) -> addProperty(date, value) } })
        if (playback.paths.isNotEmpty()) add("paths", JsonObject().apply { playback.paths.toSortedMap().forEach { (id, path) -> addProperty(id.toString(), path) } })
    }

    private fun counts(sections: JsonObject) = JsonObject().apply {
        sections.entrySet().forEach { (name, payload) -> addProperty(name, when (name) {
            BackupSection.PLAYLISTS.name -> array(objectValue(payload)["playlists"]).size()
            BackupSection.PLAYBACK.name -> objectValue(payload).let { array(it["stats"]).size() + array(it["history"]).size() + objectValue(it["daily"]).size() }
            BackupSection.PREFERENCES.name, BackupSection.DEVICE_REFERENCES.name -> array(payload).size()
            else -> throw IllegalArgumentException("Unknown backup section: $name")
        }) }
    }

    private fun digest(sections: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(sections).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun canonical(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply { value.asJsonObject.keySet().sorted().forEach { add(it, canonical(value.asJsonObject[it])) } }
        value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
        value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> JsonPrimitive(value.asBigDecimal.stripTrailingZeros())
        else -> value
    }

    private fun objectValue(value: JsonElement?): JsonObject {
        require(value != null && value.isJsonObject) { "Expected JSON object" }
        return value.asJsonObject
    }
    private fun array(value: JsonElement?): JsonArray {
        require(value != null && value.isJsonArray) { "Expected JSON array" }
        return value.asJsonArray
    }
    private fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected string" }
        return value.asString
    }
    private fun boolean(value: JsonElement?): Boolean {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Expected boolean" }
        return value.asBoolean
    }
    private fun integer(value: JsonElement?): Long = BackupStrictJson.integer(requireNotNull(value) { "Missing integer" })
    private fun int(value: JsonElement?): Int = integer(value).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer overflow" } }.toInt()
    private fun nonnegative(value: JsonElement?): Long = integer(value).also { require(it >= 0) { "Negative counter" } }
    private fun nonnegativeInt(value: JsonElement?): Int = int(value).also { require(it >= 0) { "Negative counter" } }
}
