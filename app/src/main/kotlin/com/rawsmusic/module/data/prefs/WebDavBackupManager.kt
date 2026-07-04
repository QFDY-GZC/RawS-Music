package com.rawsmusic.module.data.prefs

import android.content.Context
import android.util.Log
import com.rawsmusic.module.scanner.webdav.WebDavClient
import com.rawsmusic.module.scanner.webdav.WebDavConfig
import com.rawsmusic.module.scanner.webdav.AuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WebDavBackupManager {

    private const val TAG = "WebDavBackup"
    private const val BACKUP_DIR = "RawSMusic-Backup/"
    private const val BACKUP_FILE = "rawsmusic_backup.json"

    suspend fun backup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig() ?: return@withContext Result.failure(Exception("未配置WebDAV"))
            val client = WebDavClient()

            client.createDirectory(config, config.url + BACKUP_DIR)

            val json = JSONObject()
            json.put("version", 1)
            json.put("timestamp", System.currentTimeMillis())
            json.put("playlists", PlaylistStore.getInstance(context).exportJson())
            json.put("playback", PlaybackStatsStore.getInstance(context).exportJson())

            val remotePath = config.url + BACKUP_DIR + BACKUP_FILE
            val success = client.uploadFile(config, remotePath, json.toString().toByteArray(Charsets.UTF_8))
            if (success) Result.success("备份成功")
            else Result.failure(Exception("上传失败"))
        } catch (e: Exception) {
            Log.e(TAG, "backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restore(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig() ?: return@withContext Result.failure(Exception("未配置WebDAV"))
            val client = WebDavClient()

            val remotePath = config.url + BACKUP_DIR + BACKUP_FILE
            val bytes = client.downloadFile(config, remotePath)
                ?: return@withContext Result.failure(Exception("下载失败或无备份文件"))

            val json = JSONObject(String(bytes, Charsets.UTF_8))

            json.optJSONObject("playlists")?.let {
                PlaylistStore.getInstance(context).restoreJson(it)
            }
            json.optJSONObject("playback")?.let {
                PlaybackStatsStore.getInstance(context).restoreJson(it)
            }

            Result.success("恢复成功")
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
            }
        )
    }
}
