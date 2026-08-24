package com.rawsmusic.module.player.dsp

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * AutoEq preset cache.
 *
 * Cache identity includes repository path/source/rig instead of headphone name alone. AutoEq can
 * contain several measurements for the same model, and those presets must coexist.
 */
class AutoEqCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoEqCache"
        private const val CACHE_DIR = "autoeq_presets"
        private const val FILE_EXTENSION = ".json"
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    fun save(preset: AutoEqPreset): Boolean {
        return try {
            val file = fileForIdentity(preset.name, preset.cacheIdentity)
            file.writeText(preset.toJson())
            Log.d(TAG, "Saved preset: ${preset.name} [${preset.source}/${preset.deviceType}] -> ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preset: ${preset.name}", e)
            false
        }
    }

    /**
     * Loads only the exact online measurement identity. Old name-only cache files cannot prove the
     * measurement rig/path and must not be silently rebound to whichever same-name result was tapped.
     * They remain visible in the downloaded-presets list and can still be applied/deleted explicitly.
     */
    fun load(result: AutoEqSearchResult): AutoEqPreset? =
        loadExact(result.headphoneName, result.cacheIdentity)

    /** Legacy API retained for old callers/caches; new search results should use load(result). */
    fun load(name: String): AutoEqPreset? = loadLegacy(name)

    fun delete(preset: AutoEqPreset): Boolean {
        return try {
            val exact = fileForIdentity(preset.name, preset.cacheIdentity)
            val deleted = exact.exists() && exact.delete()
            // Only a preset which itself has no repository identity may own the old name-only file.
            // Never delete an ambiguous legacy measurement as a side effect of deleting a modern
            // source/rig-specific result with the same headphone name.
            val legacyDeleted = if (preset.originPath.isBlank() && preset.deviceType.isBlank()) {
                legacyFile(preset.name).let { it.exists() && it.delete() }
            } else {
                false
            }
            if (deleted || legacyDeleted) Log.d(TAG, "Deleted preset: ${preset.name} [${preset.source}]")
            deleted || legacyDeleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete preset: ${preset.name}", e)
            false
        }
    }

    /** Legacy deletion by display name. */
    fun delete(name: String): Boolean = try {
        legacyFile(name).let { it.exists() && it.delete() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete legacy preset: $name", e)
        false
    }

    fun listAll(): List<String> = loadAll().map { it.name }

    fun loadAll(): List<AutoEqPreset> {
        return try {
            val presets = cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(FILE_EXTENSION)
            }?.mapNotNull(::loadFile).orEmpty()

            // Prefer the richer/newer identity for duplicates while still reading old name-only files.
            presets
                .groupBy { it.cacheIdentity }
                .mapNotNull { (_, group) -> group.maxByOrNull { identityRichness(it) } }
                .sortedWith(compareBy<AutoEqPreset>({ it.name.lowercase() }, { it.source.lowercase() }, { it.deviceType.lowercase() }))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load all presets", e)
            emptyList()
        }
    }

    fun exists(result: AutoEqSearchResult): Boolean =
        fileForIdentity(result.headphoneName, result.cacheIdentity).exists()

    /** Legacy API retained for compatibility. */
    fun exists(name: String): Boolean = legacyFile(name).exists()

    private fun loadExact(name: String, identity: String): AutoEqPreset? =
        loadFile(fileForIdentity(name, identity))

    private fun loadLegacy(name: String): AutoEqPreset? = loadFile(legacyFile(name))

    private fun loadFile(file: File): AutoEqPreset? {
        if (!file.exists()) return null
        return try {
            AutoEqPreset.fromJson(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preset: ${file.name}", e)
            null
        }
    }

    private fun legacyFile(name: String): File = File(cacheDir, sanitizeFileName(name) + FILE_EXTENSION)

    private fun fileForIdentity(name: String, identity: String): File {
        val readable = sanitizeFileName(name).take(72).ifBlank { "AutoEq" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(cacheDir, "${readable}__${digest}${FILE_EXTENSION}")
    }

    private fun identityRichness(preset: AutoEqPreset): Int =
        (if (preset.originPath.isNotBlank()) 4 else 0) +
            (if (preset.deviceType.isNotBlank()) 2 else 0) +
            (if (preset.source.isNotBlank()) 1 else 0)

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("\\s+".toRegex(), "_")
            .take(100)
    }
}
