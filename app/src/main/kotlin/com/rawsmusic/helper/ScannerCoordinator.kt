package com.rawsmusic.helper

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 扫描协调器。
 *
 * 统一管理扫描启动、权限后扫描、手动扫描。
 * 第一轮：封装调用，不搬状态。
 * 第二轮：把 StartupScanHelper 状态真正搬进来。
 */
class ScannerCoordinator(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val isActivityAlive: () -> Boolean = { true },
    private val startupScanHelper: StartupScanHelper
) {
    private var startupScheduled = false

    fun scheduleStartupScan(delayMs: Long = 1_000L) {
        if (startupScheduled) return
        startupScheduled = true
        mainHandler.postDelayed({
            if (isActivityAlive()) {
                startupScanHelper.start()
            }
        }, delayMs)
    }

    fun onPermissionGranted() {
        // 权限通过后不自动触发扫描，只初始化扫描入口和 MediaStore 变化监听
        // 用户需要手动选择文件夹后才会触发扫描
        if (isActivityAlive()) {
            startupScanHelper.start()
        }
    }

    fun release() {
        startupScheduled = false
    }
}
