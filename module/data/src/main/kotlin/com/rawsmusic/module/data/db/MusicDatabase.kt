package com.rawsmusic.module.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rawsmusic.module.data.db.dao.FolderDao
import com.rawsmusic.module.data.db.dao.FolderFileDao
import com.rawsmusic.module.data.db.dao.PlaylistEntryDao
import com.rawsmusic.module.data.db.dao.PlaylistRoomDao
import com.rawsmusic.module.data.db.entity.FolderEntity
import com.rawsmusic.module.data.db.entity.FolderFileEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntryEntity

@Database(
    entities = [
        FolderEntity::class,
        FolderFileEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun folderFileDao(): FolderFileDao
    abstract fun playlistDao(): PlaylistRoomDao
    abstract fun playlistEntryDao(): PlaylistEntryDao

    companion object {
        private const val DB_NAME = "rawsmusic.db"

        @Volatile
        private var instance: MusicDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_files ADD COLUMN file_modified_at INTEGER NOT NULL DEFAULT 0")

                // 旧库可能已经有 path + cue 重复行；先保留最早的一条，避免创建唯一索引失败。
                db.execSQL(
                    """
                    DELETE FROM folder_files
                    WHERE _id NOT IN (
                        SELECT MIN(_id)
                        FROM folder_files
                        GROUP BY file_path, cue_offset_ms, cue_track_index
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_folder_files_file_path_cue_offset_ms_cue_track_index " +
                        "ON folder_files(file_path, cue_offset_ms, cue_track_index)"
                )
            }
        }

        fun getInstance(context: Context): MusicDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
