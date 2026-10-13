package com.rawsmusic.module.data.prefs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local and cloud transfers share validation, dependency checks and journalled restoration. */
object BackupArchiveManager {
    const val CURRENT_VERSION = 3

    data class RestoreReport(
        val restoredMmkv: Int,
        val restoredSharedPreferences: Int,
        val skippedPreferences: Int,
        val playlistSectionRestored: Boolean,
        val playbackSectionRestored: Boolean,
        val playbackVerified: Boolean,
        val externalReferenceCount: Int,
        val skippedReasons: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val requiresRestart: Boolean = false,
    ) {
        // Returned only after every selected persisted section passed read-back verification.
        val verified: Boolean get() = !playbackSectionRestored || playbackVerified
    }

    private val sharedPreferenceFiles = listOf(
        "raws_locale_preferences", "ai_performance_ui", "ai_separation_runtime_options",
        "ai_separation_models", "home_card_layout_preferences", "home_header_preferences",
        "raw_flow_background", "mini_player_prefs", "lyrico_sources", "custom_media_background",
        "power_list_prefs", "audio_transcode_ui_options",
    )

    suspend fun exportJson(context: Context): JSONObject = JSONObject(exportBytes(context).toString(Charsets.UTF_8))
    suspend fun exportBytes(context: Context): ByteArray = exportSelected(context, BackupSelection())

    internal suspend fun exportSelected(context: Context, selection: BackupSelection): ByteArray =
        BackupOperationGate.withExclusive { exportSelectedUnderOperationGate(context, selection) }

    internal suspend fun exportSelectedUnderOperationGate(context: Context, selection: BackupSelection): ByteArray = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val playlists = PlaylistStore.getInstance(app)
        val playback = PlaybackStatsStore.getInstance(app)
        // Await initialization before taking the non-reentrant gate used by both stores.
        playlists.awaitBackupReady()
        playback.awaitBackupReady()
        val library = if (BackupSection.PLAYBACK in selection.sections)
            com.rawsmusic.module.data.repository.MusicRepository.getAllSongsUnsortedSuspend() else emptyList()
        BackupWriteGate.withExclusive {
            val root = JSONObject().put("version", 2).put("timestamp", System.currentTimeMillis())
                .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty())
            if (BackupSection.PREFERENCES in selection.sections || BackupSection.DEVICE_REFERENCES in selection.sections) {
                root.put("settings", exportSettings(app, selection))
            }
            if (BackupSection.PLAYLISTS in selection.sections) root.put("playlists", playlists.exportJsonUnderBackupGate())
            if (BackupSection.PLAYBACK in selection.sections) root.put("playback", playback.exportJsonUnderBackupGate())
            val decoded = BackupArchiveCodec.decode(root.toString().toByteArray(Charsets.UTF_8))
            val export = decoded.copy(playback = decoded.playback?.let { BackupPlaybackPolicy.forExport(it, library) })
            BackupArchiveCodec.encode(export, selection)
        }
    }

    suspend fun restoreBytes(context: Context, bytes: ByteArray): RestoreReport =
        restoreSelected(context, BackupArchiveCodec.decode(bytes), BackupSelection())

    suspend fun restoreJson(context: Context, root: JSONObject): RestoreReport =
        restoreBytes(context, root.toString().toByteArray(Charsets.UTF_8))

    internal suspend fun restoreSelected(context: Context, backup: ValidatedBackup, selection: BackupSelection): RestoreReport {
        val result = BackupOperationGate.withExclusive { BackupAndroidRuntime.prepare(context).restore(backup, selection) }
        return RestoreReport(result.restoredMmkv, result.restoredShared, result.skipped.size,
            result.playlistsRestored, result.playbackRestored, result.playbackRestored,
            result.restoredReferences, result.skipped, result.warnings, result.requiresRestart)
    }

    internal suspend fun resolve(context: Context, backup: ValidatedBackup, selection: BackupSelection): ResolvedBackup = withContext(Dispatchers.IO) {
        val library = com.rawsmusic.module.data.repository.MusicRepository.getAllSongsUnsortedSuspend()
        BackupDependencyResolver(BackupAndroidDependencies(context.applicationContext)) { library }.resolve(backup, selection)
    }

    private fun exportSettings(context: Context, selection: BackupSelection): JSONObject {
        val access = AndroidBackupPreferenceAccess(context)
        fun entry(address: PreferenceAddress): JSONObject? {
            val rule = BackupPreferenceRegistry.classify(address) ?: return null
            if (rule.section !in selection.sections) return null
            val value = access.read(address, rule.type) ?: return null
            // Codec normalizes historical PEQ and validates nested settings before encoding.
            val encoded = when (value) {
                is PreferenceValue.BooleanValue -> value.value
                is PreferenceValue.IntValue -> value.value
                is PreferenceValue.LongValue -> value.value
                is PreferenceValue.FloatValue -> value.value.toDouble()
                is PreferenceValue.DoubleValue -> value.value
                is PreferenceValue.StringValue -> value.value
                is PreferenceValue.StringSetValue -> JSONArray(value.value.sorted())
            }
            return JSONObject().put("key", address.key).put("type", value.type.name).put("value", encoded)
        }
        val mmkv = JSONArray().apply { BackupPreferenceStorage.allKeys().sorted().forEach { key -> entry(PreferenceAddress("mmkv", key))?.let(::put) } }
        val shared = JSONObject().apply {
            sharedPreferenceFiles.forEach { store ->
                val entries = JSONArray().apply { context.getSharedPreferences(store, Context.MODE_PRIVATE).all.keys.sorted().forEach { key -> entry(PreferenceAddress(store, key))?.let(::put) } }
                if (entries.length() > 0) put(store, entries)
            }
        }
        return JSONObject().put("mmkv", mmkv).put("sharedPreferences", shared)
    }
}
