package com.rawsmusic.separation

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Loads the large ONNX Runtime core from a developer-signed repository package.
 *
 * The small Java API and JNI adapter stay in the APK. The executable core is accepted only after
 * the signed catalog metadata, ABI, size and SHA-256 have all been verified.
 */
internal object AiOnnxRuntimeLoader {
    private const val MANIFEST_FILE = "installed.json"
    private const val LIBRARY_FILE = "libonnxruntime.so"
    private const val TAG = "AiOnnxRuntime"

    @Volatile private var loadedPath: String = ""
    @Volatile private var loadedEntry: AiRuntimeCatalogEntry? = null
    @Volatile private var loadError: String = ""

    /**
     * Persistent, non-backed-up storage for the verified runtime.
     *
     * codeCacheDir is intentionally disposable and caused the 41.6 MiB runtime to be downloaded
     * again after a cold start on devices that aggressively trim executable caches.
     */
    fun runtimeRoot(context: Context): File =
        File(context.noBackupFilesDir, "ai_separation/runtime").apply { mkdirs() }

    fun installedRuntime(context: Context): Pair<AiRuntimeCatalogEntry, File>? {
        val supportedAbis = Build.SUPPORTED_ABIS.toSet()
        migrateLegacyRuntime(context, supportedAbis)
        return findInstalledRuntime(runtimeRoot(context), supportedAbis)
    }

    private fun findInstalledRuntime(
        root: File,
        supportedAbis: Set<String>,
    ): Pair<AiRuntimeCatalogEntry, File>? = root
            .walkTopDown()
            .filter { it.isFile && it.name == MANIFEST_FILE }
            .mapNotNull { manifest ->
                runCatching {
                    val entry = AiSeparationJson.parseRuntimeManifest(manifest.readText())
                    val library = File(manifest.parentFile, entry.libraryFile)
                    require(entry.abi in supportedAbis)
                    require(library.isFile && library.length() == entry.librarySizeBytes)
                    require(sha256(library) == entry.librarySha256)
                    entry to library
                }.getOrNull()
            }
            .maxByOrNull { it.first.version }

    @Synchronized
    fun ensureLoaded(context: Context): Result<AiRuntimeCatalogEntry> = runCatching {
        loadedEntry?.let { return@runCatching it }
        val installed = installedRuntime(context)
            ?: error("ONNX Runtime 尚未从可信仓库安装")
        val executable = prepareExecutableLibrary(context, installed.first, installed.second)
        val canonicalPath = executable.canonicalPath
        if (loadedPath != canonicalPath) {
            System.load(canonicalPath)
            loadedPath = canonicalPath
            loadedEntry = installed.first
            loadError = ""
            Log.i(
                TAG,
                "AI_RUNTIME_CACHE loaded persistent=${installed.second.absolutePath} " +
                    "executable=$canonicalPath",
            )
        }
        installed.first
    }.onFailure { error ->
        loadError = error.message ?: error.javaClass.simpleName
    }

    fun lastError(): String = loadError

    private fun prepareExecutableLibrary(
        context: Context,
        entry: AiRuntimeCatalogEntry,
        persistentLibrary: File,
    ): File {
        val targetDir = File(
            context.codeCacheDir,
            "ai_separation/runtime_exec/${entry.id}/${entry.version}/${entry.abi}",
        )
        val target = File(targetDir, LIBRARY_FILE)
        if (
            target.isFile &&
            target.length() == entry.librarySizeBytes &&
            sha256(target) == entry.librarySha256
        ) {
            Log.i(TAG, "AI_RUNTIME_CACHE executable=reused bytes=${target.length()}")
            return target
        }

        targetDir.mkdirs()
        val staging = File(targetDir, ".${LIBRARY_FILE}.${System.nanoTime()}.tmp")
        try {
            persistentLibrary.inputStream().buffered().use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output, 256 * 1024)
                    output.fd.sync()
                }
            }
            require(staging.length() == entry.librarySizeBytes) {
                "运行库执行镜像大小错误"
            }
            require(sha256(staging) == entry.librarySha256) {
                "运行库执行镜像哈希错误"
            }
            staging.setReadable(true, true)
            staging.setExecutable(true, true)
            if (target.exists() && !target.delete()) error("无法替换运行库执行镜像")
            require(staging.renameTo(target)) { "无法提交运行库执行镜像" }
            Log.i(TAG, "AI_RUNTIME_CACHE executable=restored bytes=${target.length()}")
            return target
        } finally {
            staging.delete()
        }
    }

    /**
     * Preserve an already downloaded runtime across this storage-layout upgrade.
     */
    private fun migrateLegacyRuntime(context: Context, supportedAbis: Set<String>) {
        val persistentRoot = runtimeRoot(context)
        if (findInstalledRuntime(persistentRoot, supportedAbis) != null) return
        val legacyRoot = File(context.codeCacheDir, "ai_separation/runtime")
        val legacy = findInstalledRuntime(legacyRoot, supportedAbis) ?: return
        val entry = legacy.first
        val targetDir = File(
            File(File(persistentRoot, entry.id), entry.version),
            entry.abi,
        )
        val staging = File(targetDir.parentFile, ".migrate_${entry.abi}_${System.nanoTime()}")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "无法创建运行库迁移目录" }
        try {
            val library = File(staging, entry.libraryFile)
            legacy.second.inputStream().buffered().use { input ->
                FileOutputStream(library).use { output ->
                    input.copyTo(output, 256 * 1024)
                    output.fd.sync()
                }
            }
            require(library.length() == entry.librarySizeBytes)
            require(sha256(library) == entry.librarySha256)
            File(legacy.second.parentFile, MANIFEST_FILE).copyTo(
                File(staging, MANIFEST_FILE),
                overwrite = true,
            )
            targetDir.parentFile?.mkdirs()
            targetDir.deleteRecursively()
            require(staging.renameTo(targetDir)) { "无法迁移已安装运行库" }
            Log.i(TAG, "AI_RUNTIME_CACHE migrated legacy runtime to persistent storage")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
