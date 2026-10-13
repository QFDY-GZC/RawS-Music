package com.rawsmusic.module.data.prefs

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface BackupRemoteStorage {
    suspend fun put(name: String, bytes: ByteArray)
    suspend fun get(name: String, maxBytes: Int): ByteArray?
}

/** Minimal verified latest pointer. No history pruning, overwrites of old archives or credentials. */
internal class BackupRemoteRepository(private val storage: BackupRemoteStorage) {
    private val namePattern = Regex("backup-[0-9]+-[0-9a-f-]{36}\\.json")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun upload(bytes: ByteArray) {
        val archive = BackupArchiveCodec.decode(bytes)
        val name = "backup-${archive.timestamp}-${UUID.randomUUID()}.json"
        currentCoroutineContext().ensureActive()
        storage.put(name, bytes)
        val verified = storage.get(name, BackupBoundedReader.MAX_BYTES) ?: error("备份上传后无法读取验证")
        require(verified.contentEquals(bytes)) { "备份上传验证不一致" }
        BackupArchiveCodec.decode(verified)
        currentCoroutineContext().ensureActive()
        val pointer = JsonObject().apply {
            addProperty("name", name); addProperty("size", bytes.size); addProperty("sha256", hash(bytes))
        }.toString().toByteArray(Charsets.UTF_8)
        storage.put("latest.json", pointer)
        require(storage.get("latest.json", 16384)?.contentEquals(pointer) == true) { "最新备份标记验证失败" }
    }
    suspend fun latest(): ValidatedBackup {
        val pointer = storage.get("latest.json", 16384)
        if (pointer == null) return BackupArchiveCodec.decode(storage.get("rawsmusic_backup.json", BackupBoundedReader.MAX_BYTES) ?: error("无云端备份"))
        val tree = BackupStrictJson.parse(pointer.toString(Charsets.UTF_8)).asJsonObject
        require(tree.keySet() == setOf("name", "size", "sha256")) { "备份标记格式错误" }
        require(tree["name"].isJsonPrimitive && tree["name"].asJsonPrimitive.isString)
        val name = tree["name"].asString
        require(namePattern.matches(name)) { "备份文件路径错误" }
        val size = BackupStrictJson.integer(tree["size"])
        require(size in 1..BackupBoundedReader.MAX_BYTES.toLong())
        val digest = tree["sha256"].asString
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val bytes = storage.get(name, BackupBoundedReader.MAX_BYTES) ?: error("备份文件缺失")
        require(bytes.size.toLong() == size && hash(bytes) == digest) { "备份文件校验失败" }
        currentCoroutineContext().ensureActive()
        return BackupArchiveCodec.decode(bytes)
    }
}
