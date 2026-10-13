package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.repository.MusicRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal object BackupAndroidRuntime {
    suspend fun prepare(context: Context): BackupRestoreCoordinator = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val playlists = PlaylistStore.getInstance(app)
        val playback = PlaybackStatsStore.getInstance(app)
        playlists.awaitBackupReady()
        playback.awaitBackupReady()
        val library = MusicRepository.getAllSongsUnsortedSuspend()
        val durable = AndroidBackupDurableAccess(MusicDatabase.getInstance(app)) {
            playlists.reloadAfterBackup()
            playback.reloadAfterBackup()
        }
        BackupRestoreCoordinator(PersistedBackupRestoreBackend(AndroidBackupPreferenceAccess(app), durable),
            BackupRecoveryJournal(BackupFileJournalStorage.forApplication(app)),
            BackupDependencyResolver(BackupAndroidDependencies(app)) { library })
    }

    /** Does no database I/O on the usual cold-start path with no pending recovery record. */
    fun recoverBeforeStoresInitialize(context: Context) {
        if (!File(context.filesDir, "backup_restore_journal.bin").exists()) return
        runBlocking(Dispatchers.IO) {
            BackupWriteGate.withExclusive {
                val durable = AndroidBackupDurableAccess(MusicDatabase.getInstance(context)) { }
                val backend = PersistedBackupRestoreBackend(AndroidBackupPreferenceAccess(context), durable)
                try {
                    BackupRecoveryJournal(BackupFileJournalStorage.forApplication(context)).recoverPending(backend)
                } catch (error: Throwable) {
                    // Fail closed before any store can publish partially restored data. Preserve the journal.
                    android.util.Log.e("BackupRecovery", "未完成的备份恢复回滚失败，保留恢复记录", error)
                    throw IllegalStateException("备份恢复尚未完成，无法安全初始化数据", error)
                }
            }
        }
    }
}
