package com.rawsmusic.module.data.prefs

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFileJournalStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun storage(failRename: Boolean = false): BackupFileJournalStorage =
        BackupFileJournalStorage(temporary.root.resolve("recovery.bin")) { source, target ->
            check(!failRename) { "rename failed" }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

    @Test fun failedReplacementRetainsPreviousCompleteRecord() {
        storage().writeAtomically("before".toByteArray())
        assertTrue(runCatching { storage(true).writeAtomically("after".toByteArray()) }.isFailure)
        assertEquals("before", storage().read()!!.toString(Charsets.UTF_8))
    }

    @Test fun recreatedJournalRecoversFromRealDisk() = runBlocking {
        BackupRecoveryJournal(storage()).prepare("old=before".toByteArray())
        val backend = TransactionTestBackend().apply { values["new"] = "created" }
        BackupRecoveryJournal(storage()).recoverPending(backend)
        assertEquals(mapOf("old" to "before"), backend.values)
        assertNull(storage().read())
    }

    @Test fun unrelatedFilesRemainWhenClearingRecovery() {
        val unrelated = temporary.newFile("user-backup.json")
        storage().writeAtomically(byteArrayOf(1))
        storage().delete()
        assertTrue(unrelated.exists())
        assertNull(storage().read())
    }
}
