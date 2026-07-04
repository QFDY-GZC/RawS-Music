package com.rawsmusic.module.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.utils.AppLogger

/**
 * 存储事件监听器。
 *
 * 监听 SD卡挂载/卸载、USB 状态变化等事件，
 * 通知 ScanScheduler 调度扫描。
 */
class StorageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StorageChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLogger.d(TAG, "onReceive: $action")

        when (action) {
            Intent.ACTION_MEDIA_MOUNTED -> {
                val data = intent.data
                val path = data?.path ?: "unknown"
                AppLogger.d(TAG, "Media mounted: $path")
                ScanScheduler.onStorageChanged(context, "media_mounted: $path")
            }

            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED -> {
                val data = intent.data
                val path = data?.path ?: "unknown"
                AppLogger.d(TAG, "Media removed/unmounted: $path")
                // 存储移除时不需要扫描，但可以更新数据库
            }

            "android.hardware.usb.action.USB_STATE" -> {
                val usbConnected = intent.getBooleanExtra("connected", false)
                if (!usbConnected) {
                    AppLogger.d(TAG, "USB disconnected, scheduling scan")
                    ScanScheduler.onStorageChanged(context, "usb_disconnected")
                }
            }
        }
    }
}
