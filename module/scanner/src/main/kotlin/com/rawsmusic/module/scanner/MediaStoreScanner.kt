package com.rawsmusic.module.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.needsTechnicalMetadataEnrich
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.core.common.utils.BitrateNormalizer
import com.rawsmusic.core.common.utils.SampleRateNormalizer
import com.rawsmusic.module.scanner.parser.CueParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

object MediaStoreScanner {

    private const val TAG = "MediaStoreScanner"
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, AudioFile>()
    private const val PROGRESS_STEP = 50

    data class ScanOptions(
        val onlyMusic: Boolean = true,
        val minDurationMs: Long = 0L,
        val minFileSizeBytes: Long = 0L,
        val includeHiddenFiles: Boolean = true,
        val excludedPathKeywords: List<String> = emptyList(),
        val ignoreVideoFormats: Boolean = true,
        val expandCueTracks: Boolean = true,
        val enrichWorkerCount: Int? = null
    ) {
        companion object {
            fun musicLibraryDefault(): ScanOptions = ScanOptions(
                onlyMusic = true,
                minDurationMs = 30_000L,
                minFileSizeBytes = 100 * 1024L,
                includeHiddenFiles = false,
                excludedPathKeywords = listOf(
                    "/Notifications/", "/Ringtones/", "/Alarms/", "/Recordings/",
                    "/WhatsApp/", "/Telegram/", "/WeChat/", "/Android/data/"
                ),
                ignoreVideoFormats = AppPreferences.Scanner.ignoreVideoFormats,
                expandCueTracks = true
            )

            fun fromPreferences(): ScanOptions {
                val minSeconds = AppPreferences.Scanner.minTrackDurationSeconds
                return musicLibraryDefault().copy(
                    minDurationMs = if (minSeconds <= 0) 0L else minSeconds * 1000L,
                    ignoreVideoFormats = AppPreferences.Scanner.ignoreVideoFormats
                )
            }
        }
    }

