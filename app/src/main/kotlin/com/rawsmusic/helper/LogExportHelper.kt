package com.rawsmusic.helper

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.rawsmusic.core.common.utils.AppLogger

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
}
