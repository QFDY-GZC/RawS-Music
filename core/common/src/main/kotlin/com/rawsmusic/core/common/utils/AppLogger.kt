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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AppLogger {

    private const val MAX_LOG_SIZE = 500 * 1024L
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "rawsmusic.log"
    private const val PLAYBACK_REPORT_TAG = "PlaybackReport"
    private const val PLAYBACK_REPORT_START = "PLAYBACK_REPORT_START"
    private const val LOG_BUFFER_SIZE = 16 * 1024
    private const val FLUSH_INTERVAL_MS = 1_000L
    private const val LOG_QUEUE_CAPACITY = 2_048
    private const val LOG_DRAIN_TIMEOUT_MS = 2_000L

    private sealed interface FileOperation {
        data class Write(
            val timestampMs: Long,
            val level: String,
            val tag: String,
            val message: String,
            val throwable: Throwable?,
        ) : FileOperation

        data class Barrier(val latch: CountDownLatch) : FileOperation
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var lastFlushAtMs = 0L

    private val lock = Any()
    private val fileOperations = LinkedBlockingDeque<FileOperation>(LOG_QUEUE_CAPACITY)
    private val writerThreadStarted = AtomicBoolean(false)
    private val droppedFileWrites = AtomicInteger(0)

    fun init() {
        try {
            val context = CoreInit.getApp()
            UsbIncidentArchive.onProcessStart(context)
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, LOG_FILE)
            writer = newWriter(logFile)
            lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
            trimLogFile()
            ensureWriterThread()
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
        awaitPendingFileWrites()
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
        awaitPendingFileWrites()
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
        if (writer == null) return
        ensureWriterThread()
        val operation = FileOperation.Write(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            throwable = throwable,
        )
        if (!fileOperations.offerLast(operation)) {
            // Debug storms must never block Main/Render/audio threads. Retain the newest context
            // and emit one explicit loss marker from the file writer when it catches up.
            fileOperations.pollFirst()
            droppedFileWrites.incrementAndGet()
            fileOperations.offerLast(operation)
        }
    }

    private fun ensureWriterThread() {
        if (!writerThreadStarted.compareAndSet(false, true)) return
        Thread(
            {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                }
                while (true) {
                    try {
                        when (val operation = fileOperations.takeFirst()) {
                            is FileOperation.Write -> writeOperation(operation)
                            is FileOperation.Barrier -> {
                                try {
                                    synchronized(lock) {
                                        writer?.flush()
                                        lastFlushAtMs = android.os.SystemClock.elapsedRealtime()
                                    }
                                } finally {
                                    operation.latch.countDown()
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        // Logging is process-scoped. Ignore incidental interrupts and keep the
                        // single ordered writer alive until Android terminates the process.
                    } catch (_: Throwable) {
                    }
                }
            },
            "RawS Log Writer",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun awaitPendingFileWrites() {
        if (!writerThreadStarted.get()) return
        val latch = CountDownLatch(1)
        try {
            // Export/clear are explicit, rare operations and may wait for queue capacity. Normal
            // logging always uses the non-blocking offer path above.
            if (fileOperations.offerLast(FileOperation.Barrier(latch), LOG_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                latch.await(LOG_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun writeOperation(operation: FileOperation.Write) {
        synchronized(lock) {
            try {
                val w = writer ?: return
                val dropped = droppedFileWrites.getAndSet(0)
                if (dropped > 0) {
                    val timestamp = dateFormat.format(Date(operation.timestampMs))
                    w.append("[$timestamp] W/AppLogger: dropped $dropped queued file log entries\n")
                }
                val timestamp = dateFormat.format(Date(operation.timestampMs))
                w.append("[$timestamp] ${operation.level}/${operation.tag}: ${operation.message}\n")
                if (operation.throwable != null) {
                    val pw = PrintWriter(w)
                    operation.throwable.printStackTrace(pw)
                    pw.flush()
                    w.append("\n")
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (operation.level == "E" || operation.level == "W" || now - lastFlushAtMs >= FLUSH_INTERVAL_MS) {
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
