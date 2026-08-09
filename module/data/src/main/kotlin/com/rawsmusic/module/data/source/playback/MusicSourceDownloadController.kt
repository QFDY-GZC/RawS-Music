package com.rawsmusic.module.data.source.playback

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.util.Log
import android.net.Uri
import android.os.Environment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status for downloads owned by the online-source portal. */
enum class MusicSourceDownloadStatus {
    Resolving,
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

data class MusicSourceDownloadTask(
    val id: String,
    val item: RawSourceMediaItem,
    val requestedQuality: RawSourceQuality,
    val resolvedQuality: RawSourceQuality = requestedQuality,
    val status: MusicSourceDownloadStatus = MusicSourceDownloadStatus.Resolving,
    val systemDownloadId: Long = -1L,
    val fileName: String = "",
    val mimeType: String = "audio/*",
    val localUri: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val libraryScanRequested: Boolean = false,
    val mediaMetadataFinalized: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isActive: Boolean
        get() = status == MusicSourceDownloadStatus.Resolving ||
            status == MusicSourceDownloadStatus.Queued ||
            status == MusicSourceDownloadStatus.Downloading ||
            status == MusicSourceDownloadStatus.Paused
}

/**
 * Resolves online audio in RawSMusic's isolated source runtime, then delegates the byte transfer
 * to Android DownloadManager so the task can continue after the Compose page leaves the screen.
 */
object MusicSourceDownloadController {
    private const val STORAGE_KEY = "music_source_download_tasks_v1"
    private const val MAX_TASKS = 80
    private const val MAX_PAYLOAD_CHARS = 64 * 1024
    const val ACTION_DOWNLOAD_MEDIA_READY = "com.rawsmusic.action.ONLINE_DOWNLOAD_MEDIA_READY"
    const val EXTRA_DOWNLOAD_PATH = "download_path"
    const val EXTRA_DOWNLOAD_ID = "download_id"
    const val PUBLIC_DOWNLOAD_DIRECTORY = "RawSMusic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val resolveJobs = linkedMapOf<String, Job>()
    private val finalizingTaskIds = linkedSetOf<String>()
    private val mutableTasks = MutableStateFlow(loadTasks())
    val tasks = mutableTasks.asStateFlow()

    @Volatile
    private var applicationContext: Context? = null
    @Volatile
    private var initialized = false
    private var monitorJob: Job? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        val firstInitialization = synchronized(lock) {
            if (initialized) false else {
                initialized = true
                true
            }
        }
        if (!firstInitialization) {
            ensureMonitor()
            return
        }
        val interruptedIds = mutableTasks.value
            .filter { it.status == MusicSourceDownloadStatus.Resolving }
            .map { it.id }
            .toSet()
        if (interruptedIds.isNotEmpty()) {
            updateTasks { current ->
                current.map { task ->
                    if (task.id in interruptedIds) {
                        task.copy(
                            status = MusicSourceDownloadStatus.Failed,
                            error = "地址解析被应用重启中断，可点击重试",
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    } else task
                }
            }
        }
        val unfinishedLibraryImports = mutableTasks.value.filter {
            it.status == MusicSourceDownloadStatus.Completed && !it.mediaMetadataFinalized
        }
        unfinishedLibraryImports.forEach(::publishCompletedDownload)
        ensureMonitor()
    }

    fun enqueue(
        context: Context,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): String {
        initialize(context)
        require(item.mediaType == RawSourceMediaType.Music) { "仅歌曲结果可以下载" }
        val activeDuplicate = mutableTasks.value.firstOrNull {
            it.item.stableIdentity == item.stableIdentity &&
                it.requestedQuality == quality &&
                it.isActive
        }
        if (activeDuplicate != null) return activeDuplicate.id

        val task = MusicSourceDownloadTask(
            id = UUID.randomUUID().toString(),
            item = item,
            requestedQuality = quality,
        )
        updateTasks { current -> (listOf(task) + current).take(MAX_TASKS) }
        launchResolution(task.id)
        return task.id
    }

