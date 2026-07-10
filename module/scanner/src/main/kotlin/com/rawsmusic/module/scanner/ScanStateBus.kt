package com.rawsmusic.module.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 扫描状态广播。
 * UI 组件通过 StateFlow 订阅扫描状态变化。
 */
object ScanStateBus {

    enum class ScanState {
        IDLE,
        DIR_SCAN_STARTED,
        TAG_SCAN_STARTED,
        SCANNING,
        COMPLETED,
        ERROR,
        FOLDER_SELECTION_NEEDED,  // 首次运行，需要用户选择文件夹
        PERMISSION_REQUIRED       // 缺少音频读取权限
    }

    data class ScanStatus(
        val state: ScanState = ScanState.IDLE,
        val message: String = "",
        val progress: Int = 0,
        val total: Int = 0,
        val timeMs: Long = 0
    )

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    fun notifyDirScanStarted(total: Int = 0) {
        _status.value = ScanStatus(
            state = ScanState.DIR_SCAN_STARTED,
            message = "目录扫描开始",
            total = total
        )
    }

    fun notifyTagScanStarted(total: Int = 0) {
        _status.value = ScanStatus(
            state = ScanState.TAG_SCAN_STARTED,
            message = "标签扫描开始",
            total = total
        )
    }

    fun notifyScanning(progress: Int, total: Int, message: String = "") {
        _status.value = ScanStatus(
            state = ScanState.SCANNING,
            message = message.ifBlank { "扫描中 $progress/$total" },
            progress = progress,
            total = total
        )
    }

    fun notifyIdleMessage(message: String) {
        _status.value = ScanStatus(
            state = ScanState.IDLE,
            message = message
        )
    }

    fun notifyCompleted(found: Int, timeMs: Long) {
        _status.value = ScanStatus(
            state = ScanState.COMPLETED,
            message = "扫描完成: $found 首, ${timeMs}ms",
            progress = found,
            total = found,
            timeMs = timeMs
        )
    }

    fun notifyError(message: String) {
        _status.value = ScanStatus(
            state = ScanState.ERROR,
            message = "扫描错误: $message"
        )
    }

    fun notifyFolderSelectionNeeded() {
        _status.value = ScanStatus(
            state = ScanState.FOLDER_SELECTION_NEEDED,
            message = "请先选择要扫描的文件夹"
        )
    }

    fun reset() {
        _status.value = ScanStatus()
    }

    fun notifyPermissionRequired() {
        _status.value = ScanStatus(
            state = ScanState.PERMISSION_REQUIRED,
            message = "需要音乐读取权限"
        )
    }

    fun isScanning(): Boolean {
        val state = _status.value.state
        return state == ScanState.DIR_SCAN_STARTED ||
            state == ScanState.TAG_SCAN_STARTED ||
            state == ScanState.SCANNING
    }
}
