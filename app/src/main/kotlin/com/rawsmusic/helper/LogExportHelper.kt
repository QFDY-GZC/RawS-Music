package com.rawsmusic.helper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File

class LogExportHelper(
    private val context: Context
) {
    fun createExportFileName(): String {
        return AppLogger.generateExportFileName()
    }

    fun exportTo(uri: Uri) {
        try {
            val logContent = AppLogger.getLogContent()
            if (logContent.isNullOrBlank()) {
                Toast.makeText(context, context.getString(com.rawsmusic.R.string.logs_empty), Toast.LENGTH_SHORT).show()
                return
            }

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(logContent.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, context.getString(com.rawsmusic.R.string.logs_exported), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(com.rawsmusic.R.string.logs_export_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    fun shareCurrentLog() {
        try {
            val logContent = AppLogger.getLogContent()
            val file = AppLogger.getLogFile()
            if (logContent.isNullOrBlank() || file == null || !file.exists()) {
                Toast.makeText(context, context.getString(com.rawsmusic.R.string.logs_empty), Toast.LENGTH_SHORT).show()
                return
            }

            shareFile(file, context.getString(com.rawsmusic.R.string.ui_log_share_title))
        } catch (error: Exception) {
            Toast.makeText(
                context,
                context.getString(com.rawsmusic.R.string.logs_export_failed, error.message.orEmpty()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun shareSuperIslandLog() {
        try {
            val content = AppLogger.getLogContent().orEmpty()
            val markers = listOf("RawSuperIsland", "RawSuperIslandService", "RawXmsf", "XiaomiSuperIsland")
            val lines = content.lineSequence()
                .filter { line -> markers.any(line::contains) }
                .toList()
            if (lines.isEmpty()) {
                Toast.makeText(context, context.getString(com.rawsmusic.R.string.logs_empty), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(
                context,
                context.getString(com.rawsmusic.R.string.logs_export_failed, error.message.orEmpty()),
                Toast.LENGTH_SHORT
            ).show()
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
