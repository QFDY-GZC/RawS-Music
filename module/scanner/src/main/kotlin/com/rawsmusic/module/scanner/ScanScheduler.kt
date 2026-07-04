package com.rawsmusic.module.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.common.utils.AppLogger

object ScanScheduler {

    private const val TAG = "ScanScheduler"
    private const val COOLDOWN_AFTER_SCAN_MS = 3_000L
    private const val COOLDOWN_AFTER_SAF_MS = 11_000L
    private const val DEBOUNCE_INITIAL_MS = 2_000L
    private const val DEBOUNCE_MAX_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var disableCount = 0
    private var lastScanEndTime = 0L
    private var lastSafActivityTime = 0L
    private var currentDebounceMs = DEBOUNCE_INITIAL_MS
    private var pendingDirRunnable: Runnable? = null
    private var pendingRequest: ScanRequest? = null

    private data class ScanRequest(
        val context: Context, val reason: String,
        val fastScan: Boolean, val manual: Boolean
    )

    fun scheduleInitialScan(context: Context) {
        AppLogger.d(TAG, "scheduleInitialScan")
        if (!AudioReadPermission.hasPermission(context)) {
            AppLogger.w(TAG, "scheduleInitialScan blocked: no audio permission")
            ScanStateBus.notifyPermissionRequired()
            return
        }
        AppLogger.d(TAG, "initial auto scan skipped; waiting for manual scan")
    }

    fun onStorageChanged(context: Context, reason: String) {
        AppLogger.d(TAG, "onStorageChanged: $reason")
        ScanStateBus.notifyIdleMessage("媒体存储有变化，手动刷新可重新扫描")
    }

    fun onContentChanged(context: Context) {
        AppLogger.d(TAG, "onContentChanged")
        pendingDirRunnable?.let { handler.removeCallbacks(it) }
        pendingDirRunnable = null
        val delay = currentDebounceMs
        currentDebounceMs = (currentDebounceMs * 2).coerceAtMost(DEBOUNCE_MAX_MS)
        AppLogger.d(TAG, "content auto scan skipped, old debounce=${delay}ms")
        ScanStateBus.notifyIdleMessage("媒体库有变化，手动刷新可重新扫描")
    }

    fun requestDirScan(context: Context, reason: String = "manual") {
        AppLogger.d(TAG, "requestDirScan: $reason")
        if (!AudioReadPermission.hasPermission(context)) {
            AppLogger.w(TAG, "requestDirScan blocked: no audio permission")
            ScanStateBus.notifyPermissionRequired()
            return
        }
        startOrQueue(ScanRequest(context.applicationContext, reason, fastScan = false, manual = true))
    }

    fun requestTagScan(context: Context, reason: String = "manual") {
        AppLogger.d(TAG, "requestTagScan: $reason")
        if (disableCount > 0) { AppLogger.d(TAG, "requestTagScan blocked"); return }
        if (ScanDispatcher.isScanning()) {
            AppLogger.d(TAG, "requestTagScan ignored: scanning")
            ScanStateBus.notifyScanning(0, 0, "正在扫描，稍后再试")
            return
        }
        ScanDispatcher.dispatchTagScan(context.applicationContext) { markScanComplete(context.applicationContext) }
    }

    fun requestIncrementalScan(context: Context) {
        AppLogger.d(TAG, "requestIncrementalScan")
        startOrQueue(ScanRequest(context.applicationContext, "incremental scan", fastScan = true, manual = true))
    }

    fun cancelCurrentScan() {
        AppLogger.d(TAG, "cancelCurrentScan")
        pendingDirRunnable?.let { handler.removeCallbacks(it) }
        pendingDirRunnable = null; pendingRequest = null; currentDebounceMs = DEBOUNCE_INITIAL_MS
        ScanDispatcher.cancelCurrentScan()
    }

    fun disableScan() { disableCount++; AppLogger.d(TAG, "disableScan: count=$disableCount") }
    fun enableScan() { disableCount = (disableCount - 1).coerceAtLeast(0); AppLogger.d(TAG, "enableScan: count=$disableCount") }
    fun notifySafActivity() { lastSafActivityTime = System.currentTimeMillis() }

    private fun scheduleDirScan(context: Context, reason: String, fastScan: Boolean, delayMs: Long, manual: Boolean) {
        val appCtx = context.applicationContext
        pendingDirRunnable?.let { handler.removeCallbacks(it) }
        val request = ScanRequest(appCtx, reason, fastScan, manual)
        val runnable = Runnable { pendingDirRunnable = null; startOrQueue(request) }
        pendingDirRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        AppLogger.d(TAG, "scheduleDirScan: reason=$reason, fast=$fastScan, manual=$manual, delay=${delayMs}ms")
    }

    private fun startOrQueue(request: ScanRequest) {
        if (disableCount > 0) { AppLogger.d(TAG, "blocked by disableCount"); return }

        if (ScanDispatcher.isScanning()) {
            pendingRequest = request.copy(manual = false)
            AppLogger.d(TAG, "scanning, pending=${request.reason}")
            ScanStateBus.notifyScanning(0, 0, "正在扫描，已记录新的扫描请求")
            return
        }

        if (!request.manual) {
            val cd = cooldownDelay()
            if (cd > 0L) {
                AppLogger.d(TAG, "cooldown ${cd}ms, reschedule ${request.reason}")
                scheduleDirScan(request.context, request.reason, request.fastScan, cd, false)
                return
            }
        }

        AppLogger.d(TAG, "start scan: ${request.reason}, fast=${request.fastScan}, manual=${request.manual}")
        ScanDispatcher.dispatchDirScan(request.context, request.fastScan) { markScanComplete(request.context) }
    }

    private fun cooldownDelay(): Long {
        val now = System.currentTimeMillis()
        val d1 = if (lastScanEndTime > 0) COOLDOWN_AFTER_SCAN_MS - (now - lastScanEndTime) else 0L
        val d2 = if (lastSafActivityTime > 0) COOLDOWN_AFTER_SAF_MS - (now - lastSafActivityTime) else 0L
        return maxOf(d1, d2, 0L)
    }

    private fun markScanComplete(context: Context) {
        lastScanEndTime = System.currentTimeMillis()
        currentDebounceMs = DEBOUNCE_INITIAL_MS
        val pending = pendingRequest; pendingRequest = null
        AppLogger.d(TAG, "markScanComplete, pending=${pending?.reason}")
        if (pending != null && disableCount == 0) {
            scheduleDirScan(context.applicationContext, pending.reason, pending.fastScan, 1_000L, false)
        }
    }
}
