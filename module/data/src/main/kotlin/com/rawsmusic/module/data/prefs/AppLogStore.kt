package com.rawsmusic.module.data.prefs

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val rawLine: String
) {
    val levelPriority: Int get() = when (level) {
        "E" -> 4
        "W" -> 3
        "I" -> 2
        "D" -> 1
        else -> 0
    }
}

data class LogStats(
    val total: Int,
    val errorCount: Int,
    val warnCount: Int,
    val infoCount: Int,
    val debugCount: Int,
    val topTags: List<Pair<String, Int>>
)

object AppLogStore {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val _stats = MutableStateFlow(LogStats(0, 0, 0, 0, 0, emptyList()))
    val stats: StateFlow<LogStats> = _stats

    private val lineRegex = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})] ([DIWE])/([^:]+): (.+)$""")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val content = AppLogger.getLogContent() ?: return@withContext
        val lines = content.lines()
        val parsed = mutableListOf<LogEntry>()
        val continuation = StringBuilder()

        var current: LogEntry? = null

        for (line in lines) {
            val match = lineRegex.matchEntire(line)
            if (match != null) {
                if (current != null) {
                    if (continuation.isNotEmpty()) {
                        parsed.add(current.copy(message = current.message + "\n" + continuation.toString().trimEnd()))
                        continuation.clear()
                    } else {
                        parsed.add(current)
                    }
                }
                current = LogEntry(
                    timestamp = match.groupValues[1],
                    level = match.groupValues[2],
                    tag = match.groupValues[3],
                    message = match.groupValues[4],
                    rawLine = line
                )
            } else if (current != null && line.isNotBlank()) {
                continuation.appendLine(line)
            }
        }

        if (current != null) {
            if (continuation.isNotEmpty()) {
                parsed.add(current.copy(message = current.message + "\n" + continuation.toString().trimEnd()))
            } else {
                parsed.add(current)
            }
        }

        _entries.value = parsed
        computeStats(parsed)
    }

    private fun computeStats(entries: List<LogEntry>) {
        var errorCount = 0
        var warnCount = 0
        var infoCount = 0
        var debugCount = 0
        val tagCounts = mutableMapOf<String, Int>()

        for (entry in entries) {
            when (entry.level) {
                "E" -> errorCount++
                "W" -> warnCount++
                "I" -> infoCount++
                "D" -> debugCount++
            }
            tagCounts[entry.tag] = (tagCounts[entry.tag] ?: 0) + 1
        }

        val topTags = tagCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        _stats.value = LogStats(
            total = entries.size,
            errorCount = errorCount,
            warnCount = warnCount,
            infoCount = infoCount,
            debugCount = debugCount,
            topTags = topTags
        )
    }

    fun filter(
        entries: List<LogEntry>,
        levelFilter: String? = null,
        tagFilter: String? = null,
        searchQuery: String? = null
    ): List<LogEntry> {
        var result = entries
        if (!levelFilter.isNullOrBlank()) {
            result = result.filter { it.level == levelFilter }
        }
        if (!tagFilter.isNullOrBlank()) {
            result = result.filter { it.tag.equals(tagFilter, ignoreCase = true) }
        }
        if (!searchQuery.isNullOrBlank()) {
            val q = searchQuery.lowercase()
            result = result.filter {
                it.message.lowercase().contains(q) ||
                it.tag.lowercase().contains(q) ||
                it.rawLine.lowercase().contains(q)
            }
        }
        return result
    }

    fun clearLog() {
        AppLogger.clearLog()
        _entries.value = emptyList()
        _stats.value = LogStats(0, 0, 0, 0, 0, emptyList())
    }

    fun getTags(): Set<String> {
        return _entries.value.map { it.tag }.toSet()
    }
}
