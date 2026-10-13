package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupRemoteRepositoryTest {
    private fun bytes() = BackupArchiveCodec.encode(ValidatedBackup(3, 1, "test", setOf(BackupSection.PLAYBACK),
        playback = BackupPlayback(emptyList(), emptyList(), mapOf("2026-10-05" to 1000))), BackupSelection())
    @Test fun latestChangesOnlyAfterVerifiedUploadAndLegacyRemainsIntact() = runBlocking {
        val storage = MemoryRemote()
        storage.files["rawsmusic_backup.json"] = bytes()
        val repo = BackupRemoteRepository(storage)
        assertEquals(1L, repo.latest().timestamp)
        repo.upload(bytes())
        val marker = storage.files["latest.json"]!!.copyOf()
        storage.corrupt = true
        assertTrue(runCatching { repo.upload(bytes()) }.isFailure)
        assertArrayEquals(marker, storage.files["latest.json"])
        assertArrayEquals(bytes(), storage.files["rawsmusic_backup.json"])
    }
    @Test fun invalidPointerCannotReadOutsideBackupDirectory() = runBlocking {
        val storage = MemoryRemote()
        storage.files["latest.json"] = "{\"name\":\"../file.json\",\"size\":1,\"sha256\":\"x\"}".toByteArray()
        assertTrue(runCatching { BackupRemoteRepository(storage).latest() }.isFailure)
        assertEquals(listOf("latest.json"), storage.reads)
    }
}
private class MemoryRemote : BackupRemoteStorage {
    val files = linkedMapOf<String, ByteArray>()
    val reads = mutableListOf<String>()
    var corrupt = false
    override suspend fun put(name: String, bytes: ByteArray) { files[name] = if (corrupt) "{}".toByteArray() else bytes.copyOf() }
    override suspend fun get(name: String, maxBytes: Int): ByteArray? { reads += name; return files[name]?.also { require(it.size <= maxBytes) } }
}
