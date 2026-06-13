package com.rawsmusic.ui.settings.compose.scene.scheduler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose 处理器
 * 对应原版的处理器系统
 *
 * 管理任务处理
 */
@Stable
class ComposeProcessor(
    private val scope: CoroutineScope
) {
    // ==================== 处理器状态 ====================

    /** 是否正在运行 */
    var isRunning by mutableStateOf(false)
        private set

    /** 任务计数 */
    var taskCount by mutableStateOf(0)
        private set

    /** 已完成任务计数 */
    var completedCount by mutableStateOf(0)
        private set

    /** 失败任务计数 */
    var failedCount by mutableStateOf(0)
        private set

    // ==================== 方法 ====================

    /**
     * 启动处理器
     */
    fun start() {
        isRunning = true
    }

    /**
     * 停止处理器
     */
    fun stop() {
        isRunning = false
    }

    /**
     * 处理任务
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun process(name: String, task: suspend () -> Unit) {
        scope.launch {
            try {
                task()
                completedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }
        taskCount++
    }

    /**
     * 处理延迟任务
     *
     * @param name 任务名称
     * @param delay 延迟 (ms)
     * @param task 任务函数
     */
    fun processDelayed(name: String, delay: Long, task: suspend () -> Unit) {
        scope.launch {
            try {
                delay(delay)
                task()
                completedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }
        taskCount++
    }

    /**
     * 处理重复任务
     *
     * @param name 任务名称
     * @param interval 间隔 (ms)
     * @param task 任务函数
     */
    fun processRepeating(name: String, interval: Long, task: suspend () -> Unit) {
        scope.launch {
            while (isRunning) {
                try {
                    task()
                    completedCount++
                } catch (e: Exception) {
                    failedCount++
                }
                delay(interval)
            }
        }
        taskCount++
    }

    /**
     * 在主线程处理
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun processOnMain(name: String, task: suspend () -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                task()
                completedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }
        taskCount++
    }

    /**
     * 在 IO 线程处理
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun processOnIO(name: String, task: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                task()
                completedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }
        taskCount++
    }

    /**
     * 在默认线程处理
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun processOnDefault(name: String, task: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) {
            try {
                task()
                completedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }
        taskCount++
    }

    /**
     * 等待任务完成
     *
     * @param name 任务名称
     * @param task 任务函数
     * @return 任务结果
     */
    suspend fun <T> awaitOnMain(name: String, task: suspend () -> T): T {
        return withContext(Dispatchers.Main) {
            task()
        }
    }

    /**
     * 等待任务完成
     *
     * @param name 任务名称
     * @param task 任务函数
     * @return 任务结果
     */
    suspend fun <T> awaitOnIO(name: String, task: suspend () -> T): T {
        return withContext(Dispatchers.IO) {
            task()
        }
    }

    /**
     * 等待任务完成
     *
     * @param name 任务名称
     * @param task 任务函数
     * @return 任务结果
     */
    suspend fun <T> awaitOnDefault(name: String, task: suspend () -> T): T {
        return withContext(Dispatchers.Default) {
            task()
        }
    }

    /**
     * 重置统计
     */
    fun resetStats() {
        taskCount = 0
        completedCount = 0
        failedCount = 0
    }

    /**
     * 获取成功率
     *
     * @return 成功率 (0..1)
     */
    fun successRate(): Float {
        return if (taskCount > 0) completedCount.toFloat() / taskCount else 0f
    }

    /**
     * 获取失败率
     *
     * @return 失败率 (0..1)
     */
    fun failureRate(): Float {
        return if (taskCount > 0) failedCount.toFloat() / taskCount else 0f
    }
}

/**
 * 记住 ComposeProcessor
 */
@Composable
fun rememberComposeProcessor(): ComposeProcessor {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeProcessor(scope) }
}
