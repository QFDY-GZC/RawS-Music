package com.rawsmusic.module.data.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical backup codec shared by local-file and WebDAV import/export.
 *
 * The archive intentionally stores user configuration and durable user data, not generated caches,
 * in-flight runtime state, learned device-health state, or credentials. External file/URI settings
 * are preserved as references only; the referenced file itself is not embedded in JSON.
 */
object BackupArchiveManager {
    const val CURRENT_VERSION = 2
    private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    data class RestoreReport(
        val restoredMmkv: Int,
        val restoredSharedPreferences: Int,
        val skippedPreferences: Int,
        val playlistSectionRestored: Boolean,
        val playbackSectionRestored: Boolean,
        val playbackVerified: Boolean,
        val externalReferenceCount: Int,
    ) {
        val verified: Boolean
            get() = playbackSectionRestored == playbackVerified


    }

    private val sharedPreferenceFiles = listOf(
        "raws_locale_preferences",
        "ai_performance_ui",
        "ai_separation_runtime_options",
        "ai_separation_models",
        "home_card_layout_preferences",
        "home_header_preferences",
        "raw_flow_background",
        "mini_player_prefs",
        "lyrico_sources",
        "custom_media_background",
        "power_list_prefs",
    )

    private val externalReferenceKeys = setOf(
        "fft_convolver_ir_uri",
        "lyric_font_path",
        "scanner_folder_dialog_uri_by_path",
        "scanner_music_folder_uris",
        "ui_custom_font_path",
        "ui_root_scan_paths",
        "ui_scan_paths",
        "ui_video_cover_current_assignments",
        "ui_video_cover_permanent_uri",
    )

    private val sharedPreferenceExternalReferenceKeys = mapOf(
        "custom_media_background" to setOf("uri"),
    )

    private val sensitiveKeyTokens = listOf(
        "password",
        "passwd",
        "secret",
        "access_token",
        "refresh_token",
        "api_key",
        "apikey",
        "credential",
        "cookie",
    )

    suspend fun exportJson(context: Context): JSONObject = JSONObject().apply {
        put("version", CURRENT_VERSION)
        put("timestamp", System.currentTimeMillis())
        put("settings", exportSettings(context.applicationContext))
        put("playlists", PlaylistStore.getInstance(context).exportJsonForBackup())
        put("playback", PlaybackStatsStore.getInstance(context).exportJsonForBackup())
    }

    suspend fun exportBytes(context: Context): ByteArray =
        exportJson(context).toString(2).toByteArray(Charsets.UTF_8)

    suspend fun restoreBytes(context: Context, bytes: ByteArray): RestoreReport {
        require(bytes.isNotEmpty()) { "备份文件为空" }
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份文件过大" }
        return restoreJson(context, JSONObject(String(bytes, Charsets.UTF_8)))
    }

    /**
     * Cross-store import cannot be one transaction. Snapshot the current portable state first; if
     * any Room or preference read-back fails, restore that snapshot before surfacing the error.
     */
    suspend fun restoreJson(context: Context, root: JSONObject): RestoreReport {
        validateVersion(root)
        val appContext = context.applicationContext
        val rollbackSnapshot = exportJson(appContext)
        return try {
            applyRestoreJson(appContext, root)
        } catch (original: Throwable) {
            runCatching { applyRestoreJson(appContext, rollbackSnapshot) }
                .onFailure(original::addSuppressed)
            throw original
        }
    }

    private fun validateVersion(root: JSONObject): Int {
        val version = root.optInt("version", 1)
        require(version in 1..CURRENT_VERSION) { "不支持的备份版本：$version" }
        return version
    }

    private suspend fun applyRestoreJson(context: Context, root: JSONObject): RestoreReport {
        val version = validateVersion(root)

        var restoredMmkv = 0
        var restoredShared = 0
        var skipped = 0
        var externalReferences = 0

        var playlistsRestored = false
        root.optJSONObject("playlists")?.let { playlists ->
            playlistsRestored = PlaylistStore.getInstance(context).restoreJsonAndVerify(playlists)
            check(playlistsRestored) { "歌单写入后校验失败" }
        }

        var playbackRestored = false
        var playbackVerified = false
        root.optJSONObject("playback")?.let { playback ->
            playbackRestored = true
            playbackVerified = PlaybackStatsStore.getInstance(context).restoreJsonAndVerify(playback)
            check(playbackVerified) { "播放统计写入后校验失败" }
        }

        // Preference stores are applied last. If one fails verification, restoreJson() rolls the
        // already-verified durable sections back to the pre-import snapshot.
        if (version >= 2) {
            root.optJSONObject("settings")?.let { settings ->
                val mmkvResult = restoreMmkv(settings.optJSONArray("mmkv") ?: JSONArray())
                restoredMmkv += mmkvResult.restored
                skipped += mmkvResult.skipped
                externalReferences += mmkvResult.externalReferences

                val sharedResult = restoreSharedPreferences(
                    context,
                    settings.optJSONObject("sharedPreferences") ?: JSONObject(),
                )
                restoredShared += sharedResult.restored
                skipped += sharedResult.skipped
                externalReferences += sharedResult.externalReferences
            }
        }

        return RestoreReport(
            restoredMmkv = restoredMmkv,
            restoredSharedPreferences = restoredShared,
            skippedPreferences = skipped,
            playlistSectionRestored = playlistsRestored,
            playbackSectionRestored = playbackRestored,
            playbackVerified = playbackVerified,
            externalReferenceCount = externalReferences,
        )
    }

