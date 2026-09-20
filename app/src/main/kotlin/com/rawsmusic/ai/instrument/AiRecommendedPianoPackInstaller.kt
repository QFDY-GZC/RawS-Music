package com.rawsmusic.ai.instrument

import android.content.Context
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.separation.AiSeparationDownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object AiRecommendedPianoPack {
    const val ID = "freepats.upright-piano-kw"
    const val VERSION = "2022.02.21-rsm2"
    const val DISPLAY_NAME = "Upright Piano KW"
    const val SOURCE_REVISION = "570f6c60ed2eff67accad3b85d5b452e57a3ad28"
    const val SOURCE_URL =
        "https://codeload.github.com/freepats/upright-piano-KW/zip/$SOURCE_REVISION"
    const val SOURCE_SFZ = "UprightPianoKW-20220221.sfz"
    const val SOURCE_LICENSE = "LICENSE"
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val ESTIMATED_DOWNLOAD_BYTES = 34L * 1024 * 1024
    const val ESTIMATED_INSTALLED_BYTES = 84L * 1024 * 1024
    const val LICENSE = "CC0-1.0"
}

/**
 * Installs the recommended compact piano from a pinned, immutable upstream repository revision.
 *
 * The downloaded archive contains SFZ + FLAC.  No upstream executable code is loaded.  The SFZ is
 * parsed into RawSMusic's own manifest, each referenced FLAC is decoded offline to PCM16 WAV by the
 * already bundled FFmpeg bridge, SHA-256 is calculated over the normalized WAV, and the resulting
 * prepared directory goes through [AiInstrumentPackStore] verification before becoming visible.
 */
