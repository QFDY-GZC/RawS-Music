package com.rawsmusic.separation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class AiModelRepositoryDescriptor(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val indexUrl: String,
    val signatureUrl: String,
    val keyAlgorithm: String,
    val signatureAlgorithm: String,
    val publicKeyBase64: String,
)

data class AiSeparationModelContract(
    val task: String,
    val tensorLayout: String,
    val tensorDataType: String,
    val inputName: String,
    val outputName: String,
    val inputShape: List<Long>,
    val outputShape: List<Long>,
    val fftSize: Int,
    val hopLength: Int,
    val frequencyBins: Int,
    val timeFrames: Int,
    val window: String,
    val center: Boolean,
    val paddingMode: String,
    val normalization: String,
    val outputType: String,
    val intraOpThreads: Int,
    val chunkMode: String = "generic_full_segment",
    val edgeTrimSamples: Int = 0,
    val compensation: Double = 1.0,
    val supportsDenoise: Boolean = false,
) {
    val tensorElementCount: Long
        get() = inputShape.fold(1L) { acc, value -> Math.multiplyExact(acc, value) }

    val outputElementCount: Long
        get() = outputShape.fold(1L) { acc, value -> Math.multiplyExact(acc, value) }
}

data class AiSeparationCatalogEntry(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val architecture: String,
    val modelFormat: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val modelFile: String,
    val modelSizeBytes: Long,
    val modelSha256: String,
    val sampleRate: Int,
    val channels: Int,
    val segmentSamples: Long,
    val overlap: Double,
    val estimatedMemoryMb: Int,
    val minimumAppVersion: String,
    val downloadUrls: List<String>,
    val contract: AiSeparationModelContract? = null,
) {
    val executable: Boolean get() = schemaVersion in 2..4 && contract != null
}

data class AiRuntimeCatalogEntry(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val abi: String,
    val libraryFile: String,
    val librarySizeBytes: Long,
    val librarySha256: String,
    val minimumAppVersion: String,
    val downloadUrls: List<String>,
)

data class AiModelPackageManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val architecture: String,
    val modelFormat: String,
    val modelFile: String,
    val modelSizeBytes: Long,
    val modelSha256: String,
    val sampleRate: Int,
    val channels: Int,
    val segmentSamples: Long,
    val overlap: Double,
    val estimatedMemoryMb: Int,
    val minimumAppVersion: String,
    val contract: AiSeparationModelContract? = null,
)

data class AiSeparationInstalledModel(
    val catalog: AiSeparationCatalogEntry,
    val directory: String,
    val installedAtEpochMs: Long,
) {
    val executable: Boolean get() = catalog.executable
}

data class AiSeparationStoreState(
    val repository: AiModelRepositoryDescriptor? = null,
    val catalog: List<AiSeparationCatalogEntry> = emptyList(),
    val runtimeCatalog: List<AiRuntimeCatalogEntry> = emptyList(),
    val installed: List<AiSeparationInstalledModel> = emptyList(),
    val selectedModelId: String = "",
    val selectedModelVersion: String = "",
    val selectedRealtimeModelId: String = "",
    val selectedRealtimeModelVersion: String = "",
    val lastError: String = "",
) {
    fun isInstalled(entry: AiSeparationCatalogEntry): Boolean = installed.any {
        it.catalog.id == entry.id && it.catalog.version == entry.version
    }

    fun isSelected(entry: AiSeparationCatalogEntry): Boolean =
        selectedModelId == entry.id && selectedModelVersion == entry.version

    fun selectedInstalledModel(): AiSeparationInstalledModel? = installed.firstOrNull {
        it.catalog.id == selectedModelId && it.catalog.version == selectedModelVersion
    }

    fun isRealtimeSelected(entry: AiSeparationCatalogEntry): Boolean =
        selectedRealtimeModelId == entry.id && selectedRealtimeModelVersion == entry.version

    fun selectedRealtimeInstalledModel(): AiSeparationInstalledModel? = installed.firstOrNull {
        it.catalog.id == selectedRealtimeModelId &&
            it.catalog.version == selectedRealtimeModelVersion
    }
}

