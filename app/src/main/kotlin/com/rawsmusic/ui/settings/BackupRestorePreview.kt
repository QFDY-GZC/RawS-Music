package com.rawsmusic.ui.settings

import com.rawsmusic.module.data.prefs.*

internal data class BackupPreview(val newPreferences: Int, val overwrittenPreferences: Int,
    val playlists: Int?, val songs: Int?, val stats: Int?, val history: Int?, val daily: Int?,
    val clearsPlaylists: Boolean, val clearsPlayback: Boolean, val skipped: List<String>)

internal object BackupRestorePreview {
    fun build(resolved: ResolvedBackup, selection: BackupSelection, existing: Set<PreferenceAddress>): BackupPreview {
        val backup = resolved.backup
        val prefs = backup.preferences.filter { BackupPreferenceRegistry.classify(it.address)?.section in selection.sections }
        val lists = backup.playlists?.takeIf { BackupSection.PLAYLISTS in selection.sections }
        val playback = backup.playback?.takeIf { BackupSection.PLAYBACK in selection.sections }
        return BackupPreview(prefs.count { it.address !in existing }, prefs.count { it.address in existing },
            lists?.size, lists?.sumOf { it.songs.size }, playback?.stats?.size, playback?.history?.size, playback?.daily?.size,
            lists?.isEmpty() == true, playback?.let { it.stats.isEmpty() && it.history.isEmpty() && it.daily.isEmpty() } == true,
            backup.skipped)
    }
}
