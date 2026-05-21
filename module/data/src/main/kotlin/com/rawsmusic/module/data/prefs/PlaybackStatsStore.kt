package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private const val MIN_LISTEN_MS_FOR_COUNT = 20_000L

        fun getInstance(ctx: Context): PlaybackStatsStore {
            return instance ?: synchronized(this) {
                instance ?: PlaybackStatsStore(ctx.applicationContext).also { instance = it }
            }
        }
    }

    private val lock = Any()
    private val statsFile = "playback_stats.json"
    private val historyFile = "playback_history.json"
    private val dailyFile = "playback_daily_stats.json"

    private val _stats = MutableStateFlow<List<SongPlaybackStats>>(emptyList())
    val stats: StateFlow<List<SongPlaybackStats>> = _stats.asStateFlow()

    private val _history = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val history: StateFlow<List<PlaybackHistoryEntry>> = _history.asStateFlow()

    private val _dailyListenMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dailyListenMs: StateFlow<Map<String, Long>> = _dailyListenMs.asStateFlow()

    init {
        _stats.value = loadStats()
        _history.value = loadHistory()
        _dailyListenMs.value = loadDaily()
    }

    fun recordPlay(song: AudioFile) {
        synchronized(lock) {
            val list = _stats.value.toMutableList()
            val idx = list.indexOfFirst { it.songId == song.id }
            if (idx >= 0) {
                val old = list[idx]
                list[idx] = old.copy(
                    playCount = old.playCount + 1,
                    lastPlayedAt = System.currentTimeMillis()
                )
            } else {
                list.add(SongPlaybackStats(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    playCount = 1,
                    listenedMs = 0L,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }
            saveStats(list)
            _stats.value = list

            val hist = _history.value.toMutableList()
            hist.add(0, PlaybackHistoryEntry(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                playedAt = System.currentTimeMillis()
            ))
            if (hist.size > MAX_HISTORY_ITEMS) {
                val trimmed = hist.subList(0, MAX_HISTORY_ITEMS)
                saveHistory(trimmed)
                _history.value = trimmed.toList()
            } else {
                saveHistory(hist)
                _history.value = hist
            }
        }
    }

    fun addListenTime(song: AudioFile, listenedMs: Long) {
        synchronized(lock) {
            val list = _stats.value.toMutableList()
            val idx = list.indexOfFirst { it.songId == song.id }
            if (idx >= 0) {
                val old = list[idx]
                list[idx] = old.copy(listenedMs = old.listenedMs + listenedMs)
            } else {
                list.add(SongPlaybackStats(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    playCount = 0,
                    listenedMs = listenedMs,
                    lastPlayedAt = 0L
                ))
            }
            saveStats(list)
            _stats.value = list

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val daily = _dailyListenMs.value.toMutableMap()
            daily[today] = (daily[today] ?: 0L) + listenedMs
            saveDaily(daily)
            _dailyListenMs.value = daily
        }
    }

    fun exportJson(): JSONObject {
        val json = JSONObject()
        json.put("stats", JSONArray().apply {
            _stats.value.forEach { s ->
                put(JSONObject()
                    .put("songId", s.songId).put("title", s.title)
                    .put("artist", s.artist).put("album", s.album)
                    .put("playCount", s.playCount).put("listenedMs", s.listenedMs)
                    .put("lastPlayedAt", s.lastPlayedAt))
            }
        })
        json.put("history", JSONArray().apply {
            _history.value.forEach { h ->
                put(JSONObject()
                    .put("songId", h.songId).put("title", h.title)
                    .put("artist", h.artist).put("album", h.album)
                    .put("playedAt", h.playedAt))
            }
        })
        json.put("daily", JSONObject().apply {
            _dailyListenMs.value.forEach { (k, v) -> put(k, v) }
        })
        return json
    }

    fun restoreJson(json: JSONObject) {
        synchronized(lock) {
            val statsList = mutableListOf<SongPlaybackStats>()
            val statsArr = json.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until statsArr.length()) {
                val o = statsArr.optJSONObject(i) ?: continue
                statsList.add(SongPlaybackStats(
                    songId = o.optLong("songId", 0), title = o.optString("title", ""),
                    artist = o.optString("artist", ""), album = o.optString("album", ""),
                    playCount = o.optInt("playCount", 0), listenedMs = o.optLong("listenedMs", 0),
                    lastPlayedAt = o.optLong("lastPlayedAt", 0)
                ))
            }
            saveStats(statsList)
            _stats.value = statsList

            val histList = mutableListOf<PlaybackHistoryEntry>()
            val histArr = json.optJSONArray("history") ?: JSONArray()
            for (i in 0 until histArr.length()) {
                val o = histArr.optJSONObject(i) ?: continue
                histList.add(PlaybackHistoryEntry(
                    songId = o.optLong("songId", 0), title = o.optString("title", ""),
                    artist = o.optString("artist", ""), album = o.optString("album", ""),
                    playedAt = o.optLong("playedAt", 0)
                ))
            }
            saveHistory(histList)
            _history.value = histList

            val dailyMap = mutableMapOf<String, Long>()
            val dailyObj = json.optJSONObject("daily") ?: JSONObject()
            dailyObj.keys().forEach { k -> dailyMap[k] = dailyObj.optLong(k, 0) }
            saveDaily(dailyMap)
            _dailyListenMs.value = dailyMap
        }
    }

    private fun loadStats(): List<SongPlaybackStats> {
        return loadJsonList(statsFile) { o ->
            SongPlaybackStats(
                songId = o.optLong("songId", 0), title = o.optString("title", ""),
                artist = o.optString("artist", ""), album = o.optString("album", ""),
                playCount = o.optInt("playCount", 0), listenedMs = o.optLong("listenedMs", 0),
                lastPlayedAt = o.optLong("lastPlayedAt", 0)
            )
        }
    }

    private fun loadHistory(): List<PlaybackHistoryEntry> {
        return loadJsonList(historyFile) { o ->
            PlaybackHistoryEntry(
                songId = o.optLong("songId", 0), title = o.optString("title", ""),
                artist = o.optString("artist", ""), album = o.optString("album", ""),
                playedAt = o.optLong("playedAt", 0)
            )
        }
    }

    private fun loadDaily(): Map<String, Long> {
        val file = context.filesDir.resolve(dailyFile)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, Long>()
            obj.keys().forEach { k -> map[k] = obj.optLong(k, 0) }
            map
        } catch (_: Exception) { emptyMap() }
    }

    private fun <T> loadJsonList(fileName: String, transform: (JSONObject) -> T): List<T> {
        val file = context.filesDir.resolve(fileName)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<T>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(transform(it)) }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun saveStats(list: List<SongPlaybackStats>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject()
                .put("songId", s.songId).put("title", s.title)
                .put("artist", s.artist).put("album", s.album)
                .put("playCount", s.playCount).put("listenedMs", s.listenedMs)
                .put("lastPlayedAt", s.lastPlayedAt))
        }
        context.filesDir.resolve(statsFile).writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun saveHistory(list: List<PlaybackHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(JSONObject()
                .put("songId", h.songId).put("title", h.title)
                .put("artist", h.artist).put("album", h.album)
                .put("playedAt", h.playedAt))
        }
        context.filesDir.resolve(historyFile).writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun saveDaily(map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        context.filesDir.resolve(dailyFile).writeText(obj.toString(), Charsets.UTF_8)
    }
}