class AiRecommendedPianoPackInstaller private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = AiInstrumentPackStore.get(appContext)
    private val cacheRoot = File(appContext.cacheDir, "ai_instrument/recommended").apply { mkdirs() }

    fun resolveInstalled(): AiInstalledInstrumentPack? =
        store.resolve(AiRecommendedPianoPack.ID, AiRecommendedPianoPack.VERSION)

    suspend fun downloadAndInstall(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onPhase: (AiSeparationDownloadPhase) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiInstalledInstrumentPack = withContext(Dispatchers.IO) {
        resolveInstalled()?.let { return@withContext it }
        require(FFmpegBridge.isLoaded()) { "FFmpeg 当前不可用，无法准备钢琴采样" }
        ensureSpace()
        val archive = File(cacheRoot, "upright_piano_kw_${AiRecommendedPianoPack.SOURCE_REVISION.take(12)}.zip.part")
        val work = File(cacheRoot, "prepare_${System.nanoTime()}")
        try {
            onPhase(AiSeparationDownloadPhase.DOWNLOADING)
            downloadPinnedArchive(archive, onProgress, isCancelled)
            ensureNotCancelled(isCancelled)
            onPhase(AiSeparationDownloadPhase.VERIFYING)
            require(archive.length() in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES) { "推荐钢琴源包大小异常" }
            try {
                preparePack(archive, work, isCancelled)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // There is no upstream project-published archive SHA for GitHub's generated source ZIP.
                // If structure/conversion verification fails, discard the local archive so the next
                // attempt cannot keep resuming from corrupt bytes.
                archive.delete()
                throw error
            }
            ensureNotCancelled(isCancelled)
            onPhase(AiSeparationDownloadPhase.INSTALLING)
            val installed = store.installPreparedDirectory(work).getOrThrow()
            require(installed.manifest.id == AiRecommendedPianoPack.ID &&
                installed.manifest.version == AiRecommendedPianoPack.VERSION) {
                "推荐钢琴采样包身份不一致"
            }
            archive.delete()
            installed
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun removeRecommended(): Result<Unit> =
        store.remove(AiRecommendedPianoPack.ID, AiRecommendedPianoPack.VERSION)

    private fun ensureSpace() {
        val required = MAX_ARCHIVE_BYTES + AiRecommendedPianoPack.ESTIMATED_INSTALLED_BYTES * 2L
        require(appContext.filesDir.usableSpace > required) {
            "存储空间不足，推荐钢琴包准备阶段需要至少 ${required / 1024 / 1024} MiB 可用空间"
        }
    }

    private fun downloadPinnedArchive(
        target: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        target.parentFile?.mkdirs()
        var redirects = 0
        var currentUrl = AiRecommendedPianoPack.SOURCE_URL
        var existing = target.takeIf(File::isFile)?.length()?.coerceAtMost(MAX_ARCHIVE_BYTES) ?: 0L
        if (target.exists() && target.length() > MAX_ARCHIVE_BYTES) {
            target.delete()
            existing = 0L
        }
        if (existing > 0L && isReusableSourceArchive(target)) {
            onProgress(existing, existing)
            return
        }
        while (true) {
            ensureNotCancelled(isCancelled)
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                if (code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0L) {
                    if (isReusableSourceArchive(target)) {
                        onProgress(existing, existing)
                        return
                    }
                    target.delete()
                    existing = 0L
                    redirects = 0
                    currentUrl = AiRecommendedPianoPack.SOURCE_URL
                    continue
                }
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    require(location.isNotBlank() && redirects++ < MAX_REDIRECTS) { "推荐钢琴下载重定向异常" }
                    val resolved = URL(URL(currentUrl), location)
                    require(resolved.protocol.equals("https", ignoreCase = true)) { "推荐钢琴下载只允许 HTTPS" }
                    currentUrl = resolved.toString()
                    continue
                }
                require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "推荐钢琴下载失败：HTTP $code"
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                if (!append) {
                    existing = 0L
                    target.delete()
                }
                val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                val total = if (contentLength > 0L) existing + contentLength else 0L
                if (total > 0L) require(total <= MAX_ARCHIVE_BYTES) { "推荐钢琴源包超过大小上限" }
                BufferedInputStream(connection.inputStream, COPY_BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(target, append), COPY_BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var written = existing
                        while (true) {
                            ensureNotCancelled(isCancelled)
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            require(written <= MAX_ARCHIVE_BYTES) { "推荐钢琴源包超过大小上限" }
                            output.write(buffer, 0, read)
                            onProgress(written, total)
                        }
                    }
                }
                require(target.length() >= MIN_ARCHIVE_BYTES) { "推荐钢琴源包下载不完整" }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun isReusableSourceArchive(file: File): Boolean = runCatching {
        require(file.isFile && file.length() in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES)
        ZipFile(file).use { zip ->
            val sfzEntry = uniqueSuffixEntry(zip, "/${AiRecommendedPianoPack.SOURCE_SFZ}")
            val rootPrefix = sfzEntry.name.removeSuffix(AiRecommendedPianoPack.SOURCE_SFZ)
            require(rootPrefix.isNotBlank() && rootPrefix.endsWith('/'))
            val licenseEntry = zip.getEntry(rootPrefix + AiRecommendedPianoPack.SOURCE_LICENSE)
            require(licenseEntry != null && !licenseEntry.isDirectory)
            val samplePrefix = rootPrefix + "samples/"
            val sampleCount = zip.entries().asSequence().count { entry ->
                !entry.isDirectory && entry.name.replace('\\', '/').startsWith(samplePrefix) &&
                    entry.name.endsWith(".flac", ignoreCase = true)
            }
            require(sampleCount in 32..128)
        }
        true
    }.getOrDefault(false)

    private fun preparePack(
        archive: File,
        work: File,
        isCancelled: () -> Boolean,
    ) {
        work.deleteRecursively()
        require(work.mkdirs()) { "无法创建钢琴包准备目录" }
        ZipFile(archive).use { zip ->
            val sfzEntry = uniqueSuffixEntry(zip, "/${AiRecommendedPianoPack.SOURCE_SFZ}")
            val rootPrefix = sfzEntry.name.removeSuffix(AiRecommendedPianoPack.SOURCE_SFZ)
            require(rootPrefix.isNotBlank() && rootPrefix.endsWith('/')) { "推荐钢琴源包目录结构异常" }
            val licenseEntry = zip.getEntry(rootPrefix + AiRecommendedPianoPack.SOURCE_LICENSE)
                ?: error("推荐钢琴源包缺少 CC0 许可")
            val sfzText = zip.getInputStream(sfzEntry).buffered().use {
                it.readBytesLimited(MAX_SFZ_BYTES).toString(Charsets.UTF_8)
            }
            val licenseBytes = zip.getInputStream(licenseEntry).buffered().use {
                it.readBytesLimited(MAX_LICENSE_BYTES)
            }
            val licenseText = licenseBytes.toString(Charsets.UTF_8)
            require(licenseText.contains("CC0", ignoreCase = true) ||
                licenseText.contains("Creative Commons Zero", ignoreCase = true)) {
                "推荐钢琴源包许可不是预期的 CC0"
            }
            val regions = AiSfzPianoMapParser.parse(sfzText)
            val uniqueRegions = regions.distinctBy { it.samplePath }
            require(uniqueRegions.size in 32..128) { "推荐钢琴 SFZ sample 数量异常" }
            require(uniqueRegions.all { it.samplePath.startsWith("samples/") }) { "推荐钢琴 SFZ 路径异常" }

            File(work, "license.txt").writeBytes(licenseBytes)
            val sampleManifests = ArrayList<AiInstrumentSampleManifest>(uniqueRegions.size)
            uniqueRegions.forEachIndexed { index, region ->
                ensureNotCancelled(isCancelled)
                val sourceEntry = zip.getEntry(rootPrefix + region.samplePath)
                    ?: error("推荐钢琴源包缺少 ${region.samplePath}")
                require(!sourceEntry.isDirectory && sourceEntry.size in 1..MAX_SOURCE_SAMPLE_BYTES) {
                    "推荐钢琴 sample 大小异常: ${region.samplePath}"
                }
                val safeBase = File(region.samplePath).nameWithoutExtension
                require(safeBase.matches(Regex("[A-Za-z0-9#._+-]+"))) { "推荐钢琴 sample 名称异常" }
                val sourceFile = File(work, ".source_$index.flac")
                val wavRelative = "samples/$safeBase.wav"
                val wavFile = File(work, wavRelative)
                try {
                    extractEntry(zip, sourceEntry, sourceFile, MAX_SOURCE_SAMPLE_BYTES)
                    wavFile.parentFile?.mkdirs()
                    val result = FFmpegBridge.convertToWav(
                        inputPath = sourceFile.absolutePath,
                        outputPath = wavFile.absolutePath,
                        targetSampleRate = AiRecommendedPianoPack.SAMPLE_RATE,
                        bitsPerSample = 16,
                        channels = AiRecommendedPianoPack.CHANNELS,
                    )
                    require(result == 0 && wavFile.isFile && wavFile.length() >= 48L) {
                        "推荐钢琴 sample 转码失败: ${region.samplePath} ($result)"
                    }
                    val audio = Pcm16WaveReader.read(
                        wavFile,
                        AiRecommendedPianoPack.SAMPLE_RATE,
                        AiRecommendedPianoPack.CHANNELS,
                    )
                    val loopStart = region.loopStartFrame
                    val loopEnd = region.loopEndFrameExclusive
                    val validLoop = loopStart != null && loopEnd != null &&
                        loopStart < audio.frameCount - 1L && loopEnd <= audio.frameCount
                    sampleManifests += AiInstrumentSampleManifest(
                        path = wavRelative,
                        rootMidiNote = region.rootMidiNote,
                        velocityMin = region.velocityMin,
                        velocityMax = region.velocityMax,
                        gainDb = 0f,
                        loopStartFrame = if (validLoop) loopStart else null,
                        loopEndFrameExclusive = if (validLoop) loopEnd else null,
                        sha256 = sha256(wavFile),
                    )
                } finally {
                    sourceFile.delete()
                }
            }
            val manifest = AiInstrumentPackManifest(
                schemaVersion = AiInstrumentPackManifest.SCHEMA_VERSION,
                id = AiRecommendedPianoPack.ID,
                version = AiRecommendedPianoPack.VERSION,
                displayName = AiRecommendedPianoPack.DISPLAY_NAME,
                instrument = "piano",
                sampleRate = AiRecommendedPianoPack.SAMPLE_RATE,
                channels = AiRecommendedPianoPack.CHANNELS,
                licenseFile = "license.txt",
                samples = sampleManifests,
            ).validated()
            File(work, AiInstrumentPackManifest.MANIFEST_FILE)
                .writeText(AiInstrumentPackJson.stringify(manifest) + "\n", Charsets.UTF_8)
        }
    }


    private fun ensureNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException(CANCELLED_MESSAGE)
    }

    private fun uniqueSuffixEntry(zip: ZipFile, suffix: String): ZipEntry {
        val matches = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.replace('\\', '/').endsWith(suffix) }
            .toList()
        require(matches.size == 1) { "推荐钢琴源包关键文件数量异常: $suffix" }
        return matches.single()
    }

    private fun extractEntry(zip: ZipFile, entry: ZipEntry, target: File, maxBytes: Long) {
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { raw ->
            BufferedInputStream(raw, COPY_BUFFER_BYTES).use { input ->
                BufferedOutputStream(FileOutputStream(target), COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        require(written <= maxBytes) { "推荐钢琴 sample 解压体积异常" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            require(total <= limit) { "推荐钢琴元数据过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
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
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val MIN_ARCHIVE_BYTES = 4L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 96L * 1024 * 1024
        private const val MAX_SOURCE_SAMPLE_BYTES = 16L * 1024 * 1024
        private const val MAX_SFZ_BYTES = 512 * 1024
        private const val MAX_LICENSE_BYTES = 512 * 1024
        private const val USER_AGENT = "RawSMusic/AI-Instrument"
        private const val CANCELLED_MESSAGE = "AI instrument download cancelled"
        @Volatile private var instance: AiRecommendedPianoPackInstaller? = null

        fun get(context: Context): AiRecommendedPianoPackInstaller = instance ?: synchronized(this) {
            instance ?: AiRecommendedPianoPackInstaller(context).also { instance = it }
        }
    }
}
