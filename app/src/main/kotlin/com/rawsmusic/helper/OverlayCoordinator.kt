package com.rawsmusic.helper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Overlay 显隐协调器。
 *
 * 统一管理所有浮层/弹窗/对话框的可见状态，
 * 替代 MainActivity 里散落的 updateComposeRootVisibility() 聚合逻辑。
 */
class OverlayCoordinator(
    private val isPlayerPageVisible: () -> Boolean
) {
    /** Compose 根内容是否可见（false 时隐藏主界面，避免弹窗后面看到内容） */
    var composeOverlayContentVisible by mutableStateOf(true)
        private set

    /** USB 音量浮层 */
    var usbVolumeOverlayText by mutableStateOf("")
        private set
    var isUsbVolumeOverlayVisible by mutableStateOf(false)
        private set

    /** 文件夹选择弹窗 */
    var showFolderDialog by mutableStateOf(false)

    fun setFolderDialogVisible(visible: Boolean) {
        showFolderDialog = visible
        refresh()
    }

    fun showUsbVolume(text: String) {
        usbVolumeOverlayText = text
        isUsbVolumeOverlayVisible = true
        refresh(forceVisible = true)
    }

    fun hideUsbVolume() {
        isUsbVolumeOverlayVisible = false
        refresh()
    }

    /**
     * 重新计算 Compose 根内容可见性。
     *
     * 任何 helper 的弹窗状态变化后都应调用此方法。
     * 第一阶段由 MainActivity 聚合调用，后续各 helper 直接通知。
     */
    fun refresh(
        forceVisible: Boolean = false,
        songActionSheetVisible: Boolean = false,
        playlistPickerVisible: Boolean = false,
        metadataDetailVisible: Boolean = false,
        metadataEditorVisible: Boolean = false,
        metadataDeleteConfirmVisible: Boolean = false,
        audioInfoVisible: Boolean = false,
        metadataCardVisible: Boolean = false,
        playModeVisible: Boolean = false,
        dialogVisible: Boolean = false,
        batteryVisible: Boolean = false
    ) {
        composeOverlayContentVisible =
            forceVisible ||
                songActionSheetVisible ||
                playlistPickerVisible ||
                metadataDetailVisible ||
                metadataEditorVisible ||
                metadataDeleteConfirmVisible ||
                audioInfoVisible ||
                metadataCardVisible ||
                playModeVisible ||
                dialogVisible ||
                batteryVisible ||
                isUsbVolumeOverlayVisible ||
                isPlayerPageVisible()
    }
}
