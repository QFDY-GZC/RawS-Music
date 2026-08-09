package com.rawsmusic.source

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.scanner.ScanScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges public online downloads into RawSMusic's local library.
 *
 * DownloadManager owns the transfer. Once it reports completion, the data controller publishes the
 * file to MediaStore and sends [MusicSourceDownloadController.ACTION_DOWNLOAD_MEDIA_READY]. This
 * receiver then starts the same persistent-cache scan used by manual library refreshes.
 */
class OnlineSourceDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (systemId < 0L) return
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        MusicSourceDownloadController.handleSystemDownloadCompleted(context, systemId)
                    } catch (error: Throwable) {
                        AppLogger.e(TAG, "download completion reconciliation failed id=$systemId", error)
                    } finally {
                        pending.finish()
                    }
                }
            }

            MusicSourceDownloadController.ACTION_DOWNLOAD_MEDIA_READY -> {
                val path = intent.getStringExtra(MusicSourceDownloadController.EXTRA_DOWNLOAD_PATH).orEmpty()
                AppLogger.i(TAG, "online download ready, request incremental scan path=$path")
                ScanScheduler.requestDirScan(context.applicationContext, "在线下载完成")
            }
        }
    }

    private companion object {
        const val TAG = "OnlineSourceDownload"
    }
}
