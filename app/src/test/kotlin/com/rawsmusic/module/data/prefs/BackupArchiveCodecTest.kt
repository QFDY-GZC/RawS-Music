package com.rawsmusic.module.data.prefs

import org.junit.Assert.*
import org.junit.Test

class BackupArchiveCodecTest {
    @Test fun newPlaylistExportOmitsNamesAndRebuildsAnonymousPathGroups() {
        val song = com.rawsmusic.core.common.model.UserPlaylistSong("path|/a.flac", 7, "PRIVATE_TRACK_NAME", "PRIVATE_ARTIST", "PRIVATE_ALBUM", 8, 1000,
            "/a.flac", 2048, "FLAC", 44100, 900000, 16, "/art.jpg", 1)
        val playlist = com.rawsmusic.core.common.model.UserPlaylist("private-list-id", "PRIVATE_PLAYLIST_NAME", listOf(song), 1, 2)
        val source = ValidatedBackup(3, 1, "test", setOf(BackupSection.PLAYLISTS), playlists = listOf(playlist))
        val bytes = BackupArchiveCodec.encode(source, BackupSelection())
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("PRIVATE_"))
        assertFalse(text.contains("private-list-id"))
        assertFalse(text.contains("/art.jpg"))
        val decoded = BackupArchiveCodec.decode(bytes).playlists!!.single()
        assertEquals("/a.flac", decoded.songs.single().path)
        assertTrue(decoded.name.isNotBlank())
        assertEquals("", decoded.songs.single().title)
    }
    @Test fun playbackKeepsOnlyMinimalPathReferencesForReinstall() {
        val playback = BackupPlayback(listOf(SongPlaybackStats(7, "A", "Artist", "Album", 5, 1000, 2)), emptyList(), emptyMap(), mapOf(7L to "/a.flac"))
        val original = ValidatedBackup(3, 1, "test", setOf(BackupSection.PLAYBACK), playback = playback)
        assertEquals(mapOf(7L to "/a.flac"), BackupArchiveCodec.decode(BackupArchiveCodec.encode(original, BackupSelection())).playback!!.paths)
    }
    private val codec = BackupArchiveCodec
    @Test fun legacyVersionsRequireActualDurableStructure() {
        val legacy = decode("""{"version":1,"timestamp":42,"playlists":{"playlists":[]}}""")
        assertEquals(1, legacy.version)
        assertEquals(emptyList<Any>(), legacy.playlists)
        assertTrue(BackupSection.PLAYLISTS in legacy.sections)
        assertEquals(1, decode("""{"playlists":{"playlists":[]}}""").version)
        rejected("{}")
        rejected("""{"version":999,"playlists":{"playlists":[]}}""")
        rejected("""{"version":2,"playback":{}}""")
        rejected("""{"version":2,"settings":{"mmkv":[],"sharedPreferences":{}}}""")
    }
    @Test fun versionTwoMigratesPreferencesAndSeparatesDeviceReferences() {
        val archive = decode(v2("""{"key":"hdr_window_enabled","type":"BOOLEAN","value":true},{"key":"ui_custom_font_path","type":"STRING","value":"/font.ttf"},{"key":"future_key","type":"INT","value":1}"""))
        assertEquals(2, archive.preferences.size)
        assertTrue(BackupSection.DEVICE_REFERENCES in archive.sections)
        assertEquals(1, archive.skipped.size)
        assertEquals(PreferenceValue.BooleanValue(true), archive.preferences.first().value)
    }
    @Test fun duplicateMembersAndPreferenceAddressesAreRejected() {
        rejected("""{"version":1,"version":2,"playlists":{"playlists":[]}}""")
        val entry = """{"key":"hdr_window_enabled","type":"BOOLEAN","value":true}"""
        rejected(v2("$entry,$entry"))
        rejected(v2("""{"key":"hdr_window_enabled","type":"BOOLEAN","value":true,"value":false}"""))
    }
    @Test fun valuesAreNeverSilentlyCoercedOrOverflowed() {
        rejected(v2("""{"key":"hdr_window_enabled","type":"BOOLEAN","value":"true"}"""))
        rejected(v2("""{"key":"ui_font_size_scale","type":"INT","value":100.5}"""))
        rejected(v2("""{"key":"ui_font_size_scale","type":"INT","value":4294967396}"""))
        rejected(v2("""{"key":"player_playback_speed","type":"FLOAT","value":1e400}"""))
        rejected(v2("""{"key":"peq_filters_json","type":"STRING","value":"[{}]"}"""))
        rejected(v2("""{"key":"hdr_window_scenes","type":"STRING_SET","value":["PLAYER","PLAYER"]}"""))
        rejected(v2("""{"key":"hdr_window_scenes","type":"STRING_SET","value":[1]}"""))
    }
    @Test fun explicitEmptyPlaybackIsNotTheSameAsMissingPlaybackStructure() {
        val archive = decode("""{"version":2,"playback":{"stats":[],"history":[],"daily":{}}}""")
        assertEquals(BackupPlayback(emptyList(), emptyList(), emptyMap()), archive.playback)
        rejected("""{"version":2,"playback":{"stats":[]}}""")
    }
    @Test fun v3RoundTripChecksContentAndRespectsSelectedSections() {
        val source = decode(v2("""{"key":"hdr_window_enabled","type":"BOOLEAN","value":true},{"key":"ui_custom_font_path","type":"STRING","value":"/font.ttf"}"""))
        val encoded = codec.encode(source, BackupSelection(setOf(BackupSection.PREFERENCES)))
        val restored = codec.decode(encoded)
        assertEquals(3, restored.version)
        assertEquals(1, restored.preferences.size)
        assertEquals(setOf(BackupSection.PREFERENCES), restored.sections)
        val changed = String(encoded, Charsets.UTF_8).replace("\"value\":true", "\"value\":false")
        assertNotEquals(String(encoded, Charsets.UTF_8), changed)
        rejected(changed)
    }
    @Test fun trailingContentAndInvalidUtf8AreRejected() {
        rejected("""{"version":1,"playlists":{"playlists":[]}} {}""")
        try { codec.decode(byteArrayOf(0xC3.toByte(), 0x28)); fail("Invalid UTF-8 accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun existingStoreAdapterOnlyReceivesSelectedValidatedValues() {
        val source = decode(v2("""{"key":"hdr_window_enabled","type":"BOOLEAN","value":true},{"key":"ui_custom_font_path","type":"STRING","value":"/font.ttf"}"""))
        val legacy = codec.legacyPayload(source, BackupSelection(setOf(BackupSection.PREFERENCES)))
        val restored = decode(legacy.toString())
        assertEquals(listOf(PreferenceAddress("mmkv", "hdr_window_enabled")), restored.preferences.map { it.address })
        assertFalse(legacy.toString().contains("/font.ttf"))
    }
    @Test fun exportRejectsOversizedContentRatherThanCreatingAnUnreadableBackup() {
        val source = ValidatedBackup(3, 1, "test", setOf(BackupSection.DEVICE_REFERENCES),
            preferences = listOf(PreferenceEntry(PreferenceAddress("mmkv", "ui_custom_font_path"),
                PreferenceValue.StringValue("x".repeat(33554433)))))
        try { codec.encode(source, BackupSelection(setOf(BackupSection.DEVICE_REFERENCES))); fail("Oversized export accepted") }
        catch (_: IllegalArgumentException) { }
    }
    private fun decode(json: String) = codec.decode(json.toByteArray(Charsets.UTF_8))
    private fun v2(entries: String) = """{"version":2,"timestamp":123,"settings":{"mmkv":[$entries],"sharedPreferences":{}}}"""
    private fun rejected(json: String) {
        try { decode(json); fail("Malformed archive accepted: $json") }
        catch (_: IllegalArgumentException) { }
    }
}
