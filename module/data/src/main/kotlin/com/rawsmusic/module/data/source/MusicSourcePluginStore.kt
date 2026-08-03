package com.rawsmusic.module.data.source

import android.content.Context
import android.net.Uri
import com.rawsmusic.core.common.source.musicfree.MusicFreeMethod
import com.rawsmusic.core.common.source.musicfree.MusicFreePluginScriptParser
import com.rawsmusic.module.data.prefs.AppPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Persisted metadata for an imported source script. The JavaScript is stored separately. */
data class InstalledMusicSource(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val appVersion: String = "",
    val sourceUrl: String = "",
    val scriptPath: String,
    val scriptSha256: String,
    val origin: MusicSourceOrigin,
    val enabled: Boolean = true,
    val methods: Set<String> = emptySet(),
    val installedAtMs: Long,
    val updatedAtMs: Long,
    val lastError: String = "",
)

enum class MusicSourceOrigin {
    LocalFile,
    RemoteUrl,
}

enum class MusicSourceInstallChange {
    Installed,
    Updated,
    Unchanged,
}

sealed interface MusicSourceInstallResult {
    data class Success(
        val source: InstalledMusicSource,
        val change: MusicSourceInstallChange,
    ) : MusicSourceInstallResult

    data class Failure(val message: String) : MusicSourceInstallResult
}

/**
 * Import/persistence boundary for MusicFree-compatible scripts.
 *
 * Importing performs static inspection only. No JavaScript is executed here. The future
 * isolated runtime will consume [InstalledMusicSource.scriptPath] after an explicit mount.
 */
object MusicSourcePluginStore {
    private const val STORAGE_KEY = "music_source_plugins_v1"
    private const val PLUGIN_DIR = "music_sources/musicfree"
    private const val MAX_REDIRECTS = 4
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val mutableSources = MutableStateFlow(loadSources())
    val sources = mutableSources.asStateFlow()

    suspend fun installFromUri(
        context: Context,
        uri: Uri,
    ): MusicSourceInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val script = context.contentResolver.openInputStream(uri)?.use(::readScript)
                ?: throw IllegalArgumentException("无法读取所选音源文件")
            installScript(
                context = context,
                script = script,
                origin = MusicSourceOrigin.LocalFile,
                sourceUrl = uri.toString(),
            )
        }.getOrElse { error ->
            MusicSourceInstallResult.Failure(error.message ?: "音源导入失败")
        }
    }

    suspend fun installFromUrl(
        context: Context,
        rawUrl: String,
    ): MusicSourceInstallResult = withContext(Dispatchers.IO) {
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
            MusicSourceInstallResult.Failure(error.message ?: "远程音源导入失败")
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
    ): MusicSourceInstallResult {
        val metadata = MusicFreePluginScriptParser.inspect(script)
        val descriptor = metadata.descriptor
        val existingByHash = mutableSources.value.firstOrNull { it.scriptSha256 == metadata.sha256 }
        if (existingByHash != null) {
            return MusicSourceInstallResult.Success(existingByHash, MusicSourceInstallChange.Unchanged)
        }

        val id = sourceId(descriptor.platform)
        val existing = mutableSources.value.firstOrNull { it.id == id }
        val pluginDirectory = File(context.filesDir, PLUGIN_DIR).apply { mkdirs() }
        check(pluginDirectory.isDirectory) { "无法创建音源存储目录" }
        val targetFile = File(pluginDirectory, "${metadata.sha256}.js")
        targetFile.writeText(script.removePrefix("\uFEFF"), Charsets.UTF_8)

        val now = System.currentTimeMillis()
        val installed = InstalledMusicSource(
            id = id,
            name = descriptor.platform,
            version = descriptor.version,
            author = descriptor.author,
            description = descriptor.description,
            appVersion = metadata.appVersion,
            sourceUrl = metadata.sourceUrl.ifBlank { sourceUrl },
            scriptPath = targetFile.absolutePath,
            scriptSha256 = metadata.sha256,
            origin = origin,
            enabled = existing?.enabled ?: true,
            methods = descriptor.methods.mapTo(linkedSetOf(), MusicFreeMethod::name),
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
        return MusicSourceInstallResult.Success(
            installed,
            if (existing == null) MusicSourceInstallChange.Installed else MusicSourceInstallChange.Updated,
        )
    }

    private fun loadSources(): List<InstalledMusicSource> {
        return runCatching {
            val json = AppPreferences.storage.decodeString(STORAGE_KEY, "").orEmpty()
            MusicSourcePersistenceJson.decodeMusicFreeSources(json)
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        AppPreferences.storage.encode(
            STORAGE_KEY,
            MusicSourcePersistenceJson.encodeMusicFreeSources(mutableSources.value),
        )
    }

    private fun sourceId(platform: String): String {
        val normalized = platform
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(64)
        val fallback = platform.toByteArray(Charsets.UTF_8)
            .fold(0) { hash, byte -> hash * 31 + byte }
            .toUInt()
            .toString(16)
        return "musicfree:${normalized.ifBlank { fallback }}"
    }

    private fun normalizeRemoteUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "请输入音源 URL" }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "仅支持 HTTP 或 HTTPS 地址"
        }
        require(!uri.host.isNullOrBlank()) { "音源 URL 缺少主机名" }
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
            connection.setRequestProperty("User-Agent", "RawSMusic/0.9.61 beta MusicSourceImporter")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "音源地址重定向次数过多" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalArgumentException("音源地址返回了无效重定向")
                    currentUrl = URI(currentUrl).resolve(location).toASCIIString()
                    normalizeRemoteUrl(currentUrl)
                    return@repeat
                }
                require(status in 200..299) { "下载音源失败：HTTP $status" }
                val declaredLength = connection.contentLengthLong
                require(
                    declaredLength <= 0L || declaredLength <= MusicFreePluginScriptParser.MAX_SCRIPT_BYTES
                ) { "远程音源超过 2 MiB 限制" }
                return connection.inputStream.use(::readScript)
            } finally {
                connection.disconnect()
            }
        }
        error("音源下载失败")
    }

    private fun readScript(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MusicFreePluginScriptParser.MAX_SCRIPT_BYTES) {
                "音源脚本超过 2 MiB 限制"
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun pruneEmptyDirectories(context: Context) {
        val directory = File(context.filesDir, PLUGIN_DIR)
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
    }
}
