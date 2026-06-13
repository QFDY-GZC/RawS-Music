package com.rawsmusic.ui.settings.compose.scene.scheduler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rawsmusic.ui.settings.compose.scene.scheduler.TaskStatus

/**
 * Compose 任务
 * 对应原版的任务系统
 *
 * 管理任务执行
 */
@Stable
class ComposeTask(
    private val scope: CoroutineScope
) {
    // ==================== 任务状态 ====================

    /** 当前任务 */
    private var currentJob: Job? = null

    /** 是否正在运行 */
    var isRunning by mutableStateOf(false)
        private set

    /** 任务进度 (0..1) */
    var progress by mutableStateOf(0f)
        private set

    /** 任务状态 */
    var status by mutableStateOf(TaskStatus.IDLE)
        private set

    /** 错误信息 */
    var error by mutableStateOf<String?>(null)
        private set

    // ==================== 方法 ====================

    /**
     * 执行任务
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun execute(name: String, task: suspend () -> Unit) {
        cancel() // 取消之前的任务

        currentJob = scope.launch {
            try {
                isRunning = true
                status = TaskStatus.RUNNING
                error = null
                progress = 0f

                task()

                progress = 1f
                status = TaskStatus.COMPLETED
            } catch (e: Exception) {
                status = TaskStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 执行带进度的任务
     *
     * @param name 任务名称
     * @param task 任务函数
     */
    fun executeWithProgress(name: String, task: suspend (suspend (Float) -> Unit) -> Unit) {
        cancel() // 取消之前的任务

        currentJob = scope.launch {
            try {
                isRunning = true
                status = TaskStatus.RUNNING
                error = null
                progress = 0f

                task { newProgress ->
                    progress = newProgress.coerceIn(0f, 1f)
                }

                progress = 1f
                status = TaskStatus.COMPLETED
            } catch (e: Exception) {
                status = TaskStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 执行延迟任务
     *
     * @param name 任务名称
     * @param delay 延迟 (ms)
     * @param task 任务函数
     */
    fun executeDelayed(name: String, delay: Long, task: suspend () -> Unit) {
        cancel() // 取消之前的任务

        currentJob = scope.launch {
            try {
                isRunning = true
                status = TaskStatus.RUNNING
                error = null
                progress = 0f

                delay(delay)

                task()

                progress = 1f
                status = TaskStatus.COMPLETED
            } catch (e: Exception) {
                status = TaskStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 取消任务
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        isRunning = false
        status = TaskStatus.CANCELLED
        progress = 0f
    }

    /**
     * 重置任务状态
     */
    fun reset() {
        cancel()
        status = TaskStatus.IDLE
        error = null
        progress = 0f
    }

    /**
     * 更新进度
     *
     * @param newProgress 进度 (0..1)
     */
    fun updateProgress(newProgress: Float) {
        progress = newProgress.coerceIn(0f, 1f)
    }

    /**
     * 检查是否已完成
     *
     * @return 是否已完成
     */
    fun isCompleted(): Boolean {
        return status == TaskStatus.COMPLETED
    }

    /**
     * 检查是否失败
     *
     * @return 是否失败
     */
    fun isFailed(): Boolean {
        return status == TaskStatus.FAILED
    }

    /**
     * 检查是否已取消
     *
     * @return 是否已取消
     */
    fun isCancelled(): Boolean {
        return status == TaskStatus.CANCELLED
    }

    /**
     * 检查是否空闲
     *
     * @return 是否空闲
     */
    fun isIdle(): Boolean {
        return status == TaskStatus.IDLE
    }
}

/**
 * 记住 ComposeTask
 */
@Composable
fun rememberComposeTask(): ComposeTask {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeTask(scope) }
}