    private fun exportSettings(context: Context): JSONObject = JSONObject().apply {
        put("mmkv", exportMmkv())
        put("sharedPreferences", exportSharedPreferences(context))
        put("note", "External files are referenced by URI/path and are not embedded in this JSON archive.")
    }

    private fun exportMmkv(): JSONArray {
        val storage = BackupPreferenceStorage
        val out = JSONArray()
        val keys = storage.allKeys().sorted()
        for (key in keys) {
            if (isSensitiveKey(key)) continue
            val type = BackupPreferenceSchema.typeForBackup(key) ?: continue
            if (!storage.contains(key)) continue
            val entry = JSONObject()
                .put("key", key)
                .put("type", type.name)
            when (type) {
                BackupPreferenceSchema.ValueType.BOOLEAN -> entry.put("value", storage.readBoolean(key, false))
                BackupPreferenceSchema.ValueType.INT -> entry.put("value", storage.readInt(key, 0))
                BackupPreferenceSchema.ValueType.LONG -> entry.put("value", storage.readLong(key, 0L))
                BackupPreferenceSchema.ValueType.FLOAT -> entry.put("value", storage.readFloat(key, 0f).toDouble())
                BackupPreferenceSchema.ValueType.DOUBLE -> entry.put("value", storage.readDouble(key, 0.0))
                BackupPreferenceSchema.ValueType.STRING -> entry.put("value", storage.readString(key, ""))
                BackupPreferenceSchema.ValueType.STRING_SET -> entry.put(
                    "value",
                    JSONArray(storage.readStringSet(key).sorted()),
                )
            }
            out.put(entry)
        }
        return out
    }

