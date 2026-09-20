package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import java.util.Locale

/** Reuses enriched Room rows when a cold-start directory probe reports the same file. */
internal class IncrementalBaselineIndex(baselineSongs: List<AudioFile>) {
    private val songsByPath = baselineSongs.groupBy { it.normalizedScanPath() }

    fun reusableSongs(raw: AudioFile): List<AudioFile>? {
        if (raw.fileSize <= 0L || raw.dateModified <= 0L) return null
        val candidates = songsByPath[raw.normalizedScanPath()].orEmpty()
        if (candidates.isEmpty()) return null
        if (candidates.any { old ->
                old.fileSize != raw.fileSize ||
                    old.dateModified <= 0L ||
                    old.dateModified != raw.dateModified
            }
        ) return null

        // One physical CUE file may own several database rows; preserve the whole expansion.
        return candidates.sortedWith(compareBy<AudioFile> { it.cueTrackIndex }.thenBy { it.cueOffsetMs })
    }
}

/** Converts expanded database rows back to one physical-file probe row per path. */
internal fun List<AudioFile>.physicalScanRows(): List<AudioFile> =
    groupBy { it.normalizedScanPath() }
        .values
        .map { rows ->
            rows.firstOrNull { it.cueTrackIndex <= 0 && it.cueOffsetMs <= 0L }
                ?: rows.first()
        }

private fun AudioFile.normalizedScanPath(): String =
    path.trim().replace('\\', '/').lowercase(Locale.ROOT)
