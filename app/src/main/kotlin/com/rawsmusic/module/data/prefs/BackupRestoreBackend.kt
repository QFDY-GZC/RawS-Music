package com.rawsmusic.module.data.prefs

/** Persistence boundary. Snapshot must include absence, original types and selected Room rows. */
internal interface BackupRestoreBackend {
    suspend fun snapshot(backup: ValidatedBackup, selection: BackupSelection): ByteArray
    suspend fun apply(backup: ValidatedBackup, selection: BackupSelection)
    suspend fun verify(backup: ValidatedBackup, selection: BackupSelection)
    suspend fun rollback(snapshot: ByteArray)
    suspend fun publish()
}

internal interface BackupJournalStorage {
    fun read(): ByteArray?
    /** Either the previous complete record or the new complete record survives a failed write. */
    fun writeAtomically(bytes: ByteArray)
    fun delete()
}
