package com.rawsmusic.module.data.prefs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal class BackupRecoveryJournal(private val storage: BackupJournalStorage) {
    private data class Record(val committed: Boolean, val snapshot: ByteArray)

    fun prepare(snapshot: ByteArray) {
        check(storage.read() == null) { "仍有未处理的恢复记录" }
        write(Record(false, snapshot))
    }

    fun commit() {
        val record = read() ?: error("恢复记录丢失")
        check(!record.committed) { "恢复已经提交" }
        write(record.copy(committed = true))
    }

    fun clear() = storage.delete()

    /** Must run before store caches publish, or while holding BackupWriteGate. */
    suspend fun recoverPending(backend: BackupRestoreBackend): Boolean {
        val record = read() ?: return false
        if (!record.committed) backend.rollback(record.snapshot)
        // Startup callers need no cache publication; runtime callers do it after this returns.
        clear()
        return !record.committed
    }

    private fun read(): Record? {
        val bytes = storage.read() ?: return null
        require(bytes.size in HEADER_SIZE..(HEADER_SIZE + BackupBoundedReader.MAX_BYTES)) { "恢复记录大小异常" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1) { "恢复记录格式异常" }
            val state = input.readUnsignedByte()
            require(state in 0..1) { "恢复记录状态异常" }
            val size = input.readInt()
            require(size in 1..BackupBoundedReader.MAX_BYTES && bytes.size == HEADER_SIZE + size) { "恢复快照大小异常" }
            val digest = ByteArray(32).also(input::readFully)
            val snapshot = ByteArray(size).also(input::readFully)
            require(MessageDigest.isEqual(digest, checksum(state, snapshot))) { "恢复记录校验失败" }
            Record(state == 1, snapshot)
        }
    }

    private fun write(record: Record) {
        require(record.snapshot.size in 1..BackupBoundedReader.MAX_BYTES) { "恢复快照过大或为空" }
        val state = if (record.committed) 1 else 0
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeByte(state)
                output.writeInt(record.snapshot.size)
                output.write(checksum(state, record.snapshot))
                output.write(record.snapshot)
            }
        }.toByteArray()
        storage.writeAtomically(bytes)
    }

    private fun checksum(state: Int, snapshot: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(state.toByte()); update(snapshot) }.digest()

    private companion object {
        const val MAGIC = 0x5253424A
        const val HEADER_SIZE = 45
    }
}