internal object AiSeparationJson {
    fun parseRepository(json: String): AiModelRepositoryDescriptor {
        val root = JsonParser.parseString(json).asJsonObject
        val descriptor = AiModelRepositoryDescriptor(
            schemaVersion = root.int("schemaVersion"),
            id = root.string("id"),
            name = root.string("name"),
            indexUrl = root.string("indexUrl"),
            signatureUrl = root.string("signatureUrl"),
            keyAlgorithm = root.string("keyAlgorithm", "EC"),
            signatureAlgorithm = root.string("signatureAlgorithm", "SHA256withECDSA"),
            publicKeyBase64 = root.string("publicKeyBase64"),
        )
        require(descriptor.schemaVersion == 1) { "不支持的仓库描述格式" }
        require(descriptor.id.matches(SAFE_ID)) { "仓库 ID 无效" }
        require(descriptor.name.isNotBlank()) { "仓库名称为空" }
        require(descriptor.indexUrl.startsWith("https://")) { "仓库索引必须使用 HTTPS" }
        require(descriptor.signatureUrl.startsWith("https://")) { "仓库签名必须使用 HTTPS" }
        require(descriptor.publicKeyBase64.isNotBlank()) { "仓库公钥为空" }
        require(descriptor.publicKeyBase64.length <= MAX_PUBLIC_KEY_BASE64_CHARS) { "仓库公钥过大" }
        require(descriptor.publicKeyBase64.matches(BASE64_TEXT)) { "仓库公钥必须是 Base64 文本" }
        require(descriptor.signatureAlgorithm in SUPPORTED_SIGNATURES) { "不支持的签名算法" }
        require(
            (descriptor.keyAlgorithm == "EC" && descriptor.signatureAlgorithm == "SHA256withECDSA") ||
                (descriptor.keyAlgorithm == "Ed25519" && descriptor.signatureAlgorithm == "Ed25519")
        ) { "公钥类型与签名算法不匹配" }
        return descriptor
    }

