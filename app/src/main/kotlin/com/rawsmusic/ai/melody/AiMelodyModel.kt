package com.rawsmusic.ai.melody

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import com.rawsmusic.separation.AiSeparationDownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Tensor/preprocessing contract for a downloadable melody estimator. */
data class AiMelodyModelDescriptor(
    val id: String,
    val version: String,
    val displayName: String,
    val modelFileName: String,
    val modelSizeBytes: Long,
    val modelSha256: String,
    val downloadUrls: List<String>,
    val sampleRate: Int,
    val fftSize: Int,
    val hopLength: Int,
    val melBins: Int,
    val melMinHz: Float,
    val melMaxHz: Float,
    val classCount: Int,
    val voicingThreshold: Float,
    val inferenceFrames: Int,
    val contextFrames: Int,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank() && displayName.isNotBlank())
        require(modelFileName.endsWith(".onnx"))
        require(modelSizeBytes > 0L && modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(downloadUrls.isNotEmpty())
        require(sampleRate > 0 && fftSize > 0 && fftSize and (fftSize - 1) == 0)
        require(hopLength in 1..fftSize && melBins > 0 && classCount == RmvpePitchDecoder.CLASS_COUNT)
        require(melMinHz >= 0f && melMaxHz > melMinHz && melMaxHz <= sampleRate / 2f)
        require(voicingThreshold in 0f..1f)
        require(inferenceFrames > 0 && inferenceFrames % 32 == 0)
        require(contextFrames >= 0 && contextFrames < inferenceFrames / 2)
    }
}

object AiRecommendedMelodyModels {
    const val RMVPE_Q8_ID = "rmvpe.q8"
    const val RMVPE_Q8_VERSION = "1.0.0"

    /**
     * INT8 graph is preferred on phones. RawSMusic keeps the RMVPE boundary contract in float32
     * and validates the installed graph I/O when opening the ONNX session.
     */
    val RMVPE_Q8 = AiMelodyModelDescriptor(
        id = RMVPE_Q8_ID,
        version = RMVPE_Q8_VERSION,
        displayName = "RMVPE INT8",
        modelFileName = "rmvpe_q8.onnx",
        modelSizeBytes = 98_719_204L,
        modelSha256 = "9151c489d8c09a2c31c035e5eb24651c18e9f05cc04c4cc1afc5086c9bce7d1e",
        downloadUrls = listOf(
            "https://huggingface.co/TigreGotico/voiceclonnx-rvc/resolve/main/rmvpe_q8.onnx?download=true",
        ),
        sampleRate = 16_000,
        fftSize = 1024,
        hopLength = 160,
        melBins = 128,
        melMinHz = 30f,
        melMaxHz = 8000f,
        classCount = 360,
        voicingThreshold = 0.03f,
        inferenceFrames = 2048,
        contextFrames = 64,
    )
}

data class AiInstalledMelodyModel(
    val descriptor: AiMelodyModelDescriptor,
    val modelFile: File,
)