    private fun exportSharedPreferences(context: Context): JSONObject = JSONObject().apply {
        for (name in sharedPreferenceFiles) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = JSONArray()
            prefs.all.toSortedMap().forEach { (key, value) ->
                if (isSensitiveKey(key)) return@forEach
                sharedPreferenceEntry(key, value)?.let(entries::put)
            }
            if (entries.length() > 0) put(name, entries)
        }
    }

    private fun sharedPreferenceEntry(key: String, value: Any?): JSONObject? {
        val entry = JSONObject().put("key", key)
        when (value) {
            is Boolean -> entry.put("type", "BOOLEAN").put("value", value)
            is Int -> entry.put("type", "INT").put("value", value)
            is Long -> entry.put("type", "LONG").put("value", value)
            is Float -> entry.put("type", "FLOAT").put("value", value.toDouble())
            is String -> entry.put("type", "STRING").put("value", value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>()
                if (strings.size != value.size) return null
                entry.put("type", "STRING_SET").put("value", JSONArray(strings.sorted()))
            }
            else -> return null
        }
        return entry
    }

    private data class RestoreCounters(
        val restored: Int,
        val skipped: Int,
        val externalReferences: Int,
    )

    private fun restoreMmkv(entries: JSONArray): RestoreCounters {
        val storage = BackupPreferenceStorage
        val admitted = ArrayList<Pair<String, BackupPreferenceSchema.ValueType>>()
        var skipped = 0
        var externalReferences = 0

        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index)
            if (entry == null) {
                skipped++
                continue
            }
            val key = entry.optString("key")
            if (key.isBlank() || isSensitiveKey(key)) {
                skipped++
                continue
            }
            val expectedType = BackupPreferenceSchema.typeForBackup(key)
            val encodedType = runCatching {
                BackupPreferenceSchema.ValueType.valueOf(entry.optString("type"))
            }.getOrNull()
            if (expectedType == null || encodedType != expectedType || !entry.has("value")) {
                skipped++
                continue
            }
            val written = when (expectedType) {
                BackupPreferenceSchema.ValueType.BOOLEAN -> storage.writeBoolean(key, entry.optBoolean("value"))
                BackupPreferenceSchema.ValueType.INT -> storage.writeInt(key, entry.optInt("value"))
                BackupPreferenceSchema.ValueType.LONG -> storage.writeLong(key, entry.optLong("value"))
                BackupPreferenceSchema.ValueType.FLOAT -> storage.writeFloat(key, entry.optDouble("value").toFloat())
                BackupPreferenceSchema.ValueType.DOUBLE -> storage.writeDouble(key, entry.optDouble("value"))
                BackupPreferenceSchema.ValueType.STRING -> storage.writeString(key, entry.optString("value"))
                BackupPreferenceSchema.ValueType.STRING_SET -> storage.writeStringSet(
                    key,
                    entry.optJSONArray("value").toStringSet(),
                )
            }
            if (written) {
                admitted += key to expectedType
                if (key in externalReferenceKeys && entry.optString("value").isNotBlank()) {
                    externalReferences++
                }
            } else {
                skipped++
            }
        }

        BackupPreferenceStorage.sync()
        admitted.forEach { (key, type) ->
            val source = findEntry(entries, key) ?: error("备份校验条目丢失：$key")
            check(mmkvValueMatches(key, type, source)) { "设置写入后校验失败：$key" }
        }
        return RestoreCounters(admitted.size, skipped, externalReferences)
    }

    private fun restoreSharedPreferences(context: Context, root: JSONObject): RestoreCounters {
        var restored = 0
        var skipped = 0
        var externalReferences = 0
        for (name in sharedPreferenceFiles) {
            val entries = root.optJSONArray(name) ?: continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val admitted = ArrayList<JSONObject>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index)
                if (entry == null) {
                    skipped++
                    continue
                }
                val key = entry.optString("key")
                if (key.isBlank() || isSensitiveKey(key) || !entry.has("value")) {
                    skipped++
                    continue
                }
                val applied = when (entry.optString("type")) {
                    "BOOLEAN" -> { editor.putBoolean(key, entry.optBoolean("value")); true }
                    "INT" -> { editor.putInt(key, entry.optInt("value")); true }
                    "LONG" -> { editor.putLong(key, entry.optLong("value")); true }
                    "FLOAT" -> { editor.putFloat(key, entry.optDouble("value").toFloat()); true }
                    "STRING" -> { editor.putString(key, entry.optString("value")); true }
                    "STRING_SET" -> { editor.putStringSet(key, entry.optJSONArray("value").toStringSet()); true }
                    else -> false
                }
                if (applied) {
                    admitted += entry
                    if (
                        key in sharedPreferenceExternalReferenceKeys[name].orEmpty() &&
                        entry.optString("value").isNotBlank()
                    ) {
                        externalReferences++
                    }
                } else skipped++
            }
            check(editor.commit()) { "SharedPreferences 写入失败：$name" }
            for (entry in admitted) {
                check(sharedPreferenceValueMatches(prefs, entry)) {
                    "SharedPreferences 写入后校验失败：$name/${entry.optString("key")}"
                }
            }
            restored += admitted.size
        }
        return RestoreCounters(restored, skipped, externalReferences)
    }

    private fun mmkvValueMatches(
        key: String,
        type: BackupPreferenceSchema.ValueType,
        source: JSONObject,
    ): Boolean {
        val storage = BackupPreferenceStorage
        return when (type) {
            BackupPreferenceSchema.ValueType.BOOLEAN -> storage.readBoolean(key, false) == source.optBoolean("value")
            BackupPreferenceSchema.ValueType.INT -> storage.readInt(key, 0) == source.optInt("value")
            BackupPreferenceSchema.ValueType.LONG -> storage.readLong(key, 0L) == source.optLong("value")
            BackupPreferenceSchema.ValueType.FLOAT ->
                storage.readFloat(key, 0f).toBits() == source.optDouble("value").toFloat().toBits()
            BackupPreferenceSchema.ValueType.DOUBLE ->
                storage.readDouble(key, 0.0).toBits() == source.optDouble("value").toBits()
            BackupPreferenceSchema.ValueType.STRING -> storage.readString(key, "") == source.optString("value")
            BackupPreferenceSchema.ValueType.STRING_SET ->
                storage.readStringSet(key) == source.optJSONArray("value").toStringSet()
        }
    }

    private fun sharedPreferenceValueMatches(prefs: SharedPreferences, entry: JSONObject): Boolean {
        val key = entry.optString("key")
        return when (entry.optString("type")) {
            "BOOLEAN" -> prefs.getBoolean(key, !entry.optBoolean("value")) == entry.optBoolean("value")
            "INT" -> prefs.getInt(key, Int.MIN_VALUE) == entry.optInt("value")
            "LONG" -> prefs.getLong(key, Long.MIN_VALUE) == entry.optLong("value")
            "FLOAT" -> prefs.getFloat(key, Float.NaN).toBits() == entry.optDouble("value").toFloat().toBits()
            "STRING" -> prefs.getString(key, null) == entry.optString("value")
            "STRING_SET" -> prefs.getStringSet(key, null).orEmpty() == entry.optJSONArray("value").toStringSet()
            else -> false
        }
    }

    private fun findEntry(entries: JSONArray, key: String): JSONObject? {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("key") == key) return entry
        }
        return null
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return key in BackupPreferenceSchema.sensitiveKeys ||
            sensitiveKeyTokens.any(normalized::contains)
    }
}
