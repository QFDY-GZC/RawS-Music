package com.rawsmusic.separation

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.rawsmusic.core.common.model.AudioFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class AiSeparationResult(
    val id: String,
    val sourceName: String,
    val modelId: String,
    val modelVersion: String,
    val modelName: String,
    val sampleRate: Int,
    val totalFrames: Long,
    val processedSegments: Int,
    val elapsedMs: Long,
    val createdAtEpochMs: Long,
    val directory: String,
    val sourceMediaId: Long = 0L,
    val sourceFileName: String = sourceName,
    val sourceSize: Long = 0L,
    val sourceDurationMs: Long = 0L,
    val outputFormat: String = "wav",
) {
    val vocalsFile: File
        get() = resolveStemFile(VOCALS_BASENAME)
    val instrumentalFile: File
        get() = resolveStemFile(INSTRUMENTAL_BASENAME)
    val mimeType: String
        get() = when (outputFormat.lowercase()) {
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/wav"
        }

    private fun resolveStemFile(baseName: String): File {
        val preferred = File(directory, "$baseName.${outputFormat.lowercase()}")
        if (preferred.isFile) return preferred
        return listOf("flac", "m4a", "aac", "wav")
            .asSequence()
            .map { File(directory, "$baseName.$it") }
            .firstOrNull(File::isFile)
            ?: preferred
    }

    companion object {
        const val VOCALS_BASENAME = "vocals"
        const val INSTRUMENTAL_BASENAME = "instrumental"
        const val TEMP_VOCALS_FILE = "vocals.stream.wav"
        const val TEMP_INSTRUMENTAL_FILE = "instrumental.stream.wav"
    }
}

class AiSeparationResultStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val legacyRoot = File(appContext.filesDir, "ai_separation/results")
    private val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "RawSMusic/AI Separation",
    )

    init {
        migrateLegacyResults()
    }

    private val mutex = Mutex()
    private val mutable = MutableStateFlow(scan())
    val results: StateFlow<List<AiSeparationResult>> = mutable.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) { mutable.value = scan() }

    suspend fun commit(
        vocals: File,
        instrumental: File,
        sourceName: String,
        sourceUri: Uri,
        outputFormat: String,
        model: AiSeparationInstalledModel,
        sampleRate: Int,
        stats: AiNativeSeparationStats,
    ): AiSeparationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(vocals.isFile && instrumental.isFile) { "分离输出文件缺失" }
            require(vocals.length() > 44L && instrumental.length() > 44L) { "分离输出为空" }
            require(root.isDirectory || root.mkdirs()) {
                "无法创建公共结果目录：${root.absolutePath}"
            }
            val sourceIdentity = querySourceIdentity(
                sourceUri = sourceUri,
                fallbackName = sourceName,
                durationMs = stats.totalFrames * 1000L / sampleRate.coerceAtLeast(1),
            )
            val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val staging = File(root, ".staging_$id")
            val target = File(root, id)
            staging.deleteRecursively()
            require(staging.mkdirs()) { "无法创建结果 staging" }
            try {
                val extension = outputFormat.lowercase()
                require(extension in setOf("flac", "m4a", "aac")) { "不支持的分轨编码：$extension" }
                val stagedVocals = File(
                    staging,
                    "${AiSeparationResult.VOCALS_BASENAME}.$extension",
                )
                val stagedInstrumental = File(
                    staging,
                    "${AiSeparationResult.INSTRUMENTAL_BASENAME}.$extension",
                )
                vocals.copyTo(stagedVocals, overwrite = false)
                instrumental.copyTo(stagedInstrumental, overwrite = false)
                val result = AiSeparationResult(
                    id = id,
                    sourceName = sourceName,
                    modelId = model.catalog.id,
                    modelVersion = model.catalog.version,
                    modelName = model.catalog.name,
                    sampleRate = sampleRate,
                    totalFrames = stats.totalFrames,
                    processedSegments = stats.processedSegments,
                    elapsedMs = stats.elapsedMs,
                    createdAtEpochMs = System.currentTimeMillis(),
                    directory = target.absolutePath,
                    sourceMediaId = sourceIdentity.mediaId,
                    sourceFileName = sourceIdentity.fileName,
                    sourceSize = sourceIdentity.size,
                    sourceDurationMs = sourceIdentity.durationMs,
                    outputFormat = extension,
                )
                atomicWrite(File(staging, RESULT_MANIFEST), resultJson(result).toByteArray())
                require(staging.renameTo(target)) { "无法原子提交分离结果" }
                val committed = result.copy(directory = target.absolutePath)
                publishToMediaLibrary(committed)
                mutable.value = scan()
                committed
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                require(id.matches(SAFE_ID)) { "结果 ID 无效" }
                val target = scan().firstOrNull { it.id == id }
                    ?.directory
                    ?.let(::File)
                    ?: File(root, id)
                require(isManagedDirectory(target)) {
                    "结果目录无效"
                }
                if (target.exists() && !target.deleteRecursively()) error("无法删除分离结果")
                mutable.value = scan()
            }
        }
    }

    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!AiSeparationJobProgressBus.hasActiveTask()) { "AI 分离运行期间不能清理结果" }
            mutex.withLock {
                managedRoots().forEach { directory ->
                    directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
                }
                mutable.value = emptyList()
            }
        }
    }

    fun findFor(song: AudioFile): AiSeparationResult? {
        val fileName = normalizeFileName(song.path.substringAfterLast('/').ifBlank { song.displayName })
        return mutable.value.firstOrNull { result ->
            val mediaIdMatches = song.id > 0L && result.sourceMediaId > 0L &&
                song.id == result.sourceMediaId
            val nameMatches = normalizeFileName(result.sourceFileName) == fileName
            val sizeMatches = song.fileSize <= 0L || result.sourceSize <= 0L ||
                song.fileSize == result.sourceSize
            val durationMatches = song.duration <= 0L || result.sourceDurationMs <= 0L ||
                kotlin.math.abs(song.duration - result.sourceDurationMs) <= DURATION_TOLERANCE_MS
            mediaIdMatches || (nameMatches && sizeMatches && durationMatches)
        }
    }

    fun cleanupStaleStaging() {
        managedRoots().forEach { directory ->
            directory.listFiles().orEmpty()
                .filter { it.name.startsWith(".staging_") || it.name.startsWith(".backup_") }
                .forEach { it.deleteRecursively() }
        }
    }

    private fun scan(): List<AiSeparationResult> = managedRoots()
        .asSequence()
        .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { directory ->
            runCatching {
                val manifest = File(directory, RESULT_MANIFEST)
                val rootJson = JsonParser.parseString(manifest.readText()).asJsonObject
                val result = AiSeparationResult(
                    id = rootJson.get("id").asString,
                    sourceName = rootJson.get("sourceName").asString,
                    modelId = rootJson.get("modelId").asString,
                    modelVersion = rootJson.get("modelVersion").asString,
                    modelName = rootJson.get("modelName").asString,
                    sampleRate = rootJson.get("sampleRate").asInt,
                    totalFrames = rootJson.get("totalFrames").asLong,
                    processedSegments = rootJson.get("processedSegments").asInt,
                    elapsedMs = rootJson.get("elapsedMs").asLong,
                    createdAtEpochMs = rootJson.get("createdAtEpochMs").asLong,
                    directory = directory.absolutePath,
                    sourceMediaId = rootJson.get("sourceMediaId")?.asLong ?: 0L,
                    sourceFileName = rootJson.get("sourceFileName")?.asString
                        ?: rootJson.get("sourceName").asString,
                    sourceSize = rootJson.get("sourceSize")?.asLong ?: 0L,
                    sourceDurationMs = rootJson.get("sourceDurationMs")?.asLong ?: 0L,
                    outputFormat = rootJson.get("outputFormat")?.asString ?: "wav",
                )
                require(result.vocalsFile.isFile && result.instrumentalFile.isFile)
                result
            }.getOrNull()
        }
        .distinctBy { it.id }
        .sortedByDescending { it.createdAtEpochMs }
        .toList()

    private fun managedRoots(): List<File> = listOf(root, legacyRoot)
        .distinctBy { it.absolutePath }

    private fun isManagedDirectory(directory: File): Boolean {
        val canonical = directory.canonicalPath
        return managedRoots().any { managed ->
            canonical.startsWith(managed.canonicalPath + File.separator)
        }
    }

    private fun migrateLegacyResults() {
        if (!legacyRoot.isDirectory) return
        if (!root.isDirectory && !root.mkdirs()) return
        legacyRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .forEach { source ->
                val target = File(root, source.name)
                if (target.exists()) return@forEach
                runCatching {
                    source.copyRecursively(target, overwrite = false)
                    require(
                        target.listFiles().orEmpty().any {
                            it.isFile && it.nameWithoutExtension == AiSeparationResult.VOCALS_BASENAME
                        } &&
                            target.listFiles().orEmpty().any {
                                it.isFile &&
                                    it.nameWithoutExtension == AiSeparationResult.INSTRUMENTAL_BASENAME
                            }
                    )
                    source.deleteRecursively()
                }.onFailure {
                    target.deleteRecursively()
                }
            }
    }

    private fun publishToMediaLibrary(result: AiSeparationResult) {
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(result.vocalsFile.absolutePath, result.instrumentalFile.absolutePath),
            arrayOf(result.mimeType, result.mimeType),
            null,
        )
    }

    private fun resultJson(result: AiSeparationResult): String = JsonObject().apply {
        addProperty("schemaVersion", 2)
        addProperty("id", result.id)
        addProperty("sourceName", result.sourceName)
        addProperty("modelId", result.modelId)
        addProperty("modelVersion", result.modelVersion)
        addProperty("modelName", result.modelName)
        addProperty("sampleRate", result.sampleRate)
        addProperty("totalFrames", result.totalFrames)
        addProperty("processedSegments", result.processedSegments)
        addProperty("elapsedMs", result.elapsedMs)
        addProperty("createdAtEpochMs", result.createdAtEpochMs)
        addProperty("sourceMediaId", result.sourceMediaId)
        addProperty("sourceFileName", result.sourceFileName)
        addProperty("sourceSize", result.sourceSize)
        addProperty("sourceDurationMs", result.sourceDurationMs)
        addProperty("outputFormat", result.outputFormat)
    }.toString()

    private fun querySourceIdentity(
        sourceUri: Uri,
        fallbackName: String,
        durationMs: Long,
    ): SourceIdentity {
        var mediaId = parseMediaId(sourceUri)
        var fileName = fallbackName
        var size = 0L
        runCatching {
            appContext.contentResolver.query(
                sourceUri,
                arrayOf("_id", OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex("_id").takeIf { it >= 0 }?.let {
                        mediaId = cursor.getLong(it).takeIf { value -> value > 0L } ?: mediaId
                    }
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        fileName = cursor.getString(it).orEmpty().ifBlank { fileName }
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        size = cursor.getLong(it).coerceAtLeast(0L)
                    }
                }
            }
        }
        return SourceIdentity(mediaId, fileName, size, durationMs)
    }

    private fun parseMediaId(uri: Uri): Long {
        val tail = uri.lastPathSegment.orEmpty()
        return tail.substringAfterLast(':').toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun normalizeFileName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private data class SourceIdentity(
        val mediaId: Long,
        val fileName: String,
        val size: Long,
        val durationMs: Long,
    )

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        require(temporary.renameTo(target)) { "无法保存 ${target.name}" }
    }

    companion object {
        private const val RESULT_MANIFEST = "result.json"
        private const val DURATION_TOLERANCE_MS = 1_500L
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,96}")

        @Volatile private var instance: AiSeparationResultStore? = null

        fun get(context: Context): AiSeparationResultStore = instance ?: synchronized(this) {
            instance ?: AiSeparationResultStore(context).also { instance = it }
        }
    }
}
