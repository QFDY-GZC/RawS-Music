package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多线程扫描池，6 个 ScanWorker 线程。
 *
 * 使用协程 Channel 分发工作项，每个 Worker 独立处理目录扫描 + 标签读取。
 */
object ScanWorkerPool {

    private const val TAG = "ScanWorkerPool"
    private const val WORKER_COUNT = 6 // 6 个工作线程

    /**
     * 工作项
     */
    data class ScanWorkItem(
        val directory: File,
        val onFileFound: (File) -> Unit
    )

    /**
     * 并行扫描多个目录
     * @param directories 要扫描的目录列表
     * @param onFileFound 发现音频文件时的回调
     * @param onProgress 进度回调（已扫描目录数）
     * @return 发现的音频文件总数
     */
    suspend fun scanParallel(
        directories: List<File>,
        onFileFound: (File) -> Unit,
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null
    ): Int {
        val channel = Channel<ScanWorkItem>(capacity = 64)
        val scannedCount = AtomicInteger(0)
        val totalCount = directories.size
        val foundCount = AtomicInteger(0)

        AppLogger.d(TAG, "scanParallel: ${directories.size} directories, $WORKER_COUNT workers")

        // 启动 Worker 协程
        val workers = List(WORKER_COUNT) { workerId ->
            CoroutineScope(Dispatchers.IO).launch {
                for (item in channel) {
                    try {
                        scanDirectoryWorker(workerId, item.directory) { file ->
                            item.onFileFound(file)
                            foundCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Worker $workerId error on ${item.directory}: ${e.message}")
                    }
                    val scanned = scannedCount.incrementAndGet()
                    onProgress?.invoke(scanned, totalCount)
                }
            }
        }

        // 发送工作项
        for (dir in directories) {
            channel.send(ScanWorkItem(dir, onFileFound))
        }
        channel.close()

        // 等待所有 Worker 完成
        workers.forEach { it.join() }

        AppLogger.d(TAG, "scanParallel complete: ${foundCount.get()} files found")
        return foundCount.get()
    }

    /**
     * 单个 Worker 的目录扫描逻辑
     */
    private fun scanDirectoryWorker(
        workerId: Int,
        directory: File,
        onFileFound: (File) -> Unit
    ) {
        val callback = object : DirScanner.ScanCallback {
            override fun startDirectory(path: String) {
                AppLogger.d(TAG, "Worker $workerId: scanning $path")
            }

            override fun fileFound(file: File) {
                onFileFound(file)
            }

            override fun endDirectory(path: String) {
                // 不做额外处理
            }
        }

        DirScanner.scanDirectory(directory, callback)
    }
}
