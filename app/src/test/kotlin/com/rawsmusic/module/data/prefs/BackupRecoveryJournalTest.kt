package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupRecoveryJournalTest {
    @Test fun newInstanceRecoversAnUncommittedRestore() = runBlocking {
        val storage = TransactionTestStorage()
        BackupRecoveryJournal(storage).prepare("old=before".toByteArray())
        val backend = TransactionTestBackend().apply { values["new"] = "created" }
        BackupRecoveryJournal(storage).recoverPending(backend)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertEquals(1, backend.rollbacks)
        assertNull(storage.bytes)
    }
    @Test fun committedJournalNeverRevertsVerifiedData() = runBlocking {
        val storage = TransactionTestStorage()
        BackupRecoveryJournal(storage).apply { prepare("old=before".toByteArray()); commit() }
        val backend = TransactionTestBackend().apply { values["old"] = "after" }
        BackupRecoveryJournal(storage).recoverPending(backend)
        assertEquals(mapOf("old" to "after"), backend.values)
        assertEquals(0, backend.rollbacks)
        assertNull(storage.bytes)
    }
    @Test fun unsuccessfulRecoveryPreservesJournalForTheNextLaunch() = runBlocking {
        val storage = TransactionTestStorage()
        BackupRecoveryJournal(storage).prepare("old=before".toByteArray())
        val before = storage.bytes!!.copyOf()
        val backend = TransactionTestBackend().apply { failRollback = true }
        assertTrue(runCatching { BackupRecoveryJournal(storage).recoverPending(backend) }.isFailure)
        assertArrayEquals(before, storage.bytes)
    }
    @Test fun corruptJournalFailsClosedWithoutWritingDatabase() = runBlocking {
        val storage = TransactionTestStorage().apply { bytes = "{}".toByteArray() }
        val backend = TransactionTestBackend()
        assertTrue(runCatching { BackupRecoveryJournal(storage).recoverPending(backend) }.isFailure)
        assertEquals(0, backend.rollbacks)
        assertNotNull(storage.bytes)
    }
}
