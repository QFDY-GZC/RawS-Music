package com.rawsmusic.core.common.utils

import android.util.Log
import com.rawsmusic.core.common.CoreInit
import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val MAX_LOG_SIZE = 500 * 1024L
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "rawsmusic.log"
    private const val PLAYBACK_REPORT_TAG = "PlaybackReport"
    private const val PLAYBACK_REPORT_START = "PLAYBACK_REPORT_START"
    private const val LOG_BUFFER_SIZE = 16 * 1024
    private const val FLUSH_INTERVAL_MS = 1_000L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var lastFlushAtMs = 0L

    private val lock = Any()

    fun init() {
        try {
            val context = CoreInit.getApp()
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, LOG_FILE)
            writer = newWriter(logFile)
            lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
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
        return synchronized(lock) {
            try {
                writer?.flush()
                lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
                logFile?.readText()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        synchronized(lock) {
            try {
                writer?.close()
                writer = null
                logFile?.writeText("")
                writer = newWriter(logFile)
                lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
            } catch (_: Exception) {}
        }
    }

    fun markPlaybackReportStart(
        title: String?,
        artist: String?,
        album: String?,
        path: String?,
        cueOffsetMs: Long = 0L
    ) {
        val message = buildString {
            append(PLAYBACK_REPORT_START)
            append(" title=")
            append(safeLogField(title))
            append(" artist=")
            append(safeLogField(artist))
            append(" album=")
            append(safeLogField(album))
            append(" cueOffsetMs=")
            append(cueOffsetMs)
            append(" path=")
            append(safeLogField(path))
        }
        i(PLAYBACK_REPORT_TAG, message)
    }

    fun getPlaybackReportContent(): String? {
        val content = getLogContent() ?: return null
        val marker = "/$PLAYBACK_REPORT_TAG: $PLAYBACK_REPORT_START"
        val markerIndex = content.lastIndexOf(marker)
        if (markerIndex < 0) return content
        val startIndex = content.lastIndexOf('\n', markerIndex).let { if (it >= 0) it + 1 else 0 }
        return content.substring(startIndex)
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
                val now = android.os.SystemClock.elapsedRealtime()
                if (level == "E" || level == "W" || now - lastFlushAtMs >= FLUSH_INTERVAL_MS) {
                    w.flush()
                    lastFlushAtMs = now
                    trimLogFile()
                }
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
                writer = newWriter(file)
                lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
            }
        } catch (_: Exception) {}
    }

    private fun newWriter(file: File?): BufferedWriter? {
        return file?.let { BufferedWriter(FileWriter(it, true), LOG_BUFFER_SIZE) }
    }

    fun generateExportFileName(): String {
        return "RawSMusic_${fileDateFormat.format(Date())}.log"
    }

    private fun safeLogField(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        return value.replace('\n', ' ').replace('\r', ' ')
    }
}
