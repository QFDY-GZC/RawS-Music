package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Collapses files reached through both direct storage and an ExternalStorageProvider SAF tree.
 *
 * The folder picker deliberately keeps the direct path and the persisted SAF permission. A scan
 * can therefore discover one file as both `/storage/.../song.flac` and a `content://.../document`
 * URI. Those sources must be compared by their physical identity before metadata enrichment,
 * because the initial SAF row generally contains only the file name.
 */
internal object ScanSourceDeduplicator {
    fun deduplicate(songs: List<AudioFile>): List<AudioFile> {
        val result = ArrayList<AudioFile>(songs.size)
        val indexByIdentity = HashMap<String, Int>(songs.size)

        songs.forEach { song ->
            val identity = sourceIdentity(song)
            if (identity.isBlank()) return@forEach

            val existingIndex = indexByIdentity[identity]
            if (existingIndex == null) {
                indexByIdentity[identity] = result.size
                result += song
            } else if (shouldReplace(result[existingIndex], song)) {
                // Keep the original list position so scan ordering remains deterministic.
                result[existingIndex] = song
            }
        }
        return result
    }

    internal fun canonicalSource(pathOrUri: String): String {
        val source = pathOrUri.trim()
        if (source.isEmpty()) return ""

        val physicalPath = if (source.startsWith("content://", ignoreCase = true)) {
            externalStorageDocumentPath(source) ?: source
        } else {
            source
        }
        return normalizePath(physicalPath)
    }

    private fun sourceIdentity(song: AudioFile): String {
        val cueSuffix = if (song.cueOffsetMs > 0L || song.cueTrackIndex > 0) {
            "@cue${song.cueOffsetMs}_${song.cueTrackIndex}"
        } else {
            ""
        }
        return canonicalSource(song.path) + cueSuffix
    }

    private fun externalStorageDocumentPath(uri: String): String? {
        if (!uri.contains("com.android.externalstorage.documents", ignoreCase = true)) return null

        val encodedDocumentId = uri.substringAfterLast("/document/", missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
        if (encodedDocumentId.isBlank()) return null

        val documentId = runCatching {
            // URLDecoder treats '+' as a space; in document IDs it is a valid file-name character.
            URLDecoder.decode(encodedDocumentId.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull()?.trim().orEmpty()
        if (documentId.isEmpty()) return null
        if (documentId.startsWith("raw:", ignoreCase = true)) {
            return documentId.substring(4)
        }

        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1).trimStart('/', '\\')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
            volume.isNotBlank() -> "/storage/$volume"
            else -> return null
        }
        return if (relativePath.isEmpty()) root else "$root/$relativePath"
    }

    private fun normalizePath(value: String): String {
        var normalized = value.replace('\\', '/').replace(Regex("/{2,}"), "/")
        normalized = when {
            normalized.equals("/sdcard", ignoreCase = true) -> "/storage/emulated/0"
            normalized.startsWith("/sdcard/", ignoreCase = true) ->
                "/storage/emulated/0/" + normalized.substring(8)
            normalized.equals("/storage/self/primary", ignoreCase = true) -> "/storage/emulated/0"
            normalized.startsWith("/storage/self/primary/", ignoreCase = true) ->
                "/storage/emulated/0/" + normalized.substring(22)
            else -> normalized
        }
        return normalized.trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun shouldReplace(existing: AudioFile, candidate: AudioFile): Boolean {
        val existingIsContent = existing.path.startsWith("content://", ignoreCase = true)
        val candidateIsContent = candidate.path.startsWith("content://", ignoreCase = true)
        if (existingIsContent != candidateIsContent) return existingIsContent
        return metadataScore(candidate) > metadataScore(existing)
    }

    private fun metadataScore(song: AudioFile): Int =
        listOf(song.title, song.artist, song.album, song.format, song.albumArtist).count { it.isNotBlank() } +
            listOf(song.duration, song.fileSize, song.dateModified).count { it > 0L }
}
