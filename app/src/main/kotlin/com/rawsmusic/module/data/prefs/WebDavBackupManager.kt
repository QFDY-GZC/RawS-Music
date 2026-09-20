package com.rawsmusic.module.data.prefs

import android.content.Context
import android.util.Log
import com.rawsmusic.module.scanner.webdav.AuthMode
import com.rawsmusic.module.scanner.webdav.WebDavClient
import com.rawsmusic.module.scanner.webdav.WebDavConfig
import com.rawsmusic.module.scanner.webdav.WebDavHeaderCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebDavBackupManager {

    private const val TAG = "WebDavBackup"
    private const val BACKUP_DIR = "RawSMusic-Backup/"
    private const val BACKUP_FILE = "rawsmusic_backup.json"

    suspend fun backup(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig() ?: return@withContext Result.failure(Exception("未配置WebDAV"))
            val client = WebDavClient()
            val backupDir = client.resolveChildUrl(config.url, BACKUP_DIR, directory = true)
            if (!client.createDirectory(config, backupDir)) {
                return@withContext Result.failure(Exception("无法创建 WebDAV 备份目录"))
            }

            val remotePath = client.resolveChildUrl(backupDir, BACKUP_FILE, directory = false)
            val success = client.uploadFile(
                config,
                remotePath,
                BackupArchiveManager.exportBytes(context),
            )
            if (success) Result.success(Unit)
            else Result.failure(Exception("上传失败"))
        } catch (e: Exception) {
            Log.e(TAG, "backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restore(context: Context): Result<BackupArchiveManager.RestoreReport> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig() ?: return@withContext Result.failure(Exception("未配置WebDAV"))
            val client = WebDavClient()
            val backupDir = client.resolveChildUrl(config.url, BACKUP_DIR, directory = true)
            val remotePath = client.resolveChildUrl(backupDir, BACKUP_FILE, directory = false)
            val bytes = client.downloadFile(config, remotePath)
                ?: return@withContext Result.failure(Exception("下载失败或无备份文件"))

            Result.success(BackupArchiveManager.restoreBytes(context, bytes))
        } catch (e: Exception) {
            Log.e(TAG, "restore failed", e)
            Result.failure(e)
        }
    }

    private fun getConfig(): WebDavConfig? {
        val url = AppPreferences.WebDav.url
        if (url.isBlank()) return null
        return WebDavConfig(
            url = url,
            username = AppPreferences.WebDav.username,
            password = AppPreferences.WebDav.password,
            authMode = when (AppPreferences.WebDav.authMode) {
                1 -> AuthMode.BASIC
                2 -> AuthMode.DIGEST
                else -> AuthMode.AUTO
            },
            extraHeaders = WebDavHeaderCodec.parseOrEmpty(AppPreferences.WebDav.extraHeadersText),
        )
    }
}