    fun retry(context: Context, taskId: String) {
        initialize(context)
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        task.systemDownloadId.takeIf { it >= 0L }?.let { systemId ->
            downloadManager()?.remove(systemId)
        }
        updateTask(taskId) {
            it.copy(
                status = MusicSourceDownloadStatus.Resolving,
                systemDownloadId = -1L,
                localUri = "",
                bytesDownloaded = 0L,
                totalBytes = -1L,
                error = "",
                updatedAtMs = System.currentTimeMillis(),
                libraryScanRequested = false,
                mediaMetadataFinalized = false,
            )
        }
        launchResolution(taskId)
    }

    fun cancel(taskId: String) {
        resolveJobs.remove(taskId)?.cancel()
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        task.systemDownloadId.takeIf { it >= 0L }?.let { downloadManager()?.remove(it) }
        updateTask(taskId) {
            it.copy(
                status = MusicSourceDownloadStatus.Cancelled,
                error = "已取消",
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Removes only the task record. A completed public music file is intentionally preserved. */
    fun removeRecord(taskId: String) {
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.isActive) cancel(taskId)
        updateTasks { current -> current.filterNot { it.id == taskId } }
    }

    fun clearFinished() {
        updateTasks { current -> current.filter { it.isActive } }
    }

    private fun launchResolution(taskId: String) {
        resolveJobs.remove(taskId)?.cancel()
        resolveJobs[taskId] = scope.launch {
            try {
                val context = applicationContext ?: error("下载服务尚未初始化")
                val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return@launch
                val resolved = MusicSourceAudioResolver.resolve(
                    context = context,
                    item = task.item,
                    requestedQuality = task.requestedQuality,
                )
                val systemId = enqueueSystemDownload(context, task, resolved)
                updateTask(taskId) {
                    val descriptor = buildFileDescriptor(task.item, resolved)
                    it.copy(
                        resolvedQuality = resolved.quality,
                        status = MusicSourceDownloadStatus.Queued,
                        systemDownloadId = systemId,
                        fileName = descriptor.fileName,
                        mimeType = descriptor.mimeType,
                        error = "",
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                ensureMonitor()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateTask(taskId) {
                    it.copy(
                        status = MusicSourceDownloadStatus.Failed,
                        error = error.message.orEmpty().ifBlank { "下载任务创建失败" }.take(1_024),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            } finally {
                val runningJob = kotlin.coroutines.coroutineContext[Job]
                synchronized(lock) {
                    if (resolveJobs[taskId] === runningJob) resolveJobs.remove(taskId)
                }
            }
        }
    }

    private fun enqueueSystemDownload(
        context: Context,
        task: MusicSourceDownloadTask,
        resolved: RawResolvedAudioSource,
    ): Long {
        val uri = Uri.parse(resolved.url)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "下载地址不是 HTTP/HTTPS"
        }
        require(!uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")) {
            "暂不支持 HLS 分片流下载"
        }
        val descriptor = buildFileDescriptor(task.item, resolved)
        val request = DownloadManager.Request(uri)
            .setTitle(task.item.title.ifBlank { descriptor.fileName })
            .setDescription(
                listOf(
                    task.item.artists.joinToString(" / "),
                    descriptor.quality.name,
                ).filter(String::isNotBlank).joinToString(" · ")
            )
            .setMimeType(descriptor.mimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val headers = linkedMapOf<String, String>()
        headers.putAll(resolved.headers)
        resolved.userAgent?.takeIf(String::isNotBlank)?.let { userAgent ->
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = userAgent
            }
        }
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) request.addRequestHeader(name, value)
        }

        val publicDirectory = publicDownloadDirectory()
        runCatching { publicDirectory.mkdirs() }
        ensurePublicDownloadFolderSelected(publicDirectory)
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_MUSIC,
            "$PUBLIC_DOWNLOAD_DIRECTORY/${descriptor.fileName}",
        )
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun ensureMonitor() {
        if (monitorJob?.isActive == true) return
        if (mutableTasks.value.none { it.systemDownloadId >= 0L && it.isActive }) return
        monitorJob = scope.launch {
            while (isActive) {
                val active = mutableTasks.value.filter { it.systemDownloadId >= 0L && it.isActive }
                if (active.isEmpty()) break
                refreshSystemDownloads(active)
                delay(750L)
            }
        }
    }

    /** Called by the app-level DownloadManager receiver, including after process recreation. */
    suspend fun handleSystemDownloadCompleted(context: Context, systemDownloadId: Long) {
        if (systemDownloadId < 0L) return
        initialize(context)
        withContext(Dispatchers.IO) {
            val task = mutableTasks.value.firstOrNull { it.systemDownloadId == systemDownloadId } ?: return@withContext
            refreshSystemDownloads(listOf(task))
        }
    }

    private fun refreshSystemDownloads(active: List<MusicSourceDownloadTask>) {
        val manager = downloadManager() ?: return
        val bySystemId = active.associateBy { it.systemDownloadId }
        val query = DownloadManager.Query().setFilterById(*bySystemId.keys.toLongArray())
        val updates = linkedMapOf<String, MusicSourceDownloadTask>()
        val completed = mutableListOf<MusicSourceDownloadTask>()
        runCatching {
            manager.query(query)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val bytesIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (cursor.moveToNext()) {
                    val systemId = cursor.getLong(idIndex)
                    val current = bySystemId[systemId] ?: continue
                    val status = cursor.getInt(statusIndex)
                    val mapped = when (status) {
                        DownloadManager.STATUS_PENDING -> MusicSourceDownloadStatus.Queued
                        DownloadManager.STATUS_RUNNING -> MusicSourceDownloadStatus.Downloading
                        DownloadManager.STATUS_PAUSED -> MusicSourceDownloadStatus.Paused
                        DownloadManager.STATUS_SUCCESSFUL -> MusicSourceDownloadStatus.Completed
                        DownloadManager.STATUS_FAILED -> MusicSourceDownloadStatus.Failed
                        else -> current.status
                    }
                    val reason = cursor.getInt(reasonIndex)
                    val error = if (mapped == MusicSourceDownloadStatus.Failed) {
                        "系统下载失败（$reason）"
                    } else {
                        ""
                    }
                    val shouldRequestScan = mapped == MusicSourceDownloadStatus.Completed && !current.mediaMetadataFinalized
                    val updated = current.copy(
                        status = mapped,
                        bytesDownloaded = cursor.getLong(bytesIndex).coerceAtLeast(0L),
                        totalBytes = cursor.getLong(totalIndex),
                        localUri = if (uriIndex >= 0) cursor.getString(uriIndex).orEmpty() else current.localUri,
                        error = error,
                        updatedAtMs = System.currentTimeMillis(),
                        libraryScanRequested = current.libraryScanRequested,
                    )
                    updates[current.id] = updated
                    if (shouldRequestScan) completed += updated
                }
            }
        }
        if (updates.isNotEmpty()) {
            updateTasks { current -> current.map { updates[it.id] ?: it } }
        }
        completed.forEach(::publishCompletedDownload)
    }

    private fun publishCompletedDownload(task: MusicSourceDownloadTask) {
        val context = applicationContext ?: return
        val accepted = synchronized(lock) { finalizingTaskIds.add(task.id) }
        if (!accepted) return
        scope.launch {
            try {
                val directory = publicDownloadDirectory().apply { mkdirs() }
                ensurePublicDownloadFolderSelected(directory)
                val expectedOutput = File(directory, task.fileName)
                val reportedOutput = task.localUri
                    .takeIf(String::isNotBlank)
                    ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
                    ?.takeIf { uri -> uri.scheme.equals("file", ignoreCase = true) }
                    ?.path
                    ?.let(::File)
                    ?.takeIf(File::exists)
                var output = when {
                    reportedOutput == null -> expectedOutput
                    reportedOutput.isDirectChildOf(directory) -> reportedOutput
                    expectedOutput.exists() -> expectedOutput
                    migrateDownloadFile(reportedOutput, expectedOutput) -> expectedOutput
                    else -> reportedOutput
                }
                if (!output.exists()) {
                    val legacy = File(
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                            "RawSMusic/Online",
                        ),
                        task.fileName,
                    )
                    if (legacy.exists()) {
                        output = if (migrateDownloadFile(legacy, expectedOutput)) expectedOutput else legacy
                    }
                }
                val notifyReady: (String) -> Unit = { path ->
                    context.sendBroadcast(
                        Intent(ACTION_DOWNLOAD_MEDIA_READY)
                            .setPackage(context.packageName)
                            .putExtra(EXTRA_DOWNLOAD_PATH, path)
                            .putExtra(EXTRA_DOWNLOAD_ID, task.systemDownloadId)
                    )
                }
                if (!output.exists()) {
                    updateTask(task.id) { current ->
                        current.copy(
                            error = "下载完成，但没有找到输出文件",
                            libraryScanRequested = true,
                            mediaMetadataFinalized = true,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                    notifyReady(output.absolutePath)
                    return@launch
                }

                val warnings = runCatching {
                    finalizeDownloadedMetadata(context, task, output)
                }.getOrElse { error ->
                    listOf("标签处理失败：${error.message.orEmpty().ifBlank { "未知错误" }}")
                }
                updateTask(task.id) { current ->
                    current.copy(
                        error = warnings.joinToString("；").take(1_024),
                        libraryScanRequested = true,
                        mediaMetadataFinalized = true,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(output.absolutePath),
                    arrayOf(task.mimeType),
                ) { path, _ -> notifyReady(path ?: output.absolutePath) }
            } finally {
                synchronized(lock) { finalizingTaskIds.remove(task.id) }
            }
        }
    }

    private suspend fun finalizeDownloadedMetadata(
        context: Context,
        task: MusicSourceDownloadTask,
        output: File,
    ): List<String> = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        if (!TagLibBridge.isLoaded() || !TagLibBridge.isSupported(output.absolutePath)) {
            warnings += "当前格式不支持写入标签和内嵌封面"
            return@withContext warnings
        }

        val metadata = linkedMapOf(
            "title" to task.item.title,
            "artist" to task.item.artists.joinToString(" / "),
            "album" to task.item.album,
            "album_artist" to task.item.artists.joinToString(" / "),
        ).filterValues(String::isNotBlank)
        if (metadata.isNotEmpty() && !TagLibBridge.writeMetadata(output.absolutePath, metadata)) {
            warnings += "歌曲标签写入失败"
        }

        if (task.item.artworkUrl.isNotBlank()) {
            val artwork = runCatching {
                MusicSourceArtworkRepository.ensureLocalFile(context, task.item)
            }.getOrNull()
            if (artwork == null) {
                warnings += "专辑图下载失败"
            } else {
                val mime = detectArtworkMime(artwork)
                if (!TagLibBridge.writeEmbeddedArtwork(output.absolutePath, artwork.absolutePath, mime)) {
                    warnings += "当前音频格式不支持内嵌专辑图"
                }
            }
        }
        if (warnings.isNotEmpty()) {
            Log.w("MusicSourceDownload", "${task.fileName}: ${warnings.joinToString()}")
        }
        warnings
    }

    private fun detectArtworkMime(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outMimeType
            ?.takeIf { it.startsWith("image/") }
            ?: when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
    }

    fun publicDownloadDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        PUBLIC_DOWNLOAD_DIRECTORY,
    )

    private fun migrateDownloadFile(source: File, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        if (!destination.exists()) {
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = false)
                if (destination.exists()) source.delete()
            }
        }
        destination.exists()
    }.getOrDefault(false)

