package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersistedBackupRestoreBackendTest {
    @Test fun rollbackRestoresWrongOriginalTypeAndRemovesNewlyCreatedKey() = runBlocking {
        val prefs = MemoryPreferenceAccess()
        val oldAddress = PreferenceAddress("home_header_preferences", "carousel_style")
        prefs.values[oldAddress] = PreferenceValue.LongValue(7)
        val durable = MemoryDurableAccess()
        val backend = PersistedBackupRestoreBackend(prefs, durable)
        val backup = testBackup().copy(preferences = testBackup().preferences + PreferenceEntry(oldAddress, PreferenceValue.IntValue(1)))
        val snapshot = backend.snapshot(backup, BackupSelection())
        backend.apply(backup, BackupSelection())
        assertEquals(PreferenceValue.IntValue(1), prefs.values[oldAddress])
        backend.rollback(snapshot)
        assertEquals(mapOf(oldAddress to PreferenceValue.LongValue(7)), prefs.values)
        assertEquals("before", durable.value)
    }

    @Test fun selectedSectionsDoNotTouchExistingResourcePreferences() = runBlocking {
        val prefs = MemoryPreferenceAccess()
        val font = PreferenceAddress("mmkv", "ui_custom_font_path")
        prefs.values[font] = PreferenceValue.StringValue("/valid.ttf")
        val backend = PersistedBackupRestoreBackend(prefs, MemoryDurableAccess())
        val backup = testBackup().copy(preferences = testBackup().preferences + PreferenceEntry(font, PreferenceValue.StringValue("/missing.ttf")))
        backend.apply(backup, BackupSelection())
        assertEquals(PreferenceValue.StringValue("/valid.ttf"), prefs.values[font])
        assertEquals(PreferenceValue.BooleanValue(true), prefs.values[PreferenceAddress("mmkv", "hdr_window_enabled")])
    }

    @Test fun failedPreferenceReadBackUsesRealBackendSnapshotToRollbackAllData() = runBlocking {
        val prefs = MemoryPreferenceAccess().apply { discardWrites = true }
        val durable = MemoryDurableAccess()
        val storage = TransactionTestStorage()
        val backend = PersistedBackupRestoreBackend(prefs, durable)
        val error = runCatching { BackupRestoreCoordinator(backend, BackupRecoveryJournal(storage)).restore(testBackup(), BackupSelection()) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("before", durable.value)
        assertTrue(prefs.values.isEmpty())
        assertNull(storage.bytes)
        assertEquals(1, durable.rollbacks)
    }

    @Test fun malformedSnapshotCannotPerformEvenTheFirstRollbackWrite() = runBlocking {
        val prefs = MemoryPreferenceAccess()
        val durable = MemoryDurableAccess()
        val backend = PersistedBackupRestoreBackend(prefs, durable)
        assertTrue(runCatching { backend.rollback(byteArrayOf(1, 2)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(prefs.values.isEmpty())
        assertEquals(0, durable.rollbacks)
    }
}

private class MemoryPreferenceAccess : BackupPreferenceAccess {
    val values = mutableMapOf<PreferenceAddress, PreferenceValue>()
    var discardWrites = false
    override fun read(address: PreferenceAddress, type: BackupPreferenceSchema.ValueType) = values[address]
    override fun write(address: PreferenceAddress, value: PreferenceValue?) {
        if (value == null) values.remove(address) else if (!discardWrites) values[address] = value
    }
    override fun sync() = Unit
}

private class MemoryDurableAccess : BackupDurableAccess {
    var value = "before"
    var rollbacks = 0
    override suspend fun snapshot(selection: BackupSelection) = value.toByteArray()
    override suspend fun apply(backup: ValidatedBackup, selection: BackupSelection) { value = "after" }
    override suspend fun verify(backup: ValidatedBackup, selection: BackupSelection) { check(value == "after") }
    override suspend fun rollback(snapshot: ByteArray) { value = snapshot.toString(Charsets.UTF_8); rollbacks++ }
    override suspend fun publish() = Unit
}
