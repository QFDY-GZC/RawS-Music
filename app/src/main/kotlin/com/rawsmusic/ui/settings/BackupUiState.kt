package com.rawsmusic.ui.settings

import com.rawsmusic.module.data.prefs.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class BackupPhase { IDLE, PICKING, LOADING, PREVIEW, EXPORTING, WRITING }
internal data class BackupUiState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val archive: ValidatedBackup? = null,
    val selection: BackupSelection = BackupSelection(emptySet()),
) {
    val busy: Boolean get() = phase != BackupPhase.IDLE
}
internal data class BackupRestoreRequest(val archive: ValidatedBackup, val selection: BackupSelection)

/** One operation owns the screen, including the document picker and confirmation stage. */
internal class BackupUiController {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    fun begin(phase: BackupPhase): Boolean {
        require(phase in setOf(BackupPhase.PICKING, BackupPhase.LOADING, BackupPhase.EXPORTING))
        return mutable.compareAndSet(BackupUiState(), BackupUiState(phase))
    }
    fun loaded(archive: ValidatedBackup) {
        check(mutable.value.phase in setOf(BackupPhase.PICKING, BackupPhase.LOADING))
        val nonempty = archive.sections.filter { section -> when (section) {
            BackupSection.PREFERENCES -> archive.preferences.any { BackupPreferenceRegistry.classify(it.address)?.section == section }
            BackupSection.PLAYLISTS -> archive.playlists?.isNotEmpty() == true
            BackupSection.PLAYBACK -> archive.playback?.let { it.stats.isNotEmpty() || it.history.isNotEmpty() || it.daily.isNotEmpty() } == true
            BackupSection.DEVICE_REFERENCES -> false
        } }.toSet()
        mutable.value = BackupUiState(BackupPhase.PREVIEW, archive, BackupSelection(nonempty))
    }
    fun select(section: BackupSection, enabled: Boolean) {
        val current = mutable.value
        if (current.phase != BackupPhase.PREVIEW || section !in current.archive!!.sections) return
        mutable.value = current.copy(selection = BackupSelection(if (enabled) current.selection.sections + section else current.selection.sections - section))
    }
    fun confirm(): BackupRestoreRequest? {
        val current = mutable.value
        if (current.phase != BackupPhase.PREVIEW || current.selection.sections.isEmpty()) return null
        val archive = current.archive ?: return null
        try { BackupArchiveCodec.legacyPayload(archive, current.selection) } catch (_: IllegalArgumentException) { return null }
        mutable.value = current.copy(phase = BackupPhase.WRITING)
        return BackupRestoreRequest(archive, current.selection)
    }
    fun cancel(): Boolean {
        if (mutable.value.phase == BackupPhase.WRITING) return false
        mutable.value = BackupUiState()
        return true
    }
    fun finish() { mutable.value = BackupUiState() }
}
