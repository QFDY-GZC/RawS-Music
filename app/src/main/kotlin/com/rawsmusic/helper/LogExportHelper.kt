package com.rawsmusic.helper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.UsbIncidentArchive
import java.io.File

class LogExportHelper(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun exportContent(): String = UsbIncidentArchive.describePersistedLog(AppLogger.getLogContent().orEmpty()) +
        "\n=== PERSISTED USB INCIDENTS / REBOOT EVIDENCE ===\n" + UsbIncidentArchive.export(context)

    fun createShareFile(): File = File(context.cacheDir, createExportFileName()).apply {
        writeText(exportContent(), Charsets.UTF_8)
    }
    fun createExportFileName(): String {
        return AppLogger.generateExportFileName()
    }

    fun exportTo(uri: Uri) {
        Thread({
            try {
                val rootEvidence = UsbIncidentArchive.refreshRootEvidenceForExport(context)
                AppLogger.i("LogExportHelper", "Reboot evidence refresh before export: $rootEvidence")
                val logContent = exportContent()
                if (logContent.isBlank()) {
                    mainHandler.post {
                        AppNoticeBus.error(context.getString(com.rawsmusic.R.string.logs_empty))
                    }
                    return@Thread
                }

                val wrote = context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(logContent.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
                mainHandler.post {
                    if (wrote) {
                        AppNoticeBus.post(
                            message = context.getString(com.rawsmusic.R.string.logs_exported),
                            icon = AppNoticeIcon.DOWNLOAD,
                        )
                    } else {
                        AppNoticeBus.error(
                            context.getString(
                                com.rawsmusic.R.string.logs_export_failed,
                                "openOutputStream returned null",
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    AppNoticeBus.error(
                        context.getString(com.rawsmusic.R.string.logs_export_failed, e.message.orEmpty())
                    )
                }
            }
        }, "raw-log-export").apply { isDaemon = true; start() }
    }

    fun shareCurrentLog() {
        Thread({
            try {
                val rootEvidence = UsbIncidentArchive.refreshRootEvidenceForExport(context)
                AppLogger.i("LogExportHelper", "Reboot evidence refresh before share: $rootEvidence")
                val file = createShareFile()
                mainHandler.post {
                    runCatching {
                        shareFile(file, context.getString(com.rawsmusic.R.string.ui_log_share_title))
                    }.onFailure { error ->
                        AppNoticeBus.error(
                            context.getString(
                                com.rawsmusic.R.string.logs_export_failed,
                                error.message.orEmpty(),
                            )
                        )
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    AppNoticeBus.error(
                        context.getString(
                            com.rawsmusic.R.string.logs_export_failed,
                            error.message.orEmpty(),
                        )
                    )
                }
            }
        }, "raw-log-share").apply { isDaemon = true; start() }
    }

    fun shareSuperIslandLog() {
        try {
            val content = AppLogger.getLogContent().orEmpty()
            val markers = listOf("RawSuperIsland", "RawSuperIslandService", "RawXmsf", "XiaomiSuperIsland")
            val lines = content.lineSequence()
                .filter { line -> markers.any(line::contains) }
                .toList()
            if (lines.isEmpty()) {
                AppNoticeBus.error(context.getString(com.rawsmusic.R.string.logs_empty))
                return
            }

            val export = File(
                context.cacheDir,
                "RawSMusic_SuperIsland_${System.currentTimeMillis()}.log"
            ).apply {
                parentFile?.mkdirs()
                writeText(
                    "RawS Music Super Island diagnostics\n" +
                        "Generated at ${System.currentTimeMillis()}\n\n" +
                        lines.joinToString("\n") + "\n",
                    Charsets.UTF_8
                )
            }
            shareFile(export, context.getString(com.rawsmusic.R.string.settings_xiaomi_super_island_export_log))
        } catch (error: Exception) {
            AppNoticeBus.error(
                context.getString(com.rawsmusic.R.string.logs_export_failed, error.message.orEmpty())
            )
        }
    }

    private fun shareFile(file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }
}
