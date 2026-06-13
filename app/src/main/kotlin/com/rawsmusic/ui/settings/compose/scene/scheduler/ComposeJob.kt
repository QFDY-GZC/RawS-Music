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

/**
 * Compose 作业
 * 对应原版的作业系统
 *
 * 管理作业执行
 */
@Stable
class ComposeJob(
    private val scope: CoroutineScope
) {
    // ==================== 作业状态 ====================

    /** 当前作业 */
    private var currentJob: Job? = null

    /** 是否正在运行 */
    var isRunning by mutableStateOf(false)
        private set

    /** 作业进度 (0..1) */
    var progress by mutableStateOf(0f)
        private set

    /** 作业状态 */
    var status by mutableStateOf(JobStatus.IDLE)
        private set

    /** 错误信息 */
    var error by mutableStateOf<String?>(null)
        private set

    // ==================== 方法 ====================

    /**
     * 启动作业
     *
     * @param name 作业名称
     * @param task 作业函数
     */
    fun start(name: String, task: suspend () -> Unit) {
        cancel() // 取消之前的作业

        currentJob = scope.launch {
            try {
                isRunning = true
                status = JobStatus.RUNNING
                error = null
                progress = 0f

                task()

                progress = 1f
                status = JobStatus.COMPLETED
            } catch (e: Exception) {
                status = JobStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 启动带进度的作业
     *
     * @param name 作业名称
     * @param task 作业函数
     */
    fun startWithProgress(name: String, task: suspend (suspend (Float) -> Unit) -> Unit) {
        cancel() // 取消之前的作业

        currentJob = scope.launch {
            try {
                isRunning = true
                status = JobStatus.RUNNING
                error = null
                progress = 0f

                task { newProgress ->
                    progress = newProgress.coerceIn(0f, 1f)
                }

                progress = 1f
                status = JobStatus.COMPLETED
            } catch (e: Exception) {
                status = JobStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 启动延迟作业
     *
     * @param name 作业名称
     * @param delay 延迟 (ms)
     * @param task 作业函数
     */
    fun startDelayed(name: String, delay: Long, task: suspend () -> Unit) {
        cancel() // 取消之前的作业

        currentJob = scope.launch {
            try {
                isRunning = true
                status = JobStatus.RUNNING
                error = null
                progress = 0f

                delay(delay)

                task()

                progress = 1f
                status = JobStatus.COMPLETED
            } catch (e: Exception) {
                status = JobStatus.FAILED
                error = e.message
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * 取消作业
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        isRunning = false
        status = JobStatus.CANCELLED
        progress = 0f
    }

    /**
     * 重置作业状态
     */
    fun reset() {
        cancel()
        status = JobStatus.IDLE
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
        return status == JobStatus.COMPLETED
    }

    /**
     * 检查是否失败
     *
     * @return 是否失败
     */
    fun isFailed(): Boolean {
        return status == JobStatus.FAILED
    }

    /**
     * 检查是否已取消
     *
     * @return 是否已取消
     */
    fun isCancelled(): Boolean {
        return status == JobStatus.CANCELLED
    }

    /**
     * 检查是否空闲
     *
     * @return 是否空闲
     */
    fun isIdle(): Boolean {
        return status == JobStatus.IDLE
    }
}

/**
 * 作业状态
 */
enum class JobStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 记住 ComposeJob
 */
@Composable
fun rememberComposeJob(): ComposeJob {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeJob(scope) }
}
