package com.rawsmusic.module.data.db.migration

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.converter.EntityConverter
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.runBlocking

/**
 * MMKV → Room 一次性数据迁移工具
 *
 * 首次启动时检测 MMKV 中是否有旧数据，如有则迁移到 Room。
 * 迁移完成后标记标志，后续启动跳过。
 */
object MmkvToRoomMigration {

    private const val TAG = "MmkvMigration"
    private const val KEY_SONGS = "music_songs"
    private const val KEY_MIGRATION_DONE = "room_migration_done_v1"

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()

    /**
     * 检查是否需要迁移，如需要则执行迁移。
     * 应在 MusicRepository.init() 之后、refreshAll() 之前调用。
     */
    fun migrateIfNeeded(database: MusicDatabase) {
        val migrationDone = kv.decodeBool(KEY_MIGRATION_DONE, false)
        if (migrationDone) {
            AppLogger.d(TAG, "Migration already done, skipping")
            return
        }

        val json = kv.decodeString(KEY_SONGS, "") ?: ""
        if (json.isBlank()) {
            AppLogger.d(TAG, "No MMKV data to migrate")
            kv.encode(KEY_MIGRATION_DONE, true)
            return
        }

        AppLogger.d(TAG, "Starting MMKV → Room migration...")
        val t0 = System.currentTimeMillis()

        try {
            val type = object : TypeToken<List<AudioFile>>() {}.type
            val songs: List<AudioFile> = gson.fromJson(json, type) ?: emptyList()

            if (songs.isEmpty()) {
                AppLogger.d(TAG, "No songs in MMKV")
                kv.encode(KEY_MIGRATION_DONE, true)
                return
            }

            runBlocking {
                val folderDao = database.folderDao()
                val fileDao = database.folderFileDao()

                // 按文件夹分组
                val songsByFolder = songs.groupBy { it.path.substringBeforeLast("/") }
                var totalInserted = 0

                for ((folderPath, folderSongs) in songsByFolder) {
                    // 创建文件夹
                    val folderEntity = EntityConverter.pathToFolderEntity(folderPath)
                    val folderId = folderDao.insert(folderEntity)
                    val actualFolderId = if (folderId == -1L) {
                        folderDao.getByPath(folderPath)?.id ?: continue
                    } else {
                        folderId
                    }

                    // 批量插入文件
                    val entities = folderSongs.map { song ->
                        EntityConverter.audioFileToFolderFile(song, actualFolderId)
                    }
                    fileDao.insertBatch(entities)
                    totalInserted += folderSongs.size
                }

                val tDone = System.currentTimeMillis()
                AppLogger.d(TAG, "Migration complete: $totalInserted songs, ${songsByFolder.size} folders, ${tDone - t0}ms")
            }

            kv.encode(KEY_MIGRATION_DONE, true)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Migration failed", e)
            // 不标记完成，下次启动会重试
        }
    }
}
