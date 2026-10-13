package com.rawsmusic.module.data.prefs

import com.rawsmusic.module.data.db.entity.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

internal data class BackupRoomSnapshot(
    val playlists: List<UserPlaylistEntity>? = null,
    val playlistSongs: List<UserPlaylistSongEntity>? = null,
    val stats: List<PlaybackStatEntity>? = null,
    val history: List<PlaybackHistoryEntity>? = null,
    val daily: List<DailyListenEntity>? = null,
)

internal object BackupRoomSnapshotCodec {
    // Entity field names are protected by the existing db.** R8 rule, unlike runtime DSP models.
    private val gson = Gson()
    fun encode(state: BackupRoomSnapshot): ByteArray = JsonObject().apply {
        addProperty("version", 6)
        state.playlists?.let { add("playlists", gson.toJsonTree(it)) }
        state.playlistSongs?.let { add("playlistSongs", gson.toJsonTree(it)) }
        state.stats?.let { add("stats", gson.toJsonTree(it)) }
        state.history?.let { add("history", gson.toJsonTree(it)) }
        state.daily?.let { add("daily", gson.toJsonTree(it)) }
    }.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size <= BackupBoundedReader.MAX_BYTES) { "数据库恢复快照过大" }
        decode(it)
    }

    fun decode(bytes: ByteArray): BackupRoomSnapshot {
        require(bytes.size <= BackupBoundedReader.MAX_BYTES) { "数据库恢复快照过大" }
        val element = BackupStrictJson.parse(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        require(element.isJsonObject) { "数据库恢复快照格式错误" }
        val root = element.asJsonObject
        require(BackupStrictJson.integer(root.get("version")) == 6L) { "数据库恢复快照版本不兼容" }
        require(root.keySet().all { it in setOf("version", "playlists", "playlistSongs", "stats", "history", "daily") }) { "数据库恢复快照包含未知分类" }
        require(root.has("playlists") == root.has("playlistSongs")) { "歌单快照不完整" }
        require(listOf("stats", "history", "daily").map(root::has).distinct().size == 1) { "播放统计快照不完整" }
        return BackupRoomSnapshot(rows(root, "playlists", UserPlaylistEntity::class.java),
            rows(root, "playlistSongs", UserPlaylistSongEntity::class.java),
            rows(root, "stats", PlaybackStatEntity::class.java),
            rows(root, "history", PlaybackHistoryEntity::class.java),
            rows(root, "daily", DailyListenEntity::class.java))
    }

    private fun <T> rows(root: JsonObject, key: String, type: Class<T>): List<T>? {
        val array = root.get(key) ?: return null
        require(array.isJsonArray) { "数据库快照分类不是数组" }
        val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        val names = fields.map { it.name }.toSet()
        return array.asJsonArray.map { row ->
            require(row.isJsonObject && row.asJsonObject.keySet() == names) { "数据库快照字段不完整" }
            fields.forEach { field ->
                val value = row.asJsonObject.get(field.name)
                when (field.type) {
                    String::class.java -> require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "数据库快照文本类型错误" }
                    java.lang.Long.TYPE -> BackupStrictJson.integer(value)
                    java.lang.Integer.TYPE -> require(BackupStrictJson.integer(value) in Int.MIN_VALUE..Int.MAX_VALUE) { "数据库快照整数溢出" }
                    else -> error("未注册的数据库快照字段类型")
                }
            }
            gson.fromJson(row, type)
        }
    }
}