    fun parseCatalog(json: String, expectedRepositoryId: String): List<AiSeparationCatalogEntry> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.int("schemaVersion") == 1) { "不支持的模型索引格式" }
        require(root.string("repositoryId") == expectedRepositoryId) { "模型索引仓库 ID 不匹配" }
        val models = root.getAsJsonArray("models") ?: error("模型索引缺少 models")
        val parsed = models.map { element -> parseCatalogEntry(element.asJsonObject) }
        require(parsed.distinctBy { it.id to it.version }.size == parsed.size) {
            "模型索引包含重复的 ID 和版本"
        }
        return parsed
    }

    fun parseRuntimeCatalog(json: String, expectedRepositoryId: String): List<AiRuntimeCatalogEntry> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.int("schemaVersion") == 1) { "不支持的模型索引格式" }
        require(root.string("repositoryId") == expectedRepositoryId) { "模型索引仓库 ID 不匹配" }
        val parsed = root.getAsJsonArray("runtimes")?.map { element ->
            val item = element.asJsonObject
            AiRuntimeCatalogEntry(
                schemaVersion = item.int("schemaVersion", 1),
                id = item.string("id"),
                name = item.string("name"),
                version = item.string("version"),
                abi = item.string("abi"),
                libraryFile = item.string("libraryFile"),
                librarySizeBytes = item.long("librarySizeBytes"),
                librarySha256 = item.string("librarySha256").lowercase(),
                minimumAppVersion = item.string("minimumAppVersion"),
                downloadUrls = item.getAsJsonArray("downloadUrls")?.map { urlElement ->
                    urlElement.asString.also { url ->
                        require(url.startsWith("https://")) { "运行库下载地址必须使用 HTTPS" }
                    }
                }.orEmpty(),
            ).also(::validateRuntime)
        }.orEmpty()
        require(parsed.distinctBy { Triple(it.id, it.version, it.abi) }.size == parsed.size) {
            "运行库索引包含重复的 ID、版本和 ABI"
        }
        return parsed
    }

    fun parsePackageManifest(json: String): AiModelPackageManifest {
        val root = JsonParser.parseString(json).asJsonObject
        val schemaVersion = root.int("schemaVersion")
        val manifest = AiModelPackageManifest(
            schemaVersion = schemaVersion,
            id = root.string("id"),
            name = root.string("name"),
            version = root.string("version"),
            description = root.string("description"),
            architecture = root.string("architecture"),
            modelFormat = root.string("modelFormat"),
            modelFile = root.string("modelFile"),
            modelSizeBytes = root.long("modelSizeBytes"),
            modelSha256 = root.string("modelSha256").lowercase(),
            sampleRate = root.int("sampleRate"),
            channels = root.int("channels"),
            segmentSamples = root.long("segmentSamples"),
            overlap = root.double("overlap"),
            estimatedMemoryMb = root.int("estimatedMemoryMb"),
            minimumAppVersion = root.string("minimumAppVersion"),
            contract = root.getAsJsonObject("contract")?.let(::parseContract),
        )
        validateCommon(
            schemaVersion = manifest.schemaVersion,
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            architecture = manifest.architecture,
            modelFormat = manifest.modelFormat,
            modelFile = manifest.modelFile,
            modelSizeBytes = manifest.modelSizeBytes,
            modelSha256 = manifest.modelSha256,
            sampleRate = manifest.sampleRate,
            channels = manifest.channels,
            segmentSamples = manifest.segmentSamples,
            overlap = manifest.overlap,
            estimatedMemoryMb = manifest.estimatedMemoryMb,
            contract = manifest.contract,
        )
        return manifest
    }

    fun packageManifestJson(entry: AiSeparationCatalogEntry, installedAtEpochMs: Long): String =
        catalogEntryJson(entry).apply {
            addProperty("installedAtEpochMs", installedAtEpochMs)
        }.toString()

    fun parseInstalled(json: String): Pair<AiSeparationCatalogEntry, Long> {
        val root = JsonParser.parseString(json).asJsonObject
        return parseCatalogEntry(root) to root.long("installedAtEpochMs")
    }

    fun contractJson(contract: AiSeparationModelContract): String = contractJsonObject(contract).toString()

    fun runtimeManifestJson(entry: AiRuntimeCatalogEntry): String = JsonObject().apply {
        addProperty("schemaVersion", entry.schemaVersion)
        addProperty("id", entry.id)
        addProperty("name", entry.name)
        addProperty("version", entry.version)
        addProperty("abi", entry.abi)
        addProperty("libraryFile", entry.libraryFile)
        addProperty("librarySizeBytes", entry.librarySizeBytes)
        addProperty("librarySha256", entry.librarySha256)
        addProperty("minimumAppVersion", entry.minimumAppVersion)
        add("downloadUrls", JsonArray().apply { entry.downloadUrls.forEach(::add) })
    }.toString()

    fun parseRuntimeManifest(json: String): AiRuntimeCatalogEntry {
        val root = JsonParser.parseString(json).asJsonObject
        return AiRuntimeCatalogEntry(
            schemaVersion = root.int("schemaVersion", 1),
            id = root.string("id"),
            name = root.string("name"),
            version = root.string("version"),
            abi = root.string("abi"),
            libraryFile = root.string("libraryFile"),
            librarySizeBytes = root.long("librarySizeBytes"),
            librarySha256 = root.string("librarySha256").lowercase(),
            minimumAppVersion = root.string("minimumAppVersion"),
            downloadUrls = root.getAsJsonArray("downloadUrls")?.map { it.asString }.orEmpty(),
        ).also(::validateRuntime)
    }

    private fun parseCatalogEntry(root: JsonObject): AiSeparationCatalogEntry {
        val schemaVersion = root.int("schemaVersion", 1)
        val entry = AiSeparationCatalogEntry(
            schemaVersion = schemaVersion,
            id = root.string("id"),
            name = root.string("name"),
            version = root.string("version"),
            description = root.string("description"),
            architecture = root.string("architecture"),
            modelFormat = root.string("modelFormat"),
            archiveSizeBytes = root.long("archiveSizeBytes"),
            archiveSha256 = root.string("archiveSha256").lowercase(),
            modelFile = root.string("modelFile"),
            modelSizeBytes = root.long("modelSizeBytes"),
            modelSha256 = root.string("modelSha256").lowercase(),
            sampleRate = root.int("sampleRate"),
            channels = root.int("channels"),
            segmentSamples = root.long("segmentSamples"),
            overlap = root.double("overlap"),
            estimatedMemoryMb = root.int("estimatedMemoryMb"),
            minimumAppVersion = root.string("minimumAppVersion"),
            downloadUrls = root.getAsJsonArray("downloadUrls")?.map { element ->
                element.asString.also { url ->
                    require(url.startsWith("https://")) { "模型下载地址必须使用 HTTPS" }
                }
            }.orEmpty(),
            contract = root.getAsJsonObject("contract")?.let(::parseContract),
        )
        validateCommon(
            schemaVersion = entry.schemaVersion,
            id = entry.id,
            name = entry.name,
            version = entry.version,
            architecture = entry.architecture,
            modelFormat = entry.modelFormat,
            modelFile = entry.modelFile,
            modelSizeBytes = entry.modelSizeBytes,
            modelSha256 = entry.modelSha256,
            sampleRate = entry.sampleRate,
            channels = entry.channels,
            segmentSamples = entry.segmentSamples,
            overlap = entry.overlap,
            estimatedMemoryMb = entry.estimatedMemoryMb,
            contract = entry.contract,
        )
        require(entry.archiveSizeBytes in 1..MAX_ARCHIVE_BYTES) { "模型包大小无效" }
        require(entry.archiveSha256.matches(SHA256)) { "模型包 SHA-256 无效" }
        require(entry.downloadUrls.isNotEmpty()) { "模型没有 HTTPS 下载地址" }
        return entry
    }

    private fun validateCommon(
        schemaVersion: Int,
        id: String,
        name: String,
        version: String,
        architecture: String,
        modelFormat: String,
        modelFile: String,
        modelSizeBytes: Long,
        modelSha256: String,
        sampleRate: Int,
        channels: Int,
        segmentSamples: Long,
        overlap: Double,
        estimatedMemoryMb: Int,
        contract: AiSeparationModelContract?,
    ) {
        require(schemaVersion in 1..4) { "不支持的模型包格式" }
        require(id.matches(SAFE_ID)) { "模型 ID 无效" }
        require(name.isNotBlank()) { "模型名称为空" }
        require(version.matches(SAFE_VERSION)) { "模型版本无效" }
        require(architecture.isNotBlank()) { "模型架构为空" }
        require(modelFormat in setOf("ort", "onnx")) { "模型格式必须是 ort 或 onnx" }
        require(modelFile.matches(SAFE_FILE_NAME)) { "模型文件名无效" }
        require(modelFile == "model.$modelFormat") { "模型文件必须命名为 model.$modelFormat" }
        require(modelSizeBytes in 1..MAX_MODEL_BYTES) { "模型文件大小无效" }
        require(modelSha256.matches(SHA256)) { "模型文件 SHA-256 无效" }
        require(sampleRate in 8_000..192_000) { "模型采样率无效" }
        require(channels in 1..8) { "模型声道数无效" }
        require(segmentSamples in 1..sampleRate.toLong() * MAX_SEGMENT_SECONDS) { "模型分块长度无效" }
        require(overlap in 0.0..0.75) { "模型重叠比例无效" }
        require(estimatedMemoryMb in 1..16_384) { "模型内存估算无效" }
        if (schemaVersion >= 2) {
            require(contract != null) { "可执行模型缺少 contract" }
            validateExecutableContract(schemaVersion, contract, sampleRate, channels, segmentSamples, overlap)
        } else {
            require(contract == null) { "schema 1 不允许携带可执行 contract" }
        }
    }

    private fun parseContract(root: JsonObject): AiSeparationModelContract = AiSeparationModelContract(
        task = root.string("task"),
        tensorLayout = root.string("tensorLayout"),
        tensorDataType = root.string("tensorDataType"),
        inputName = root.string("inputName"),
        outputName = root.string("outputName"),
        inputShape = root.longList("inputShape"),
        outputShape = root.longList("outputShape"),
        fftSize = root.int("fftSize"),
        hopLength = root.int("hopLength"),
        frequencyBins = root.int("frequencyBins"),
        timeFrames = root.int("timeFrames"),
        window = root.string("window"),
        center = root.bool("center", true),
        paddingMode = root.string("paddingMode"),
        normalization = root.string("normalization"),
        outputType = root.string("outputType"),
        intraOpThreads = root.int("intraOpThreads", 2),
        chunkMode = root.string("chunkMode", "generic_full_segment"),
        edgeTrimSamples = root.int("edgeTrimSamples", 0),
        compensation = root.double("compensation", 1.0),
        supportsDenoise = root.bool("supportsDenoise", false),
    )

    private fun validateExecutableContract(
        schemaVersion: Int,
        contract: AiSeparationModelContract,
        sampleRate: Int,
        channels: Int,
        segmentSamples: Long,
        overlap: Double,
    ) {
        require(contract.task == "vocals_2stem") { "当前仅支持 vocals_2stem" }
        require(contract.tensorDataType == "float32") { "公开输入输出必须是 float32" }
        require(contract.inputName == "*" || contract.inputName.matches(SAFE_TENSOR_NAME)) { "模型输入张量名无效" }
        require(contract.outputName == "*" || contract.outputName.matches(SAFE_TENSOR_NAME)) { "模型输出张量名无效" }
        if (schemaVersion == 2) {
            require(contract.inputName != "*" && contract.outputName != "*") { "schema 2 必须声明精确张量名" }
        }
        require(channels == 2) { "当前可执行模型必须是双声道" }
        require(contract.tensorLayout == "bcft_complex_channels") { "不支持的张量布局" }
        require(contract.fftSize in 256..32_768 && contract.fftSize % 2 == 0) { "fftSize 无效" }
        require(contract.hopLength in 1..contract.fftSize) { "hopLength 无效" }
        require(contract.frequencyBins in 1..(contract.fftSize / 2 + 1)) { "frequencyBins 无效" }
        require(contract.timeFrames in 1..65_536) { "timeFrames 无效" }
        val expectedShape = listOf(
            1L,
            4L,
            contract.frequencyBins.toLong(),
            contract.timeFrames.toLong(),
        )
        require(contract.inputShape == expectedShape) { "inputShape 必须为 [1,4,F,T]" }
        require(contract.outputShape == expectedShape) { "outputShape 必须与输入一致" }
        require(contract.tensorElementCount in 1..MAX_TENSOR_FLOATS) { "单张量元素数量过大" }
        require(contract.window == "hann") { "当前仅支持 Hann 窗" }
        require(contract.paddingMode in setOf("reflect", "constant")) { "paddingMode 无效" }
        require(contract.normalization in setOf("none", "global_mean_std")) { "normalization 无效" }
        require(contract.outputType in setOf("complex_spectrogram", "complex_mask")) { "outputType 无效" }
        require(contract.intraOpThreads in 1..8) { "ORT 线程数无效" }
        require(contract.chunkMode in setOf("generic_full_segment", "uvr_mdx_center_trim")) { "不支持的分块模式" }
        require(contract.edgeTrimSamples in 0 until segmentSamples.toInt()) { "edgeTrimSamples 无效" }
        require(contract.compensation in 0.1..4.0) { "compensation 无效" }
        require(overlap in 0.0..0.5) { "可执行模型 overlap 必须在 0～0.5" }
        require(segmentSamples <= Int.MAX_VALUE.toLong()) { "segmentSamples 超出 native 支持范围" }
        val centerPad = if (contract.center) contract.fftSize else 0
        val covered = (contract.timeFrames - 1L) * contract.hopLength + contract.fftSize
        require(covered >= segmentSamples + centerPad) { "STFT 帧数不足以覆盖分块" }
        if (contract.chunkMode == "generic_full_segment") {
            require(contract.edgeTrimSamples == 0) { "通用分块不允许 edge trim" }
        } else {
            require(schemaVersion >= 3) { "UVR MDX 精确分块需要 schema 3" }
            require(contract.center && contract.paddingMode == "reflect") { "UVR MDX 必须使用 center reflect STFT" }
            require(contract.normalization == "none") { "UVR MDX 9482 不使用输入归一化" }
            require(contract.outputType == "complex_spectrogram") { "UVR MDX 必须输出复数频谱" }
            require(contract.frequencyBins <= contract.fftSize / 2) {
                "UVR MDX frequencyBins 必须排除 Nyquist，且允许裁掉未建模的高频 bin"
            }
            require(segmentSamples == contract.hopLength.toLong() * (contract.timeFrames - 1L)) {
                "UVR MDX segmentSamples 必须等于 hop*(T-1)"
            }
            require(contract.edgeTrimSamples == contract.fftSize / 2) { "UVR MDX edge trim 必须为 n_fft/2" }
            require(segmentSamples - 2L * contract.edgeTrimSamples > 0L) { "UVR MDX useful chunk 长度无效" }
            require(!contract.supportsDenoise || contract.outputType == "complex_spectrogram") {
                "双推理去噪只支持复数频谱输出"
            }
        }
        require(sampleRate > 0) { "采样率无效" }
    }

    private fun validateRuntime(entry: AiRuntimeCatalogEntry) {
        require(entry.schemaVersion == 1) { "不支持的运行库格式" }
        require(entry.id.matches(SAFE_ID)) { "运行库 ID 无效" }
        require(entry.name.isNotBlank()) { "运行库名称为空" }
        require(entry.version.matches(SAFE_VERSION)) { "运行库版本无效" }
        require(entry.abi in SUPPORTED_RUNTIME_ABIS) { "运行库 ABI 无效" }
        require(entry.libraryFile == ONNX_RUNTIME_LIBRARY) { "运行库文件名无效" }
        require(entry.librarySizeBytes in 1..MAX_RUNTIME_BYTES) { "运行库大小无效" }
        require(entry.librarySha256.matches(SHA256)) { "运行库 SHA-256 无效" }
        require(entry.downloadUrls.isNotEmpty()) { "运行库没有 HTTPS 下载地址" }
    }

    private fun catalogEntryJson(entry: AiSeparationCatalogEntry): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", entry.schemaVersion)
        addProperty("id", entry.id)
        addProperty("name", entry.name)
        addProperty("version", entry.version)
        addProperty("description", entry.description)
        addProperty("architecture", entry.architecture)
        addProperty("modelFormat", entry.modelFormat)
        addProperty("archiveSizeBytes", entry.archiveSizeBytes)
        addProperty("archiveSha256", entry.archiveSha256)
        addProperty("modelFile", entry.modelFile)
        addProperty("modelSizeBytes", entry.modelSizeBytes)
        addProperty("modelSha256", entry.modelSha256)
        addProperty("sampleRate", entry.sampleRate)
        addProperty("channels", entry.channels)
        addProperty("segmentSamples", entry.segmentSamples)
        addProperty("overlap", entry.overlap)
        addProperty("estimatedMemoryMb", entry.estimatedMemoryMb)
        addProperty("minimumAppVersion", entry.minimumAppVersion)
        add("downloadUrls", JsonArray().apply { entry.downloadUrls.forEach { value -> add(value) } })
        entry.contract?.let { add("contract", contractJsonObject(it)) }
    }

    private fun contractJsonObject(contract: AiSeparationModelContract): JsonObject = JsonObject().apply {
        addProperty("task", contract.task)
        addProperty("tensorLayout", contract.tensorLayout)
        addProperty("tensorDataType", contract.tensorDataType)
        addProperty("inputName", contract.inputName)
        addProperty("outputName", contract.outputName)
        add("inputShape", JsonArray().apply { contract.inputShape.forEach { value -> add(value) } })
        add("outputShape", JsonArray().apply { contract.outputShape.forEach { value -> add(value) } })
        addProperty("fftSize", contract.fftSize)
        addProperty("hopLength", contract.hopLength)
        addProperty("frequencyBins", contract.frequencyBins)
        addProperty("timeFrames", contract.timeFrames)
        addProperty("window", contract.window)
        addProperty("center", contract.center)
        addProperty("paddingMode", contract.paddingMode)
        addProperty("normalization", contract.normalization)
        addProperty("outputType", contract.outputType)
        addProperty("intraOpThreads", contract.intraOpThreads)
        addProperty("chunkMode", contract.chunkMode)
        addProperty("edgeTrimSamples", contract.edgeTrimSamples)
        addProperty("compensation", contract.compensation)
        addProperty("supportsDenoise", contract.supportsDenoise)
    }

    private fun JsonObject.string(key: String, fallback: String = ""): String = get(key)?.let {
        runCatching { it.asString }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.int(key: String, fallback: Int = 0): Int = get(key)?.let {
        runCatching { it.asInt }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.long(key: String, fallback: Long = 0L): Long = get(key)?.let {
        runCatching { it.asLong }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.double(key: String, fallback: Double = 0.0): Double = get(key)?.let {
        runCatching { it.asDouble }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean = get(key)?.let {
        runCatching { it.asBoolean }.getOrDefault(fallback)
    } ?: fallback

    private fun JsonObject.longList(key: String): List<Long> = getAsJsonArray(key)?.map { it.asLong }.orEmpty()

    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,96}")
    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,128}")
    private val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
    private val SAFE_TENSOR_NAME = Regex("[^\\u0000\\r\\n]{1,256}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SUPPORTED_SIGNATURES = setOf("SHA256withECDSA", "Ed25519")
    private val SUPPORTED_RUNTIME_ABIS = setOf("arm64-v8a")
    private val BASE64_TEXT = Regex("[A-Za-z0-9+/]+={0,2}")
    private const val ONNX_RUNTIME_LIBRARY = "libonnxruntime.so"
    private const val MAX_PUBLIC_KEY_BASE64_CHARS = 16 * 1024
    private const val MAX_SEGMENT_SECONDS = 60L
    private const val MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_RUNTIME_BYTES = 256L * 1024L * 1024L
    private const val MAX_TENSOR_FLOATS = 32L * 1024L * 1024L
}
