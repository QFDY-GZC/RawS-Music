package com.rawsmusic.separation

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.util.Base64
import android.util.Log
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

class AiSeparationPluginStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "ai_separation").apply { mkdirs() }
    private val repositoryFile = File(root, "repository.json")
    private val catalogFile = File(root, "catalog.json")
    private val catalogSignatureFile = File(root, "catalog.sig")
    private val downloadDir = File(root, "downloads").apply { mkdirs() }
    private val modelsDir = File(root, "models").apply { mkdirs() }
    private val runtimesDir = AiOnnxRuntimeLoader.runtimeRoot(appContext)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutationMutex = Mutex()
    private val mutableState = MutableStateFlow(run {
        removeRetiredWaveformModel()
        loadState()
    })

    val state: StateFlow<AiSeparationStoreState> = mutableState.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutableState.value = loadState()
    }

    suspend fun importRepository(uri: Uri): Result<AiModelRepositoryDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use {
                readLimited(it, MAX_REPOSITORY_BYTES)
            } ?: error("无法读取仓库描述文件")
            val descriptor = AiSeparationJson.parseRepository(bytes.toString(Charsets.UTF_8))
            mutationMutex.withLock {
                atomicWrite(repositoryFile, bytes)
                catalogFile.delete()
                catalogSignatureFile.delete()
                mutableState.value = loadState().copy(lastError = "")
            }
            descriptor
        }.onFailure { error -> publishError(error) }
    }

    suspend fun removeRepository(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                repositoryFile.delete()
                catalogFile.delete()
                catalogSignatureFile.delete()
                mutableState.value = loadState().copy(lastError = "")
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun refreshCatalog(): Result<List<AiSeparationCatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val repository = readRepository() ?: error("请先导入可信模型仓库")
            val indexBytes = fetchSmall(repository.indexUrl, MAX_CATALOG_BYTES)
            val signatureBytes = decodeDetachedSignature(
                fetchSmall(repository.signatureUrl, MAX_SIGNATURE_BYTES)
            )
            verifySignature(repository, indexBytes, signatureBytes)
            val catalog = AiSeparationJson.parseCatalog(
                indexBytes.toString(Charsets.UTF_8),
                repository.id,
            )
            mutationMutex.withLock {
                atomicWrite(catalogFile, indexBytes)
                atomicWrite(catalogSignatureFile, signatureBytes)
                mutableState.value = loadState().copy(lastError = "")
            }
            catalog
        }.onFailure { error -> publishError(error) }
    }

    suspend fun importModelPackage(uri: Uri): Result<AiSeparationInstalledModel> = withContext(Dispatchers.IO) {
        runCatching {
            val catalog = readVerifiedCachedCatalog()
            require(catalog.isNotEmpty()) { "请先刷新可信模型仓库" }
            val imported = File(downloadDir, "manual_${System.nanoTime()}.rsm-ai-model")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(imported)).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                } ?: error("无法读取模型包")
                require(imported.length() <= MAX_MODEL_ARCHIVE_BYTES) { "模型包过大" }
                val archiveHash = sha256(imported)
                val expected = catalog.firstOrNull { it.archiveSha256 == archiveHash }
                    ?: error("模型包不在已验签的仓库索引中")
                installVerifiedArchive(imported, expected)
            } finally {
                imported.delete()
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun importRecommendedModel(
        uri: Uri,
        modelId: String = AiRecommendedModels.UVR_9482_ID,
        modelVersion: String = AiRecommendedModels.UVR_9482_VERSION,
    ): Result<AiSeparationInstalledModel> = withContext(Dispatchers.IO) {
        runCatching {
            val expected = AiRecommendedModels.find(modelId, modelVersion)
                ?: error("推荐模型不存在")
            ensureFreeSpace(expected.modelSizeBytes * 2L + EXTRA_FREE_SPACE_BYTES)
            val imported = File(downloadDir, "recommended_manual_${System.nanoTime()}.onnx")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedInputStream(input).use { bufferedInput ->
                        BufferedOutputStream(FileOutputStream(imported)).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val read = bufferedInput.read(buffer)
                                if (read < 0) break
                                copied += read
                                require(copied <= expected.modelSizeBytes) {
                                    "所选文件大于推荐模型声明大小"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } ?: error("无法读取推荐模型文件")
                require(imported.length() == expected.modelSizeBytes) {
                    "推荐模型大小不匹配：${imported.length()}/${expected.modelSizeBytes}"
                }
                require(sha256(imported) == expected.modelSha256) {
                    "推荐模型 SHA-256 校验失败"
                }
                installVerifiedRecommendedModel(imported, expected)
            } finally {
                imported.delete()
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun downloadAndInstall(
        modelId: String,
        modelVersion: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): AiSeparationInstalledModel = withContext(Dispatchers.IO) {
        val recommended = AiRecommendedModels.find(modelId, modelVersion)
        if (recommended != null) {
            return@withContext downloadAndInstallRecommended(
                recommended, onProgress, onPhase, isCancelled,
            )
        }
        val catalog = readVerifiedCachedCatalog()
        val entry = catalog.firstOrNull { it.id == modelId && it.version == modelVersion }
            ?: error("模型不存在或仓库索引尚未刷新")
        ensureFreeSpace(entry.archiveSizeBytes + entry.modelSizeBytes + EXTRA_FREE_SPACE_BYTES)
        val part = File(downloadDir, "${entry.id}-${entry.version}.part")
        var lastError: Throwable? = null
        var verifiedArchive = false
        for (url in entry.downloadUrls) {
            if (isCancelled()) throw CancellationException("下载已取消")
            try {
                downloadWithResume(url, part, entry.archiveSizeBytes, onProgress, isCancelled)
                onPhase(AiSeparationDownloadPhase.VERIFYING)
                if (part.length() != entry.archiveSizeBytes) {
                    val actualBytes = part.length()
                    part.delete()
                    error("下载大小不匹配：$actualBytes/${entry.archiveSizeBytes}")
                }
                if (sha256(part) != entry.archiveSha256) {
                    part.delete()
                    error("模型包 SHA-256 校验失败")
                }
                verifiedArchive = true
                break
            } catch (error: Throwable) {
                lastError = error
                AppLogger.e(TAG, "AI model mirror failed: $url", error)
                if (error is CancellationException) throw error
            }
        }
        if (!verifiedArchive) throw lastError ?: IllegalStateException("所有模型下载地址均失败")
        onPhase(AiSeparationDownloadPhase.INSTALLING)
        try {
            installVerifiedArchive(part, entry)
        } finally {
            part.delete()
        }
    }

    suspend fun downloadAndInstallRuntime(
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): AiRuntimeCatalogEntry = withContext(Dispatchers.IO) {
        val signedEntry = readVerifiedCachedRuntimes()
            .filter { it.abi in Build.SUPPORTED_ABIS }
            .maxByOrNull { it.version }
        val entry = signedEntry ?: AiRecommendedRuntime.ONNX_RUNTIME_1_26
        require(isAppVersionCompatible(entry.minimumAppVersion)) {
            "当前 RawSMusic 版本低于运行库要求 ${entry.minimumAppVersion}"
        }
        val usesOfficialAar = signedEntry == null
        val downloadBytes = if (usesOfficialAar) {
            AiRecommendedRuntime.ARCHIVE_SIZE_BYTES
        } else {
            entry.librarySizeBytes
        }
        ensureFreeSpace(downloadBytes + entry.librarySizeBytes + EXTRA_FREE_SPACE_BYTES)
        val part = File(
            downloadDir,
            "runtime_${entry.id}-${entry.version}-${entry.abi}.${if (usesOfficialAar) "aar" else "part"}",
        )
        var lastError: Throwable? = null
        var verified = false
        for (url in entry.downloadUrls) {
            if (isCancelled()) throw CancellationException("下载已取消")
            try {
                downloadWithResume(url, part, downloadBytes, onProgress, isCancelled)
                onPhase(AiSeparationDownloadPhase.VERIFYING)
                require(part.length() == downloadBytes) {
                    "运行库下载大小不匹配：${part.length()}/$downloadBytes"
                }
                val archiveHash = sha256(part)
                val expectedArchiveHash = if (usesOfficialAar) {
                    AiRecommendedRuntime.ARCHIVE_SHA256
                } else {
                    entry.librarySha256
                }
                Log.i(
                    TAG,
                    "AI_RUNTIME_VERIFY archive host=${URL(url).host} bytes=${part.length()} " +
                        "sha256=$archiveHash expected=$expectedArchiveHash officialAar=$usesOfficialAar",
                )
                if (usesOfficialAar) {
                    if (archiveHash != expectedArchiveHash) {
                        // Maven mirrors may repackage the AAR ZIP without changing
                        // its executable payload. The fixed inner ELF hash below is
                        // the security identity that must match exactly.
                        Log.w(
                            TAG,
                            "AI_RUNTIME_VERIFY AAR container differs; verifying embedded runtime",
                        )
                    }
                } else {
                    require(archiveHash == expectedArchiveHash) {
                        "运行库下载 SHA-256 校验失败"
                    }
                }
                verified = true
                break
            } catch (error: Throwable) {
                lastError = error
                Log.e(
                    TAG,
                    "AI_RUNTIME_VERIFY mirror failed host=${runCatching { URL(url).host }.getOrDefault("invalid")}",
                    error,
                )
                part.delete()
                AppLogger.e(TAG, "AI runtime mirror failed: $url", error)
                if (error is CancellationException) throw error
            }
        }
        if (!verified) throw lastError ?: IllegalStateException("所有运行库下载地址均失败")
        onPhase(AiSeparationDownloadPhase.INSTALLING)
        val extracted = File(downloadDir, "runtime_${entry.id}-${entry.version}.so")
        try {
            val source = if (usesOfficialAar) {
                extractRuntimeFromOfficialAar(part, extracted, entry)
                extracted
            } else {
                part
            }
            installVerifiedRuntime(source, entry)
        } finally {
            part.delete()
            extracted.delete()
        }
    }

    suspend fun importRuntime(uri: Uri): Result<AiRuntimeCatalogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = (
                readVerifiedCachedRuntimes() + AiRecommendedRuntime.ONNX_RUNTIME_1_26
            ).filter { it.abi in Build.SUPPORTED_ABIS }
            val imported = File(downloadDir, "manual_runtime_${System.nanoTime()}.so")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(imported)).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                } ?: error("无法读取运行库")
                val hash = sha256(imported)
                val expected = entries.firstOrNull {
                    it.librarySizeBytes == imported.length() && it.librarySha256 == hash
                } ?: error("运行库不在已验签的仓库索引中")
                installVerifiedRuntime(imported, expected)
            } finally {
                imported.delete()
            }
        }.onFailure { error -> publishError(error) }
    }

    private fun extractRuntimeFromOfficialAar(
        archive: File,
        output: File,
        expected: AiRuntimeCatalogEntry,
    ) {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(AiRecommendedRuntime.AAR_LIBRARY_PATH)
                ?: error("官方 AAR 缺少 arm64 ONNX Runtime")
            require(!entry.isDirectory && entry.size == expected.librarySizeBytes) {
                "官方 AAR 中的运行库大小无效"
            }
            zip.getInputStream(entry).buffered().use { input ->
                FileOutputStream(output).use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= expected.librarySizeBytes) { "运行库解压数据超出声明大小" }
                        destination.write(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
            }
        }
        require(output.length() == expected.librarySizeBytes) { "运行库解压后大小错误" }
        val runtimeHash = sha256(output)
        Log.i(
            TAG,
            "AI_RUNTIME_VERIFY embedded bytes=${output.length()} sha256=$runtimeHash " +
                "expected=${expected.librarySha256}",
        )
        require(runtimeHash == expected.librarySha256) { "运行库解压后哈希错误" }
    }

    fun installedRuntime(): Pair<AiRuntimeCatalogEntry, File>? =
        AiOnnxRuntimeLoader.installedRuntime(appContext)

    private suspend fun installVerifiedRuntime(
        source: File,
        entry: AiRuntimeCatalogEntry,
    ): AiRuntimeCatalogEntry = mutationMutex.withLock {
        require(entry.abi in Build.SUPPORTED_ABIS) { "运行库 ABI 与设备不匹配" }
        require(source.length() == entry.librarySizeBytes) { "运行库大小校验失败" }
        require(sha256(source) == entry.librarySha256) { "运行库哈希校验失败" }
        val target = File(File(File(runtimesDir, entry.id), entry.version), entry.abi)
        val staging = File(target.parentFile, ".staging_${entry.abi}_${System.nanoTime()}")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建运行库暂存目录" }
        try {
            val library = File(staging, entry.libraryFile)
            source.inputStream().buffered().use { input ->
                FileOutputStream(library).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            require(library.setReadable(true, true)) { "无法设置运行库读取权限" }
            library.setExecutable(true, true)
            require(library.length() == entry.librarySizeBytes) { "运行库复制后大小错误" }
            require(sha256(library) == entry.librarySha256) { "运行库复制后哈希错误" }
            atomicWrite(
                File(staging, RUNTIME_MANIFEST),
                AiSeparationJson.runtimeManifestJson(entry).toByteArray(Charsets.UTF_8),
            )
            val backup = File(target.parentFile, ".backup_${entry.abi}_${System.nanoTime()}")
            target.parentFile?.mkdirs()
            if (target.exists()) require(target.renameTo(backup)) { "无法备份旧运行库" }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装运行库")
            }
            backup.deleteRecursively()
            entry
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private suspend fun downloadAndInstallRecommended(
        entry: AiSeparationCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): AiSeparationInstalledModel {
        ensureFreeSpace(entry.modelSizeBytes * 2L + EXTRA_FREE_SPACE_BYTES)
        val part = File(downloadDir, "recommended_${entry.id}-${entry.version}.part")
        var lastError: Throwable? = null
        val failedHosts = mutableListOf<String>()
        var verified = false
        for (url in entry.downloadUrls) {
            if (isCancelled()) throw CancellationException("下载已取消")
            try {
                downloadWithResume(url, part, entry.modelSizeBytes, onProgress, isCancelled)
                onPhase(AiSeparationDownloadPhase.VERIFYING)
                if (part.length() != entry.modelSizeBytes) {
                    val actualBytes = part.length()
                    part.delete()
                    error("推荐模型大小不匹配：$actualBytes/${entry.modelSizeBytes}")
                }
                if (sha256(part) != entry.modelSha256) {
                    part.delete()
                    error("推荐模型 SHA-256 校验失败")
                }
                verified = true
                break
            } catch (error: Throwable) {
                lastError = error
                failedHosts += runCatching { URL(url).host }.getOrDefault(url)
                AppLogger.e(TAG, "Recommended AI model mirror failed: $url", error)
                if (error is CancellationException) throw error
            }
        }
        if (!verified) {
            val hosts = failedHosts.distinct().joinToString()
            throw IllegalStateException(
                "所有在线镜像均不可用${if (hosts.isBlank()) "" else "（$hosts）"}，" +
                    "请使用“导入已下载模型”从本地安装 ${entry.name}",
                lastError,
            )
        }
        onPhase(AiSeparationDownloadPhase.INSTALLING)
        return try {
            installVerifiedRecommendedModel(part, entry)
        } finally {
            part.delete()
        }
    }

    private suspend fun installVerifiedRecommendedModel(
        downloadedModel: File,
        expected: AiSeparationCatalogEntry,
    ): AiSeparationInstalledModel = mutationMutex.withLock {
        require(AiRecommendedModels.isRecommended(expected.id, expected.version)) {
            "不是 APK 内置的推荐模型"
        }
        require(downloadedModel.length() == expected.modelSizeBytes) { "推荐模型大小校验失败" }
        require(sha256(downloadedModel) == expected.modelSha256) { "推荐模型哈希校验失败" }
        require(isAppVersionCompatible(expected.minimumAppVersion)) {
            "当前 RawSMusic 版本低于模型要求 ${expected.minimumAppVersion}"
        }

        val staging = File(modelsDir, ".staging_${expected.id}_${System.nanoTime()}")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建推荐模型暂存目录" }
        try {
            val modelFile = File(staging, expected.modelFile)
            downloadedModel.inputStream().buffered().use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            require(modelFile.length() == expected.modelSizeBytes) { "推荐模型复制后大小错误" }
            require(sha256(modelFile) == expected.modelSha256) { "推荐模型复制后哈希错误" }
            atomicWrite(
                File(staging, "license.txt"),
                AiRecommendedModels.licenseFor(expected).toByteArray(Charsets.UTF_8),
            )
            atomicWrite(
                File(staging, "model-card.txt"),
                AiRecommendedModels.modelCardFor(expected).toByteArray(Charsets.UTF_8),
            )
            expected.contract?.let { contract ->
                atomicWrite(
                    File(staging, "config.json"),
                    AiSeparationJson.contractJson(contract).toByteArray(Charsets.UTF_8),
                )
                if (AiSeparationRuntimeBridge.status(appContext).onnxRuntimePresent) {
                    AiSeparationRuntimeBridge.probeModel(appContext, modelFile, contract).getOrThrow()
                }
            }

            val installedAt = System.currentTimeMillis()
            atomicWrite(
                File(staging, INSTALLED_MANIFEST),
                AiSeparationJson.packageManifestJson(expected, installedAt).toByteArray(),
            )
            val target = modelVersionDir(expected.id, expected.version)
            target.parentFile?.mkdirs()
            val backup = File(target.parentFile, ".backup_${target.name}_${System.nanoTime()}")
            if (target.exists()) require(target.renameTo(backup)) { "无法备份旧推荐模型" }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装推荐模型")
            }
            backup.deleteRecursively()
            val selection = preferences.edit()
            if (AiRecommendedModels.isRealtime(expected)) {
                selection
                    .putString(KEY_REALTIME_SELECTED_ID, expected.id)
                    .putString(KEY_REALTIME_SELECTED_VERSION, expected.version)
                if (preferences.getString(KEY_SELECTED_ID, "").isNullOrBlank()) {
                    selection
                        .putString(KEY_SELECTED_ID, expected.id)
                        .putString(KEY_SELECTED_VERSION, expected.version)
                }
            } else {
                selection
                    .putString(KEY_SELECTED_ID, expected.id)
                    .putString(KEY_SELECTED_VERSION, expected.version)
            }
            selection.apply()
            val installed = AiSeparationInstalledModel(expected, target.absolutePath, installedAt)
            mutableState.value = loadState().copy(lastError = "")
            installed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun selectModel(id: String, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                val installed = scanInstalledModels()
                require(installed.any { it.catalog.id == id && it.catalog.version == version }) {
                    "模型尚未安装"
                }
                preferences.edit()
                    .putString(KEY_SELECTED_ID, id)
                    .putString(KEY_SELECTED_VERSION, version)
                    .apply()
                mutableState.value = loadState().copy(lastError = "")
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun selectRealtimeModel(
        id: String,
        version: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                val installed = scanInstalledModels().firstOrNull {
                    it.catalog.id == id && it.catalog.version == version
                } ?: error("模型尚未安装")
                require(AiRecommendedModels.isRealtime(installed.catalog)) {
                    "该模型仅支持离线分离"
                }
                preferences.edit()
                    .putString(KEY_REALTIME_SELECTED_ID, id)
                    .putString(KEY_REALTIME_SELECTED_VERSION, version)
                    .apply()
                mutableState.value = loadState().copy(lastError = "")
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun removeModel(id: String, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!AiSeparationJobProgressBus.isModelActive(id, version)) {
                "AI 分离运行期间不能删除正在使用的模型"
            }
            mutationMutex.withLock {
                val target = modelVersionDir(id, version)
                require(target.canonicalPath.startsWith(modelsDir.canonicalPath + File.separator)) {
                    "模型目录无效"
                }
                if (target.exists() && !target.deleteRecursively()) error("无法删除模型")
                if (preferences.getString(KEY_SELECTED_ID, "") == id &&
                    preferences.getString(KEY_SELECTED_VERSION, "") == version
                ) {
                    preferences.edit().remove(KEY_SELECTED_ID).remove(KEY_SELECTED_VERSION).apply()
                }
                if (preferences.getString(KEY_REALTIME_SELECTED_ID, "") == id &&
                    preferences.getString(KEY_REALTIME_SELECTED_VERSION, "") == version
                ) {
                    preferences.edit()
                        .remove(KEY_REALTIME_SELECTED_ID)
                        .remove(KEY_REALTIME_SELECTED_VERSION)
                        .apply()
                }
                mutableState.value = loadState().copy(lastError = "")
            }
        }.onFailure { error -> publishError(error) }
    }

    suspend fun clearAllModels(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!AiSeparationJobProgressBus.hasActiveTask()) { "AI 分离运行期间不能清空模型" }
            mutationMutex.withLock {
                modelsDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
                downloadDir.listFiles().orEmpty().forEach { it.delete() }
                preferences.edit()
                    .remove(KEY_SELECTED_ID)
                    .remove(KEY_SELECTED_VERSION)
                    .remove(KEY_REALTIME_SELECTED_ID)
                    .remove(KEY_REALTIME_SELECTED_VERSION)
                    .apply()
                mutableState.value = loadState().copy(lastError = "")
            }
        }.onFailure { error -> publishError(error) }
    }

    fun selectedInstalledModel(): AiSeparationInstalledModel? {
        val current = mutableState.value
        return current.installed.firstOrNull {
            it.catalog.id == current.selectedModelId &&
                it.catalog.version == current.selectedModelVersion
        }?.takeIf { installed ->
            File(installed.directory, installed.catalog.modelFile).isFile
        }
    }

    fun selectedModelFile(): File? = selectedInstalledModel()?.let { installed ->
        File(installed.directory, installed.catalog.modelFile).takeIf(File::isFile)
    }

    fun selectedRealtimeInstalledModel(): AiSeparationInstalledModel? {
        val current = mutableState.value
        return current.installed.firstOrNull {
            it.catalog.id == current.selectedRealtimeModelId &&
                it.catalog.version == current.selectedRealtimeModelVersion
        }?.takeIf { installed ->
            File(installed.directory, installed.catalog.modelFile).isFile
        }
    }

    fun selectedRealtimeModelFile(): File? = selectedRealtimeInstalledModel()?.let { installed ->
        File(installed.directory, installed.catalog.modelFile).takeIf(File::isFile)
    }

    private suspend fun installVerifiedArchive(
        archive: File,
        expected: AiSeparationCatalogEntry,
    ): AiSeparationInstalledModel = mutationMutex.withLock {
        require(archive.length() == expected.archiveSizeBytes) { "模型包大小与索引不一致" }
        require(sha256(archive) == expected.archiveSha256) { "模型包校验失败" }
        require(isAppVersionCompatible(expected.minimumAppVersion)) {
            "当前 RawSMusic 版本低于模型要求 ${expected.minimumAppVersion}"
        }

        val staging = File(modelsDir, ".staging_${expected.id}_${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val entries = buildList {
                    val enumeration = zip.entries()
                    while (enumeration.hasMoreElements()) add(enumeration.nextElement())
                }
                require(entries.size <= MAX_ZIP_ENTRIES) { "模型包文件数量过多" }
                require(entries.none { it.isDirectory }) { "模型包不允许包含目录" }
                require(entries.map { it.name }.toSet().size == entries.size) {
                    "模型包不允许包含重名文件"
                }
                val manifestEntry = zip.getEntry(PACKAGE_MANIFEST) ?: error("模型包缺少 manifest.json")
                val manifestText = zip.getInputStream(manifestEntry).use {
                    readLimited(it, MAX_PACKAGE_MANIFEST_BYTES).toString(Charsets.UTF_8)
                }
                val manifest = AiSeparationJson.parsePackageManifest(manifestText)
                require(manifest.id == expected.id && manifest.version == expected.version) {
                    "模型包 ID 或版本与仓库索引不一致"
                }
                require(manifest.schemaVersion == expected.schemaVersion) { "模型 schema 与索引不一致" }
                require(manifest.contract == expected.contract) { "模型运行契约与索引不一致" }
                require(manifest.modelFile == expected.modelFile) { "模型文件名与索引不一致" }
                require(manifest.modelSizeBytes == expected.modelSizeBytes) { "模型文件大小与索引不一致" }
                require(manifest.modelSha256 == expected.modelSha256) { "模型文件哈希与索引不一致" }
                require(manifest.modelFormat == expected.modelFormat) { "模型格式与索引不一致" }
                require(manifest.architecture == expected.architecture) { "模型架构与索引不一致" }
                require(manifest.sampleRate == expected.sampleRate) { "模型采样率与索引不一致" }
                require(manifest.channels == expected.channels) { "模型声道数与索引不一致" }
                require(manifest.segmentSamples == expected.segmentSamples) { "模型分块参数与索引不一致" }
                require(manifest.overlap == expected.overlap) { "模型重叠参数与索引不一致" }

                var totalExtracted = 0L
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    val name = entry.name
                    require(name in allowedPackageEntries(expected.modelFile)) { "模型包包含未允许的文件：$name" }
                    require(!name.contains('/') && !name.contains('\\')) { "模型包路径无效" }
                    require(entry.size in 0..MAX_MODEL_ARCHIVE_BYTES) { "模型包条目大小无效" }
                    val output = File(staging, name)
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(output)).use { out ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            var entryExtracted = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                entryExtracted += read
                                totalExtracted += read
                                require(entryExtracted <= MAX_MODEL_ARCHIVE_BYTES) {
                                    "模型包条目解压后过大"
                                }
                                require(totalExtracted <= MAX_MODEL_ARCHIVE_BYTES) {
                                    "模型包解压后过大"
                                }
                                out.write(buffer, 0, read)
                            }
                            require(entryExtracted == entry.size) { "模型包条目大小不一致：$name" }
                        }
                    }
                }
            }

            val modelFile = File(staging, expected.modelFile)
            require(modelFile.isFile) { "模型文件缺失" }
            if (expected.executable) {
                require(File(staging, "license.txt").isFile) { "可执行模型缺少 license.txt" }
                require(File(staging, "model-card.txt").isFile) { "可执行模型缺少 model-card.txt" }
                require(File(staging, "license.txt").length() in 1..MAX_TEXT_ATTACHMENT_BYTES) {
                    "模型许可证文件无效"
                }
                require(File(staging, "model-card.txt").length() in 1..MAX_TEXT_ATTACHMENT_BYTES) {
                    "模型说明文件无效"
                }
            }
            require(modelFile.length() == expected.modelSizeBytes) { "模型文件大小校验失败" }
            require(sha256(modelFile) == expected.modelSha256) { "模型文件 SHA-256 校验失败" }

            val runtime = AiSeparationRuntimeBridge.status(appContext)
            val contract = expected.contract
            if (runtime.onnxRuntimePresent && contract != null) {
                AiSeparationRuntimeBridge.probeModel(appContext, modelFile, contract).getOrThrow()
            }

            val installedAt = System.currentTimeMillis()
            atomicWrite(
                File(staging, INSTALLED_MANIFEST),
                AiSeparationJson.packageManifestJson(expected, installedAt).toByteArray(),
            )
            val target = modelVersionDir(expected.id, expected.version)
            target.parentFile?.mkdirs()
            val backup = File(target.parentFile, ".backup_${target.name}_${System.nanoTime()}")
            if (target.exists()) {
                require(target.renameTo(backup)) { "无法备份旧模型" }
            }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装模型")
            }
            backup.deleteRecursively()

            val selection = preferences.edit()
            if (preferences.getString(KEY_SELECTED_ID, "").isNullOrBlank()) {
                selection
                    .putString(KEY_SELECTED_ID, expected.id)
                    .putString(KEY_SELECTED_VERSION, expected.version)
            }
            if (
                AiRecommendedModels.isRealtime(expected) &&
                preferences.getString(KEY_REALTIME_SELECTED_ID, "").isNullOrBlank()
            ) {
                selection
                    .putString(KEY_REALTIME_SELECTED_ID, expected.id)
                    .putString(KEY_REALTIME_SELECTED_VERSION, expected.version)
            }
            selection.apply()
            val installed = AiSeparationInstalledModel(expected, target.absolutePath, installedAt)
            mutableState.value = loadState().copy(lastError = "")
            installed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun loadState(): AiSeparationStoreState {
        val repository = runCatching { readRepository() }.getOrNull()
        val catalog = runCatching { readVerifiedCachedCatalog(repository) }.getOrDefault(emptyList())
        val runtimeCatalog = runCatching {
            readVerifiedCachedRuntimes(repository)
        }.getOrDefault(emptyList())
        val installed = scanInstalledModels()
        val selectedId = preferences.getString(KEY_SELECTED_ID, "").orEmpty()
        val selectedVersion = preferences.getString(KEY_SELECTED_VERSION, "").orEmpty()
        val validSelection = installed.any {
            it.catalog.id == selectedId && it.catalog.version == selectedVersion
        }
        val storedRealtimeId = preferences.getString(KEY_REALTIME_SELECTED_ID, "").orEmpty()
        val storedRealtimeVersion = preferences.getString(
            KEY_REALTIME_SELECTED_VERSION,
            "",
        ).orEmpty()
        val legacyRealtime = installed.firstOrNull {
            it.catalog.id == selectedId &&
                it.catalog.version == selectedVersion &&
                AiRecommendedModels.isRealtime(it.catalog)
        }
        val realtimeId = storedRealtimeId.ifBlank { legacyRealtime?.catalog?.id.orEmpty() }
        val realtimeVersion = storedRealtimeVersion.ifBlank {
            legacyRealtime?.catalog?.version.orEmpty()
        }
        val validRealtimeSelection = installed.any {
            it.catalog.id == realtimeId &&
                it.catalog.version == realtimeVersion &&
                AiRecommendedModels.isRealtime(it.catalog)
        }
        return AiSeparationStoreState(
            repository = repository,
            catalog = catalog,
            runtimeCatalog = runtimeCatalog,
            installed = installed,
            selectedModelId = if (validSelection) selectedId else "",
            selectedModelVersion = if (validSelection) selectedVersion else "",
            selectedRealtimeModelId = if (validRealtimeSelection) realtimeId else "",
            selectedRealtimeModelVersion = if (validRealtimeSelection) realtimeVersion else "",
        )
    }

    private fun readRepository(): AiModelRepositoryDescriptor? {
        if (!repositoryFile.isFile) return null
        return AiSeparationJson.parseRepository(repositoryFile.readText())
    }

    private fun readVerifiedCachedCatalog(
        repository: AiModelRepositoryDescriptor? = readRepository(),
    ): List<AiSeparationCatalogEntry> {
        if (repository == null || !catalogFile.isFile || !catalogSignatureFile.isFile) return emptyList()
        val bytes = catalogFile.readBytes()
        verifySignature(repository, bytes, catalogSignatureFile.readBytes())
        return AiSeparationJson.parseCatalog(bytes.toString(Charsets.UTF_8), repository.id)
    }

    private fun readVerifiedCachedRuntimes(
        repository: AiModelRepositoryDescriptor? = readRepository(),
    ): List<AiRuntimeCatalogEntry> {
        if (repository == null || !catalogFile.isFile || !catalogSignatureFile.isFile) return emptyList()
        val bytes = catalogFile.readBytes()
        verifySignature(repository, bytes, catalogSignatureFile.readBytes())
        return AiSeparationJson.parseRuntimeCatalog(bytes.toString(Charsets.UTF_8), repository.id)
    }

    private fun scanInstalledModels(): List<AiSeparationInstalledModel> = modelsDir
        .walkTopDown()
        .maxDepth(3)
        .filter { it.isFile && it.name == INSTALLED_MANIFEST }
        .mapNotNull { manifest ->
            runCatching {
                val (catalog, installedAt) = AiSeparationJson.parseInstalled(manifest.readText())
                val dir = manifest.parentFile ?: return@runCatching null
                val model = File(dir, catalog.modelFile)
                require(model.isFile && model.length() == catalog.modelSizeBytes)
                AiSeparationInstalledModel(catalog, dir.absolutePath, installedAt)
            }.getOrNull()
        }
        .filterNotNull()
        .sortedBy { it.catalog.name }
        .toList()

    private fun verifySignature(
        repository: AiModelRepositoryDescriptor,
        payload: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val keyBytes = Base64.decode(repository.publicKeyBase64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance(repository.keyAlgorithm)
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance(repository.signatureAlgorithm)
        verifier.initVerify(publicKey)
        verifier.update(payload)
        require(verifier.verify(signatureBytes)) { "模型仓库签名校验失败" }
    }

    private fun decodeDetachedSignature(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.US_ASCII).trim()
        require(text.isNotEmpty()) { "仓库签名为空" }
        require(text.matches(BASE64_SIGNATURE)) { "仓库签名必须是 Base64 文本" }
        return Base64.decode(text, Base64.DEFAULT).also {
            require(it.isNotEmpty()) { "仓库签名为空" }
        }
    }

    private fun fetchSmall(url: String, limit: Int): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "下载重定向过多" }
                    current = URL(URL(current), connection.getHeaderField("Location")).toString()
                    require(current.startsWith("https://")) { "下载重定向必须使用 HTTPS" }
                    return@repeat
                }
                require(code in 200..299) { "HTTP $code" }
                return connection.inputStream.use { readLimited(it, limit) }
            } finally {
                connection.disconnect()
            }
        }
        error("下载重定向失败")
    }

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
                readTimeout = MODEL_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "下载重定向过多" }
                    current = URL(URL(current), connection.getHeaderField("Location")).toString()
                    require(current.startsWith("https://")) { "下载重定向必须使用 HTTPS" }
                    return@repeat
                }
                if (code == 416 && existing == expectedBytes) {
                    onProgress(existing, expectedBytes)
                    return
                }
                require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "HTTP $code"
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                if (!append && target.exists()) target.delete()
                val start = if (append) existing else 0L
                BufferedInputStream(connection.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(target, append)).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var downloaded = start
                        var lastPublish = 0L
                        while (true) {
                            if (isCancelled()) throw CancellationException("下载已取消")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            require(downloaded <= expectedBytes) { "下载数据超过索引声明大小" }
                            val now = android.os.SystemClock.elapsedRealtime()
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
        error("下载重定向失败")
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val available = StatFs(root.absolutePath).availableBytes
        require(available >= requiredBytes) {
            "可用空间不足，需要至少 ${requiredBytes / 1024 / 1024} MB"
        }
    }

    private fun isAppVersionCompatible(minimum: String): Boolean {
        if (minimum.isBlank()) return true
        val current = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        return compareVersions(current, minimum) >= 0
    }

    private fun compareVersions(left: String, right: String): Int {
        fun tokens(value: String) = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
        val a = tokens(left)
        val b = tokens(right)
        repeat(maxOf(a.size, b.size)) { index ->
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun modelVersionDir(id: String, version: String): File = File(File(modelsDir, id), version)

    private fun removeRetiredWaveformModel() {
        File(modelsDir, RETIRED_MEL_ROFORMER_ID).deleteRecursively()
        downloadDir.listFiles()
            .orEmpty()
            .filter { it.name.contains(RETIRED_MEL_ROFORMER_ID) }
            .forEach { it.delete() }
        if (preferences.getString(KEY_SELECTED_ID, "") == RETIRED_MEL_ROFORMER_ID) {
            preferences.edit()
                .remove(KEY_SELECTED_ID)
                .remove(KEY_SELECTED_VERSION)
                .apply()
        }
    }

    private fun allowedPackageEntries(modelFile: String): Set<String> = setOf(
        PACKAGE_MANIFEST,
        modelFile,
        "license.txt",
        "model-card.txt",
        "config.json",
    )

    private fun publishError(error: Throwable) {
        AppLogger.e(TAG, "AI separation model store failure", error)
        mutableState.value = mutableState.value.copy(lastError = error.message.orEmpty())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        val backup = File(target.parentFile, ".${target.name}.${System.nanoTime()}.bak")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.renameTo(backup)) { "无法备份 ${target.name}" }
                backedUp = true
            }
            require(temp.renameTo(target)) { "无法保存 ${target.name}" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "文件超过允许大小" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "AiSeparationStore"
        private const val PREFS_NAME = "ai_separation_models"
        private const val KEY_SELECTED_ID = "selected_model_id"
        private const val KEY_SELECTED_VERSION = "selected_model_version"
        private const val KEY_REALTIME_SELECTED_ID = "selected_realtime_model_id"
        private const val KEY_REALTIME_SELECTED_VERSION = "selected_realtime_model_version"
        private const val RETIRED_MEL_ROFORMER_ID = "melband.roformer.kim.vocals"
        private const val PACKAGE_MANIFEST = "manifest.json"
        private const val INSTALLED_MANIFEST = "installed.json"
        private const val RUNTIME_MANIFEST = "installed.json"
        private const val MAX_REPOSITORY_BYTES = 256 * 1024
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 16 * 1024
        private const val MAX_PACKAGE_MANIFEST_BYTES = 256 * 1024
        private const val MAX_TEXT_ATTACHMENT_BYTES = 2L * 1024L * 1024L
        private const val MAX_MODEL_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val EXTRA_FREE_SPACE_BYTES = 128L * 1024L * 1024L
        private const val MAX_ZIP_ENTRIES = 16
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MODEL_READ_TIMEOUT_MS = 90_000
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "RawSMusic/0.9.61beta AI-Model-Manager"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val BASE64_SIGNATURE = Regex("[A-Za-z0-9+/]+={0,2}")

        @Volatile private var instance: AiSeparationPluginStore? = null

        fun get(context: Context): AiSeparationPluginStore = instance ?: synchronized(this) {
            instance ?: AiSeparationPluginStore(context).also { instance = it }
        }
    }
}
