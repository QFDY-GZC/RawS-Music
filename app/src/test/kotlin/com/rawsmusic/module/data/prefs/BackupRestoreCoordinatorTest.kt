package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreCoordinatorTest {
    @Test fun unavailableOnlyDependencyRejectsBeforeAnyMutation() = runBlocking {
        val backend = TransactionTestBackend()
        val access = object : BackupDependencyAccess {
            override fun readable(reference: String) = false
            override fun pluginInstalled(id: String) = false
            override fun modelInstalled(id: String, version: String) = false
            override fun instrumentInstalled(key: String) = false
            override fun current(address: PreferenceAddress): PreferenceValue? = null
        }
        val source = testBackup().copy(preferences = listOf(PreferenceEntry(PreferenceAddress("lyrico_sources", "enabled:org.example.lost"), PreferenceValue.BooleanValue(true))))
        val result = runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(TransactionTestStorage()), BackupDependencyResolver(access)).restore(source, BackupSelection()) }
        assertTrue(result.isFailure)
        assertEquals(0, backend.applies)
    }
    @Test fun pendingRollbackRefreshesCachesBeforeAnotherSnapshotCanFail() = runBlocking {
        val backend = TransactionTestBackend().apply { values["old"] = "partial"; failSnapshot = true }
        val storage = TransactionTestStorage()
        BackupRecoveryJournal(storage).prepare("old=before".toByteArray())
        assertTrue(runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.isFailure)
        assertEquals(listOf(mapOf("old" to "before")), backend.published)
        assertEquals(0, backend.applies)
    }
    @Test fun concurrentRestoresAndQueuedEditsAreSerialized() = runBlocking {
        val first = TransactionTestBackend().apply { pauseApply = true }
        val second = TransactionTestBackend()
        val firstJob = launch { BackupRestoreCoordinator(first, BackupRecoveryJournal(TransactionTestStorage())).restore(testBackup(), BackupSelection()) }
        first.applying.await()
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            BackupRestoreCoordinator(second, BackupRecoveryJournal(TransactionTestStorage())).restore(testBackup(), BackupSelection())
        }
        val edit = launch(start = CoroutineStart.UNDISPATCHED) {
            BackupWriteGate.withExclusive { first.values["queued-play"] = "1"; first.values["queued-playlist"] = "kept" }
        }
        assertEquals(0, second.applies)
        assertFalse(first.values.containsKey("queued-play"))
        first.resumeApply.complete(Unit)
        joinAll(firstJob, secondJob, edit)
        assertEquals("1", first.values["queued-play"])
        assertEquals("kept", first.values["queued-playlist"])
        assertEquals(1, second.applies)
    }

    @Test fun committedDataSurvivesCleanupFailureAndNextRecovery() = runBlocking {
        val backend = TransactionTestBackend()
        val storage = TransactionTestStorage().apply { failDelete = true }
        val report = BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection())
        assertTrue(report.warnings.isNotEmpty())
        storage.failDelete = false
        BackupRecoveryJournal(storage).recoverPending(backend)
        assertEquals(0, backend.rollbacks)
        assertEquals(mapOf("old" to "after", "new" to "created"), backend.values)
    }

    @Test fun commitWriteFailureRollsBackAndKeepsCancellationSemantics() = runBlocking {
        val backend = TransactionTestBackend()
        val storage = TransactionTestStorage().apply { failWriteNumber = 2 }
        assertTrue(runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.isFailure)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertNull(storage.bytes)
    }

    @Test fun emptySelectionIsRejectedBeforeSnapshotOrPublication() = runBlocking {
        val backend = TransactionTestBackend()
        assertTrue(runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(TransactionTestStorage())).restore(testBackup(), BackupSelection(emptySet())) }.isFailure)
        assertEquals(0, backend.applies)
        assertTrue(backend.published.isEmpty())
    }
    @Test fun failedApplyRemovesNewKeysAndRestoresOriginalValues() = runBlocking {
        val backend = TransactionTestBackend().apply { failApply = true }
        val storage = TransactionTestStorage()
        val result = runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }
        assertTrue(result.isFailure)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertEquals(1, backend.rollbacks)
        assertNull(storage.bytes)
    }

    @Test fun failedReadBackRollsBackBeforePublishing() = runBlocking {
        val backend = TransactionTestBackend().apply { failVerify = true }
        val storage = TransactionTestStorage()
        assertTrue(runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.isFailure)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertEquals(listOf(mapOf("old" to "before")), backend.published)
    }

    @Test fun cancellationFinishesRollbackAndThenPropagates() = runBlocking {
        val backend = TransactionTestBackend().apply { pauseApply = true; yieldRollback = true }
        val storage = TransactionTestStorage()
        val job = launch { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }
        backend.applying.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, backend.rollbacks)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertNull(storage.bytes)
    }

    @Test fun journalWriteFailureDoesNotChangePersistence() = runBlocking {
        val backend = TransactionTestBackend()
        val storage = TransactionTestStorage().apply { failWrite = true }
        assertTrue(runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.isFailure)
        assertEquals(0, backend.applies)
        assertEquals(mapOf("old" to "before"), backend.values)
    }

    @Test fun rollbackFailureRetainsRecoveryRecordAndIsReported() = runBlocking {
        val backend = TransactionTestBackend().apply { failApply = true; failRollback = true }
        val storage = TransactionTestStorage()
        val error = runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.exceptionOrNull()!!
        assertTrue(error.suppressed.any { it.message == "rollback failed" })
        assertNotNull(storage.bytes)
    }

    @Test fun committedRestorePublishesOnlyVerifiedData() = runBlocking {
        val backend = TransactionTestBackend()
        val storage = TransactionTestStorage()
        BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection())
        assertEquals(listOf(mapOf("old" to "after", "new" to "created")), backend.published)
        assertEquals(1, backend.verifications)
        assertNull(storage.bytes)
    }
}