    enum class ScanStage { MEDIASTORE, METADATA, CUE, DONE }

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.BITRATE,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DATA
    )

    fun scan(
        context: Context,
        customPaths: List<String> = emptyList(),
        quickScan: Boolean = false,
        options: ScanOptions = ScanOptions()
    ): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()
        val contentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val forceLegacyPathSelection = shouldUseAndroid10LegacyPathSelection(customPaths)
        val selection = buildSelection(customPaths, options, forceLegacyPathSelection)
        val selectionArgs = buildSelectionArgs(customPaths, forceLegacyPathSelection)

        android.util.Log.d("MediaStoreScanner", "scan: customPaths=$customPaths, quickScan=$quickScan, android10LegacyPath=$forceLegacyPathSelection")

        val rawFiles = mutableListOf<AudioFile>()

        try {
            contentResolver.query(uri, PROJECTION, selection, selectionArgs,
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER)?.use { cursor ->
                val total = cursor.count
                emit(ScanProgress.Started(total))
                var scanned = 0
                while (cursor.moveToNext()) {
                    val file = parseCursor(cursor)
                    if (file != null && shouldInclude(file, options)) rawFiles.add(file)
                    scanned++
                    if (scanned % PROGRESS_STEP == 0 || scanned == total) {
                        emit(ScanProgress.Progress(scanned, total, ScanStage.MEDIASTORE, "读取媒体库"))
                    }
                }
            } ?: emit(ScanProgress.Started(0))
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreScanner", "MediaStore query failed", e)
            emit(ScanProgress.Error(e.message ?: "MediaStore query failed"))
            return@flow
        }

        // Android 10 only: if the user explicitly selected a folder and MediaStore returns 0,
        // scan that selected folder directly. Do not fall back to public Music or whole storage.
        if (rawFiles.isEmpty() && shouldAttemptAndroid10SelectedFolderFallback(customPaths)) {
            val fallbackPaths = android10SelectedFallbackPaths(customPaths)

            if (fallbackPaths.isNotEmpty()) {
                android.util.Log.w(
                    "MediaStoreScanner",
                    "Android 10 MediaStore returned 0 files, fallback to selected filesystem paths only: $fallbackPaths"
                )
                emit(ScanProgress.Progress(0, 0, ScanStage.MEDIASTORE, "Android 10 已选目录兜底扫描"))
                val fallbackFiles = runCatching { scanCustomPathsByFileSystem(context, fallbackPaths) }
                    .onFailure { e -> android.util.Log.w("MediaStoreScanner", "Android 10 selected filesystem fallback failed", e) }
                    .getOrDefault(emptyList())

                rawFiles.addAll(fallbackFiles)
                android.util.Log.d("MediaStoreScanner", "Android 10 selected fallback parsed: ${fallbackFiles.size} files")
            }
        }

        android.util.Log.d("MediaStoreScanner", "MediaStore parsed: ${rawFiles.size} files")

        val scannedFiles = if (quickScan || rawFiles.isEmpty()) {
            rawFiles
        } else {
            val workerCount = options.enrichWorkerCount
                ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val semaphore = Semaphore(workerCount)
            val enriched = mutableListOf<AudioFile>()
            var processed = 0
            val chunks = rawFiles.chunked(96)
            for (chunk in chunks) {
                val enrichedChunk = coroutineScope {
                    chunk.map { rawFile ->
                        async(Dispatchers.IO) { semaphore.withPermit { enrichFile(rawFile) } }
                    }.awaitAll()
                }
                enriched.addAll(enrichedChunk)
                processed += chunk.size
                emit(ScanProgress.Progress(processed, rawFiles.size, ScanStage.METADATA, "读取音频信息"))
            }
            if (options.expandCueTracks) {
                val cueExpanded = mutableListOf<AudioFile>()
                var cueProcessed = 0
                for (file in enriched) {
                    cueExpanded.addAll(expandCueTracks(file))
                    cueProcessed++
                    if (cueProcessed % PROGRESS_STEP == 0 || cueProcessed == enriched.size) {
                        emit(ScanProgress.Progress(cueProcessed, enriched.size, ScanStage.CUE, "处理 CUE 分轨"))
                    }
                }
                cueExpanded
            } else enriched
        }

        val elapsed = System.currentTimeMillis() - startTime
        android.util.Log.d("MediaStoreScanner", "scan complete: ${scannedFiles.size} files in ${elapsed}ms")

        emit(ScanProgress.Progress(scannedFiles.size, scannedFiles.size, ScanStage.DONE, "完成"))
        emit(ScanProgress.Completed(songs = scannedFiles, found = scannedFiles.size, timeMs = elapsed))
    }.flowOn(Dispatchers.IO)

    private fun parseCursor(cursor: Cursor): AudioFile? {
        return try {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: ""
            val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: ""
            val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)) ?: ""
            val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
            val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
            val bitRateRaw = cursor.getColumnSafely(MediaStore.Audio.Media.BITRATE)
            val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)) ?: ""
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
            val rawTrack = cursor.getColumnSafely(MediaStore.Audio.Media.TRACK)
            val year = cursor.getColumnSafely(MediaStore.Audio.Media.YEAR)
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
            val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED))
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""

            if (path.isBlank() || size <= 0L) return null

            val format = guessFormat(path, mimeType)
            val albumArtPath = ""

            AudioFile(
                id = id, path = path, title = title,
                artist = sanitizeMediaStoreText(artist),
                album = sanitizeMediaStoreText(album),
                albumId = albumId, duration = duration.coerceAtLeast(0L),
                sampleRate = 0,
                bitRate = BitrateNormalizer.toBps(
                    rawBitrate = bitRateRaw,
                    durationMs = duration,
                    fileSizeBytes = size,
                    formatName = format,
                    filePath = path
                ),
                bitsPerSample = 0, format = format, fileSize = size,
                trackNumber = normalizeTrackNumber(rawTrack), year = year,
                dateAdded = dateAdded * 1000L, dateModified = dateModified * 1000L,
                albumArtPath = albumArtPath,
                genre = "", composer = "", discNumber = normalizeDiscNumber(rawTrack),
                channelCount = 0, bpm = 0, albumArtist = "",
                encodingFormat = format,
                trackGain = 0f, trackPeak = 1.0f, albumGain = 0f, albumPeak = 1.0f
            )
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreScanner", "parseCursor failed: ${e.message}", e)
            null
        }
    }

    private fun enrichFile(rawFile: AudioFile): AudioFile {
        val cacheKey = "${rawFile.path}|${rawFile.fileSize}|${rawFile.dateModified}"
        metadataCache[cacheKey]?.let { cached ->
            val merged = cached.copy(id = rawFile.id, albumId = rawFile.albumId,
                albumArtPath = rawFile.albumArtPath, dateAdded = rawFile.dateAdded,
                dateModified = rawFile.dateModified)

            if (!merged.needsTechnicalMetadataEnrich()) {
                return merged
            }
            // 缓存的技术元数据不完整，继续 enrich
            android.util.Log.d("MediaStoreScanner",
                "cached metadata incomplete, re-enrich: path=${rawFile.path}, " +
                    "sr=${merged.sampleRate}, bits=${merged.bitsPerSample}, ch=${merged.channelCount}")
        }
        if (isMediaStoreDataComplete(rawFile)) return rawFile

        return try {
            val fullInfo = FfmpegMetadataReader.readFullInfo(rawFile.path)
            val tagData = fullInfo.tags
            val streamInfo = fullInfo.stream
            val duration = resolveDurationMs(rawFile.path, streamInfo.durationMs, rawFile.duration)
            val encodingFormat = FfmpegMetadataReader.mapCodecToFormat(streamInfo.codecName, rawFile.path)
                .ifBlank { rawFile.encodingFormat.ifBlank { guessFormat(rawFile.path, "") } }
            val normalizedBitRate = BitrateNormalizer.toBps(
                rawBitrate = if (streamInfo.bitRate > 0) streamInfo.bitRate else rawFile.bitRate,
                durationMs = duration,
                fileSizeBytes = rawFile.fileSize,
                codecName = streamInfo.codecName,
                formatName = encodingFormat,
                filePath = rawFile.path
            )

            val result = rawFile.copy(
                title = chooseBetterText(rawFile.title, tagData.title),
                artist = chooseBetterText(rawFile.artist, tagData.artist),
                album = chooseBetterText(rawFile.album, tagData.album),
                duration = duration.coerceAtLeast(0L),
                sampleRate = SampleRateNormalizer.normalize(
                    rawSampleRate = if (streamInfo.sampleRate > 0) streamInfo.sampleRate else rawFile.sampleRate,
                    codecName = streamInfo.codecName,
                    formatName = encodingFormat,
                    filePath = rawFile.path
                ),
                bitRate = if (normalizedBitRate > 0) normalizedBitRate else rawFile.bitRate,
                bitsPerSample = resolveBitsPerSample(streamInfo, rawFile.path, rawFile.bitsPerSample, rawFile.encodingFormat.ifBlank { rawFile.format }),
                format = encodingFormat,
                genre = if (!tagData.genre.isMetadataDefault()) tagData.genre else rawFile.genre,
                composer = if (!tagData.composer.isMetadataDefault()) tagData.composer else rawFile.composer,
                discNumber = if (tagData.discNumber > 0) tagData.discNumber else rawFile.discNumber,
                channelCount = if (streamInfo.channels > 0) streamInfo.channels else rawFile.channelCount,
                bpm = if (tagData.bpm > 0) tagData.bpm else rawFile.bpm,
                albumArtist = if (!tagData.albumArtist.isMetadataDefault()) tagData.albumArtist else rawFile.albumArtist,
                encodingFormat = encodingFormat,
                year = if (rawFile.year > 0) rawFile.year else tagData.year,
                trackGain = if (tagData.trackGain != 0f) tagData.trackGain else rawFile.trackGain,
                trackPeak = if (tagData.trackPeak != 1.0f) tagData.trackPeak else rawFile.trackPeak,
                albumGain = if (tagData.albumGain != 0f) tagData.albumGain else rawFile.albumGain,
                albumPeak = if (tagData.albumPeak != 1.0f) tagData.albumPeak else rawFile.albumPeak
            )
            metadataCache[cacheKey] = result
            result
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreScanner", "enrichFile failed: ${rawFile.path}", e)
            rawFile
        }
    }

    fun enrichSong(song: AudioFile): AudioFile {
        val t0 = System.currentTimeMillis()
        return try {
            val fullInfo = FfmpegMetadataReader.readFullInfo(song.path)
            val tagData = fullInfo.tags
            val streamInfo = fullInfo.stream
            val duration = resolveDurationMs(song.path, streamInfo.durationMs, song.duration)
            val encodingFormat = if (streamInfo.codecName.isNotBlank())
                FfmpegMetadataReader.mapCodecToFormat(streamInfo.codecName, song.path) else song.encodingFormat
            val normalizedBitRate = BitrateNormalizer.toBps(
                rawBitrate = if (streamInfo.bitRate > 0) streamInfo.bitRate else song.bitRate,
                durationMs = duration,
                fileSizeBytes = song.fileSize,
                codecName = streamInfo.codecName,
                formatName = encodingFormat,
                filePath = song.path
            )

            val result = song.copy(
                duration = if (duration > 0) duration else song.duration,
                sampleRate = SampleRateNormalizer.normalize(
                    rawSampleRate = if (streamInfo.sampleRate > 0) streamInfo.sampleRate else song.sampleRate,
                    codecName = streamInfo.codecName,
                    formatName = encodingFormat,
                    filePath = song.path
                ),
                bitRate = if (normalizedBitRate > 0) normalizedBitRate else song.bitRate,
                bitsPerSample = resolveBitsPerSample(streamInfo, song.path, song.bitsPerSample, song.encodingFormat.ifBlank { song.format }),
                channelCount = if (streamInfo.channels > 0) streamInfo.channels else song.channelCount,
                encodingFormat = encodingFormat,
                format = encodingFormat.ifBlank { song.format },
                title = chooseBetterText(song.title, tagData.title),
                artist = chooseBetterText(song.artist, tagData.artist),
                album = chooseBetterText(song.album, tagData.album),
                genre = if (!tagData.genre.isMetadataDefault()) tagData.genre else song.genre,
                composer = if (!tagData.composer.isMetadataDefault()) tagData.composer else song.composer,
                year = if (tagData.year > 0) tagData.year else song.year,
                discNumber = if (tagData.discNumber > 0) tagData.discNumber else song.discNumber,
                bpm = if (tagData.bpm > 0) tagData.bpm else song.bpm,
                albumArtist = if (!tagData.albumArtist.isMetadataDefault()) tagData.albumArtist else song.albumArtist,
                trackGain = if (tagData.trackGain != 0f) tagData.trackGain else song.trackGain,
                trackPeak = if (tagData.trackPeak != 1.0f) tagData.trackPeak else song.trackPeak,
                albumGain = if (tagData.albumGain != 0f) tagData.albumGain else song.albumGain,
                albumPeak = if (tagData.albumPeak != 1.0f) tagData.albumPeak else song.albumPeak
            )
            val took = System.currentTimeMillis() - t0
            if (took >= 800L) {
                android.util.Log.w("MediaStoreScanner", "slow enrichSong: ${took}ms path=${song.path} format=${song.format} size=${song.fileSize}")
            }
            result
        } catch (e: Exception) {
            android.util.Log.w("EnrichSong", "Failed for ${song.path}: ${e.message}", e)
            song
        }
    }

    private fun isMediaStoreDataComplete(file: AudioFile): Boolean {
        return !file.needsTechnicalMetadataEnrich()
    }

    private fun resolveDurationMs(path: String, streamDurationMs: Long, fallbackDurationMs: Long): Long {
        return when {
            streamDurationMs > 0L -> streamDurationMs
            fallbackDurationMs > 0L -> fallbackDurationMs
            else -> readDurationWithRetriever(path)
        }
    }

    private fun readDurationWithRetriever(path: String): Long {
        if (path.isBlank()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveBitsPerSample(
        streamInfo: FfmpegMetadataReader.AudioStreamInfo,
        path: String,
        oldBits: Int,
        oldFormat: String
    ): Int {
        // 合并 MediaStore 旧位深与 FFmpeg/TagLib 流信息位深
        val mergedBits = when {
            streamInfo.bitsPerSample > 0 -> streamInfo.bitsPerSample
            oldBits > 0 -> oldBits
            else -> AudioBitDepthResolver.inferFromCodecOrExtension(streamInfo.codecName, path)
        }

        // 通过 AudioBitDepthResolver 确保有损格式（AAC/MP3/Opus/Vorbis/WMA 等）返回 0，
        // 无损格式（ALAC/FLAC/WAV 等）保留真实位深，实现 metadata 与位深解耦
        return AudioBitDepthResolver.resolveSourceBitDepth(
            codecName = streamInfo.codecName,
            formatName = oldFormat,
            filePath = path,
            bitsPerSample = mergedBits
        )
    }

    fun shouldInclude(file: AudioFile, options: ScanOptions): Boolean {
        if (options.minDurationMs > 0L && file.duration in 1 until options.minDurationMs) return false
        if (options.minFileSizeBytes > 0L && file.fileSize in 1 until options.minFileSizeBytes) return false
        val normalizedPath = file.path.replace('\\', '/')
        if (options.ignoreVideoFormats && isVideoLikeFile(normalizedPath, file.format)) return false
        if (!options.includeHiddenFiles) {
            val name = normalizedPath.substringAfterLast("/")
            if (name.startsWith(".")) return false
            if (normalizedPath.contains("/.")) return false
        }
        if (options.excludedPathKeywords.any { normalizedPath.contains(it, ignoreCase = true) }) return false
        return true
    }

    private fun isVideoLikeFile(path: String, format: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        val fmt = format.lowercase()
        return ext in VIDEO_LIKE_EXTENSIONS || fmt in VIDEO_LIKE_EXTENSIONS
    }

    private val VIDEO_LIKE_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "avi", "webm", "flv",
        "wmv", "3gp", "3g2", "ts", "mts", "m2ts", "mpg", "mpeg"
    )

    private fun buildSelection(customPaths: List<String>, options: ScanOptions, forceLegacyPathSelection: Boolean = false): String? {
        val conditions = mutableListOf<String>()
        if (options.onlyMusic) conditions += "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        if (customPaths.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !forceLegacyPathSelection) {
                // Android 10+：优先用 RELATIVE_PATH 匹配
                val relativePaths = customPaths.mapNotNull(::customPathToRelativePath).distinct()
                if (relativePaths.isNotEmpty()) {
                    conditions += "(${relativePaths.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }})"
                } else {
                    // fallback: 用 DATA LIKE
                    conditions += "(${customPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }})"
                }
            } else {
                conditions += "(${customPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }})"
            }
        }
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    private fun buildSelectionArgs(customPaths: List<String>, forceLegacyPathSelection: Boolean = false): Array<String>? {
        if (customPaths.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !forceLegacyPathSelection) {
            val relativePaths = customPaths.mapNotNull(::customPathToRelativePath).distinct()
            if (relativePaths.isNotEmpty()) {
                relativePaths.toTypedArray()
            } else {
                customPaths.map(::pathLikeArg).toTypedArray()
            }
        } else {
            customPaths.map(::pathLikeArg).toTypedArray()
        }
    }

    private fun customPathToRelativePath(path: String): String? {
        val normalized = path.trimEnd('/')
        val prefix = "/storage/emulated/0/"
        if (!normalized.startsWith(prefix)) return null
        return normalized
            .removePrefix(prefix)
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "$it/%" }
    }

    private fun shouldUseAndroid10LegacyPathSelection(customPaths: List<String>): Boolean {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && customPaths.isNotEmpty()
    }

    private fun shouldAttemptAndroid10SelectedFolderFallback(customPaths: List<String>): Boolean {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && customPaths.isNotEmpty()
    }

    private fun android10SelectedFallbackPaths(customPaths: List<String>): List<String> {
        return customPaths
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun pathLikeArg(path: String): String {
        val normalized = path.trimEnd('/')
        return if (normalized.isBlank()) "%" else "$normalized/%"
    }

    private fun Cursor.getColumnSafely(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun guessFormat(path: String, mimeType: String): String {
        val ext = path.substringAfterLast(".", "").uppercase()
        return when (ext) {
            "M4A", "MP4" -> "M4A"; "MP3" -> "MP3"; "FLAC" -> "FLAC"; "WAV" -> "WAV"
            "OGG" -> "OGG"; "AAC" -> "AAC"; "WMA" -> "WMA"; "APE" -> "APE"; "OPUS" -> "OPUS"
            "DSF" -> "DSF"; "DFF" -> "DFF"; "AIFF", "AIF" -> "AIFF"
            else -> ext.ifBlank { mimeType.substringAfter("/", "").uppercase() }
        }
    }

    private fun isEmbeddedArtworkPreferredFormat(path: String, format: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        val fmt = format.lowercase()
        return ext in EMBEDDED_ARTWORK_PREFERRED_EXTENSIONS ||
            fmt in EMBEDDED_ARTWORK_PREFERRED_EXTENSIONS
    }

    private val EMBEDDED_ARTWORK_PREFERRED_EXTENSIONS = setOf(
        "dsf", "dff", "wav", "aiff", "aif", "ape"
    )

    private fun sanitizeMediaStoreText(value: String): String =
        if (value.isMetadataDefault()) "" else value

    private fun chooseBetterText(current: String, tag: String): String = when {
        !tag.isMetadataDefault() -> tag
        current.isMetadataDefault() -> ""
        else -> current
    }

    private fun String.isMetadataDefault(): Boolean =
        isBlank() || equals("<unknown>", ignoreCase = true) || equals("music", ignoreCase = true)

    private fun normalizeTrackNumber(rawTrack: Int): Int =
        if (rawTrack >= 1000) rawTrack % 1000 else rawTrack

    private fun normalizeDiscNumber(rawTrack: Int): Int =
        if (rawTrack >= 1000) rawTrack / 1000 else 0

    fun expandCueTracks(song: AudioFile): List<AudioFile> {
        val cueText = readCueSheetForFile(song.path)
        if (cueText.isBlank()) {
            android.util.Log.d("CueExpand", "CUE not found path=${song.path}")
            return listOf(song)
        }
        val cueSheet = try { CueParser.parse(cueText) } catch (e: Exception) {
            android.util.Log.w("CueExpand", "CUE parse failed for: ${song.path}", e)
            return listOf(song)
        }
        if (cueSheet.tracks.isEmpty()) {
            android.util.Log.w("CueExpand", "CUE has no tracks path=${song.path} textLength=${cueText.length}")
            return listOf(song)
        }
        val sourceDuration = when {
            song.duration > 0L -> song.duration
            else -> runCatching {
                FfmpegMetadataReader.readFullInfo(song.path).stream.durationMs
            }.getOrDefault(0L).takeIf { it > 0L }
                ?: readDurationWithRetriever(song.path)
        }
        val results = mutableListOf<AudioFile>()
        for (track in cueSheet.tracks) {
            val trackDuration = when {
                track.endIndexMs > 0 -> track.endIndexMs - track.startIndexMs
                sourceDuration > 0 -> sourceDuration - track.startIndexMs
                else -> -1L
            }
            if (trackDuration <= 0L) continue
            val uniqueId = -(kotlin.math.abs(
                "${song.path}_${track.startIndexMs}_${track.number}".hashCode().toLong() % 1_000_000_000L
            ) + track.number)
            results += song.copy(
                id = uniqueId,
                title = track.title.ifBlank { "${song.title} - Track ${track.number}" },
                artist = track.performer.ifBlank { cueSheet.performer.ifBlank { song.artist } },
                album = cueSheet.title.ifBlank { song.album },
                albumArtist = cueSheet.performer.ifBlank { song.albumArtist },
                duration = trackDuration,
                trackNumber = track.number,
                cueOffsetMs = track.startIndexMs,
                cueEndMs = if (track.endIndexMs > 0) track.endIndexMs else sourceDuration,
                cueTrackIndex = track.number
            )
        }
        android.util.Log.i(
            "CueExpand",
            "CUE expanded path=${song.path} tracks=${cueSheet.tracks.size} results=${results.size} sourceDuration=$sourceDuration"
        )
        return results.ifEmpty { listOf(song) }
    }

    private fun readCueSheetForFile(filePath: String): String {
        try {
            val fullInfo = FfmpegMetadataReader.readFullInfo(filePath)
            val embedded = fullInfo.tags.cueSheet
            if (embedded.isNotBlank()) {
                android.util.Log.d("CueExpand", "CUE source=embedded path=$filePath length=${embedded.length}")
                return embedded
            }
        } catch (_: Exception) {}
        val cueFile = findExternalCueFile(filePath) ?: return ""
        return try {
            val bytes = cueFile.readBytes()
            val utf8 = String(bytes, Charsets.UTF_8)
            val text = if (!utf8.contains("\ufffd")) utf8
            else try { String(bytes, charset("GBK")) } catch (_: Exception) { utf8 }
            android.util.Log.d("CueExpand", "CUE source=external file=${cueFile.absolutePath} length=${text.length}")
            text
        } catch (_: Exception) { "" }
    }

    private fun findExternalCueFile(audioPath: String): File? {
        val audioFile = File(audioPath)
        val dir = audioFile.parentFile ?: return null
        val baseName = audioFile.name.substringBeforeLast(".")
        val candidates = listOf(
            File(dir, "$baseName.cue"), File(dir, "$baseName.CUE"),
            File(dir, "${baseName.lowercase()}.cue"), File(dir, "${baseName.uppercase()}.CUE")
        )
        for (file in candidates) { if (file.exists() && file.canRead()) return file }
        dir.listFiles()?.forEach { file ->
            if (file.extension.equals("cue", ignoreCase = true)) {
                val cueBase = file.name.substringBeforeLast(".")
                if (cueBase.equals(baseName, ignoreCase = true)) return file
                if (cueBase.equals(baseName.substringBeforeLast(" "), ignoreCase = true)) return file
                if (baseName.startsWith(cueBase, ignoreCase = true)) return file
            }
        }
        // Some rippers use a generic CUE filename. Match the FILE directive against the
        // container filename before giving up on an otherwise valid sheet.
        val audioName = audioFile.name
        dir.listFiles()?.firstOrNull { file ->
            if (!file.extension.equals("cue", ignoreCase = true) || !file.isFile || !file.canRead()) {
                return@firstOrNull false
            }
            val header = runCatching {
                file.readLines(Charsets.UTF_8).take(24).joinToString(" ")
            }.getOrDefault("")
            Regex("FILE\\s+\\\"?([^\\\"]+)\\\"?", RegexOption.IGNORE_CASE)
                .find(header)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.let { referenced ->
                    referenced.equals(audioName, ignoreCase = true) ||
                        referenced.substringBeforeLast(".").equals(baseName, ignoreCase = true)
                } == true
        }?.let { return it }
        return null
    }

    // ─────────────── Legacy 文件系统递归扫描 ───────────────

    fun scanCustomPathsByFileSystem(
        context: Context,
        customPaths: List<String>
    ): List<AudioFile> {
        val out = mutableListOf<AudioFile>()
        Log.d(TAG, "legacy scan enabled=${AppPreferences.Scanner.legacyFileAccessEnabled}, paths=$customPaths")

        customPaths
            .map { java.io.File(it) }
            .filter { it.exists() && (it.canRead() || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) }
            .forEach { root ->
                Log.d(TAG, "legacy path: ${root.absolutePath}, exists=${root.exists()}, canRead=${root.canRead()}")
                scanDirectoryRecursive(context, root, out)
            }

        Log.d(TAG, "legacy parsed: ${out.size} files")
        return out
    }

    private fun scanDirectoryRecursive(
        context: Context,
        dir: java.io.File,
        out: MutableList<AudioFile>
    ) {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return

        files.forEach { file ->
            when {
                file.isDirectory -> scanDirectoryRecursive(context, file, out)
                file.isFile && isSupportedAudioFile(file) -> {
                    readAudioFileByPath(context, file)?.let(out::add)
                }
            }
        }
    }

    private fun isSupportedAudioFile(file: java.io.File): Boolean {
        val ext = file.extension.lowercase()
        if (AppPreferences.Scanner.ignoreVideoFormats && ext in LEGACY_VIDEO_EXTENSIONS) return false
        return ext in LEGACY_AUDIO_EXTENSIONS
    }

    private fun readAudioFileByPath(context: Context, file: java.io.File): AudioFile? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val retrieverDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val fullInfo = runCatching { FfmpegMetadataReader.readFullInfo(file.absolutePath) }.getOrNull()
            val durationMs = resolveDurationMs(
                path = file.absolutePath,
                streamDurationMs = fullInfo?.stream?.durationMs ?: 0L,
                fallbackDurationMs = retrieverDurationMs
            )
            val encodingFormat = fullInfo?.stream?.codecName
                ?.takeIf { it.isNotBlank() }
                ?.let { FfmpegMetadataReader.mapCodecToFormat(it, file.absolutePath) }
                ?: guessFormat(file.absolutePath, "")
            val bitRate = BitrateNormalizer.toBps(
                rawBitrate = fullInfo?.stream?.bitRate ?: 0,
                durationMs = durationMs,
                fileSizeBytes = file.length(),
                codecName = fullInfo?.stream?.codecName.orEmpty(),
                formatName = encodingFormat,
                filePath = file.absolutePath
            )

            val minSec = AppPreferences.Scanner.minTrackDurationSeconds
            if (minSec > 0 && durationMs in 1 until minSec * 1000L) return null

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .orEmpty().ifBlank { file.nameWithoutExtension }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .orEmpty().ifBlank { "未知艺术家" }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                .orEmpty().ifBlank { "未知专辑" }
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty()
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty()
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.take(4)?.toIntOrNull() ?: 0
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")?.toIntOrNull() ?: 0

            AudioFile(
                id = stablePathId(file.absolutePath),
                path = file.absolutePath,
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                composer = composer,
                genre = genre,
                year = year,
                trackNumber = track,
                duration = durationMs,
                bitRate = bitRate,
                format = encodingFormat,
                encodingFormat = encodingFormat,
                fileSize = file.length(),
                dateAdded = file.lastModified() / 1000,
                albumArtPath = ""
            )
        } catch (e: Throwable) {
            Log.w(TAG, "legacy read failed: ${file.absolutePath}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun stablePathId(path: String): Long {
        var h = 1125899906842597L
        path.forEach { c -> h = 31L * h + c.code }
        return if (h == Long.MIN_VALUE) 0L else kotlin.math.abs(h)
    }

    private val LEGACY_AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
        "ape", "wv", "tta", "tak", "alac", "aiff", "aif",
        "dsf", "dff", "mka", "mpc"
    )

    private val LEGACY_VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "avi", "webm",
        "flv", "wmv", "3gp", "3g2", "ts", "mts", "m2ts", "mpg", "mpeg"
    )
}

sealed class ScanProgress {
    data class Started(val totalEstimated: Int) : ScanProgress()
    data class Progress(
        val scanned: Int, val total: Int,
        val stage: MediaStoreScanner.ScanStage = MediaStoreScanner.ScanStage.MEDIASTORE,
        val message: String? = null
    ) : ScanProgress()
    data class Completed(val songs: List<AudioFile>, val found: Int, val timeMs: Long) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}