/** Owns the pinned melody-estimation graph used by AI performance generation. */
class AiMelodyModelStore private constructor(context: Context) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    private fun publishChanged() { mutableRevision.value = mutableRevision.value + 1L }

    private val root = File(context.applicationContext.filesDir, "ai_melody/models").apply { mkdirs() }
    private val downloadDir = File(context.applicationContext.cacheDir, "ai_melody/downloads").apply { mkdirs() }
    @Volatile private var verifiedRecommendedStamp: String = ""

    fun resolveRecommended(): AiInstalledMelodyModel? {
        val descriptor = AiRecommendedMelodyModels.RMVPE_Q8
        val target = modelFile(descriptor)
        if (!target.isFile || target.length() != descriptor.modelSizeBytes) return null
        val stamp = verificationStamp(target)
        if (verifiedRecommendedStamp != stamp) {
            if (!sha256(target).equals(descriptor.modelSha256, ignoreCase = true)) return null
            verifiedRecommendedStamp = stamp
        }
        return AiInstalledMelodyModel(descriptor, target)
    }

    suspend fun installRecommendedFrom(source: File): Result<AiInstalledMelodyModel> =
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = AiRecommendedMelodyModels.RMVPE_Q8
                require(source.isFile && source.length() == descriptor.modelSizeBytes) {
                    "RMVPE 模型大小不匹配"
                }
                require(sha256(source).equals(descriptor.modelSha256, ignoreCase = true)) {
                    "RMVPE 模型 SHA-256 不匹配"
                }
                val directory = modelDirectory(descriptor)
                require(directory.isDirectory || directory.mkdirs()) { "无法创建旋律模型目录" }
                val staging = File(directory, ".${descriptor.modelFileName}.${System.nanoTime()}.tmp")
                try {
                    FileInputStream(source).use { input ->
                        FileOutputStream(staging).use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                    require(staging.length() == descriptor.modelSizeBytes) { "RMVPE 模型复制不完整" }
                    require(sha256(staging).equals(descriptor.modelSha256, ignoreCase = true)) {
                        "RMVPE 模型复制后校验失败"
                    }
                    val target = modelFile(descriptor)
                    if (target.exists()) target.delete()
                    require(staging.renameTo(target)) { "无法原子安装 RMVPE 模型" }
                    verifiedRecommendedStamp = verificationStamp(target)
                    AiInstalledMelodyModel(descriptor, target).also { publishChanged() }
                } finally {
                    staging.delete()
                }
            }
        }

    suspend fun downloadAndInstallRecommended(
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): AiInstalledMelodyModel = withContext(Dispatchers.IO) {
        val descriptor = AiRecommendedMelodyModels.RMVPE_Q8
        ensureFreeSpace(descriptor.modelSizeBytes * 2L + EXTRA_FREE_SPACE_BYTES)
        val part = File(downloadDir, "${descriptor.id}-${descriptor.version}.part")
        var lastError: Throwable? = null
        val failedHosts = mutableListOf<String>()
        for (url in descriptor.downloadUrls) {
            if (isCancelled()) throw CancellationException("下载已取消")
            try {
                downloadWithResume(
                    sourceUrl = url,
                    target = part,
                    expectedBytes = descriptor.modelSizeBytes,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                onPhase(AiSeparationDownloadPhase.VERIFYING)
                if (part.length() != descriptor.modelSizeBytes) {
                    val actualBytes = part.length()
                    part.delete()
                    error("RMVPE 下载大小不匹配：$actualBytes/${descriptor.modelSizeBytes}")
                }
                if (!sha256(part).equals(descriptor.modelSha256, ignoreCase = true)) {
                    part.delete()
                    error("RMVPE 下载文件 SHA-256 校验失败")
                }
                onPhase(AiSeparationDownloadPhase.INSTALLING)
                return@withContext try {
                    installRecommendedFrom(part).getOrThrow()
                } finally {
                    part.delete()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                failedHosts += runCatching { URL(url).host }.getOrDefault(url)
            }
        }
        throw IllegalStateException(
            "旋律模型下载源不可用${failedHosts.distinct().takeIf { it.isNotEmpty() }?.joinToString(prefix = "（", postfix = "）").orEmpty()}，可改用本地导入 RMVPE",
            lastError,
        )
    }

    suspend fun removeRecommended(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val descriptor = AiRecommendedMelodyModels.RMVPE_Q8
            modelDirectory(descriptor).deleteRecursively()
            downloadDir.listFiles().orEmpty()
                .filter { it.name.startsWith("${descriptor.id}-${descriptor.version}") }
                .forEach { it.delete() }
            verifiedRecommendedStamp = ""
            publishChanged()
        }
    }

    fun recommendedTargetFile(): File = modelFile(AiRecommendedMelodyModels.RMVPE_Q8)

    private fun modelDirectory(descriptor: AiMelodyModelDescriptor): File =
        File(File(root, descriptor.id), descriptor.version)

    private fun modelFile(descriptor: AiMelodyModelDescriptor): File =
        File(modelDirectory(descriptor), descriptor.modelFileName)

    private fun verificationStamp(file: File): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    private fun downloadWithResume(
        sourceUrl: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (target.length() > expectedBytes) target.delete()
        var current = sourceUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val existing = target.length()
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "旋律模型下载重定向过多" }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error("旋律模型下载重定向缺少目标地址")
                    current = URL(URL(current), location).toString()
                    require(current.startsWith("https://")) { "旋律模型下载重定向必须使用 HTTPS" }
                    return@repeat
                }
                if (code == 416 && existing == expectedBytes) {
                    onProgress(existing, expectedBytes)
                    return
                }
                if (code == 416 && existing > 0L) {
                    target.delete()
                    current = sourceUrl
                    return@repeat
                }
                require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "RMVPE 下载 HTTP $code"
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                if (!append && target.exists()) target.delete()
                val start = if (append) existing else 0L
                BufferedInputStream(connection.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(target, append)).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var downloaded = start
                        var lastPublish = 0L
                        while (true) {
                            if (isCancelled()) throw CancellationException("下载已取消")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            require(downloaded <= expectedBytes) { "RMVPE 下载数据超过固定大小" }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPublish >= PROGRESS_INTERVAL_MS || downloaded == expectedBytes) {
                                lastPublish = now
                                onProgress(downloaded, expectedBytes)
                            }
                        }
                    }
                }
                onProgress(target.length(), expectedBytes)
                return
            } finally {
                connection.disconnect()
            }
        }
        error("旋律模型下载重定向失败")
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val available = StatFs(root.absolutePath).availableBytes
        require(available >= requiredBytes) {
            "可用空间不足，需要至少 ${requiredBytes / 1024 / 1024} MB"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_REDIRECTS = 5
        private const val EXTRA_FREE_SPACE_BYTES = 96L * 1024L * 1024L
        private const val USER_AGENT = "RawSMusic AI-Melody-Model-Manager"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        @Volatile private var instance: AiMelodyModelStore? = null

        fun get(context: Context): AiMelodyModelStore = instance ?: synchronized(this) {
            instance ?: AiMelodyModelStore(context).also { instance = it }
        }
    }
}
