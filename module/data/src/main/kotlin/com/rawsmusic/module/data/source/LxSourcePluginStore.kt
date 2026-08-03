package com.rawsmusic.module.data.source

import android.content.Context
import android.net.Uri
import com.rawsmusic.core.common.source.lx.LxSourceScriptParser
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class InstalledLxSource(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val sourceUrl: String = "",
    val scriptPath: String,
    val scriptSha256: String,
    val origin: MusicSourceOrigin,
    val enabled: Boolean = true,
    val format: String = "userApi",
    val platforms: Map<String, Set<String>> = emptyMap(),
    val actions: Set<String> = setOf("musicUrl"),
    val installedAtMs: Long,
    val updatedAtMs: Long,
    val lastError: String = "",
)

sealed interface LxSourceInstallResult {
    data class Success(
        val source: InstalledLxSource,
        val change: MusicSourceInstallChange,
    ) : LxSourceInstallResult

    data class Failure(val message: String) : LxSourceInstallResult
}

/**
 * Persistence boundary for LX User API scripts.
 *
 * LX sources stay separate from MusicFree search plugins because their protocol only resolves
 * playback/lyric/picture data for catalog items supplied by another provider.
 */
object LxSourcePluginStore {
    private const val STORAGE_KEY = "lx_source_plugins_v1"
    private const val PLUGIN_DIR = "music_sources/lx"
    private const val MAX_REDIRECTS = 4
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val mutableSources = MutableStateFlow(loadSources())
    val sources = mutableSources.asStateFlow()

