package com.rawsmusic.module.data.prefs

import com.rawsmusic.core.common.model.toUserPlaylistSong

internal data class ResolvedBackup(val backup: ValidatedBackup, val skippedDependencies: List<String>)

internal interface BackupDependencyAccess {
    fun readable(reference: String): Boolean
    fun pluginInstalled(id: String): Boolean
    fun modelInstalled(id: String, version: String): Boolean
    fun instrumentInstalled(key: String): Boolean
    fun current(address: PreferenceAddress): PreferenceValue?
}

internal class BackupDependencyResolver(
    private val access: BackupDependencyAccess,
    private val library: () -> List<com.rawsmusic.core.common.model.AudioFile> = { emptyList() },
) {
    fun resolve(backup: ValidatedBackup, selection: BackupSelection): ResolvedBackup {
        val selected = backup.preferences.filter { BackupPreferenceRegistry.classify(it.address)?.section in selection.sections }
        val incoming = selected.associateBy { it.address }
        val skipped = mutableListOf<String>()
        val localSongs by lazy(library)
        fun text(store: String, key: String): String = ((incoming[PreferenceAddress(store, key)]?.value
            ?: access.current(PreferenceAddress(store, key))) as? PreferenceValue.StringValue)?.value.orEmpty()
        fun readable(value: String) = value.isBlank() || access.readable(value)
        fun references(entry: PreferenceEntry): Boolean {
            val value = (entry.value as? PreferenceValue.StringValue)?.value.orEmpty()
            return when (entry.address.key) {
                "lyric_font_name" -> readable(text("mmkv", "lyric_font_path"))
                "fft_convolver_ir_name" -> readable(text("mmkv", "fft_convolver_ir_uri"))
                "scanner_music_folder_uris" -> value.lineSequence().filter { it.isNotBlank() }.all(::readable)
                "ui_root_scan_paths", "ui_scan_paths" -> value.isBlank() || BackupStrictJson.parse(value).asJsonArray.all { readable(it.asString) }
                "ui_video_cover_current_assignments" -> if (value.isBlank()) true else BackupStrictJson.parse(value).asJsonObject.entrySet().all { readable(it.value.asString) }
                else -> readable(value)
            }
        }
        val accepted = selected.mapNotNull { entry ->
            val address = entry.address
            val rule = requireNotNull(BackupPreferenceRegistry.classify(address))
            val allowed = when {
                address.store == "custom_media_background" -> readable(text(address.store, "uri"))
                rule.dependency == PreferenceDependency.FILE_OR_URI -> references(entry)
                rule.dependency == PreferenceDependency.INSTALLED_PLUGIN -> {
                    if (address.key == "preferred_source_order_v1") {
                        val ids = (entry.value as PreferenceValue.StringValue).value.lineSequence().filter { it.isNotBlank() }.distinct().toList()
                        val installed = ids.filter(access::pluginInstalled)
                        (ids - installed.toSet()).forEach { skipped += "歌词插件未安装：$it" }
                        return@mapNotNull if (ids.isEmpty() || installed.isNotEmpty()) entry.copy(value = PreferenceValue.StringValue(installed.joinToString("\n"))) else null
                    }
                    access.pluginInstalled(address.key.removePrefix("enabled:"))
                }
                rule.dependency == PreferenceDependency.INSTALLED_MODEL -> {
                    if (address.store == "ai_performance_ui") {
                        val key = (entry.value as PreferenceValue.StringValue).value
                        key.isBlank() || access.instrumentInstalled(key)
                    } else {
                        val base = address.key.removeSuffix("_id").removeSuffix("_version")
                        val id = text(address.store, base + "_id")
                        val version = text(address.store, base + "_version")
                        (id.isBlank() && version.isBlank()) || (id.isNotBlank() && version.isNotBlank() && access.modelInstalled(id, version))
                    }
                }
                address.store == "mmkv" && address.key == "fft_convolver_enabled" && (entry.value as PreferenceValue.BooleanValue).value -> text("mmkv", "fft_convolver_ir_uri").let { it.isNotBlank() && access.readable(it) }
                address.store == "mmkv" && address.key == "ui_video_cover_enabled" && (entry.value as PreferenceValue.BooleanValue).value -> {
                    val mode = ((incoming[PreferenceAddress("mmkv", "ui_video_cover_mode")]?.value
                        ?: access.current(PreferenceAddress("mmkv", "ui_video_cover_mode"))) as? PreferenceValue.IntValue)?.value ?: 0
                    when (mode) {
                        0 -> text("mmkv", "ui_video_cover_permanent_uri").let { it.isNotBlank() && access.readable(it) }
                        1 -> text("mmkv", "ui_video_cover_current_assignments").let { encoded ->
                            encoded.isNotBlank() && BackupStrictJson.parse(encoded).asJsonObject.entrySet().let { rows ->
                                rows.isNotEmpty() && rows.all { it.value.asString.isNotBlank() && access.readable(it.value.asString) }
                            }
                        }
                        else -> false // Process-only temporary covers cannot survive restoration.
                    }
                }
                else -> true
            }
            if (allowed) entry else { skipped += "依赖不可用，保留原设置：${address.store}/${address.key}"; null }
        }
        val playback = backup.playback?.let { original ->
            if (BackupSection.PLAYBACK !in selection.sections) original else {
                val result = BackupPlaybackPolicy.resolve(original, localSongs, access::readable)
                if (result.missingStats > 0 || result.missingHistory > 0) skipped += "播放统计已降级：${result.missingStats} 条逐曲统计、${result.missingHistory} 条历史未关联到可用本地歌曲；每日时长保留"
                result.playback
            }
        }
        val playlists = backup.playlists?.let { groups ->
            if (BackupSection.PLAYLISTS !in selection.sections) groups else {
                val byPath = localSongs.groupBy { it.path }
                val readablePaths = mutableMapOf<String, Boolean>()
                var missing = 0
                groups.map { group ->
                    group.copy(songs = group.songs.mapNotNull { archived ->
                        val actual = byPath[archived.path]?.singleOrNull()?.takeIf {
                            it.path.isNotBlank() && readablePaths.getOrPut(it.path) { access.readable(it.path) }
                        }
                        if (actual == null) { missing++; null }
                        else actual.toUserPlaylistSong().copy(addedAt = archived.addedAt)
                    })
                }.also {
                    if (missing > 0) skipped += "歌单已降级：$missing 首歌曲未关联到可用本地文件；保留空歌单，不显示缺失歌曲"
                }
            }
        }
        return ResolvedBackup(backup.copy(preferences = accepted, playlists = playlists, playback = playback, skipped = backup.skipped + skipped), skipped)
    }
}
