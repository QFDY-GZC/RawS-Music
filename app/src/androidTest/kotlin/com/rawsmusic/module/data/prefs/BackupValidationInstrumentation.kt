package com.rawsmusic.module.data.prefs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.room.Room
import com.rawsmusic.module.data.db.MusicDatabase
import java.io.File
import kotlinx.coroutines.runBlocking

/** Test APK only: never uses the release package or its library database. */
class BackupValidationInstrumentation : Instrumentation() {
    private var phase = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase").orEmpty(); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName == "com.rawsmusic.debug") { "Isolated debug target required" }
            runBlocking { validate() }
            result.putString("stream", "Backup Android validation PASS phase=$phase\n")
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", "Backup Android validation FAIL phase=$phase ${error.stackTraceToString()}\n")
            finish(0, result)
        }
    }
    private suspend fun validate() {
        val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("backup_validation_$name", mode)
        }
        val database = Room.databaseBuilder(context, MusicDatabase::class.java, "backup-validation.db").build()
        val preference = AndroidBackupPreferenceAccess(context)
        val durable = AndroidBackupDurableAccess(database) { }
        val backend = PersistedBackupRestoreBackend(preference, durable)
        val directory = File(targetContext.filesDir, "backup-validation").apply { mkdirs() }
        val file = File(directory, "journal.bin")
        val storage = BackupFileJournalStorage(file) { from, to -> android.system.Os.rename(from.absolutePath, to.absolutePath) }
        val journal = BackupRecoveryJournal(storage)
        val selection = BackupSelection(setOf(BackupSection.PREFERENCES, BackupSection.PLAYBACK))
        val signature = PreferenceAddress("mmkv", "lyrics_export_signature")
        val shared = PreferenceAddress("raw_flow_background", "motion_speed")
        val data = ValidatedBackup(3, 1, "validation", selection.sections,
            listOf(PreferenceEntry(signature, PreferenceValue.StringValue("validation changed")),
                PreferenceEntry(shared, PreferenceValue.FloatValue(2f))),
            playback = BackupPlayback(emptyList(), emptyList(), mapOf("2026-10-05" to 999L)))
        try {
            when (phase) {
                "prepare" -> {
                    check(!file.exists()) { "Pending validation must be recovered first" }
                    val original = backend.snapshot(data, selection)
                    File(directory, "expected.bin").writeBytes(original)
                    journal.prepare(original)
                    backend.apply(data, selection)
                    backend.verify(data, selection)
                    check(file.exists()) // Deliberately leave uncommitted record for next process.
                }
                "recover" -> {
                    check(journal.recoverPending(backend))
                    check(!file.exists())
                    check(backend.snapshot(data, selection).contentEquals(File(directory, "expected.bin").readBytes()))
                    // Now inject a verification failure and prove automatic rollback against actual persistence.
                    val failing = object : BackupRestoreBackend by backend {
                        override suspend fun verify(backup: ValidatedBackup, selection: BackupSelection) { error("injected verification failure") }
                    }
                    check(runCatching { BackupRestoreCoordinator(failing, journal).restore(data, selection) }.isFailure)
                    check(backend.snapshot(data, selection).contentEquals(File(directory, "expected.bin").readBytes()))
                    check(!file.exists())
                }
                else -> error("Expected prepare or recover phase")
            }
        } finally { database.close() }
    }
}