    suspend fun installFromUri(
        context: Context,
        uri: Uri,
    ): LxSourceInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val script = context.contentResolver.openInputStream(uri)?.use(::readScript)
                ?: throw IllegalArgumentException("无法读取所选 LX 音源文件")
            installScript(
                context = context,
                script = script,
                origin = MusicSourceOrigin.LocalFile,
                sourceUrl = uri.toString(),
            )
        }.getOrElse { error ->
            LxSourceInstallResult.Failure(error.message ?: "LX 音源导入失败")
        }
    }

    suspend fun installFromUrl(
        context: Context,
        rawUrl: String,
    ): LxSourceInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeRemoteUrl(rawUrl)
            val script = downloadScript(normalizedUrl)
            installScript(
                context = context,
                script = script,
                origin = MusicSourceOrigin.RemoteUrl,
                sourceUrl = normalizedUrl,
            )
        }.getOrElse { error ->
            LxSourceInstallResult.Failure(error.message ?: "远程 LX 音源导入失败")
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        var changed = false
        mutableSources.value = mutableSources.value.map { source ->
            if (source.id == id && source.enabled != enabled) {
                changed = true
                source.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis())
            } else {
                source
            }
        }
        if (changed) persist()
    }

    fun remove(context: Context, id: String) {
        val removed = mutableSources.value.firstOrNull { it.id == id } ?: return
        mutableSources.value = mutableSources.value.filterNot { it.id == id }
        runCatching { File(removed.scriptPath).delete() }
        pruneEmptyDirectories(context)
        persist()
    }

    fun setLastError(id: String, message: String) {
        val normalized = message.take(1_024)
        var changed = false
        mutableSources.value = mutableSources.value.map { source ->
            if (source.id == id && source.lastError != normalized) {
                changed = true
                source.copy(lastError = normalized, updatedAtMs = System.currentTimeMillis())
            } else {
                source
            }
        }
        if (changed) persist()
    }

    private fun installScript(
        context: Context,
        script: String,
        origin: MusicSourceOrigin,
        sourceUrl: String,
    ): LxSourceInstallResult {
        val normalizedScript = script.removePrefix("\uFEFF").trim()
        val metadata = LxSourceScriptParser.inspect(normalizedScript)
        val descriptor = metadata.descriptor
        val existingByHash = mutableSources.value.firstOrNull { it.scriptSha256 == metadata.sha256 }
        if (existingByHash != null) {
            return LxSourceInstallResult.Success(existingByHash, MusicSourceInstallChange.Unchanged)
        }

        val id = sourceId(sourceUrl = sourceUrl, scriptSha256 = metadata.sha256)
        val existing = mutableSources.value.firstOrNull { it.id == id }
        val pluginDirectory = File(context.filesDir, PLUGIN_DIR).apply { mkdirs() }
        check(pluginDirectory.isDirectory) { "无法创建 LX 音源存储目录" }
        val targetFile = File(pluginDirectory, "${metadata.sha256}.js")
        targetFile.writeText(normalizedScript, Charsets.UTF_8)

        val now = System.currentTimeMillis()
        val installed = InstalledLxSource(
            id = id,
            name = descriptor.name,
            version = descriptor.version,
            author = descriptor.author,
            description = descriptor.description,
            homepage = descriptor.homepage,
            sourceUrl = sourceUrl,
            scriptPath = targetFile.absolutePath,
            scriptSha256 = metadata.sha256,
            origin = origin,
            enabled = existing?.enabled ?: true,
            format = metadata.format.wireName,
            platforms = descriptor.platforms.associate { it.platform to it.qualities },
            actions = descriptor.platforms.flatMapTo(linkedSetOf()) { capability ->
                capability.actions.map { it.wireName }
            }.ifEmpty { linkedSetOf("musicUrl") },
            installedAtMs = existing?.installedAtMs ?: now,
            updatedAtMs = now,
            lastError = "",
        )

        mutableSources.value = (mutableSources.value.filterNot { it.id == id } + installed)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        if (existing != null && existing.scriptPath != targetFile.absolutePath) {
            runCatching { File(existing.scriptPath).delete() }
        }
        persist()
        return LxSourceInstallResult.Success(
            installed,
            if (existing == null) MusicSourceInstallChange.Installed else MusicSourceInstallChange.Updated,
        )
    }

    private fun loadSources(): List<InstalledLxSource> = runCatching {
        val json = AppPreferences.storage.decodeString(STORAGE_KEY, "").orEmpty()
        MusicSourcePersistenceJson.decodeLxSources(json)
    }.getOrDefault(emptyList())

    private fun persist() {
        AppPreferences.storage.encode(
            STORAGE_KEY,
            MusicSourcePersistenceJson.encodeLxSources(mutableSources.value),
        )
    }

    private fun sourceId(sourceUrl: String, scriptSha256: String): String {
        // Hacylon keys imported sources by their import location and falls back to script content.
        // Keep the same behavior so updating one URL replaces that source instead of colliding with
        // another script that happens to expose the same display name.
        val identity = sourceUrl.trim().ifBlank { scriptSha256 }
        val hash = identity.toByteArray(Charsets.UTF_8)
            .fold(0) { result, byte -> result * 31 + byte }
            .toUInt()
            .toString(16)
        return "lx:$hash"
    }

    private fun normalizeRemoteUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "请输入 LX 音源 URL" }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "仅支持 HTTP 或 HTTPS 地址"
        }
        require(!uri.host.isNullOrBlank()) { "LX 音源 URL 缺少主机名" }
        return uri.toASCIIString()
    }

    private fun downloadScript(initialUrl: String): String {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = URI(currentUrl).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/javascript, text/javascript, text/plain, */*")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("User-Agent", "RawSMusic/0.9.61 beta LxSourceImporter")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "LX 音源地址重定向次数过多" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalArgumentException("LX 音源地址返回了无效重定向")
                    currentUrl = URI(currentUrl).resolve(location).toASCIIString()
                    normalizeRemoteUrl(currentUrl)
                    return@repeat
                }
                require(status in 200..299) { "下载 LX 音源失败：HTTP $status" }
                val declaredLength = connection.contentLengthLong
                require(declaredLength <= 0L || declaredLength <= LxSourceScriptParser.MAX_SCRIPT_BYTES.toLong()) {
                    "LX 音源脚本超过 9 MB 限制"
                }
                val contentEncoding = connection.contentEncoding.orEmpty().lowercase(Locale.ROOT)
                val input = when {
                    "gzip" in contentEncoding -> GZIPInputStream(connection.inputStream)
                    "deflate" in contentEncoding -> InflaterInputStream(connection.inputStream)
                    else -> connection.inputStream
                }
                return input.use(::readScript)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalArgumentException("LX 音源下载失败")
    }

    private fun readScript(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= LxSourceScriptParser.MAX_SCRIPT_BYTES) { "LX 音源脚本超过 9 MB 限制" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun pruneEmptyDirectories(context: Context) {
        val directory = File(context.filesDir, PLUGIN_DIR)
        if (directory.listFiles().isNullOrEmpty()) runCatching { directory.delete() }
    }
}
