package com.rawsmusic.module.player.dsp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * AutoEq 预设缓存管理器
 * 负责在应用沙盒目录读写预设文件
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

    /**
     * 保存预设到缓存
     * @param preset 要保存的预设
     * @return 是否保存成功
     */
    fun save(preset: AutoEqPreset): Boolean {
        return try {
            val fileName = sanitizeFileName(preset.name) + FILE_EXTENSION
            val file = File(cacheDir, fileName)
            file.writeText(preset.toJson())
            Log.d(TAG, "Saved preset: ${preset.name} -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preset: ${preset.name}", e)
            false
        }
    }

    /**
     * 从缓存加载预设
     * @param name 预设名称
     * @return 预设对象，如果不存在则返回 null
     */
    fun load(name: String): AutoEqPreset? {
        return try {
            val fileName = sanitizeFileName(name) + FILE_EXTENSION
            val file = File(cacheDir, fileName)
            if (!file.exists()) return null

            val json = file.readText()
            AutoEqPreset.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preset: $name", e)
            null
        }
    }

    /**
     * 删除缓存中的预设
     * @param name 预设名称
     * @return 是否删除成功
     */
    fun delete(name: String): Boolean {
        return try {
            val fileName = sanitizeFileName(name) + FILE_EXTENSION
            val file = File(cacheDir, fileName)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Deleted preset: $name")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete preset: $name", e)
            false
        }
    }

    /**
     * 获取所有已缓存的预设名称
     * @return 预设名称列表
     */
    fun listAll(): List<String> {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(FILE_EXTENSION)
            }?.map { file ->
                file.nameWithoutExtension
            }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list presets", e)
            emptyList()
        }
    }

    /**
     * 获取所有已缓存的预设
     * @return 预设列表
     */
    fun loadAll(): List<AutoEqPreset> {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(FILE_EXTENSION)
            }?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    AutoEqPreset.fromJson(json)
                } catch (e: Exception) {
                    null
                }
            }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load all presets", e)
            emptyList()
        }
    }

    /**
     * 检查预设是否已缓存
     * @param name 预设名称
     * @return 是否存在
     */
    fun exists(name: String): Boolean {
        val fileName = sanitizeFileName(name) + FILE_EXTENSION
        return File(cacheDir, fileName).exists()
    }

    /**
     * 清理文件名，移除非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("\\s+".toRegex(), "_")
            .take(100) // 限制文件名长度
    }
}
