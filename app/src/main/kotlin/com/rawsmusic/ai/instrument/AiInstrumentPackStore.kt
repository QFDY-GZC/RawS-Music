package com.rawsmusic.ai.instrument

import android.content.Context
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
import java.security.MessageDigest
import java.util.zip.ZipFile

internal fun isCanonicalInstrumentPackDirectory(
    directory: File,
    manifest: AiInstrumentPackManifest,
): Boolean = directory.name == manifest.version && directory.parentFile?.name == manifest.id

/** Verified owner for user/imported instrument sample packs. No third-party samples ship in APK. */
class AiInstrumentPackStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_instrument/packs").apply { mkdirs() }
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    private fun publishChanged() {
        mutableRevision.value = mutableRevision.value + 1L
    }

    fun installedPacks(): List<AiInstalledInstrumentPack> = root.listFiles(File::isDirectory).orEmpty()
        .flatMap { idDir -> idDir.listFiles(File::isDirectory).orEmpty().toList() }
        .mapNotNull { dir -> runCatching { loadInstalled(dir) }.getOrNull() }
        .sortedWith(compareBy({ it.manifest.instrument }, { it.manifest.displayName }, { it.manifest.version }))

    fun resolve(id: String, version: String): AiInstalledInstrumentPack? =
        runCatching { loadInstalled(File(File(root, id), version)) }.getOrNull()

    suspend fun installFromZip(archive: File): Result<AiInstalledInstrumentPack> = withContext(Dispatchers.IO) {
        runCatching {
            require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "乐器采样包大小无效" }
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_FILE) ?: error("乐器采样包缺少 manifest.json")
                require(!manifestEntry.isDirectory && manifestEntry.size in 1..MAX_MANIFEST_BYTES) { "乐器 manifest 无效" }
                val manifestText = zip.getInputStream(manifestEntry).buffered().use { input ->
                    input.readBytesLimited(MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8)
                }
                val manifest = AiInstrumentPackJson.parse(manifestText)
                validateArchiveEntries(zip, manifest)

                val target = File(File(root, manifest.id), manifest.version)
                val staging = File(root, ".${manifest.id}.${manifest.version}.${System.nanoTime()}.tmp")
                staging.deleteRecursively()
                require(staging.mkdirs()) { "无法创建乐器采样包 staging 目录" }
                try {
                    extractVerified(zip, manifest, staging)
                    val verified = loadInstalled(staging, enforceCanonicalIdentity = false)
                    require(verified.manifest == manifest) { "乐器采样包安装后 manifest 不一致" }
                    target.parentFile?.mkdirs()
                    target.deleteRecursively()
                    require(staging.renameTo(target)) { "无法原子安装乐器采样包" }
                    loadInstalled(target).also { publishChanged() }
                } finally {
                    staging.deleteRecursively()
                }
            }
        }
    }


    /** Install a locally prepared pack directory after the exact same manifest/audio verification as ZIP imports. */
    suspend fun installPreparedDirectory(source: File): Result<AiInstalledInstrumentPack> = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isDirectory) { "乐器采样目录不存在" }
            val manifestFile = File(source, MANIFEST_FILE)
            require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES) { "乐器 manifest 无效" }
            val manifest = AiInstrumentPackJson.parse(manifestFile.readText())
            val required = buildSet {
                add(MANIFEST_FILE)
                add(manifest.licenseFile)
                manifest.samples.forEach { add(it.path) }
            }
            val actual = source.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .toSet()
            require(actual == required) { "乐器采样目录包含缺失或未声明文件" }

            val target = File(File(root, manifest.id), manifest.version)
            val staging = File(root, ".${manifest.id}.${manifest.version}.${System.nanoTime()}.tmp")
            staging.deleteRecursively()
            require(staging.mkdirs()) { "无法创建乐器采样包 staging 目录" }
            try {
                copyPreparedFile(File(source, MANIFEST_FILE), File(staging, MANIFEST_FILE), null)
                copyPreparedFile(File(source, manifest.licenseFile), File(staging, manifest.licenseFile), null)
                require(File(staging, manifest.licenseFile).length() in 1..MAX_LICENSE_BYTES) { "乐器采样包许可文件无效" }
                var expandedBytes = File(staging, MANIFEST_FILE).length() + File(staging, manifest.licenseFile).length()
                manifest.samples.forEach { sample ->
                    val targetSample = File(staging, sample.path)
                    expandedBytes += copyPreparedFile(File(source, sample.path), targetSample, sample.sha256.lowercase())
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "乐器采样包实际体积过大" }
                    verifySampleAudio(targetSample, manifest, sample)
                }
                val verified = loadInstalled(staging, enforceCanonicalIdentity = false)
                require(verified.manifest == manifest) { "乐器采样包安装后 manifest 不一致" }
                target.parentFile?.mkdirs()
                target.deleteRecursively()
                require(staging.renameTo(target)) { "无法原子安装乐器采样包" }
                loadInstalled(target).also { publishChanged() }
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun remove(id: String, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) { "乐器采样包 ID 无效" }
            require(version.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}"))) { "乐器采样包版本无效" }
            val target = File(File(root, id), version)
            if (target.exists()) require(target.deleteRecursively()) { "无法删除乐器采样包" }
            target.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
            publishChanged()
            Unit
        }
    }

    private fun validateArchiveEntries(zip: ZipFile, manifest: AiInstrumentPackManifest) {
        val required = buildSet {
            add(MANIFEST_FILE)
            add(manifest.licenseFile)
            manifest.samples.forEach { add(it.path) }
        }
        val seen = mutableSetOf<String>()
        val entries = zip.entries()
        var fileCount = 0
        var expandedBytes = 0L
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name.replace('\\', '/')
            require(AiInstrumentPackManifest.isSafeRelativePath(name)) { "乐器采样包包含不安全路径" }
            if (entry.isDirectory) continue
            require(seen.add(name)) { "乐器采样包包含重复文件: $name" }
            require(name in required) { "乐器采样包包含未声明文件: $name" }
            fileCount++
            require(fileCount <= AiInstrumentPackManifest.MAX_SAMPLES + 2) { "乐器采样包文件过多" }
            require(entry.size >= 0L) { "乐器采样包不接受未知解压大小" }
            expandedBytes += entry.size
            require(expandedBytes <= MAX_EXPANDED_BYTES) { "乐器采样包解压体积过大" }
        }
        require(required == seen) { "乐器采样包缺少 manifest 声明的文件" }
    }

    private fun extractVerified(zip: ZipFile, manifest: AiInstrumentPackManifest, staging: File) {
        var expandedBytes = 0L
        expandedBytes += extract(zip, MANIFEST_FILE, File(staging, MANIFEST_FILE), null)
        expandedBytes += extract(zip, manifest.licenseFile, File(staging, manifest.licenseFile), null)
        require(File(staging, manifest.licenseFile).length() in 1..MAX_LICENSE_BYTES) { "乐器采样包许可文件无效" }
        manifest.samples.forEach { sample ->
            val target = File(staging, sample.path)
            expandedBytes += extract(zip, sample.path, target, sample.sha256.lowercase())
            require(expandedBytes <= MAX_EXPANDED_BYTES) { "乐器采样包实际解压体积过大" }
            // Strict parser validates PCM format/rate/channels before the pack becomes visible.
            verifySampleAudio(target, manifest, sample)
        }
    }

    private fun extract(zip: ZipFile, path: String, target: File, expectedSha256: String?): Long {
        val entry = zip.getEntry(path) ?: error("乐器采样包缺少 $path")
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "无法创建采样目录" } }
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        zip.getInputStream(entry).use { raw ->
            BufferedInputStream(raw, COPY_BUFFER_BYTES).use { input ->
                BufferedOutputStream(FileOutputStream(target), COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        require(written <= MAX_SINGLE_FILE_BYTES) { "乐器采样文件过大" }
                        digest?.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expectedSha256) { "乐器采样 SHA-256 不匹配: $path" }
        }
        return target.length()
    }

    private fun loadInstalled(
        directory: File,
        enforceCanonicalIdentity: Boolean = true,
    ): AiInstalledInstrumentPack {
        require(directory.isDirectory)
        val manifestFile = File(directory, MANIFEST_FILE)
        require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES)
        val manifest = AiInstrumentPackJson.parse(manifestFile.readText())
        if (enforceCanonicalIdentity) {
            require(isCanonicalInstrumentPackDirectory(directory, manifest)) {
                "乐器采样包目录身份不一致"
            }
        }
        val license = File(directory, manifest.licenseFile)
        require(license.isFile && license.length() in 1..MAX_LICENSE_BYTES) { "乐器采样包许可文件缺失" }
        manifest.samples.forEach { sample ->
            val file = File(directory, sample.path)
            require(file.isFile && file.length() in 45..MAX_SINGLE_FILE_BYTES) { "乐器采样文件缺失: ${sample.path}" }
            require(sha256(file).equals(sample.sha256, ignoreCase = true)) { "乐器采样文件校验失败: ${sample.path}" }
            verifySampleAudio(file, manifest, sample)
        }
        return AiInstalledInstrumentPack(manifest, directory)
    }


    private fun verifySampleAudio(
        file: File,
        manifest: AiInstrumentPackManifest,
        sample: AiInstrumentSampleManifest,
    ) {
        val audio = Pcm16WaveReader.read(file, manifest.sampleRate, manifest.channels)
        val loopStart = sample.loopStartFrame
        val loopEnd = sample.loopEndFrameExclusive
        if (loopStart != null && loopEnd != null) {
            require(loopStart < audio.frameCount - 1L && loopEnd <= audio.frameCount) {
                "乐器采样 loop 超出音频范围: ${sample.path}"
            }
        }
    }

    private fun copyPreparedFile(source: File, target: File, expectedSha256: String?): Long {
        require(source.isFile) { "乐器采样文件缺失: ${source.name}" }
        require(source.length() in 1..MAX_SINGLE_FILE_BYTES || expectedSha256 == null) { "乐器采样文件过大" }
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "无法创建采样目录" } }
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        FileInputStream(source).use { raw ->
            BufferedInputStream(raw, COPY_BUFFER_BYTES).use { input ->
                BufferedOutputStream(FileOutputStream(target), COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        require(written <= MAX_SINGLE_FILE_BYTES || expectedSha256 == null) { "乐器采样文件过大" }
                        digest?.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expectedSha256) { "乐器采样 SHA-256 不匹配: ${source.name}" }
        }
        return target.length()
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

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            require(total <= limit) { "manifest 过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MANIFEST_FILE = AiInstrumentPackManifest.MANIFEST_FILE
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024L
        private const val MAX_LICENSE_BYTES = 2 * 1024 * 1024L
        private const val MAX_SINGLE_FILE_BYTES = 64 * 1024 * 1024L
        private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 3L * 1024 * 1024 * 1024
        @Volatile private var instance: AiInstrumentPackStore? = null

        fun get(context: Context): AiInstrumentPackStore = instance ?: synchronized(this) {
            instance ?: AiInstrumentPackStore(context).also { instance = it }
        }
    }
}
