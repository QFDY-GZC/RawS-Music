package com.rawsmusic.module.data.prefs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal interface BackupPreferenceAccess {
    fun read(address: PreferenceAddress, type: BackupPreferenceSchema.ValueType): PreferenceValue?
    fun write(address: PreferenceAddress, value: PreferenceValue?)
    fun sync()
}

internal interface BackupDurableAccess {
    suspend fun snapshot(selection: BackupSelection): ByteArray
    suspend fun apply(backup: ValidatedBackup, selection: BackupSelection)
    suspend fun verify(backup: ValidatedBackup, selection: BackupSelection)
    suspend fun rollback(snapshot: ByteArray)
    suspend fun publish()
}

internal class PersistedBackupRestoreBackend(
    private val preferences: BackupPreferenceAccess,
    private val durable: BackupDurableAccess,
) : BackupRestoreBackend {
    private fun selected(backup: ValidatedBackup, selection: BackupSelection) = backup.preferences.filter {
        BackupPreferenceRegistry.classify(it.address)?.section in selection.sections
    }

    override suspend fun snapshot(backup: ValidatedBackup, selection: BackupSelection): ByteArray {
        val oldValues = selected(backup, selection).associate { entry ->
            entry.address to preferences.read(entry.address, entry.value.type)
        }
        val preferenceBytes = BackupPreferenceSnapshot.encode(oldValues)
        val durableBytes = durable.snapshot(selection)
        require(preferenceBytes.size.toLong() + durableBytes.size + 12 <= BackupBoundedReader.MAX_BYTES) { "恢复快照过大" }
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeInt(1)
                it.writeInt(preferenceBytes.size)
                it.write(preferenceBytes)
                it.writeInt(durableBytes.size)
                it.write(durableBytes)
            }
        }.toByteArray()
    }

    override suspend fun apply(backup: ValidatedBackup, selection: BackupSelection) {
        durable.apply(backup, selection)
        selected(backup, selection).forEach { preferences.write(it.address, it.value) }
        preferences.sync()
    }

    override suspend fun verify(backup: ValidatedBackup, selection: BackupSelection) {
        durable.verify(backup, selection)
        selected(backup, selection).forEach { entry ->
            check(preferences.read(entry.address, entry.value.type) == entry.value) { "设置写入校验失败：${entry.address.store}/${entry.address.key}" }
        }
    }

    override suspend fun rollback(snapshot: ByteArray) {
        require(snapshot.size in 12..BackupBoundedReader.MAX_BYTES) { "恢复快照大小异常" }
        val (oldValues, durableBytes) = DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
            require(input.readInt() == 1) { "恢复快照版本异常" }
            val preferenceSize = input.readInt()
            require(preferenceSize in 2..(snapshot.size - 12)) { "偏好快照长度异常" }
            val values = BackupPreferenceSnapshot.decode(ByteArray(preferenceSize).also(input::readFully))
            val durableSize = input.readInt()
            require(durableSize >= 0 && durableSize == input.available()) { "数据库快照长度异常" }
            values to ByteArray(durableSize).also(input::readFully)
        }
        // Both envelopes are decoded before the first preference mutation.
        durable.rollback(durableBytes)
        oldValues.forEach { (address, value) -> preferences.write(address, value) }
        preferences.sync()
        oldValues.forEach { (address, value) ->
            val type = value?.type ?: BackupPreferenceRegistry.classify(address)?.type
                ?: error("恢复快照缺少键类型")
            check(preferences.read(address, type) == value) { "偏好回滚校验失败：${address.store}/${address.key}" }
        }
    }

    override suspend fun publish() = durable.publish()
}
