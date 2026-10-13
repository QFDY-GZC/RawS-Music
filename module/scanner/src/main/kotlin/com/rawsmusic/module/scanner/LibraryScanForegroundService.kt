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
    private var pendingScanReason: String? = null
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
        val displayReason = localizedReason(reason)
        if (scanJob?.isActive == true) {
            pendingScanReason = reason
            LibraryScanEventBus.updateState(
                LibraryScanEventBus.scanState.value.copy(
                    pendingScan = true,
                    message = getString(R.string.library_scan_pending_request),
                )
            )
            return
        }
        val startingState = ScanUiState.starting(
            reason = displayReason,
            stage = getString(R.string.library_scan_stage_start),
            message = getString(R.string.library_scan_preparing, displayReason),
        )
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
        var finalSync: LibraryScanCoordinator.Event.DatabaseSyncCompleted? = null
        var failed = false

        try {
            coordinator.scanAndSync(
                context = applicationContext,
                options = TwoStageMediaScanner.Options(
                    scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
                    customPaths = AppPreferences.UI.scanPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    sourceMode = TwoStageMediaScanner.SourceMode.fromPreferences(),
                    expandCueTracks = true, emitEachSong = false, usePersistentCache = true
                )
            ).collect { event ->
                val state = reduceEventToState(reason, LibraryScanEventBus.scanState.value, event)
                LibraryScanEventBus.updateState(state)
                updateNotification(state)
                when (event) {
                    is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                        if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) {
                            finalSync = event
                        }
                    }
                    is LibraryScanCoordinator.Event.VisibleCompleted -> {
                        // Project-style: publish the fast MediaStore list as soon as it is
                        // visible in Room. Enrichment continues, but the library can be used now.
                        if (event.hasChanges) LibraryScanEventBus.tryEmitSongs(event.songs)
                        ScanStateBus.notifyCompleted(event.found, event.timeMs)
                        AppLogger.d(TAG, "quick visible completed: found=${event.found} time=${event.timeMs}ms; enrichment continues")
                    }
                    is LibraryScanCoordinator.Event.Completed -> {
                        if (event.hasChanges) LibraryScanEventBus.tryEmitSongs(event.songs)
                        val stats = finalSync
                        LibraryScanToast.show(
                            applicationContext,
                            LibraryScanSummary(
                                scanned = event.songs.size,
                                added = stats?.added ?: 0,
                                updated = stats?.updated ?: 0,
                                removed = stats?.deleted ?: 0,
                                elapsedMs = event.timeMs
                            )
                        )
                    }
                    is LibraryScanCoordinator.Event.Error -> failed = true
                    else -> Unit
                }
            }

            if (failed) {
                AppLogger.w(TAG, "Foreground scan ended with a scanner error")
                stopSelf()
                return
            }

            val doneState = LibraryScanEventBus.scanState.value.copy(
                isScanning = false, canCancel = false, pendingScan = false,
                stage = getString(R.string.library_scan_stage_complete), progress = 1f
            )
            LibraryScanEventBus.updateState(doneState)
            updateNotification(doneState)
            AppLogger.d(TAG, "Foreground scan completed")
            val deferredReason = pendingScanReason
            if (!deferredReason.isNullOrBlank()) {
                pendingScanReason = null
                scanJob = null
                startScanIfNeeded(deferredReason)
                return
            }
            stopSelf()
        } catch (e: CancellationException) {
            val cancelledState = cancelledState()
            LibraryScanEventBus.updateState(cancelledState)
            updateNotification(cancelledState)
            AppLogger.d(TAG, "Foreground scan cancelled")
            stopSelf()
        } catch (e: Exception) {
            val errorState = LibraryScanEventBus.scanState.value.copy(
                isScanning = false,
                canCancel = false,
                stage = getString(R.string.library_scan_stage_error),
                error = e.message,
                message = getString(
                    R.string.library_scan_failed_detail,
                    localizedErrorDetail(e.message),
                ),
            )
            LibraryScanEventBus.updateState(errorState)
            updateNotification(errorState)
            AppLogger.e(TAG, "Foreground scan failed", e)
            stopSelf()
        }
    }

    private fun cancelScan() {
        pendingScanReason = null
        scanJob?.cancel(CancellationException("User cancelled"))
        scanJob = null
        LibraryScanEventBus.updateState(cancelledState())
        stopSelf()
    }

    private fun reduceEventToState(reason: String, current: ScanUiState, event: LibraryScanCoordinator.Event): ScanUiState {
        val displayReason = localizedReason(reason)
        return when (event) {
            is LibraryScanCoordinator.Event.ScannerEvent -> reduceScannerEvent(displayReason, current, event.event)
            is LibraryScanCoordinator.Event.DatabaseSyncStarted -> {
                val stageText = when (event.phase) {
                    LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> getString(R.string.library_scan_stage_quick_write)
                    LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> getString(R.string.library_scan_stage_background_enrich)
                    LibraryScanCoordinator.SyncPhase.FINAL -> getString(R.string.library_scan_stage_final_sync)
                }
                current.copy(
                    isScanning = true, canCancel = true, stage = stageText,
                    progress = if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) 0.90f else current.progress,
                    message = getString(
                        R.string.library_scan_sync_started,
                        displayReason,
                        stageText,
                        event.newCount,
                    )
                )
            }
            is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                val message = when (event.phase) {
                    LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> getString(
                        R.string.library_scan_quick_visible,
                        displayReason,
                        event.upserted,
                    )
                    LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> getString(
                        R.string.library_scan_enriched_written,
                        displayReason,
                        event.upserted,
                    )
                    LibraryScanCoordinator.SyncPhase.FINAL -> getString(
                        R.string.library_scan_database_synced,
                        displayReason,
                        event.added,
                        event.updated,
                        event.deleted,
                        event.unchanged,
                    )
                }
                current.copy(
                    isScanning = true, canCancel = true,
                    stage = getString(R.string.library_scan_stage_database_complete),
                    progress = if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) 0.96f else current.progress,
                    dbUpserted = event.upserted, dbDeleted = event.deleted, dbUnchanged = event.unchanged,
                    message = message
                )
            }
            is LibraryScanCoordinator.Event.VisibleCompleted -> current.copy(
                isScanning = true, canCancel = true, pendingScan = false,
                stage = getString(R.string.library_scan_stage_visible),
                progress = current.progress.coerceAtLeast(0.40f), found = event.found, timeMs = event.timeMs,
                message = getString(R.string.library_scan_visible_message, displayReason, event.found)
            )
            is LibraryScanCoordinator.Event.Completed -> current.copy(
                isScanning = false, canCancel = false, pendingScan = false,
                stage = getString(R.string.library_scan_stage_complete),
                progress = 1f, found = event.songs.size, timeMs = event.timeMs,
                message = getString(
                    R.string.library_scan_completed_message,
                    displayReason,
                    event.songs.size,
                    event.timeMs,
                )
            )
            is LibraryScanCoordinator.Event.Error -> current.copy(
                isScanning = false, canCancel = false,
                stage = getString(R.string.library_scan_stage_error),
                error = event.message,
                message = getString(
                    R.string.library_scan_reason_failed,
                    displayReason,
                    localizedErrorDetail(event.message),
                )
            )
        }
    }

    private fun reduceScannerEvent(reason: String, current: ScanUiState, event: TwoStageMediaScanner.Event): ScanUiState {
        return when (event) {
            is TwoStageMediaScanner.Event.Started -> current.copy(
                isScanning = true, canCancel = true, reason = reason,
                stage = getString(R.string.library_scan_stage_start),
                total = event.totalEstimated, scanned = 0, progress = 0f,
                message = getString(R.string.library_scan_started_message, reason, event.totalEstimated)
            )
            is TwoStageMediaScanner.Event.CacheLoaded -> current.copy(
                stage = getString(R.string.library_scan_stage_cache),
                message = getString(R.string.library_scan_cache_loaded, reason, event.cachedCount)
            )
            is TwoStageMediaScanner.Event.QuickProgress -> {
                val p = if (event.total > 0) event.scanned.toFloat() / event.total * 0.35f else 0f
                current.copy(isScanning = true, canCancel = true,
                    stage = getString(R.string.library_scan_stage_quick),
                    scanned = event.scanned, total = event.total, progress = p.coerceIn(0f, 0.35f),
                    message = getString(
                        R.string.library_scan_quick_progress,
                        reason,
                        event.scanned,
                        event.total,
                    ))
            }
            is TwoStageMediaScanner.Event.QuickCompleted -> current.copy(
                isScanning = true, canCancel = true,
                stage = getString(R.string.library_scan_stage_quick_complete),
                found = event.found, progress = 0.35f,
                message = getString(R.string.library_scan_quick_completed, reason, event.found)
            )
            is TwoStageMediaScanner.Event.EnrichProgress -> {
                val ep = if (event.total > 0) event.processed.toFloat() / event.total else 0f
                current.copy(isScanning = true, canCancel = true,
                    stage = getString(R.string.library_scan_stage_enrich),
                    scanned = event.processed, total = event.total,
                    progress = (0.35f + ep * 0.55f).coerceIn(0.35f, 0.90f),
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = getString(
                        R.string.library_scan_enrich_progress,
                        reason,
                        event.processed,
                        event.total,
                        event.cacheHits,
                        event.enrichedCount,
                    ))
            }
            is TwoStageMediaScanner.Event.SongEnriched -> current
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> current
            is TwoStageMediaScanner.Event.FullyCompleted -> current.copy(
                isScanning = true, canCancel = true,
                stage = getString(R.string.library_scan_stage_prepare_sync), progress = 0.90f,
                found = event.found, cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                message = getString(
                    R.string.library_scan_enrich_completed,
                    reason,
                    event.found,
                    event.cacheHits,
                    event.enrichedCount,
                )
            )
            is TwoStageMediaScanner.Event.Error -> current.copy(
                isScanning = false, canCancel = false,
                stage = getString(R.string.library_scan_stage_error),
                error = event.message,
                message = getString(
                    R.string.library_scan_reason_failed,
                    reason,
                    localizedErrorDetail(event.message),
                )
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
            .setContentTitle(getString(R.string.library_scan_notification_title))
            .setContentText(state.message.ifBlank { getString(R.string.library_scan_notification_text) })
            .setOnlyAlertOnce(true)
            .setOngoing(state.isScanning)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.isScanning) {
            builder.setProgress(100, state.progressPercent, state.total <= 0)
            if (state.canCancel) {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.common_cancel),
                    cancelPending
                )
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.library_scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.library_scan_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun cancelledState(): ScanUiState = ScanUiState.cancelled(
        stage = getString(R.string.library_scan_stage_cancelled),
        message = getString(R.string.library_scan_cancelled),
    )

    private fun localizedReason(reason: String): String = when (reason) {
        LibraryScanServiceActions.REASON_MANUAL -> getString(R.string.library_scan_reason_manual)
        LibraryScanServiceActions.REASON_AUTO -> getString(R.string.library_scan_reason_auto)
        else -> reason
    }

    private fun localizedErrorDetail(rawMessage: String?): String {
        val raw = rawMessage?.trim().orEmpty()
        if (raw.isBlank()) return getString(R.string.library_scan_unknown_error)
        if (resources.configuration.locales[0].language == "zh") return raw
        val containsHan = raw.any { ch ->
            Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
        }
        return if (containsHan) getString(R.string.library_scan_unknown_error) else raw
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
