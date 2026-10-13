package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import java.util.Locale
import java.net.URI
import java.net.URLDecoder

internal fun normalizedScanPath(path: String): String =
    if (path.startsWith("content://", ignoreCase = true)) path.trim()
    else path.trim().replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

internal fun scanRowKey(song: AudioFile): String =
    "${normalizedScanPath(song.path)}|${song.cueTrackIndex}|${song.cueOffsetMs}"

class ScanPathIndex(rows: List<AudioFile>) {
    private val paths = rows.groupBy { normalizedScanPath(it.path) }
    private val direct = rows.groupBy { directoryIdentity(it.path).substringBeforeLast('/', "") }
    private val ancestors = HashSet<String>().apply {
        for (directory in direct.keys) {
            var parent = directory
            while (parent.isNotEmpty()) {
                add(parent)
                val next = parent.substringBeforeLast('/', "")
                if (next == parent) break
                parent = next
            }
        }
    }
    fun rowsForPath(path: String): List<AudioFile> = paths[normalizedScanPath(path)].orEmpty()
    fun directRows(directory: String): List<AudioFile> = direct[directoryIdentity(directory)].orEmpty()
    fun hasRowsUnder(directory: String): Boolean = directoryIdentity(directory) in ancestors

    private fun directoryIdentity(path: String): String {
        if (!path.startsWith("content://", ignoreCase = true)) return normalizedScanPath(path)
        return runCatching {
            val uri = URI(path)
            val raw = uri.rawPath
            val document = if ("/document/" in raw) raw.substringAfter("/document/")
                else raw.substringAfter("/tree/")
            "content://${uri.authority}/" + URLDecoder.decode(document.replace("+", "%2B"), "UTF-8").trimEnd('/')
        }.getOrDefault(path)
    }
}
