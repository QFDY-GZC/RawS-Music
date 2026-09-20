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
    private val lyricAlignmentDownloadDir = File(root, "lyric_alignment/downloads").apply { mkdirs() }
    private val lyricAlignmentModelsDir = File(root, "lyric_alignment/models").apply { mkdirs() }
    private val fastVocalAlignmentDir = File(root, "fast_vocal_alignment").apply { mkdirs() }
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

    /**
     * Imports a repository descriptor from a HTTPS endpoint.
     *
     * The descriptor is only a bootstrap document. The catalog and every model artifact still
     * have to pass the repository signature and SHA-256 checks below before they are accepted.
     */
    suspend fun importRepositoryFromUrl(url: String): Result<AiModelRepositoryDescriptor> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedUrl = url.trim()
                require(normalizedUrl.startsWith("https://")) { "仓库地址必须使用 HTTPS" }
                val bytes = fetchSmall(normalizedUrl, MAX_REPOSITORY_BYTES)
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
            val repository = ensureDefaultRepository().getOrThrow()
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

    /** Refreshes the signed index and returns the separately declared CTC lyric models. */
    suspend fun refreshLyricAlignmentCatalog(): Result<List<AiLyricAlignmentCatalogEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                refreshCatalog().getOrThrow()
                readVerifiedCachedLyricAlignmentCatalog()
            }.onFailure { error -> publishError(error) }
        }

    /**
     * Installs the signed RawSMusic model repository descriptor on first use.
     *
     * The APK contains only this HTTPS bootstrap URL. The descriptor still carries the
     * repository public key, and every catalog/model artifact is verified before use.
     */
    suspend fun ensureDefaultRepository(): Result<AiModelRepositoryDescriptor> =
        withContext(Dispatchers.IO) {
            runCatching {
                readRepository()?.let { return@runCatching it }
                val bytes = fetchSmall(DEFAULT_REPOSITORY_URL, MAX_REPOSITORY_BYTES)
                val descriptor = AiSeparationJson.parseRepository(bytes.toString(Charsets.UTF_8))
                mutationMutex.withLock {
                    if (readRepository() == null) {
                        atomicWrite(repositoryFile, bytes)
                        catalogFile.delete()
                        catalogSignatureFile.delete()
                        mutableState.value = loadState().copy(lastError = "")
                    }
                }
                readRepository() ?: descriptor
            }.onFailure { error -> publishError(error) }
        }

    /** The signed repository descriptor URL bundled as the model download entry point. */
    fun defaultRepositoryUrl(): String = DEFAULT_REPOSITORY_URL

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

    /** Downloads and atomically extracts the standalone two-stem FP16 alignment bundle. */
    suspend fun downloadAndInstallFastVocalAlignmentBundle(
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): Long = withContext(Dispatchers.IO) {
        ensureFreeSpace(
            AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES * 2L + EXTRA_FREE_SPACE_BYTES,
        )
        val part = File(
            downloadDir,
            "${AiFastVocalAlignmentBundle.ID}-${AiFastVocalAlignmentBundle.VERSION}.zip.part",
        )
        try {
            downloadWithResume(
                sourceUrl = AiFastVocalAlignmentBundle.DOWNLOAD_URL,
                target = part,
                expectedBytes = AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            onPhase(AiSeparationDownloadPhase.VERIFYING)
            require(part.length() == AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES) {
                "快速对齐模型包大小校验失败：${part.length()}/" +
                    AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES
            }
            require(sha256(part) == AiFastVocalAlignmentBundle.ARCHIVE_SHA256) {
                "快速对齐模型包 SHA-256 校验失败"
            }
            onPhase(AiSeparationDownloadPhase.INSTALLING)
            installFastVocalAlignmentBundle(part)
            AiFastVocalAlignmentBundle.ARCHIVE_SIZE_BYTES
        } finally {
            part.delete()
        }
    }

    /** Downloads a CTC model and its vocabulary from the signed repository, never from APK assets. */
    suspend fun downloadAndInstallLyricAlignment(
        modelId: String,
        modelVersion: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onPhase: (AiSeparationDownloadPhase) -> Unit,
        isCancelled: () -> Boolean,
    ): AiLyricAlignmentInstalledModel = withContext(Dispatchers.IO) {
        // The foreground service can outlive the Compose process state. Resolve from the
        // signed disk cache first, then refresh the signed catalog once when the cache is stale.
        val entry = resolveLyricAlignmentEntry(modelId, modelVersion)
            ?: error("歌词对齐模型不存在或仓库索引尚未刷新")
        require(isAppVersionCompatible(entry.minimumAppVersion)) {
            "当前 RawSMusic 版本低于歌词对齐模型要求 ${entry.minimumAppVersion}"
        }
        val totalBytes = entry.modelSizeBytes + entry.vocabularySizeBytes
        ensureFreeSpace(totalBytes * 2L + EXTRA_FREE_SPACE_BYTES)
        val modelPart = File(
            lyricAlignmentDownloadDir,
            "${entry.id}-${entry.version}-${entry.modelFile}.part",
        )
        val vocabularyPart = File(
            lyricAlignmentDownloadDir,
            "${entry.id}-${entry.version}-${entry.vocabularyFile}.part",
        )
        try {
            onPhase(AiSeparationDownloadPhase.DOWNLOADING)
            downloadLyricArtifact(
                urls = entry.modelDownloadUrls,
                target = modelPart,
                expectedBytes = entry.modelSizeBytes,
                expectedSha256 = entry.modelSha256,
                completedBytes = 0L,
                totalBytes = totalBytes,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            downloadLyricArtifact(
                urls = entry.vocabularyDownloadUrls,
                target = vocabularyPart,
                expectedBytes = entry.vocabularySizeBytes,
                expectedSha256 = entry.vocabularySha256,
                completedBytes = entry.modelSizeBytes,
                totalBytes = totalBytes,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            onPhase(AiSeparationDownloadPhase.INSTALLING)
            installVerifiedLyricAlignment(modelPart, vocabularyPart, entry)
        } finally {
            modelPart.delete()
            vocabularyPart.delete()
        }
    }

    /** Resolves a lyric model without trusting the service's potentially stale in-memory state. */
    suspend fun resolveLyricAlignmentEntry(
        modelId: String,
        modelVersion: String,
    ): AiLyricAlignmentCatalogEntry? = withContext(Dispatchers.IO) {
        readVerifiedCachedLyricAlignmentCatalog()
            .firstOrNull { it.id == modelId && it.version == modelVersion }
            ?: refreshLyricAlignmentCatalog().getOrNull()
                ?.firstOrNull { it.id == modelId && it.version == modelVersion }
    }

    /** Synchronous disk-cache lookup used only to populate the foreground notification. */
    fun cachedLyricAlignmentEntry(
        modelId: String,
        modelVersion: String,
    ): AiLyricAlignmentCatalogEntry? = runCatching {
        readVerifiedCachedLyricAlignmentCatalog()
            .firstOrNull { it.id == modelId && it.version == modelVersion }
    }.getOrNull()

    suspend fun selectLyricAlignmentModel(id: String, version: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                mutationMutex.withLock {
                    require(scanInstalledLyricAlignmentModels().any {
                        it.catalog.id == id && it.catalog.version == version
                    }) { "歌词对齐模型尚未安装" }
                    preferences.edit()
                        .putString(KEY_SELECTED_LYRIC_ID, id)
                        .putString(KEY_SELECTED_LYRIC_VERSION, version)
                        .apply()
                    mutableState.value = loadState().copy(lastError = "")
                }
            }.onFailure { error -> publishError(error) }
        }

    fun selectedLyricAlignmentModel(): AiLyricAlignmentInstalledModel? {
        val current = mutableState.value
        return current.installedLyricAlignmentModels.firstOrNull {
            it.catalog.id == current.selectedLyricAlignmentId &&
                it.catalog.version == current.selectedLyricAlignmentVersion
        }?.takeIf { it.modelFile.isFile && it.vocabularyFile.isFile }
    }

    fun selectedLyricAlignmentModelFile(): File? = selectedLyricAlignmentModel()?.modelFile

    fun selectedLyricAlignmentVocabularyFile(): File? = selectedLyricAlignmentModel()?.vocabularyFile

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

    private fun downloadLyricArtifact(
        urls: List<String>,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
        completedBytes: Long,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var lastError: Throwable? = null
        for (url in urls) {
            if (isCancelled()) throw CancellationException("下载已取消")
            try {
                Log.i(
                    TAG,
                    "AI_LYRIC_ARTIFACT_START file=${target.name} host=${runCatching { URL(url).host }.getOrDefault("invalid")} " +
                        "expectedBytes=$expectedBytes expectedSha256=$expectedSha256",
                )
                downloadWithResume(
                    sourceUrl = url,
                    target = target,
                    expectedBytes = expectedBytes,
                    onProgress = { downloaded, _ ->
                        onProgress(completedBytes + downloaded, totalBytes)
                    },
                    isCancelled = isCancelled,
                )
                require(target.length() == expectedBytes) {
                    "歌词对齐文件大小不匹配：${target.length()}/$expectedBytes"
                }
                val actualSha256 = sha256(target)
                Log.i(
                    TAG,
                    "AI_LYRIC_ARTIFACT_VERIFY file=${target.name} bytes=${target.length()} sha256=$actualSha256",
                )
                require(actualSha256 == expectedSha256) {
                    "歌词对齐文件 SHA-256 校验失败"
                }
                return
            } catch (error: Throwable) {
                lastError = error
                Log.e(TAG, "AI_LYRIC_ARTIFACT_FAILED file=${target.name}", error)
                AppLogger.e(TAG, "Lyric alignment artifact mirror failed: $url", error)
                if (error is CancellationException) throw error
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException("所有歌词对齐文件下载地址均失败")
    }

    private suspend fun installVerifiedLyricAlignment(
        model: File,
        vocabulary: File,
        expected: AiLyricAlignmentCatalogEntry,
    ): AiLyricAlignmentInstalledModel = mutationMutex.withLock {
        require(model.length() == expected.modelSizeBytes)
        require(vocabulary.length() == expected.vocabularySizeBytes)
        require(sha256(model) == expected.modelSha256) { "歌词对齐模型校验失败" }
        require(sha256(vocabulary) == expected.vocabularySha256) { "歌词对齐词表校验失败" }
        val staging = File(lyricAlignmentModelsDir, ".staging_${expected.id}_${System.nanoTime()}")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建歌词对齐模型暂存目录" }
        try {
            copyAndSync(model, File(staging, expected.modelFile))
            copyAndSync(vocabulary, File(staging, expected.vocabularyFile))
            require(File(staging, expected.modelFile).length() == expected.modelSizeBytes)
            require(File(staging, expected.vocabularyFile).length() == expected.vocabularySizeBytes)
            val installedAt = System.currentTimeMillis()
            atomicWrite(
                File(staging, LYRIC_ALIGNMENT_MANIFEST),
                AiSeparationJson.lyricAlignmentManifestJson(expected, installedAt)
                    .toByteArray(Charsets.UTF_8),
            )
            val target = lyricAlignmentVersionDir(expected.id, expected.version)
            target.parentFile?.mkdirs()
            val backup = File(target.parentFile, ".backup_${target.name}_${System.nanoTime()}")
            if (target.exists()) require(target.renameTo(backup)) { "无法备份旧歌词对齐模型" }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装歌词对齐模型")
            }
            backup.deleteRecursively()
            val selection = preferences.edit()
            if (preferences.getString(KEY_SELECTED_LYRIC_ID, "").isNullOrBlank()) {
                selection
                    .putString(KEY_SELECTED_LYRIC_ID, expected.id)
                    .putString(KEY_SELECTED_LYRIC_VERSION, expected.version)
            }
            selection.apply()
            val installed = AiLyricAlignmentInstalledModel(expected, target.absolutePath, installedAt)
            mutableState.value = loadState().copy(lastError = "")
            installed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun copyAndSync(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
    }

    suspend fun importRuntime(uri: Uri): Result<AiRuntimeCatalogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = (
                readVerifiedCachedRuntimes() + AiRecommendedRuntime.ONNX_RUNTIME_1_26
            ).filter { it.abi in Build.SUPPORTED_ABIS }
            val imported = File(downloadDir, "manual_runtime_${System.nanoTime()}.bin")
            val extracted = File(downloadDir, "manual_runtime_${System.nanoTime()}.so")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedOutputStream(FileOutputStream(imported)).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                } ?: error("无法读取运行库")
                val hash = sha256(imported)
                val rawExpected = entries.firstOrNull {
                    it.librarySizeBytes == imported.length() && it.librarySha256 == hash
                }
                val expected = rawExpected ?: extractVerifiedImportedRuntimeArchive(
                    archive = imported,
                    output = extracted,
                    entries = entries,
                ) ?: error("运行库不在已验签的仓库索引中")
                val source = if (rawExpected != null) imported else extracted
                installVerifiedRuntime(source, expected)
            } finally {
                imported.delete()
                extracted.delete()
            }
        }.onFailure { error -> publishError(error) }
    }

    private fun extractVerifiedImportedRuntimeArchive(
        archive: File,
        output: File,
        entries: List<AiRuntimeCatalogEntry>,
    ): AiRuntimeCatalogEntry? = runCatching {
        ZipFile(archive).use { zip ->
            for (entry in entries) {
                val candidates = listOf(
                    "jni/${entry.abi}/${entry.libraryFile}",
                    if (entry == AiRecommendedRuntime.ONNX_RUNTIME_1_26) {
                        AiRecommendedRuntime.AAR_LIBRARY_PATH
                    } else {
                        ""
                    },
                ).filter(String::isNotBlank).distinct()
                val payload = candidates.firstNotNullOfOrNull(zip::getEntry) ?: continue
                if (payload.isDirectory) continue
                output.delete()
                zip.getInputStream(payload).buffered().use { input ->
                    FileOutputStream(output).use { destination ->
                        input.copyTo(destination, COPY_BUFFER_SIZE)
                        destination.fd.sync()
                    }
                }
                if (output.length() == entry.librarySizeBytes && sha256(output) == entry.librarySha256) {
                    Log.i(
                        TAG,
                        "AI_RUNTIME_IMPORT archive payload verified abi=${entry.abi} " +
                            "bytes=${output.length()} entry=${payload.name}",
                    )
                    return@use entry
                }
            }
            null
        }
    }.getOrNull()

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
            val installed = AiOnnxRuntimeLoader.installedRuntime(appContext)
                ?: error("运行库已写入但安装清单无法重新验证")
            require(installed.first.id == entry.id &&
                installed.first.version == entry.version &&
                installed.first.abi == entry.abi
            ) {
                "运行库安装后重新验证得到其他版本"
            }
            // Do not report INSTALLING -> COMPLETED until the exact same path can actually be
            // dlopen'ed and the Java/JNI ORT environment is usable. This catches SELinux / exec
            // mirror / JNI bridge problems at install time instead of leaving a green download
            // card followed by an unusable separator.
            AiOnnxRuntimeSession.runtimeDetails(appContext).getOrThrow()
            mutableState.value = loadState().copy(lastError = "")
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
                fastVocalAlignmentDir.deleteRecursively()
                fastVocalAlignmentDir.mkdirs()
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

    /**
     * Realtime playback must never fall back to the ordinary/offline selection merely because its
     * tensor layout happens to be compatible. Prefer the explicit realtime choice, otherwise pick
     * the lightest installed catalog entry that is explicitly certified for realtime playback.
     */
    fun preferredRealtimeInstalledModel(): AiSeparationInstalledModel? {
        selectedRealtimeInstalledModel()?.let { return it }
        return mutableState.value.installed
            .asSequence()
            .filter { AiRecommendedModels.isRealtime(it.catalog) }
            .filter { File(it.directory, it.catalog.modelFile).isFile }
            .minWithOrNull(
                compareBy<AiSeparationInstalledModel> { it.catalog.estimatedMemoryMb }
                    .thenBy { it.catalog.segmentSamples }
                    .thenBy { it.catalog.modelSizeBytes }
            )
    }

    /** Resolves the small model used by lyric activity analysis, never the offline RoFormer. */
    fun selectedFastVocalAlignmentModel(): AiSeparationInstalledModel? =
        AiFastVocalAlignmentModel.resolve(mutableState.value.installed)

    fun fastVocalAlignmentBundleDirectory(): File? =
        fastVocalAlignmentDir.takeIf { mutableState.value.fastVocalAlignmentBundleInstalled }

    private suspend fun installFastVocalAlignmentBundle(archive: File) {
        mutationMutex.withLock {
            val staging = File(root, ".staging_fast_vocal_alignment_${System.nanoTime()}")
            staging.deleteRecursively()
            require(staging.mkdirs()) { "无法创建快速对齐模型暂存目录" }
            try {
                val allowed = setOf(
                    AiFastVocalAlignmentBundle.VOCALS_FILE,
                    AiFastVocalAlignmentBundle.ACCOMPANIMENT_FILE,
                    "MODEL_CARD.md",
                    "LICENSE.sherpa-onnx.txt",
                    "LICENSE.spleeter.txt",
                )
                val extractedNames = linkedSetOf<String>()
                var extractedBytes = 0L
                ZipFile(archive).use { zip ->
                    val entries = buildList {
                        val enumeration = zip.entries()
                        while (enumeration.hasMoreElements()) add(enumeration.nextElement())
                    }
                    require(entries.size in 2..MAX_FAST_BUNDLE_ENTRIES) {
                        "快速对齐模型包条目数量无效"
                    }
                    entries.forEach { entry ->
                        val name = entry.name
                        require(!entry.isDirectory && name in allowed && name == File(name).name) {
                            "快速对齐模型包包含不允许的文件：$name"
                        }
                        require(extractedNames.add(name)) { "快速对齐模型包包含重复文件：$name" }
                        require(entry.size in 1..MAX_FAST_BUNDLE_ENTRY_BYTES) {
                            "快速对齐模型包文件大小无效：$name"
                        }
                        val target = File(staging, name)
                        var entryBytes = 0L
                        zip.getInputStream(entry).buffered().use { input ->
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    extractedBytes += read
                                    require(entryBytes <= MAX_FAST_BUNDLE_ENTRY_BYTES) {
                                        "快速对齐模型包条目解压后过大：$name"
                                    }
                                    require(extractedBytes <= MAX_FAST_BUNDLE_EXTRACTED_BYTES) {
                                        "快速对齐模型包解压后过大"
                                    }
                                    output.write(buffer, 0, read)
                                }
                                output.fd.sync()
                            }
                        }
                        require(entryBytes == entry.size) { "快速对齐模型包条目大小不一致：$name" }
                    }
                }
                require(
                    File(staging, AiFastVocalAlignmentBundle.VOCALS_FILE).isFile &&
                        File(staging, AiFastVocalAlignmentBundle.ACCOMPANIMENT_FILE).isFile,
                ) { "快速对齐模型包缺少两个 ONNX 文件" }
                atomicWrite(
                    File(staging, FAST_BUNDLE_MANIFEST),
                    ("id=${AiFastVocalAlignmentBundle.ID}\n" +
                        "version=${AiFastVocalAlignmentBundle.VERSION}\n" +
                        "archiveSha256=${AiFastVocalAlignmentBundle.ARCHIVE_SHA256}\n" +
                        "installedAt=${System.currentTimeMillis()}\n")
                        .toByteArray(Charsets.UTF_8),
                )
                val backup = File(root, ".backup_fast_vocal_alignment_${System.nanoTime()}")
                if (fastVocalAlignmentDir.exists()) {
                    require(fastVocalAlignmentDir.renameTo(backup)) { "无法备份旧快速对齐模型" }
                }
                if (!staging.renameTo(fastVocalAlignmentDir)) {
                    backup.renameTo(fastVocalAlignmentDir)
                    error("无法安装快速对齐模型")
                }
                backup.deleteRecursively()
                mutableState.value = loadState().copy(lastError = "")
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
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
        val lyricAlignmentCatalog = runCatching {
            readVerifiedCachedLyricAlignmentCatalog(repository)
        }.getOrDefault(emptyList())
        val installed = scanInstalledModels()
        val installedLyricAlignmentModels = scanInstalledLyricAlignmentModels()
        val fastVocalAlignmentBundleInstalled = isFastVocalAlignmentBundleInstalled()
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
        val selectedLyricId = preferences.getString(KEY_SELECTED_LYRIC_ID, "").orEmpty()
        val selectedLyricVersion = preferences.getString(KEY_SELECTED_LYRIC_VERSION, "").orEmpty()
        val validLyricSelection = installedLyricAlignmentModels.any {
            it.catalog.id == selectedLyricId && it.catalog.version == selectedLyricVersion
        }
        return AiSeparationStoreState(
            repository = repository,
            catalog = catalog,
            runtimeCatalog = runtimeCatalog,
            lyricAlignmentCatalog = lyricAlignmentCatalog,
            installed = installed,
            installedLyricAlignmentModels = installedLyricAlignmentModels,
            fastVocalAlignmentBundleInstalled = fastVocalAlignmentBundleInstalled,
            selectedModelId = if (validSelection) selectedId else "",
            selectedModelVersion = if (validSelection) selectedVersion else "",
            selectedRealtimeModelId = if (validRealtimeSelection) realtimeId else "",
            selectedRealtimeModelVersion = if (validRealtimeSelection) realtimeVersion else "",
            selectedLyricAlignmentId = if (validLyricSelection) selectedLyricId else "",
            selectedLyricAlignmentVersion = if (validLyricSelection) selectedLyricVersion else "",
        )
    }

    private fun isFastVocalAlignmentBundleInstalled(): Boolean =
        File(fastVocalAlignmentDir, FAST_BUNDLE_MANIFEST).isFile &&
            File(fastVocalAlignmentDir, AiFastVocalAlignmentBundle.VOCALS_FILE).isFile &&
            File(fastVocalAlignmentDir, AiFastVocalAlignmentBundle.ACCOMPANIMENT_FILE).isFile

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

    private fun readVerifiedCachedLyricAlignmentCatalog(
        repository: AiModelRepositoryDescriptor? = readRepository(),
    ): List<AiLyricAlignmentCatalogEntry> {
        if (repository == null || !catalogFile.isFile || !catalogSignatureFile.isFile) return emptyList()
        val bytes = catalogFile.readBytes()
        verifySignature(repository, bytes, catalogSignatureFile.readBytes())
        return AiSeparationJson.parseLyricAlignmentCatalog(bytes.toString(Charsets.UTF_8), repository.id)
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

    private fun scanInstalledLyricAlignmentModels(): List<AiLyricAlignmentInstalledModel> =
        lyricAlignmentModelsDir
            .walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name == LYRIC_ALIGNMENT_MANIFEST }
            .mapNotNull { manifest ->
                runCatching {
                    val (catalog, installedAt) = AiSeparationJson.parseLyricAlignmentInstalled(
                        manifest.readText(),
                    )
                    val dir = manifest.parentFile ?: return@runCatching null
                    val model = File(dir, catalog.modelFile)
                    val vocabulary = File(dir, catalog.vocabularyFile)
                    require(model.isFile && model.length() == catalog.modelSizeBytes)
                    require(vocabulary.isFile && vocabulary.length() == catalog.vocabularySizeBytes)
                    AiLyricAlignmentInstalledModel(catalog, dir.absolutePath, installedAt)
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
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                Log.i(TAG, "AI_STORE_HTTP phase=small code=$code host=${runCatching { URL(current).host }.getOrDefault("invalid")}")
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "下载重定向过多" }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error("下载重定向缺少目标地址")
                    current = URL(URL(current), location).toString()
                    require(current.startsWith("https://")) { "下载重定向必须使用 HTTPS" }
                    return@repeat
                }
                require(code in 200..299) { "HTTP $code${readHttpErrorSuffix(connection)}" }
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
                // Do not allow transparent gzip: it changes the byte stream and invalidates
                // Range offsets, Content-Length and the signed SHA-256 check.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                Log.i(
                    TAG,
                    "AI_STORE_HTTP phase=artifact code=$code host=${runCatching { URL(current).host }.getOrDefault("invalid")} " +
                        "existing=$existing expected=$expectedBytes",
                )
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "下载重定向过多" }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error("下载重定向缺少目标地址")
                    current = URL(URL(current), location).toString()
                    require(current.startsWith("https://")) { "下载重定向必须使用 HTTPS" }
                    return@repeat
                }
                if (code == 416 && existing == expectedBytes) {
                    onProgress(existing, expectedBytes)
                    return
                }
                if (code == 416 && existing > 0L) {
                    // A stale CDN range can survive a failed previous install. Restart once
                    // from byte zero instead of surfacing a misleading HTTP 416 error.
                    target.delete()
                    current = sourceUrl
                    return@repeat
                }
                require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "HTTP $code${readHttpErrorSuffix(connection)}"
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

    private fun readHttpErrorSuffix(connection: HttpURLConnection): String {
        val body = runCatching {
            (connection.errorStream ?: connection.inputStream).bufferedReader().use { reader ->
                reader.readText().take(512).replace(Regex("\\s+"), " ").trim()
            }
        }.getOrDefault("")
        return if (body.isBlank()) "" else ": $body"
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

    private fun lyricAlignmentVersionDir(id: String, version: String): File =
        File(File(lyricAlignmentModelsDir, id), version)

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
        private const val KEY_SELECTED_LYRIC_ID = "selected_lyric_alignment_id"
        private const val KEY_SELECTED_LYRIC_VERSION = "selected_lyric_alignment_version"
        private const val RETIRED_MEL_ROFORMER_ID = "melband.roformer.kim.vocals"
        private const val PACKAGE_MANIFEST = "manifest.json"
        private const val INSTALLED_MANIFEST = "installed.json"
        private const val RUNTIME_MANIFEST = "installed.json"
        private const val LYRIC_ALIGNMENT_MANIFEST = "installed.json"
        private const val FAST_BUNDLE_MANIFEST = "installed.properties"
        private const val MAX_REPOSITORY_BYTES = 256 * 1024
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 16 * 1024
        private const val MAX_PACKAGE_MANIFEST_BYTES = 256 * 1024
        private const val MAX_TEXT_ATTACHMENT_BYTES = 2L * 1024L * 1024L
        private const val MAX_MODEL_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val EXTRA_FREE_SPACE_BYTES = 128L * 1024L * 1024L
        private const val MAX_ZIP_ENTRIES = 16
        private const val MAX_FAST_BUNDLE_ENTRIES = 5
        private const val MAX_FAST_BUNDLE_ENTRY_BYTES = 64L * 1024L * 1024L
        private const val MAX_FAST_BUNDLE_EXTRACTED_BYTES = 100L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MODEL_READ_TIMEOUT_MS = 90_000
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "RawSMusic/1.0.0-release AI-Model-Manager"
        private const val DEFAULT_REPOSITORY_URL =
            "https://github.com/QFDY-GZC/RawS-Music/releases/download/" +
                "rawsmusic-ai-models-v1.0.0/repository.json"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val BASE64_SIGNATURE = Regex("[A-Za-z0-9+/]+={0,2}")

        @Volatile private var instance: AiSeparationPluginStore? = null

        fun get(context: Context): AiSeparationPluginStore = instance ?: synchronized(this) {
            instance ?: AiSeparationPluginStore(context).also { instance = it }
        }
    }
}
