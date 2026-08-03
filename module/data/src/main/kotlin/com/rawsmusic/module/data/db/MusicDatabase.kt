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
import com.rawsmusic.module.data.db.dao.PlaybackStatsDao
import com.rawsmusic.module.data.db.dao.SearchHistoryDao
import com.rawsmusic.module.data.db.dao.UserPlaylistDao
import com.rawsmusic.module.data.db.entity.FolderEntity
import com.rawsmusic.module.data.db.entity.FolderFileEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntity
import com.rawsmusic.module.data.db.entity.PlaylistEntryEntity
import com.rawsmusic.module.data.db.entity.DailyListenEntity
import com.rawsmusic.module.data.db.entity.PlaybackHistoryEntity
import com.rawsmusic.module.data.db.entity.PlaybackStatEntity
import com.rawsmusic.module.data.db.entity.SearchHistoryEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity

@Database(
    entities = [
        FolderEntity::class,
        FolderFileEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SearchHistoryEntity::class,
        PlaybackStatEntity::class,
        PlaybackHistoryEntity::class,
        DailyListenEntity::class,
        UserPlaylistEntity::class,
        UserPlaylistSongEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun folderFileDao(): FolderFileDao
    abstract fun playlistDao(): PlaylistRoomDao
    abstract fun playlistEntryDao(): PlaylistEntryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun playbackStatsDao(): PlaybackStatsDao
    abstract fun userPlaylistDao(): UserPlaylistDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_history (
                        normalized_query TEXT NOT NULL PRIMARY KEY,
                        query TEXT NOT NULL,
                        used_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_stats (" +
                        "song_id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, album TEXT NOT NULL, play_count INTEGER NOT NULL, " +
                        "listened_ms INTEGER NOT NULL, last_played_at INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, song_id INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, " +
                        "played_at INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_played_at ON playback_history(played_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_song_id ON playback_history(song_id)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_listen_stats (" +
                        "date TEXT NOT NULL PRIMARY KEY, listened_ms INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS user_playlists (" +
                        "playlist_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                        "sort_order INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_user_playlists_name " +
                        "ON user_playlists(name)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS user_playlist_songs (" +
                        "playlist_id TEXT NOT NULL, song_key TEXT NOT NULL, media_id INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, " +
                        "album_id INTEGER NOT NULL, duration INTEGER NOT NULL, path TEXT NOT NULL, " +
                        "file_size INTEGER NOT NULL, format TEXT NOT NULL, sample_rate INTEGER NOT NULL, " +
                        "bit_rate INTEGER NOT NULL, bits_per_sample INTEGER NOT NULL, " +
                        "album_art_path TEXT NOT NULL, added_at INTEGER NOT NULL, sort_order INTEGER NOT NULL, " +
                        "PRIMARY KEY(playlist_id, song_key), " +
                        "FOREIGN KEY(playlist_id) REFERENCES user_playlists(playlist_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_user_playlist_songs_playlist_id " +
                        "ON user_playlist_songs(playlist_id)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_user_playlist_songs_playlist_id_sort_order " +
                        "ON user_playlist_songs(playlist_id, sort_order)"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
