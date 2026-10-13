package com.rawsmusic.ui.settings

import com.rawsmusic.module.data.prefs.*
import org.junit.Assert.*
import org.junit.Test

class BackupUiStateTest {
    private val archive = ValidatedBackup(3, 1, "test", setOf(BackupSection.PLAYBACK),
        playback = BackupPlayback(emptyList(), emptyList(), emptyMap()))
    @Test fun loadingAndPreviewNeverAuthorizeWritesWithoutConfirmation() {
        val ui = BackupUiController()
        assertTrue(ui.begin(BackupPhase.LOADING))
        assertFalse(ui.begin(BackupPhase.EXPORTING))
        ui.loaded(archive)
        assertEquals(BackupPhase.PREVIEW, ui.state.value.phase)
        assertNull(ui.confirm()) // Explicitly empty data must not be preselected.
        ui.select(BackupSection.PLAYBACK, true)
        assertNotNull(ui.confirm())
        assertEquals(BackupPhase.WRITING, ui.state.value.phase)
        assertFalse(ui.cancel())
        ui.finish()
        assertTrue(ui.begin(BackupPhase.EXPORTING))
    }
    @Test fun cancelPickerAndPreviewReleaseOperation() {
        val ui = BackupUiController()
        ui.begin(BackupPhase.PICKING)
        assertTrue(ui.cancel())
        ui.begin(BackupPhase.LOADING)
        ui.loaded(archive)
        assertTrue(ui.cancel())
        assertEquals(BackupPhase.IDLE, ui.state.value.phase)
    }
    @Test fun resourcesDefaultOffAndUnselectedGroupsCannotBeConfirmed() {
        val source = archive.copy(sections = BackupSection.entries.toSet(), preferences = listOf(
            PreferenceEntry(PreferenceAddress("mmkv", "ui_custom_font_path"), PreferenceValue.StringValue("/font"))))
        val ui = BackupUiController()
        ui.begin(BackupPhase.LOADING)
        ui.loaded(source)
        assertFalse(BackupSection.DEVICE_REFERENCES in ui.state.value.selection.sections)
        assertNull(ui.confirm())
    }
}
