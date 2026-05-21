package com.rawsmusic.core.common.utils

import android.util.Log
import com.rawsmusic.core.common.CoreInit
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val MAX_LOG_SIZE = 500 * 1024L
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "rawsmusic.log"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var writer: FileWriter? = null

    private val lock = Any()

    fun init() {
        try {
            val context = CoreInit.getApp()
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, LOG_FILE)
            writer = FileWriter(logFile, true)
            trimLogFile()
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writeToFile("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeToFile("I", tag, msg)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        writeToFile("W", tag, msg, throwable)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        writeToFile("E", tag, msg, throwable)
    }

    fun getLogContent(): String? {
        return try {
            logFile?.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        synchronized(lock) {
            try {
                writer?.close()
                writer = null
                logFile?.writeText("")
                writer = FileWriter(logFile, true)
            } catch (_: Exception) {}
        }
    }

    private fun writeToFile(level: String, tag: String, msg: String, throwable: Throwable? = null) {
        synchronized(lock) {
            try {
                val w = writer ?: return
                val timestamp = dateFormat.format(Date())
                w.append("[$timestamp] $level/$tag: $msg\n")
                if (throwable != null) {
                    val pw = PrintWriter(w)
                    throwable.printStackTrace(pw)
                    pw.flush()
                    w.append("\n")
                }
                w.flush()
                trimLogFile()
            } catch (_: Exception) {}
        }
    }

    private fun trimLogFile() {
        try {
            val file = logFile ?: return
            if (file.length() > MAX_LOG_SIZE) {
                writer?.close()
                writer = null
                val content = file.readText()
                val keepLength = content.length / 2
                val cutIndex = content.indexOf('\n', content.length - keepLength)
                val trimmed = if (cutIndex >= 0) content.substring(cutIndex + 1) else content
                file.writeText(trimmed)
                writer = FileWriter(file, true)
            }
        } catch (_: Exception) {}
    }

    fun generateExportFileName(): String {
        return "RawSMusic_${fileDateFormat.format(Date())}.log"
    }
}
