package com.rawsmusic.module.scanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import android.widget.Toast
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LibraryScanForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LibraryScanServiceActions.ACTION_CANCEL -> { cancelScan(); return START_NOT_STICKY }
            LibraryScanServiceActions.ACTION_START, null -> {
                val reason = intent?.getStringExtra(LibraryScanServiceActions.EXTRA_REASON)
                    ?: LibraryScanServiceActions.REASON_MANUAL
                startScanIfNeeded(reason)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startScanIfNeeded(reason: String) {
        if (scanJob?.isActive == true) {
            LibraryScanEventBus.updateState(
                LibraryScanEventBus.scanState.value.copy(pendingScan = true, message = "扫描正在进行，已记录新的扫描请求")
            )
            return
        }
        val startingState = ScanUiState.starting(reason)
        LibraryScanEventBus.updateState(startingState)
        startForeground(NOTIFICATION_ID, buildNotification(startingState))
        scanJob = serviceScope.launch { runScan(reason) }
        AppLogger.d(TAG, "Foreground scan started: $reason")
    }

    private suspend fun runScan(reason: String) {
        val repository = try { LibraryScannerDependencies.repository(this) } catch (_: Exception) {
            AppLogger.e(TAG, "No repository installed"); stopSelf(); return
        }
        val coordinator = LibraryScanCoordinator(repository)

        try {
            coordinator.scanAndSync(
                context = applicationContext,
                options = TwoStageMediaScanner.Options(
                    scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
                    customPaths = AppPreferences.UI.scanPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    expandCueTracks = true, emitEachSong = false, usePersistentCache = true
                )
            ).collect { event ->
                val state = reduceEventToState(reason, LibraryScanEventBus.scanState.value, event)
                LibraryScanEventBus.updateState(state)
                updateNotification(state)
                when (event) {
                    is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                        if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) {
                            showScanToast(
                                added = event.added,
                                updated = event.updated,
                                deleted = event.deleted
                            )
                        }
                    }
                    is LibraryScanCoordinator.Event.VisibleCompleted -> {
                        // Project-style: publish the fast MediaStore list as soon as it is
                        // visible in Room. Enrichment continues, but the library can be used now.
                        LibraryScanEventBus.tryEmitSongs(event.songs)
                        ScanStateBus.notifyCompleted(event.found, event.timeMs)
                        AppLogger.d(TAG, "quick visible completed: found=${event.found} time=${event.timeMs}ms; enrichment continues")
                    }
                    is LibraryScanCoordinator.Event.Completed -> {
                        LibraryScanEventBus.tryEmitSongs(event.songs)
                    }
                    else -> Unit
                }
            }

            val doneState = LibraryScanEventBus.scanState.value.copy(
                isScanning = false, canCancel = false, pendingScan = false,
                stage = "完成", progress = 1f
            )
            LibraryScanEventBus.updateState(doneState)
            updateNotification(doneState)
            AppLogger.d(TAG, "Foreground scan completed")
            stopSelf()
        } catch (e: CancellationException) {
            LibraryScanEventBus.updateState(ScanUiState.cancelled())
            updateNotification(ScanUiState.cancelled())
            AppLogger.d(TAG, "Foreground scan cancelled")
            stopSelf()
        } catch (e: Exception) {
            val errorState = LibraryScanEventBus.scanState.value.copy(
                isScanning = false, canCancel = false, stage = "错误",
                error = e.message, message = "扫描失败：${e.message}"
            )
            LibraryScanEventBus.updateState(errorState)
            updateNotification(errorState)
            AppLogger.e(TAG, "Foreground scan failed", e)
            stopSelf()
        }
    }

    private fun cancelScan() {
        scanJob?.cancel(CancellationException("User cancelled"))
        scanJob = null
        LibraryScanEventBus.updateState(ScanUiState.cancelled())
        stopSelf()
    }

    private fun reduceEventToState(reason: String, current: ScanUiState, event: LibraryScanCoordinator.Event): ScanUiState {
        return when (event) {
            is LibraryScanCoordinator.Event.ScannerEvent -> reduceScannerEvent(reason, current, event.event)
            is LibraryScanCoordinator.Event.DatabaseSyncStarted -> {
                val stageText = when (event.phase) {
                    LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> "快速写入"
                    LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> "后台补全"
                    LibraryScanCoordinator.SyncPhase.FINAL -> "最终同步"
                }
                current.copy(
                    isScanning = true, canCancel = true, stage = stageText,
                    progress = if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) 0.90f else current.progress,
                    message = "$reason：$stageText ${event.newCount} 首"
                )
            }
            is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                val message = when (event.phase) {
                    LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> "$reason：快速结果已显示，新增/变更 ${event.upserted} 首"
                    LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> "$reason：后台补全已写入 ${event.upserted} 首"
                    LibraryScanCoordinator.SyncPhase.FINAL -> "$reason：数据库同步完成，新增 ${event.added}，更新 ${event.updated}，删除 ${event.deleted}，未变 ${event.unchanged}"
                }
                current.copy(
                    isScanning = true, canCancel = true, stage = "数据库完成",
                    progress = if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) 0.96f else current.progress,
                    dbUpserted = event.upserted, dbDeleted = event.deleted, dbUnchanged = event.unchanged,
                    message = message
                )
            }
            is LibraryScanCoordinator.Event.VisibleCompleted -> current.copy(
                isScanning = true, canCancel = true, pendingScan = false, stage = "可浏览",
                progress = current.progress.coerceAtLeast(0.40f), found = event.found, timeMs = event.timeMs,
                message = "$reason：${event.found} 首已可浏览，后台继续补全音频信息"
            )
            is LibraryScanCoordinator.Event.Completed -> current.copy(
                isScanning = false, canCancel = false, pendingScan = false, stage = "完成",
                progress = 1f, found = event.songs.size, timeMs = event.timeMs,
                message = "$reason：完成 ${event.songs.size} 首，用时 ${event.timeMs}ms"
            )
            is LibraryScanCoordinator.Event.Error -> current.copy(
                isScanning = false, canCancel = false, stage = "错误",
                error = event.message, message = "$reason：失败 ${event.message}"
            )
        }
    }

    private fun reduceScannerEvent(reason: String, current: ScanUiState, event: TwoStageMediaScanner.Event): ScanUiState {
        return when (event) {
            is TwoStageMediaScanner.Event.Started -> current.copy(
                isScanning = true, canCancel = true, reason = reason, stage = "开始",
                total = event.totalEstimated, scanned = 0, progress = 0f,
                message = "$reason：开始扫描 ${event.totalEstimated} 个媒体项"
            )
            is TwoStageMediaScanner.Event.CacheLoaded -> current.copy(
                stage = "读取缓存", message = "$reason：已加载缓存 ${event.cachedCount} 条"
            )
            is TwoStageMediaScanner.Event.QuickProgress -> {
                val p = if (event.total > 0) event.scanned.toFloat() / event.total * 0.35f else 0f
                current.copy(isScanning = true, canCancel = true, stage = "快速扫描",
                    scanned = event.scanned, total = event.total, progress = p.coerceIn(0f, 0.35f),
                    message = "$reason：${event.message} ${event.scanned}/${event.total}")
            }
            is TwoStageMediaScanner.Event.QuickCompleted -> current.copy(
                isScanning = true, canCancel = true, stage = "快速扫描完成",
                found = event.found, progress = 0.35f,
                message = "$reason：已找到 ${event.found} 首，正在写入快速结果"
            )
            is TwoStageMediaScanner.Event.EnrichProgress -> {
                val ep = if (event.total > 0) event.processed.toFloat() / event.total else 0f
                current.copy(isScanning = true, canCancel = true, stage = "补全信息",
                    scanned = event.processed, total = event.total,
                    progress = (0.35f + ep * 0.55f).coerceIn(0.35f, 0.90f),
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = "$reason：${event.message} ${event.processed}/${event.total}，缓存 ${event.cacheHits}，新读 ${event.enrichedCount}")
            }
            is TwoStageMediaScanner.Event.SongEnriched -> current
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> current
            is TwoStageMediaScanner.Event.FullyCompleted -> current.copy(
                isScanning = true, canCancel = true, stage = "准备同步数据库", progress = 0.90f,
                found = event.found, cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                message = "$reason：补全完成，${event.found} 首，缓存 ${event.cacheHits}，新读 ${event.enrichedCount}"
            )
            is TwoStageMediaScanner.Event.Error -> current.copy(
                isScanning = false, canCancel = false, stage = "错误",
                error = event.message, message = "$reason：扫描失败 ${event.message}"
            )
        }
    }

    private fun buildNotification(state: ScanUiState): android.app.Notification {
        val cancelIntent = Intent(this, LibraryScanForegroundService::class.java).apply {
            action = LibraryScanServiceActions.ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(this, 1001, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RawSMusic 正在扫描音乐")
            .setContentText(state.message.ifBlank { "正在扫描音乐库" })
            .setOnlyAlertOnce(true)
            .setOngoing(state.isScanning)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.isScanning) {
            builder.setProgress(100, state.progressPercent, state.total <= 0)
            if (state.canCancel) {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
            }
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun updateNotification(state: ScanUiState) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "音乐库扫描", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示 RawSMusic 音乐库扫描进度"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun showScanToast(
        added: Int,
        updated: Int,
        deleted: Int
    ) {
        val text = when {
            added > 0 && updated > 0 && deleted > 0 ->
                "扫描完成：新增 $added 首，更新 $updated 首，移除 $deleted 首"
            added > 0 && updated > 0 ->
                "扫描完成：新增 $added 首，更新 $updated 首"
            added > 0 && deleted > 0 ->
                "扫描完成：新增 $added 首，移除 $deleted 首"
            added > 0 ->
                "扫描完成：新增 $added 首歌曲"
            updated > 0 || deleted > 0 ->
                "扫描完成：没有新增歌曲，更新 $updated 首，移除 $deleted 首"
            else ->
                "扫描完成：没有新增歌曲"
        }

        Toast.makeText(
            applicationContext,
            text,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val TAG = "LibraryScanService"
        private const val CHANNEL_ID = "rawsmusic_library_scan"
        private const val NOTIFICATION_ID = 3001

        fun start(context: Context, reason: String = LibraryScanServiceActions.REASON_MANUAL) {
            val intent = Intent(context, LibraryScanForegroundService::class.java).apply {
                action = LibraryScanServiceActions.ACTION_START
                putExtra(LibraryScanServiceActions.EXTRA_REASON, reason)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, LibraryScanForegroundService::class.java).apply {
                action = LibraryScanServiceActions.ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