    private fun File.isDirectChildOf(directory: File): Boolean =
        normalizeFolderPath(parentFile?.absolutePath.orEmpty()) == normalizeFolderPath(directory.absolutePath)

    private fun ensurePublicDownloadFolderSelected(directory: File) {
        val normalized = normalizeFolderPath(directory.absolutePath)
        if (normalized.isBlank()) return
        val selected = AppPreferences.UI.scanPaths
            .map(::normalizeFolderPath)
            .filter(String::isNotBlank)
            .distinct()
        AppPreferences.UI.scanPaths = when {
            selected.any { path -> normalized == path || normalized.startsWith("$path/") } -> selected
            else -> selected.filterNot { path -> path.startsWith("$normalized/") } + normalized
        }
        val musicRoot = normalizeFolderPath(directory.parentFile?.absolutePath.orEmpty())
        if (musicRoot.isNotBlank()) {
            AppPreferences.UI.rootScanPaths = (AppPreferences.UI.rootScanPaths
                .map(::normalizeFolderPath)
                .filter(String::isNotBlank) + musicRoot)
                .distinct()
        }
    }

    private fun normalizeFolderPath(path: String): String = path
        .replace('\\', '/')
        .trim()
        .trimEnd('/')

    private fun downloadManager(): DownloadManager? = applicationContext
        ?.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private fun updateTask(id: String, transform: (MusicSourceDownloadTask) -> MusicSourceDownloadTask) {
        updateTasks { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    private fun updateTasks(transform: (List<MusicSourceDownloadTask>) -> List<MusicSourceDownloadTask>) {
        synchronized(lock) {
            mutableTasks.value = transform(mutableTasks.value)
            persist(mutableTasks.value)
        }
    }

    private data class FileDescriptor(
        val fileName: String,
        val mimeType: String,
        val quality: RawSourceQuality,
    )

    private fun buildFileDescriptor(
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
    ): FileDescriptor {
        val extension = inferExtension(source.url, source.quality)
        val mime = when (extension) {
            "flac" -> "audio/flac"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        val artist = item.artists.joinToString(" & ").ifBlank { "未知歌手" }
        val shortId = item.stableIdentity.hashCode().toUInt().toString(16).padStart(8, '0').takeLast(8)
        val base = sanitizeFileName("${item.title} - $artist [${qualityFileLabel(source.quality)}]_$shortId")
        return FileDescriptor("$base.$extension", mime, source.quality)
    }

    private fun inferExtension(url: String, quality: RawSourceQuality): String {
        val path = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
            .substringBefore('?')
            .lowercase(Locale.ROOT)
        val candidate = path.substringAfterLast('.', "")
        if (candidate in setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav")) {
            return candidate
        }
        return when (quality) {
            RawSourceQuality.Lossless, RawSourceQuality.HiRes -> "flac"
            else -> "mp3"
        }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(160)
        .ifBlank { "RawSMusic-online" }

    private fun qualityFileLabel(quality: RawSourceQuality): String = when (quality) {
        RawSourceQuality.Standard -> "标准"
        RawSourceQuality.High -> "高品质"
        RawSourceQuality.Super -> "超高品质"
        RawSourceQuality.Lossless -> "无损"
        RawSourceQuality.HiRes -> "Hi-Res"
    }

    private fun loadTasks(): List<MusicSourceDownloadTask> = runCatching {
        val raw = AppPreferences.storage.decodeString(STORAGE_KEY, "").orEmpty()
        if (raw.isBlank()) return@runCatching emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull(::parseTask).take(MAX_TASKS)
    }.getOrDefault(emptyList())

    private fun parseTask(element: com.google.gson.JsonElement): MusicSourceDownloadTask? = runCatching {
        val obj = element.asJsonObject
        val itemObj = obj.getAsJsonObject("item") ?: return null
        MusicSourceDownloadTask(
            id = obj.string("id"),
            item = RawSourceMediaItem(
                sourceId = itemObj.string("sourceId"),
                remoteId = itemObj.string("remoteId"),
                mediaType = runCatching { RawSourceMediaType.valueOf(itemObj.string("mediaType")) }
                    .getOrDefault(RawSourceMediaType.Music),
                title = itemObj.string("title"),
                artists = itemObj.getAsJsonArray("artists")?.mapNotNull { it.asString }.orEmpty(),
                album = itemObj.string("album"),
                durationMs = itemObj.long("durationMs"),
                artworkUrl = itemObj.string("artworkUrl"),
                availableQualities = itemObj.getAsJsonArray("availableQualities")
                    ?.mapNotNull { value -> runCatching { RawSourceQuality.valueOf(value.asString) }.getOrNull() }
                    ?.toSet()
                    ?.ifEmpty { setOf(RawSourceQuality.Standard) }
                    ?: setOf(RawSourceQuality.Standard),
                sourcePayload = itemObj.string("sourcePayload").let { payload ->
                    payload.takeIf { it.length <= MAX_PAYLOAD_CHARS } ?: "{}"
                },
            ),
            requestedQuality = obj.quality("requestedQuality"),
            resolvedQuality = obj.quality("resolvedQuality"),
            status = runCatching { MusicSourceDownloadStatus.valueOf(obj.string("status")) }
                .getOrDefault(MusicSourceDownloadStatus.Failed),
            systemDownloadId = obj.long("systemDownloadId", -1L),
            fileName = obj.string("fileName"),
            mimeType = obj.string("mimeType").ifBlank { "audio/*" },
            localUri = obj.string("localUri"),
            bytesDownloaded = obj.long("bytesDownloaded"),
            totalBytes = obj.long("totalBytes", -1L),
            error = obj.string("error"),
            createdAtMs = obj.long("createdAtMs"),
            updatedAtMs = obj.long("updatedAtMs"),
            libraryScanRequested = obj.bool("libraryScanRequested"),
            mediaMetadataFinalized = obj.bool("mediaMetadataFinalized"),
        )
    }.getOrNull()

    private fun persist(tasks: List<MusicSourceDownloadTask>) {
        AppPreferences.storage.encode(STORAGE_KEY, JsonArray().apply {
            tasks.take(MAX_TASKS).forEach { add(it.toJson()) }
        }.toString())
    }

    private fun MusicSourceDownloadTask.toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("requestedQuality", requestedQuality.name)
        addProperty("resolvedQuality", resolvedQuality.name)
        addProperty("status", status.name)
        addProperty("systemDownloadId", systemDownloadId)
        addProperty("fileName", fileName)
        addProperty("mimeType", mimeType)
        addProperty("localUri", localUri)
        addProperty("bytesDownloaded", bytesDownloaded)
        addProperty("totalBytes", totalBytes)
        addProperty("error", error)
        addProperty("createdAtMs", createdAtMs)
        addProperty("updatedAtMs", updatedAtMs)
        addProperty("libraryScanRequested", libraryScanRequested)
        addProperty("mediaMetadataFinalized", mediaMetadataFinalized)
        add("item", JsonObject().apply {
            addProperty("sourceId", item.sourceId)
            addProperty("remoteId", item.remoteId)
            addProperty("mediaType", item.mediaType.name)
            addProperty("title", item.title)
            add("artists", JsonArray().apply { item.artists.forEach { add(it) } })
            addProperty("album", item.album)
            addProperty("durationMs", item.durationMs)
            addProperty("artworkUrl", item.artworkUrl)
            add("availableQualities", JsonArray().apply { item.availableQualities.forEach { add(it.name) } })
            addProperty(
                "sourcePayload",
                item.sourcePayload.takeIf { it.length <= MAX_PAYLOAD_CHARS } ?: "{}",
            )
        })
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.long(name: String, fallback: Long = 0L): Long =
        runCatching { get(name)?.asLong ?: fallback }.getOrDefault(fallback)

    private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
        runCatching { get(name)?.asBoolean ?: fallback }.getOrDefault(fallback)

    private fun JsonObject.quality(name: String): RawSourceQuality =
        runCatching { RawSourceQuality.valueOf(string(name)) }.getOrDefault(RawSourceQuality.Standard)
}
