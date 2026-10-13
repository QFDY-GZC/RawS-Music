package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class BackupRestoreReport(
    val preferenceCount: Int,
    val playlistsRestored: Boolean,
    val playbackRestored: Boolean,
    val skipped: List<String>,
    val warnings: List<String>,
    val requiresRestart: Boolean,
    val restoredMmkv: Int = 0,
    val restoredShared: Int = 0,
    val restoredReferences: Int = 0,
)

internal class BackupRestoreCoordinator(
    private val backend: BackupRestoreBackend,
    private val journal: BackupRecoveryJournal,
    private val dependencies: BackupDependencyResolver? = null,
) {
    suspend fun restore(backup: ValidatedBackup, selection: BackupSelection): BackupRestoreReport {
        // Also rejects a selection with no actual restorable content before touching persistence.
        BackupArchiveCodec.legacyPayload(backup, selection)
        return BackupWriteGate.withExclusive {
            if (journal.recoverPending(backend)) backend.publish()
            val writable = dependencies?.resolve(backup, selection)?.backup ?: backup
            BackupArchiveCodec.legacyPayload(writable, selection)
            val snapshot = backend.snapshot(writable, selection)
            currentCoroutineContext().ensureActive()
            journal.prepare(snapshot) // A failed/disk-full journal cannot leave partial data writes.
            try {
                backend.apply(writable, selection)
                backend.verify(writable, selection)
                currentCoroutineContext().ensureActive()
                // Once this atomic write succeeds, a restart must retain the verified new state.
                journal.commit()
            } catch (original: Throwable) {
                withContext(NonCancellable) {
                    try {
                        backend.rollback(snapshot)
                        backend.publish()
                        journal.clear()
                    } catch (rollbackFailure: Throwable) {
                        original.addSuppressed(rollbackFailure)
                        // Leave the journal intact: never claim that a failed rollback succeeded.
                    }
                }
                throw original
            }
            val warnings = mutableListOf<String>()
            withContext(NonCancellable) {
                try { backend.publish() }
                catch (_: Exception) { warnings += "数据已验证保存，内存刷新失败，请重启应用" }
                try { journal.clear() }
                catch (_: Exception) { warnings += "数据已提交，恢复记录将在下次启动时清理" }
            }
            val preferences = writable.preferences.filter {
                BackupPreferenceRegistry.classify(it.address)?.section in selection.sections
            }
            BackupRestoreReport(preferences.size,
                writable.playlists != null && BackupSection.PLAYLISTS in selection.sections,
                writable.playback != null && BackupSection.PLAYBACK in selection.sections,
                writable.skipped, warnings, preferences.isNotEmpty() || warnings.isNotEmpty(),
                preferences.count { it.address.store == "mmkv" }, preferences.count { it.address.store != "mmkv" },
                preferences.count { BackupPreferenceRegistry.classify(it.address)?.section == BackupSection.DEVICE_REFERENCES })
        }
    }
}
