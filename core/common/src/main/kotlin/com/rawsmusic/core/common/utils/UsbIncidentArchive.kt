package com.rawsmusic.core.common.utils

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream

/** Preserve the previous process before startup logging/rotation can overwrite evidence. */
object UsbIncidentArchive {
    private const val LIMIT = 1024 * 1024

    /** Low-frequency control decisions only; never called by PCM/ISO callbacks. */
    @Synchronized
    fun recordTransportDecision(context: Context, message: String) = runCatching {
        val file = File(context.filesDir, "usb_transport_decision.txt")
        durableWrite(file, "timeMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.elapsedRealtime()}\n$message\n")
    }

    fun onProcessStart(context: Context) = runCatching {
        val root = File(context.filesDir, "usb_incidents").apply { mkdirs() }
        val marker = File(root, "current-session.txt")
        val bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")
        val previous = marker.takeIf { it.isFile }?.readText().orEmpty()
        val native = File(context.filesDir, "usb_native_breadcrumb.log")
        val rotated = File(context.filesDir, "usb_native_breadcrumb.log.1")
        if (previous.isNotBlank() || native.exists()) {
            val destination = File(root, "session-${System.currentTimeMillis()}").apply { mkdirs() }
            val oldBoot = previous.lineSequence().firstOrNull { it.startsWith("bootId=") }?.substringAfter('=')
            val classification = when {
                oldBoot == null || oldBoot == "unavailable" || bootId == "unavailable" -> "UNKNOWN"
                oldBoot != bootId -> "DEVICE_REBOOT_BETWEEN_STARTS"
                else -> "PROCESS_RESTART_SAME_BOOT"
            }
            durableWrite(File(destination, "session.txt"), previous +
                "\nNextStartClassification=$classification\n" +
                "This identifies a restart, not its cause; normal shutdown and force-stop are also possible.\n")
            listOf(File(context.filesDir, "logs/rawsmusic.log"),
                File(context.filesDir, "usb_transport_decision.txt"), rotated, native).forEach { source ->
                if (source.isFile) {
                    durableWrite(File(destination, source.name), readTail(source))
                    // Only clear native breadcrumbs after the durable archive exists.
                    if (source == native || source == rotated) source.delete()
                }
            }
            if (classification == "DEVICE_REBOOT_BETWEEN_STARTS") {
                // Keep reboot evidence separately from the three rotating process archives.
                val evidence = File(root, "last-reboot").apply { mkdirs() }
                // This directory is intentionally stable so the exporter can always find the
                // latest reboot. Never let rooted evidence from an older reboot survive into a
                // newly detected boot boundary before the user has had a chance to authorize su.
                File(evidence, "boot-evidence-root.txt").delete()
                durableWrite(File(evidence, "session.txt"), previous + "\nnewBootId=$bootId\n")
                durableWrite(File(evidence, "usb_native_breadcrumb.log"),
                    File(destination, "usb_native_breadcrumb.log").takeIf { it.exists() }?.let(::readTail).orEmpty())
                durableWrite(File(evidence, "usb_transport_decision.txt"),
                    File(destination, "usb_transport_decision.txt").takeIf { it.exists() }?.let(::readTail).orEmpty())
                durableWrite(File(evidence, "boot-evidence.txt"), "Collection pending for bootId=$bootId\n")
                UsbBootEvidence.collectAsync(context, evidence)
            }
        }
        durableWrite(marker, "bootId=$bootId\nstartedAtMs=${System.currentTimeMillis()}\n" +
            "uptimeMs=${SystemClock.elapsedRealtime()}\npid=${android.os.Process.myPid()}\n")
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("session-") }
            ?.sortedByDescending { it.name }?.drop(3)?.forEach { it.deleteRecursively() }
    }

    /**
     * Refresh previous-boot evidence with root privileges during an explicit log export.
     * No root request is made when there is no archived cross-boot incident.
     */
    fun refreshRootEvidenceForExport(context: Context): String {
        val evidence = File(context.filesDir, "usb_incidents/last-reboot")
        val session = File(evidence, "session.txt")
        if (!session.isFile) return "no archived device reboot; root evidence collection skipped"
        return UsbBootEvidence.collectRootEvidenceForExport(evidence)
    }

    fun export(context: Context): String = buildString {
        val root = File(context.filesDir, "usb_incidents")
        val files = buildList {
            add(File(root, "current-session.txt"))
            add(File(context.filesDir, "usb_transport_decision.txt"))
            addAll(File(root, "last-reboot").listFiles()?.filter {
                it.isFile && it.extension in setOf("txt", "log")
            }?.sortedBy { it.name }.orEmpty())
            add(File(context.filesDir, "usb_native_breadcrumb.log.1"))
            add(File(context.filesDir, "usb_native_breadcrumb.log"))
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("session-") }
                ?.sortedByDescending { it.name }?.take(3)?.forEach { dir ->
                    addAll(dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty())
                }
        }
        files.filter { it.isFile }.forEach { file ->
            appendLine("=== ${file.parentFile?.name}/${file.name} ===")
            appendLine(runCatching { readTail(file) }.getOrElse { "Read failed: ${it.javaClass.simpleName}" })
        }
    }

    private fun readTail(file: File): String = java.io.RandomAccessFile(file, "r").use { input ->
        val limit = if (file.name == "boot-evidence.txt") 4L * LIMIT else LIMIT.toLong()
        val size = input.length().coerceAtMost(limit).toInt()
        input.seek((input.length() - size).coerceAtLeast(0))
        val bytes = ByteArray(size)
        input.readFully(bytes)
        describePersistedLog(bytes.toString(Charsets.UTF_8))
    }

    fun describePersistedLog(decoded: String): String {
        val nulCount = decoded.count { it == '\u0000' }
        return if (nulCount == 0) decoded else
            "[WARNING: $nulCount NUL bytes in persisted log; content lost/unavailable, not evidence of successful execution]\n" +
                decoded.replace("\u0000", "")
    }

    private fun durableWrite(file: File, text: String) {
        FileOutputStream(file).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }
}
