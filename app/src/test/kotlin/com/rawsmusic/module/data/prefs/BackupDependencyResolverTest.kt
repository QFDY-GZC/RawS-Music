package com.rawsmusic.module.data.prefs

import org.junit.Assert.*
import org.junit.Test
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.toUserPlaylistSong

class BackupDependencyResolverTest {
    private val access = TestDependencies()
    private val resolver = BackupDependencyResolver(access)
    private fun entry(key: String, value: String, store: String = "mmkv") = PreferenceEntry(PreferenceAddress(store, key), PreferenceValue.StringValue(value))
    private fun backup(vararg entries: PreferenceEntry) = ValidatedBackup(3, 1, "test", BackupSection.entries.toSet(), entries.toList())
    private val all = BackupSelection(BackupSection.entries.toSet())

    @Test fun playlistRestoreUsesActualLocalSongNotArchivedDisplayData() {
        val song = AudioFile(id = 90, path = "/Music/a.flac", title = "Current title")
        access.files += song.path
        val group = UserPlaylist(id = "restored-0", name = "导入歌单 1", createdAt = 0, updatedAt = 0, songs = listOf(song.copy(id = 7, title = "Old title").toUserPlaylistSong()))
        val source = backup().copy(playlists = listOf(group))
        val result = BackupDependencyResolver(access) { listOf(song) }.resolve(source, all)
        assertEquals(90L, result.backup.playlists!!.single().songs.single().id)
        assertEquals("Current title", result.backup.playlists!!.single().songs.single().title)
    }

    @Test fun missingPlaylistMediaLeavesAnEmptyGroupWithoutPhantomSongs() {
        val song = AudioFile(id = 7, path = "/lost.flac", title = "Lost")
        val source = backup().copy(playlists = listOf(UserPlaylist(id = "restored-0", name = "导入歌单 1", createdAt = 0, updatedAt = 0, songs = listOf(song.toUserPlaylistSong()))))
        val result = resolver.resolve(source, all)
        assertTrue(result.backup.playlists!!.single().songs.isEmpty())
        assertTrue(result.skippedDependencies.any { it.contains("歌单") })
    }

    @Test fun unselectedPlaylistsDoNotReadLibraryOrFiles() {
        val source = backup().copy(playlists = emptyList())
        BackupDependencyResolver(access) { error("Library must not be read") }.resolve(source, BackupSelection(setOf(BackupSection.PREFERENCES)))
        assertEquals(0, access.reads)
    }

    @Test fun portableArchiveReinstallRefreshesIdsAndNewSystemRetainsOnlyDailyTotals() {
        val song = AudioFile(id = 7, path = "/Music/a.flac", title = "A", artist = "Artist", album = "Album")
        val playback = BackupPlayback(listOf(SongPlaybackStats(7, "A", "Artist", "Album", 5, 1000, 2)),
            listOf(PlaybackHistoryEntry(7, "A", "Artist", "Album", 2)), mapOf("2026-10-05" to 1000L))
        val source = backup().copy(playback = BackupPlaybackPolicy.forExport(playback, listOf(song)),
            playlists = listOf(UserPlaylist(id = "private-id", name = "private-name", createdAt = 0, updatedAt = 0, songs = listOf(song.toUserPlaylistSong()))))
        val decoded = BackupArchiveCodec.decode(BackupArchiveCodec.encode(source, all))
        access.files += song.path
        val restored = BackupDependencyResolver(access) { listOf(song.copy(id = 90)) }.resolve(decoded, all).backup
        assertEquals(90L, restored.playback!!.stats.single().songId)
        assertEquals(90L, restored.playlists!!.single().songs.single().id)
        assertNotEquals("private-name", restored.playlists!!.single().name)
        val degraded = resolver.resolve(decoded, all)
        assertTrue(degraded.backup.playback!!.stats.isEmpty())
        assertTrue(degraded.backup.playback!!.history.isEmpty())
        assertTrue(degraded.backup.playlists!!.single().songs.isEmpty())
        assertEquals(playback.daily, degraded.backup.playback!!.daily)
        assertTrue(degraded.skippedDependencies.any { it.contains("播放统计已降级") })
    }

