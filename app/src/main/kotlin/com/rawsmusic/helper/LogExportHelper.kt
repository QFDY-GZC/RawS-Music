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
                Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                return
            }

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(logContent.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
