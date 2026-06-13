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
 * Compose 服务
 * 对应原版的服务系统
 *
 * 管理服务执行
 */
@Stable
class ComposeService(
    private val scope: CoroutineScope
) {
    // ==================== 服务状态 ====================

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
     * 启动服务
     */
    fun start() {
        isRunning = true
    }

    /**
     * 停止服务
     */
    fun stop() {
        isRunning = false
    }

    /**
     * 执行服务任务
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun execute(name: String, task: suspend () -> Unit) {
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
     * 执行延迟服务任务
     *
     * @param name 任务名称
     * @param delay 延迟 (ms)
     * @param task 任务函数
     */
    fun executeDelayed(name: String, delay: Long, task: suspend () -> Unit) {
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
     * 执行重复服务任务
     *
     * @param name 任务名称
     * @param interval 间隔 (ms)
     * @param task 任务函数
     */
    fun executeRepeating(name: String, interval: Long, task: suspend () -> Unit) {
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
     * 在主线程执行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun executeOnMain(name: String, task: suspend () -> Unit) {
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
     * 在 IO 线程执行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun executeOnIO(name: String, task: suspend () -> Unit) {
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
     * 在默认线程执行
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun executeOnDefault(name: String, task: suspend () -> Unit) {
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
 * 记住 ComposeService
 */
@Composable
fun rememberComposeService(): ComposeService {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeService(scope) }
}