    @Test fun unselectedDeviceReferencesAreNotEvenRead() {
        val result = resolver.resolve(backup(entry("ui_custom_font_path", "/missing.ttf")), BackupSelection())
        assertTrue(result.backup.preferences.isEmpty())
        assertEquals(0, access.reads)
    }

    @Test fun missingFontAndItsDisplayNameAreSkippedTogether() {
        val result = resolver.resolve(backup(entry("lyric_font_path", "/missing.ttf"), entry("lyric_font_name", "Old font")), all)
        assertTrue(result.backup.preferences.isEmpty())
        assertEquals(2, result.skippedDependencies.size)
    }

    @Test fun missingBackgroundCannotEnableOrReplaceExistingBackground() {
        val result = resolver.resolve(backup(entry("uri", "content://lost/image", "custom_media_background"),
            PreferenceEntry(PreferenceAddress("custom_media_background", "enabled"), PreferenceValue.BooleanValue(true))), all)
        assertTrue(result.backup.preferences.isEmpty())
        assertEquals(2, result.skippedDependencies.size)
    }

    @Test fun pluginOrderRetainsOnlyInstalledEntriesWithoutDisablingOthers() {
        access.plugins += "org.example.good"
        val result = resolver.resolve(backup(entry("preferred_source_order_v1", "org.example.lost\norg.example.good", "lyrico_sources"),
            PreferenceEntry(PreferenceAddress("lyrico_sources", "enabled:org.example.lost"), PreferenceValue.BooleanValue(false))), all)
        assertEquals(listOf(entry("preferred_source_order_v1", "org.example.good", "lyrico_sources")), result.backup.preferences)
        assertEquals(2, result.skippedDependencies.size)
    }

    @Test fun modelIdAndVersionRequireSameInstalledPair() {
        access.models += "voice" to "2"
        val result = resolver.resolve(backup(entry("selected_model_id", "voice", "ai_separation_models"),
            entry("selected_model_version", "1", "ai_separation_models")), all)
        assertTrue(result.backup.preferences.isEmpty())
        assertEquals(2, result.skippedDependencies.size)
    }

    @Test fun readableReferencesAndIntentionalUnsetAreRetained() {
        access.files += "/valid.ttf"
        val source = backup(entry("ui_custom_font_path", "/valid.ttf"), entry("lyric_font_path", ""))
        assertEquals(source.preferences, resolver.resolve(source, all).backup.preferences)
    }

    @Test fun scanPathsUseTheOwnersJsonArrayEncoding() {
        access.files += "/Music"
        val source = backup(entry("ui_scan_paths", "[\"/Music\"]"))
        assertEquals(source.preferences, resolver.resolve(source, all).backup.preferences)
    }

    @Test fun enablingConvolverWithoutReadableImpulseIsSkippedEvenWithoutReferenceSelection() {
        val source = backup(PreferenceEntry(PreferenceAddress("mmkv", "fft_convolver_enabled"), PreferenceValue.BooleanValue(true)))
        assertTrue(resolver.resolve(source, BackupSelection()).backup.preferences.isEmpty())
    }

    @Test fun videoCoverConfigurationIsNeverIncludedEvenWhenReferencesAreSelected() {
        access.files += "/video.mp4"
        val source = backup(entry("ui_video_cover_current_assignments", "{\"song\":\"/video.mp4\"}"),
            PreferenceEntry(PreferenceAddress("mmkv", "ui_video_cover_mode"), PreferenceValue.IntValue(1)),
            PreferenceEntry(PreferenceAddress("mmkv", "ui_video_cover_enabled"), PreferenceValue.BooleanValue(true)))
        assertTrue(resolver.resolve(source, all).backup.preferences.isEmpty())
    }
}

private class TestDependencies : BackupDependencyAccess {
    var reads = 0
    val files = mutableSetOf<String>()
    val plugins = mutableSetOf<String>()
    val models = mutableSetOf<Pair<String, String>>()
    override fun readable(reference: String): Boolean { reads++; return reference in files }
    override fun pluginInstalled(id: String) = id in plugins
    override fun modelInstalled(id: String, version: String) = id to version in models
    override fun instrumentInstalled(key: String) = false
    override fun current(address: PreferenceAddress): PreferenceValue? = null
}
