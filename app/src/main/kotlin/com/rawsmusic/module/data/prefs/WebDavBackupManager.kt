package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.module.scanner.webdav.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebDavBackupManager {
    suspend fun backup(context: Context): Result<Unit> = backupSelected(context, BackupSelection())

    internal suspend fun backupSelected(context: Context, selection: BackupSelection): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            BackupOperationGate.withExclusive {
                repository().upload(BackupArchiveManager.exportSelectedUnderOperationGate(context, selection))
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    /** Downloads and validates only. Applying requires the shared confirmation screen. */
    internal suspend fun loadLatest(): ValidatedBackup = withContext(Dispatchers.IO) {
        BackupOperationGate.withExclusive { repository().latest() }
    }

    suspend fun restore(context: Context): Result<BackupArchiveManager.RestoreReport> = withContext(Dispatchers.IO) {
        try { Result.success(BackupArchiveManager.restoreSelected(context, loadLatest(), BackupSelection())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    private fun repository(): BackupRemoteRepository {
        val url = AppPreferences.WebDav.url
        require(url.isNotBlank()) { "未配置 WebDAV" }
        val config = WebDavConfig(url, AppPreferences.WebDav.username, AppPreferences.WebDav.password,
            when (AppPreferences.WebDav.authMode) { 1 -> AuthMode.BASIC; 2 -> AuthMode.DIGEST; else -> AuthMode.AUTO },
            WebDavHeaderCodec.parseOrEmpty(AppPreferences.WebDav.extraHeadersText))
        val client = WebDavClient()
        val directory = client.resolveChildUrl(config.url, "RawSMusic-Backup/", directory = true)
        return BackupRemoteRepository(object : BackupRemoteStorage {
            override suspend fun put(name: String, bytes: ByteArray) {
                require(client.createDirectory(config, directory)) { "无法创建备份目录" }
                require(client.uploadFile(config, client.resolveChildUrl(directory, name, directory = false), bytes)) { "上传失败" }
            }
            override suspend fun get(name: String, maxBytes: Int): ByteArray? =
                client.downloadFile(config, client.resolveChildUrl(directory, name, directory = false), maxBytes)
        })
    }
}
