package com.rawsmusic.lyrico

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.rawsmusic.lyrico.runtime.HostApiRegistry
import com.rawsmusic.lyrico.runtime.QuickJsHostApi
import com.rawsmusic.lyrico.runtime.QuickJsRuntime
import java.io.File
import java.util.zip.ZipInputStream

@Keep
data class LyricoPluginManifest(
    val id: String = "",
    val name: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val author: String = "",
    val description: String = "",
    val apiVersion: Int = 0,
    val minHostApiVersion: Int = 1,
    val entry: String = "source.js",
    val includeDirs: List<String>? = emptyList(),
    val icon: String? = null,
    val capabilities: Set<String>? = emptySet()
)

data class InstalledLyricoPlugin(
    val manifest: LyricoPluginManifest,
    val directory: File,
    val enabled: Boolean
)

data class LyricoPluginImportResult(
    val plugins: List<InstalledLyricoPlugin> = emptyList(),
    val failures: List<String> = emptyList(),
    val error: String? = null
) {
    val isSuccess: Boolean get() = plugins.isNotEmpty()
}

class LyricoPluginStore private constructor(private val context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "lyrico_sources")
    private val preferences = context.getSharedPreferences("lyrico_sources", Context.MODE_PRIVATE)

    fun listInstalled(): List<InstalledLyricoPlugin> {
        root.mkdirs()
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching {
                    val manifest = readManifest(File(directory, MANIFEST_FILE))
                    validateInstalledPlugin(directory, manifest)
                    InstalledLyricoPlugin(
                        manifest = manifest,
                        directory = directory,
                        enabled = preferences.getBoolean(enabledKey(manifest.id), true)
                    )
                }.getOrNull()
            }
            .sortedBy { it.manifest.name.lowercase() }
            .toList()
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        preferences.edit().putBoolean(enabledKey(pluginId), enabled).apply()
    }

    fun import(uri: Uri): LyricoPluginImportResult = runCatching {
        root.mkdirs()
        val temp = File(root, ".import-${System.currentTimeMillis()}")
        require(temp.mkdirs()) { "Unable to create import directory" }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractArchive(input = input, target = temp)
            } ?: error("Unable to open plugin archive")

            val manifests = temp.walkTopDown()
                .filter { it.isFile && it.name == MANIFEST_FILE }
                .take(MAX_PLUGINS_PER_ARCHIVE + 1)
                .toList()
            require(manifests.isNotEmpty()) { "manifest.json was not found" }
            require(manifests.size <= MAX_PLUGINS_PER_ARCHIVE) { "Archive contains too many plugins" }

            val candidates = manifests.map { manifestFile ->
                runCatching {
                    require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "manifest.json is too large" }
                    val manifest = readManifest(manifestFile)
                    val sourceRoot = manifestFile.parentFile ?: error("Invalid plugin directory")
                    validateInstalledPlugin(sourceRoot, manifest)
                    ImportCandidate(manifest, sourceRoot)
                }
            }
            val duplicateIds = candidates.mapNotNull { it.getOrNull()?.manifest?.id }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            val installed = mutableListOf<InstalledLyricoPlugin>()
            val failures = mutableListOf<String>()
            candidates.forEachIndexed { index, candidateResult ->
                candidateResult.fold(
                    onSuccess = { candidate ->
                        if (candidate.manifest.id in duplicateIds) {
                            failures += "${candidate.manifest.name}: duplicate plugin id"
                        } else {
                            runCatching {
                                installAtomically(candidate.sourceRoot, candidate.manifest)
                            }.onSuccess(installed::add)
                                .onFailure { error ->
                                    failures += "${candidate.manifest.name}: ${error.message ?: error.javaClass.simpleName}"
                                }
                        }
                    },
                    onFailure = { error ->
                        failures += "plugin ${index + 1}: ${error.message ?: error.javaClass.simpleName}"
                    }
                )
            }
            require(installed.isNotEmpty()) { failures.joinToString("; ") }
            Log.i(TAG, "Imported ${installed.size} Lyrico source(s), failed=${failures.size}")
            LyricoPluginImportResult(plugins = installed, failures = failures)
        } finally {
            temp.deleteRecursively()
        }
    }.fold(
        onSuccess = { result -> result },
        onFailure = { throwable ->
            Log.w(TAG, "Unable to import Lyrico sources", throwable)
            LyricoPluginImportResult(error = throwable.message ?: throwable.javaClass.simpleName)
        }
    )

    fun runtimeHealthCheck(): Result<Unit> = runCatching {
        val cacheRoot = File(context.cacheDir, "lyrico_runtime_health")
        QuickJsRuntime(
            memoryLimitBytes = 16L * 1024L * 1024L,
            timeoutMs = 2_000L,
            hostApi = QuickJsHostApi(
                pluginId = "rawsmusic.health",
                cacheRootDir = cacheRoot
            )
        ).use { runtime ->
            runtime.eval(
                "function __rawsHealth(request) { return Platform.crypto.md5('RawSMusic'); }",
                "<rawsmusic-health>"
            )
            val digest = runtime.call("__rawsHealth", "{}")
            check(digest.length == 32) { "QuickJS host API returned an invalid digest" }
        }
    }

    private fun installAtomically(
        sourceRoot: File,
        manifest: LyricoPluginManifest
    ): InstalledLyricoPlugin {
        val target = File(root, manifest.id)
        val staging = File(root, ".staging-${manifest.id}-${System.currentTimeMillis()}")
        val backup = File(root, ".backup-${manifest.id}-${System.currentTimeMillis()}")
        require(sourceRoot.copyRecursively(staging, overwrite = true)) { "Unable to stage plugin" }
        require(directorySize(staging) <= MAX_PLUGIN_BYTES) { "Plugin is too large" }

        var previousMoved = false
        try {
            if (target.exists()) {
                require(target.renameTo(backup)) { "Unable to replace installed plugin" }
                previousMoved = true
            }
            require(staging.renameTo(target)) { "Unable to activate imported plugin" }
            backup.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (previousMoved) backup.renameTo(target)
            throw error
        } finally {
            staging.deleteRecursively()
            if (!target.exists()) backup.deleteRecursively()
        }

        val enabled = preferences.getBoolean(enabledKey(manifest.id), true)
        preferences.edit().putBoolean(enabledKey(manifest.id), enabled).apply()
        return InstalledLyricoPlugin(manifest, target, enabled)
    }

    private fun extractArchive(input: java.io.InputStream, target: File) {
        val canonicalTarget = target.canonicalFile
        var entries = 0
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank() && !name.startsWith('/') && ':' !in name) {
                    "Unsafe zip entry: ${entry.name}"
                }
                require(name.split('/').count { it.isNotBlank() } <= MAX_DEPTH) {
                    "Zip entry is too deep: $name"
                }
                require(++entries <= MAX_ARCHIVE_ENTRIES) { "Archive contains too many files" }
                val output = File(canonicalTarget, name).canonicalFile
                require(output.path == canonicalTarget.path || output.path.startsWith(canonicalTarget.path + File.separator)) {
                    "Unsafe zip entry: $name"
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_ARCHIVE_BYTES) { "Archive is too large after extraction" }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun validateInstalledPlugin(directory: File, manifest: LyricoPluginManifest) {
        require(manifest.id.matches(ID_PATTERN)) { "Plugin id must use reverse-domain format" }
        require(manifest.name.isNotBlank()) { "Plugin name is required" }
        require(manifest.versionCode >= 1) { "versionCode must be at least 1" }
        require(manifest.apiVersion in HostApiRegistry.SUPPORTED_PLUGIN_API_VERSIONS) {
            "Unsupported plugin API ${manifest.apiVersion}; supported versions are " +
                "${HostApiRegistry.SUPPORTED_PLUGIN_API_VERSIONS}"
        }
        require(manifest.minHostApiVersion <= HostApiRegistry.HOST_API_VERSION) {
            "Plugin requires a newer host API"
        }
        val capabilities = manifest.capabilities.orEmpty()
        require(capabilities.isEmpty() || "searchSongs" in capabilities) {
            "A source plugin must support searchSongs"
        }
        val entry = safeChild(directory, manifest.entry)
        require(entry.isFile && entry.extension.equals("js", ignoreCase = true)) {
            "Plugin entry was not found"
        }
        require(entry.length() <= MAX_ENTRY_BYTES) { "Plugin entry is too large" }
        manifest.includeDirs.orEmpty().forEach { includePath ->
            require(safeChild(directory, includePath).isDirectory) { "Invalid include directory: $includePath" }
        }
        manifest.icon?.let { iconPath -> require(safeChild(directory, iconPath).isFile) { "Plugin icon was not found" } }
    }

    private fun safeChild(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && ':' !in relativePath) {
            "Unsafe plugin path: $relativePath"
        }
        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, relativePath).canonicalFile
        require(child.path.startsWith(canonicalRoot.path + File.separator)) {
            "Plugin path escapes its directory: $relativePath"
        }
        return child
    }

    private fun readManifest(file: File): LyricoPluginManifest =
        gson.fromJson(file.readText(), LyricoPluginManifest::class.java)
            ?: error("Unable to parse manifest.json")

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun enabledKey(id: String) = "enabled:$id"

    companion object {
        private const val TAG = "LyricoPluginStore"
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_PLUGINS_PER_ARCHIVE = 20
        private const val MAX_ARCHIVE_ENTRIES = 256
        private const val MAX_DEPTH = 12
        private const val MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L
        private const val MAX_PLUGIN_BYTES = 8L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 128L * 1024L
        private const val MAX_ENTRY_BYTES = 2L * 1024L * 1024L
        private val ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        @Volatile private var instance: LyricoPluginStore? = null

        fun get(context: Context): LyricoPluginStore = instance ?: synchronized(this) {
            instance ?: LyricoPluginStore(context.applicationContext).also { instance = it }
        }
    }

    private data class ImportCandidate(
        val manifest: LyricoPluginManifest,
        val sourceRoot: File
    )
}
