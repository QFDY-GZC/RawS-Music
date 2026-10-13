package com.rawsmusic.module.data.prefs

import android.content.Context
import androidx.room.withTransaction
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.*

internal class AndroidBackupPreferenceAccess(private val context: Context) : BackupPreferenceAccess {
    override fun read(address: PreferenceAddress, type: BackupPreferenceSchema.ValueType): PreferenceValue? {
        if (address.store != "mmkv") {
            val value = context.getSharedPreferences(address.store, Context.MODE_PRIVATE).all[address.key] ?: return null
            return when (value) {
                is Boolean -> PreferenceValue.BooleanValue(value)
                is Int -> PreferenceValue.IntValue(value)
                is Long -> PreferenceValue.LongValue(value)
                is Float -> PreferenceValue.FloatValue(value)
                is String -> PreferenceValue.StringValue(value)
                is Set<*> -> { check(value.all { it is String }); PreferenceValue.StringSetValue(value.filterIsInstance<String>().toSet()) }
                else -> error("不支持的原设置类型：${address.store}/${address.key}")
            }
        }
        if (!BackupPreferenceStorage.contains(address.key)) return null
        val key = address.key
        return when (type) {
            BackupPreferenceSchema.ValueType.BOOLEAN -> PreferenceValue.BooleanValue(BackupPreferenceStorage.readBoolean(key))
            BackupPreferenceSchema.ValueType.INT -> PreferenceValue.IntValue(BackupPreferenceStorage.readInt(key))
            BackupPreferenceSchema.ValueType.LONG -> PreferenceValue.LongValue(BackupPreferenceStorage.readLong(key))
            BackupPreferenceSchema.ValueType.FLOAT -> PreferenceValue.FloatValue(BackupPreferenceStorage.readFloat(key))
            BackupPreferenceSchema.ValueType.DOUBLE -> PreferenceValue.DoubleValue(BackupPreferenceStorage.readDouble(key))
            BackupPreferenceSchema.ValueType.STRING -> PreferenceValue.StringValue(BackupPreferenceStorage.readString(key))
            BackupPreferenceSchema.ValueType.STRING_SET -> PreferenceValue.StringSetValue(BackupPreferenceStorage.readStringSet(key))
        }
    }

    override fun write(address: PreferenceAddress, value: PreferenceValue?) {
        require(address.store.matches(Regex("[A-Za-z0-9_]+")) && address.key.isNotBlank()) { "恢复存储地址无效" }
        if (address.store == "mmkv") {
            val key = address.key
            val ok = when (value) {
                null -> { BackupPreferenceStorage.remove(key); true }
                is PreferenceValue.BooleanValue -> BackupPreferenceStorage.writeBoolean(key, value.value)
                is PreferenceValue.IntValue -> BackupPreferenceStorage.writeInt(key, value.value)
                is PreferenceValue.LongValue -> BackupPreferenceStorage.writeLong(key, value.value)
                is PreferenceValue.FloatValue -> BackupPreferenceStorage.writeFloat(key, value.value)
                is PreferenceValue.DoubleValue -> BackupPreferenceStorage.writeDouble(key, value.value)
                is PreferenceValue.StringValue -> BackupPreferenceStorage.writeString(key, value.value)
                is PreferenceValue.StringSetValue -> BackupPreferenceStorage.writeStringSet(key, value.value)
            }
            check(ok) { "设置写入失败：$key" }
        } else {
            val editor = context.getSharedPreferences(address.store, Context.MODE_PRIVATE).edit()
            when (value) {
                null -> editor.remove(address.key)
                is PreferenceValue.BooleanValue -> editor.putBoolean(address.key, value.value)
                is PreferenceValue.IntValue -> editor.putInt(address.key, value.value)
                is PreferenceValue.LongValue -> editor.putLong(address.key, value.value)
                is PreferenceValue.FloatValue -> editor.putFloat(address.key, value.value)
                is PreferenceValue.StringValue -> editor.putString(address.key, value.value)
                is PreferenceValue.StringSetValue -> editor.putStringSet(address.key, value.value)
                is PreferenceValue.DoubleValue -> error("SharedPreferences 不支持 Double")
            }
            check(editor.commit()) { "设置文件写入失败：${address.store}" }
        }
    }
    override fun sync() = BackupPreferenceStorage.sync()
}