internal fun testBackup() = ValidatedBackup(3, 1, "test", setOf(BackupSection.PREFERENCES),
    listOf(PreferenceEntry(PreferenceAddress("mmkv", "hdr_window_enabled"), PreferenceValue.BooleanValue(true))))

internal class TransactionTestStorage : BackupJournalStorage {
    var bytes: ByteArray? = null
    var failWrite = false
    var failWriteNumber = 0
    private var writes = 0
    var failDelete = false
    override fun read() = bytes?.copyOf()
    override fun writeAtomically(bytes: ByteArray) { writes++; check(!failWrite && writes != failWriteNumber) { "disk full" }; this.bytes = bytes.copyOf() }
    override fun delete() { check(!failDelete) { "delete failed" }; bytes = null }
}

internal class TransactionTestBackend : BackupRestoreBackend {
    var values = mutableMapOf("old" to "before")
    var failApply = false
    var failSnapshot = false
    var failVerify = false
    var failRollback = false
    var pauseApply = false
    var yieldRollback = false
    var applies = 0
    var rollbacks = 0
    var verifications = 0
    val applying = CompletableDeferred<Unit>()
    val resumeApply = CompletableDeferred<Unit>()
    val published = mutableListOf<Map<String, String>>()
    override suspend fun snapshot(backup: ValidatedBackup, selection: BackupSelection): ByteArray {
        check(!failSnapshot) { "snapshot failed" }
        return values.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()
    }
    override suspend fun apply(backup: ValidatedBackup, selection: BackupSelection) {
        applies++
        values["old"] = "after"
        values["new"] = "created"
        applying.complete(Unit)
        if (pauseApply) resumeApply.await()
        check(!failApply) { "apply failed" }
    }
    override suspend fun verify(backup: ValidatedBackup, selection: BackupSelection) { verifications++; check(!failVerify) { "verify failed" } }
    override suspend fun rollback(snapshot: ByteArray) {
        check(!failRollback) { "rollback failed" }
        if (yieldRollback) { yield(); delay(1) }
        values = snapshot.toString(Charsets.UTF_8).split('\n').associate { val parts = it.split('=', limit = 2); parts[0] to parts[1] }.toMutableMap()
        rollbacks++
    }
    override suspend fun publish() { published += values.toMap() }
}
