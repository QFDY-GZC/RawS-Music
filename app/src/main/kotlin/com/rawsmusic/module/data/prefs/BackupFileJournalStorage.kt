package com.rawsmusic.module.data.prefs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal class BackupFileJournalStorage(
    private val file: File,
    private val replaceAtomically: (File, File) -> Unit,
) : BackupJournalStorage {
    private val temporary = File(file.parentFile, "${file.name}.pending")

    override fun read(): ByteArray? {
        if (!file.exists()) return null
        return FileInputStream(file).use { BackupBoundedReader.read(it, BackupBoundedReader.MAX_BYTES + 45) }
    }

    override fun writeAtomically(bytes: ByteArray) {
        require(bytes.size <= BackupBoundedReader.MAX_BYTES + 45) { "恢复记录过大" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Android production uses POSIX rename; it atomically replaces only this private file.
            replaceAtomically(temporary, file)
        } catch (error: Throwable) {
            // A failed temporary write never removes the previous complete recovery record.
            if (temporary.exists() && !temporary.delete()) {
                error.addSuppressed(java.io.IOException("无法清理未提交的恢复临时文件"))
            }
            throw error
        }
    }

    override fun delete() {
        check(!file.exists() || file.delete()) { "无法清理恢复记录" }
        check(!temporary.exists() || temporary.delete()) { "无法清理恢复临时文件" }
    }

    companion object {
        fun forApplication(context: android.content.Context): BackupFileJournalStorage =
            BackupFileJournalStorage(File(context.applicationContext.filesDir, "backup_restore_journal.bin")) { source, target ->
                android.system.Os.rename(source.absolutePath, target.absolutePath)
            }
    }
}
