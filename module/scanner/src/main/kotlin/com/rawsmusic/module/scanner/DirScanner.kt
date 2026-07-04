package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.utils.AppLogger
import java.io.File

/**
 * 目录遍历器。
 *
 * 递归遍历目录，过滤隐藏文件，识别音频文件。
 * 通过回调接口报告发现的目录和文件。
 */
object DirScanner {

    private const val TAG = "DirScanner"

    /**
     * 扫描回调接口
     */
    interface ScanCallback {
        fun startDirectory(path: String)
        fun fileFound(file: File)
        fun endDirectory(path: String)
    }

    /**
     * 扫描单个目录（递归）
     */
    fun scanDirectory(directory: File, callback: ScanCallback) {
        if (!directory.exists() || !directory.isDirectory) return
        callback.startDirectory(directory.absolutePath)

        try {
            val files = directory.listFiles() ?: return
            for (file in files) {
                if (shouldSkip(file)) continue

                if (file.isDirectory) {
                    scanDirectory(file, callback)
                } else if (isAudioFile(file)) {
                    callback.fileFound(file)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error scanning ${directory.absolutePath}: ${e.message}")
        }

        callback.endDirectory(directory.absolutePath)
    }

    /**
     * 批量扫描多个目录
     */
    fun scanDirectories(directories: List<File>, callback: ScanCallback) {
        for (dir in directories) {
            if (Thread.currentThread().isInterrupted) break
            scanDirectory(dir, callback)
        }
    }

    /**
     * 检查是否应跳过该文件/目录
     * 跳过以 . 或 _ 开头的隐藏文件/目录
     */
    private fun shouldSkip(file: File): Boolean {
        val name = file.name
        return name.startsWith(".") || name.startsWith("_")
    }

    /**
     * 检查是否为音频文件
     */
    private fun isAudioFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma",
        "ape", "opus", "alac", "dsf", "dff", "aiff", "aif"
    )
}