internal class AndroidBackupDurableAccess(
    private val database: MusicDatabase,
    private val publishCaches: suspend () -> Unit,
) : BackupDurableAccess {
    private val playlistsDao get() = database.userPlaylistDao()
    private val playbackDao get() = database.playbackStatsDao()

    override suspend fun snapshot(selection: BackupSelection): ByteArray = database.withTransaction {
        BackupRoomSnapshotCodec.encode(readSnapshot(selection))
    }

    private suspend fun readSnapshot(selection: BackupSelection): BackupRoomSnapshot {
        val playlists = BackupSection.PLAYLISTS in selection.sections
        val playback = BackupSection.PLAYBACK in selection.sections
        return BackupRoomSnapshot(
            if (playlists) playlistsDao.getPlaylists() else null,
            if (playlists) playlistsDao.getSongs() else null,
            if (playback) playbackDao.getStats() else null,
            if (playback) playbackDao.getHistory(Int.MAX_VALUE) else null,
            if (playback) playbackDao.getDaily() else null,
        )
    }

    override suspend fun apply(backup: ValidatedBackup, selection: BackupSelection) = database.withTransaction {
        val state = incoming(backup, selection)
        replace(state)
    }

    override suspend fun verify(backup: ValidatedBackup, selection: BackupSelection) = database.withTransaction {
        val expected = incoming(backup, selection)
        val actual = readSnapshot(BackupSelection(buildSet {
            if (expected.playlists != null) add(BackupSection.PLAYLISTS)
            if (expected.stats != null) add(BackupSection.PLAYBACK)
        }))
        check(actual.playlists == expected.playlists && actual.playlistSongs == expected.playlistSongs) { "歌单写入校验失败" }
        check(actual.stats?.sortedBy { it.songId } == expected.stats?.sortedBy { it.songId } &&
            historyValues(actual.history) == historyValues(expected.history) &&
            actual.daily?.sortedBy { it.date } == expected.daily?.sortedBy { it.date }) { "播放统计写入校验失败" }
    }

    override suspend fun rollback(snapshot: ByteArray) {
        val original = BackupRoomSnapshotCodec.decode(snapshot)
        database.withTransaction {
            replace(original)
            val actual = readSnapshot(BackupSelection(buildSet {
                if (original.playlists != null) add(BackupSection.PLAYLISTS)
                if (original.stats != null) add(BackupSection.PLAYBACK)
            }))
            check(actual == original) { "数据库回滚校验失败" }
        }
    }

    private suspend fun replace(state: BackupRoomSnapshot) {
        state.playlists?.let { playlistsDao.replaceAll(it, requireNotNull(state.playlistSongs)) }
        state.stats?.let { playbackDao.replaceAll(it, requireNotNull(state.history), requireNotNull(state.daily)) }
    }

    private fun incoming(backup: ValidatedBackup, selection: BackupSelection): BackupRoomSnapshot {
        val playlists = backup.playlists?.takeIf { BackupSection.PLAYLISTS in selection.sections }?.let { list ->
            if (list.any { it.id == FAVORITES_PLAYLIST_ID }) list
            else listOf(UserPlaylist(FAVORITES_PLAYLIST_ID, "我喜欢的音乐", emptyList(), 0, 0)) + list
        }
        val playback = backup.playback?.takeIf { BackupSection.PLAYBACK in selection.sections }
        return BackupRoomSnapshot(
            playlists?.mapIndexed { index, it -> UserPlaylistEntity(it.id, it.name, it.createdAt, it.updatedAt, index) },
            playlists?.flatMap { playlist -> playlist.songs.mapIndexed { index, it ->
                UserPlaylistSongEntity(playlist.id, it.key, it.id, it.title, it.artist, it.album, it.albumId,
                    it.duration, it.path, it.fileSize, it.format, it.sampleRate, it.bitRate, it.bitsPerSample,
                    it.albumArtPath, it.addedAt, index)
            } }?.sortedWith(compareBy({ it.playlistId }, { it.sortOrder })),
            playback?.stats?.map { PlaybackStatEntity(it.songId, it.title, it.artist, it.album, it.playCount, it.listenedMs, it.lastPlayedAt) },
            playback?.history?.map { PlaybackHistoryEntity(songId = it.songId, title = it.title, artist = it.artist, album = it.album, playedAt = it.playedAt) },
            playback?.daily?.map { (date, value) -> DailyListenEntity(date, value) },
        )
    }

    private fun historyValues(rows: List<PlaybackHistoryEntity>?) = rows?.map { it.copy(id = 0) }?.sortedWith(
        compareBy({ it.playedAt }, { it.songId }, { it.title }, { it.artist }, { it.album }))

    override suspend fun publish() = publishCaches()
}
