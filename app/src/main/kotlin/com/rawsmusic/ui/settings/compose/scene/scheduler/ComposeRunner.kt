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
 * Compose 运行器
 * 对应原版的运行器系统
 *
 * 管理任务运行
 */
@Stable
class ComposeRunner(
    private val scope: CoroutineScope
) {
    // ==================== 运行器状态 ====================

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
     * 启动运行器
     */
    fun start() {
        isRunning = true
    }

    /**
     * 停止运行器
     */
    fun stop() {
        isRunning = false
    }

    /**
     * 运行任务
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun run(name: String, task: suspend () -> Unit) {
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
     * 运行延迟任务
     *
     * @param name 任务名称
     * @param delay 延迟 (ms)
     * @param task 任务函数
     */
    fun runDelayed(name: String, delay: Long, task: suspend () -> Unit) {
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
     * 运行重复任务
     *
     * @param name 任务名称
     * @param interval 间隔 (ms)
     * @param task 任务函数
     */
    fun runRepeating(name: String, interval: Long, task: suspend () -> Unit) {
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
     * 在主线程运行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun runOnMain(name: String, task: suspend () -> Unit) {
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
     * 在 IO 线程运行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun runOnIO(name: String, task: suspend () -> Unit) {
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
     * 在默认线程运行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun runOnDefault(name: String, task: suspend () -> Unit) {
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
 * 记住 ComposeRunner
 */
@Composable
fun rememberComposeRunner(): ComposeRunner {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeRunner(scope) }
}
