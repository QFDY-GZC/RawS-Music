package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rawsmusic.module.data.db.entity.FolderFileEntity

@Dao
interface FolderFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: FolderFileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(files: List<FolderFileEntity>): List<Long>

    @Update
    suspend fun update(file: FolderFileEntity)

    @Update
    suspend fun updateBatch(files: List<FolderFileEntity>)

    @Query("SELECT * FROM folder_files WHERE _id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderFileEntity?

    @Query("SELECT * FROM folder_files WHERE file_path = :path LIMIT 1")
    suspend fun getByPath(path: String): FolderFileEntity?

    @Query("SELECT * FROM folder_files WHERE file_path = :path AND cue_offset_ms = :cueOffset AND cue_track_index = :cueTrack LIMIT 1")
    suspend fun getByPathAndCue(path: String, cueOffset: Long, cueTrack: Int): FolderFileEntity?

    @Query("SELECT * FROM folder_files WHERE folder_id = :folderId ORDER BY track_number ASC")
    suspend fun getByFolderId(folderId: Long): List<FolderFileEntity>

    @Query("SELECT * FROM folder_files ORDER BY name ASC")
    suspend fun getAll(): List<FolderFileEntity>

    @Query("SELECT * FROM folder_files WHERE tag_status != 0")
    suspend fun getAllTagged(): List<FolderFileEntity>

    @Query("""
        SELECT * FROM folder_files
        WHERE title_tag LIKE '%' || :query || '%'
           OR artist_tag LIKE '%' || :query || '%'
           OR album_tag LIKE '%' || :query || '%'
           OR name LIKE '%' || :query || '%'
        ORDER BY title_tag ASC
    """)
    suspend fun search(query: String): List<FolderFileEntity>

    @Query("SELECT DISTINCT artist_tag FROM folder_files WHERE artist_tag != '' ORDER BY artist_tag ASC")
    suspend fun getDistinctArtists(): List<String>

    @Query("SELECT DISTINCT album_tag FROM folder_files WHERE album_tag != '' ORDER BY album_tag ASC")
    suspend fun getDistinctAlbums(): List<String>

    @Query("SELECT DISTINCT album_artist_tag FROM folder_files WHERE album_artist_tag != '' ORDER BY album_artist_tag ASC")
    suspend fun getDistinctAlbumArtists(): List<String>

    @Query("SELECT DISTINCT genre_tag FROM folder_files WHERE genre_tag != '' ORDER BY genre_tag ASC")
    suspend fun getDistinctGenres(): List<String>

    @Query("SELECT DISTINCT composer_tag FROM folder_files WHERE composer_tag != '' ORDER BY composer_tag ASC")
    suspend fun getDistinctComposers(): List<String>

    @Query("SELECT * FROM folder_files WHERE artist_tag = :artist ORDER BY album_tag ASC, track_number ASC")
    suspend fun getByArtist(artist: String): List<FolderFileEntity>

    @Query("SELECT * FROM folder_files WHERE album_tag = :album ORDER BY disc_number ASC, track_number ASC")
    suspend fun getByAlbum(album: String): List<FolderFileEntity>

    @Query("SELECT * FROM folder_files WHERE genre_tag = :genre ORDER BY artist_tag ASC, album_tag ASC")
    suspend fun getByGenre(genre: String): List<FolderFileEntity>

    @Query("SELECT * FROM folder_files WHERE album_artist_tag = :albumArtist ORDER BY album_tag ASC, track_number ASC")
    suspend fun getByAlbumArtist(albumArtist: String): List<FolderFileEntity>

    @Query("UPDATE folder_files SET played_times = played_times + 1, played_at = :playedAt, last_pos = :lastPos WHERE _id = :id")
    suspend fun updatePlayedInfo(id: Long, playedAt: Long, lastPos: Long)

    @Query("UPDATE folder_files SET played_fully_at = :playedFullyAt WHERE _id = :id")
    suspend fun updatePlayedFully(id: Long, playedFullyAt: Long)

    @Query("UPDATE folder_files SET rating = :rating WHERE _id = :id")
    suspend fun updateRating(id: Long, rating: Int)

    @Query("DELETE FROM folder_files WHERE file_path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM folder_files WHERE file_path = :path AND cue_offset_ms = :cueOffset AND cue_track_index = :cueTrack")
    suspend fun deleteByPathAndCue(path: String, cueOffset: Long, cueTrack: Int)

    @Query("DELETE FROM folder_files WHERE folder_id = :folderId")
    suspend fun deleteByFolderId(folderId: Long)

    @Query("DELETE FROM folder_files")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM folder_files")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM folder_files WHERE folder_id = :folderId")
    suspend fun countByFolder(folderId: Long): Int

    @Query("SELECT SUM(duration) FROM folder_files")
    suspend fun totalDuration(): Long?

    @Query("SELECT SUM(file_size) FROM folder_files")
    suspend fun totalFileSize(): Long?

    @Query("UPDATE folder_files SET tag_status = :status WHERE _id = :id")
    suspend fun updateTagStatus(id: Long, status: Int)
}
