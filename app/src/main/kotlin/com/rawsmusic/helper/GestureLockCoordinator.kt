package com.rawsmusic.helper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 手势锁协调器。
 *
 * 多个组件可以同时加锁，不会因为某一个地方结束就把所有手势解开。
 * 用法：
 *   val lock = coordinator.acquire(Reason.ProgressSeek)
 *   // ... 拖动中 ...
 *   lock.release()
 *
 * 或者用 set：
 *   coordinator.set(Reason.PlayerModal, true)
 *   // ... 弹窗关闭 ...
 *   coordinator.set(Reason.PlayerModal, false)
 */
class GestureLockCoordinator(
    private val applySceneIntercept: (Boolean) -> Unit
) {
    private val locks = mutableStateMapOf<GestureLockReason, Int>()

    /** 是否有任何锁生效 */
    var isBlocked by mutableStateOf(false)
        private set

    /** 进度条是否正在拖动（单独暴露，供 UI 防回跳用） */
    var isProgressSeeking by mutableStateOf(false)
        private set

    fun acquire(reason: GestureLockReason): GestureLock {
        locks[reason] = (locks[reason] ?: 0) + 1
        if (reason == GestureLockReason.ProgressSeek) isProgressSeeking = true
        recompute()
        return GestureLock { release(reason) }
    }

    fun set(reason: GestureLockReason, blocked: Boolean) {
        if (blocked) {
            if ((locks[reason] ?: 0) <= 0) locks[reason] = 1
            if (reason == GestureLockReason.ProgressSeek) isProgressSeeking = true
        } else {
            locks.remove(reason)
            if (reason == GestureLockReason.ProgressSeek) isProgressSeeking = false
        }
        recompute()
    }

    fun release(reason: GestureLockReason) {
        val count = locks[reason] ?: return
        if (count <= 1) {
            locks.remove(reason)
            if (reason == GestureLockReason.ProgressSeek) isProgressSeeking = false
        } else {
            locks[reason] = count - 1
        }
        recompute()
    }

    fun clear() {
        locks.clear()
        isProgressSeeking = false
        recompute()
    }

    private fun recompute() {
        val blocked = locks.values.any { it > 0 }
        if (isBlocked != blocked) isBlocked = blocked
        applySceneIntercept(blocked)
    }
}

enum class GestureLockReason {
    ProgressSeek,
    PlayerModal,
    SongActionSheet,
    MetadataDetail,
    MetadataEditor,
    AudioInfoPopup,
    PlayModePopup,
    Dialog,
    BatteryOptimization,
    SceneTransition
}

class GestureLock internal constructor(
    private val onRelease: () -> Unit
) {
    private var released = false
    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}
