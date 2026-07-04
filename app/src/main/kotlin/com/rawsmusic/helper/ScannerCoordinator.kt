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

    fun scheduleStartupScan(delayMs: Long = 10_000L) {
        if (startupScheduled) return
        startupScheduled = true
        mainHandler.postDelayed({
            if (isActivityAlive()) {
                startupScanHelper.start()
            }
        }, delayMs)
    }

    fun onPermissionGranted() {
        scheduleStartupScan(delayMs = 2_000L)
    }

    fun release() {
        startupScheduled = false
    }
}
