package com.rawsmusic.ui.settings.compose.scene.scheduler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Compose 调度器
 * 对应原版的调度系统
 *
 * 管理任务调度
 */
@Stable
class ComposeScheduler(
    private val scope: CoroutineScope
) {
    // ==================== 调度状态 ====================

    /** 是否正在运行 */
    var isRunning by mutableStateOf(false)
        private set

    /** 任务队列 */
    private val taskQueue = mutableStateListOf<ScheduledTask>()

    /** 已完成任务 */
    private val completedTasks = mutableStateListOf<ScheduledTask>()

    /** 任务计数 */
    var taskCount by mutableStateOf(0)
        private set

    /** 已完成任务计数 */
    var completedCount by mutableStateOf(0)
        private set

    // ==================== 方法 ====================

    /**
     * 启动调度器
     */
    fun start() {
        isRunning = true
        processQueue()
    }

    /**
     * 停止调度器
     */
    fun stop() {
        isRunning = false
    }

    /**
     * 添加任务
     *
     * @param name 任务名称
     * @param delay 延迟 (ms)
     * @param task 任务函数
     */
    fun addTask(name: String, delay: Long = 0, task: suspend () -> Unit) {
        val scheduledTask = ScheduledTask(
            name = name,
            delay = delay,
            task = task,
            status = TaskStatus.PENDING
        )
        taskQueue.add(scheduledTask)
        taskCount++

        if (isRunning) {
            processQueue()
        }
    }

    /**
     * 添加定时任务
     *
     * @param name 任务名称
     * @param interval 间隔 (ms)
     * @param task 任务函数
     */
    fun addRepeatingTask(name: String, interval: Long, task: suspend () -> Unit) {
        val scheduledTask = ScheduledTask(
            name = name,
            interval = interval,
            task = task,
            status = TaskStatus.PENDING,
            repeating = true
        )
        taskQueue.add(scheduledTask)
        taskCount++

        if (isRunning) {
            processQueue()
        }
    }

    /**
     * 移除任务
     *
     * @param name 任务名称
     */
    fun removeTask(name: String) {
        taskQueue.removeAll { it.name == name }
    }

    /**
     * 取消任务
     *
     * @param name 任务名称
     */
    fun cancelTask(name: String) {
        taskQueue.find { it.name == name }?.let { task ->
            task.status = TaskStatus.CANCELLED
        }
    }

    /**
     * 清空任务队列
     */
    fun clearQueue() {
        taskQueue.clear()
        completedTasks.clear()
        taskCount = 0
        completedCount = 0
    }

    /**
     * 获取任务状态
     *
     * @param name 任务名称
     * @return 任务状态
     */
    fun getTaskStatus(name: String): TaskStatus? {
        return taskQueue.find { it.name == name }?.status
    }

    /**
     * 获取待处理任务
     *
     * @return 待处理任务列表
     */
    fun getPendingTasks(): List<ScheduledTask> {
        return taskQueue.filter { it.status == TaskStatus.PENDING }
    }

    /**
     * 获取已完成任务
     *
     * @return 已完成任务列表
     */
    fun getCompletedTasks(): List<ScheduledTask> {
        return completedTasks.toList()
    }

    /**
     * 处理任务队列
     */
    private fun processQueue() {
        scope.launch(Dispatchers.Default) {
            while (isRunning) {
                val pendingTasks = taskQueue.filter { it.status == TaskStatus.PENDING }

                pendingTasks.forEach { task ->
                    scope.launch {
                        executeTask(task)
                    }
                }

                delay(100) // 每 100ms 检查一次
            }
        }
    }

    /**
     * 执行任务
     *
     * @param task 任务
     */
    private suspend fun executeTask(task: ScheduledTask) {
        try {
            task.status = TaskStatus.RUNNING

            if (task.delay > 0) {
                delay(task.delay)
            }

            task.task()

            task.status = TaskStatus.COMPLETED
            task.completionTime = System.currentTimeMillis()
            completedTasks.add(task)
            completedCount++

            if (task.repeating) {
                // 重新添加到队列
                task.status = TaskStatus.PENDING
            } else {
                taskQueue.remove(task)
            }
        } catch (e: Exception) {
            task.status = TaskStatus.FAILED
            task.error = e.message
        }
    }
}

/**
 * 调度任务
 */
data class ScheduledTask(
    val name: String,
    val delay: Long = 0,
    val interval: Long = 0,
    val task: suspend () -> Unit,
    var status: TaskStatus,
    var completionTime: Long? = null,
    var error: String? = null,
    val repeating: Boolean = false
)

/**
 * 任务状态
 */
enum class TaskStatus {
    IDLE,
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 记住 ComposeScheduler
 */
@Composable
fun rememberComposeScheduler(): ComposeScheduler {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeScheduler(scope) }
}
