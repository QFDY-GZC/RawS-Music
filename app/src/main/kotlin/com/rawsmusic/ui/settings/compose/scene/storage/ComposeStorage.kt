package com.rawsmusic.ui.settings.compose.scene.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Compose 存储
 * 对应原版的存储系统
 *
 * 管理本地存储
 */
@Stable
class ComposeStorage {
    // ==================== 存储状态 ====================

    /** 是否已初始化 */
    var isInitialized by mutableStateOf(false)
        private set

    /** 存储数据 */
    private val storage = mutableStateMapOf<String, Any?>()

    /** 存储大小限制 (bytes) */
    var sizeLimit by mutableStateOf(1024 * 1024) // 1MB
        private set

    /** 当前存储大小 (bytes) */
    var currentSize by mutableStateOf(0L)
        private set

    // ==================== 方法 ====================

    /**
     * 初始化存储
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true
    }

    /**
     * 获取值
     *
     * @param key 键
     * @param defaultValue 默认值
     * @return 值
     */
    fun get(key: String, defaultValue: Any? = null): Any? {
        return storage[key] ?: defaultValue
    }

    /**
     * 设置值
     *
     * @param key 键
     * @param value 值
     * @return 是否成功
     */
    fun set(key: String, value: Any?): Boolean {
        val oldValue = storage[key]
        storage[key] = value

        // 更新大小
        currentSize += estimateSize(value) - estimateSize(oldValue)

        return true
    }

    /**
     * 移除值
     *
     * @param key 键
     * @return 是否成功
     */
    fun remove(key: String): Boolean {
        val oldValue = storage.remove(key)
        if (oldValue != null) {
            currentSize -= estimateSize(oldValue)
            return true
        }
        return false
    }

    /**
     * 检查是否包含指定键
     *
     * @param key 键
     * @return 是否包含
     */
    fun contains(key: String): Boolean {
        return storage.containsKey(key)
    }

    /**
     * 清空存储
     */
    fun clear() {
        storage.clear()
        currentSize = 0L
    }

    /**
     * 获取所有键
     *
     * @return 键集合
     */
    fun keys(): Set<String> {
        return storage.keys
    }

    /**
     * 获取所有值
     *
     * @return 值集合
     */
    fun values(): Collection<Any?> {
        return storage.values
    }

    /**
     * 获取所有条目
     *
     * @return 条目集合
     */
    fun entries(): Set<Map.Entry<String, Any?>> {
        return storage.entries
    }

    /**
     * 获取存储大小
     *
     * @return 存储大小
     */
    fun size(): Int {
        return storage.size
    }

    /**
     * 检查是否为空
     *
     * @return 是否为空
     */
    fun isEmpty(): Boolean {
        return storage.isEmpty()
    }

    /**
     * 检查是否不为空
     *
     * @return 是否不为空
     */
    fun isNotEmpty(): Boolean {
        return storage.isNotEmpty()
    }

    /**
     * 检查是否超出大小限制
     *
     * @return 是否超出
     */
    fun isOverLimit(): Boolean {
        return currentSize > sizeLimit
    }

    /**
     * 获取剩余空间
     *
     * @return 剩余空间 (bytes)
     */
    fun getRemainingSpace(): Long {
        return (sizeLimit - currentSize).coerceAtLeast(0)
    }

    /**
     * 估算值的大小
     *
     * @param value 值
     * @return 大小 (bytes)
     */
    private fun estimateSize(value: Any?): Long {
        return when (value) {
            is String -> value.length.toLong() * 2 // 每个字符 2 bytes
            is Int -> 4L
            is Long -> 8L
            is Float -> 4L
            is Double -> 8L
            is Boolean -> 1L
            is ByteArray -> value.size.toLong()
            else -> 0L
        }
    }

    /**
     * 导出存储数据
     *
     * @return 存储数据字符串
     */
    fun export(): String {
        val sb = StringBuilder()
        storage.forEach { (key, value) ->
            sb.appendLine("$key=$value")
        }
        return sb.toString()
    }

    /**
     * 导入存储数据
     *
     * @param data 存储数据字符串
     */
    fun import(data: String) {
        data.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty()) {
                    set(key, value)
                }
            }
        }
    }
}

/**
 * 记住 ComposeStorage
 */
@Composable
fun rememberComposeStorage(): ComposeStorage {
    return remember { ComposeStorage().also { it.initialize() } }
}
