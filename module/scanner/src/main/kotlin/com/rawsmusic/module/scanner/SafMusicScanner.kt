package com.rawsmusic.module.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.IOException

private const val TAG = "SafMusicScanner"

/** Scans persisted document trees independently from MediaStore. */
object SafMusicScanner {

    data class ScanReport(
        val songs: List<AudioFile>,
        val successfulUris: Set<String>,
        val failedUris: Map<String, String>,
        val skippedDirectoryCount: Int = 0,
        val directorySnapshots: Map<String, Long> = emptyMap()
    ) {
        val allConfiguredSourcesSucceeded: Boolean get() = failedUris.isEmpty()
        val hasSuccessfulSource: Boolean get() = successfulUris.isNotEmpty()
    }

    private data class SafEntry(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean
    )

    private data class TreeScanReport(
        val skippedDirectories: Set<String> = emptySet(),
        val directorySnapshots: Map<String, Long> = emptyMap()
    )

    private class TreeScanAccumulator {
        val skippedDirectories = linkedSetOf<String>()
        val directorySnapshots = linkedMapOf<String, Long>()
    }

    fun scanSelectedFolders(context: Context): List<AudioFile> =
        scanSelectedFoldersReport(context, quickScan = false).songs

    /** Reads tags only after the cold-start fingerprint gate marks this SAF file as changed/new. */
    fun enrichSong(context: Context, raw: AudioFile): AudioFile {
        val uri = runCatching { Uri.parse(raw.path) }.getOrNull() ?: return raw
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: raw.duration
            raw.copy(
                duration = duration,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    .orEmpty().ifBlank { raw.title },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .orEmpty().ifBlank { raw.artist.ifBlank { "未知艺术家" } },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    .orEmpty().ifBlank { raw.album.ifBlank { "未知专辑" } },
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    .orEmpty().ifBlank { raw.albumArtist },
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    .orEmpty().ifBlank { raw.composer },
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    .orEmpty().ifBlank { raw.genre },
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.take(4)?.toIntOrNull() ?: raw.year,
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull() ?: raw.trackNumber
            )
        } catch (error: Throwable) {
            Log.w(TAG, "incremental SAF enrich failed: $uri", error)
            raw
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun scanSelectedFoldersReport(
        context: Context,
        quickScan: Boolean = false,
        incrementalDirectoryScan: Boolean = false,
        baselineSongs: List<AudioFile> = emptyList()
    ): ScanReport {
        val result = mutableListOf<AudioFile>()
        val successful = linkedSetOf<String>()
        val failed = linkedMapOf<String, String>()
        var skippedDirectoryCount = 0
        val directorySnapshots = linkedMapOf<String, Long>()
        val directoryState = if (incrementalDirectoryScan) DirectoryScanState.index() else null

        AppPreferences.Scanner.musicFolderUris.forEach { rawUri ->
            val treeUri = runCatching { Uri.parse(rawUri) }.getOrNull()
            if (treeUri == null) {
                failed[rawUri] = "URI 无效"
                return@forEach
            }

            val before = result.size
            val contractResult = runCatching {
                scanTreeWithDocumentsContract(
                    context,
                    treeUri,
                    result,
                    quickScan,
                    incrementalDirectoryScan,
                    baselineSongs,
                    directoryState
                )
            }

            if (contractResult.isFailure) {
                Log.w(TAG, "DocumentsContract scan failed, trying DocumentFile: $rawUri", contractResult.exceptionOrNull())
                while (result.size > before) result.removeAt(result.lastIndex)
                val fallbackResult = runCatching {
                    scanTreeWithDocumentFile(
                        context,
                        treeUri,
                        result,
                        quickScan,
                        incrementalDirectoryScan,
                        baselineSongs,
                        directoryState
                    )
                }
                if (fallbackResult.isFailure) {
                    while (result.size > before) result.removeAt(result.lastIndex)
                    val reason = fallbackResult.exceptionOrNull()?.message
                        ?: contractResult.exceptionOrNull()?.message
                        ?: "目录无法读取"
                    failed[rawUri] = reason
                    Log.w(TAG, "SAF folder unavailable: $rawUri reason=$reason", fallbackResult.exceptionOrNull())
                    return@forEach
                }
                val report = fallbackResult.getOrThrow()
                skippedDirectoryCount += report.skippedDirectories.size
                directorySnapshots.putAll(report.directorySnapshots)
            }

            if (contractResult.isSuccess) {
                val report = contractResult.getOrThrow()
                skippedDirectoryCount += report.skippedDirectories.size
                directorySnapshots.putAll(report.directorySnapshots)
            }

            successful += rawUri
        }

        if (incrementalDirectoryScan) DirectoryScanState.commit(directorySnapshots)

        return ScanReport(
            songs = result.distinctBy(::scanIdentity),
            successfulUris = successful,
            failedUris = failed,
            skippedDirectoryCount = skippedDirectoryCount,
            directorySnapshots = directorySnapshots
        )
    }

    private fun scanTreeWithDocumentsContract(
        context: Context,
        treeUri: Uri,
        out: MutableList<AudioFile>,
        quickScan: Boolean,
        incrementalDirectoryScan: Boolean,
        baselineSongs: List<AudioFile>,
        directoryState: DirectoryScanState.Index?
    ): TreeScanReport {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val accumulator = TreeScanAccumulator()
        val rootModifiedAt = readDocumentLastModified(context, treeUri, rootDocumentId)
        scanDocumentChildren(
            context,
            treeUri,
            rootDocumentId,
            rootModifiedAt,
            out,
            HashSet(),
            quickScan,
            incrementalDirectoryScan,
            baselineSongs,
            accumulator,
            directoryState
        )
        appendReusedBaselineSongs(out, baselineSongs, accumulator.skippedDirectories)
        return TreeScanReport(accumulator.skippedDirectories, accumulator.directorySnapshots)
    }

    private fun scanDocumentChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        folderModifiedAt: Long,
        out: MutableList<AudioFile>,
        visited: MutableSet<String>,
        quickScan: Boolean,
        incrementalDirectoryScan: Boolean,
        baselineSongs: List<AudioFile>,
        accumulator: TreeScanAccumulator,
        directoryState: DirectoryScanState.Index?
    ) {
        if (!visited.add(parentDocumentId)) return

        val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val hasBaseline = baselineSongs.any { isSongUnderSafDirectory(it.path, folderUri) }
        val skipUnchangedFiles = incrementalDirectoryScan && directoryState?.canSkip(
                DirectoryScanState.safKey(folderUri.toString()),
                folderModifiedAt,
                hasBaseline
            ) == true
        if (skipUnchangedFiles) accumulator.skippedDirectories += folderUri.toString()
        if (folderModifiedAt > 0L) {
            accumulator.directorySnapshots[DirectoryScanState.safKey(folderUri.toString())] = folderModifiedAt
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Provider 返回空目录游标: $childrenUri")

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (it.moveToNext()) {
                val documentId = it.getString(idIndex) ?: continue
                val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex).orEmpty() else ""
                if (shouldSkipName(name)) continue
                val mime = if (mimeIndex >= 0 && !it.isNull(mimeIndex)) it.getString(mimeIndex).orEmpty() else ""
                val entry = SafEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    documentId = documentId,
                    name = name,
                    mimeType = mime,
                    size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L,
                    lastModified = if (modifiedIndex >= 0 && !it.isNull(modifiedIndex)) it.getLong(modifiedIndex) else 0L,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                )

                if (entry.isDirectory) {
                    scanDocumentChildren(
                        context,
                        treeUri,
                        entry.documentId,
                        entry.lastModified,
                        out,
                        visited,
                        quickScan,
                        incrementalDirectoryScan,
                        baselineSongs,
                        accumulator,
                        directoryState
                    )
                } else if (isSupportedAudio(entry.name, entry.mimeType) &&
                    (!skipUnchangedFiles || shouldScanSafFile(entry, baselineSongs))
                ) {
                    readAudioFile(context, entry, quickScan)?.let(out::add)
                }
            }
        }
    }

    private fun scanTreeWithDocumentFile(
        context: Context,
        treeUri: Uri,
        out: MutableList<AudioFile>,
        quickScan: Boolean,
        incrementalDirectoryScan: Boolean,
        baselineSongs: List<AudioFile>,
        directoryState: DirectoryScanState.Index?
    ): TreeScanReport {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法创建 DocumentFile")
        if (!root.exists() || !root.canRead()) throw IOException("SAF 授权已失效或目录不可读")
        val accumulator = TreeScanAccumulator()
        scanDocumentFileRecursive(
            context,
            root,
            out,
            HashSet(),
            quickScan,
            incrementalDirectoryScan,
            baselineSongs,
            accumulator,
            directoryState
        )
        appendReusedBaselineSongs(out, baselineSongs, accumulator.skippedDirectories)
        return TreeScanReport(accumulator.skippedDirectories, accumulator.directorySnapshots)
    }

    private fun scanDocumentFileRecursive(
        context: Context,
        folder: DocumentFile,
        out: MutableList<AudioFile>,
        visitedFolders: MutableSet<String>,
        quickScan: Boolean,
        incrementalDirectoryScan: Boolean,
        baselineSongs: List<AudioFile>,
        accumulator: TreeScanAccumulator,
        directoryState: DirectoryScanState.Index?
    ) {
        val folderUri = folder.uri.toString()
        if (!visitedFolders.add(folderUri)) return
        val folderModifiedAt = folder.lastModified()
        val hasBaseline = baselineSongs.any { isSongUnderSafDirectory(it.path, folder.uri) }
        val skipUnchangedFiles = incrementalDirectoryScan && directoryState?.canSkip(
                DirectoryScanState.safKey(folderUri),
                folderModifiedAt,
                hasBaseline
            ) == true
        if (skipUnchangedFiles) accumulator.skippedDirectories += folderUri
        if (folderModifiedAt > 0L) {
            accumulator.directorySnapshots[DirectoryScanState.safKey(folderUri)] = folderModifiedAt
        }
        folder.listFiles().forEach { file ->
            val name = file.name.orEmpty()
            if (shouldSkipName(name)) return@forEach
            when {
                file.isDirectory -> scanDocumentFileRecursive(
                    context,
                    file,
                    out,
                    visitedFolders,
                    quickScan,
                    incrementalDirectoryScan,
                    baselineSongs,
                    accumulator,
                    directoryState
                )
                file.isFile && isSupportedAudio(name, file.type.orEmpty()) &&
                    (!skipUnchangedFiles || shouldScanSafFile(
                        SafEntry(
                            uri = file.uri,
                            documentId = file.uri.toString(),
                            name = name,
                            mimeType = file.type.orEmpty(),
                            size = file.length(),
                            lastModified = file.lastModified(),
                            isDirectory = false
                        ),
                        baselineSongs
                    )
                ) -> {
                    readAudioFile(
                        context,
                        SafEntry(
                            uri = file.uri,
                            documentId = file.uri.toString(),
                            name = name,
                            mimeType = file.type.orEmpty(),
                            size = file.length(),
                            lastModified = file.lastModified(),
                            isDirectory = false
                        ),
                        quickScan
                    )?.let(out::add)
                }
            }
        }
    }

    private fun readDocumentLastModified(context: Context, treeUri: Uri, documentId: String): Long {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return runCatching {
            context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).coerceAtLeast(0L) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun appendReusedBaselineSongs(
        out: MutableList<AudioFile>,
        baselineSongs: List<AudioFile>,
        skippedDirectories: Set<String>
    ) {
        if (skippedDirectories.isEmpty()) return
        baselineSongs.physicalScanRows().filter { song ->
            skippedDirectories.any { directory -> isSongDirectlyUnderSafDirectory(song.path, Uri.parse(directory)) }
        }.forEach(out::add)
    }

    private fun shouldScanSafFile(entry: SafEntry, baselineSongs: List<AudioFile>): Boolean {
        val candidates = baselineSongs.filter { it.path.equals(entry.uri.toString(), ignoreCase = true) }
        return candidates.isEmpty() || candidates.any { old ->
            old.fileSize != entry.size ||
                old.dateModified <= 0L ||
                old.dateModified != entry.lastModified
        }
    }

    private fun isSongUnderSafDirectory(songPath: String, directoryUri: Uri): Boolean {
        if (!songPath.startsWith("content://", ignoreCase = true)) return false
        val songUri = runCatching { Uri.parse(songPath) }.getOrNull() ?: return false
        if (songUri.authority != directoryUri.authority) return false
        val directoryDocumentId = runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull()
        val songDocumentId = runCatching { DocumentsContract.getDocumentId(songUri) }.getOrNull()
        if (!directoryDocumentId.isNullOrBlank() && !songDocumentId.isNullOrBlank()) {
            return songDocumentId == directoryDocumentId || songDocumentId.startsWith("$directoryDocumentId/")
        }
        return songUri.toString().startsWith(directoryUri.toString().trimEnd('/'))
    }

    private fun isSongDirectlyUnderSafDirectory(songPath: String, directoryUri: Uri): Boolean {
        if (!songPath.startsWith("content://", ignoreCase = true)) return false
        val songUri = runCatching { Uri.parse(songPath) }.getOrNull() ?: return false
        if (songUri.authority != directoryUri.authority) return false
        val directoryDocumentId = runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull()
        val songDocumentId = runCatching { DocumentsContract.getDocumentId(songUri) }.getOrNull()
        if (!directoryDocumentId.isNullOrBlank() && !songDocumentId.isNullOrBlank()) {
            return songDocumentId.substringBeforeLast('/', missingDelimiterValue = "") == directoryDocumentId
        }
        return false
    }

    private fun scanIdentity(song: AudioFile): String = buildString {
        append(song.path.trim().lowercase())
        append('|').append(song.cueTrackIndex)
        append('|').append(song.cueOffsetMs)
    }

    private fun shouldSkipName(name: String): Boolean =
        name == "." || name == ".." || name.startsWith("._") || name.startsWith(".")

    private fun isSupportedAudio(name: String, rawMime: String): Boolean {
        val mime = rawMime.lowercase()
        if (AppPreferences.Scanner.ignoreVideoFormats) {
            if (mime.startsWith("video/")) return false
            if (name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) return false
        }
        if (mime.startsWith("audio/")) return true
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    private fun readAudioFile(context: Context, entry: SafEntry, quickScan: Boolean): AudioFile? {
        if (quickScan) return quickAudioFile(entry)
        val retriever = MediaMetadataRetriever()
        var metadataReadable = false
        var durationMs = 0L
        var title = entry.name.substringBeforeLast('.')
        var artist = "未知艺术家"
        var album = "未知专辑"
        var albumArtist = ""
        var composer = ""
        var genre = ""
        var year = 0
        var track = 0

        try {
            retriever.setDataSource(context, entry.uri)
            metadataReadable = true
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank { title }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().ifBlank { artist }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().ifBlank { album }
            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty()
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty()
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
            track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull() ?: 0
        } catch (error: Throwable) {
            // Some vendor providers expose a readable descriptor but reject MMR seek patterns.
            val descriptorReadable = runCatching {
                context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!descriptorReadable) {
                Log.w(TAG, "read SAF audio failed: ${entry.uri}", error)
                return null
            }
            Log.w(TAG, "SAF metadata unavailable, keeping minimal record: ${entry.uri}", error)
        } finally {
            runCatching { retriever.release() }
        }

        val minSec = AppPreferences.Scanner.minTrackDurationSeconds
        if (metadataReadable && minSec > 0 && durationMs in 1 until minSec * 1000L) return null

        val extension = entry.name.substringAfterLast('.', "").lowercase()
        return AudioFile(
            id = stableSafId(entry.uri),
            path = entry.uri.toString(),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            composer = composer,
            genre = genre,
            year = year,
            trackNumber = track,
            duration = durationMs,
            format = extension,
            encodingFormat = extension,
            fileSize = entry.size.coerceAtLeast(0L),
            dateAdded = System.currentTimeMillis(),
            dateModified = entry.lastModified.coerceAtLeast(0L),
            albumArtPath = entry.uri.toString()
        )
    }

    private fun quickAudioFile(entry: SafEntry): AudioFile? {
        if (entry.size <= 0L) return null
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        return AudioFile(
            id = stableSafId(entry.uri),
            path = entry.uri.toString(),
            title = entry.name.substringBeforeLast('.'),
            format = extension,
            encodingFormat = extension,
            fileSize = entry.size,
            dateAdded = entry.lastModified.coerceAtLeast(0L),
            dateModified = entry.lastModified.coerceAtLeast(0L),
            albumArtPath = entry.uri.toString()
        )
    }

    private fun stableSafId(uri: Uri): Long {
        var hash = 1125899906842597L
        uri.toString().forEach { char -> hash = 31L * hash + char.code }
        return if (hash == Long.MIN_VALUE) 0L else kotlin.math.abs(hash)
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
        "ape", "wv", "tta", "tak", "alac", "aiff", "aif",
        "dsf", "dff", "mka", "mpc", "ac3", "eac3", "ec3", "truehd", "thd", "mlp"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "avi", "webm", "flv", "wmv",
        "3gp", "3g2", "ts", "mts", "m2ts", "mpg", "mpeg"
    )
}
