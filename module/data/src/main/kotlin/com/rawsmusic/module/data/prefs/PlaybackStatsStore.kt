package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.DailyListenEntity
import com.rawsmusic.module.data.db.entity.PlaybackHistoryEntity
import com.rawsmusic.module.data.db.entity.PlaybackStatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SongPlaybackStats(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val playCount: Int,
    val listenedMs: Long,
    val lastPlayedAt: Long
)

data class PlaybackHistoryEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val playedAt: Long
)

class PlaybackStatsStore private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: PlaybackStatsStore? = null
        private const val MAX_HISTORY_ITEMS = 200

        fun getInstance(ctx: Context): PlaybackStatsStore {
            return instance ?: synchronized(this) {
                instance ?: PlaybackStatsStore(ctx.applicationContext).also { instance = it }
            }
        }
    }

    private val lock = Any()
    private val statsFile = context.filesDir.resolve("playback_stats.json")
    private val historyFile = context.filesDir.resolve("playback_history.json")
    private val dailyFile = context.filesDir.resolve("playback_daily_stats.json")
    private val dao = MusicDatabase.getInstance(context).playbackStatsDao()
    private val databaseDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "RawS-PlaybackStatsRoom").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
    private val databaseScope = CoroutineScope(SupervisorJob() + databaseDispatcher)

    private val _stats = MutableStateFlow<List<SongPlaybackStats>>(emptyList())
    val stats: StateFlow<List<SongPlaybackStats>> = _stats.asStateFlow()

    private val _history = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val history: StateFlow<List<PlaybackHistoryEntry>> = _history.asStateFlow()

    private val _dailyListenMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dailyListenMs: StateFlow<Map<String, Long>> = _dailyListenMs.asStateFlow()

    init {
        val legacyStats = loadLegacyStats()
        val legacyHistory = loadLegacyHistory()
        val legacyDaily = loadLegacyDaily()
        if (legacyStats.isNotEmpty() || legacyHistory.isNotEmpty() || legacyDaily.isNotEmpty()) {
            _stats.value = legacyStats
            _history.value = legacyHistory.take(MAX_HISTORY_ITEMS)
            _dailyListenMs.value = legacyDaily
        }
        databaseScope.launch {
            if (statsFile.exists() || historyFile.exists() || dailyFile.exists()) {
                dao.replaceAll(
                    stats = _stats.value.map(SongPlaybackStats::toEntity),
                    history = _history.value.map(PlaybackHistoryEntry::toEntity),
                    daily = _dailyListenMs.value.map { (date, listenedMs) ->
                        DailyListenEntity(date, listenedMs)
                    }
                )
                deleteLegacyFiles()
            } else {
                loadRoomSnapshot()
            }
        }
    }

    fun recordPlay(song: AudioFile) {
        val now = System.currentTimeMillis()
        val updatedStat: SongPlaybackStats
        val historyEntry = PlaybackHistoryEntry(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            playedAt = now
        )
        synchronized(lock) {
            val list = _stats.value.toMutableList()
            val index = list.indexOfFirst { it.songId == song.id }
            updatedStat = if (index >= 0) {
                list[index].copy(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    playCount = list[index].playCount + 1,
                    lastPlayedAt = now
                ).also { list[index] = it }
            } else {
                SongPlaybackStats(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    playCount = 1,
                    listenedMs = 0L,
                    lastPlayedAt = now
                ).also(list::add)
            }
            _stats.value = list
            _history.value = buildList {
                add(historyEntry)
                addAll(_history.value)
            }.take(MAX_HISTORY_ITEMS)
        }
        databaseScope.launch {
            dao.upsertStat(updatedStat.toEntity())
            dao.insertHistory(historyEntry.toEntity())
            dao.trimHistory(MAX_HISTORY_ITEMS)
        }
    }

    fun addListenTime(song: AudioFile, listenedMs: Long) {
        if (listenedMs <= 0L) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val updatedStat: SongPlaybackStats
        val updatedDailyMs: Long
        synchronized(lock) {
            val list = _stats.value.toMutableList()
            val index = list.indexOfFirst { it.songId == song.id }
            updatedStat = if (index >= 0) {
                list[index].copy(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    listenedMs = list[index].listenedMs + listenedMs
                ).also { list[index] = it }
            } else {
                SongPlaybackStats(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    playCount = 0,
                    listenedMs = listenedMs,
                    lastPlayedAt = 0L
                ).also(list::add)
            }
            _stats.value = list
            val daily = _dailyListenMs.value.toMutableMap()
            updatedDailyMs = (daily[today] ?: 0L) + listenedMs
            daily[today] = updatedDailyMs
            _dailyListenMs.value = daily
        }
        databaseScope.launch {
            dao.upsertStat(updatedStat.toEntity())
            dao.upsertDaily(DailyListenEntity(today, updatedDailyMs))
        }
    }

    fun exportJson(): JSONObject = JSONObject().apply {
        put("stats", JSONArray().apply {
            _stats.value.forEach { stat ->
                put(JSONObject()
                    .put("songId", stat.songId)
                    .put("title", stat.title)
                    .put("artist", stat.artist)
                    .put("album", stat.album)
                    .put("playCount", stat.playCount)
                    .put("listenedMs", stat.listenedMs)
                    .put("lastPlayedAt", stat.lastPlayedAt))
            }
        })
        put("history", JSONArray().apply {
            _history.value.forEach { entry ->
                put(JSONObject()
                    .put("songId", entry.songId)
                    .put("title", entry.title)
                    .put("artist", entry.artist)
                    .put("album", entry.album)
                    .put("playedAt", entry.playedAt))
            }
        })
        put("daily", JSONObject().apply {
            _dailyListenMs.value.forEach { (date, listenedMs) -> put(date, listenedMs) }
        })
    }

    /** Serialize only after earlier Room initialization/writes on the single store dispatcher complete. */
    suspend fun exportJsonForBackup(): JSONObject = withContext(databaseDispatcher) {
        exportJson()
    }

    fun restoreJson(json: JSONObject) {
        val payload = parseRestorePayload(json)
        applyRestorePayloadToMemory(payload)
        databaseScope.launch {
            persistRestorePayload(payload)
        }
    }

    /**
     * Restore and wait for the Room write to finish. Backup/import uses this path so success is not
     * reported while persistence is still queued on another coroutine.
     */
    suspend fun restoreJsonAndVerify(json: JSONObject): Boolean {
        val payload = parseRestorePayload(json)
        applyRestorePayloadToMemory(payload)
        return withContext(databaseDispatcher) {
            persistRestorePayload(payload)
            val storedStats = dao.getStats().map(PlaybackStatEntity::toModel)
            val storedHistory = dao.getHistory(MAX_HISTORY_ITEMS).map(PlaybackHistoryEntity::toModel)
            val storedDaily = dao.getDaily().associate { it.date to it.listenedMs }
            sameStats(storedStats, payload.stats) &&
                sameHistory(storedHistory, payload.history) &&
                storedDaily == payload.daily
        }
    }


    private fun sameStats(first: List<SongPlaybackStats>, second: List<SongPlaybackStats>): Boolean =
        first.sortedBy(SongPlaybackStats::songId) == second.sortedBy(SongPlaybackStats::songId)

    private fun sameHistory(first: List<PlaybackHistoryEntry>, second: List<PlaybackHistoryEntry>): Boolean {
        val order = compareBy<PlaybackHistoryEntry>(
            PlaybackHistoryEntry::playedAt,
            PlaybackHistoryEntry::songId,
            PlaybackHistoryEntry::title,
            PlaybackHistoryEntry::artist,
            PlaybackHistoryEntry::album,
        )
        return first.sortedWith(order) == second.sortedWith(order)
    }

    private data class RestorePayload(
        val stats: List<SongPlaybackStats>,
        val history: List<PlaybackHistoryEntry>,
        val daily: Map<String, Long>,
    )

    private fun parseRestorePayload(json: JSONObject): RestorePayload = RestorePayload(
        stats = parseStats(json.optJSONArray("stats") ?: JSONArray()),
        history = parseHistory(json.optJSONArray("history") ?: JSONArray()).take(MAX_HISTORY_ITEMS),
        daily = buildMap {
            val dailyJson = json.optJSONObject("daily") ?: JSONObject()
            dailyJson.keys().forEach { date -> put(date, dailyJson.optLong(date, 0L)) }
        },
    )

    private fun applyRestorePayloadToMemory(payload: RestorePayload) {
        synchronized(lock) {
            _stats.value = payload.stats
            _history.value = payload.history
            _dailyListenMs.value = payload.daily
        }
    }

    private suspend fun persistRestorePayload(payload: RestorePayload) {
        dao.replaceAll(
            stats = payload.stats.map(SongPlaybackStats::toEntity),
            history = payload.history.map(PlaybackHistoryEntry::toEntity),
            daily = payload.daily.map { (date, listenedMs) -> DailyListenEntity(date, listenedMs) },
        )
        deleteLegacyFiles()
    }

    private suspend fun loadRoomSnapshot() {
        val roomStats = dao.getStats().map(PlaybackStatEntity::toModel)
        val roomHistory = dao.getHistory(MAX_HISTORY_ITEMS).map(PlaybackHistoryEntity::toModel)
        val roomDaily = dao.getDaily().associate { it.date to it.listenedMs }
        synchronized(lock) {
            _stats.value = roomStats
            _history.value = roomHistory
            _dailyListenMs.value = roomDaily
        }
    }

    private fun loadLegacyStats(): List<SongPlaybackStats> =
        loadJsonArray(statsFile)?.let(::parseStats).orEmpty()

    private fun loadLegacyHistory(): List<PlaybackHistoryEntry> =
        loadJsonArray(historyFile)?.let(::parseHistory).orEmpty()

    private fun loadLegacyDaily(): Map<String, Long> {
        if (!dailyFile.exists()) return emptyMap()
        return runCatching {
            buildMap {
                val json = JSONObject(dailyFile.readText(Charsets.UTF_8))
                json.keys().forEach { date -> put(date, json.optLong(date, 0L)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadJsonArray(file: java.io.File): JSONArray? {
        if (!file.exists()) return null
        return runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun parseStats(array: JSONArray): List<SongPlaybackStats> = buildList {
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            add(SongPlaybackStats(
                songId = item.optLong("songId", 0L),
                title = item.optString("title", ""),
                artist = item.optString("artist", ""),
                album = item.optString("album", ""),
                playCount = item.optInt("playCount", 0),
                listenedMs = item.optLong("listenedMs", 0L),
                lastPlayedAt = item.optLong("lastPlayedAt", 0L)
            ))
        }
    }

    private fun parseHistory(array: JSONArray): List<PlaybackHistoryEntry> = buildList {
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            add(PlaybackHistoryEntry(
                songId = item.optLong("songId", 0L),
                title = item.optString("title", ""),
                artist = item.optString("artist", ""),
                album = item.optString("album", ""),
                playedAt = item.optLong("playedAt", 0L)
            ))
        }
    }

    private fun deleteLegacyFiles() {
        statsFile.delete()
        historyFile.delete()
        dailyFile.delete()
    }
}

private fun SongPlaybackStats.toEntity() = PlaybackStatEntity(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    playCount = playCount,
    listenedMs = listenedMs,
    lastPlayedAt = lastPlayedAt
)

private fun PlaybackStatEntity.toModel() = SongPlaybackStats(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    playCount = playCount,
    listenedMs = listenedMs,
    lastPlayedAt = lastPlayedAt
)

private fun PlaybackHistoryEntry.toEntity() = PlaybackHistoryEntity(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    playedAt = playedAt
)

private fun PlaybackHistoryEntity.toModel() = PlaybackHistoryEntry(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    playedAt = playedAt
)
